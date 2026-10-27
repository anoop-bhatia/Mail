package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.junit.Test;

public class TransactionScopeTest {
    private static final long TIMEOUT_SECONDS = 5;

    // Model the installed Room 2.8.5 compatibility runner: begin is outside
    // its try/finally, while commit and the callback are inside. These tests
    // exercise the production coordinator, not Android SQLite or Room itself.
    private static final class FakeRoom {
        final List<String> trace = new ArrayList<>();
        Throwable beginFailure;
        Throwable commitFailure;
        Throwable endFailure;
        boolean swallowKnownEndFailure;

        void begin() {
            trace.add("begin");
            raise(beginFailure);
        }

        void commit() {
            trace.add("commit");
            raise(commitFailure);
        }

        void end() {
            trace.add("end");
            if (swallowKnownEndFailure && endFailure != null &&
                    (endFailure.getMessage().contains("no current transaction") ||
                            endFailure.getMessage().contains("no transaction is active"))) {
                trace.add("logged-end");
                return;
            }
            raise(endFailure);
        }

        void run(Runnable callback) {
            begin();
            try {
                callback.run();
                commit();
            } finally {
                end();
            }
        }
    }

    private static void raise(Throwable failure) {
        if (failure instanceof RuntimeException)
            throw (RuntimeException) failure;
        if (failure instanceof Error)
            throw (Error) failure;
        if (failure != null)
            throw new AssertionError("test model only accepts unchecked failures", failure);
    }

    /** A small state model: writes are staged and become visible only when a successful frame ends. */
    private static final class StatefulRoom {
        private static final class Frame {
            final List<String> writes = new ArrayList<>();
            boolean successful;
            boolean poisoned;
        }

        final List<String> committed = new ArrayList<>();
        final List<String> trace = new ArrayList<>();
        final Deque<Frame> staged = new ArrayDeque<>();
        RuntimeException beginFailure;
        RuntimeException commitFailure;

        void begin() {
            trace.add("begin");
            staged.push(new Frame());
            raise(beginFailure);
        }

        void write(String value) {
            if (staged.isEmpty())
                throw new AssertionError("write outside transaction");
            trace.add("write:" + value);
            staged.peek().writes.add(value);
        }

        void commit() {
            trace.add("commit");
            raise(commitFailure);
            if (!staged.peek().poisoned)
                staged.peek().successful = true;
        }

        void end() {
            trace.add("end");
            if (staged.isEmpty())
                return;
            Frame frame = staged.pop();
            if (frame.successful) {
                if (staged.isEmpty())
                    committed.addAll(frame.writes);
                else
                    staged.peek().writes.addAll(frame.writes);
            } else if (!staged.isEmpty()) {
                // A failed nested scope poisons its parent, even if its failure is caught.
                staged.peek().poisoned = true;
            }
        }

        void run(Runnable callback) {
            begin();
            try {
                callback.run();
                commit();
            } finally {
                end();
            }
        }
    }

    private static Throwable outcome(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable ex) {
            return ex;
        }
    }

    private static void legacy(FakeRoom room, Runnable body) {
        try {
            room.begin();
            body.run();
            room.commit();
        } finally {
            room.end();
        }
    }

    private static void legacy(StatefulRoom room, Runnable body) {
        try {
            room.begin();
            body.run();
            room.commit();
        } finally {
            room.end();
        }
    }

    private static FakeRoom assertEquivalent(Consumer<FakeRoom> configure,
                                            Consumer<FakeRoom> body,
                                            Throwable expected) {
        FakeRoom oldRoom = new FakeRoom();
        FakeRoom newRoom = new FakeRoom();
        configure.accept(oldRoom);
        configure.accept(newRoom);
        Throwable oldFailure = outcome(() -> legacy(oldRoom, () -> body.accept(oldRoom)));
        Throwable newFailure = outcome(() -> TransactionScope.run(
                newRoom::run, newRoom::end, () -> body.accept(newRoom)));
        assertSame(expected, oldFailure);
        assertSame(oldFailure, newFailure);
        assertEquals(oldRoom.trace, newRoom.trace);
        return newRoom;
    }

    @Test
    public void successMatchesExplicitLegacyTrace() {
        FakeRoom room = assertEquivalent(r -> { }, r -> r.trace.add("body"), null);
        assertEquals(Arrays.asList("begin", "body", "commit", "end"), room.trace);
    }

    @Test
    public void bodyRuntimeExceptionPreservesIdentityAndEndsOnce() {
        RuntimeException failure = new IllegalStateException("body");
        FakeRoom room = assertEquivalent(r -> { }, r -> { throw failure; }, failure);
        assertEquals(Arrays.asList("begin", "end"), room.trace);
    }

    @Test
    public void bodyErrorPreservesIdentityAndEndsOnce() {
        Error failure = new AssertionError("body");
        assertEquivalent(r -> { }, r -> { throw failure; }, failure);
    }

    @Test
    public void beginRuntimeExceptionRunsLegacyCleanup() {
        RuntimeException failure = new IllegalStateException("begin");
        FakeRoom room = assertEquivalent(r -> r.beginFailure = failure,
                r -> fail("body must not run"), failure);
        assertEquals(Arrays.asList("begin", "end"), room.trace);
    }

    @Test
    public void beginErrorRunsLegacyCleanup() {
        Error failure = new AssertionError("begin");
        assertEquivalent(r -> r.beginFailure = failure,
                r -> fail("body must not run"), failure);
    }

    @Test
    public void cleanupFailureMasksBeginFailureLikeLegacyFinally() {
        RuntimeException begin = new IllegalStateException("begin");
        Error cleanup = new AssertionError("cleanup");
        assertEquivalent(r -> {
            r.beginFailure = begin;
            r.endFailure = cleanup;
        }, r -> fail("body must not run"), cleanup);
    }

    @Test
    public void commitFailureRollsBackAndIsNotRetried() {
        RuntimeException failure = new IllegalStateException("commit");
        FakeRoom room = assertEquivalent(r -> r.commitFailure = failure,
                r -> r.trace.add("body"), failure);
        assertEquals(Arrays.asList("begin", "body", "commit", "end"), room.trace);
    }

    @Test
    public void endFailureMasksBodyFailureWithoutSecondCleanup() {
        RuntimeException body = new IllegalStateException("body");
        RuntimeException end = new IllegalStateException("end");
        FakeRoom room = assertEquivalent(r -> r.endFailure = end,
                r -> { throw body; }, end);
        assertEquals(Arrays.asList("begin", "end"), room.trace);
    }

    @Test
    public void endErrorAfterSuccessIsNotRetried() {
        Error end = new AssertionError("end");
        FakeRoom room = assertEquivalent(r -> r.endFailure = end,
                r -> r.trace.add("body"), end);
        assertEquals(Arrays.asList("begin", "body", "commit", "end"), room.trace);
    }

    @Test
    public void simulatedKnownDeviceEndFailuresStaySwallowedOnce() {
        for (String message : Arrays.asList("no current transaction", "no transaction is active")) {
            RuntimeException end = new IllegalStateException(message);
            FakeRoom room = assertEquivalent(r -> {
                r.endFailure = end;
                r.swallowKnownEndFailure = true;
            }, r -> r.trace.add("body"), null);
            assertEquals(Arrays.asList("begin", "body", "commit", "end", "logged-end"), room.trace);
        }
    }

    @Test
    public void swallowedEndFailureDoesNotHideBeginOrBodyFailure() {
        RuntimeException original = new IllegalStateException("original");
        RuntimeException end = new IllegalStateException("no current transaction");
        for (boolean failBegin : Arrays.asList(false, true)) {
            assertEquivalent(r -> {
                r.beginFailure = failBegin ? original : null;
                r.endFailure = end;
                r.swallowKnownEndFailure = true;
            }, r -> { throw original; }, original);
        }
    }

    @Test
    public void nestedTransactionsKeepIndependentEntryState() {
        FakeRoom oldRoom = new FakeRoom();
        FakeRoom newRoom = new FakeRoom();
        legacy(oldRoom, () -> legacy(oldRoom, () -> oldRoom.trace.add("inner-body")));
        TransactionScope.run(newRoom::run, newRoom::end, () ->
                TransactionScope.run(newRoom::run, newRoom::end, () -> newRoom.trace.add("inner-body")));
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(Arrays.asList("begin", "begin", "inner-body", "commit", "end", "commit", "end"), newRoom.trace);
    }

    @Test
    public void nestedBeginFailureCleansInnerThenOuterExactlyOnce() {
        RuntimeException failure = new IllegalStateException("inner begin");
        FakeRoom oldRoom = new FakeRoom();
        FakeRoom newRoom = new FakeRoom();
        Throwable oldFailure = outcome(() -> legacy(oldRoom, () -> {
            oldRoom.beginFailure = failure;
            legacy(oldRoom, () -> fail("inner body"));
        }));
        Throwable newFailure = outcome(() -> TransactionScope.run(newRoom::run, newRoom::end, () -> {
            newRoom.beginFailure = failure;
            TransactionScope.run(newRoom::run, newRoom::end, () -> fail("inner body"));
        }));
        assertSame(failure, oldFailure);
        assertSame(oldFailure, newFailure);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(Arrays.asList("begin", "begin", "end", "end"), newRoom.trace);
    }

    @Test
    public void sequentialCallsDoNotLeakEntryState() {
        FakeRoom room = new FakeRoom();
        TransactionScope.run(room::run, room::end, () -> room.trace.add("body"));
        RuntimeException begin = new IllegalStateException("second begin");
        room.beginFailure = begin;
        assertSame(begin, outcome(() -> TransactionScope.run(room::run, room::end, () -> fail("body"))));
        assertEquals(Arrays.asList("begin", "body", "commit", "end", "begin", "end"), room.trace);
    }

    @Test
    public void concurrentCallsDoNotShareEntryState() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<Throwable> first = executor.submit(() -> outcome(() ->
                    TransactionScope.run(Runnable::run, () -> fail("entered cleanup"), () -> {
                        entered.countDown();
                        try {
                            if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                                throw new AssertionError("release timeout");
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(ex);
                        }
                    })));
            assertTrue(entered.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            Future<Throwable> second = executor.submit(() -> {
                RuntimeException begin = new IllegalStateException("other begin");
                int[] cleanups = {0};
                Throwable result = outcome(() -> TransactionScope.run(r -> { throw begin; },
                        () -> cleanups[0]++, () -> fail("failed begin body")));
                assertSame(begin, result);
                assertEquals(1, cleanups[0]);
                return null;
            });
            assertNull(second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            release.countDown();
            assertNull(first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
    }

    @Test
    public void statefulSuccessCommitsMultipleWritesAndPostActionRunsAfterCommit() {
        StatefulRoom room = new StatefulRoom();
        List<String> postTransaction = new ArrayList<>();

        TransactionScope.run(room::run, room::end, () -> {
            room.write("first");
            room.write("second");
        });
        postTransaction.addAll(room.committed);

        assertEquals(Arrays.asList("first", "second"), room.committed);
        assertEquals(room.committed, postTransaction);
        assertEquals(Arrays.asList("begin", "write:first", "write:second", "commit", "end"), room.trace);
    }

    @Test
    public void statefulPartialBodyFailureRollsBackAllWrites() {
        StatefulRoom room = new StatefulRoom();
        RuntimeException failure = new IllegalStateException("body");

        assertSame(failure, outcome(() -> TransactionScope.run(room::run, room::end, () -> {
            room.write("before-failure");
            throw failure;
        })));

        assertTrue(room.committed.isEmpty());
        assertTrue(room.staged.isEmpty());
        assertEquals(Arrays.asList("begin", "write:before-failure", "end"), room.trace);
    }

    @Test
    public void mutableCollectionFollowUpWaitsForCleanupAndIsSkippedOnFailure() {
        for (boolean scoped : Arrays.asList(false, true)) {
            for (String stage : Arrays.asList("success", "begin", "body", "commit", "end")) {
                FakeRoom room = new FakeRoom();
                List<String> ids = new ArrayList<>(Arrays.asList("first", "second"));
                RuntimeException failure = new IllegalStateException(stage);
                if (stage.equals("begin")) room.beginFailure = failure;
                if (stage.equals("commit")) room.commitFailure = failure;
                if (stage.equals("end")) room.endFailure = failure;
                Throwable actual = outcome(() -> {
                    Runnable body = () -> {
                        for (String id : ids)
                            room.trace.add("write:" + id);
                        if (stage.equals("body")) throw failure;
                    };
                    if (scoped)
                        TransactionScope.run(room::run, room::end, body);
                    else
                        legacy(room, body);
                    assertEquals("end", room.trace.get(room.trace.size() - 1));
                    ids.clear();
                });
                assertSame(stage.equals("success") ? null : failure, actual);
                assertEquals(stage.equals("success") ? new ArrayList<String>() :
                        Arrays.asList("first", "second"), ids);
            }
        }
    }

    @Test
    public void earlyReturnRollbackIsNotEquivalentToCallbackReturn() {
        StatefulRoom original = new StatefulRoom();
        legacyEarlyReturn(original);
        assertTrue(original.committed.isEmpty());
        assertTrue(original.staged.isEmpty());

        StatefulRoom unsafe = new StatefulRoom();
        TransactionScope.run(unsafe::run, unsafe::end, () -> {
            unsafe.write("before-return");
            return;
        });
        // This deliberately demonstrates why early-return callers stay blocked.
        assertEquals(Arrays.asList("before-return"), unsafe.committed);
        assertEquals(Arrays.asList("begin", "write:before-return", "end"), original.trace);
        assertEquals(Arrays.asList("begin", "write:before-return", "commit", "end"), unsafe.trace);
    }

    private static void legacyEarlyReturn(StatefulRoom room) {
        try {
            room.begin();
            room.write("before-return");
            return;
        } finally {
            room.end();
        }
    }

    @Test
    public void statefulSuccessMarkFailureRollsBackWrites() {
        StatefulRoom room = new StatefulRoom();
        RuntimeException failure = new IllegalStateException("commit");
        room.commitFailure = failure;

        assertSame(failure, outcome(() -> TransactionScope.run(room::run, room::end, () -> {
            room.write("must-not-commit");
        })));

        assertTrue(room.committed.isEmpty());
        assertTrue(room.staged.isEmpty());
        assertEquals(Arrays.asList("begin", "write:must-not-commit", "commit", "end"), room.trace);
    }

    @Test
    public void statefulFailureSkipsPostTransactionActionAndReuseStartsClean() {
        StatefulRoom room = new StatefulRoom();
        RuntimeException failure = new IllegalStateException("body");
        int[] postTransactionActions = {0};

        assertSame(failure, outcome(() -> {
            TransactionScope.run(room::run, room::end, () -> {
                room.write("rolled-back");
                throw failure;
            });
            postTransactionActions[0]++;
        }));
        assertEquals(0, postTransactionActions[0]);
        assertTrue(room.committed.isEmpty());

        TransactionScope.run(room::run, room::end, () -> room.write("next-call"));
        postTransactionActions[0]++;
        assertEquals(1, postTransactionActions[0]);
        assertEquals(Arrays.asList("next-call"), room.committed);
        assertTrue(room.staged.isEmpty());
    }

    @Test
    public void statefulNestedSuccessMergesInnerWritesAndCommitsOncePerScope() {
        StatefulRoom room = new StatefulRoom();

        TransactionScope.run(room::run, room::end, () -> {
            room.write("outer-before");
            TransactionScope.run(room::run, room::end, () -> room.write("inner"));
            room.write("outer-after");
        });

        assertEquals(Arrays.asList("outer-before", "inner", "outer-after"), room.committed);
        assertTrue(room.staged.isEmpty());
        assertEquals(Arrays.asList("begin", "write:outer-before", "begin", "write:inner", "commit", "end",
                "write:outer-after", "commit", "end"), room.trace);
    }

    @Test
    public void statefulWritesStayInvisibleUntilSuccessfulEnd() {
        StatefulRoom room = new StatefulRoom();

        room.begin();
        room.write("pending");
        room.commit();
        assertTrue(room.committed.isEmpty());
        assertEquals(Arrays.asList("pending"), room.staged.peek().writes);

        room.end();
        assertEquals(Arrays.asList("pending"), room.committed);
    }

    @Test
    public void statefulLegacyLifecycleAndTransactionScopeHaveSameState() {
        StatefulRoom legacyRoom = new StatefulRoom();
        StatefulRoom scopedRoom = new StatefulRoom();
        Runnable legacyBody = () -> {
            legacyRoom.write("first");
            legacyRoom.write("second");
        };
        Runnable scopedBody = () -> {
            scopedRoom.write("first");
            scopedRoom.write("second");
        };

        legacy(legacyRoom, legacyBody);
        TransactionScope.run(scopedRoom::run, scopedRoom::end, scopedBody);

        assertEquals(legacyRoom.committed, scopedRoom.committed);
        assertEquals(legacyRoom.trace, scopedRoom.trace);
        assertTrue(scopedRoom.staged.isEmpty());
    }

    @Test
    public void statefulCaughtNestedFailurePoisonsOuterRollback() {
        StatefulRoom room = new StatefulRoom();
        RuntimeException failure = new IllegalStateException("inner");

        TransactionScope.run(room::run, room::end, () -> {
            room.write("outer-before");
            try {
                TransactionScope.run(room::run, room::end, () -> {
                    room.write("inner");
                    throw failure;
                });
                fail("inner failure must escape its scope");
            } catch (RuntimeException ex) {
                assertSame(failure, ex);
            }
            room.write("outer-after");
        });

        assertTrue(room.committed.isEmpty());
        assertTrue(room.staged.isEmpty());
        assertEquals(Arrays.asList("begin", "write:outer-before", "begin", "write:inner", "end",
                "write:outer-after", "commit", "end"), room.trace);
    }

    @Test
    public void outerCatchAndFinallyRunAfterRollbackCleanup() {
        RuntimeException bodyFailure = new IllegalStateException("body");
        for (boolean scoped : Arrays.asList(false, true)) {
            FakeRoom room = new FakeRoom();
            try {
                Runnable body = () -> {
                    room.trace.add("body");
                    throw bodyFailure;
                };
                if (scoped)
                    TransactionScope.run(room::run, room::end, body);
                else
                    legacy(room, body);
                fail("body failure must escape");
            } catch (RuntimeException ex) {
                assertSame(bodyFailure, ex);
                room.trace.add("outer-catch");
            } finally {
                room.trace.add("outer-finally");
            }
            assertEquals(Arrays.asList("begin", "body", "end", "outer-catch", "outer-finally"), room.trace);
        }
    }

    @Test
    public void conditionalRequiresTransactionKeepsExistingOuterFrame() {
        for (boolean requiresTransaction : Arrays.asList(false, true)) {
            StatefulRoom oldRoom = new StatefulRoom();
            StatefulRoom newRoom = new StatefulRoom();
            legacy(oldRoom, () -> {
                if (requiresTransaction)
                    legacy(oldRoom, () -> oldRoom.write("operation"));
                else
                    oldRoom.write("operation");
            });
            TransactionScope.run(newRoom::run, newRoom::end, () -> {
                if (requiresTransaction)
                    TransactionScope.run(newRoom::run, newRoom::end, () -> newRoom.write("operation"));
                else
                    newRoom.write("operation");
            });
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
            assertTrue(newRoom.staged.isEmpty());
        }
    }

    @Test
    public void successFollowUpAndOuterFinallyWaitForEndAndInvalidation() {
        for (boolean scoped : Arrays.asList(false, true)) {
            FakeRoom room = new FakeRoom();
            Runnable endAndInvalidate = () -> {
                room.end();
                room.trace.add("invalidation");
            };
            try {
                if (scoped)
                    TransactionScope.run(body -> {
                        room.begin();
                        try {
                            body.run();
                            room.commit();
                        } finally {
                            endAndInvalidate.run();
                        }
                    }, endAndInvalidate, () -> room.trace.add("body"));
                else
                    try {
                        room.begin();
                        room.trace.add("body");
                        room.commit();
                    } finally {
                        endAndInvalidate.run();
                    }
                room.trace.add("follow-up");
            } finally {
                room.trace.add("outer-finally");
            }
            assertEquals(Arrays.asList("begin", "body", "commit", "end", "invalidation",
                    "follow-up", "outer-finally"), room.trace);
        }
    }
}

package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.Test;

/** Lifecycle tests for the TransactionResult decorator around Room's callable runner. */
public class TransactionCompletionTest {
    private static final class CheckedFailure extends Exception {
        CheckedFailure(String message) { super(message); }
    }

    private static <T, E extends Exception> T call(TransactionCallableRoomModel room,
                                                     Runnable cleanup,
                                                     TransactionCallable<TransactionResult<T>, E> body)
            throws E {
        return TransactionCompletion.call(room::run, cleanup, body);
    }

    @Test
    public void commitPreservesFalseNullAndObjectIdentity() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        Boolean falseValue = Boolean.FALSE;
        Object object = new Object();
        assertSame(falseValue, call(room, room::end, () -> TransactionResult.commit(falseValue)));
        assertTrue(call(room, room::end, () -> TransactionResult.commit(null)) == null);
        assertSame(object, call(room, room::end, () -> TransactionResult.commit(object)));
        assertTrue(room.committed.isEmpty());
    }

    @Test
    public void rollbackPreservesFalseNullAndOriginalObjectIdentityAndDiscardsWrites() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        Boolean falseValue = Boolean.FALSE;
        Object object = new Object();
        assertSame(falseValue, call(room, room::end, () -> {
            room.write("false");
            return TransactionResult.rollback(falseValue);
        }));
        assertTrue(call(room, room::end, () -> {
            room.write("null");
            return TransactionResult.rollback(null);
        }) == null);
        assertSame(object, call(room, room::end, () -> {
            room.write("object");
            return TransactionResult.rollback(object);
        }));
        assertTrue(room.committed.isEmpty());
        assertEquals(0, room.trace.stream().filter("commit"::equals).count());
    }

    @Test
    public void writesBeforeRollbackDecisionAndBeforeReturnAreRolledBack() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        String value = call(room, room::end, () -> {
            room.write("before-marker");
            TransactionResult<String> result = TransactionResult.rollback("value");
            room.write("after-marker-expression");
            return result;
        });
        assertEquals("value", value);
        assertTrue(room.committed.isEmpty());
        assertEquals(Arrays.asList("begin", "write:before-marker", "write:after-marker-expression",
                "end"), room.trace);
    }

    @Test
    public void checkedRuntimeAndErrorIdentitySurvive() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        IOException checked = new IOException("checked");
        try {
            call(room, room::end, () -> { throw checked; });
            fail("expected checked failure");
        } catch (IOException actual) { assertSame(checked, actual); }

        CheckedFailure custom = new CheckedFailure("custom");
        try {
            call(room, room::end, () -> { throw custom; });
            fail("expected checked failure");
        } catch (CheckedFailure actual) { assertSame(custom, actual); }

        RuntimeException runtime = new IllegalArgumentException("runtime");
        try {
            call(room, room::end, () -> { throw runtime; });
            fail("expected runtime failure");
        } catch (RuntimeException actual) { assertSame(runtime, actual); }

        Error error = new AssertionError("error");
        try {
            call(room, room::end, () -> { throw error; });
            fail("expected error");
        } catch (Error actual) { assertSame(error, actual); }
    }

    @Test
    public void expressionFailureBeforeMarkerRollsBackWrites() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException failure = new IllegalStateException("decision expression");
        try {
            call(room, room::end, () -> {
                room.write("not-committed");
                throw failure;
            });
            fail("expected expression failure");
        } catch (RuntimeException actual) { assertSame(failure, actual); }
        assertTrue(room.committed.isEmpty());
    }

    @Test
    public void literalNullDecisionFailsSafelyAndRollsBack() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        try {
            call(room, room::end, () -> {
                room.write("null-decision");
                return null;
            });
            fail("expected null decision failure");
        } catch (NullPointerException expected) {
            // A malformed decision is not an implicit commit.
        }
        assertTrue(room.committed.isEmpty());
    }

    @Test
    public void bothBeginCleanupPoliciesAreHonoredAndCleanupCanMaskBegin() {
        TransactionCallableRoomModel noCleanup = new TransactionCallableRoomModel();
        RuntimeException begin = new IllegalStateException("begin");
        noCleanup.beginFailure = begin;
        try {
            call(noCleanup, null, () -> { fail("body must not run"); return null; });
            fail("expected begin");
        } catch (RuntimeException actual) { assertSame(begin, actual); }
        assertEquals(Arrays.asList("begin"), noCleanup.trace);

        TransactionCallableRoomModel cleanup = new TransactionCallableRoomModel();
        cleanup.beginFailure = begin;
        Error cleanupFailure = new AssertionError("cleanup");
        cleanup.endFailure = cleanupFailure;
        try {
            call(cleanup, cleanup::end, () -> { fail("body must not run"); return null; });
            fail("expected cleanup");
        } catch (Error actual) { assertSame(cleanupFailure, actual); }
        assertEquals(Arrays.asList("begin", "end"), cleanup.trace);

        TransactionCallableRoomModel successfulCleanup = new TransactionCallableRoomModel();
        successfulCleanup.beginFailure = begin;
        try {
            call(successfulCleanup, successfulCleanup::end, () -> {
                fail("body must not run");
                return null;
            });
            fail("expected begin");
        } catch (RuntimeException actual) { assertSame(begin, actual); }
        assertEquals(Arrays.asList("begin", "end"), successfulCleanup.trace);
    }

    @Test
    public void endFailureMasksRollbackBodyAndCommitWithoutSuppressed() throws Exception {
        TransactionCallableRoomModel rollback = new TransactionCallableRoomModel();
        Error end = new AssertionError("end rollback");
        rollback.endFailure = end;
        try {
            call(rollback, rollback::end, () -> TransactionResult.rollback("value"));
            fail("expected end");
        } catch (Error actual) { assertSame(end, actual); assertEquals(0, actual.getSuppressed().length); }

        TransactionCallableRoomModel body = new TransactionCallableRoomModel();
        body.endFailure = end;
        IOException original = new IOException("body");
        try {
            call(body, body::end, () -> { throw original; });
            fail("expected end");
        } catch (Error actual) { assertSame(end, actual); assertEquals(0, actual.getSuppressed().length); }

        TransactionCallableRoomModel commit = new TransactionCallableRoomModel();
        commit.endFailure = end;
        RuntimeException commitFailure = new IllegalStateException("commit");
        commit.commitFailure = commitFailure;
        try {
            call(commit, commit::end, () -> TransactionResult.commit("value"));
            fail("expected end");
        } catch (Error actual) { assertSame(end, actual); assertEquals(0, actual.getSuppressed().length); }
    }

    @Test
    public void knownEndFailureIsSwallowedOnlyAndPreservesBodyOrResult() throws Exception {
        for (String marker : Arrays.asList("no current transaction", "no transaction is active")) {
            TransactionCallableRoomModel success = new TransactionCallableRoomModel();
            success.swallowKnownEndFailure = true;
            success.endFailure = new IllegalStateException("prefix " + marker);
            assertEquals("ok", call(success, success::end, () -> TransactionResult.commit("ok")));

            TransactionCallableRoomModel failure = new TransactionCallableRoomModel();
            failure.swallowKnownEndFailure = true;
            failure.endFailure = new IllegalStateException(marker);
            IOException original = new IOException("body");
            try {
                call(failure, failure::end, () -> { throw original; });
                fail("expected body");
            } catch (IOException actual) { assertSame(original, actual); }

            TransactionCallableRoomModel rollback = new TransactionCallableRoomModel();
            rollback.swallowKnownEndFailure = true;
            rollback.endFailure = new IllegalStateException(marker);
            assertEquals("legacy", call(rollback, rollback::end,
                    () -> TransactionResult.rollback("legacy")));
        }
    }

    @Test
    public void nestedRollbackPoisonsOuterEvenWhenOuterReturnsNormallyAndSignalsAreIndependent() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        String outer = call(room, room::end, () -> {
            room.write("outer-before");
            assertEquals("inner", call(room, room::end, () -> {
                room.write("inner");
                return TransactionResult.rollback("inner");
            }));
            room.write("outer-after");
            return TransactionResult.commit("outer");
        });
        assertEquals("outer", outer);
        assertTrue(room.committed.isEmpty());
    }

    @Test
    public void foreignRollbackSignalIsNotConsumedByAnotherInvocation() {
        Function<Callable<String>, String> runner = callback -> {
            try {
                return callback.call();
            } catch (RuntimeException signal) {
                try {
                    TransactionCompletion.call(ignored -> { throw signal; }, null,
                            () -> TransactionResult.commit("different invocation"));
                    fail("expected captured rollback signal");
                } catch (RuntimeException actual) {
                    assertSame(signal, actual);
                }
                throw signal;
            } catch (Exception checked) {
                throw new AssertionError(checked);
            }
        };
        assertEquals("value", TransactionCompletion.call(runner, null,
                () -> TransactionResult.rollback("value")));
    }

    @Test
    public void successfulCommitWritesArePublished() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        assertEquals("value", call(room, room::end, () -> {
            room.write("value");
            return TransactionResult.commit("value");
        }));
        assertEquals(Arrays.asList("value"), room.committed);
        assertEquals(Arrays.asList("begin", "write:value", "commit", "end"), room.trace);
    }

    @Test
    public void nestedSuccessfulCommitsMergeWrites() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        assertEquals("outer", call(room, room::end, () -> {
            room.write("outer");
            assertEquals("inner", call(room, room::end, () -> {
                room.write("inner");
                return TransactionResult.commit("inner");
            }));
            return TransactionResult.commit("outer");
        }));
        assertEquals(Arrays.asList("outer", "inner"), room.committed);
        assertEquals(Arrays.asList("begin", "write:outer", "begin", "write:inner", "commit", "end",
                "commit", "end"), room.trace);
    }

    @Test
    public void followupRunsOnlyAfterSuccessfulCleanupAndUsesConsumerResult() throws Exception {
        TransactionCallableRoomModel failed = new TransactionCallableRoomModel();
        failed.endFailure = new IllegalStateException("end");
        List<String> followups = new java.util.ArrayList<>();
        try {
            String result = call(failed, failed::end, () -> TransactionResult.rollback("legacy"));
            followups.add(result);
            fail("expected end");
        } catch (RuntimeException expected) { }
        assertTrue(followups.isEmpty());

        TransactionCallableRoomModel clean = new TransactionCallableRoomModel();
        String result = call(clean, clean::end, () -> TransactionResult.rollback("legacy"));
        if ("legacy".equals(result)) followups.add(result);
        assertEquals(Arrays.asList("legacy"), followups);
    }

    @Test
    public void concurrentInvocationsDoNotShareRollbackState() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstEntered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<String> first = executor.submit(() -> {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                return call(room, room::end, () -> {
                    firstEntered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return TransactionResult.rollback("first");
                });
            });
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
            Future<String> second = executor.submit(() -> {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                return call(room, room::end, () -> TransactionResult.rollback("second"));
            });
            assertEquals("second", second.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("first", first.get(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}

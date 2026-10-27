package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
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

/** Typed host tests for the Room-compatible callable transaction runner. */
public class TransactionCallableTest {
    private static final class CustomChecked extends Exception {
        CustomChecked(String message) { super(message); }
    }

    private static <T, E extends Exception> T scoped(TransactionCallableRoomModel room,
                                                      Runnable cleanup,
                                                      TransactionCallable<T, E> body)
            throws E {
        return TransactionScope.call(room::run, cleanup, body);
    }

    @Test
    public void valuesAndNullCommitExplicitly() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        assertEquals("value", scoped(room, room::end, () -> {
            room.write("value");
            return "value";
        }));
        assertEquals(Arrays.asList("value"), room.committed);
        assertEquals(Arrays.asList("begin", "write:value", "commit", "end"), room.trace);
        assertNull(scoped(room, room::end, () -> null));
        assertEquals(Arrays.asList("value"), room.committed);
    }

    @Test
    public void checkedAndRuntimeAndErrorIdentitySurvives() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        IOException io = new IOException("io");
        try {
            scoped(room, room::end, () -> { throw io; });
            fail("expected IOException");
        } catch (IOException actual) {
            assertSame(io, actual);
        }
        CustomChecked custom = new CustomChecked("custom");
        try {
            scoped(room, room::end, () -> { throw custom; });
            fail("expected checked exception");
        } catch (CustomChecked actual) {
            assertSame(custom, actual);
        }
        RuntimeException runtime = new IllegalStateException("runtime");
        try {
            scoped(room, room::end, () -> { throw runtime; });
            fail("expected runtime exception");
        } catch (RuntimeException actual) {
            assertSame(runtime, actual);
        }
        Error error = new AssertionError("error");
        try {
            scoped(room, room::end, () -> { throw error; });
            fail("expected error");
        } catch (Error actual) {
            assertSame(error, actual);
        }
    }

    @Test
    public void beginFailureRunsCleanupAndCleanupCanMaskIt() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException begin = new IllegalStateException("begin");
        Error cleanup = new AssertionError("cleanup");
        room.beginFailure = begin;
        room.endFailure = cleanup;
        try {
            scoped(room, room::end, () -> { fail("body must not run"); return null; });
            fail("expected cleanup");
        } catch (Error actual) {
            assertSame(cleanup, actual);
        }
        assertEquals(Arrays.asList("begin", "end"), room.trace);
    }

    @Test
    public void beginFailureWithoutCleanupFailureIsPreserved() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException begin = new IllegalStateException("begin");
        room.beginFailure = begin;
        try {
            scoped(room, room::end, () -> { fail("body must not run"); return null; });
            fail("expected begin");
        } catch (RuntimeException actual) {
            assertSame(begin, actual);
        }
    }

    @Test
    public void commitAndEndFailuresHavePinnedPrecedence() {
        TransactionCallableRoomModel commitRoom = new TransactionCallableRoomModel();
        RuntimeException commit = new IllegalStateException("commit");
        commitRoom.commitFailure = commit;
        try {
            scoped(commitRoom, commitRoom::end, () -> "body");
            fail("expected commit");
        } catch (RuntimeException actual) {
            assertSame(commit, actual);
        }
        TransactionCallableRoomModel endRoom = new TransactionCallableRoomModel();
        RuntimeException body = new IllegalStateException("body");
        RuntimeException end = new IllegalStateException("end");
        endRoom.endFailure = end;
        try {
            scoped(endRoom, endRoom::end, () -> { throw body; });
            fail("expected end");
        } catch (RuntimeException actual) {
            assertSame(end, actual);
        }
    }

    @Test
    public void knownEndFailureIsSwallowedButNeverHidesOriginal() throws Exception {
        for (String message : Arrays.asList("no current transaction", "no transaction is active")) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            room.swallowKnownEndFailure = true;
            room.endFailure = new IllegalStateException("prefix " + message + " suffix");
            assertEquals("ok", scoped(room, room::end, () -> "ok"));
            assertTrue(room.trace.contains("logged-end"));

            TransactionCallableRoomModel failing = new TransactionCallableRoomModel();
            failing.swallowKnownEndFailure = true;
            failing.endFailure = new IllegalStateException(message);
            IOException original = new IOException("original");
            try {
                scoped(failing, failing::end, () -> { throw original; });
                fail("expected original");
            } catch (IOException actual) {
                assertSame(original, actual);
            }
        }
    }

    @Test
    public void nestedWritesPoisonParentWhenInnerFailureIsCaught() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        scoped(room, room::end, () -> {
            room.write("outer-before");
            try {
                scoped(room, room::end, () -> {
                    room.write("inner");
                    throw new IOException("inner");
                });
                fail("expected inner");
            } catch (IOException expected) {
                assertEquals("inner", expected.getMessage());
            }
            room.write("outer-after");
            return null;
        });
        assertTrue(room.committed.isEmpty());
    }

    @Test
    public void nestedSuccessMergesAndIndependentBeginCleanupWorks() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        scoped(room, room::end, () -> {
            room.write("outer");
            scoped(room, room::end, () -> { room.write("inner"); return "done"; });
            return null;
        });
        assertEquals(Arrays.asList("outer", "inner"), room.committed);

        TransactionCallableRoomModel failed = new TransactionCallableRoomModel();
        RuntimeException innerBegin = new IllegalStateException("inner begin");
        scoped(failed, failed::end, () -> {
            failed.beginFailure = innerBegin;
            try {
                scoped(failed, failed::end, () -> { fail("inner body"); return null; });
                fail("expected inner begin");
            } catch (RuntimeException actual) {
                assertSame(innerBegin, actual);
            } finally {
                failed.beginFailure = null;
            }
            return null;
        });
        assertTrue(failed.trace.equals(Arrays.asList("begin", "begin", "end", "commit", "end")));
    }

    @Test
    public void directRoomRunHasNoCleanupAndFollowupNeedsSuccessfulEnd() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        TransactionCallable<String, IOException> body = () -> { room.write("direct"); return "direct"; };
        assertEquals("direct", room.run(body::call));
        assertEquals(Arrays.asList("direct"), room.committed);

        TransactionCallableRoomModel failing = new TransactionCallableRoomModel();
        List<String> followups = new ArrayList<>();
        failing.endFailure = new IllegalStateException("end");
        try {
            String result = scoped(failing, failing::end, () -> "result");
            followups.add(result);
            fail("expected end");
        } catch (RuntimeException expected) {
            assertEquals("end", expected.getMessage());
        }
        assertTrue(followups.isEmpty());
        failing.endFailure = null;
        followups.add(scoped(failing, failing::end, () -> {
            failing.write("followup");
            return "followup";
        }));
        assertEquals(Arrays.asList("followup"), followups);
        assertEquals(Arrays.asList("followup"), failing.committed);
    }

    @Test
    public void directBeginFailureDoesNotRunLegacyCleanup() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException begin = new IllegalStateException("begin");
        room.beginFailure = begin;
        try {
            room.run(() -> { fail("body must not run"); return null; });
            fail("expected begin");
        } catch (RuntimeException actual) {
            assertSame(begin, actual);
        }
        assertEquals(Arrays.asList("begin"), room.trace);
    }

    @Test
    public void endFailureMasksCheckedBodyCommitAndResultWithoutSuppression() throws Exception {
        for (String stage : Arrays.asList("body", "commit", "result")) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            IOException body = new IOException("body");
            RuntimeException commit = new IllegalStateException("commit");
            Error end = new AssertionError("end");
            room.endFailure = end;
            if (stage.equals("commit"))
                room.commitFailure = commit;
            try {
                scoped(room, room::end, () -> {
                    if (stage.equals("body"))
                        throw body;
                    return "result";
                });
                fail("expected end");
            } catch (Error actual) {
                assertSame(end, actual);
                assertEquals(0, actual.getSuppressed().length);
            }
            assertEquals(1, room.trace.stream().filter("end"::equals).count());
        }
    }

    @Test
    public void concurrentCallsKeepIndependentEntryFlags() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            TransactionCallableRoomModel first = new TransactionCallableRoomModel();
            Future<String> active = executor.submit(() -> scoped(first, first::end, () -> {
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return "first";
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            Future<String> failed = executor.submit(() -> {
                TransactionCallableRoomModel second = new TransactionCallableRoomModel();
                RuntimeException begin = new IllegalStateException("second begin");
                second.beginFailure = begin;
                try {
                    scoped(second, second::end, () -> { fail("second body"); return null; });
                    fail("expected begin");
                } catch (RuntimeException actual) {
                    assertSame(begin, actual);
                }
                assertEquals(Arrays.asList("begin", "end"), second.trace);
                return "second";
            });
            assertEquals("second", failed.get(5, TimeUnit.SECONDS));
            release.countDown();
            assertEquals("first", active.get(5, TimeUnit.SECONDS));
            assertEquals(Arrays.asList("begin", "commit", "end"), first.trace);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void callableFunctionKeepsCheckedTypeWithoutAdapter() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        Function<Callable<String>, String> runner = room::run;
        IOException expected = new IOException("typed");
        try {
            TransactionScope.call(runner, room::end, () -> { throw expected; });
            fail("expected typed exception");
        } catch (IOException actual) {
            assertSame(expected, actual);
        }
    }
}

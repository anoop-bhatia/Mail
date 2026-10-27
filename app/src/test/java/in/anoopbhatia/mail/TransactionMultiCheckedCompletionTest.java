package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;
import javax.mail.MessagingException;
import org.json.JSONException;
import org.junit.Test;

/**
 * Parity matrix for the three-independent-checked-exception completion adapter.
 * The model is Room's real synchronous Callable runner; no fake runner is used.
 */
public class TransactionMultiCheckedCompletionTest {
    private static <T, A extends Exception, B extends Exception, C extends Exception>
            T complete(TransactionCallableRoomModel room,
                    DB.TransactionMultiCheckedCallable<TransactionResult<T>, A, B, C> body)
            throws A, B, C {
        Callable<TransactionResult<T>> callable = () -> body.call();
        return TransactionCompletion.callChecked(room::run, room::end, callable);
    }

    private static <T> T legacy(TransactionCallableRoomModel room,
                                 Callable<TransactionResult<T>> body) throws Exception {
        try {
            room.begin();
            TransactionResult<T> result = body.call();
            if (result.commit)
                room.commit();
            return result.value;
        } finally {
            room.end();
        }
    }

    private static Throwable thrown(ThrowingCall call) {
        try {
            call.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingCall { void run() throws Throwable; }

    @Test
    public void commitAndRollbackPreserveFalseNullIdentityAndWrites() throws Exception {
        Object object = new Object();
        for (boolean commit : new boolean[] {false, true}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            Boolean oldValue = legacy(oldRoom, () -> {
                oldRoom.write("decision");
                return commit ? TransactionResult.commit(Boolean.FALSE)
                              : TransactionResult.rollback(Boolean.FALSE);
            });
            Boolean newValue = complete(newRoom, () -> {
                newRoom.write("decision");
                return commit ? TransactionResult.commit(Boolean.FALSE)
                              : TransactionResult.rollback(Boolean.FALSE);
            });
            assertSame(Boolean.FALSE, oldValue);
            assertSame(oldValue, newValue);
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
        }
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        assertNull(complete(room, () -> TransactionResult.rollback(null)));
        assertSame(object, complete(room, () -> TransactionResult.commit(object)));
    }

    @Test
    public void eachNarrowCheckedTypeRetainsIdentityAndTrace() throws Exception {
        IOException io = new IOException("io");
        MessagingException mail = new MessagingException("mail");
        JSONException json = new JSONException("json");
        for (int selected = 0; selected < 3; selected++) {
            final int choice = selected;
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            Throwable oldFailure = thrown(() -> legacy(oldRoom, () -> {
                if (choice == 0) throw io;
                if (choice == 1) throw mail;
                throw json;
            }));
            Throwable newFailure = thrown(() -> complete(newRoom, () -> {
                if (choice == 0) throw io;
                if (choice == 1) throw mail;
                throw json;
            }));
            assertSame(oldFailure, newFailure);
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
        }
    }

    @Test
    public void checkedBodyAndEndFailureHaveLegacyPrecedenceAndNoSuppression() throws Exception {
        IOException body = new IOException("body");
        AssertionError end = new AssertionError("end");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.endFailure = end;
        newRoom.endFailure = end;
        Throwable oldFailure = thrown(() -> legacy(oldRoom, () -> { throw body; }));
        Throwable newFailure = thrown(() -> complete(newRoom, () -> { throw body; }));
        assertSame(end, oldFailure);
        assertSame(oldFailure, newFailure);
        assertEquals(0, newFailure.getSuppressed().length);
        assertEquals(oldRoom.trace, newRoom.trace);
    }

    @Test
    public void beginFailureRunsCleanupAndCleanupHasSamePrecedence() throws Exception {
        IllegalStateException begin = new IllegalStateException("begin");
        AssertionError cleanup = new AssertionError("cleanup");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.beginFailure = begin;
        newRoom.beginFailure = begin;
        oldRoom.endFailure = cleanup;
        newRoom.endFailure = cleanup;
        Throwable oldFailure = thrown(() -> legacy(oldRoom, () -> TransactionResult.commit(true)));
        Throwable newFailure = thrown(() -> complete(newRoom, () -> TransactionResult.commit(true)));
        assertSame(cleanup, oldFailure);
        assertSame(oldFailure, newFailure);
        assertEquals(oldRoom.trace, newRoom.trace);
    }

    @Test
    public void runtimeAndErrorIdentitySurviveExactly() throws Exception {
        for (Throwable expected : new Throwable[] {
                new IllegalStateException("runtime"), new AssertionError("error") }) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            Throwable oldFailure = thrown(() -> legacy(oldRoom, () -> {
                if (expected instanceof RuntimeException) throw (RuntimeException) expected;
                throw (Error) expected;
            }));
            Throwable newFailure = thrown(() -> complete(newRoom, () -> {
                if (expected instanceof RuntimeException) throw (RuntimeException) expected;
                throw (Error) expected;
            }));
            assertSame(expected, oldFailure);
            assertSame(oldFailure, newFailure);
            assertEquals(oldRoom.trace, newRoom.trace);
        }
    }

    @Test
    public void nestedRollbackPoisonsOuter() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        legacy(oldRoom, () -> {
            oldRoom.write("outer");
            legacy(oldRoom, () -> { oldRoom.write("inner"); return TransactionResult.rollback(false); });
            oldRoom.write("after-inner");
            return TransactionResult.commit(true);
        });
        complete(newRoom, () -> {
            newRoom.write("outer");
            complete(newRoom, () -> { newRoom.write("inner"); return TransactionResult.rollback(false); });
            newRoom.write("after-inner");
            return TransactionResult.commit(true);
        });
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(oldRoom.committed, newRoom.committed);
        assertTrue(newRoom.committed.isEmpty());
    }

    @Test
    public void loopContinueRollbackCounterRunsBeforeMarkerAndMatchesLegacy() throws Exception {
        for (int count = 0; count < 4; count++) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            int oldApplied = 0;
            int[] newApplied = {0};
            for (int i = 0; i < count; i++) {
                try {
                    oldRoom.begin();
                    if (i % 2 == 0)
                        continue;
                    oldApplied++;
                    oldRoom.trace.add("count:" + oldApplied);
                    oldRoom.commit();
                } finally {
                    oldRoom.end();
                }
            }
            for (int i = 0; i < count; i++) {
                final int index = i;
                complete(newRoom, () -> {
                    if (index % 2 == 0)
                        return TransactionResult.rollback(null);
                    newApplied[0]++;
                    newRoom.trace.add("count:" + newApplied[0]);
                    return TransactionResult.commit(null);
                });
            }
            assertEquals(oldApplied, newApplied[0]);
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
        }
    }

    @Test
    public void nullCallableDereferenceOccursAfterBeginAndEndFailureStillWins() {
        for (boolean endFails : new boolean[] {false, true}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            AssertionError end = new AssertionError("end");
            if (endFails) {
                oldRoom.endFailure = end;
                newRoom.endFailure = end;
            }
            Throwable oldFailure = thrown(() -> TransactionCompletion.<Object, Exception>
                    call(oldRoom::run, oldRoom::end, null));
            Throwable newFailure = thrown(() -> TransactionCompletion.<Object>
                    callChecked(newRoom::run, newRoom::end, null));
            if (endFails) {
                assertSame(end, oldFailure);
                assertSame(end, newFailure);
            } else {
                assertTrue(oldFailure instanceof NullPointerException);
                assertTrue(newFailure instanceof NullPointerException);
            }
            assertEquals(Arrays.asList("begin", "end"), oldRoom.trace);
            assertEquals(oldRoom.trace, newRoom.trace);
        }
    }

    @Test
    public void failureStopsFollowupAfterCompletionAndKeepsTraceEqual() throws Exception {
        IOException failure = new IOException("stop");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        Throwable oldFailure = thrown(() -> {
            legacy(oldRoom, () -> { throw failure; });
            oldRoom.trace.add("followup");
        });
        Throwable newFailure = thrown(() -> {
            complete(newRoom, () -> { throw failure; });
            newRoom.trace.add("followup");
        });
        assertSame(failure, oldFailure);
        assertSame(oldFailure, newFailure);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertFalse(oldRoom.trace.contains("followup"));
    }

    @Test
    public void bodyCheckedAndEndFailurePrecedenceIsSameForAllThreeTypes() throws Exception {
        Exception[] failures = { new IOException("io"), new MessagingException("mail"),
                new JSONException("json") };
        for (Exception failure : failures) {
            AssertionError end = new AssertionError("end-" + failure.getMessage());
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            oldRoom.endFailure = end;
            newRoom.endFailure = end;
            Throwable oldFailure = thrown(() -> legacy(oldRoom, () -> { throw failure; }));
            Throwable newFailure = thrown(() -> complete(newRoom, () -> { throw failure; }));
            assertSame(end, oldFailure);
            assertSame(oldFailure, newFailure);
            assertEquals(oldRoom.trace, newRoom.trace);
        }
    }

    @Test
    public void publicAdapterCanStateExactlyThreeNarrowThrows() throws IOException, MessagingException, JSONException {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        TransactionMultiCheckedCompletionTest.<Void, IOException, MessagingException, JSONException>
                complete(room, () -> TransactionResult.commit(null));
        assertEquals(Arrays.asList("begin", "commit", "end"), room.trace);
    }
}

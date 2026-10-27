package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Host-model parity tests for the early-return migration and its post-end follow-up. */
public class TransactionCompletionFollowupTest {
    private interface Operation { boolean run() throws Exception; }
    private interface Followup { void run() throws Exception; }

    /** The original shape: the early return is inside the try/finally. */
    private static Void legacy(TransactionCallableRoomModel room, Operation operation,
                               Followup followup) throws Exception {
        try {
            room.begin();
            if (!operation.run())
                return null;
            room.commit();
        } finally {
            room.end();
        }
        followup.run();
        return null;
    }

    /** The migrated shape: only a successful completion reaches the follow-up. */
    private static Void migrated(TransactionCallableRoomModel room, Operation operation,
                                Followup followup) throws Exception {
        if (!TransactionCompletion.call(room::run, room::end, () ->
                operation.run() ? TransactionResult.commit(Boolean.TRUE)
                                : TransactionResult.rollback(Boolean.FALSE)))
            return null;
        followup.run();
        return null;
    }

    private static void assertParity(Operation oldOperation, Operation newOperation,
                                     Followup oldFollowup, Followup newFollowup,
                                     String... expectedTrace) throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        Throwable oldFailure = capture(() -> legacy(oldRoom, oldOperation, oldFollowup));
        Throwable newFailure = capture(() -> migrated(newRoom, newOperation, newFollowup));
        if (oldFailure == null || newFailure == null) {
            assertTrue(oldFailure == null && newFailure == null);
        } else {
            assertSame(oldFailure, newFailure);
        }
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(oldRoom.committed, newRoom.committed);
        if (expectedTrace.length != 0)
            assertEquals(Arrays.asList(expectedTrace), oldRoom.trace);
    }

    private interface ThrowingCall { void run() throws Exception; }

    private static Throwable capture(ThrowingCall call) {
        try {
            call.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static boolean throwFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException)
            throw (IOException) failure;
        if (failure instanceof RuntimeException)
            throw (RuntimeException) failure;
        if (failure instanceof Error)
            throw (Error) failure;
        throw new AssertionError("unexpected checked failure", failure);
    }

    @Test
    public void rollbackReturnsNullAndSkipsFollowup() throws Exception {
        List<String> oldFollowup = new java.util.ArrayList<>();
        List<String> newFollowup = new java.util.ArrayList<>();
        assertParity(() -> false, () -> false,
                () -> oldFollowup.add("followup"), () -> newFollowup.add("followup"),
                "begin", "end");
        assertTrue(oldFollowup.isEmpty() && newFollowup.isEmpty());
    }

    @Test
    public void writesBeforeEarlyReturnAreRolledBackAndFollowupIsNotRun() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        List<String> oldFollowup = new java.util.ArrayList<>();
        List<String> newFollowup = new java.util.ArrayList<>();
        assertSame(null, legacy(oldRoom, () -> {
            oldRoom.write("before-return");
            return false;
        }, () -> oldFollowup.add("followup")));
        assertSame(null, migrated(newRoom, () -> {
            newRoom.write("before-return");
            return false;
        }, () -> newFollowup.add("followup")));
        assertEquals(Arrays.asList("begin", "write:before-return", "end"), oldRoom.trace);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertTrue(oldRoom.committed.isEmpty() && newRoom.committed.isEmpty());
        assertTrue(oldFollowup.isEmpty() && newFollowup.isEmpty());
    }

    @Test
    public void multipleEarlyReturnsPreserveWritesAndRollback() throws Exception {
        for (int site = 0; site < 3; site++) {
            final int position = site;
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            List<String> oldFollowup = new java.util.ArrayList<>();
            List<String> newFollowup = new java.util.ArrayList<>();
            Operation oldOperation = () -> {
                if (position == 0) return false;
                oldRoom.write("before-" + position);
                if (position == 1) return false;
                oldRoom.write("decision-" + position);
                return false;
            };
            Operation newOperation = () -> {
                if (position == 0) return false;
                newRoom.write("before-" + position);
                if (position == 1) return false;
                newRoom.write("decision-" + position);
                return false;
            };
            assertSame(null, legacy(oldRoom, oldOperation, () -> oldFollowup.add("followup")));
            assertSame(null, migrated(newRoom, newOperation, () -> newFollowup.add("followup")));
            assertEquals(oldRoom.trace, newRoom.trace);
            assertTrue(oldRoom.committed.isEmpty() && newRoom.committed.isEmpty());
            assertTrue(oldFollowup.isEmpty() && newFollowup.isEmpty());
        }
    }

    @Test
    public void successfulNullReturnRunsFollowupAfterEnd() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        List<String> oldFollowup = new java.util.ArrayList<>();
        List<String> newFollowup = new java.util.ArrayList<>();
        assertSame(null, legacy(oldRoom, () -> { oldRoom.write("value"); return true; },
                () -> { oldRoom.trace.add("followup"); oldFollowup.add("followup"); }));
        assertSame(null, migrated(newRoom, () -> { newRoom.write("value"); return true; },
                () -> { newRoom.trace.add("followup"); newFollowup.add("followup"); }));
        assertEquals(Arrays.asList("value"), oldRoom.committed);
        assertEquals(Arrays.asList("begin", "write:value", "commit", "end", "followup"), oldRoom.trace);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(oldFollowup, newFollowup);
    }

    @Test
    public void beginFailureSkipsBodyAndFollowup() throws Exception {
        RuntimeException begin = new IllegalStateException("begin");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.beginFailure = begin;
        newRoom.beginFailure = begin;
        Followup forbidden = () -> fail("followup must not run");
        assertSame(begin, capture(() -> legacy(oldRoom, () -> { fail("body"); return true; }, forbidden)));
        assertSame(begin, capture(() -> migrated(newRoom, () -> { fail("body"); return true; }, forbidden)));
        assertEquals(Arrays.asList("begin", "end"), oldRoom.trace);
        assertEquals(oldRoom.trace, newRoom.trace);
    }

    @Test
    public void checkedRuntimeAndErrorBodyFailuresKeepIdentityAndSkipFollowup() throws Exception {
        assertFailureParity(new IOException("checked"));
        assertFailureParity(new IllegalArgumentException("runtime"));
        assertFailureParity(new AssertionError("error"));
    }

    private static void assertFailureParity(Throwable failure) throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        Followup forbidden = () -> fail("followup must not run");
        assertSame(failure, capture(() -> legacy(oldRoom, () -> { throwFailure(failure); return true; }, forbidden)));
        assertSame(failure, capture(() -> migrated(newRoom, () -> { throwFailure(failure); return true; }, forbidden)));
        assertEquals(oldRoom.trace, newRoom.trace);
        assertTrue(oldRoom.committed.isEmpty() && newRoom.committed.isEmpty());
    }

    @Test
    public void markerFailureSkipsFollowupAndKeepsIdentity() throws Exception {
        RuntimeException marker = new IllegalStateException("commit");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.commitFailure = marker;
        newRoom.commitFailure = marker;
        Followup forbidden = () -> fail("followup must not run");
        assertSame(marker, capture(() -> legacy(oldRoom, () -> true, forbidden)));
        assertSame(marker, capture(() -> migrated(newRoom, () -> true, forbidden)));
        assertEquals(oldRoom.trace, newRoom.trace);
    }

    @Test
    public void endFailureMasksRollbackBodyAndMarkerWithoutSuppression() throws Exception {
        for (int caseNumber = 0; caseNumber < 3; caseNumber++) {
            Error end = new AssertionError("end");
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            oldRoom.endFailure = end;
            newRoom.endFailure = end;
            Operation oldOperation;
            Operation newOperation;
            if (caseNumber == 0) {
                oldOperation = () -> false;
                newOperation = () -> false;
            } else if (caseNumber == 1) {
                oldOperation = () -> { throw new IOException("body"); };
                newOperation = () -> { throw new IOException("body"); };
            } else {
                RuntimeException marker = new IllegalStateException("marker");
                oldRoom.commitFailure = marker;
                newRoom.commitFailure = marker;
                oldOperation = () -> true;
                newOperation = () -> true;
            }
            Throwable oldFailure = capture(() -> legacy(oldRoom, oldOperation, () -> fail("followup")));
            Throwable newFailure = capture(() -> migrated(newRoom, newOperation, () -> fail("followup")));
            assertSame(end, oldFailure);
            assertSame(end, newFailure);
            assertEquals(0, oldFailure.getSuppressed().length);
            assertEquals(oldRoom.trace, newRoom.trace);
        }
    }

    @Test
    public void knownEndFailureIsSwallowedAndFollowupRunsAfterEnd() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.swallowKnownEndFailure = true;
        newRoom.swallowKnownEndFailure = true;
        oldRoom.endFailure = new IllegalStateException("no current transaction");
        newRoom.endFailure = oldRoom.endFailure;
        assertSame(null, legacy(oldRoom, () -> true, () -> oldRoom.trace.add("followup")));
        assertSame(null, migrated(newRoom, () -> true, () -> newRoom.trace.add("followup")));
        assertEquals(Arrays.asList("begin", "commit", "end", "logged-end", "followup"), oldRoom.trace);
        assertEquals(oldRoom.trace, newRoom.trace);
    }

    @Test
    public void followupFailureIsInjectedInsideBothMethodsAfterEnd() throws Exception {
        RuntimeException failure = new IllegalStateException("followup");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        Followup oldFollowup = () -> { oldRoom.trace.add("followup"); throw failure; };
        Followup newFollowup = () -> { newRoom.trace.add("followup"); throw failure; };
        assertSame(failure, capture(() -> legacy(oldRoom, () -> true, oldFollowup)));
        assertSame(failure, capture(() -> migrated(newRoom, () -> true, newFollowup)));
        assertEquals(Arrays.asList("begin", "commit", "end", "followup"), oldRoom.trace);
        assertEquals(oldRoom.trace, newRoom.trace);
    }

    @Test
    public void nestedRollbackPoisonsOuterAndSkipsItsFollowup() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        List<String> oldFollowup = new java.util.ArrayList<>();
        List<String> newFollowup = new java.util.ArrayList<>();
        assertSame(null, legacy(oldRoom, () -> {
            oldRoom.write("outer");
            assertSame(null, legacy(oldRoom, () -> false, () -> oldFollowup.add("inner")));
            return false;
        }, () -> oldFollowup.add("outer")));
        assertSame(null, migrated(newRoom, () -> {
            newRoom.write("outer");
            assertSame(null, migrated(newRoom, () -> false, () -> newFollowup.add("inner")));
            return false;
        }, () -> newFollowup.add("outer")));
        assertTrue(oldRoom.committed.isEmpty() && newRoom.committed.isEmpty());
        assertEquals(oldRoom.trace, newRoom.trace);
        assertTrue(oldFollowup.isEmpty() && newFollowup.isEmpty());
    }
}

package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/** Host parity for the reviewed void follow-up and callback-local entity checks. */
public class TransactionCompletionVoidTest {
    private static void legacyExists(TransactionCallableRoomModel room, boolean exists,
                                     List<Throwable> caught, RuntimeException followupFailure) {
        try {
            try {
                room.begin();
                if (!exists)
                    return;
                room.write("exists");
                room.commit();
            } finally {
                room.end();
            }
            room.trace.add("eval");
            if (followupFailure != null)
                throw followupFailure;
        } catch (Throwable ex) {
            caught.add(ex);
        }
    }

    private static void migratedExists(TransactionCallableRoomModel room, boolean exists,
                                       List<Throwable> caught, RuntimeException followupFailure) {
        try {
            if (!TransactionCompletion.call(room::run, room::end, () -> {
                if (!exists)
                    return TransactionResult.rollback(false);
                room.write("exists");
                return TransactionResult.commit(true);
            }))
                return;
            room.trace.add("eval");
            if (followupFailure != null)
                throw followupFailure;
        } catch (Throwable ex) {
            caught.add(ex);
        }
    }

    @Test
    public void bareReturnDoesNotReachEvalOrOuterCatch() {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        List<Throwable> oldCaught = new ArrayList<>();
        List<Throwable> newCaught = new ArrayList<>();
        legacyExists(oldRoom, false, oldCaught, null);
        migratedExists(newRoom, false, newCaught, null);
        assertEquals(Arrays.asList("begin", "end"), oldRoom.trace);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertTrue(oldCaught.isEmpty() && newCaught.isEmpty());
    }

    @Test
    public void successfulVoidEvalIsAfterEndAndItsFailureReachesSameOuterCatch() {
        RuntimeException failure = new IllegalStateException("eval");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        List<Throwable> oldCaught = new ArrayList<>();
        List<Throwable> newCaught = new ArrayList<>();
        legacyExists(oldRoom, true, oldCaught, failure);
        migratedExists(newRoom, true, newCaught, failure);
        assertEquals(Arrays.asList("begin", "write:exists", "commit", "end", "eval"), oldRoom.trace);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(oldRoom.committed, newRoom.committed);
        assertSame(failure, oldCaught.get(0));
        assertSame(failure, newCaught.get(0));
    }

    @Test
    public void voidBeginAndEndFailuresKeepOuterCatchIdentityAndMasking() {
        for (boolean endFails : new boolean[]{false, true}) {
            RuntimeException begin = new IllegalStateException("begin");
            Error end = new AssertionError("end");
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            oldRoom.beginFailure = newRoom.beginFailure = begin;
            if (endFails)
                oldRoom.endFailure = newRoom.endFailure = end;
            List<Throwable> oldCaught = new ArrayList<>();
            List<Throwable> newCaught = new ArrayList<>();
            legacyExists(oldRoom, true, oldCaught, null);
            migratedExists(newRoom, true, newCaught, null);
            assertSame(endFails ? end : begin, oldCaught.get(0));
            assertSame(oldCaught.get(0), newCaught.get(0));
            assertEquals(0, newCaught.get(0).getSuppressed().length);
            assertEquals(Arrays.asList("begin", "end"), oldRoom.trace);
            assertEquals(oldRoom.trace, newRoom.trace);
        }
    }

    private static void legacyIgnore(TransactionCallableRoomModel room, Object messageValue,
                                     Object folderValue) {
        Object message;
        Object folder;
        try {
            room.begin();
            message = messageValue;
            if (message == null)
                return;
            folder = folderValue;
            if (folder == null)
                return;
            room.write("ignored");
            room.commit();
        } finally {
            room.end();
        }
    }

    private static void migratedIgnore(TransactionCallableRoomModel room, Object messageValue,
                                       Object folderValue) {
        TransactionCompletion.call(room::run, room::end, () -> {
            Object message = messageValue;
            if (message == null)
                return TransactionResult.rollback(null);
            Object folder = folderValue;
            if (folder == null)
                return TransactionResult.rollback(null);
            room.write("ignored");
            return TransactionResult.commit(null);
        });
    }

    @Test
    public void genuineCallbackLocalsPreserveBothNullExitsAndSuccess() {
        for (int branch = 0; branch < 3; branch++) {
            Object message = branch == 0 ? null : new Object();
            Object folder = branch == 1 ? null : new Object();
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            legacyIgnore(oldRoom, message, folder);
            migratedIgnore(newRoom, message, folder);
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
            assertEquals(branch == 2 ? Arrays.asList("ignored") : List.of(), newRoom.committed);
        }
    }

    @Test
    public void voidCheckedIoFailureIsNotWrappedAndEarlierWritesRollback() {
        IOException failure = new IOException("file write");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        try {
            try {
                oldRoom.begin();
                oldRoom.write("attachment-delete");
                throw failure;
            } finally {
                oldRoom.end();
            }
        } catch (IOException actual) {
            assertSame(failure, actual);
        }
        try {
            TransactionCompletion.call(newRoom::run, newRoom::end, () -> {
                newRoom.write("attachment-delete");
                throw failure;
            });
            fail("expected IOException");
        } catch (IOException actual) {
            assertSame(failure, actual);
        }
        assertEquals(oldRoom.trace, newRoom.trace);
        assertTrue(oldRoom.committed.isEmpty() && newRoom.committed.isEmpty());
    }

    private static void legacyLockMessage(TransactionCallableRoomModel room, int stop,
                                          IOException failure) throws IOException {
        try {
            room.begin();
            room.write("delete");
            if (stop == 1)
                return;
            room.write("queue");
            if (stop == 2)
                return;
            if (failure != null)
                throw failure;
            room.commit();
        } finally {
            room.end();
        }
    }

    private static void migratedLockMessage(TransactionCallableRoomModel room, int stop,
                                            IOException failure) throws IOException {
        TransactionCompletion.call(room::run, room::end, () -> {
            room.write("delete");
            if (stop == 1)
                return TransactionResult.rollback(null);
            room.write("queue");
            if (stop == 2)
                return TransactionResult.rollback(null);
            if (failure != null)
                throw failure;
            return TransactionResult.commit(null);
        });
    }

    @Test
    public void lockMessageEarlyReturnAfterDeleteAndQueueWritesRollsBack() throws Exception {
        for (int stop : new int[]{1, 2, 0}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            if (stop == 0) {
                legacyLockMessage(oldRoom, stop, null);
                migratedLockMessage(newRoom, stop, null);
                assertEquals(Arrays.asList("delete", "queue"), oldRoom.committed);
            } else {
                legacyLockMessage(oldRoom, stop, null);
                migratedLockMessage(newRoom, stop, null);
                assertTrue(oldRoom.committed.isEmpty());
            }
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
        }
    }

    @Test
    public void lockMessageIOExceptionKeepsIdentityAndRollsBackQueuedWrites() throws Exception {
        IOException failure = new IOException("queue");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        try {
            legacyLockMessage(oldRoom, 0, failure);
            fail("expected IOException");
        } catch (IOException actual) {
            assertSame(failure, actual);
        }
        try {
            migratedLockMessage(newRoom, 0, failure);
            fail("expected IOException");
        } catch (IOException actual) {
            assertSame(failure, actual);
        }
        assertEquals(oldRoom.trace, newRoom.trace);
        assertTrue(oldRoom.committed.isEmpty() && newRoom.committed.isEmpty());
    }

    @Test
    public void lockMessageSuccessCommitsDeleteAndQueueWrites() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        legacyLockMessage(oldRoom, 0, null);
        migratedLockMessage(newRoom, 0, null);
        assertEquals(Arrays.asList("delete", "queue"), oldRoom.committed);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(oldRoom.committed, newRoom.committed);
    }

    @Test
    public void bodyMarkerAndEndFailuresPreserveIdentityWithoutWrappingOrSuppression() throws Exception {
        RuntimeException marker = new IllegalStateException("marker");
        TransactionCallableRoomModel oldMarker = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newMarker = new TransactionCallableRoomModel();
        oldMarker.commitFailure = newMarker.commitFailure = marker;
        try { legacyLockMessage(oldMarker, 0, null); fail("expected marker"); }
        catch (RuntimeException actual) { assertSame(marker, actual); }
        try { migratedLockMessage(newMarker, 0, null); fail("expected marker"); }
        catch (RuntimeException actual) { assertSame(marker, actual); }
        assertEquals(oldMarker.trace, newMarker.trace);
        assertTrue(oldMarker.committed.isEmpty() && newMarker.committed.isEmpty());

        Error end = new AssertionError("end");
        TransactionCallableRoomModel oldEnd = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newEnd = new TransactionCallableRoomModel();
        oldEnd.endFailure = newEnd.endFailure = end;
        try { legacyLockMessage(oldEnd, 1, null); fail("expected end"); }
        catch (Error actual) { assertSame(end, actual); assertEquals(0, actual.getSuppressed().length); }
        try { migratedLockMessage(newEnd, 1, null); fail("expected end"); }
        catch (Error actual) { assertSame(end, actual); assertEquals(0, actual.getSuppressed().length); }
        assertEquals(oldEnd.trace, newEnd.trace);
    }

    @Test
    public void knownDeviceEndFailureIsSwallowedWhenRoomModelSupportsIt() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.swallowKnownEndFailure = newRoom.swallowKnownEndFailure = true;
        IllegalStateException end = new IllegalStateException("no current transaction");
        oldRoom.endFailure = newRoom.endFailure = end;
        legacyLockMessage(oldRoom, 0, null);
        migratedLockMessage(newRoom, 0, null);
        assertEquals(Arrays.asList("delete", "queue"), oldRoom.committed);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(oldRoom.committed, newRoom.committed);
    }

    private static ArrayList<String> legacyMoveThread(TransactionCallableRoomModel room,
                                                       boolean missing,
                                                       ArrayList<String> result) {
        try {
            room.begin();
            if (missing)
                return result;
            room.write("move-thread");
            room.commit();
            return result;
        } finally {
            room.end();
        }
    }

    private static ArrayList<String> migratedMoveThread(TransactionCallableRoomModel room,
                                                         boolean missing,
                                                         ArrayList<String> result) {
        return TransactionCompletion.call(room::run, room::end, () -> {
            if (missing)
                return TransactionResult.rollback(result);
            room.write("move-thread");
            return TransactionResult.commit(result);
        });
    }

    @Test
    public void moveThreadEarlyAndSuccessfulReturnsPreserveListIdentity() {
        for (boolean missing : new boolean[]{true, false}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            ArrayList<String> oldResult = new ArrayList<>();
            ArrayList<String> newResult = new ArrayList<>();
            assertSame(oldResult, legacyMoveThread(oldRoom, missing, oldResult));
            assertSame(newResult, migratedMoveThread(newRoom, missing, newResult));
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
            assertEquals(missing, newRoom.committed.isEmpty());
        }
    }

}

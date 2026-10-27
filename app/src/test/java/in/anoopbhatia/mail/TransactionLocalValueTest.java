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

/** Host recipes for locals crossing Room's synchronous transaction callback boundary. */
public class TransactionLocalValueTest {
    private static <T, E extends Exception> T call(TransactionCallableRoomModel room,
                                                     TransactionCallable<T, E> body)
            throws E {
        return TransactionScope.call(room::run, room::end, body);
    }

    @Test
    public void nullableIdentityAndNonNullIdentityReturnTheExactQueriedReference() throws Exception {
        for (Identity identity : new Identity[]{null, new Identity("alice")}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            Identity returned = call(room, () -> {
                Identity queried = identity;
                room.write("identity-query");
                return queried;
            });
            assertSame(identity, returned);
        }
    }

    @Test
    public void composeRecipeQueriesIdentityThenWritesFourPrimaryFieldsOnlyWhenFound() throws Exception {
        for (Identity expected : new Identity[]{null, new Identity("compose")}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            Identity returned = call(room, () -> {
                room.write("identity-query");
                if (expected != null) {
                    room.write("reset-account-primary");
                    room.write("set-account-primary");
                    room.write("reset-identity-primary");
                    room.write("set-identity-primary");
                }
                return expected;
            });
            assertSame(expected, returned);
            List<String> writes = expected == null
                    ? Arrays.asList("identity-query")
                    : Arrays.asList("identity-query", "reset-account-primary",
                            "set-account-primary", "reset-identity-primary", "set-identity-primary");
            assertEquals(writes, room.committed);
            assertEquals(writes.size() + 3, room.trace.size());
            assertEquals("begin", room.trace.get(0));
            assertEquals("commit", room.trace.get(room.trace.size() - 2));
            assertEquals("end", room.trace.get(room.trace.size() - 1));
        }
    }

    @Test
    public void messageSnapshotAliasIsSameObjectAndFieldMutationsRemainVisibleInRunnable() {
        RunnableRoom room = new RunnableRoom();
        Message message = new Message("before", false);
        Message snapshot = message;
        snapshot.subject = "after";
        snapshot.unread = true;
        TransactionScope.run(room::run, room::end, () -> {
            assertSame(message, snapshot);
            assertEquals("after", snapshot.subject);
            assertTrue(snapshot.unread);
            room.trace.add("message-read");
        });
        assertEquals(Arrays.asList("begin", "message-read", "commit", "end"), room.trace);
        assertEquals("after", message.subject);
        assertTrue(message.unread);
    }

    @Test
    public void coreUidSnapshotUsesExactMissingOrFoundBranchAndFetchesAfterEnd() throws Exception {
        for (Long found : new Long[]{null, 42L}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            final Long transactionFound = found;
            List<String> afterEnd = new ArrayList<>();
            Long seen = call(room, () -> {
                if (transactionFound == null) {
                    room.write("error:Message not found in target folder account/folder msgid=msgid");
                    room.write("unhide");
                    room.write("unbusy");
                } else {
                    room.write("seen");
                    room.write("delete");
                }
                return transactionFound;
            });
            assertSame(found, seen);
            if (found != null) {
                afterEnd.add("fetch:" + found);
                room.trace.add("fetch:" + found);
            }
            assertEquals(found == null ? Arrays.asList(
                    "error:Message not found in target folder account/folder msgid=msgid", "unhide", "unbusy")
                    : Arrays.asList("seen", "delete"), room.committed);
            assertEquals(found == null ? Arrays.asList() : Arrays.asList("fetch:42"), afterEnd);
            assertEquals(found == null ? "end" : "fetch:42", room.trace.get(room.trace.size() - 1));
        }
    }

    @Test
    public void apiPermanentConfirmationIsCallbackLocalAndCapturesPredicatesBeforeMarker() {
        RunnableRoom room = new RunnableRoom();
        List<String> captured = new ArrayList<>();
        final boolean[] confirmation = {false};
        TransactionScope.run(room::run, room::end, () -> {
            boolean apiPermanentConfirmation = false;
            apiPermanentConfirmation |= true;
            apiPermanentConfirmation |= false;
            confirmation[0] = apiPermanentConfirmation;
            captured.add("permanent=true");
            captured.add("marker");
            room.trace.add("confirm");
        });
        assertTrue(confirmation[0]);
        assertEquals(Arrays.asList("permanent=true", "marker"), captured);
        assertEquals(Arrays.asList("begin", "confirm", "commit", "end"), room.trace);
    }

    @Test
    public void corePostEndFetchRunsOnlyAfterSuccessfulTransaction() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        List<String> after = new ArrayList<>();
        call(room, () -> { room.write("set-message"); return 7L; });
        after.add("fetch:7");
        room.trace.add("fetch:7");
        assertEquals(Arrays.asList("set-message"), room.committed);
        assertEquals(Arrays.asList("fetch:7"), after);
        assertEquals("fetch:7", room.trace.get(room.trace.size() - 1));
    }

    @Test
    public void corePostEndFetchSkipsWhenTransactionFails() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException failure = new IllegalStateException("set-message");
        room.commitFailure = failure;
        List<String> after = new ArrayList<>();
        try {
            call(room, () -> { room.write("set-message"); return 7L; });
            after.add("fetch:7");
        } catch (RuntimeException actual) {
            assertSame(failure, actual);
        }
        assertTrue(after.isEmpty());
        assertTrue(room.committed.isEmpty());
    }

    @Test
    public void checkedRuntimeAndErrorBodyFailuresPreserveIdentity() {
        for (Throwable expected : new Throwable[]{new IOException("io"),
                new IllegalStateException("runtime"), new AssertionError("error")}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            try {
                call(room, () -> { throwFailure(expected); return null; });
                fail("expected failure");
            } catch (Throwable actual) {
                assertSame(expected, actual);
            }
            assertEquals("end", room.trace.get(room.trace.size() - 1));
        }
    }

    @Test
    public void beginFailureRunsLegacyCleanupAndDoesNotEnterBody() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException begin = new IllegalStateException("begin");
        room.beginFailure = begin;
        try {
            call(room, () -> { fail("body must not run"); return null; });
            fail("expected begin failure");
        } catch (RuntimeException actual) {
            assertSame(begin, actual);
        }
        assertEquals(Arrays.asList("begin", "end"), room.trace);
    }

    @Test
    public void endFailureMasksBodyFailureWithoutSuppression() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException body = new IllegalStateException("body");
        RuntimeException end = new IllegalStateException("end");
        room.endFailure = end;
        try {
            call(room, () -> { throw body; });
            fail("expected end failure");
        } catch (RuntimeException actual) {
            assertSame(end, actual);
            assertEquals(0, actual.getSuppressed().length);
        }
    }

    @Test
    public void markerCommitFailureSkipsPostTransactionSyncAndEndStillRunsOnce() {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException marker = new IllegalStateException("commit");
        room.commitFailure = marker;
        int[] sync = {0};
        try {
            call(room, () -> "value");
            sync[0]++;
            fail("expected marker failure");
        } catch (RuntimeException actual) {
            assertSame(marker, actual);
        }
        assertEquals(0, sync[0]);
        assertEquals(1, room.trace.stream().filter("end"::equals).count());
    }

    @Test
    public void knownDeviceEndFailuresAreSwallowedOnlyAfterSuccessfulCallback() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        room.swallowKnownEndFailure = true;
        room.endFailure = new IllegalStateException("no current transaction");
        assertEquals("ok", call(room, () -> "ok"));
        assertTrue(room.trace.contains("logged-end"));

        TransactionCallableRoomModel failing = new TransactionCallableRoomModel();
        failing.swallowKnownEndFailure = true;
        failing.endFailure = new IllegalStateException("no transaction is active");
        RuntimeException body = new IllegalStateException("body");
        try {
            call(failing, () -> { throw body; });
            fail("expected body failure");
        } catch (RuntimeException actual) {
            assertSame(body, actual);
        }
    }

    @Test
    public void runnableHelperAlsoKeepsCleanupBeforeCallerFollowUp() {
        RunnableRoom room = new RunnableRoom();
        List<String> followUp = new ArrayList<>();
        TransactionScope.run(room::run, room::end, () -> room.trace.add("body"));
        followUp.add("reload");
        assertEquals(Arrays.asList("begin", "body", "commit", "end", "reload"),
                concat(room.trace, followUp));
    }

    private static void throwFailure(Throwable failure) throws Exception {
        if (failure instanceof IOException) throw (IOException) failure;
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        throw (Error) failure;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static final class Identity {
        final String name;
        Identity(String name) { this.name = name; }
    }

    private static final class Message {
        String subject;
        boolean unread;
        Message(String subject, boolean unread) { this.subject = subject; this.unread = unread; }
    }

    private static final class RunnableRoom {
        final List<String> trace = new ArrayList<>();
        void run(Runnable callback) {
            trace.add("begin");
            try {
                callback.run();
                trace.add("commit");
            } finally {
                end();
            }
        }
        void end() { trace.add("end"); }
    }
}

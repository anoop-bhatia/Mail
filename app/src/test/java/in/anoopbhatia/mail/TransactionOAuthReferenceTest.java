package in.anoopbhatia.mail;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Host boundary recipes; exact production provider branches are source-gated separately. */
public class TransactionOAuthReferenceTest {
    private interface Action { void run(); }
    private static Throwable capture(Action action) {
        try { action.run(); return null; } catch (Throwable failure) { return failure; }
    }
    private static void raise(Throwable failure) {
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new AssertionError(failure);
    }
    private static final class Account {
        final Long id;
        final String user;
        final boolean zoho;
        String name;
        Account(Long id, String user, boolean zoho) {
            this.id = id; this.user = user; this.zoho = zoho;
        }
    }
    private static final class State {
        final List<Account> candidates = new ArrayList<>();
        final List<String> folderTypes = new ArrayList<>(Arrays.asList("Archive", "Inbox"));
        Long accountArg;
        Boolean updatedArg;
        boolean hasIdentity = true;
        String identityName;
    }
    private static Account body(TransactionCallableRoomModel room, State state, String user,
                                String displayName, boolean update, Throwable failure) {
        Account matchedAccount = null;
        if (update)
            for (Account candidate : state.candidates)
                if (candidate.zoho && user.equalsIgnoreCase(candidate.user)) {
                    if (matchedAccount != null) throw new IllegalArgumentException("duplicate");
                    matchedAccount = candidate;
                }
        Account account;
        if (matchedAccount == null) {
            account = new Account(40L, user, true);
            room.write("insert-account");
        } else {
            account = matchedAccount;
            room.write("update-account");
        }
        // Like EntityAccount/Bundle mutations, these are not DB rollback-aware carriers.
        account.name = displayName;
        state.accountArg = account.id;
        room.trace.add("args:account");
        boolean inbox = false;
        for (String type : state.folderTypes) {
            room.write("folder:" + type);
            if ("Inbox".equals(type)) inbox = true;
        }
        if (!inbox) throw new IllegalArgumentException("missing selectable Inbox");
        state.identityName = displayName;
        if (matchedAccount == null || !state.hasIdentity)
            room.write("insert-identity");
        else
            room.write("update-identity");
        raise(failure);
        return matchedAccount;
    }
    private static Account oauth(boolean modern, TransactionCallableRoomModel room, State state,
                                 String displayName, boolean update, Throwable failure) {
        Account existing;
        if (modern) {
            final String accountDisplayName = displayName;
            existing = TransactionScope.call(room::run, room::end,
                    () -> body(room, state, "user@example.com", accountDisplayName, update, failure));
        } else {
            try {
                room.begin();
                existing = body(room, state, "user@example.com", displayName, update, failure);
                room.commit();
            } finally { room.end(); }
        }
        state.updatedArg = existing != null;
        room.trace.add("args:updated:" + state.updatedArg);
        room.trace.add("log");
        room.trace.add("eval");
        room.trace.add("backup");
        return existing;
    }
    private static State existingState() {
        State state = new State();
        state.candidates.add(new Account(7L, "USER@example.com", true));
        return state;
    }
    private static void assertNoConsumers(TransactionCallableRoomModel room, State state) {
        assertNull(state.updatedArg);
        for (String consumer : new String[]{"log", "eval", "backup"})
            assertFalse(room.trace.contains(consumer));
    }
    @Test public void newReturnsNullExistingReturnsSameReferenceAndConsumersStayAfterEnd() {
        for (boolean modern : new boolean[]{false, true})
            for (boolean update : new boolean[]{false, true}) {
                State state = existingState();
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                Account returned = oauth(modern, room, state, "Name", update, null);
                if (update) assertSame(state.candidates.get(0), returned);
                else assertNull(returned);
                assertEquals(update ? Long.valueOf(7) : Long.valueOf(40), state.accountArg);
                assertEquals(Arrays.asList(update ? "update-account" : "insert-account",
                        "folder:Archive", "folder:Inbox", update ? "update-identity" : "insert-identity"), room.committed);
                assertEquals(Arrays.asList("end", "args:updated:" + update, "log", "eval", "backup"),
                        room.trace.subList(room.trace.indexOf("end"), room.trace.size()));
            }
    }
    @Test public void matchingRemainsUpdateOnlyProviderSpecificCaseInsensitiveAndDuplicateSafe() {
        for (boolean modern : new boolean[]{false, true}) {
            State state = existingState();
            state.candidates.add(new Account(8L, "user@example.com", false));
            state.candidates.add(new Account(9L, "other@example.com", true));
            assertSame(state.candidates.get(0), oauth(modern, new TransactionCallableRoomModel(), state, "Name", true, null));
            State duplicate = existingState();
            duplicate.candidates.add(new Account(8L, "user@example.com", true));
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertTrue(capture(() -> oauth(modern, room, duplicate, "Name", true, null)) instanceof IllegalArgumentException);
            assertEquals(Arrays.asList("begin", "end"), room.trace);
            assertTrue(room.committed.isEmpty());
            assertNull(duplicate.accountArg);
            assertNoConsumers(room, duplicate);
        }
    }
    @Test public void missingInboxRollsBackPriorWritesButKeepsAccountBundleValue() {
        for (boolean modern : new boolean[]{false, true}) {
            State state = new State(); state.folderTypes.remove("Inbox");
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertTrue(capture(() -> oauth(modern, room, state, "Name", false, null)) instanceof IllegalArgumentException);
            assertEquals(Arrays.asList("begin", "write:insert-account", "args:account", "write:folder:Archive", "end"), room.trace);
            assertTrue(room.committed.isEmpty()); assertEquals(Long.valueOf(40), state.accountArg);
            assertNoConsumers(room, state);
        }
    }
    @Test public void existingWithoutIdentityStillInsertsIdentityAndSnapshotUsesOriginalReference() {
        for (boolean modern : new boolean[]{false, true}) {
            State state = existingState(); state.hasIdentity = false;
            String normalized = new String("Normalized Name");
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            Account result = oauth(modern, room, state, normalized, true, null);
            assertSame(state.candidates.get(0), result);
            assertSame(normalized, state.identityName);
            assertSame(normalized, result.name);
            assertEquals(Arrays.asList("update-account", "folder:Archive", "folder:Inbox", "insert-identity"), room.committed);
        }
    }
    @Test public void bodyRuntimeAndErrorFailuresKeepReferenceMutationsButRollBackDatabaseWrites() {
        for (boolean modern : new boolean[]{false, true})
            for (Throwable failure : new Throwable[]{new IllegalStateException("body"), new AssertionError("body")}) {
                State state = existingState();
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                assertSame(failure, capture(() -> oauth(modern, room, state, "Changed", true, failure)));
                assertTrue(room.committed.isEmpty());
                assertEquals("Changed", state.candidates.get(0).name);
                assertEquals(Long.valueOf(7), state.accountArg);
                assertEquals(Arrays.asList("begin", "write:update-account", "args:account", "write:folder:Archive",
                        "write:folder:Inbox", "write:update-identity", "end"), room.trace);
                assertNoConsumers(room, state);
            }
    }
    @Test public void beginMarkerAndEndFailurePreserveExactCleanupIdentityAndSkipConsumers() {
        for (boolean modern : new boolean[]{false, true})
            for (int stage = 0; stage < 3; stage++) {
                State state = new State(); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                RuntimeException failure = new IllegalStateException("failure");
                if (stage == 0) room.beginFailure = failure;
                if (stage == 1) room.commitFailure = failure;
                if (stage == 2) room.endFailure = failure;
                assertSame(failure, capture(() -> oauth(modern, room, state, "Name", false, null)));
                assertEquals(stage == 0 ? Arrays.asList("begin", "end") : Arrays.asList("begin", "write:insert-account",
                        "args:account", "write:folder:Archive", "write:folder:Inbox", "write:insert-identity", "commit", "end"), room.trace);
                assertEquals(stage == 2 ? Arrays.asList("insert-account", "folder:Archive", "folder:Inbox", "insert-identity") : Collections.emptyList(), room.committed);
                assertNoConsumers(room, state);
            }
    }
    @Test public void endFailureMasksBeginBodyAndMarkerFailureWithExactlyOneCleanup() {
        for (boolean modern : new boolean[]{false, true})
            for (int stage = 0; stage < 3; stage++) {
                State state = new State(); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                RuntimeException original = new IllegalStateException("original");
                Error end = new AssertionError("end"); room.endFailure = end;
                if (stage == 0) room.beginFailure = original;
                if (stage == 2) room.commitFailure = original;
                Throwable bodyFailure = stage == 1 ? original : null;
                assertSame(end, capture(() -> oauth(modern, room, state, "Name", false, bodyFailure)));
                assertEquals(1, Collections.frequency(room.trace, "end"));
                assertTrue(room.committed.isEmpty()); assertNoConsumers(room, state);
            }
    }
    @Test public void nestedSuccessRemainsSubjectToOuterRollbackWithoutUndoingCarriers() {
        for (boolean modern : new boolean[]{false, true}) {
            State state = existingState(); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            room.begin();
            assertSame(state.candidates.get(0), oauth(modern, room, state, "Name", true, null));
            assertTrue(room.committed.isEmpty());
            room.end();
            assertTrue(room.committed.isEmpty());
            assertEquals(Boolean.TRUE, state.updatedArg);
            assertEquals(Long.valueOf(7), state.accountArg);
            assertEquals(2, Collections.frequency(room.trace, "end"));
        }
    }
}

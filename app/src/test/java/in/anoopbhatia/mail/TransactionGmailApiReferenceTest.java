package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Host model of FragmentGmailApi's account-selection transaction only.
 * This is not a Gmail/provider or Android database integration test.
 */
public class TransactionGmailApiReferenceTest {
    private interface Action { void run() throws Exception; }

    private static Throwable capture(Action action) {
        try { action.run(); return null; }
        catch (Throwable failure) { return failure; }
    }

    private static void rethrow(Throwable failure) throws Exception {
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError(failure);
    }

    private static final class Account {
        final Long id;
        final boolean gmail;
        String user;
        String provider;
        String password;
        String name;
        boolean synchronize;
        Account(Long id, boolean gmail, String user) {
            this.id = id; this.gmail = gmail; this.user = user;
        }
    }

    private static final class State {
        final List<Account> accounts = new ArrayList<>();
        final List<String> writes = new ArrayList<>();
        Long accountArg;
        Account createdAccount;
        Boolean updatedArg;
        String identityName;
        boolean identityExists = true;
        String mailbox = "user@example.com";
        String selectedName;
        String selectedPassword = "gmail-state";
    }

    private static final class Checked extends Exception {
        Checked(String message) { super(message); }
    }

    /* The body preserves the production branch: matching is update-only, Gmail-only,
       and strips only the historic recent: prefix before case-insensitive comparison. */
    private static Account body(TransactionCallableRoomModel room, State state,
                                boolean update, String name, Throwable failure)
            throws Exception {
        Account matched = null;
        String user = state.mailbox;
        if (update) {
            for (Account candidate : state.accounts) {
                String candidateUser = candidate.user == null ? null :
                        candidate.user.replaceFirst("(?i)^recent:", "");
                if (candidate.gmail && candidateUser != null &&
                        user.equalsIgnoreCase(candidateUser)) {
                    if (matched != null)
                        throw new IllegalArgumentException("duplicate Gmail account");
                    matched = candidate;
                }
            }
        }

        Account account;
        if (matched == null) {
            account = new Account(40L, true, user);
            account.provider = "gmail";
            account.password = state.selectedPassword;
            account.name = "generated-name";
            account.synchronize = true;
            state.createdAccount = account;
            room.write("insert-account");
            state.accountArg = account.id;
            room.write("insert-folder");
            // Production falls back from an empty display name before inserting identity.
            state.identityName = name == null || name.isEmpty()
                    ? state.mailbox.substring(0, state.mailbox.indexOf('@')) : name;
            room.write("insert-identity");
        } else {
            account = matched;
            account.provider = "gmail";
            account.user = user;
            account.password = state.selectedPassword;
            account.synchronize = true;
            state.accountArg = account.id;
            room.write("update-account");
            // Only existing Gmail identities are touched; unrelated identities are not modeled.
            if (state.identityExists)
                room.write("update-identity-password-provider");
        }
        if (failure != null) rethrow(failure);
        return matched; // Actual onExecute returns null after the transaction; this is its local result.
    }

    private static Account run(boolean modern, TransactionCallableRoomModel room,
                               State state, boolean update, String name,
                               Throwable failure) throws Exception {
        Account result;
        if (modern)
            result = TransactionScope.call(room::run, room::end,
                    () -> body(room, state, update, name, failure));
        else {
            try {
                room.begin();
                result = body(room, state, update, name, failure);
                room.commit();
            } finally { room.end(); }
        }
        state.updatedArg = result != null;
        if (result == null)
            room.trace.add("post:new-account-folder-defaults-pending");
        room.trace.add("post:updated");
        room.trace.add("post:sync-eval");
        room.trace.add("post:backup");
        return result;
    }

    private static State existing() {
        State state = new State();
        state.accounts.add(new Account(7L, true, "recent:USER@example.com"));
        return state;
    }

    private static void assertNoPostConsumers(TransactionCallableRoomModel room, State state) {
        assertNull(state.updatedArg);
        for (String marker : Arrays.asList("post:updated", "post:sync-eval", "post:backup"))
            assertFalse(room.trace.contains(marker));
    }

    private static void assertSuccessfulPostTrace(TransactionCallableRoomModel room,
                                                   boolean newAccount) {
        List<String> expected = new ArrayList<>();
        if (newAccount)
            expected.add("post:new-account-folder-defaults-pending");
        expected.add("post:updated");
        expected.add("post:sync-eval");
        expected.add("post:backup");
        assertTrue(room.trace.size() >= expected.size());
        assertEquals(expected, room.trace.subList(room.trace.size() - expected.size(),
                room.trace.size()));
    }

    @Test public void newReturnsNullAndExistingReturnsSameReferenceAcrossBothBoundaries() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State created = new State();
            Account newResult = run(modern, new TransactionCallableRoomModel(), created, false, "Display", null);
            assertNull(newResult);
            assertEquals(Boolean.FALSE, created.updatedArg);
            State updated = existing();
            Account old = updated.accounts.get(0);
            TransactionCallableRoomModel updatedRoom = new TransactionCallableRoomModel();
            Account oldResult = run(modern, updatedRoom, updated, true, "Display", null);
            assertSame(old, oldResult);
            assertEquals(Boolean.TRUE, updated.updatedArg);
            assertSuccessfulPostTrace(updatedRoom, false);
        }
    }

    @Test public void providerPasswordAndNameBranchesArePracticalButNotProviderExecution() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State created = new State();
            TransactionCallableRoomModel createdRoom = new TransactionCallableRoomModel();
            run(modern, createdRoom, created, false, "", null);
            assertEquals("gmail", created.createdAccount == null ? null : created.createdAccount.provider);
            // The host model records the same account credentials/name decisions without Gmail API calls.
            assertEquals("gmail-state", created.selectedPassword);
            assertEquals("user", created.identityName);
            assertSuccessfulPostTrace(createdRoom, true);
            State updated = existing();
            Account account = updated.accounts.get(0);
            run(modern, new TransactionCallableRoomModel(), updated, true, "Ignored", null);
            assertEquals("gmail", account.provider);
            assertEquals("gmail-state", account.password);
            assertTrue(account.synchronize);
        }
    }

    @Test public void matchingIsUpdateOnlyGmailCaseInsensitiveRecentAwareAndDuplicateSafe() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State state = existing();
            state.accounts.add(new Account(8L, false, "user@example.com"));
            assertSame(state.accounts.get(0), run(modern, new TransactionCallableRoomModel(), state, true, "N", null));
            State duplicate = existing();
            duplicate.accounts.add(new Account(9L, true, "user@example.com"));
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            Throwable failure = capture(() -> run(modern, room, duplicate, true, "N", null));
            assertTrue(failure instanceof IllegalArgumentException);
            assertEquals(Arrays.asList("begin", "end"), room.trace);
            assertNoPostConsumers(room, duplicate);
        }
    }

    @Test public void bodyFailureRollsBackWritesButKeepsEntityAndBundleMutations() {
        for (boolean modern : new boolean[]{false, true})
            for (Throwable failure : new Throwable[]{new Checked("checked"),
                    new IllegalStateException("runtime"), new AssertionError("error")}) {
                State state = existing();
                Account account = state.accounts.get(0);
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                Throwable actual = capture(() -> run(modern, room, state, true, "N", failure));
                assertSame(failure, actual);
                assertTrue(room.committed.isEmpty());
                assertEquals(Long.valueOf(7), state.accountArg);
                assertEquals("gmail-state", account.password);
                assertNoPostConsumers(room, state);
            }
    }

    @Test public void beginMarkerAndEndFailureIdentityAndCleanupMatchLegacyShape() {
        for (boolean modern : new boolean[]{false, true})
            for (int stage = 0; stage < 3; stage++) {
                State state = new State();
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                RuntimeException failure = new IllegalStateException("failure");
                if (stage == 0) room.beginFailure = failure;
                if (stage == 1) room.commitFailure = failure;
                if (stage == 2) room.endFailure = failure;
                Throwable actual = capture(() -> run(modern, room, state, false, "N", null));
                assertSame(failure, actual);
                assertEquals(1, Collections.frequency(room.trace, "end"));
                assertNoPostConsumers(room, state);
            }
    }

    @Test public void endFailureMasksBodyAndCommitWithoutSuppressionOrDuplicateCleanup() {
        for (boolean modern : new boolean[]{false, true})
            for (String stage : Arrays.asList("body", "commit")) {
                State state = existing();
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                Error end = new AssertionError("end");
                room.endFailure = end;
                Throwable original = stage.equals("body") ? new IllegalStateException("body")
                        : new IllegalStateException("commit");
                if (stage.equals("commit")) room.commitFailure = original;
                Throwable actual = capture(() -> run(modern, room, state, true, "N",
                        stage.equals("body") ? original : null));
                assertSame(end, actual);
                assertEquals(0, actual.getSuppressed().length);
                assertEquals(1, Collections.frequency(room.trace, "end"));
                assertNoPostConsumers(room, state);
            }
    }

    @Test public void nestedRollbackPoisonsOuterAndPostEndConsumersOnlyFollowSuccessfulOuterEnd() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State state = existing();
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            room.begin();
            try {
                Throwable inner = capture(() -> run(modern, room, state, true, "N",
                        new IOException("inner")));
                assertTrue(inner instanceof IOException);
                assertTrue(room.committed.isEmpty());
                assertNull(state.updatedArg);
            } finally { room.end(); }
            assertTrue(room.committed.isEmpty());
            assertEquals(2, Collections.frequency(room.trace, "end"));
        }
    }

    @Test public void postEndConsumersAreNotRunWhenCleanupEndFails() {
        for (boolean modern : new boolean[]{false, true}) {
            State state = new State();
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            room.endFailure = new IllegalStateException("end");
            Throwable failure = capture(() -> run(modern, room, state, false, "N", null));
            assertEquals("end", failure.getMessage());
            assertNoPostConsumers(room, state);
        }
    }
}

package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Host-model recipes for FragmentOAuth's Microsoft Graph branch only.
 * This deliberately does not execute a provider, Android, or Room implementation.
 */
public class TransactionGraphOAuthReferenceTest {
    private interface Action { void run() throws Exception; }
    private static Throwable capture(Action action) {
        try { action.run(); return null; } catch (Throwable failure) { return failure; }
    }
    private static void raise(Throwable failure) throws Exception {
        if (failure == null) return;
        if (failure instanceof Exception) throw (Exception) failure;
        if (failure instanceof Error) throw (Error) failure;
        throw new AssertionError(failure);
    }

    private static final String GRAPH = "graph";
    private static final class Account {
        final Long id; final String provider; String user, name, token;
        Account(Long id, String provider, String user) { this.id = id; this.provider = provider; this.user = user; }
    }
    private static final class Folder {
        final String remote; Long account; boolean special;
        Folder(String remote) { this.remote = remote; }
    }
    private static final class State {
        final List<Account> accounts = new ArrayList<>();
        final List<Folder> remoteFolders = new ArrayList<>();
        final List<Folder> existingFolders = new ArrayList<>();
        final List<String> identities = new ArrayList<>();
        String principal = "USER@example.com", displayName = "Graph User";
        String token = "graph-token";
        Long accountArg; Boolean updatedArg; Account created; String queryUser;
    }

    private static Account existing(String provider, String user) { return new Account(7L, provider, user); }

    private static String nativeApiAccountName(String user) {
        if (user == null) return null;
        int at = user.indexOf('@');
        return "Microsoft Mail/" + (at < 0 ? user : user.substring(0, at));
    }

    private static void mutateOAuthInputs(State s) {
        s.principal = "mutated@example.com";
        s.displayName = "Mutated";
    }

    private static Account body(TransactionCallableRoomModel room, State s, boolean update,
                                String userSnapshot, String displaySnapshot, Throwable failure)
            throws Exception {
        Account existing = null;
        Long accountId;
        if (update) {
            s.queryUser = userSnapshot;
            room.trace.add("query-accounts");
            for (Account candidate : s.accounts) {
                if (GRAPH.equals(candidate.provider) && userSnapshot != null &&
                        userSnapshot.equalsIgnoreCase(candidate.user)) {
                    if (existing != null) throw new IllegalArgumentException("duplicate Graph account");
                    existing = candidate;
                }
            }
        }
        Account account = existing;
        if (account == null) {
            account = new Account(40L, GRAPH, userSnapshot);
            account.name = nativeApiAccountName(userSnapshot); account.token = s.token; s.created = account;
            room.write("insert-account"); accountId = account.id; s.accountArg = accountId;
        } else {
            accountId = account.id; s.accountArg = accountId;
            account.user = userSnapshot;
            account.token = s.token;
            room.write("set-account-synchronize");
            room.write("set-account-token-provider");
            room.write("set-identity-token-provider");
        }
        for (Folder remote : s.remoteFolders) {
            Folder found = null;
            for (Folder local : s.existingFolders)
                if (local.remote.equals(remote.remote)) { found = local; break; }
            room.trace.add("query-folder:" + remote.remote);
            if (found == null) {
                remote.account = accountId; remote.special = true;
                room.write("insert-folder:" + remote.remote);
            } else {
                found.account = accountId; found.special = true;
                room.write("update-folder:" + remote.remote);
            }
        }
        if (existing == null) {
            room.write("insert-identity:" + displaySnapshot);
            s.identities.add(displaySnapshot);
        }
        raise(failure);
        return existing; // Nullable by design: creation returns null, exact update returns the same entity.
    }

    private static Account run(boolean modern, TransactionCallableRoomModel room, State s,
                               boolean update, Throwable failure) throws Exception {
        // These are callback-local snapshots: helper execution cannot change their source values.
        final String userSnapshot = s.principal;
        final String displaySnapshot = s.displayName;
        // Simulate a helper changing source values after callback-local snapshots were taken.
        mutateOAuthInputs(s);
        Account existing;
        if (modern)
            existing = TransactionScope.call(room::run, room::end,
                    () -> body(room, s, update, userSnapshot, displaySnapshot, failure));
        else {
            try { room.begin(); existing = body(room, s, update, userSnapshot, displaySnapshot, failure); room.commit(); }
            finally { room.end(); }
        }
        s.updatedArg = existing != null;
        room.trace.add("updated"); room.trace.add("log"); room.trace.add("eval"); room.trace.add("backup");
        return existing;
    }

    private static State fresh() {
        State s = new State(); s.remoteFolders.add(new Folder("Inbox")); s.remoteFolders.add(new Folder("Archive"));
        return s;
    }
    private static void assertNoPostEffects(TransactionCallableRoomModel room, State s) {
        assertNull(s.updatedArg);
        assertFalse(room.trace.contains("log")); assertFalse(room.trace.contains("eval")); assertFalse(room.trace.contains("backup"));
    }

    @Test public void nullableReturnAndNoExactDuplicateMatchAreEquivalent() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State none = fresh();
            assertNull(run(modern, new TransactionCallableRoomModel(), none, true, null));
            assertEquals(Boolean.FALSE, none.updatedArg);
            State exact = fresh(); Account old = existing(GRAPH, "user@example.com"); exact.accounts.add(old);
            assertSame(old, run(modern, new TransactionCallableRoomModel(), exact, true, null));
            State duplicate = fresh(); duplicate.accounts.add(existing(GRAPH, "user@example.com")); duplicate.accounts.add(existing(GRAPH, "USER@example.com"));
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            Throwable failure = capture(() -> run(modern, room, duplicate, true, null));
            assertTrue(failure instanceof IllegalArgumentException); assertEquals(Arrays.asList("begin", "query-accounts", "end"), room.trace); assertNoPostEffects(room, duplicate);
        }
    }

    @Test public void matchingIsCaseInsensitiveButGraphProviderAndUpdateFlagRemainRequired() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State s = fresh(); Account graph = existing(GRAPH, "User@Example.com"); s.accounts.add(graph);
            s.accounts.add(existing("imap", "user@example.com"));
            assertSame(graph, run(modern, new TransactionCallableRoomModel(), s, true, null));
            State disabled = fresh(); Account other = existing(GRAPH, "user@example.com"); disabled.accounts.add(other);
            assertNull(run(modern, new TransactionCallableRoomModel(), disabled, false, null));
            assertNotSame(other, disabled.created);
        }
    }

    @Test public void createAndUpdateAccountReferencesAndEntityMutationsMatch() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State created = fresh(); TransactionCallableRoomModel cr = new TransactionCallableRoomModel();
            assertNull(run(modern, cr, created, false, null));
            assertEquals(Long.valueOf(40), created.accountArg); assertEquals("Microsoft Mail/USER", created.created.name);
            State updated = fresh(); Account old = existing(GRAPH, "user@example.com"); old.name = "old"; updated.accounts.add(old);
            assertSame(old, run(modern, new TransactionCallableRoomModel(), updated, true, null));
            assertEquals(Long.valueOf(7), updated.accountArg); assertEquals("old", old.name);
            assertEquals("USER@example.com", old.user); assertEquals("graph-token", old.token);
        }
    }

    @Test public void remoteFolderInsertAndIdentityOrderingArePinned() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State s = fresh(); s.existingFolders.add(new Folder("Inbox"));
            TransactionCallableRoomModel room = new TransactionCallableRoomModel(); run(modern, room, s, false, null);
            assertEquals(Arrays.asList("begin", "write:insert-account", "query-folder:Inbox", "write:update-folder:Inbox", "query-folder:Archive", "write:insert-folder:Archive", "write:insert-identity:Graph User", "commit", "end", "updated", "log", "eval", "backup"), room.trace);
            assertNull(s.remoteFolders.get(0).account); assertEquals(Long.valueOf(40), s.remoteFolders.get(1).account); assertTrue(s.remoteFolders.get(1).special);
        }
    }

    @Test public void snapshotLocalsProtectUserAndDisplayNameFromHelperMutation() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State s = fresh(); s.principal = new String("User@example.com"); s.displayName = new String("Before");
            run(modern, new TransactionCallableRoomModel(), s, false, null);
            assertEquals("User@example.com", s.created.user); assertEquals("Microsoft Mail/User", s.created.name); assertEquals("Before", s.identities.get(0));
        }
    }

    @Test public void bodyMutationFailureRollsBackWritesButRetainsMarkersAndSkipsConsumers() {
        for (boolean modern : new boolean[]{false, true}) {
            State s = fresh(); RuntimeException failure = new IllegalStateException("body"); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertSame(failure, capture(() -> run(modern, room, s, false, failure)));
            assertTrue(room.committed.isEmpty()); assertEquals(Long.valueOf(40), s.accountArg); assertEquals(Long.valueOf(40), s.remoteFolders.get(0).account); assertNoPostEffects(room, s);
        }
    }

    @Test public void beginCommitAndMarkerFailuresHaveOneCleanupAndNoPostEffects() {
        for (boolean modern : new boolean[]{false, true}) for (int stage = 0; stage < 2; stage++) {
            State s = fresh(); TransactionCallableRoomModel room = new TransactionCallableRoomModel(); RuntimeException failure = new IllegalStateException("failure");
            if (stage == 0) room.beginFailure = failure; if (stage == 1) room.commitFailure = failure;
            Throwable actual = capture(() -> run(modern, room, s, false, null));
            assertSame(failure, actual); assertEquals(1, Collections.frequency(room.trace, "end")); assertNoPostEffects(room, s);
        }
    }

    @Test public void endFailureMasksBodyOrCommitAndPostEndEffectsOnlyFollowSuccessfulEnd() {
        for (boolean modern : new boolean[]{false, true}) for (String earlier : new String[]{"body", "commit", "success"}) {
            State s = fresh(); TransactionCallableRoomModel room = new TransactionCallableRoomModel(); AssertionError end = new AssertionError("end"); room.endFailure = end;
            RuntimeException prior = new IllegalStateException(earlier); if ("commit".equals(earlier)) room.commitFailure = prior;
            Throwable actual = capture(() -> run(modern, room, s, false, "body".equals(earlier) ? prior : null));
            assertSame(end, actual); assertEquals(1, Collections.frequency(room.trace, "end")); assertNoPostEffects(room, s);
        }
    }

    @Test public void successfulPostEndLogEvalBackupOrderAndUpdatedMarkerArePinned() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State s = fresh(); Account old = existing(GRAPH, "user@example.com"); s.accounts.add(old); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertSame(old, run(modern, room, s, true, null));
            int end = room.trace.indexOf("end"); assertEquals(Arrays.asList("end", "updated", "log", "eval", "backup"), room.trace.subList(end, room.trace.size())); assertEquals(Boolean.TRUE, s.updatedArg);
        }
    }
}

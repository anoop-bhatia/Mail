package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Host-only recipe for FragmentOAuth's legacy transaction and its callable replacement. */
public class TransactionLegacyOAuthReferenceTest {
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

    private static final int IMAP = 1, POP = 2;
    private static final class Account {
        final Long id; final int protocol; final String user;
        String name, password, token, auth, provider, swipeLeft, swipeRight; boolean synchronize;
        Account(Long id, int protocol, String user) { this.id = id; this.protocol = protocol; this.user = user; }
    }
    private static final class Folder {
        final String name; Long account; boolean specials; boolean synchronize;
        Folder(String name, boolean synchronize) { this.name = name; this.synchronize = synchronize; }
    }
    private static final class Identity {
        String auth, password; Long maxSize; String user;
    }
    private static final class State {
        final List<Account> accounts = new ArrayList<>();
        final List<Folder> sourceFolders = new ArrayList<>();
        final List<Folder> existingFolders = new ArrayList<>();
        final List<String> state = new ArrayList<>();
        Long accountArg; Boolean popArg, updatedArg; Identity identity;
        String accountTokenArg, accountAuthArg, identityTokenArg, identityAuthArg, identityUserArg;
        Account created; String queryUser; int queryProtocol; String postFailure;
        boolean inboundOnly, policyAllowed = true;
        String principal = "user@example.com", sharedname;
        Long maxSize = null, maxSizeSnapshot;
    }
    private static Account existing(int protocol, String user) {
        return new Account(7L, protocol, user);
    }

    private static Account body(TransactionCallableRoomModel room, State s, boolean update,
                                String principal, String sharedname, boolean pop,
                                Long maxSizeSnapshot, Throwable failure) throws Exception {
        int protocol = pop ? POP : IMAP;
        String inboundUser = sharedname == null ? principal : sharedname;
        Account matched = null;
        if (update) {
            s.queryUser = inboundUser; s.queryProtocol = protocol; room.trace.add("query-accounts");
            List<Account> matches = new ArrayList<>();
            for (Account candidate : s.accounts)
                if (candidate.protocol == protocol && candidate.user.equals(inboundUser)) matches.add(candidate);
            if (matches.size() == 1) matched = matches.get(0);
        }
        if (matched == null) {
            Account account = new Account(40L, protocol, inboundUser); s.created = account;
            account.password = s.state.get(0); account.token = s.state.get(0); account.auth = "OAUTH"; account.provider = "provider"; account.synchronize = true;
            room.write("insert-account"); s.accountArg = account.id;
            for (Folder source : s.sourceFolders) {
                Folder found = null;
                for (Folder candidate : s.existingFolders)
                    if (candidate.name.equals(source.name)) { found = candidate; break; }
                room.trace.add("query-folder:" + source.name);
                if (found == null) {
                    source.account = account.id; source.specials = true;
                    room.write("insert-folder:" + source.name);
                    if (source.synchronize) room.trace.add("sync-folder:" + source.name);
                }
            }
            if (pop) { account.swipeLeft = "delete"; account.swipeRight = "seen"; }
            else { account.swipeLeft = "default-left"; account.swipeRight = "default-right"; }
            room.write("update-account");
            if (!s.inboundOnly) {
                Identity identity = new Identity(); identity.auth = s.state.size() == 1 ? "OAUTH" : "GRAPH";
                identity.password = s.state.get(s.state.size() - 1); identity.user = principal;
                identity.maxSize = maxSizeSnapshot; s.identity = identity; room.write("insert-identity");
            }
            s.popArg = pop;
        } else {
            s.accountArg = matched.id; room.write("set-account-synchronize");
            s.accountTokenArg = s.state.get(0); s.accountAuthArg = "OAUTH";
            s.identityTokenArg = s.state.get(s.state.size() - 1);
            s.identityAuthArg = s.state.size() == 1 ? "OAUTH" : "GRAPH";
            s.identityUserArg = principal;
            room.write("set-account-password-provider"); room.write("set-identity-password-provider");
        }
        raise(failure);
        return matched;
    }

    private static Account run(boolean modern, TransactionCallableRoomModel room, State s,
                               boolean update, boolean pop, Throwable failure) throws Exception {
        // This source-gated policy check deliberately remains outside the transaction.
        if (!s.policyAllowed) throw new IllegalArgumentException("policy");
        final String principal = s.principal; final String sharedname = s.sharedname;
        final boolean requestedUpdate = update, requestedPop = pop;
        final Long maxSizeSnapshot = s.maxSize; s.maxSizeSnapshot = maxSizeSnapshot;
        Account result;
        if (modern)
            result = TransactionScope.call(room::run, room::end,
                    () -> body(room, s, requestedUpdate, principal, sharedname, requestedPop, maxSizeSnapshot, failure));
        else {
            try { room.begin(); result = body(room, s, requestedUpdate, principal, sharedname, requestedPop, maxSizeSnapshot, failure); room.commit(); }
            finally { room.end(); }
        }
        room.trace.add("done"); room.trace.add("sync-eval");
        if ("sync-eval".equals(s.postFailure)) throw new IllegalStateException("sync-eval");
        s.updatedArg = result != null; room.trace.add("args:updated");
        if ("args:updated".equals(s.postFailure)) throw new IllegalStateException("args:updated");
        room.trace.add("backup");
        if ("backup".equals(s.postFailure)) throw new IllegalStateException("backup");
        return result;
    }
    private static State fresh() {
        State s = new State(); s.state.add("token-0"); s.sourceFolders.add(new Folder("Inbox", true));
        s.sourceFolders.add(new Folder("Archive", false)); return s;
    }
    private static void noPostConsumers(TransactionCallableRoomModel room, State s) {
        assertNull(s.updatedArg); assertFalse(room.trace.contains("done")); assertFalse(room.trace.contains("sync-eval"));
        assertFalse(room.trace.contains("args:updated")); assertFalse(room.trace.contains("backup"));
    }

    @Test public void exactOneMatchReturnsSameReferenceButNullEmptyAndMultipleCreate() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State exact = fresh(); Account old = existing(IMAP, "user@example.com"); exact.accounts.add(old);
            assertSame(old, run(modern, new TransactionCallableRoomModel(), exact, true, false, null));
            State nil = fresh(); assertNull(run(modern, new TransactionCallableRoomModel(), nil, true, false, null));
            State many = fresh(); many.accounts.add(existing(IMAP, "user@example.com")); many.accounts.add(existing(IMAP, "user@example.com"));
            assertNull(run(modern, new TransactionCallableRoomModel(), many, true, false, null));
            assertEquals(Boolean.FALSE, nil.updatedArg); assertEquals(Boolean.FALSE, many.updatedArg);
        }
    }

    @Test public void legacyAndModernNewPopImapHaveFolderGuardsAndSwipeParity() throws Exception {
        for (boolean modern : new boolean[]{false, true}) for (boolean pop : new boolean[]{false, true}) {
            State s = fresh(); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertNull(run(modern, room, s, false, pop, null));
            assertEquals(Arrays.asList("begin", "write:insert-account", "query-folder:Inbox", "write:insert-folder:Inbox",
                    "sync-folder:Inbox", "query-folder:Archive", "write:insert-folder:Archive", "write:update-account",
                    "write:insert-identity", "commit", "end", "done", "sync-eval", "args:updated", "backup"), room.trace);
            assertEquals(pop ? "delete" : "default-left", s.created.swipeLeft); assertEquals(Boolean.valueOf(pop), s.popArg);
        }
    }

    @Test public void inboundOnlyNewOmitsIdentityButExistingUsesDbSettersWithoutEntityMutation() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State inbound = fresh(); inbound.inboundOnly = true; TransactionCallableRoomModel r = new TransactionCallableRoomModel();
            assertNull(run(modern, r, inbound, false, false, null)); assertNull(inbound.identity);
            State update = fresh(); Account old = existing(IMAP, "user@example.com"); old.name = "old"; old.password = "old"; update.accounts.add(old);
            assertSame(old, run(modern, new TransactionCallableRoomModel(), update, true, false, null));
            assertEquals("old", old.name); assertEquals("old", old.password);
        }
    }

    @Test public void stateLengthSelectsOAuthOrGraphAndAlwaysUsesLastTokenAndMaxSizeIdentity() throws Exception {
        for (boolean modern : new boolean[]{false, true}) for (int count : new int[]{1, 2, 3}) {
            State s = fresh(); s.state.clear(); for (int i = 0; i < count; i++) s.state.add("token-" + i);
            Long max = (count == 2 ? null : Long.valueOf(12345L)); s.maxSize = max;
            run(modern, new TransactionCallableRoomModel(), s, false, false, null);
            assertEquals(count == 1 ? "OAUTH" : "GRAPH", s.identity.auth); assertEquals("token-" + (count - 1), s.identity.password);
            assertSame(s.maxSizeSnapshot, s.identity.maxSize); assertSame(max, s.identity.maxSize);
        }
    }

    @Test public void sharedMailboxUsesSharedInboundButPrincipalIdentityForNewAndUpdate() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State created = fresh(); created.principal = "owner@example.com"; created.sharedname = "shared@example.com";
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertNull(run(modern, room, created, false, true, null));
            assertEquals("shared@example.com", created.created.user);
            assertEquals("owner@example.com", created.identity.user); assertEquals("token-0", created.created.token);
            assertEquals("OAUTH", created.created.auth); assertEquals("OAUTH", created.identity.auth);
            assertTrue(created.sourceFolders.get(0).specials); assertEquals(Long.valueOf(40), created.sourceFolders.get(0).account);
            assertTrue(room.trace.contains("sync-folder:Inbox")); assertFalse(room.trace.contains("sync-folder:Archive"));

            State updated = fresh(); updated.principal = "owner@example.com"; updated.sharedname = "shared@example.com";
            updated.state.add("token-1"); Account old = existing(POP, "shared@example.com"); updated.accounts.add(old);
            assertSame(old, run(modern, new TransactionCallableRoomModel(), updated, true, true, null));
            assertEquals("shared@example.com", updated.queryUser); assertEquals("token-0", updated.accountTokenArg);
            assertEquals("OAUTH", updated.accountAuthArg); assertEquals("token-1", updated.identityTokenArg);
            assertEquals("GRAPH", updated.identityAuthArg); assertEquals("owner@example.com", updated.identityUserArg);
            assertEquals(Boolean.TRUE, updated.updatedArg);
        }
    }

    @Test public void existingFolderIsInsertOnlyAndSourceMutationsAndBundleEffectsSurviveRollback() {
        for (boolean modern : new boolean[]{false, true}) {
            State s = fresh(); Folder old = new Folder("Inbox", true); old.account = 99L; s.existingFolders.add(old);
            RuntimeException failure = new IllegalStateException("body"); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertSame(failure, capture(() -> run(modern, room, s, false, false, failure)));
            assertNull(s.sourceFolders.get(0).account); assertFalse(s.sourceFolders.get(0).specials);
            assertFalse(room.trace.contains("write:insert-folder:Inbox")); assertTrue(room.trace.contains("write:insert-folder:Archive"));
            assertTrue(room.committed.isEmpty()); assertEquals(Long.valueOf(40), s.accountArg); noPostConsumers(room, s);
        }
    }

    @Test public void bodyFailuresRollbackWritesButKeepNullableReferenceAndEntityEffects() {
        for (boolean modern : new boolean[]{false, true}) for (Throwable failure : new Throwable[]{new IllegalStateException("body"), new AssertionError("body")}) {
            State s = fresh(); Account old = existing(IMAP, "user@example.com"); s.accounts.add(old); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertSame(failure, capture(() -> run(modern, room, s, true, false, failure)));
            assertTrue(room.committed.isEmpty()); assertEquals(Long.valueOf(7), s.accountArg); noPostConsumers(room, s);
        }
    }

    @Test public void policyPreflightDoesNotBeginAndBeginCommitEndFailuresHavePinnedPrecedence() {
        State denied = fresh(); denied.policyAllowed = false; TransactionCallableRoomModel preflight = new TransactionCallableRoomModel();
        assertTrue(capture(() -> run(false, preflight, denied, false, false, null)) instanceof IllegalArgumentException); assertTrue(preflight.trace.isEmpty());
        for (boolean modern : new boolean[]{false, true}) for (int stage = 0; stage < 3; stage++) {
            State s = fresh(); TransactionCallableRoomModel room = new TransactionCallableRoomModel(); RuntimeException f = new IllegalStateException("failure");
            if (stage == 0) room.beginFailure = f; if (stage == 1) room.commitFailure = f; if (stage == 2) room.endFailure = f;
            assertSame(f, capture(() -> run(modern, room, s, false, false, null))); noPostConsumers(room, s);
        }
    }

    @Test public void endFailureMasksBodyCommitAndPostEndFailuresAndNeverRunsConsumers() {
        for (boolean modern : new boolean[]{false, true}) for (String earlier : new String[]{"body", "commit", "sync-eval", "args:updated", "backup"}) {
            State s = fresh(); TransactionCallableRoomModel room = new TransactionCallableRoomModel(); AssertionError end = new AssertionError("end"); room.endFailure = end;
            Throwable bodyFailure = "body".equals(earlier) ? new IllegalStateException("body") : null;
            if ("commit".equals(earlier)) room.commitFailure = new IllegalStateException("commit");
            if (earlier.startsWith("sync") || earlier.startsWith("args") || "backup".equals(earlier)) s.postFailure = earlier;
            assertSame(end, capture(() -> run(modern, room, s, false, false, bodyFailure))); assertEquals(1, Collections.frequency(room.trace, "end"));
        }
    }

    @Test public void successfulPostEndOrderAndReturnedNullableReferenceArePinned() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State s = fresh(); Account old = existing(IMAP, "user@example.com"); s.accounts.add(old); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertSame(old, run(modern, room, s, true, false, null));
            int end = room.trace.indexOf("end"); assertEquals(Arrays.asList("end", "done", "sync-eval", "args:updated", "backup"), room.trace.subList(end, room.trace.size()));
            assertEquals(Boolean.TRUE, s.updatedArg);
        }
    }
}

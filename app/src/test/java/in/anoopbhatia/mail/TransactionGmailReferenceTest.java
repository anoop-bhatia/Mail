package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Host model of FragmentGmail's account transaction; not an Android/provider integration test. */
public class TransactionGmailReferenceTest {
    private interface Action { void run() throws Exception; }
    private static Throwable capture(Action action) {
        try { action.run(); return null; }
        catch (Throwable failure) { return failure; }
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
        String name, password, swipeLeft, swipeRight; boolean leaveOnDevice;
        Account(Long id, int protocol, String user) { this.id = id; this.protocol = protocol; this.user = user; }
    }
    private static final class Folder {
        final String name; Long account; boolean synchronizedFolder; boolean specialsSet;
        Folder(String name) { this.name = name; }
    }
    private static final class State {
        List<Account> accounts = new ArrayList<>();
        final List<Folder> sourceFolders = new ArrayList<>();
        final List<Folder> existingFolders = new ArrayList<>();
        Long accountArg; Boolean updatedArg; String queryUser; int queryProtocol;
        String accountIdentityName, accountUser = "user@example.com", identityName, identityEmail;
        boolean accountPop, identityInserted; String password = "secret", providerName = "Google";
        Account createdAccount; String postFailure;
    }
    private static final class Checked extends Exception { Checked(String message) { super(message); } }

    private static State existing(int protocol, String user) {
        State state = new State(); state.accounts.add(new Account(7L, protocol, user)); return state;
    }
    private static boolean sameFolder(Folder a, Folder b) { return a.name.equals(b.name); }

    /** Abstracts only Room writes; operation order follows FragmentGmail's callback. */
    private static Account body(TransactionCallableRoomModel room, State state, boolean update,
                                String identityArg, boolean popArg, String userArg,
                                Throwable failure) throws Exception {
        final int protocol = popArg ? POP : IMAP;
        Account matched = null;
        if (update) {
            state.queryUser = userArg; state.queryProtocol = protocol; room.trace.add("query-accounts");
            List<Account> matches = new ArrayList<>();
            if (state.accounts != null)
                for (Account candidate : state.accounts)
                    if (candidate.protocol == protocol && userArg.equals(candidate.user)) matches.add(candidate);
            if (matches.size() == 1) matched = matches.get(0);
        }
        if (matched == null) {
            Account account = new Account(40L, protocol, userArg); state.createdAccount = account;
            account.name = state.providerName + "/username"; account.password = state.password;
            account.leaveOnDevice = popArg;
            room.write("insert-account");
            state.accountArg = account.id;
            for (Folder source : state.sourceFolders) {
                Folder existing = null;
                for (Folder candidate : state.existingFolders) if (sameFolder(candidate, source)) { existing = candidate; break; }
                room.trace.add("query-folder:" + source.name);
                if (existing == null) {
                    source.account = account.id; source.specialsSet = true;
                    room.write("insert-folder:" + source.name);
                    if (source.synchronizedFolder) room.trace.add("sync-folder:" + source.name);
                }
            }
            if (popArg) { account.swipeLeft = "delete"; account.swipeRight = "seen"; }
            else { account.swipeLeft = "default-left"; account.swipeRight = "default-right"; }
            room.write("update-account");
            String resolved = identityArg;
            if (resolved == null || resolved.isEmpty()) resolved = userArg.split("@")[0];
            state.identityName = resolved; state.identityEmail = userArg; state.identityInserted = true;
            room.write("insert-identity");
        } else {
            state.accountArg = matched.id; room.write("set-account-synchronize");
            room.write("set-account-password-provider"); room.write("set-identity-password-provider");
        }
        raise(failure); return matched;
    }

    private static Account run(boolean modern, TransactionCallableRoomModel room, State state,
                               boolean update, Throwable failure) throws Exception {
        final String identity = state.accountIdentityName; final boolean pop = state.accountPop;
        final String user = state.accountUser; Account result;
        if (modern) {
            result = TransactionScope.call(room::run, room::end,
                    () -> body(room, state, update, identity, pop, user, failure));
        } else {
            try { room.begin(); result = body(room, state, update, identity, pop, user, failure); room.commit(); }
            finally { room.end(); }
        }
        room.trace.add("sync-eval");
        if ("sync-eval".equals(state.postFailure)) throw new IllegalStateException("sync-eval");
        state.updatedArg = result != null; room.trace.add("args:updated");
        if ("args:updated".equals(state.postFailure)) throw new IllegalStateException("args:updated");
        room.trace.add("backup");
        if ("backup".equals(state.postFailure)) throw new IllegalStateException("backup");
        return result;
    }
    private static void assertNoPostConsumers(TransactionCallableRoomModel room, State state) {
        assertNull(state.updatedArg); assertFalse(room.trace.contains("sync-eval"));
        assertFalse(room.trace.contains("args:updated")); assertFalse(room.trace.contains("backup"));
    }

    @Test public void updateFlagControlsQueryAndNullEmptyMultipleOrExactAccountMatching() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State disabled = existing(IMAP, "user@example.com");
            TransactionCallableRoomModel disabledRoom = new TransactionCallableRoomModel();
            assertNull(run(modern, disabledRoom, disabled, false, null));
            assertNull(disabled.queryUser); assertEquals(Boolean.FALSE, disabled.updatedArg); assertFalse(disabledRoom.trace.contains("query-accounts"));
            State nil = existing(IMAP, "other@example.com"); nil.accounts = null;
            TransactionCallableRoomModel nilRoom = new TransactionCallableRoomModel();
            assertNull(run(modern, nilRoom, nil, true, null)); assertTrue(nilRoom.trace.contains("query-accounts"));
            State empty = new State();
            assertNull(run(modern, new TransactionCallableRoomModel(), empty, true, null));
            State wrongProtocol = existing(POP, "user@example.com");
            assertNull(run(modern, new TransactionCallableRoomModel(), wrongProtocol, true, null));
            State multiple = existing(IMAP, "user@example.com"); multiple.accounts.add(new Account(8L, IMAP, "user@example.com"));
            assertNull(run(modern, new TransactionCallableRoomModel(), multiple, true, null));
            State exact = existing(POP, "recent:user@example.com"); exact.accountPop = true; exact.accountUser = "recent:user@example.com";
            assertSame(exact.accounts.get(0), run(modern, new TransactionCallableRoomModel(), exact, true, null));
            assertEquals("recent:user@example.com", exact.queryUser); assertEquals(POP, exact.queryProtocol);
        }
    }

    @Test public void newLegacyAndModernPopImapUseDefaultsAndExactTraceOrder() throws Exception {
        for (boolean modern : new boolean[]{false, true}) for (boolean pop : new boolean[]{false, true}) {
            State state = new State(); state.accountPop = pop; state.accountIdentityName = "";
            Folder inbox = new Folder("Inbox"); inbox.synchronizedFolder = true; Folder archive = new Folder("Archive");
            state.sourceFolders.add(inbox); state.sourceFolders.add(archive); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertNull(run(modern, room, state, false, null));
            assertEquals(Arrays.asList("begin", "write:insert-account", "query-folder:Inbox", "write:insert-folder:Inbox",
                    "sync-folder:Inbox", "query-folder:Archive", "write:insert-folder:Archive", "write:update-account",
                    "write:insert-identity", "commit", "end", "sync-eval", "args:updated", "backup"), room.trace);
            assertEquals(Long.valueOf(40), inbox.account); assertSame(inbox, state.sourceFolders.get(0)); assertTrue(inbox.specialsSet);
            assertEquals(pop ? "delete" : "default-left", state.createdAccount.swipeLeft);
            assertEquals(pop, state.createdAccount.leaveOnDevice); assertEquals("user", state.identityName);
        }
    }

    @Test public void existingFolderIsNotMutatedQueuedOrInsertedAndMissingSourceMutationSurvivesRollback() {
        for (boolean modern : new boolean[]{false, true}) {
            State state = new State(); Folder old = new Folder("Inbox"); old.account = 99L; state.existingFolders.add(old);
            Folder existingSource = new Folder("Inbox"); existingSource.synchronizedFolder = true;
            Folder missing = new Folder("Sent"); missing.synchronizedFolder = true; state.sourceFolders.add(existingSource); state.sourceFolders.add(missing);
            TransactionCallableRoomModel room = new TransactionCallableRoomModel(); RuntimeException failure = new RuntimeException("body");
            assertSame(failure, capture(() -> run(modern, room, state, false, failure)));
            assertSame(existingSource, state.sourceFolders.get(0)); assertNull(existingSource.account); assertFalse(existingSource.specialsSet);
            assertSame(missing, state.sourceFolders.get(1)); assertEquals(Long.valueOf(40), missing.account); assertTrue(missing.specialsSet);
            assertFalse(room.trace.contains("write:insert-folder:Inbox")); assertFalse(room.trace.contains("sync-folder:Inbox"));
            assertTrue(room.trace.contains("write:insert-folder:Sent")); assertTrue(room.trace.contains("sync-folder:Sent")); assertTrue(room.committed.isEmpty());
        }
    }

    @Test public void emptyNameFallbackPreservesRecentPrefixAndCapturedArguments() throws Exception {
        for (boolean modern : new boolean[]{false, true}) for (String name : new String[]{null, "", "Display"}) {
            State state = new State(); state.accountIdentityName = name; state.accountUser = "recent:user@example.com";
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertNull(run(modern, room, state, false, null));
            assertEquals(name == null || name.isEmpty() ? "recent:user" : name, state.identityName);
            assertEquals(state.accountUser, state.identityEmail);
        }
    }

    @Test public void existingUpdateUsesDbSettersAndReturnedEntityIsNotLocallyChangedForBothPaths() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State state = existing(IMAP, "user@example.com"); Account original = state.accounts.get(0); original.name = "old"; original.password = "old";
            TransactionCallableRoomModel room = new TransactionCallableRoomModel(); assertSame(original, run(modern, room, state, true, null));
            assertEquals("old", original.name); assertEquals("old", original.password);
            assertEquals(Arrays.asList("begin", "query-accounts", "write:set-account-synchronize", "write:set-account-password-provider",
                    "write:set-identity-password-provider", "commit", "end", "sync-eval", "args:updated", "backup"), room.trace);
        }
    }

    @Test public void checkedRuntimeAndErrorRollbackWritesButKeepNontransactionalSourceMutations() {
        for (boolean modern : new boolean[]{false, true}) for (Throwable failure : new Throwable[]{new Checked("checked"), new IllegalStateException("runtime"), new AssertionError("error")}) {
            State state = new State(); Folder source = new Folder("Inbox"); state.sourceFolders.add(source); TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertSame(failure, capture(() -> run(modern, room, state, false, failure))); assertTrue(room.committed.isEmpty());
            assertEquals(Long.valueOf(40), state.accountArg); assertEquals(Long.valueOf(40), source.account); assertNoPostConsumers(room, state);
        }
    }

    @Test public void beginCommitAndEndFailuresHaveParityAndEndMasksEarlierFailure() {
        for (boolean modern : new boolean[]{false, true}) for (int stage = 0; stage < 3; stage++) {
            State state = new State(); TransactionCallableRoomModel room = new TransactionCallableRoomModel(); RuntimeException f = new IllegalStateException("failure");
            if (stage == 0) room.beginFailure = f; if (stage == 1) room.commitFailure = f; if (stage == 2) room.endFailure = f;
            assertSame(f, capture(() -> run(modern, room, state, false, null))); assertEquals(1, Collections.frequency(room.trace, "end")); assertNoPostConsumers(room, state);
        }
        for (boolean modern : new boolean[]{false, true}) for (int stage = 0; stage < 3; stage++) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel(); RuntimeException body = new IllegalStateException("body"), end = new IllegalStateException("end"); room.endFailure = end;
            if (stage == 0) room.beginFailure = body;
            if (stage == 1) room.commitFailure = body;
            final Throwable callbackFailure = stage == 2 ? body : null;
            assertSame(end, capture(() -> run(modern, room, new State(), false, callbackFailure))); assertEquals(0, end.getSuppressed().length);
            assertEquals(1, Collections.frequency(room.trace, "end"));
        }
    }

    @Test public void nestedRollbackPoisonsOuterAndSuccessfulPostEndOrderIsExact() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            State state = existing(IMAP, "user@example.com"); TransactionCallableRoomModel room = new TransactionCallableRoomModel(); room.begin();
            try { assertTrue(capture(() -> run(modern, room, state, true, new IllegalStateException("inner"))) instanceof IllegalStateException); assertNull(state.updatedArg); assertTrue(room.committed.isEmpty()); }
            finally { room.end(); }
            assertTrue(room.committed.isEmpty()); State success = existing(IMAP, "user@example.com"); TransactionCallableRoomModel successRoom = new TransactionCallableRoomModel(); run(modern, successRoom, success, true, null);
            int end = successRoom.trace.indexOf("end"); assertEquals(Arrays.asList("end", "sync-eval", "args:updated", "backup"), successRoom.trace.subList(end, successRoom.trace.size()));
        }
    }

    @Test public void failingPostEndConsumerDoesNotReorderOrRunLaterConsumers() {
        for (String failing : new String[]{"sync-eval", "args:updated", "backup"}) {
            State state = existing(IMAP, "user@example.com"); state.postFailure = failing; TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            Throwable actual = capture(() -> run(true, room, state, true, null)); assertEquals(failing, actual.getMessage()); int end = room.trace.indexOf("end"); assertEquals("sync-eval", room.trace.get(end + 1));
            if ("sync-eval".equals(failing)) assertNull(state.updatedArg); else assertEquals(Boolean.TRUE, state.updatedArg);
            assertFalse("sync-eval".equals(failing) && room.trace.contains("args:updated")); assertFalse("args:updated".equals(failing) && room.trace.contains("backup"));
        }
    }
}

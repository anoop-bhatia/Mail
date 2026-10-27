package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Host-only parity model for FragmentDialogFoldersApply.  These tests deliberately do not
 * claim to exercise Android, Room, or ServiceSynchronize; the small Room model records the
 * contract which the fragment's transaction callback must preserve.
 */
public class TransactionCompletionFoldersApplyTest {
    private static final class Folder {
        final String id;
        final String type;
        boolean synchronize;
        boolean poll;
        Folder(String id, String type, boolean synchronize, boolean poll) {
            this.id = id; this.type = type; this.synchronize = synchronize; this.poll = poll;
        }
    }

    private static final class Account {
        int interval;
        Account(int interval) { this.interval = interval; }
    }

    private static final class Input {
        final Boolean enable;
        final String sync;
        final String keep;
        final boolean system;
        final boolean user;
        Input(Boolean enable, String sync, String keep, boolean system, boolean user) {
            this.enable = enable; this.sync = sync; this.keep = keep;
            this.system = system; this.user = user;
        }
    }

    private static final class State {
        final TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        final Account account;
        final List<Folder> folders;
        final boolean foldersMissing;
        final List<String> reloads = new ArrayList<>();
        State(Account account, List<Folder> folders) {
            this.account = account; this.folders = folders; this.foldersMissing = folders == null;
        }
        State(Account account, List<Folder> folders, boolean missing) {
            this.account = account; this.folders = folders; this.foldersMissing = missing;
        }
    }

    private static Folder f(String id, String type, boolean sync, boolean poll) {
        return new Folder(id, type, sync, poll);
    }

    private interface ThrowingCall { void run() throws Exception; }

    private static Throwable capture(ThrowingCall call) {
        try { call.run(); return null; }
        catch (Throwable failure) { return failure; }
    }

    /** The pre-refactor try/finally shape, kept as an executable comparison oracle. */
    private static void oldApply(State s, Input in) throws Exception {
        String sync = in.sync == null || in.sync.isEmpty() ? "7" : in.sync;
        String keep = in.keep == null || in.keep.isEmpty() ? "30" : in.keep;
        try {
            s.room.begin();
            s.room.trace.add("read:account");
            if (s.account == null) return;
            if (in.system && s.account.interval > 15)
                s.room.write("keepalive:15");
            s.room.trace.add("read:folders");
            if (s.foldersMissing) return;
            for (Folder folder : s.folders) oldFolder(s, folder, in, sync, keep);
            s.room.commit();
        } finally { s.room.end(); }
        s.reloads.add("Apply");
    }

    private static void oldFolder(State s, Folder folder, Input in, String sync, String keep) {
        if ("USER".equals(folder.type)) {
            if (in.enable != null) {
                folder.synchronize = in.enable;
                s.room.write("sync:" + folder.id + ":" + folder.synchronize);
            }
            s.room.write("properties:" + folder.id + ":" + Integer.parseInt(sync) + ":" + Integer.parseInt(keep));
        }
        if (folder.synchronize && !folder.poll &&
                ("USER".equals(folder.type) ? in.user : in.system && !"INBOX".equals(folder.type)))
            s.room.write("poll:" + folder.id);
    }

    /** Candidate shape: immutable inputs, explicit rollback result, and success-only reload. */
    private static void newApply(State s, Input in) throws Exception {
        final Boolean enable = in.enable;
        final String sync = in.sync == null || in.sync.isEmpty() ? "7" : in.sync;
        final String keep = in.keep == null || in.keep.isEmpty() ? "30" : in.keep;
        Boolean ok = TransactionCompletion.call(s.room::run, s.room::end, () -> {
            s.room.trace.add("read:account");
            if (s.account == null) return TransactionResult.rollback(false);
            if (in.system && s.account.interval > 15)
                s.room.write("keepalive:15");
            s.room.trace.add("read:folders");
            if (s.foldersMissing) return TransactionResult.rollback(false);
            for (Folder folder : s.folders) newFolder(s, folder, in, enable, sync, keep);
            return TransactionResult.commit(true);
        });
        if (Boolean.TRUE.equals(ok)) s.reloads.add("Apply");
    }

    private static void newFolder(State s, Folder folder, Input in, Boolean enable,
                                  String sync, String keep) {
        if ("USER".equals(folder.type)) {
            if (enable != null) {
                folder.synchronize = enable;
                s.room.write("sync:" + folder.id + ":" + folder.synchronize);
            }
            // Parsing remains at the original USER-folder point, not before the loop.
            s.room.write("properties:" + folder.id + ":" + Integer.parseInt(sync) + ":" + Integer.parseInt(keep));
        }
        if (folder.synchronize && !folder.poll &&
                ("USER".equals(folder.type) ? in.user : in.system && !"INBOX".equals(folder.type)))
            s.room.write("poll:" + folder.id);
    }

    @Test
    public void enableNullFalseTruePreservesUserMutationAndPollDecision() throws Exception {
        for (Boolean enable : new Boolean[]{null, Boolean.FALSE, Boolean.TRUE}) {
            State oldState = new State(new Account(10), Arrays.asList(f("u", "USER", true, false)));
            State newState = new State(new Account(10), Arrays.asList(f("u", "USER", true, false)));
            Input input = new Input(enable, "2", "4", false, true);
            oldApply(oldState, input); newApply(newState, input);
            assertEquals(oldState.room.trace, newState.room.trace);
            assertEquals(oldState.room.committed, newState.room.committed);
            assertEquals(oldState.folders.get(0).synchronize, newState.folders.get(0).synchronize);
            assertEquals(Arrays.asList("Apply"), newState.reloads);
            List<String> expected = new ArrayList<>();
            if (enable != null) expected.add("sync:u:" + enable);
            expected.add("properties:u:2:4");
            if (enable != Boolean.FALSE) expected.add("poll:u");
            assertEquals(expected, newState.room.committed);
            assertFalse(newState.folders.get(0).poll); // DAO write does not mutate the entity
        }
    }

    @Test
    public void systemAndUserFlagsRespectUserInboxAndOtherSystemCombinations() throws Exception {
        for (boolean system : new boolean[]{false, true}) for (boolean user : new boolean[]{false, true}) {
            List<Folder> folders = Arrays.asList(f("u", "USER", true, false),
                    f("i", "INBOX", true, false), f("s", "ARCHIVE", true, false));
            State state = new State(new Account(10), folders);
            newApply(state, new Input(null, "1", "1", system, user));
            List<String> expected = new ArrayList<>();
            expected.add("properties:u:1:1");
            if (user) expected.add("poll:u");
            if (system) expected.add("poll:s");
            assertEquals(expected, state.room.committed);
            assertFalse(folders.get(0).poll);
            assertFalse(folders.get(1).poll);
            assertFalse(folders.get(2).poll);
            assertEquals(Arrays.asList("Apply"), state.reloads);
        }
    }

    @Test
    public void intervalGuardPrecedesFoldersAndMissingFoldersRollBackKeepalive() throws Exception {
        State success = new State(new Account(30), Arrays.asList(f("u", "USER", true, false)));
        newApply(success, new Input(null, "2", "3", true, false));
        assertEquals(30, success.account.interval); // DAO write does not mutate the entity
        assertEquals(Arrays.asList("keepalive:15", "properties:u:2:3"), success.room.committed);
        assertEquals(Arrays.asList("Apply"), success.reloads);

        State missing = new State(new Account(30), null, true);
        newApply(missing, new Input(null, "2", "3", true, false));
        assertEquals(30, missing.account.interval); // rolled-back DAO write never changes the entity
        assertTrue(missing.room.committed.isEmpty());
        assertTrue(missing.reloads.isEmpty());
        assertEquals(Arrays.asList("begin", "read:account", "write:keepalive:15", "read:folders", "end"),
                missing.room.trace);
    }

    @Test
    public void defaultsParsePerUserAndEmptyOrNoUserListsAvoidMalformedInput() throws Exception {
        State noUser = new State(new Account(10), Arrays.asList(f("i", "INBOX", true, false)));
        newApply(noUser, new Input(null, "bad-sync", "bad-keep", false, false));
        assertTrue(noUser.room.committed.isEmpty());
        assertEquals(Arrays.asList("Apply"), noUser.reloads);

        State empty = new State(new Account(10), new ArrayList<Folder>());
        newApply(empty, new Input(null, "bad-sync", "bad-keep", false, false));
        assertTrue(empty.room.committed.isEmpty());
        assertEquals(Arrays.asList("begin", "read:account", "read:folders", "commit", "end"), empty.room.trace);

        State defaults = new State(new Account(10), Arrays.asList(f("u", "USER", true, false)));
        newApply(defaults, new Input(null, "", "", false, false));
        assertEquals(Arrays.asList("properties:u:7:30"), defaults.room.committed);
    }

    @Test
    public void accountMissingRollsBackAndSkipsReloadInBothImplementations() {
        Input input = new Input(null, "2", "3", true, true);
        State oldState = new State(null, null, true);
        State newState = new State(null, null, true);
        Throwable oldFailure = capture(() -> oldApply(oldState, input));
        Throwable newFailure = capture(() -> newApply(newState, input));
        assertEquals(oldFailure, newFailure);
        assertEquals(oldState.room.trace, newState.room.trace);
        assertEquals(oldState.room.committed, newState.room.committed);
        assertTrue(newState.room.committed.isEmpty());
        assertTrue(newState.reloads.isEmpty());
    }

    @Test
    public void intervalGuardOnlyWritesForSystemAccountsAboveFifteen() throws Exception {
        for (boolean system : new boolean[]{false, true})
            for (int interval : new int[]{15, 16}) {
                State state = new State(new Account(interval), new ArrayList<Folder>());
                newApply(state, new Input(null, "1", "1", system, false));
                boolean guarded = system && interval > 15;
                assertEquals(guarded ? Arrays.asList("keepalive:15") : new ArrayList<String>(),
                        state.room.committed);
                assertEquals(interval, state.account.interval);
            }
    }

    @Test
    public void malformedSyncAndKeepRollBackWritesButRetainInMemoryMutation() {
        for (boolean badSync : new boolean[]{true, false}) {
            State oldState = new State(new Account(10), Arrays.asList(f("u", "USER", true, false)));
            State newState = new State(new Account(10), Arrays.asList(f("u", "USER", true, false)));
            Input input = new Input(Boolean.FALSE, badSync ? "bad" : "2", badSync ? "4" : "bad", false, true);
            Throwable oldFailure = capture(() -> oldApply(oldState, input));
            Throwable newFailure = capture(() -> newApply(newState, input));
            assertTrue(oldFailure instanceof NumberFormatException);
            assertTrue(newFailure instanceof NumberFormatException);
            assertEquals(oldState.room.trace, newState.room.trace);
            assertEquals(oldState.room.committed, newState.room.committed);
            assertEquals(oldState.folders.get(0).synchronize, newState.folders.get(0).synchronize);
            assertFalse(newState.folders.get(0).synchronize);
            assertTrue(newState.room.committed.isEmpty());
            assertTrue(newState.reloads.isEmpty());
        }
    }

    @Test
    public void beginBodyMarkerAndEndFailureIdentityAndMaskingMatchRunnerContract() {
        RuntimeException begin = new IllegalStateException("begin");
        State beginState = new State(new Account(1), new ArrayList<Folder>());
        beginState.room.beginFailure = begin;
        assertSame(begin, capture(() -> newApply(beginState, new Input(null, "1", "1", false, false))));

        IOException body = new IOException("body");
        State cleanBody = new State(new Account(1), new ArrayList<Folder>());
        assertSame(body, capture(() -> TransactionCompletion.call(cleanBody.room::run,
                cleanBody.room::end, () -> { throw body; })));
        State bodyState = new State(new Account(1), Arrays.asList(f("u", "USER", true, false)));
        Error bodyEnd = new AssertionError("end");
        bodyState.room.endFailure = bodyEnd;
        assertSame(bodyEnd, capture(() -> TransactionCompletion.call(bodyState.room::run,
                bodyState.room::end, () -> { throw body; })));
        assertEquals(0, bodyEnd.getSuppressed().length);
        State markerState = new State(new Account(1), new ArrayList<Folder>());
        RuntimeException marker = new IllegalStateException("marker");
        markerState.room.commitFailure = marker;
        assertSame(marker, capture(() -> newApply(markerState, new Input(null, "1", "1", false, false))));

        State endState = new State(new Account(1), new ArrayList<Folder>());
        Error end = new AssertionError("end"); endState.room.endFailure = end;
        assertSame(end, capture(() -> newApply(endState, new Input(null, "1", "1", false, false))));
        assertTrue(endState.reloads.isEmpty());
    }

    @Test
    public void knownEndFailureIsLoggedAndReloadRemainsAfterEnd() throws Exception {
        State state = new State(new Account(1), new ArrayList<Folder>());
        state.room.swallowKnownEndFailure = true;
        state.room.endFailure = new IllegalStateException("no current transaction");
        newApply(state, new Input(null, "1", "1", false, false));
        assertEquals(Arrays.asList("begin", "read:account", "read:folders", "commit", "end", "logged-end"),
                state.room.trace);
        assertEquals(Arrays.asList("Apply"), state.reloads);
    }

    @Test
    public void nestedRollbackPoisonsOuterAndDoesNotPublishInnerWrites() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        String result = TransactionCompletion.call(room::run, room::end, () -> {
            room.write("outer-before");
            assertEquals("missing", TransactionCompletion.call(room::run, room::end, () -> {
                room.write("inner-keepalive");
                return TransactionResult.rollback("missing");
            }));
            room.write("outer-after");
            return TransactionResult.commit("outer");
        });
        assertEquals("outer", result);
        assertTrue(room.committed.isEmpty());
        assertEquals(Arrays.asList("begin", "write:outer-before", "begin", "write:inner-keepalive",
                "end", "write:outer-after", "commit", "end"), room.trace);
    }

    @Test
    public void reloadIsOnlyAfterSuccessfulEndAndUsesStableInputSnapshot() throws Exception {
        State failed = new State(new Account(1), Arrays.asList(f("u", "USER", true, false)));
        failed.room.endFailure = new AssertionError("end");
        Throwable failure = capture(() -> newApply(failed, new Input(Boolean.TRUE, "2", "3", false, true)));
        assertSame(failed.room.endFailure, failure);
        assertTrue(failed.reloads.isEmpty());

        State success = new State(new Account(1), Arrays.asList(f("u", "USER", true, false)));
        Input snapshot = new Input(Boolean.TRUE, "2", "3", false, true);
        newApply(success, snapshot);
        assertTrue(snapshot.enable); // captured input remains the value used by the loop
        assertEquals(Arrays.asList("sync:u:true", "properties:u:2:3", "poll:u"), success.room.committed);
        assertFalse(success.folders.get(0).poll); // DAO write does not mutate the entity
        assertEquals(Arrays.asList("Apply"), success.reloads);
    }

    @Test
    public void oldAndCandidateAgreeOnSuccessfulFolderTraceAndState() throws Exception {
        Input input = new Input(Boolean.TRUE, "5", "9", true, true);
        State oldState = new State(new Account(20), Arrays.asList(f("u", "USER", true, false), f("i", "INBOX", true, false), f("s", "OTHER", true, false)));
        State newState = new State(new Account(20), Arrays.asList(f("u", "USER", true, false), f("i", "INBOX", true, false), f("s", "OTHER", true, false)));
        oldApply(oldState, input); newApply(newState, input);
        assertEquals(oldState.room.trace, newState.room.trace);
        assertEquals(oldState.room.committed, newState.room.committed);
        assertEquals(oldState.account.interval, newState.account.interval);
        for (int i = 0; i < 3; i++) {
            assertEquals(oldState.folders.get(i).synchronize, newState.folders.get(i).synchronize);
            assertEquals(oldState.folders.get(i).poll, newState.folders.get(i).poll);
        }
        assertEquals(oldState.reloads, newState.reloads);
    }
}

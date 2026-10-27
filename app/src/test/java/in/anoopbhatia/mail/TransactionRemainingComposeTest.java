package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * Host-model parity for the two Compose callbacks.  The old side is the
 * original begin/marker/end shape; the new side uses the real synchronous Room
 * callable and keeps cleanup for the begin-before-callback window.
 */
public class TransactionRemainingComposeTest {
    private static final class DraftState {
        long aid;
        long iid;
        String extra;
        String body;
        boolean dirty;
        Object draft;
        final List<String> followups = new ArrayList<>();
    }

    private static final class ActionState {
        String extra;
        String body;
        boolean dirty;
        Object draft;
        Object mutated;
        final List<String> followups = new ArrayList<>();
    }

    private static final Object DRAFT = new Object();

    private static void marker(TransactionCallableRoomModel room) {
        room.commit();
    }

    private static void legacyDraft(TransactionCallableRoomModel room,
                                     DraftState state, boolean returnNull)
            throws IOException {
        final long initialAid = state.aid;
        final long initialIid = state.iid;
        try {
            room.begin();
            long transactionAid = initialAid;
            long transactionIid = initialIid;
            transactionAid++;
            transactionIid++;
            room.write("draft:" + initialAid + ":" + initialIid);
            if (!returnNull)
                state.draft = DRAFT;
            marker(room);
        } finally {
            room.end();
        }
    }

    private static void modernDraft(TransactionCallableRoomModel room,
                                    DraftState state, boolean returnNull)
            throws IOException {
        final long initialAid = state.aid;
        final long initialIid = state.iid;
        TransactionScope.call(room::run, room::end, () -> {
            long transactionAid = initialAid;
            long transactionIid = initialIid;
            transactionAid++;
            transactionIid++;
            room.write("draft:" + initialAid + ":" + initialIid);
            if (!returnNull)
                state.draft = DRAFT;
            return null;
        });
    }

    private static Object legacyAction(TransactionCallableRoomModel room,
                                       ActionState state, int branch,
                                       IOException bodyFailure)
            throws IOException {
        Object draft = state.draft;
        try {
            room.begin();
            String transactionExtra = state.extra;
            String transactionBody = state.body;
            boolean transactionDirty = state.dirty;
            transactionExtra = transactionExtra + "-local";
            transactionBody = transactionBody + "-local";
            transactionDirty = !transactionDirty;
            state.mutated = DRAFT;
            if (bodyFailure != null)
                throw bodyFailure;
            if (branch == 1 || branch == 2) {
                draft = DRAFT;
                marker(room);
                return draft;
            }
            draft = DRAFT;
            room.write("action:" + state.extra + ":" + state.body);
            marker(room);
        } finally {
            room.end();
        }
        state.dirty = !state.dirty;
        state.draft = draft;
        state.followups.add("refresh");
        return draft;
    }

    private static Object modernAction(TransactionCallableRoomModel room,
                                       ActionState state, int branch,
                                       IOException bodyFailure)
            throws IOException {
        final String initialExtra = state.extra;
        final String initialBody = state.body;
        final boolean[] flags = {state.dirty, false};
        Object draft = TransactionScope.call(room::run, room::end, () -> {
            String transactionExtra = initialExtra;
            String transactionBody = initialBody;
            transactionExtra = transactionExtra + "-local";
            transactionBody = transactionBody + "-local";
            flags[0] = !flags[0];
            state.mutated = DRAFT;
            if (bodyFailure != null)
                throw bodyFailure;
            if (branch == 1 || branch == 2) {
                flags[1] = true;
                return DRAFT;
            }
            room.write("action:" + initialExtra + ":" + initialBody);
            return DRAFT;
        });
        if (flags[1])
            return draft;
        state.dirty = flags[0];
        state.draft = draft;
        state.followups.add("refresh");
        return draft;
    }

    private interface Call { void run() throws Exception; }

    private static Throwable capture(Call call) {
        try {
            call.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @Test
    public void draftNullReturnAutoCommitsAndSnapshotsAidIid() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        DraftState oldState = new DraftState();
        DraftState newState = new DraftState();
        oldState.aid = newState.aid = 17L;
        oldState.iid = newState.iid = 29L;
        legacyDraft(oldRoom, oldState, true);
        modernDraft(newRoom, newState, true);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(Arrays.asList("begin", "write:draft:17:29", "commit", "end"),
                newRoom.trace);
        assertEquals(oldRoom.committed, newRoom.committed);
        assertEquals(Arrays.asList("draft:17:29"), newRoom.committed);
    }

    @Test
    public void actionNormalCopiesDirtyAndRunsFollowupAfterEnd() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        ActionState oldState = new ActionState();
        ActionState newState = new ActionState();
        oldState.extra = newState.extra = "extra";
        oldState.body = newState.body = "body";
        oldState.dirty = newState.dirty = false;
        assertSame(legacyAction(oldRoom, oldState, 0, null),
                modernAction(newRoom, newState, 0, null));
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(oldRoom.committed, newRoom.committed);
        assertTrue(newState.dirty);
        assertEquals(Arrays.asList("refresh"), newState.followups);
    }

    @Test
    public void bothEarlyCommitBranchesReturnWithoutPostEndFollowup() throws Exception {
        for (int branch : new int[] {1, 2}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            ActionState oldState = new ActionState();
            ActionState newState = new ActionState();
            oldState.extra = newState.extra = "x";
            oldState.body = newState.body = "y";
            oldState.dirty = newState.dirty = true;
            assertSame(legacyAction(oldRoom, oldState, branch, null),
                    modernAction(newRoom, newState, branch, null));
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(Arrays.asList("begin", "commit", "end"), newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
            assertTrue(newState.followups.isEmpty());
            assertTrue(newState.dirty);
        }
    }

    @Test
    public void checkedIOExceptionKeepsIdentityAndJavaMutationsDespiteRollback()
            throws Exception {
        IOException failure = new IOException("compose-body");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        ActionState oldState = new ActionState();
        ActionState newState = new ActionState();
        oldState.extra = newState.extra = "e";
        oldState.body = newState.body = "b";
        Throwable oldFailure = capture(() -> legacyAction(oldRoom, oldState, 0, failure));
        Throwable newFailure = capture(() -> modernAction(newRoom, newState, 0, failure));
        assertSame(failure, oldFailure);
        assertSame(failure, newFailure);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertTrue(newRoom.committed.isEmpty());
        assertSame(DRAFT, newState.mutated);
        assertTrue(newState.followups.isEmpty());
    }

    @Test
    public void beginFailureUsesCleanupAndSharedEndFailureTakesPrecedence() {
        RuntimeException begin = new IllegalStateException("begin");
        RuntimeException end = new IllegalStateException("end");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.beginFailure = begin;
        oldRoom.endFailure = end;
        newRoom.beginFailure = begin;
        newRoom.endFailure = end;
        Throwable oldFailure = capture(() -> legacyDraft(oldRoom, new DraftState(), true));
        Throwable newFailure = capture(() -> modernDraft(newRoom, new DraftState(), true));
        assertSame(end, oldFailure);
        assertSame(end, newFailure);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(Arrays.asList("begin", "end"), newRoom.trace);
    }

    @Test
    public void markerFailureSkipsFollowupAndPreservesIdentity() {
        RuntimeException marker = new IllegalStateException("marker");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.commitFailure = marker;
        newRoom.commitFailure = marker;
        ActionState oldState = new ActionState();
        ActionState newState = new ActionState();
        Throwable oldFailure = capture(() -> legacyAction(oldRoom, oldState, 0, null));
        Throwable newFailure = capture(() -> modernAction(newRoom, newState, 0, null));
        assertSame(marker, oldFailure);
        assertSame(marker, newFailure);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertTrue(newState.followups.isEmpty());
        assertTrue(newRoom.committed.isEmpty());
    }

    @Test
    public void finalPrimitiveAndReferenceSnapshotsIgnoreCallbackLocalMutation() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        DraftState draft = new DraftState();
        draft.aid = 41L;
        draft.iid = 43L;
        modernDraft(room, draft, false);
        ActionState action = new ActionState();
        action.extra = "initial-extra";
        action.body = "initial-body";
        action.dirty = false;
        Object result = modernAction(new TransactionCallableRoomModel(), action, 0, null);
        assertEquals(Arrays.asList("draft:41:43"), room.committed);
        assertSame(DRAFT, draft.draft);
        assertEquals("initial-extra", action.extra);
        assertEquals("initial-body", action.body);
        assertTrue(action.dirty);
        assertSame(DRAFT, result);
        assertFalse(action.followups.isEmpty());
    }
}

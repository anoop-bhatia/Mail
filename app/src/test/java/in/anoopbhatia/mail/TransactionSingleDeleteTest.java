package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

/** Host recipes for the single-delete migration; these are not real provider calls. */
public class TransactionSingleDeleteTest {
    private interface Action { void run(); }

    private static Throwable capture(Action action) {
        try { action.run(); return null; }
        catch (Throwable failure) { return failure; }
    }

    private static void raise(Throwable failure) {
        if (failure instanceof RuntimeException) throw (RuntimeException) failure;
        if (failure instanceof Error) throw (Error) failure;
        if (failure != null) throw new AssertionError(failure);
    }

    private enum Branch {
        MISSING, STALE, GMAIL_ACCEPTED, GMAIL_REJECTED, GRAPH_PERMANENT,
        ZOHO_ACCEPTED, ZOHO_REJECTED, LOCAL_DRAFT, LEGACY
    }

    private static final class State {
        final List<String> result = new ArrayList<>();
        final List<String> wakeups = new ArrayList<>();
        final List<String> reload = new ArrayList<>();
        String token;
        int generated;
        boolean legacyWake;
    }

    private static final class Recipe {
        final boolean modern;
        final TransactionCallableRoomModel room;
        final State state;
        final String suppliedToken;
        final boolean gmailHint;
        final boolean executingSend;
        final Branch branch;
        final Throwable bodyFailure;

        Recipe(boolean modern, TransactionCallableRoomModel room, State state,
               String suppliedToken, boolean gmailHint, boolean executingSend,
               Branch branch, Throwable bodyFailure) {
            this.modern = modern;
            this.room = room;
            this.state = state;
            this.suppliedToken = suppliedToken;
            this.gmailHint = gmailHint;
            this.executingSend = executingSend;
            this.branch = branch;
            this.bodyFailure = bodyFailure;
        }

        private String newToken() {
            state.generated++;
            return "generated-" + state.generated;
        }

        private void preflight() {
            // Host recipe for the unchanged pre-begin argument normalization.
            String attemptToken = gmailHint ? suppliedToken : null;
            if (gmailHint && attemptToken == null)
                attemptToken = newToken();
            state.token = attemptToken;
            room.trace.add("preflight:" + attemptToken);
            room.trace.add("send-operation:SEND");
            if (executingSend) {
                state.result.add(gmailHint ? "gmail-source-missing" : "legacy-removed");
                if (gmailHint) state.reload.add("stale");
                room.trace.add("return-preflight");
            }
        }

        private Boolean body(String initialAttemptToken) {
            // This is the migrated callback-local snapshot and carrier-free Boolean result.
            final String initial = initialAttemptToken;
            String deleteAttemptToken = initial;
            boolean legacyWake = false;
            room.trace.add("lookup:" + branch);
            switch (branch) {
                case MISSING:
                    state.result.add(gmailHint ? "gmail-missing" : "legacy-removed");
                    break;
                case STALE:
                    if (deleteAttemptToken == null) deleteAttemptToken = newToken();
                    state.token = deleteAttemptToken;
                    state.result.add("gmail-stale");
                    state.reload.add("stale");
                    break;
                case GMAIL_ACCEPTED:
                    if (deleteAttemptToken == null) deleteAttemptToken = newToken();
                    state.token = deleteAttemptToken;
                    room.write("gmail-queue:" + deleteAttemptToken);
                    state.wakeups.add("gmail:" + deleteAttemptToken);
                    state.result.add("gmail-queued");
                    break;
                case GMAIL_REJECTED:
                    if (deleteAttemptToken == null) deleteAttemptToken = newToken();
                    state.token = deleteAttemptToken;
                    state.result.add("gmail-rejected");
                    break;
                case GRAPH_PERMANENT:
                    room.write("queue:graph-permanent");
                    state.result.add("graph");
                    legacyWake = true;
                    break;
                case ZOHO_ACCEPTED:
                    room.write("queue:zoho");
                    state.result.add("zoho");
                    legacyWake = true;
                    break;
                case ZOHO_REJECTED:
                    state.result.add("zoho-skipped");
                    break;
                case LOCAL_DRAFT:
                    room.write("delete:local-draft");
                    state.result.add("legacy-removed");
                    legacyWake = true;
                    break;
                case LEGACY:
                    room.write("queue:legacy");
                    state.result.add("legacy-removed");
                    legacyWake = true;
                    break;
                default:
                    throw new AssertionError(branch);
            }
            raise(bodyFailure);
            state.legacyWake = legacyWake;
            return legacyWake ? Boolean.TRUE : Boolean.FALSE;
        }

        boolean run() {
            preflight();
            if (executingSend) return false;
            final String initialAttemptToken = state.token;
            if (!executingSend) room.trace.add("deleteOperation:SEND");
            Boolean wakeLegacy;
            if (modern) {
                wakeLegacy = TransactionScope.call(room::run, room::end,
                        () -> body(initialAttemptToken));
            } else {
                try {
                    room.begin();
                    wakeLegacy = body(initialAttemptToken);
                    room.commit();
                } finally {
                    room.end();
                }
            }
            state.legacyWake = Boolean.TRUE.equals(wakeLegacy);
            for (String wake : state.wakeups) room.trace.add("dispatch:" + wake);
            for (String reload : state.reload) room.trace.add("reload:" + reload);
            if (state.legacyWake) room.trace.add("eval:legacy");
            room.trace.add("cancel-notification");
            return state.legacyWake;
        }
    }

    private static boolean delete(boolean modern, TransactionCallableRoomModel room, State state,
                                 String token, boolean gmailHint, boolean executingSend,
                                 Branch branch, Throwable failure) {
        return new Recipe(modern, room, state, token, gmailHint, executingSend, branch, failure).run();
    }

    @Test
    public void hostRecipePreservesExistingTokenIdentityAndGeneratesOnlyWhenNeeded() {
        for (boolean modern : new boolean[]{false, true})
            for (String token : new String[]{null, "provided"}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                State state = new State();
                delete(modern, room, state, token, true, false, Branch.GMAIL_ACCEPTED, null);
                assertEquals(token == null ? "generated-1" : token, state.token);
                assertEquals(token == null ? 1 : 0, state.generated);
                assertEquals(Collections.singletonList("gmail:" + state.token), state.wakeups);
                assertSame(token == null ? null : token, token == null ? null : state.token);
            }
    }

    @Test
    public void sendExecutingBypassesTransactionWhileNonexecutingDeletesBeforeBegin() {
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel executing = new TransactionCallableRoomModel();
            State executingState = new State();
            delete(modern, executing, executingState, "send-token", true, true, Branch.GMAIL_ACCEPTED, null);
            assertEquals(Arrays.asList("preflight:send-token", "send-operation:SEND",
                    "return-preflight"), executing.trace);
            assertFalse(executing.trace.contains("begin"));
            assertEquals(Collections.singletonList("gmail-source-missing"), executingState.result);

            TransactionCallableRoomModel pending = new TransactionCallableRoomModel();
            State pendingState = new State();
            delete(modern, pending, pendingState, "send-token", true, false, Branch.MISSING, null);
            assertEquals(Arrays.asList("preflight:send-token", "send-operation:SEND",
                    "deleteOperation:SEND", "begin"), pending.trace.subList(0, 4));
            assertEquals(Collections.singletonList("gmail-missing"), pendingState.result);
        }
    }

    @Test
    public void allProviderBranchesReturnExpectedCachedBooleanAndFollowups() {
        Branch[] branches = {Branch.MISSING, Branch.STALE, Branch.GMAIL_ACCEPTED,
                Branch.GMAIL_REJECTED, Branch.GRAPH_PERMANENT, Branch.ZOHO_ACCEPTED,
                Branch.ZOHO_REJECTED, Branch.LOCAL_DRAFT, Branch.LEGACY};
        for (boolean modern : new boolean[]{false, true})
            for (Branch branch : branches) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                State state = new State();
                boolean actual = delete(modern, room, state, "t", branch == Branch.GMAIL_ACCEPTED ||
                        branch == Branch.GMAIL_REJECTED, false, branch, null);
                boolean expected = branch == Branch.GRAPH_PERMANENT || branch == Branch.ZOHO_ACCEPTED ||
                        branch == Branch.LOCAL_DRAFT || branch == Branch.LEGACY;
                assertEquals(branch + " result", expected, actual);
                assertEquals(branch + " cached result", expected, state.legacyWake);
                assertEquals(expected, room.trace.contains("eval:legacy"));
            }
    }

    @Test
    public void nontransactionalResultsSurviveRollbackButRoomWritesDoNot() {
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            State state = new State();
            IllegalStateException failure = new IllegalStateException("body");
            assertSame(failure, capture(() -> delete(modern, room, state, "t", true, false,
                    Branch.GMAIL_ACCEPTED, failure)));
            assertEquals(Collections.singletonList("gmail-queued"), state.result);
            assertEquals(Collections.singletonList("gmail:t"), state.wakeups);
            assertTrue(room.committed.isEmpty());
            assertFalse(room.trace.contains("dispatch:gmail:t"));
            assertFalse(room.trace.contains("cancel-notification"));
        }
    }

    @Test
    public void bodyFailureKeepsResultCarriersAndSkipsEveryPostEndConsumer() {
        for (Throwable expected : new Throwable[]{new IllegalStateException("body"), new AssertionError("body")})
            for (boolean modern : new boolean[]{false, true}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                State state = new State();
                assertSame(expected, capture(() -> delete(modern, room, state, null, true, false,
                        Branch.GMAIL_ACCEPTED, expected)));
                assertEquals(1, state.result.size());
                assertEquals(1, state.wakeups.size());
                assertTrue(room.committed.isEmpty());
                assertFalse(room.trace.contains("cancel-notification"));
            }
    }

    @Test
    public void gmailAcceptedGeneratesTokenInsideCallbackWhenHintIsFalse() {
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            State state = new State();
            delete(modern, room, state, null, false, false, Branch.GMAIL_ACCEPTED, null);
            assertEquals("generated-1", state.token);
            assertEquals(1, state.generated);
            assertEquals(Collections.singletonList("gmail:generated-1"), state.wakeups);
        }
    }

    @Test
    public void beginBodyMarkerAndEndFailuresRetainIdentityAndCleanup() {
        for (boolean modern : new boolean[]{false, true})
            for (int stage = 0; stage < 4; stage++) {
                final int failureStage = stage;
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                State state = new State();
                RuntimeException transactionFailure = new IllegalStateException("stage");
                Throwable expected = failureStage == 1 ? new AssertionError("body") :
                        transactionFailure;
                if (failureStage == 0) room.beginFailure = transactionFailure;
                if (failureStage == 2) room.commitFailure = transactionFailure;
                if (failureStage == 3) room.endFailure = expected;
                Throwable actual = capture(() -> delete(modern, room, state, "t", true, false,
                        Branch.GMAIL_ACCEPTED, failureStage == 1 ? expected : null));
                assertSame(expected, actual);
                assertFalse(room.trace.contains("cancel-notification"));
                assertTrue(failureStage == 3 ? !room.committed.isEmpty() : room.committed.isEmpty());
            }
    }

    @Test
    public void endFailureMasksBeginOrBodyAndIsNeverSuppressed() {
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel beginRoom = new TransactionCallableRoomModel();
            IllegalStateException begin = new IllegalStateException("begin");
            AssertionError end = new AssertionError("end");
            beginRoom.beginFailure = begin;
            beginRoom.endFailure = end;
            Throwable beginActual = capture(() -> delete(modern, beginRoom, new State(), "t", true,
                    false, Branch.GMAIL_ACCEPTED, null));
            assertSame(end, beginActual);
            assertEquals(0, beginActual.getSuppressed().length);

            TransactionCallableRoomModel bodyRoom = new TransactionCallableRoomModel();
            bodyRoom.endFailure = end;
            IllegalStateException body = new IllegalStateException("body");
            Throwable bodyActual = capture(() -> delete(modern, bodyRoom, new State(), "t", true,
                    false, Branch.GMAIL_ACCEPTED, body));
            assertSame(end, bodyActual);
            assertEquals(0, bodyActual.getSuppressed().length);
            assertFalse(bodyRoom.trace.contains("cancel-notification"));
        }
    }

    @Test
    public void successfulEndOrdersDispatchReloadLegacyEvaluationAndCancelAfterEnd() {
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            State state = new State();
            delete(modern, room, state, "t", true, false, Branch.LEGACY, null);
            int end = room.trace.indexOf("end");
            assertTrue(end >= 0);
            assertEquals(Arrays.asList("end", "eval:legacy", "cancel-notification"),
                    room.trace.subList(end, room.trace.size()));

            TransactionCallableRoomModel gmailRoom = new TransactionCallableRoomModel();
            State gmailState = new State();
            gmailState.reload.add("stale");
            delete(modern, gmailRoom, gmailState, "t", true, false, Branch.GMAIL_ACCEPTED, null);
            end = gmailRoom.trace.indexOf("end");
            assertEquals(Arrays.asList("end", "dispatch:gmail:t", "reload:stale", "cancel-notification"),
                    gmailRoom.trace.subList(end, gmailRoom.trace.size()));

            TransactionCallableRoomModel staleRoom = new TransactionCallableRoomModel();
            State staleState = new State();
            delete(modern, staleRoom, staleState, null, true, false, Branch.STALE, null);
            end = staleRoom.trace.indexOf("end");
            assertEquals("generated-1", staleState.token);
            assertEquals(Arrays.asList("end", "reload:stale", "cancel-notification"),
                    staleRoom.trace.subList(end, staleRoom.trace.size()));
        }
    }

    @Test
    public void nullAndEmptyInputsDoNotInventLegacyWakeOrProviderFollowups() {
        for (boolean modern : new boolean[]{false, true})
            for (Branch branch : new Branch[]{Branch.MISSING, Branch.STALE, Branch.GMAIL_REJECTED, Branch.ZOHO_REJECTED}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                State state = new State();
                boolean actual = delete(modern, room, state, null, branch == Branch.GMAIL_REJECTED,
                        false, branch, null);
                assertFalse(actual);
                assertFalse(room.trace.contains("eval:legacy"));
                assertTrue(room.trace.contains("cancel-notification"));
            }
    }
}

package in.anoopbhatia.mail;

import org.junit.Test;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

/** Executable host recipes; exact production bytes are separately source-gated. */
public class TransactionUpdateGraphTest {
    private static final class Message {
        int revision = 1;
        int revisions = 2;
        String graph = "old";
        int folder = 1;
    }
    private static void raise(Throwable ex) {
        if (ex instanceof RuntimeException) throw (RuntimeException) ex;
        if (ex instanceof Error) throw (Error) ex;
    }
    private static void updateBody(TransactionCallableRoomModel room, Message message,
                                   boolean existing, boolean process, Throwable failure) {
        room.trace.add("lookup");
        if (existing) {
            message.revision = 7;
            message.revisions = 8;
            room.trace.add("revision-copy");
        }
        room.write("update");
        if (process) room.write("rules");
        raise(failure);
    }
    private static void update(boolean modern, TransactionCallableRoomModel room, Message message,
                               boolean existing, boolean process, Throwable failure) {
        if (modern) {
            final Message updatedMessage = message;
            final boolean processRules = process;
            TransactionScope.run(body -> room.run(() -> { body.run(); return null; }), room::end,
                    () -> updateBody(room, updatedMessage, existing, processRules, failure));
        } else {
            try {
                room.begin();
                updateBody(room, message, existing, process, failure);
                room.commit();
            } finally {
                room.end();
            }
        }
        if (process) {
            room.trace.add("contact");
            room.trace.add("classify");
            room.trace.add("report");
        } else room.trace.add("unchanged");
    }
    private static boolean graphBody(TransactionCallableRoomModel room, boolean candidate,
                                     boolean pending, Boolean seen, Boolean flagged,
                                     boolean seenPending, boolean flagPending, Throwable failure) {
        boolean relocate = candidate;
        // Keep the pending-location query short-circuited when relocation is ineligible.
        if (relocate) room.trace.add("pending-location");
        relocate = relocate && !pending;
        if (relocate) {
            room.write("graph");
            room.write("folder");
        }
        if (seen != null) {
            room.write("seen:" + seen);
            if (!seenPending) room.write("uiSeen:" + seen);
        }
        if (flagged != null) {
            room.write("flag:" + flagged);
            if (!flagPending) room.write("uiFlag:" + flagged);
        }
        raise(failure);
        return relocate;
    }
    private static boolean graph(boolean modern, TransactionCallableRoomModel room, Message message,
                                 boolean candidate, boolean pending, Boolean seen, Boolean flagged,
                                 boolean seenPending, boolean flagPending, boolean sameFolder,
                                 Throwable failure) {
        final boolean relocationCandidate = candidate;
        boolean canRelocate;
        if (modern) {
            canRelocate = room.run(() -> {
                boolean relocate = graphBody(room, relocationCandidate, pending, seen, flagged,
                        seenPending, flagPending, failure);
                return relocate ? Boolean.TRUE : Boolean.FALSE;
            });
        } else {
            room.begin();
            try {
                canRelocate = graphBody(room, candidate, pending, seen, flagged,
                        seenPending, flagPending, failure);
                room.commit();
            } finally {
                room.end();
            }
        }
        if (canRelocate) {
            message.graph = "new";
            message.folder = 2;
            room.trace.add("message-location");
        }
        if (!sameFolder || !canRelocate) {
            room.trace.add("refresh-source");
            room.trace.add("refresh-actual");
        }
        return canRelocate;
    }
    private interface Work { void run(); }
    private static Throwable failure(Work work) {
        try { work.run(); } catch (Throwable ex) { return ex; }
        throw new AssertionError("expected failure");
    }
    @Test public void updateExistingMissingAndProcessBranchesKeepTraceAndCommittedWrites() {
        for (boolean existing : new boolean[]{false, true})
            for (boolean process : new boolean[]{false, true}) {
                TransactionCallableRoomModel old = new TransactionCallableRoomModel();
                TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
                Message before = new Message(), after = new Message();
                update(false, old, before, existing, process, null);
                update(true, modern, after, existing, process, null);
                assertEquals(old.trace, modern.trace);
                assertEquals(old.committed, modern.committed);
                assertEquals(existing ? 7 : 1, after.revision);
                assertEquals(existing ? 8 : 2, after.revisions);
                assertEquals(before.revision, after.revision);
                assertTrue(modern.trace.indexOf("end") < modern.trace.indexOf(process ? "contact" : "unchanged"));
            }
    }
    @Test public void updateBodyFailureRollsBackButDoesNotUndoMessageRevisionMutation() {
        for (Throwable ex : new Throwable[]{new IllegalStateException("body"), new AssertionError("body")})
            for (boolean modern : new boolean[]{false, true}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                Message message = new Message();
                assertSame(ex, failure(() -> update(modern, room, message, true, true, ex)));
                assertEquals(7, message.revision);
                assertEquals(8, message.revisions);
                assertTrue(room.committed.isEmpty());
                assertEquals("end", room.trace.get(room.trace.size() - 1));
                assertFalse(room.trace.contains("contact"));
            }
    }
    @Test public void graphBranchMatrixKeepsTargetedWritesPendingUiGuardsAndPostEndWork() {
        for (boolean candidate : new boolean[]{false, true})
            for (boolean pending : new boolean[]{false, true})
                for (Boolean seen : new Boolean[]{null, false, true})
                    for (Boolean flagged : new Boolean[]{null, false, true})
                        for (boolean seenPending : new boolean[]{false, true})
                            for (boolean flagPending : new boolean[]{false, true})
                                for (boolean sameFolder : new boolean[]{false, true}) {
                                    TransactionCallableRoomModel old = new TransactionCallableRoomModel();
                                    TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
                                    Message before = new Message(), after = new Message();
                                    boolean a = graph(false, old, before, candidate, pending, seen, flagged, seenPending, flagPending, sameFolder, null);
                                    boolean b = graph(true, modern, after, candidate, pending, seen, flagged, seenPending, flagPending, sameFolder, null);
                                    assertEquals(a, b);
                                    assertEquals(candidate && !pending, b);
                                    assertEquals(old.trace, modern.trace);
                                    assertEquals(old.committed, modern.committed);
                                    assertEquals(before.graph, after.graph);
                                    assertEquals(before.folder, after.folder);
                                    assertEquals(candidate, modern.trace.contains("pending-location"));
                                    if (b) assertTrue(modern.trace.indexOf("end") < modern.trace.indexOf("message-location"));
                                    if (!sameFolder || !b) assertTrue(modern.trace.indexOf("end") < modern.trace.indexOf("refresh-source"));
                                }
    }
    @Test public void graphBodyFailuresRollbackAndSkipMessageMutationAndRefresh() {
        for (Throwable ex : new Throwable[]{new IllegalStateException("body"), new AssertionError("body")})
            for (boolean modern : new boolean[]{false, true}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                Message message = new Message();
                assertSame(ex, failure(() -> graph(modern, room, message, true, false, true, true, false, false, false, ex)));
                assertEquals("old", message.graph);
                assertEquals(1, message.folder);
                assertTrue(room.committed.isEmpty());
                assertEquals(Arrays.asList("begin", "pending-location", "write:graph", "write:folder",
                        "write:seen:true", "write:uiSeen:true", "write:flag:true", "write:uiFlag:true",
                        "end"), room.trace);
                assertEquals(1, Collections.frequency(room.trace, "end"));
                assertFalse(room.trace.contains("refresh-source"));
            }
    }
    @Test public void beginFailuresRetainDistinctCleanupPolicies() {
        for (boolean graph : new boolean[]{false, true})
            for (boolean modern : new boolean[]{false, true}) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                room.beginFailure = new IllegalStateException("begin");
                Message message = new Message();
                assertSame(room.beginFailure, failure(() -> {
                    if (graph) graph(modern, room, message, true, false, null, null, false, false, false, null);
                    else update(modern, room, message, true, true, null);
                }));
                assertEquals(graph ? Collections.singletonList("begin") : Arrays.asList("begin", "end"), room.trace);
            }
    }
    @Test public void markerAndEndFailuresMatchLegacyTracesAndSkipPostEndWork() {
        for (boolean graph : new boolean[]{false, true})
            for (boolean marker : new boolean[]{false, true}) {
                TransactionCallableRoomModel old = new TransactionCallableRoomModel();
                TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
                RuntimeException expected = new IllegalStateException(marker ? "marker" : "end");
                if (marker) { old.commitFailure = expected; modern.commitFailure = expected; }
                else { old.endFailure = expected; modern.endFailure = expected; }
                for (boolean current : new boolean[]{false, true}) {
                    TransactionCallableRoomModel room = current ? modern : old;
                    assertSame(expected, failure(() -> {
                        if (graph) graph(current, room, new Message(), true, false, true, true, false, false, false, null);
                        else update(current, room, new Message(), true, true, null);
                    }));
                    assertFalse(room.trace.contains("contact"));
                    assertFalse(room.trace.contains("message-location"));
                }
                assertEquals(old.trace, modern.trace);
                assertEquals(old.committed, modern.committed);
            }
    }
    @Test public void endFailureMasksBodyOrBeginOnlyWhenLegacyEndRuns() {
        for (boolean graph : new boolean[]{false, true})
            for (boolean begin : new boolean[]{false, true})
                for (boolean modern : new boolean[]{false, true}) {
                    TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                    RuntimeException original = new IllegalStateException("original");
                    Error end = new AssertionError("end");
                    room.endFailure = end;
                    if (begin) room.beginFailure = original;
                    Throwable actual = failure(() -> {
                        if (graph) graph(modern, room, new Message(), true, false, null, null, false, false, false, begin ? null : original);
                        else update(modern, room, new Message(), true, true, begin ? null : original);
                    });
                    assertSame(graph && begin ? original : end, actual);
                }
    }
    @Test public void retainedKnownEndBridgeFailureStillAllowsExistingPostEndConsumers() {
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            room.endFailure = new IllegalStateException("no current transaction");
            room.swallowKnownEndFailure = true;
            Message message = new Message();
            assertTrue(graph(modern, room, message, true, false, null, null, false, false, false, null));
            assertEquals("new", message.graph);
            assertTrue(room.trace.indexOf("logged-end") < room.trace.indexOf("message-location"));
        }
    }
}

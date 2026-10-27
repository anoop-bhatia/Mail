package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.Test;

/** Host parity recipes for inputs captured before Room starts. */
public class TransactionCompletionNormalizedInputTest {
    private interface ThrowingCall<T> { T call() throws Exception; }
    private interface Followup { void run() throws Exception; }

    private static <T> T oldResult(TransactionCallableRoomModel room, boolean commit,
                                   Callable<T> body) throws Exception {
        T result;
        try {
            room.begin();
            result = body.call();
            if (!commit)
                return null;
            room.commit();
        } finally {
            room.end();
        }
        return result;
    }

    private static <T> T newResult(TransactionCallableRoomModel room, boolean commit,
                                   Callable<T> body) throws Exception {
        return TransactionCompletion.call(room::run, room::end, () -> {
            T value = body.call();
            return commit ? TransactionResult.commit(value) : TransactionResult.rollback(value);
        });
    }

    private static Throwable capture(ThrowingCall<?> call) {
        try { call.call(); return null; }
        catch (Throwable failure) { return failure; }
    }

    private static void assertFailureParity(Throwable expected, ThrowingCall<?> oldCall,
                                            ThrowingCall<?> newCall) {
        assertSame(expected, capture(oldCall));
        assertSame(expected, capture(newCall));
        assertEquals(0, expected.getSuppressed().length);
    }

    @Test
    public void importanceUsesNullableNormalizedIntegerAndQueuesBothFlags() throws Exception {
        for (Integer importance : new Integer[] {null, 0, 2}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            List<String> oldTrace = importance(oldRoom, importance, false);
            List<String> newTrace = importance(newRoom, importance, true);
            assertEquals(oldTrace, newTrace);
            assertEquals(Arrays.asList("read:importance", "normalize", "begin",
                    "read:message", "write:importance:" + importance,
                    "queue:low:" + (importance != null && importance == 0),
                    "queue:high:" + (importance != null && importance == 2),
                    "commit", "end"), oldTrace);
        }
    }

    private static List<String> importance(TransactionCallableRoomModel room, Integer value,
                                           boolean modern) throws Exception {
        List<String> trace = new ArrayList<>();
        trace.add("read:importance");
        trace.add("normalize");
        if (modern) {
            TransactionCompletion.call(room::run, room::end, () -> {
                room.trace.add("read:message");
                room.trace.add("write:importance:" + value);
                room.trace.add("queue:low:" + (value != null && value == 0));
                room.trace.add("queue:high:" + (value != null && value == 2));
                return TransactionResult.commit(null);
            });
        } else {
            room.begin(); room.trace.add("read:message"); room.trace.add("write:importance:" + value);
            room.trace.add("queue:low:" + (value != null && value == 0));
            room.trace.add("queue:high:" + (value != null && value == 2)); room.commit(); room.end();
        }
        trace.addAll(room.trace);
        return trace;
    }

    @Test
    public void attachmentCallbackSharesOriginalObjectAndNeverRunsAfterRollbackOrEndFailure() throws Exception {
        Attachment original = new Attachment("name", "type", 7);
        for (boolean missingDraft : new boolean[] {false, true}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            List<String> oldTrace = new ArrayList<>();
            List<String> newTrace = new ArrayList<>();
            Attachment[] oldSeen = new Attachment[1], newSeen = new Attachment[1];
            attachment(oldRoom, original, missingDraft, false, oldTrace, false, oldSeen);
            attachment(newRoom, original, missingDraft, false, newTrace, true, newSeen);
            assertEquals(oldTrace, newTrace);
            if (missingDraft) { assertEquals(null, oldSeen[0]); assertEquals(null, newSeen[0]); }
            else { assertSame(original, oldSeen[0]); assertSame(original, newSeen[0]); }
            assertEquals(missingDraft ? Arrays.asList("begin", "read:draft", "end")
                    : Arrays.asList("begin", "read:draft", "write:attachment", "commit", "end", "callback"), oldTrace);
        }
        TransactionCallableRoomModel oldEnd = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newEnd = new TransactionCallableRoomModel();
        oldEnd.endFailure = new AssertionError("end");
        newEnd.endFailure = oldEnd.endFailure;
        List<String> oldFollowup = new ArrayList<>(), newFollowup = new ArrayList<>();
        Attachment[] oldSeen = new Attachment[1], newSeen = new Attachment[1];
        Throwable failure = capture(() -> { attachment(oldEnd, original, false, true, oldFollowup, false, oldSeen); return null; });
        Throwable modernFailure = capture(() -> { attachment(newEnd, original, false, true, newFollowup, true, newSeen); return null; });
        assertEquals(null, oldSeen[0]); assertEquals(null, newSeen[0]);
        assertSame(oldEnd.endFailure, failure); assertSame(failure, modernFailure);
        assertEquals(Arrays.asList("begin", "read:draft", "write:attachment", "commit", "end"), oldEnd.trace);
        assertEquals(oldEnd.trace, newEnd.trace);
    }

    private static void attachment(TransactionCallableRoomModel room, Attachment attachment,
                                   boolean missing, boolean endFails, List<String> trace,
                                   boolean modern, Attachment[] seen) throws Exception {
        Followup callback = () -> { seen[0] = attachment; assertEquals("name", attachment.name);
            assertEquals("type", attachment.type); assertEquals(7, attachment.sequence); room.trace.add("callback"); };
        if (modern) {
            Boolean ok = TransactionCompletion.call(room::run, room::end, () -> {
                room.trace.add("read:draft"); if (missing) return TransactionResult.rollback(false);
                room.trace.add("write:attachment"); return TransactionResult.commit(true);
            });
            if (Boolean.TRUE.equals(ok)) callback.run();
        } else {
            boolean success = false;
            try { room.begin(); room.trace.add("read:draft"); if (!missing) {
                    room.trace.add("write:attachment"); room.commit(); success = true;
                }
            } finally { room.end(); }
            if (success) callback.run();
        }
        trace.addAll(room.trace);
    }

    @Test
    public void notesHaveThreeRollbackExitsWithoutInventedSecondWriteAndWidgetOnlyAfterSuccess() throws Exception {
        for (int exit = 0; exit < 4; exit++) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            List<String> oldWidget = new ArrayList<>(), newWidget = new ArrayList<>();
            notes(oldRoom, exit, oldWidget, false); notes(newRoom, exit, newWidget, true);
            assertEquals(oldRoom.trace, newRoom.trace); assertEquals(oldRoom.committed, newRoom.committed);
            assertEquals(exit == 3 ? Arrays.asList("widget") : Arrays.asList(), oldWidget);
            assertEquals(oldWidget, newWidget);
            assertEquals(exit == 3 ? Arrays.asList("notes:original", "notes:similar") : Arrays.asList(),
                    oldRoom.committed);
            if (exit == 3)
                assertTrue(oldRoom.trace.indexOf("widget") > oldRoom.trace.indexOf("end"));
        }
    }

    private static void notes(TransactionCallableRoomModel room, int exit, List<String> widget,
                              boolean modern) throws Exception {
        boolean success;
        if (modern) {
            Boolean result = TransactionCompletion.call(room::run, room::end, () -> {
                room.trace.add("read:message"); if (exit == 0) return TransactionResult.rollback(false);
                room.write("notes:original"); if (exit == 1) return TransactionResult.rollback(false);
                room.trace.add("lookup:similarity"); if (exit == 2) return TransactionResult.rollback(false);
                room.write("notes:similar"); return TransactionResult.commit(true); });
            success = Boolean.TRUE.equals(result);
        } else { try { room.begin(); room.trace.add("read:message"); if (exit == 0) return;
            room.write("notes:original"); if (exit == 1) return;
            room.trace.add("lookup:similarity"); if (exit == 2) return;
            room.write("notes:similar"); room.commit(); success = true; } finally { room.end(); } }
        if (success) {
            widget.add("widget");
            room.trace.add("widget");
        }
    }

    @Test
    public void selectFolderReadsInputBeforeBeginAndUsesLookupDerivedNameForAllPaths() throws Exception {
        for (boolean accountPresent : new boolean[]{false, true})
            for (String parent : new String[]{null, "Parent/"}) {
                TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
                TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
                selectFolder(oldRoom, accountPresent, parent, false);
                selectFolder(newRoom, accountPresent, parent, true);
                assertEquals(oldRoom.trace, newRoom.trace);
                assertEquals(oldRoom.committed, newRoom.committed);
                if (!accountPresent) {
                    assertEquals(Arrays.asList("read:input", "begin", "read:account", "end"), oldRoom.trace);
                    assertTrue(oldRoom.committed.isEmpty());
                } else {
                    String name = (parent == null ? "Archive" : "Parent/Archive");
                    assertEquals(Arrays.asList("folder:" + name), oldRoom.committed);
                    assertTrue(oldRoom.trace.indexOf("lookup:parent") > oldRoom.trace.indexOf("begin"));
                    assertEquals(parent != null, oldRoom.trace.contains("prefix"));
                    assertTrue(oldRoom.trace.indexOf("args:folder") < oldRoom.trace.indexOf("commit"));
                    assertTrue(oldRoom.trace.indexOf("reload") > oldRoom.trace.indexOf("end"));
                    assertTrue(oldRoom.trace.indexOf("query") > oldRoom.trace.indexOf("reload"));
                }
            }
    }

    private static void selectFolder(TransactionCallableRoomModel room, boolean accountPresent,
                                     String parent, boolean modern) throws Exception {
        room.trace.add("read:input");
        String name = "Archive";
        if (modern) {
            final String inputName = name;
            Boolean result = TransactionCompletion.call(room::run, room::end, () -> {
                room.trace.add("read:account");
                if (!accountPresent)
                    return TransactionResult.rollback(false);
                room.trace.add("lookup:parent");
                if (parent != null)
                    room.trace.add("prefix");
                final String folderName = (parent == null ? inputName : parent + inputName);
                room.write("folder:" + folderName);
                room.trace.add("args:folder");
                return TransactionResult.commit(true);
            });
            if (!result)
                return;
        } else {
            try {
                room.begin();
                room.trace.add("read:account");
                if (!accountPresent)
                    return;
                room.trace.add("lookup:parent");
                if (parent != null) {
                    room.trace.add("prefix");
                    name = parent + name;
                }
                room.write("folder:" + name);
                room.trace.add("args:folder");
                room.commit();
            } finally {
                room.end();
            }
        }
        room.trace.add("reload");
        room.trace.add("query");
    }

    @Test
    public void colorQueuesNullableIntegerAndEvaluatesOnlyAfterSuccessfulEnd() throws Exception {
        for (Integer color : new Integer[] {null, 0, 0x123456}) for (boolean missing : new boolean[] {false, true}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            List<String> oldEval = new ArrayList<>(), newEval = new ArrayList<>();
            color(oldRoom, color, missing, oldEval, false); color(newRoom, color, missing, newEval, true);
            assertEquals(oldRoom.trace, newRoom.trace); assertEquals(oldEval, newEval);
            assertEquals(missing ? Arrays.asList("begin", "read:message", "end")
                    : Arrays.asList("begin", "read:message", "queue:color:" + color, "commit", "end", "eval"), oldRoom.trace);
        }
    }

    private static void color(TransactionCallableRoomModel room, Integer value, boolean missing,
                              List<String> eval, boolean modern) throws Exception {
        if (modern) { Boolean ok = TransactionCompletion.call(room::run, room::end, () -> {
            room.trace.add("read:message"); if (missing) return TransactionResult.rollback(false);
            room.trace.add("queue:color:" + value); return TransactionResult.commit(true); }); if (Boolean.TRUE.equals(ok)) { eval.add("eval"); room.trace.add("eval"); } }
        else { try { room.begin(); room.trace.add("read:message"); if (missing) return;
            room.trace.add("queue:color:" + value); room.commit(); } finally { room.end(); } eval.add("eval"); room.trace.add("eval"); }
    }

    @Test
    public void snoozeRollbackReturnsNormalizedWakeupIncludingSameReference() throws Exception {
        for (Long wakeup : new Long[] {null, Long.valueOf(1000)}) for (boolean success : new boolean[] {false, true}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel(), newRoom = new TransactionCallableRoomModel();
            Long oldValue = snooze(oldRoom, wakeup, success, false); Long newValue = snooze(newRoom, wakeup, success, true);
            assertSame(wakeup, oldValue); assertSame(oldValue, newValue); assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(success ? Arrays.asList("begin", "read:message", "write:snooze:" + wakeup, "commit", "end")
                    : Arrays.asList("begin", "read:message", "end"), oldRoom.trace);
        }
    }

    private static Long snooze(TransactionCallableRoomModel room, Long wakeup, boolean success, boolean modern) throws Exception {
        if (modern) return TransactionCompletion.call(room::run, room::end, () -> {
            room.trace.add("read:message"); if (!success) return TransactionResult.rollback(wakeup);
            room.trace.add("write:snooze:" + wakeup); return TransactionResult.commit(wakeup); });
        try { room.begin(); room.trace.add("read:message"); if (!success) return wakeup;
            room.trace.add("write:snooze:" + wakeup); room.commit(); } finally { room.end(); } return wakeup;
    }

    @Test
    public void normalizationFailureIsBeforeBeginWithExactIdentity() {
        RuntimeException failure = new IllegalArgumentException("normalize");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel(), newRoom = new TransactionCallableRoomModel();
        assertSame(failure, capture(() -> { oldNormalizeThenRun(oldRoom, failure); return null; }));
        assertSame(failure, capture(() -> { newNormalizeThenRun(newRoom, failure); return null; }));
        assertTrue(oldRoom.trace.isEmpty()); assertTrue(newRoom.trace.isEmpty());
    }

    private static void oldNormalizeThenRun(TransactionCallableRoomModel room, RuntimeException failure)
            throws Exception { throwNormalize(failure); }

    private static void newNormalizeThenRun(TransactionCallableRoomModel room, RuntimeException failure)
            throws Exception { throwNormalize(failure); }

    private static void throwNormalize(RuntimeException failure) { throw failure; }

    @Test
    public void beginBodyMarkerAndEndFailuresKeepIdentityAndMask() {
        RuntimeException begin = new IllegalStateException("begin");
        TransactionCallableRoomModel oldBegin = new TransactionCallableRoomModel(), newBegin = new TransactionCallableRoomModel();
        oldBegin.beginFailure = begin; newBegin.beginFailure = begin;
        assertFailureParity(begin, () -> oldResult(oldBegin, true, () -> "x"), () -> newResult(newBegin, true, () -> "x"));
        IOException body = new IOException("body");
        TransactionCallableRoomModel oldBody = new TransactionCallableRoomModel(), newBody = new TransactionCallableRoomModel();
        assertFailureParity(body, () -> oldResult(oldBody, true, () -> { throw body; }), () -> newResult(newBody, true, () -> { throw body; }));
        RuntimeException marker = new IllegalStateException("marker");
        TransactionCallableRoomModel oldMarker = new TransactionCallableRoomModel(), newMarker = new TransactionCallableRoomModel();
        oldMarker.commitFailure = marker; newMarker.commitFailure = marker;
        assertFailureParity(marker, () -> oldResult(oldMarker, true, () -> "x"), () -> newResult(newMarker, true, () -> "x"));
        Error end = new AssertionError("end");
        TransactionCallableRoomModel oldEnd = new TransactionCallableRoomModel(), newEnd = new TransactionCallableRoomModel();
        oldEnd.endFailure = end; newEnd.endFailure = end;
        assertFailureParity(end, () -> oldResult(oldEnd, true, () -> "x"), () -> newResult(newEnd, true, () -> "x"));
        for (int phase = 0; phase < 4; phase++) {
            TransactionCallableRoomModel oldMask = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newMask = new TransactionCallableRoomModel();
            oldMask.endFailure = newMask.endFailure = end;
            if (phase == 0)
                oldMask.beginFailure = newMask.beginFailure = begin;
            if (phase == 2)
                oldMask.commitFailure = newMask.commitFailure = marker;
            final int failurePhase = phase;
            assertFailureParity(end,
                    () -> oldResult(oldMask, failurePhase != 3, () -> {
                        if (failurePhase == 1) throw body;
                        return null;
                    }),
                    () -> newResult(newMask, failurePhase != 3, () -> {
                        if (failurePhase == 1) throw body;
                        return null;
                    }));
            assertEquals(oldMask.trace, newMask.trace);
        }
    }

    @Test
    public void nestedRollbackPoisonsOuterWithSameWritesAndResult() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel(), newRoom = new TransactionCallableRoomModel();
        assertEquals("outer", oldResult(oldRoom, true, () -> { oldRoom.write("outer"); oldResult(oldRoom, false, () -> { oldRoom.write("inner"); return null; }); oldRoom.write("after"); return "outer"; }));
        assertEquals("outer", newResult(newRoom, true, () -> { newRoom.write("outer"); newResult(newRoom, false, () -> { newRoom.write("inner"); return null; }); newRoom.write("after"); return "outer"; }));
        assertEquals(oldRoom.trace, newRoom.trace); assertTrue(oldRoom.committed.isEmpty()); assertEquals(oldRoom.committed, newRoom.committed);
    }

    @Test
    public void knownEndFailureIsSwallowedAndFollowupKeepsOrder() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel(), newRoom = new TransactionCallableRoomModel();
        oldRoom.swallowKnownEndFailure = true; newRoom.swallowKnownEndFailure = true;
        IllegalStateException end = new IllegalStateException("no current transaction"); oldRoom.endFailure = end; newRoom.endFailure = end;
        oldResult(oldRoom, true, () -> "value"); newResult(newRoom, true, () -> "value"); oldRoom.trace.add("followup"); newRoom.trace.add("followup");
        assertEquals(Arrays.asList("begin", "commit", "end", "logged-end", "followup"), oldRoom.trace); assertEquals(oldRoom.trace, newRoom.trace);
    }

    private static final class Attachment {
        final String name, type; final int sequence;
        Attachment(String name, String type, int sequence) { this.name = name; this.type = type; this.sequence = sequence; }
    }
}

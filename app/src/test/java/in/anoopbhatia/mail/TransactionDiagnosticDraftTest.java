package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

/**
 * Host recipes for the DebugHelper diagnostic-draft transaction.
 *
 * <p>This deliberately models the Room boundary rather than claiming to test
 * Android, SQLite, attachment helpers, or ServiceSynchronize itself.</p>
 */
public class TransactionDiagnosticDraftTest {
    private enum FailureStage { NONE, BODY }

    private interface DraftCall {
        EntityMessage run(TransactionCallableRoomModel room, List<String> identities,
                          boolean drafts, String recipient, int sdk, List<String> files)
                throws Exception;
    }

    private static EntityMessage diagnostic(boolean modern, TransactionCallableRoomModel room,
                                           List<String> identities, boolean drafts,
                                           String recipient, int sdk, List<String> files)
            throws Exception {
        return diagnostic(modern, room, identities, drafts, recipient, sdk, files,
                new ArrayList<EntityMessage>(), FailureStage.NONE, null, null);
    }

    private static EntityMessage diagnostic(boolean modern, TransactionCallableRoomModel room,
                                           List<String> identities, boolean drafts,
                                           String recipient, int sdk, List<String> files,
                                           List<EntityMessage> allocated,
                                           FailureStage failureStage, Throwable failure,
                                           IOException fileFailure) throws Exception {
        DraftCall recipe = (r, ids, hasDrafts, to, version, written) -> {
            r.trace.add("lookup:identities");
            if (ids == null || ids.isEmpty())
                throw new IllegalArgumentException("no composable identities");
            r.trace.add("select:identity:" + ids.get(0));
            r.trace.add("lookup:drafts");
            if (!hasDrafts)
                throw new IllegalArgumentException("no drafts");

            EntityMessage draft = new EntityMessage();
            allocated.add(draft);
            r.trace.add("allocate");
            r.trace.add("select:from:" + ids.get(0));
            r.trace.add("select:recipient:" + (to == null ? "default" : to));
            r.trace.add("insert");
            r.write("message");
            if (failureStage == FailureStage.BODY)
                throwUnchecked(failure);
            // A file write is intentionally outside Room's rollback model.
            written.add("file");
            if (fileFailure != null)
                throw fileFailure;
            r.trace.add("write:content");
            r.trace.add("content");
            for (int i = 1; i <= 7; i++)
                r.trace.add("attachment:" + i);
            if (version >= 26)
                r.trace.add("attachment:8");
            r.trace.add("attachment:9");
            r.trace.add("queue:ADD");
            return draft;
        };

        EntityMessage draft;
        if (modern) {
            draft = TransactionScope.call(room::run, room::end,
                    () -> recipe.run(room, identities, drafts, recipient, sdk, files));
        } else {
            draft = null;
            try {
                room.begin();
                draft = recipe.run(room, identities, drafts, recipient, sdk, files);
                room.commit();
            } finally {
                room.end();
            }
        }
        room.trace.add("eval:debuginfo");
        return draft;
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof Error)
            throw (Error) failure;
        if (failure instanceof RuntimeException)
            throw (RuntimeException) failure;
        throw new AssertionError(failure);
    }

    @Test
    public void emptyIdentitiesAndMissingDraftsValidateBeforeAllocationAndEval() {
        for (boolean modern : new boolean[]{false, true}) {
            for (List<String> identities : Arrays.asList(Collections.<String>emptyList(), null)) {
                TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                try {
                    diagnostic(modern, room, identities, true, null, 30, new ArrayList<>());
                    fail("expected identity validation");
                } catch (IllegalArgumentException expected) {
                    assertEquals("no composable identities", expected.getMessage());
                } catch (Exception unexpected) {
                    fail(unexpected.toString());
                }
                assertEquals(Arrays.asList("begin", "lookup:identities", "end"), room.trace);
                assertFalse(room.trace.contains("allocate"));
                assertFalse(room.trace.contains("eval:debuginfo"));
            }
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            try {
                diagnostic(modern, room, Arrays.asList("identity"), false, null, 30,
                        new ArrayList<>());
                fail("expected drafts validation");
            } catch (IllegalArgumentException expected) {
                assertEquals("no drafts", expected.getMessage());
            } catch (Exception unexpected) {
                fail(unexpected.toString());
            }
            assertEquals(Arrays.asList("begin", "lookup:identities", "select:identity:identity",
                    "lookup:drafts", "end"), room.trace);
            assertFalse(room.trace.contains("allocate"));
            assertFalse(room.trace.contains("eval:debuginfo"));
        }
    }

    @Test
    public void legacyAndModernRecipesReturnTheBareAllocatedReferenceAndEvalAfterEnd()
            throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        List<EntityMessage> oldAllocated = new ArrayList<>();
        List<EntityMessage> newAllocated = new ArrayList<>();
        List<String> oldFiles = new ArrayList<>();
        List<String> newFiles = new ArrayList<>();
        EntityMessage oldDraft = diagnostic(false, oldRoom, Arrays.asList("first", "second"),
                true, "chosen", 30, oldFiles, oldAllocated, FailureStage.NONE, null, null);
        EntityMessage newDraft = diagnostic(true, newRoom, Arrays.asList("first", "second"),
                true, "chosen", 30, newFiles, newAllocated, FailureStage.NONE, null, null);
        assertSame(oldAllocated.get(0), oldDraft);
        assertSame(newAllocated.get(0), newDraft);
        assertTrue(oldDraft != newDraft);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(oldRoom.committed, newRoom.committed);
        assertEquals(Arrays.asList("file"), oldFiles);
        assertTrue(newRoom.trace.indexOf("eval:debuginfo") > newRoom.trace.indexOf("end"));
        assertEquals(1, count(newRoom.trace, "allocate"));
    }

    @Test
    public void fullRecipePreservesSelectionContentAttachmentsQueueAndCommitOrder() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        diagnostic(true, room, Arrays.asList("identity"), true, "recipient", 30,
                new ArrayList<>());
        assertEquals(Arrays.asList("begin", "lookup:identities", "select:identity:identity",
                "lookup:drafts", "allocate", "select:from:identity", "select:recipient:recipient",
                "insert", "write:message", "write:content", "content",
                "attachment:1", "attachment:2", "attachment:3", "attachment:4",
                "attachment:5", "attachment:6", "attachment:7", "attachment:8",
                "attachment:9", "queue:ADD", "commit", "end", "eval:debuginfo"), room.trace);
        assertEquals(Collections.singletonList("message"), room.committed);
    }

    @Test
    public void notificationAttachmentIsConditionalOnSdk() throws Exception {
        for (int sdk : new int[]{25, 26}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            diagnostic(true, room, Arrays.asList("identity"), true, null, sdk, new ArrayList<>());
            assertEquals(sdk >= 26, room.trace.contains("attachment:8"));
            assertTrue(room.trace.indexOf("attachment:9") > room.trace.indexOf("attachment:7"));
        }
    }

    @Test
    public void ioFailureLeavesFileEffectButRollsBackRoomAndSkipsEval() {
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            List<String> files = new ArrayList<>();
            IOException expected = new IOException("write failed");
            try {
                diagnostic(modern, room, Arrays.asList("identity"), true, null, 30, files,
                        new ArrayList<EntityMessage>(), FailureStage.NONE, null, expected);
                fail("expected IOException");
            } catch (IOException actual) {
                assertSame(expected, actual);
            } catch (Exception unexpected) {
                fail(unexpected.toString());
            }
            assertEquals(Collections.singletonList("file"), files);
            assertEquals(Collections.emptyList(), room.committed);
            assertTrue(room.trace.contains("write:message"));
            assertFalse(room.trace.contains("eval:debuginfo"));
        }
    }

    @Test
    public void cleanupFailureMasksEveryOriginalStageAndMatchesLegacy() {
        for (boolean modern : new boolean[]{false, true}) {
            for (int stage = 0; stage < 4; stage++) {
                for (boolean errorOriginal : new boolean[]{false, true}) {
                    TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                    TransactionCallableRoomModel comparison = new TransactionCallableRoomModel();
                    Throwable original = stage == 3 ? null : (errorOriginal
                            ? new AssertionError("original:" + stage)
                            : new IllegalStateException("original:" + stage));
                    Error end = new AssertionError("distinct-end:" + stage + ":" + errorOriginal);
                    configureFailure(room, stage, original, end);
                    configureFailure(comparison, stage, original, end);
                    final int failureStage = stage;
                    Throwable actual = outcome(() -> invoke(modern, room, failureStage, original));
                    Throwable expected = outcome(() -> invoke(false, comparison, failureStage, original));
                    assertSame(end, actual);
                    assertSame(end, expected);
                    assertEquals(comparison.trace, room.trace);
                    assertEquals(comparison.committed, room.committed);
                    assertFalse(room.trace.contains("eval:debuginfo"));
                    assertFalse(comparison.trace.contains("eval:debuginfo"));
                }
            }
        }
    }

    @Test
    public void unmaskedFailuresKeepOriginalIdentityAndMatchLegacy() {
        for (int stage = 0; stage < 4; stage++)
            for (boolean errorOriginal : new boolean[]{false, true}) {
                TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
                TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
                Throwable original = errorOriginal ? new AssertionError("original:" + stage)
                        : new IllegalStateException("original:" + stage);
                for (TransactionCallableRoomModel room : new TransactionCallableRoomModel[]{oldRoom, newRoom}) {
                    if (stage == 0) room.beginFailure = original;
                    if (stage == 2) room.commitFailure = original;
                    if (stage == 3) room.endFailure = original;
                }
                final int failureStage = stage;
                assertSame(original, outcome(() -> invoke(false, oldRoom, failureStage, original)));
                assertSame(original, outcome(() -> invoke(true, newRoom, failureStage, original)));
                assertEquals(oldRoom.trace, newRoom.trace);
                assertEquals(oldRoom.committed, newRoom.committed);
                assertFalse(newRoom.trace.contains("eval:debuginfo"));
            }
    }

    private static void configureFailure(TransactionCallableRoomModel room, int stage,
                                        Throwable original, Error end) {
        room.endFailure = end;
        if (stage == 0)
            room.beginFailure = original;
        else if (stage == 2)
            room.commitFailure = original;
    }

    private static void invoke(boolean modern, TransactionCallableRoomModel room, int stage,
                               Throwable original) throws Exception {
        if (stage == 3)
            diagnostic(modern, room, Arrays.asList("identity"), true, null, 30,
                    new ArrayList<>());
        else if (stage == 1)
            diagnostic(modern, room, Arrays.asList("identity"), true, null, 30,
                    new ArrayList<>(), new ArrayList<EntityMessage>(), FailureStage.BODY,
                    original, null);
        else
            diagnostic(modern, room, Arrays.asList("identity"), true, null, 30,
                    new ArrayList<>());
    }

    private static Throwable outcome(ThrowingCall operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable ex) {
            return ex;
        }
    }

    private interface ThrowingCall {
        void run() throws Exception;
    }

    private static int count(List<String> values, String wanted) {
        int result = 0;
        for (String value : values)
            if (wanted.equals(value)) result++;
        return result;
    }
}

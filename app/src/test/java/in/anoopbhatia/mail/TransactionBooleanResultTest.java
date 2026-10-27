package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

/** Focused host recipes; exact-source gates separately pin the real consumer bodies. */
public class TransactionBooleanResultTest {
    private interface Call { void run() throws Exception; }
    private static final class Content { boolean value = true; }

    private static Throwable failure(Call call) {
        try {
            call.run();
            return null;
        } catch (Throwable ex) {
            return ex;
        }
    }

    private static void raise(Throwable ex) throws Exception {
        if (ex instanceof Error)
            throw (Error) ex;
        if (ex != null)
            throw (Exception) ex;
    }

    private static boolean pollBody(TransactionCallableRoomModel room, boolean scheduled,
                                    boolean eligible, Throwable bodyFailure) throws Exception {
        boolean work = false;
        room.trace.add("accounts");
        if (scheduled || eligible) {
            work = true;
            room.write("poll");
        }
        raise(bodyFailure);
        return work;
    }

    private static Boolean poll(boolean modern, TransactionCallableRoomModel room,
                                boolean scheduled, boolean eligible, Throwable bodyFailure)
            throws Exception {
        boolean work;
        if (modern)
            work = TransactionScope.call(room::run, room::end, () -> {
                boolean pollWork = pollBody(room, scheduled, eligible, bodyFailure);
                return pollWork ? Boolean.TRUE : Boolean.FALSE;
            });
        else {
            work = false;
            try {
                room.begin();
                work = pollBody(room, scheduled, eligible, bodyFailure);
                room.commit();
            } finally {
                room.end();
            }
        }
        room.trace.add("schedule:" + work);
        room.trace.add("eval:poll");
        return work ? Boolean.TRUE : Boolean.FALSE;
    }

    private static boolean staleBody(TransactionCallableRoomModel room, Content content,
                                     boolean nativeRest, boolean queueResult,
                                     Throwable bodyFailure) throws Exception {
        boolean queued = false;
        room.write("reset");
        content.value = false; // The original entity mutation is not rolled back by SQLite.
        room.trace.add("content:false");
        if (nativeRest) {
            room.trace.add("native-queue");
            queued = queueResult;
            if (queued)
                room.write("native:BODY");
        } else
            room.write("BODY");
        raise(bodyFailure);
        return queued;
    }

    private static Boolean stale(boolean modern, TransactionCallableRoomModel room,
                                 Content content, boolean nativeRest, boolean queueResult,
                                 Throwable bodyFailure) throws Exception {
        boolean bodyQueued;
        if (modern)
            bodyQueued = TransactionScope.call(room::run, room::end, () -> {
                boolean queuedBody = staleBody(room, content, nativeRest, queueResult, bodyFailure);
                return queuedBody ? Boolean.TRUE : Boolean.FALSE;
            });
        else {
            bodyQueued = false;
            try {
                room.begin();
                bodyQueued = staleBody(room, content, nativeRest, queueResult, bodyFailure);
                room.commit();
            } finally {
                room.end();
            }
        }
        if (nativeRest) {
            room.trace.add("diagnostic:" + bodyQueued);
            room.trace.add("eval:native");
        }
        return bodyQueued ? Boolean.TRUE : Boolean.FALSE;
    }

    @Test
    public void pollScheduledAndIgnoredScheduleProduceSameWorkAndPostEndOrder() throws Exception {
        for (boolean scheduled : new boolean[]{false, true}) {
            TransactionCallableRoomModel old = new TransactionCallableRoomModel();
            TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
            assertSame(Boolean.TRUE, poll(false, old, scheduled, !scheduled, null));
            assertSame(Boolean.TRUE, poll(true, modern, scheduled, !scheduled, null));
            assertEquals(old.trace, modern.trace);
            assertEquals(Arrays.asList("begin", "accounts", "write:poll", "commit", "end",
                    "schedule:true", "eval:poll"), modern.trace);
            assertEquals(Collections.singletonList("poll"), modern.committed);
        }
    }

    @Test
    public void pollNoEligibleAccountStillCommitsFalseAndSchedulesAfterEnd() throws Exception {
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            assertSame(Boolean.FALSE, poll(modern, room, false, false, null));
            assertEquals(Arrays.asList("begin", "accounts", "commit", "end",
                    "schedule:false", "eval:poll"), room.trace);
            assertTrue(room.committed.isEmpty());
        }
    }

    @Test
    public void staleNativeAndLegacyBranchesKeepWritesContentAndDiagnostics() throws Exception {
        for (boolean nativeRest : new boolean[]{false, true})
            for (boolean queued : new boolean[]{false, true}) {
                TransactionCallableRoomModel old = new TransactionCallableRoomModel();
                TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
                Content oldContent = new Content();
                Content newContent = new Content();
                Boolean before = stale(false, old, oldContent, nativeRest, queued, null);
                Boolean after = stale(true, modern, newContent, nativeRest, queued, null);
                assertSame(nativeRest && queued ? Boolean.TRUE : Boolean.FALSE, after);
                assertSame(before, after);
                assertEquals(old.trace, modern.trace);
                assertEquals(old.committed, modern.committed);
                assertFalse(oldContent.value);
                assertFalse(newContent.value);
                assertEquals(nativeRest ? queued ? Arrays.asList("reset", "native:BODY") :
                        Collections.singletonList("reset") : Arrays.asList("reset", "BODY"), modern.committed);
                if (nativeRest) {
                    assertTrue(modern.trace.indexOf("end") < modern.trace.indexOf("diagnostic:" + queued));
                    assertTrue(modern.trace.indexOf("diagnostic:" + queued) < modern.trace.indexOf("eval:native"));
                } else
                    assertFalse(modern.trace.contains("eval:native"));
            }
    }

    @Test
    public void staleResetAndNontransactionalMutationPrecedeQueue() throws Exception {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        stale(true, room, new Content(), true, true, null);
        assertEquals(Arrays.asList("begin", "write:reset", "content:false", "native-queue",
                "write:native:BODY", "commit", "end", "diagnostic:true", "eval:native"), room.trace);
    }

    @Test
    public void checkedRuntimeAndErrorBodyFailuresRetainIdentityAndRollback() {
        for (Throwable expected : new Throwable[]{new IOException("checked"),
                new IllegalStateException("runtime"), new AssertionError("error")})
            for (boolean staleShape : new boolean[]{false, true})
                for (boolean modern : new boolean[]{false, true}) {
                    TransactionCallableRoomModel room = new TransactionCallableRoomModel();
                    Content content = new Content();
                    Throwable actual = failure(() -> {
                        if (staleShape)
                            stale(modern, room, content, true, true, expected);
                        else
                            poll(modern, room, true, false, expected);
                    });
                    assertSame(expected, actual);
                    assertTrue(room.committed.isEmpty());
                    assertEquals("end", room.trace.get(room.trace.size() - 1));
                    assertFalse(room.trace.contains("commit"));
                    if (staleShape)
                        assertFalse(content.value);
                }
    }

    private static void configure(TransactionCallableRoomModel room, int stage,
                                  Throwable original, boolean mask, Throwable cleanup) {
        if (stage == 0) room.beginFailure = original;
        if (stage == 2) room.commitFailure = original;
        if (stage == 3) room.endFailure = original;
        if (mask) room.endFailure = cleanup;
    }

    @Test
    public void beginBodyMarkerAndEndFailuresMatchBothRecipesIncludingCleanupMasking() {
        for (boolean staleShape : new boolean[]{false, true})
            for (int stage = 0; stage < 4; stage++)
                for (boolean mask : new boolean[]{false, true}) {
                    final int failureStage = stage;
                    Throwable original = stage == 1 ? new IOException("body") :
                            stage == 3 ? new AssertionError("end") : new IllegalStateException("stage:" + stage);
                    Throwable cleanup = new IllegalArgumentException("cleanup");
                    TransactionCallableRoomModel old = new TransactionCallableRoomModel();
                    TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
                    Content oldContent = new Content();
                    Content newContent = new Content();
                    configure(old, stage, original, mask, cleanup);
                    configure(modern, stage, original, mask, cleanup);
                    Throwable before = failure(() -> {
                        if (staleShape) stale(false, old, oldContent, true, true,
                                failureStage == 1 ? original : null);
                        else poll(false, old, true, false, failureStage == 1 ? original : null);
                    });
                    Throwable after = failure(() -> {
                        if (staleShape) stale(true, modern, newContent, true, true,
                                failureStage == 1 ? original : null);
                        else poll(true, modern, true, false, failureStage == 1 ? original : null);
                    });
                    assertSame(mask ? cleanup : original, before);
                    assertSame(before, after);
                    assertEquals(old.trace, modern.trace);
                    assertEquals(old.committed, modern.committed);
                    assertEquals(oldContent.value, newContent.value);
                    assertEquals(1, Collections.frequency(modern.trace, "end"));
                    assertEquals("end", modern.trace.get(modern.trace.size() - 1));
                    if (stage < 3)
                        assertTrue(modern.committed.isEmpty());
                    else
                        assertFalse(modern.committed.isEmpty());
                }
    }

    @Test
    public void beginFailureStillAttemptsCleanupBeforeAnyMutation() {
        for (boolean modern : new boolean[]{false, true}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            Content content = new Content();
            RuntimeException expected = new IllegalStateException("begin");
            room.beginFailure = expected;
            assertSame(expected, failure(() -> stale(modern, room, content, true, true, null)));
            assertEquals(Arrays.asList("begin", "end"), room.trace);
            assertTrue(content.value);
        }
    }
}

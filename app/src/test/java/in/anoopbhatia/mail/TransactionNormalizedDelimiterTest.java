package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

import static org.junit.Assert.*;

/**
 * Host recipe/lifecycle parity, complemented by the exact production-byte Python
 * gate. Not live SQLite/OEM validation or a substitute for that source proof.
 */
public class TransactionNormalizedDelimiterTest {
    private static final class Frame {
        final List<String> writes = new ArrayList<>();
        boolean success;
    }

    private static final class Room {
        final List<String> trace = new ArrayList<>();
        final List<String> committed = new ArrayList<>();
        final Deque<Frame> frames = new ArrayDeque<>();
        RuntimeException beginFailure, markerFailure, endFailure;
        boolean knownEnd;

        void begin() {
            trace.add("begin");
            if (beginFailure != null)
                throw beginFailure;
            frames.push(new Frame());
        }

        void write(String value) {
            assertFalse("write outside transaction", frames.isEmpty());
            trace.add(value);
            frames.peek().writes.add(value);
        }

        void marker() {
            trace.add("marker");
            if (markerFailure != null)
                throw markerFailure;
            frames.peek().success = true;
        }

        void end() {
            trace.add("end");
            if (!frames.isEmpty()) {
                Frame frame = frames.pop();
                if (frame.success) {
                    if (frames.isEmpty()) committed.addAll(frame.writes);
                    else frames.peek().writes.addAll(frame.writes);
                }
            }
            if (endFailure != null) {
                String message = endFailure.getMessage();
                if (knownEnd && message != null && (message.contains("no current transaction") ||
                        message.contains("no transaction is active")))
                    trace.add("logged-end");
                else throw endFailure;
            }
        }

        void runner(Runnable body) {
            begin();
            try {
                body.run();
                marker();
            } finally {
                end();
            }
        }
    }

    private static void invoke(boolean modern, Room room, Runnable body) {
        if (modern) {
            TransactionScope.run(room::runner, room::end, body);
        } else {
            try {
                room.begin();
                body.run();
                room.marker();
            } finally {
                room.end();
            }
        }
    }

    private static Throwable failure(Runnable action) {
        try { action.run(); return null; }
        catch (Throwable ex) { return ex; }
    }

    private static void expected(Room room, String... writes) {
        List<String> trace = new ArrayList<>();
        trace.add("begin");
        trace.addAll(Arrays.asList(writes));
        trace.add("marker");
        trace.add("end");
        assertEquals(trace, room.trace);
        assertEquals(Arrays.asList(writes), room.committed);
        assertTrue(room.frames.isEmpty());
    }

    @Test
    public void contactCreateAndConditionalNameUpdateKeepNormalizedReference() {
        for (boolean modern : new boolean[]{false, true})
            for (boolean create : new boolean[]{false, true})
                for (String input : new String[]{null, "", new String("Alice")}) {
                    String name = input;
                    if (name != null && name.isEmpty()) name = null;
                    final String contactName = name;
                    Room room = new Room();
                    String[] loadedName = {null};
                    invoke(modern, room, () -> {
                        if (create || (loadedName[0] == null && contactName != null))
                            loadedName[0] = contactName;
                        room.write(create ? "contact:insert" : "contact:update");
                        room.write(create ? "log:insert" : "log:update");
                    });
                    assertSame(name, loadedName[0]);
                    expected(room, create ? "contact:insert" : "contact:update", create ? "log:insert" : "log:update");
                }
    }

    @Test
    public void answerResetsStayBeforeSaveAndSnapshotsRetainNullableReferences() {
        for (boolean modern : new boolean[]{false, true})
            for (boolean insert : new boolean[]{false, true}) {
                String label = new String("label"), group = null;
                Integer color = null;
                final String savedLabel = label, savedGroup = group;
                final Integer savedColor = color;
                Object[] saved = new Object[3];
                Room room = new Room();
                invoke(modern, room, () -> {
                    room.write("reset:standard");
                    room.write("reset:receipt");
                    saved[0] = savedLabel; saved[1] = savedGroup; saved[2] = savedColor;
                    room.write(insert ? "answer:insert" : "answer:update");
                });
                assertSame(label, saved[0]); assertSame(group, saved[1]); assertSame(color, saved[2]);
                expected(room, "reset:standard", "reset:receipt", insert ? "answer:insert" : "answer:update");
            }
    }

    @Test
    public void contactMergeDeletesDuplicateBeforeUpdateAndNewContactInserts() {
        for (boolean modern : new boolean[]{false, true})
            for (boolean loaded : new boolean[]{false, true})
                for (boolean existing : new boolean[]{false, true}) {
                    Room room = new Room();
                    invoke(modern, room, () -> {
                        if (loaded && existing) room.write("duplicate:delete");
                        room.write(loaded || existing ? "contact:update" : "contact:insert");
                    });
                    if (loaded && existing) expected(room, "duplicate:delete", "contact:update");
                    else expected(room, loaded || existing ? "contact:update" : "contact:insert");
                }
    }

    @Test
    public void folderColorUsesSameNullableObjectForChildrenAndSingleFolder() {
        for (boolean modern : new boolean[]{false, true})
            for (Integer color : new Integer[]{null, 123456})
                for (boolean children : new boolean[]{false, true}) {
                    final Integer selectedColor = color;
                    List<Integer> written = new ArrayList<>();
                    Room room = new Room();
                    invoke(modern, room, () -> {
                        if (children) {
                            for (long id : new long[]{11, 12}) {
                                written.add(selectedColor); room.write("folder:" + id);
                            }
                        } else {
                            written.add(selectedColor); room.write("folder:10");
                        }
                    });
                    for (Integer value : written) assertSame(color, value);
                    if (children) expected(room, "folder:11", "folder:12");
                    else expected(room, "folder:10");
                }
    }

    @Test
    public void flagSkipsMissingIncapableAndUnchangedMessagesThenEvaluatesAfterEnd() {
        for (boolean modern : new boolean[]{false, true})
            for (boolean missing : new boolean[]{false, true})
                for (boolean capable : new boolean[]{false, true}) {
                    final Integer selectedColor = null;
                    final boolean requestedFlag = true, loadedFlag = false;
                    Room room = new Room();
                    invoke(modern, room, () -> {
                        if (!missing && capable && (loadedFlag != requestedFlag || !Objects.equals(null, selectedColor)))
                            room.write("flag:true:null");
                    });
                    if (!missing && capable) expected(room, "flag:true:null");
                    else expected(room);
                    room.trace.add("eval:flag");
                    assertEquals("end", room.trace.get(room.trace.size() - 2));
                    assertEquals("eval:flag", room.trace.get(room.trace.size() - 1));
                }
        for (boolean modern : new boolean[]{false, true}) {
            Room room = new Room();
            final Integer selectedColor = null;
            final boolean requestedFlag = true, loadedFlag = true;
            invoke(modern, room, () -> {
                if (loadedFlag != requestedFlag || !Objects.equals(null, selectedColor)) room.write("unexpected");
            });
            expected(room);
        }
    }

    @Test
    public void importanceWritesThenQueuesBothKeywordBooleansEvenForNull() {
        for (boolean modern : new boolean[]{false, true})
            for (Integer importance : new Integer[]{null, 0, 2}) {
                final Integer selectedImportance = importance;
                Room room = new Room();
                Integer[] value = new Integer[1];
                invoke(modern, room, () -> {
                    value[0] = selectedImportance;
                    room.write("importance:" + selectedImportance);
                    room.write("low:" + Integer.valueOf(0).equals(selectedImportance));
                    room.write("high:" + Integer.valueOf(2).equals(selectedImportance));
                });
                assertSame(importance, value[0]);
                expected(room, "importance:" + importance, "low:" + Integer.valueOf(0).equals(importance),
                        "high:" + Integer.valueOf(2).equals(importance));
            }
    }

    private static void snooze(Room room, Long wakeup, boolean main, boolean sameFolder, boolean flagSnoozed) {
        room.write("unsnoozed:false");
        room.write("ignored:true");
        if (main) {
            room.write("store:" + wakeup);
            room.write("alarm:" + wakeup);
            if (wakeup != null) room.write("seen:true");
        } else {
            room.write("store:" + (wakeup == null ? null : Long.MAX_VALUE));
            room.write("alarm:null");
        }
        if (flagSnoozed && sameFolder) room.write("flag:" + (wakeup != null));
    }

    @Test
    public void mainSnoozeNullSkipsSeenButStillQueuesFalseFlagWhenEnabled() {
        for (boolean modern : new boolean[]{false, true}) {
            Room room = new Room();
            final Long selectedWakeup = null;
            invoke(modern, room, () -> snooze(room, selectedWakeup, true, true, true));
            expected(room, "unsnoozed:false", "ignored:true", "store:null", "alarm:null", "flag:false");
        }
    }

    @Test
    public void mainSnoozeNonNullPreservesAlarmSeenThenFlagOrder() {
        for (boolean modern : new boolean[]{false, true}) {
            Room room = new Room();
            Long wakeup = Long.valueOf(1234567);
            final Long selectedWakeup = wakeup;
            Long[] observed = new Long[1];
            invoke(modern, room, () -> {
                observed[0] = selectedWakeup;
                snooze(room, selectedWakeup, true, true, true);
            });
            assertSame(wakeup, observed[0]);
            expected(room, "unsnoozed:false", "ignored:true", "store:1234567", "alarm:1234567", "seen:true", "flag:true");
        }
    }

    @Test
    public void siblingSnoozeUsesNullOrMaxCancelsAlarmAndGatesFlagByFolder() {
        for (boolean modern : new boolean[]{false, true})
            for (Long wakeup : new Long[]{null, 1234567L})
                for (boolean flagSnoozed : new boolean[]{false, true})
                    for (boolean sameFolder : new boolean[]{false, true}) {
                        final Long selectedWakeup = wakeup;
                        Room room = new Room();
                        invoke(modern, room, () -> snooze(room, selectedWakeup, false, sameFolder, flagSnoozed));
                        List<String> operations = new ArrayList<>(Arrays.asList("unsnoozed:false", "ignored:true",
                                "store:" + (wakeup == null ? null : Long.MAX_VALUE), "alarm:null"));
                        if (flagSnoozed && sameFolder) operations.add("flag:" + (wakeup != null));
                        expected(room, operations.toArray(new String[0]));
                    }
    }

    @Test
    public void beginBodyMarkerAndEndFailuresKeepIdentityRollbackAndSkipFollowup() {
        RuntimeException begin = new IllegalStateException("begin"), body = new IllegalStateException("body"),
                marker = new IllegalStateException("marker"), end = new IllegalStateException("end");
        for (boolean modern : new boolean[]{false, true})
            for (int phase = 0; phase < 4; phase++) {
                Room room = new Room();
                if (phase == 0) room.beginFailure = begin;
                if (phase == 2) room.markerFailure = marker;
                if (phase == 3) room.endFailure = end;
                final int failing = phase;
                Throwable actual = failure(() -> {
                    invoke(modern, room, () -> {
                        room.write("mutation");
                        if (failing == 1) throw body;
                    });
                    room.trace.add("eval:flag");
                });
                assertSame(phase == 0 ? begin : phase == 1 ? body : phase == 2 ? marker : end, actual);
                assertFalse(room.trace.contains("eval:flag"));
                assertEquals("end", room.trace.get(room.trace.size() - 1));
                assertEquals(phase == 3 ? Arrays.asList("mutation") : new ArrayList<>(), room.committed);
                assertTrue(room.frames.isEmpty());
            }
    }

    @Test
    public void unknownEndMasksBeginOrBodyFailureWithoutWrappingOrSuppression() {
        for (boolean modern : new boolean[]{false, true})
            for (boolean failingBegin : new boolean[]{false, true}) {
                Room room = new Room();
                RuntimeException original = new IllegalStateException("original"), end = new IllegalStateException("end");
                if (failingBegin) room.beginFailure = original;
                room.endFailure = end;
                assertSame(end, failure(() -> invoke(modern, room, () -> { throw original; })));
                assertEquals(0, end.getSuppressed().length);
                assertEquals(Arrays.asList("begin", "end"), room.trace);
            }
    }

    @Test
    public void successfulNestedScopesAndKnownDeviceEndHandlingStayOwnedByRunner() {
        for (boolean modern : new boolean[]{false, true}) {
            Room room = new Room();
            invoke(modern, room, () -> invoke(modern, room, () -> room.write("inner")));
            assertEquals(Arrays.asList("begin", "begin", "inner", "marker", "end", "marker", "end"), room.trace);
            assertEquals(Arrays.asList("inner"), room.committed);
            for (String message : new String[]{"no current transaction", "cannot rollback - no transaction is active"}) {
                Room known = new Room();
                known.knownEnd = true;
                known.endFailure = new IllegalStateException(message);
                invoke(modern, known, () -> known.write("mutation"));
                assertEquals(Arrays.asList("begin", "mutation", "marker", "end", "logged-end"), known.trace);
                assertEquals(Arrays.asList("mutation"), known.committed);
            }
        }
    }
}

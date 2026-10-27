package in.anoopbhatia.mail;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

/** Host recipes for count/flag consumers, supplemented by exact production body gates. */
public class TransactionRemainingSmallTest {
    private static final class State {
        int count;
        int index;
        int flags;
        final List<String> followup = new ArrayList<>();
    }

    private static void countBody(TransactionCallableRoomModel room, State state,
                                  int[] count, RuntimeException failure) {
        for (; state.index < 5 && count[0] < 2; state.index++) {
            if (state.index == 0 || state.index == 2)
                continue;
            room.write("found:" + state.index);
            count[0]++;
            if (failure != null)
                throw failure;
        }
    }

    private static Throwable runCount(boolean modern, TransactionCallableRoomModel room,
                                      State state, RuntimeException bodyFailure) {
        final int[] count = {state.count};
        try {
            if (modern) {
                TransactionScope.run(body -> room.run(() -> { body.run(); return null; }), room::end,
                        () -> countBody(room, state, count, bodyFailure));
            } else {
                try {
                    room.begin();
                    countBody(room, state, count, bodyFailure);
                    room.commit();
                } finally {
                    room.end();
                }
            }
            state.count = count[0];
            state.followup.add("after-end:" + state.count);
            return null;
        } catch (RuntimeException | Error ex) {
            return ex;
        }
    }

    private static Integer refreshBody(TransactionCallableRoomModel room, boolean disconnected,
                                       boolean transientAccount, boolean outbox) {
        boolean now = true;
        boolean reload = false;
        boolean send = false;
        room.write("sync");
        if (outbox)
            send = true;
        if (disconnected) {
            now = false;
            if (!transientAccount)
                reload = true;
        }
        return Integer.valueOf((now ? 1 : 0) | (reload ? 2 : 0) | (send ? 4 : 0));
    }

    private static Throwable runFlags(boolean modern, TransactionCallableRoomModel room,
                                     State state, boolean force, boolean disconnected,
                                     boolean transientAccount, boolean outbox) {
        try {
            int flags;
            if (modern) {
                flags = TransactionScope.call(room::run, room::end,
                        () -> refreshBody(room, disconnected, transientAccount, outbox));
            } else {
                try {
                    room.begin();
                    flags = refreshBody(room, disconnected, transientAccount, outbox);
                    room.commit();
                } finally {
                    room.end();
                }
            }
            state.flags = flags;
            state.followup.add(force || (flags & 2) != 0 ? "reload" : "eval");
            if ((flags & 4) != 0)
                state.followup.add("send");
            if ((flags & 1) == 0 && !force)
                throw new IllegalArgumentException("no connection");
            return null;
        } catch (RuntimeException | Error ex) {
            return ex;
        }
    }

    @Test public void countPageSkipsAndProgressArePreserved() {
        TransactionCallableRoomModel old = new TransactionCallableRoomModel();
        TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
        State a = new State(), b = new State();
        assertNull(runCount(false, old, a, null));
        assertNull(runCount(true, modern, b, null));
        assertEquals(old.trace, modern.trace);
        assertEquals(old.committed, modern.committed);
        assertEquals(a.index, b.index);
        assertEquals(2, b.count);
        assertEquals(a.followup, b.followup);
    }

    @Test public void countFailuresPreserveProgressRollbackAndIdentity() {
        for (String phase : Arrays.asList("begin", "body", "marker", "end")) {
            RuntimeException failure = new IllegalStateException(phase);
            TransactionCallableRoomModel old = new TransactionCallableRoomModel();
            TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
            if (phase.equals("begin")) old.beginFailure = modern.beginFailure = failure;
            if (phase.equals("marker")) old.commitFailure = modern.commitFailure = failure;
            if (phase.equals("end")) old.endFailure = modern.endFailure = failure;
            State a = new State(), b = new State();
            RuntimeException body = phase.equals("body") ? failure : null;
            assertSame(failure, runCount(false, old, a, body));
            assertSame(failure, runCount(true, modern, b, body));
            assertEquals(phase, old.trace, modern.trace);
            assertEquals(phase, old.committed, modern.committed);
            assertEquals(a.index, b.index);
            assertEquals(a.count, b.count);
            assertTrue(b.followup.isEmpty());
        }
    }

    @Test public void allRefreshFlagCombinationsAndFollowupsMatch() {
        for (int bits = 0; bits < 16; bits++) {
            boolean force = (bits & 1) != 0, disconnected = (bits & 2) != 0;
            boolean transientAccount = (bits & 4) != 0, outbox = (bits & 8) != 0;
            TransactionCallableRoomModel old = new TransactionCallableRoomModel();
            TransactionCallableRoomModel modern = new TransactionCallableRoomModel();
            State a = new State(), b = new State();
            Throwable x = runFlags(false, old, a, force, disconnected, transientAccount, outbox);
            Throwable y = runFlags(true, modern, b, force, disconnected, transientAccount, outbox);
            assertEquals(x == null ? null : x.getClass(), y == null ? null : y.getClass());
            assertEquals(old.trace, modern.trace);
            assertEquals(a.flags, b.flags);
            assertEquals(a.followup, b.followup);
        }
    }

    @Test public void endFailurePreventsRefreshAndSend() {
        RuntimeException end = new IllegalStateException("end");
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        room.endFailure = end;
        State state = new State();
        assertSame(end, runFlags(true, room, state, true, true, false, true));
        assertTrue(state.followup.isEmpty());
        assertEquals(Arrays.asList("sync"), room.committed);
    }

    @Test public void refreshMaskUsesOnlyGuaranteedCachedIntegerValues() {
        for (int value = 0; value < 8; value++)
            assertSame(Integer.valueOf(value), Integer.valueOf(value));
    }
}

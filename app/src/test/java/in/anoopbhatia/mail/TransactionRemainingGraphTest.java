package in.anoopbhatia.mail;

import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Host boundary recipes; the production gate separately proves exact provider-body preservation. */
public class TransactionRemainingGraphTest {
    private static final Object INITIAL = new Object();
    private static final Object CURRENT = new Object();
    private static final Object SURVIVOR = new Object();
    private static final class State {
        Object mutated;
        final List<String> followups = new ArrayList<>();
    }
    private static Object legacy(TransactionCallableRoomModel room, State state, int branch,
                                 IOException bodyFailure, boolean relocated) throws IOException {
        Object cached = INITIAL;
        room.begin();
        try {
            if (branch == 0) return null;
            cached = CURRENT;
            if (branch == 1) return null;
            if (branch == 2) return null;
            if (branch == 3) cached = SURVIVOR;
            room.write("relocate");
            state.mutated = cached;
            if (bodyFailure != null) throw bodyFailure;
            room.commit();
        } finally {
            room.end();
        }
        if (relocated) {
            state.followups.add("refresh-source");
            state.followups.add("refresh-actual");
            state.followups.add("log");
        }
        return cached;
    }
    private static Object modern(TransactionCallableRoomModel room, State state, int branch,
                                 IOException bodyFailure, boolean relocated) throws IOException {
        final Object initialCached = INITIAL;
        Object cached = TransactionCompletion.call(room::run, null, () -> {
            Object working = initialCached;
            if (branch == 0) return TransactionResult.rollback(null);
            working = CURRENT;
            if (branch == 1) return TransactionResult.rollback(null);
            if (branch == 2) return TransactionResult.rollback(null);
            if (branch == 3) working = SURVIVOR;
            room.write("relocate");
            state.mutated = working;
            if (bodyFailure != null) throw bodyFailure;
            return TransactionResult.commit(working);
        });
        if (cached == null) return null;
        if (relocated) {
            state.followups.add("refresh-source");
            state.followups.add("refresh-actual");
            state.followups.add("log");
        }
        return cached;
    }
    @Test public void allThreeEarlyReturnsRollbackWithoutFollowup() throws IOException {
        for (int branch = 0; branch < 3; branch++) {
            TransactionCallableRoomModel old = new TransactionCallableRoomModel(), next = new TransactionCallableRoomModel();
            State a = new State(), b = new State();
            assertNull(legacy(old, a, branch, null, true));
            assertNull(modern(next, b, branch, null, true));
            assertEquals(old.trace, next.trace);
            assertEquals(Arrays.asList("begin", "end"), next.trace);
            assertTrue(next.committed.isEmpty());
            assertTrue(b.followups.isEmpty());
        }
    }
    @Test public void originalCurrentAndSurvivorReferencesAndFollowupMatch() throws IOException {
        for (int branch : new int[]{3, 4}) {
            for (boolean relocated : new boolean[]{false, true}) {
                TransactionCallableRoomModel old = new TransactionCallableRoomModel(), next = new TransactionCallableRoomModel();
                State a = new State(), b = new State();
                assertSame(legacy(old, a, branch, null, relocated), modern(next, b, branch, null, relocated));
                assertSame(a.mutated, b.mutated);
                assertEquals(old.trace, next.trace);
                assertEquals(old.committed, next.committed);
                assertEquals(a.followups, b.followups);
            }
        }
    }
    @Test public void bodyExceptionIdentityAndReferenceMutationSurviveRollback() throws IOException {
        IOException failure = new IOException("duplicate pending work");
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        State state = new State();
        try { modern(room, state, 3, failure, true); fail(); }
        catch (IOException actual) { assertSame(failure, actual); }
        assertSame(SURVIVOR, state.mutated);
        assertTrue(room.committed.isEmpty());
        assertTrue(state.followups.isEmpty());
    }
    @Test public void beginFailureHasNoCleanupOrCallback() throws IOException {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        RuntimeException failure = new IllegalStateException("begin");
        room.beginFailure = failure;
        State state = new State();
        try { modern(room, state, 4, null, true); fail(); }
        catch (RuntimeException actual) { assertSame(failure, actual); }
        assertEquals(Arrays.asList("begin"), room.trace);
        assertNull(state.mutated);
    }
    @Test public void markerAndEndFailureSkipAllFollowup() throws IOException {
        for (boolean marker : new boolean[]{false, true}) {
            TransactionCallableRoomModel room = new TransactionCallableRoomModel();
            RuntimeException failure = new IllegalStateException(marker ? "marker" : "end");
            if (marker) room.commitFailure = failure; else room.endFailure = failure;
            State state = new State();
            try { modern(room, state, 3, null, true); fail(); }
            catch (RuntimeException actual) { assertSame(failure, actual); }
            assertTrue(state.followups.isEmpty());
            assertEquals(marker, room.committed.isEmpty());
        }
    }
    @Test public void endFailureReplacesRollbackSignal() throws IOException {
        TransactionCallableRoomModel room = new TransactionCallableRoomModel();
        Error failure = new AssertionError("end");
        room.endFailure = failure;
        try { modern(room, new State(), 0, null, true); fail(); }
        catch (Error actual) { assertSame(failure, actual); }
        assertEquals(Arrays.asList("begin", "end"), room.trace);
    }
}

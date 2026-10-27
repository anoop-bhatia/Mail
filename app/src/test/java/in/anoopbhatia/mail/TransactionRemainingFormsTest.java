package in.anoopbhatia.mail;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import org.junit.Test;

/** Small legacy/manual versus TransactionCompletion recipes at the Room boundary. */
public class TransactionRemainingFormsTest {
    private static final Object ACCOUNT = new Object();
    private static final Object IDENTITY = new Object();
    private static final Object UPDATE = new Object();

    private static final class Decision<T> {
        final boolean commit; final T value;
        Decision(boolean commit, T value) { this.commit = commit; this.value = value; }
    }

    private static final class State {
        Object account;
        Object identity;
        Object update;
        final List<String> followups = new ArrayList<>();
        boolean evaluated;
        boolean mutated;
    }

    private static <T, E extends Exception> T legacy(TransactionCallableRoomModel room,
                                                       Runnable end,
                                                       TransactionCallable<T, E> body)
            throws E {
        room.begin();
        try {
            T value = body.call();
            room.commit();
            return value;
        } finally {
            end.run();
        }
    }

    private static <T, E extends Exception> T legacyBeginInsideTry(TransactionCallableRoomModel room,
                                                                       TransactionCallable<T, E> body)
            throws E {
        try {
            room.begin();
            T value = body.call();
            room.commit();
            return value;
        } finally {
            room.end();
        }
    }

    private static <T, E extends Exception> T legacyDecision(TransactionCallableRoomModel room,
                                                               TransactionCallable<Decision<T>, E> body)
            throws E {
        room.begin();
        try {
            Decision<T> decision = body.call();
            if (!decision.commit) return decision.value;
            room.commit();
            return decision.value;
        } finally {
            room.end();
        }
    }

    private static <T, E extends Exception> T modern(TransactionCallableRoomModel room,
                                                       Runnable beginCleanup,
                                                       TransactionCallable<TransactionResult<T>, E> body)
            throws E {
        return TransactionCompletion.call(room::run, beginCleanup, body);
    }

    private static void sameRoom(TransactionCallableRoomModel oldRoom,
                                 TransactionCallableRoomModel newRoom) {
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(oldRoom.committed, newRoom.committed);
    }

    @Test public void finalNormalizedInputSnapshotKeepsReferences() throws IOException {
        State a = new State(), b = new State();
        Object[] oldSnapshot = {ACCOUNT, IDENTITY, UPDATE};
        Object[] newSnapshot = {ACCOUNT, IDENTITY, UPDATE};
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        Object oldValue = legacy(oldRoom, oldRoom::end, () -> {
            a.account = oldSnapshot[0]; a.identity = oldSnapshot[1]; a.update = oldSnapshot[2];
            return oldSnapshot;
        });
        Object newValue = modern(newRoom, newRoom::end, () -> {
            b.account = newSnapshot[0]; b.identity = newSnapshot[1]; b.update = newSnapshot[2];
            return TransactionResult.commit(newSnapshot);
        });
        assertSame(oldValue, oldSnapshot); assertSame(newValue, newSnapshot);
        assertSame(a.account, b.account); assertSame(a.identity, b.identity); assertSame(a.update, b.update);
        sameRoom(oldRoom, newRoom);
    }

    @Test public void nullableAccountIdentityUpdateReplacementAfterEndMatches() throws IOException {
        State a = new State(), b = new State();
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        Object oldValue = legacy(oldRoom, oldRoom::end, () -> {
            a.account = ACCOUNT; a.identity = null; a.update = UPDATE; return a.account;
        });
        Object newValue = modern(newRoom, newRoom::end, () -> {
            b.account = ACCOUNT; b.identity = null; b.update = UPDATE; return TransactionResult.commit(b.account);
        });
        assertSame(oldValue, newValue); assertSame(a.account, b.account); assertNull(b.identity); assertSame(a.update, b.update);
        sameRoom(oldRoom, newRoom);
    }

    @Test public void originalObjectMutationSurvivesRollback() throws IOException {
        State a = new State(), b = new State();
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        Object oldValue = legacyDecision(oldRoom, () -> { a.mutated = true; return new Decision<>(false, null); });
        Object newValue = modern(newRoom, newRoom::end, () -> { b.mutated = true; return TransactionResult.rollback(null); });
        assertNull(oldValue); assertNull(newValue); assertTrue(a.mutated == b.mutated);
        sameRoom(oldRoom, newRoom); assertTrue(newRoom.committed.isEmpty());
    }

    @Test public void beginInsideTryCleanupAndEndFailurePrecedenceMatch() throws IOException {
        RuntimeException begin = new IllegalStateException("begin");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.beginFailure = begin; newRoom.beginFailure = begin;
        try { legacyBeginInsideTry(oldRoom, () -> ACCOUNT); fail(); } catch (RuntimeException e) { assertSame(begin, e); }
        try { modern(newRoom, newRoom::end, () -> TransactionResult.commit(ACCOUNT)); fail(); } catch (RuntimeException e) { assertSame(begin, e); }
        sameRoom(oldRoom, newRoom); assertEquals(Arrays.asList("begin", "end"), newRoom.trace);

        Error oldEnd = new AssertionError("end"), newEnd = new AssertionError("end");
        oldRoom = new TransactionCallableRoomModel(); newRoom = new TransactionCallableRoomModel();
        oldRoom.endFailure = oldEnd; newRoom.endFailure = newEnd;
        try { legacyDecision(oldRoom, () -> new Decision<>(false, ACCOUNT)); fail(); } catch (Error e) { assertSame(oldEnd, e); }
        try { modern(newRoom, newRoom::end, () -> TransactionResult.rollback(ACCOUNT)); fail(); } catch (Error e) { assertSame(newEnd, e); }
        sameRoom(oldRoom, newRoom);
    }

    @Test public void markerFailurePreventsFollowup() throws IOException {
        RuntimeException oldFailure = new IllegalStateException("marker");
        RuntimeException newFailure = new IllegalStateException("marker");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.commitFailure = oldFailure; newRoom.commitFailure = newFailure;
        State a = new State(), b = new State();
        try { legacy(oldRoom, oldRoom::end, () -> ACCOUNT); a.followups.add("never"); fail(); }
        catch (RuntimeException e) { assertSame(oldFailure, e); }
        try { modern(newRoom, newRoom::end, () -> TransactionResult.commit(ACCOUNT)); b.followups.add("never"); fail(); }
        catch (RuntimeException e) { assertSame(newFailure, e); }
        assertEquals(a.followups, b.followups); assertTrue(b.followups.isEmpty()); sameRoom(oldRoom, newRoom);
    }

    @Test public void folderEarlyDecisionsTrueFalseRollbackAndCommitMasksMatch() throws IOException {
        for (int decision = 0; decision < 4; decision++) {
            final int choice = decision;
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            State a = new State(), b = new State();
            boolean commit = decision >= 2;
            Boolean oldValue = legacyDecision(oldRoom, () -> {
                if (choice == 0) return new Decision<>(false, Boolean.FALSE);
                if (choice == 1) return new Decision<>(false, Boolean.TRUE);
                return new Decision<>(true, choice == 2 ? Boolean.FALSE : Boolean.TRUE);
            });
            Integer encoded = modern(newRoom, newRoom::end, () -> {
                if (choice < 2) return TransactionResult.rollback(Integer.valueOf(choice));
                boolean reload = choice == 3;
                return TransactionResult.commit(Integer.valueOf(reload ? 3 : 2));
            });
            assertSame(Integer.valueOf(choice), encoded);
            Boolean newValue = (encoded & 1) != 0;
            if (encoded >= 2) { a.followups.add("commit"); b.followups.add("commit"); }
            assertEquals(oldValue, newValue); assertEquals(commit, newRoom.trace.contains("commit"));
            assertEquals(a.followups, b.followups); sameRoom(oldRoom, newRoom);
        }
    }

    @Test public void normalPostEndReloadAndEvaluationOnlyAfterCommit() throws IOException {
        for (boolean commit : new boolean[] {false, true}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            State a = new State(), b = new State();
            Boolean oldValue = legacyDecision(oldRoom, () -> new Decision<>(commit, commit ? Boolean.TRUE : Boolean.FALSE));
            Boolean newValue = modern(newRoom, newRoom::end, () -> commit ? TransactionResult.commit(Boolean.TRUE) : TransactionResult.rollback(Boolean.FALSE));
            if (commit) { a.followups.add("reload"); a.evaluated = true; b.followups.add("reload"); b.evaluated = true; }
            assertEquals(oldValue, newValue); assertEquals(a.followups, b.followups); assertEquals(a.evaluated, b.evaluated); sameRoom(oldRoom, newRoom);
        }
    }

    @Test public void checkedIOExceptionIdentityAndPostEndStateMatch() {
        IOException failure = new IOException("body");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel(), newRoom = new TransactionCallableRoomModel();
        State a = new State(), b = new State();
        try { legacy(oldRoom, oldRoom::end, () -> { a.mutated = true; throw failure; }); fail(); }
        catch (IOException e) { assertSame(failure, e); }
        try { modern(newRoom, newRoom::end, () -> { b.mutated = true; throw failure; }); fail(); }
        catch (IOException e) { assertSame(failure, e); }
        assertEquals(a.mutated, b.mutated); sameRoom(oldRoom, newRoom);
    }

    @Test public void nestedRollbackPoisonsRealOuterModel() throws IOException {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        Object oldValue = legacy(oldRoom, oldRoom::end, () -> { legacyDecision(oldRoom, () -> new Decision<>(false, ACCOUNT)); return ACCOUNT; });
        Object newValue = modern(newRoom, newRoom::end, () -> { modern(newRoom, newRoom::end, () -> TransactionResult.rollback(ACCOUNT)); return TransactionResult.commit(ACCOUNT); });
        assertSame(oldValue, newValue); sameRoom(oldRoom, newRoom); assertTrue(newRoom.committed.isEmpty());
        assertEquals(Arrays.asList("begin", "begin", "end", "commit", "end"), newRoom.trace);
    }
}

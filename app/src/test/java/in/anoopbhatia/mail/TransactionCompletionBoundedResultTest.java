package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.Callable;

import org.junit.Test;

/** Bounded parity traces for results crossing Room's transaction boundary. */
public class TransactionCompletionBoundedResultTest {
    private interface ThrowingCall<T> { T call() throws Exception; }

    private static <T> T legacyResult(TransactionCallableRoomModel room,
                                      boolean commit, Callable<T> body) throws Exception {
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

    private static <T> T migratedResult(TransactionCallableRoomModel room,
                                        boolean commit, Callable<T> body) throws Exception {
        return TransactionCompletion.call(room::run, room::end,
                () -> commit ? TransactionResult.commit(body.call())
                              : TransactionResult.rollback(body.call()));
    }

    private static Throwable capture(ThrowingCall<?> call) {
        try {
            call.call();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @Test
    public void externalResultAndCallbackLocalResultPreserveEarlyNullAndReference() throws Exception {
        Object value = new Object();
        for (boolean commit : new boolean[] {false, true}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            Object oldResult = legacyResult(oldRoom, commit, () -> commit ? value : null);
            Object newResult = migratedResult(newRoom, commit, () -> commit ? value : null);
            if (commit) {
                assertSame(value, oldResult);
                assertSame(value, newResult);
            } else {
                assertNull(oldResult);
                assertNull(newResult);
            }
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
        }
    }

    @Test
    public void resultWritesRollbackOrCommitWithTheSameLifecycleTrace() throws Exception {
        for (boolean commit : new boolean[] {false, true}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            Object marker = new Object();
            assertSame(commit ? marker : null, legacyResult(oldRoom, commit, () -> {
                oldRoom.write("entity");
                return commit ? marker : null;
            }));
            assertSame(commit ? marker : null, migratedResult(newRoom, commit, () -> {
                newRoom.write("entity");
                return commit ? marker : null;
            }));
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(commit ? Arrays.asList("entity") : Arrays.asList(), oldRoom.committed);
        }
    }

    @Test
    public void nullableEntityGatesFollowupOnlyAfterCleanup() throws Exception {
        for (boolean present : new boolean[] {false, true}) {
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            Object entity = present ? new Object() : null;
            String oldResult = legacyEntity(oldRoom, entity);
            String newResult = migratedEntity(newRoom, entity);
            if (present) {
                assertEquals("followup", oldResult);
                assertEquals("followup", newResult);
            } else {
                assertNull(oldResult);
                assertNull(newResult);
            }
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(oldRoom.committed, newRoom.committed);
        }
    }

    private static String legacyEntity(TransactionCallableRoomModel room, Object entity)
            throws Exception {
        Object result;
        try {
            room.begin();
            result = entity;
            if (result == null)
                return null;
            room.write("entity");
            room.commit();
        } finally {
            room.end();
        }
        room.trace.add("followup");
        return "followup";
    }

    private static String migratedEntity(TransactionCallableRoomModel room, Object entity)
            throws Exception {
        Object result = TransactionCompletion.call(room::run, room::end, () -> {
            if (entity == null)
                return TransactionResult.rollback(null);
            room.write("entity");
            return TransactionResult.commit(entity);
        });
        if (result == null)
            return null;
        room.trace.add("followup");
        return "followup";
    }

    @Test
    public void primitivePostSuccessReturnMatchesCommitTrueAndOrder() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        assertTrue(legacyFlag(oldRoom));
        assertTrue(TransactionCompletion.call(newRoom::run, newRoom::end, () -> {
            newRoom.write("flag");
            return TransactionResult.commit(true);
        }));
        assertEquals(Arrays.asList("begin", "write:flag", "commit", "end"), oldRoom.trace);
        assertEquals(oldRoom.trace, newRoom.trace);
        assertEquals(Arrays.asList("flag"), oldRoom.committed);
        assertEquals(oldRoom.committed, newRoom.committed);
    }

    private static boolean legacyFlag(TransactionCallableRoomModel room) throws Exception {
        try {
            room.begin();
            room.write("flag");
            room.commit();
            return true;
        } finally {
            room.end();
        }
    }

    @Test
    public void markerAndEndFailuresKeepIdentityForPrimitiveResultWithoutSuppression() {
        RuntimeException marker = new IllegalStateException("marker");
        Error end = new AssertionError("end");
        TransactionCallableRoomModel oldMarker = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newMarker = new TransactionCallableRoomModel();
        oldMarker.commitFailure = marker;
        newMarker.commitFailure = marker;
        assertSame(marker, capture(() -> legacyFlag(oldMarker)));
        assertSame(marker, capture(() -> TransactionCompletion.call(newMarker::run, newMarker::end,
                () -> {
                    newMarker.write("flag");
                    return TransactionResult.commit(true);
                })));

        TransactionCallableRoomModel oldEnd = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newEnd = new TransactionCallableRoomModel();
        oldEnd.endFailure = end;
        newEnd.endFailure = end;
        Throwable oldFailure = capture(() -> legacyFlag(oldEnd));
        Throwable newFailure = capture(() -> TransactionCompletion.call(newEnd::run, newEnd::end,
                () -> {
                    newEnd.write("flag");
                    return TransactionResult.commit(true);
                }));
        assertSame(end, oldFailure);
        assertSame(end, newFailure);
        assertEquals(0, oldFailure.getSuppressed().length);
        assertEquals(0, newFailure.getSuppressed().length);
        assertEquals(oldEnd.trace, newEnd.trace);
    }

    @Test
    public void checkedIOExceptionContractAndIdentityRemainExact() {
        IOException expected = new IOException("io");
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        try {
            legacyResult(oldRoom, true, () -> { throw expected; });
            fail("expected IOException");
        } catch (IOException actual) {
            assertSame(expected, actual);
        } catch (Exception unexpected) {
            fail("checked contract changed: " + unexpected);
        }
        try {
            TransactionCompletion.call(newRoom::run, newRoom::end, () -> { throw expected; });
            fail("expected IOException");
        } catch (IOException actual) {
            assertSame(expected, actual);
        }
        assertEquals(oldRoom.trace, newRoom.trace);
    }

    @Test
    public void nestedRollbackPoisonsOuterButPreservesOuterResultPropagation() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        assertEquals("outer", legacyResult(oldRoom, true, () -> {
            oldRoom.write("outer");
            assertNull(legacyResult(oldRoom, false, () -> {
                oldRoom.write("inner");
                return null;
            }));
            oldRoom.write("after");
            return "outer";
        }));
        assertEquals("outer", migratedResult(newRoom, true, () -> {
            newRoom.write("outer");
            assertNull(migratedResult(newRoom, false, () -> {
                newRoom.write("inner");
                return null;
            }));
            newRoom.write("after");
            return "outer";
        }));
        assertEquals(oldRoom.trace, newRoom.trace);
        assertTrue(oldRoom.committed.isEmpty());
        assertEquals(oldRoom.committed, newRoom.committed);
    }

    @Test
    public void swallowedKnownEndFailureStillPropagatesResultAfterEnd() throws Exception {
        TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
        TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
        oldRoom.swallowKnownEndFailure = true;
        newRoom.swallowKnownEndFailure = true;
        IllegalStateException end = new IllegalStateException("no current transaction");
        oldRoom.endFailure = end;
        newRoom.endFailure = end;
        assertEquals("value", legacyResult(oldRoom, true, () -> "value"));
        assertEquals("value", migratedResult(newRoom, true, () -> "value"));
        assertEquals(Arrays.asList("begin", "commit", "end", "logged-end"), oldRoom.trace);
        assertEquals(oldRoom.trace, newRoom.trace);
    }

    private static void legacyReload(TransactionCallableRoomModel room, int stop, Long reload) {
        try {
            room.begin();
            if (stop == 1)
                return;
            room.write("queue");
            if (stop == 2)
                return;
            room.commit();
        } finally {
            room.end();
        }
        room.trace.add(reload == null ? "eval" : "reload:" + reload);
    }

    private static void migratedReload(TransactionCallableRoomModel room, int stop, Long reload) {
        java.util.Optional<Long> completed = TransactionCompletion.call(room::run, room::end, () -> {
            if (stop == 1)
                return TransactionResult.rollback(null);
            room.write("queue");
            if (stop == 2)
                return TransactionResult.rollback(null);
            return TransactionResult.commit(java.util.Optional.ofNullable(reload));
        });
        if (completed == null)
            return;
        Long value = completed.orElse(null);
        room.trace.add(value == null ? "eval" : "reload:" + value);
    }

    @Test
    public void nullableReloadSuccessIsNotConfusedWithEitherRollbackExit() {
        for (int stop : new int[]{0, 1, 2})
            for (Long reload : new Long[]{null, 42L}) {
                TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
                TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
                legacyReload(oldRoom, stop, reload);
                migratedReload(newRoom, stop, reload);
                assertEquals(oldRoom.trace, newRoom.trace);
                assertEquals(oldRoom.committed, newRoom.committed);
                if (stop == 0)
                    assertEquals(reload == null ? "eval" : "reload:" + reload,
                            newRoom.trace.get(newRoom.trace.size() - 1));
            }
    }

    @Test
    public void nullableReloadEndFailureMasksCommitAndRollbackBeforeDispatch() {
        for (int stop : new int[]{0, 1, 2}) {
            Error expected = new AssertionError("end");
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            oldRoom.endFailure = newRoom.endFailure = expected;
            assertSame(expected, capture(() -> { legacyReload(oldRoom, stop, null); return null; }));
            assertSame(expected, capture(() -> { migratedReload(newRoom, stop, null); return null; }));
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals("end", newRoom.trace.get(newRoom.trace.size() - 1));
            assertEquals(0, expected.getSuppressed().length);
        }
    }

    @Test
    public void nullableReloadKnownEndFailureRetainsBothNullSuccessAndRollback() {
        for (int stop : new int[]{0, 1, 2}) {
            IllegalStateException expected = new IllegalStateException("no transaction is active");
            TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
            TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
            oldRoom.endFailure = newRoom.endFailure = expected;
            oldRoom.swallowKnownEndFailure = newRoom.swallowKnownEndFailure = true;
            legacyReload(oldRoom, stop, null);
            migratedReload(newRoom, stop, null);
            assertEquals(oldRoom.trace, newRoom.trace);
            assertEquals(stop == 0 ? "eval" : "logged-end",
                    newRoom.trace.get(newRoom.trace.size() - 1));
        }
    }

    @Test
    public void twoNullableResultsAndWakeupDispatchRetainPostEndOrder() {
        for (boolean present : new boolean[]{false, true})
            for (Long reload : new Long[]{null, 42L})
                for (Long graph : new Long[]{null, 84L}) {
                    TransactionCallableRoomModel oldRoom = new TransactionCallableRoomModel();
                    TransactionCallableRoomModel newRoom = new TransactionCallableRoomModel();
                    boolean committed = false;
                    try {
                        oldRoom.begin();
                        if (present) {
                            oldRoom.write("seen");
                            oldRoom.commit();
                            committed = true;
                        }
                    } finally {
                        oldRoom.end();
                    }
                    if (committed) {
                        oldRoom.trace.add("dispatch");
                        oldRoom.trace.add(reload == null ? "eval:" + graph : "reload:" + reload);
                    }
                    java.util.Map.Entry<Long, Long> outcome = TransactionCompletion.call(
                            newRoom::run, newRoom::end, () -> {
                                if (!present)
                                    return TransactionResult.rollback(null);
                                newRoom.write("seen");
                                return TransactionResult.commit(
                                        new java.util.AbstractMap.SimpleImmutableEntry<>(reload, graph));
                            });
                    if (outcome != null) {
                        assertSame(reload, outcome.getKey());
                        assertSame(graph, outcome.getValue());
                        newRoom.trace.add("dispatch");
                        newRoom.trace.add(outcome.getKey() == null
                                ? "eval:" + outcome.getValue() : "reload:" + outcome.getKey());
                    }
                    assertEquals(oldRoom.trace, newRoom.trace);
                    assertEquals(oldRoom.committed, newRoom.committed);
                }
    }
}

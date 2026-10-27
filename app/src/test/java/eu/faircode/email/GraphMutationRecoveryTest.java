package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphMutationRecoveryTest {
    @Test
    public void ambiguousMove404StaysQuarantinedAndUsesFolderDeltasWithoutReplayingMove() {
        EntityOperation move = new EntityOperation();
        move.name = EntityOperation.MOVE;
        move.state = null;
        move.error = GraphMailMutator.OUTCOME_UNKNOWN_ERROR;

        assertTrue(GraphMutationRecovery.requiresReconciliation(
                move, GraphMailMutator.OUTCOME_UNKNOWN_ERROR));
        assertEquals(GraphMutationRecovery.MissingMessageAction.REFRESH_SOURCE_AND_TARGET_DELTAS,
                GraphMutationRecovery.afterMessageLookupFailure(move, 404));
        assertEquals(GraphMutationRecovery.MissingMessageAction.KEEP_QUARANTINED,
                GraphMutationRecovery.afterMessageLookupFailure(move, 503));

        EntityOperation ordinaryMove = new EntityOperation();
        ordinaryMove.name = EntityOperation.MOVE;
        ordinaryMove.error = null;
        assertFalse(GraphMutationRecovery.requiresReconciliation(
                ordinaryMove, GraphMailMutator.OUTCOME_UNKNOWN_ERROR));
    }
}

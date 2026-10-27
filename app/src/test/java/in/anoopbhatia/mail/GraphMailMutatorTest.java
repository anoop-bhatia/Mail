package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphMailMutatorTest {
    @Test
    public void deleteOutsideTrashMovesToDeletedItems() {
        assertEquals(GraphMailMutator.DeleteRoute.MOVE_TO_DELETED_ITEMS,
                GraphMailMutator.getDeleteRoute(EntityFolder.INBOX));
        assertEquals(GraphMailMutator.DeleteRoute.MOVE_TO_DELETED_ITEMS,
                GraphMailMutator.getDeleteRoute(EntityFolder.ARCHIVE));
    }

    @Test
    public void deleteFromTrashUsesOrdinarySecondStageDelete() {
        assertEquals(GraphMailMutator.DeleteRoute.DELETE_FROM_DELETED_ITEMS,
                GraphMailMutator.getDeleteRoute(EntityFolder.TRASH));
    }

    @Test
    public void olderReadAndFlagCompletionsMustNotUndoNewerUserIntent() {
        for (String name : Arrays.asList(EntityOperation.SEEN, EntityOperation.FLAG)) {
            EntityOperation current = operation(10L, 3L, name);
            assertTrue(GraphMailMutator.hasNewerStateOperation(current,
                    Arrays.asList(current, operation(11L, 3L, name))));
        }
    }

    @Test
    public void unrelatedOrOlderOperationsDoNotProtectAnObsoleteStateOverlay() {
        EntityOperation current = operation(10L, 3L, EntityOperation.SEEN);
        assertFalse(GraphMailMutator.hasNewerStateOperation(current, Arrays.asList(
                operation(9L, 3L, EntityOperation.SEEN), current,
                operation(11L, 4L, EntityOperation.SEEN),
                operation(12L, 3L, EntityOperation.FLAG))));
        assertFalse(GraphMailMutator.hasNewerStateOperation(current, Collections.emptyList()));
        assertFalse(GraphMailMutator.hasNewerStateOperation(current, null));
    }

    @Test
    public void stateOverlayGuardDoesNotChangeMoveRecovery() {
        assertFalse(GraphMailMutator.hasNewerStateOperation(
                operation(10L, 3L, EntityOperation.MOVE),
                Collections.singletonList(operation(11L, 3L, EntityOperation.MOVE))));
    }

    private static EntityOperation operation(long id, long message, String name) {
        EntityOperation operation = new EntityOperation();
        operation.id = id;
        operation.message = message;
        operation.name = name;
        return operation;
    }
}

package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}

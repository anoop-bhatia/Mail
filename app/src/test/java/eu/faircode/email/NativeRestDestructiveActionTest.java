package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeRestDestructiveActionTest {
    @Test
    public void gmailMoveAndTrashPassQueueGateForSelectableReadOnlyLabelSource() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL);
        EntityFolder source = readOnlySelectableLabel();

        assertTrue(EntityOperation.canQueueNativeRestDestructiveAction(
                gmail, source, EntityOperation.MOVE));
        assertTrue(EntityOperation.canQueueNativeRestDestructiveAction(
                gmail, source, EntityOperation.DELETE));
    }

    @Test
    public void graphAndZohoStillRejectReadOnlyDestructiveSources() {
        EntityFolder source = readOnlySelectableLabel();
        EntityAccount graph = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API);
        EntityAccount zoho = account(ServiceAuthenticator.AUTH_TYPE_ZOHO);

        assertFalse(EntityOperation.canQueueNativeRestDestructiveAction(
                graph, source, EntityOperation.MOVE));
        assertFalse(EntityOperation.canQueueNativeRestDestructiveAction(
                graph, source, EntityOperation.DELETE));
        assertFalse(EntityOperation.canQueueNativeRestDestructiveAction(
                zoho, source, EntityOperation.MOVE));
        assertFalse(EntityOperation.canQueueNativeRestDestructiveAction(
                zoho, source, EntityOperation.DELETE));
    }

    @Test
    public void gmailExceptionDoesNotBypassMissingOrNonSelectableSourceChecks() {
        EntityAccount gmail = account(ServiceAuthenticator.AUTH_TYPE_GMAIL);
        EntityFolder unselectable = readOnlySelectableLabel();
        unselectable.selectable = false;

        assertFalse(EntityOperation.canQueueNativeRestDestructiveAction(
                gmail, null, EntityOperation.MOVE));
        assertFalse(EntityOperation.canQueueNativeRestDestructiveAction(
                gmail, unselectable, EntityOperation.MOVE));
    }

    private static EntityAccount account(int authType) {
        EntityAccount account = new EntityAccount();
        account.auth_type = authType;
        return account;
    }

    private static EntityFolder readOnlySelectableLabel() {
        EntityFolder folder = new EntityFolder("Projects", EntityFolder.USER);
        folder.selectable = true;
        folder.read_only = true;
        return folder;
    }
}

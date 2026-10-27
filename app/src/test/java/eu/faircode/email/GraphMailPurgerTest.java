package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphMailPurgerTest {
    @Test
    public void permanentDeleteRequiresExactDeletedItemsFolderIdentity() {
        GraphMailApi.MailFolder deletedItems = new GraphMailApi.MailFolder(
                "trash-id", "Deleted Items", null, 0, null, null);
        GraphMailApi.MailFolder inbox = new GraphMailApi.MailFolder(
                "inbox-id", "Inbox", null, 0, null, null);
        GraphMailApi.MailFolder wrongName = new GraphMailApi.MailFolder(
                "trash-id", "Archive", null, 0, null, null);

        assertTrue(GraphMailPurger.isDeletedItemsFolder("trash-id", deletedItems));
        assertFalse(GraphMailPurger.isDeletedItemsFolder("different-id", deletedItems));
        assertFalse(GraphMailPurger.isDeletedItemsFolder("inbox-id", inbox));
        assertFalse(GraphMailPurger.isDeletedItemsFolder("trash-id", wrongName));
        assertFalse(GraphMailPurger.isDeletedItemsFolder("trash-id", null));
    }

    @Test
    public void permanentDeleteIsASeparateGraphRemoteWrite() {
        assertTrue(EntityOperation.isGraphApiMutation(EntityOperation.GRAPH_PERMANENT_DELETE));
        assertTrue(EntityOperation.isGraphApiRemoteWrite(EntityOperation.GRAPH_PERMANENT_DELETE));
        assertTrue(EntityOperation.isGraphApiRemoteWrite(EntityOperation.DELETE));
        assertFalse(EntityOperation.isGraphApiRemoteWrite(EntityOperation.GRAPH_PURGE_TRASH));
    }

    @Test
    public void legacyStandaloneTrashPermanentDeleteCanOnlyDowngradeWithVerifiedLocalIdentity() {
        EntityAccount account = graphAccount();
        EntityFolder trash = trashFolder(account);
        EntityMessage message = trashMessage(account, trash);
        EntityOperation legacy = new EntityOperation();
        legacy.account = account.id;
        legacy.folder = trash.id;
        legacy.message = message.id;
        legacy.name = EntityOperation.GRAPH_PERMANENT_DELETE;
        legacy.args = new org.json.JSONArray().put("deleted-items-id").toString();

        assertTrue(GraphMailPurger.isStandalonePermanentDelete(legacy));
        assertTrue(GraphMailPurger.canDowngradeStandalonePermanentDelete(legacy, account, trash, message));

        legacy.args = new org.json.JSONArray().put("wrong-folder").toString();
        assertFalse(GraphMailPurger.canDowngradeStandalonePermanentDelete(legacy, account, trash, message));
        legacy.args = new org.json.JSONArray().put("deleted-items-id").put(77).put("graph-message-id").toString();
        assertFalse(GraphMailPurger.isStandalonePermanentDelete(legacy));
        assertFalse(GraphMailPurger.canDowngradeStandalonePermanentDelete(legacy, account, trash, message));
    }

    @Test
    public void hardDeleteChildRequiresMatchingEmptyTrashParent() {
        EntityAccount account = graphAccount();
        EntityFolder trash = trashFolder(account);
        EntityOperation parent = new EntityOperation();
        parent.id = 40L;
        parent.account = account.id;
        parent.folder = trash.id;
        parent.name = EntityOperation.GRAPH_PURGE_TRASH;
        EntityOperation child = new EntityOperation();
        child.id = 41L;
        child.account = account.id;
        child.folder = trash.id;
        child.name = EntityOperation.GRAPH_PERMANENT_DELETE;
        child.args = new org.json.JSONArray().put("deleted-items-id").put(parent.id)
                .put("graph-message-id").toString();

        assertTrue(GraphMailPurger.isValidPurgeChildRelationship(account, trash, child, parent));
        assertFalse(GraphMailPurger.isValidPurgeChildRelationship(account, trash, child, null));
        parent.name = EntityOperation.GRAPH_PERMANENT_DELETE;
        assertFalse(GraphMailPurger.isValidPurgeChildRelationship(account, trash, child, parent));
    }

    private static EntityAccount graphAccount() {
        EntityAccount account = new EntityAccount();
        account.id = 7L;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        account.user = "alice@example.com";
        return account;
    }

    private static EntityFolder trashFolder(EntityAccount account) {
        EntityFolder folder = new EntityFolder("Deleted Items", EntityFolder.TRASH);
        folder.id = 9L;
        folder.account = account.id;
        folder.graph_folder_id = "deleted-items-id";
        return folder;
    }

    private static EntityMessage trashMessage(EntityAccount account, EntityFolder folder) {
        EntityMessage message = new EntityMessage();
        message.id = 11L;
        message.account = account.id;
        message.folder = folder.id;
        message.graph_id = "graph-message-id";
        return message;
    }
}

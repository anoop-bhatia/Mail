package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

import org.json.JSONObject;
import org.junit.Test;

public class EntityFolderBackupJsonTest {
    private static EntityFolder folder() {
        EntityFolder folder = new EntityFolder("Remote", EntityFolder.USER);
        folder.id = 42L;
        folder.gmail_label_id = "Label_123";
        folder.zoho_folder_id = "zoho-123";
        folder.graph_folder_id = "graph-456";
        folder.synchronize = true;
        folder.poll = true;
        folder.sync_days = 14;
        folder.keep_days = 90;
        folder.unified = true;
        folder.notify = true;
        return folder;
    }

    @Test
    public void backupJsonRoundTripPreservesProviderFolderIdsAndPreferences() throws Exception {
        EntityFolder source = folder();
        JSONObject json = source.toJSON();
        assertEquals("zoho-123", json.getString("zoho_folder_id"));
        assertEquals("graph-456", json.getString("graph_folder_id"));
        EntityFolder restored = EntityFolder.fromJSON(json);
        assertEquals(source.gmail_label_id, restored.gmail_label_id);
        assertEquals(source.zoho_folder_id, restored.zoho_folder_id);
        assertEquals(source.graph_folder_id, restored.graph_folder_id);
        assertPreferences(source, restored);
    }

    @Test
    public void legacyBackupJsonWithoutProviderFolderIdsStillImports() throws Exception {
        JSONObject json = folder().toJSON();
        json.remove("zoho_folder_id");
        json.remove("graph_folder_id");
        EntityFolder restored = EntityFolder.fromJSON(json);
        assertNull(restored.zoho_folder_id);
        assertNull(restored.graph_folder_id);
        assertEquals("Label_123", restored.gmail_label_id);
    }

    @Test
    public void explicitNullMappingsDoNotBecomeLiteralNullStrings() throws Exception {
        JSONObject json = folder().toJSON();
        json.put("zoho_folder_id", JSONObject.NULL);
        json.put("graph_folder_id", JSONObject.NULL);
        EntityFolder restored = EntityFolder.fromJSON(json);
        assertNull(restored.zoho_folder_id);
        assertNull(restored.graph_folder_id);
    }

    @Test
    public void imapFoldersKeepExistingBackupShapeAndPreferences() throws Exception {
        EntityFolder source = folder();
        source.gmail_label_id = null;
        source.zoho_folder_id = null;
        source.graph_folder_id = null;
        source.namespace = "INBOX";
        source.separator = '/';
        JSONObject json = source.toJSON();
        assertFalse(json.has("gmail_label_id"));
        assertFalse(json.has("zoho_folder_id"));
        assertFalse(json.has("graph_folder_id"));
        EntityFolder restored = EntityFolder.fromJSON(json);
        assertEquals(source.namespace, restored.namespace);
        assertEquals(source.separator, restored.separator);
        assertPreferences(source, restored);
    }

    @Test
    public void backupDoesNotRestoreStaleApiSyncCursors() throws Exception {
        EntityFolder source = folder();
        source.graph_delta_link = "https://graph.microsoft.com/private-delta";
        source.graph_page_link = "https://graph.microsoft.com/private-page";
        source.zoho_page_start = 200;
        source.zoho_recent_scan_head_id = "private-head";
        JSONObject json = source.toJSON();
        assertFalse(json.has("graph_delta_link"));
        assertFalse(json.has("graph_page_link"));
        assertFalse(json.has("zoho_page_start"));
        assertFalse(json.has("zoho_recent_scan_head_id"));
        EntityFolder restored = EntityFolder.fromJSON(json);
        assertNull(restored.graph_delta_link);
        assertNull(restored.graph_page_link);
        assertEquals(Integer.valueOf(1), restored.zoho_page_start);
        assertNull(restored.zoho_recent_scan_head_id);
    }

    private static void assertPreferences(EntityFolder source, EntityFolder restored) {
        assertEquals(source.name, restored.name);
        assertEquals(source.type, restored.type);
        assertEquals(source.synchronize, restored.synchronize);
        assertEquals(source.poll, restored.poll);
        assertEquals(source.sync_days, restored.sync_days);
        assertEquals(source.keep_days, restored.keep_days);
        assertEquals(source.unified, restored.unified);
        assertEquals(source.notify, restored.notify);
    }
}

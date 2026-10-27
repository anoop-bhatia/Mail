package in.anoopbhatia.mail;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class GraphFolderMappingTest {
    @Test
    public void legacyBackupInboxIsMappedInPlaceBeforeFirstSyncAndRecoveryIsIdempotent() throws Exception {
        EntityFolder original = local(10, "Inbox", EntityFolder.INBOX);
        original.synchronize = false;
        JSONObject legacy = original.toJSON();
        legacy.remove("graph_folder_id");
        EntityFolder inbox = EntityFolder.fromJSON(legacy);
        inbox.id = 10L;
        inbox.type = EntityFolder.INBOX;
        inbox.notify = true;
        inbox.keep_days = 90;
        inbox.sync_days = 7;
        inbox.initialize = 30;
        inbox.sync_state = "requested";
        FakeStore store = new FakeStore(inbox);
        int[] discoveries = {0};
        GraphFolderMapping.FolderSource source = () -> {
            discoveries[0]++;
            return Arrays.asList(remote("inbox-id", "Inbox"));
        };

        GraphFolderMapping.ensureMappings(store, source);
        assertEquals("inbox-id", inbox.graph_folder_id);
        assertEquals(Long.valueOf(10), inbox.id);
        assertFalse(inbox.synchronize);
        assertTrue(inbox.notify);
        assertEquals(Integer.valueOf(90), inbox.keep_days);
        assertEquals(Integer.valueOf(7), inbox.sync_days);
        assertEquals(Integer.valueOf(30), inbox.initialize);
        assertEquals("requested", inbox.sync_state);
        assertEquals(1, store.writes);
        GraphFolderMapping.ensureMappings(store, source);
        assertEquals(1, discoveries[0]);
        assertEquals(1, store.writes);
    }

    @Test
    public void mixedBackupRepairsNullAndEmptyIdsButPreservesMappedFoldersAndCursors() throws Exception {
        EntityFolder inbox = local(1, "Inbox", EntityFolder.INBOX);
        inbox.graph_folder_id = "";
        EntityFolder sent = local(2, "Sent Items", EntityFolder.SENT);
        sent.graph_folder_id = "existing-sent";
        sent.graph_delta_link = "saved-delta";
        sent.graph_page_link = "saved-page";
        EntityFolder projects = local(3, "Projects", EntityFolder.USER);
        FakeStore store = new FakeStore(inbox, sent, projects);
        GraphFolderMapping.ensureMappings(store, () -> Arrays.asList(
                remote("inbox-id", "Inbox"), remote("new-sent", "Sent Items"),
                remote("projects-id", "Projects")));
        assertEquals("inbox-id", inbox.graph_folder_id);
        assertEquals("projects-id", projects.graph_folder_id);
        assertEquals("existing-sent", sent.graph_folder_id);
        assertEquals("saved-delta", sent.graph_delta_link);
        assertEquals("saved-page", sent.graph_page_link);
        assertEquals(2, store.writes);
        assertEquals(3, store.folders.size());
    }

    @Test
    public void localizedSystemFolderCanRecoverByUniqueType() throws Exception {
        EntityFolder inbox = local(1, "INBOX", EntityFolder.INBOX);
        FakeStore store = new FakeStore(inbox);
        GraphFolderMapping.ensureMappings(store,
                () -> Arrays.asList(remote("localized-inbox", "Posteingang")));
        assertEquals("localized-inbox", inbox.graph_folder_id);
        assertEquals("INBOX", inbox.name);
    }

    @Test
    public void completeBackupsAndNonselectableFoldersDoNotTriggerDiscovery() throws Exception {
        EntityFolder inbox = local(1, "Inbox", EntityFolder.INBOX);
        inbox.graph_folder_id = "saved-id";
        inbox.graph_delta_link = "saved-delta";
        EntityFolder container = local(2, "Container", EntityFolder.USER);
        container.selectable = false;
        FakeStore store = new FakeStore(inbox, container);
        GraphFolderMapping.ensureMappings(store, () -> {
            fail("Complete mappings must not require discovery");
            return new ArrayList<>();
        });
        assertEquals(0, store.transactions);
        assertEquals(0, store.writes);
        assertEquals("saved-id", inbox.graph_folder_id);
        assertEquals("saved-delta", inbox.graph_delta_link);
        assertNull(container.graph_folder_id);
    }

    @Test
    public void ambiguousSystemTypesAndRenamedUserFoldersAreNotGuessed() throws Exception {
        EntityFolder inbox = local(1, "INBOX", EntityFolder.INBOX);
        EntityFolder projects = local(2, "Old Projects", EntityFolder.USER);
        FakeStore store = new FakeStore(inbox, projects);
        GraphFolderMapping.ensureMappings(store, () -> Arrays.asList(
                remote("inbox-1", "Posteingang"), remote("inbox-2", "Boîte de réception"),
                remote("projects-id", "New Projects")));
        assertEquals(0, store.writes);
        assertNull(inbox.graph_folder_id);
        assertNull(projects.graph_folder_id);
    }

    @Test
    public void ambiguousOrUnavailableFoldersAreNotGuessedOrDeleted() throws Exception {
        EntityFolder inbox = local(1, "Inbox", EntityFolder.INBOX);
        EntityFolder projects = local(2, "Projects", EntityFolder.USER);
        EntityFolder gone = local(3, "Gone", EntityFolder.USER);
        FakeStore store = new FakeStore(inbox, projects, gone);
        GraphFolderMapping.ensureMappings(store, () -> Arrays.asList(
                remote("inbox-1", "Inbox"), remote("inbox-2", "Inbox"),
                remote("projects-1", "Projects"), remote("projects-2", "Projects")));
        assertEquals(0, store.writes);
        assertNull(inbox.graph_folder_id);
        assertNull(projects.graph_folder_id);
        assertNull(gone.graph_folder_id);
        assertEquals(3, store.folders.size());
    }

    @Test
    public void duplicateLocalNamesAndAlreadyClaimedRemoteIdsAreNotReassigned() throws Exception {
        EntityFolder first = local(1, "Projects", EntityFolder.USER);
        EntityFolder second = local(2, "Projects", EntityFolder.USER);
        EntityFolder inbox = local(3, "INBOX", EntityFolder.INBOX);
        EntityFolder owner = local(4, "Other", EntityFolder.USER);
        owner.graph_folder_id = "inbox-id";
        FakeStore store = new FakeStore(first, second, inbox, owner);
        GraphFolderMapping.ensureMappings(store, () -> Arrays.asList(
                remote("projects-id", "Projects"), remote("inbox-id", "Inbox")));
        assertEquals(0, store.writes);
        assertNull(first.graph_folder_id);
        assertNull(second.graph_folder_id);
        assertNull(inbox.graph_folder_id);
        assertEquals("inbox-id", owner.graph_folder_id);
    }

    @Test
    public void discoveryFailureLeavesWorkUntouchedAndNextStartupRetries() throws Exception {
        EntityFolder inbox = local(1, "Inbox", EntityFolder.INBOX);
        inbox.sync_state = "requested";
        FakeStore store = new FakeStore(inbox);
        try {
            GraphFolderMapping.ensureMappings(store, () -> { throw new IOException("offline"); });
            fail("Discovery failures must reach worker retry handling");
        } catch (IOException expected) {
            assertEquals("offline", expected.getMessage());
        }
        assertEquals(0, store.writes);
        assertEquals(0, store.transactions);
        assertNull(inbox.graph_folder_id);
        assertEquals("requested", inbox.sync_state);
        GraphFolderMapping.ensureMappings(store, () -> Arrays.asList(remote("inbox-id", "Inbox")));
        assertEquals("inbox-id", inbox.graph_folder_id);
    }

    @Test
    public void mappingAddedDuringDiscoveryIsNotOverwritten() throws Exception {
        EntityFolder inbox = local(1, "Inbox", EntityFolder.INBOX);
        FakeStore store = new FakeStore(inbox);
        GraphFolderMapping.ensureMappings(store, () -> {
            inbox.graph_folder_id = "concurrent-id";
            inbox.graph_delta_link = "concurrent-delta";
            return Arrays.asList(remote("discovered-id", "Inbox"));
        });
        assertEquals(0, store.writes);
        assertEquals("concurrent-id", inbox.graph_folder_id);
        assertEquals("concurrent-delta", inbox.graph_delta_link);
    }

    @Test
    public void missingMappingClearsStaleCursorsOnlyAfterSuccessfulWrite() throws Exception {
        EntityFolder inbox = local(1, "Inbox", EntityFolder.INBOX);
        inbox.graph_delta_link = "unmapped-delta";
        inbox.graph_page_link = "unmapped-page";
        FakeStore store = new FakeStore(inbox);
        store.rejectWrites = true;
        GraphFolderMapping.FolderSource source = () -> Arrays.asList(remote("inbox-id", "Inbox"));
        GraphFolderMapping.ensureMappings(store, source);
        assertNull(inbox.graph_folder_id);
        assertEquals("unmapped-delta", inbox.graph_delta_link);
        assertEquals("unmapped-page", inbox.graph_page_link);
        store.rejectWrites = false;
        GraphFolderMapping.ensureMappings(store, source);
        assertEquals("inbox-id", inbox.graph_folder_id);
        assertNull(inbox.graph_delta_link);
        assertNull(inbox.graph_page_link);
    }

    @Test
    public void workerRecoversBeforeMutationsRequestedSyncAndInboxSync() throws Exception {
        String service = source("ServiceSynchronize.java");
        int start = service.indexOf("private void monitorGraphAccount(");
        int recovery = service.indexOf("GraphFolderMapping.ensureMappings(", start);
        assertTrue(recovery > start);
        for (String call : Arrays.asList("GraphMailMutator.process(", "NativeReaderState.process(",
                "NativeApiContentOperations.process(", "NativeFolderState.process(",
                "syncRequestedGraphFolders(", "GraphMailReceiver.syncInbox("))
            assertTrue(call, service.indexOf(call, start) > recovery);
        String dao = source("DaoFolder.java");
        int method = dao.indexOf("int setMissingGraphFolderId(");
        String update = dao.substring(dao.lastIndexOf("@Query(", method), method);
        assertTrue(update.contains("WHERE account = :account AND id = :id"));
        assertTrue(update.contains("graph_folder_id IS NULL OR graph_folder_id = ''"));
        assertTrue(update.contains("graph_delta_link = NULL, graph_page_link = NULL"));
        assertFalse(update.contains("DELETE"));
    }

    private static String source(String name) throws IOException {
        Path path = Path.of("src/main/java/in/anoopbhatia/mail", name);
        if (!Files.exists(path))
            path = Path.of("app").resolve(path);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static EntityFolder local(long id, String name, String type) {
        EntityFolder folder = new EntityFolder(name, type);
        folder.id = id;
        folder.account = 7L;
        return folder;
    }

    private static GraphMailApi.MailFolder remote(String id, String name) {
        return new GraphMailApi.MailFolder(id, name, "root", 0, null, null);
    }

    private static final class FakeStore implements GraphFolderMapping.Store {
        final List<EntityFolder> folders;
        int writes;
        int transactions;
        boolean rejectWrites;

        FakeStore(EntityFolder... folders) {
            this.folders = new ArrayList<>(Arrays.asList(folders));
        }

        @Override
        public List<EntityFolder> folders() {
            return folders;
        }

        @Override
        public void transaction(Runnable work) {
            transactions++;
            work.run();
        }

        @Override
        public boolean map(long folderId, String remoteId) {
            assertTrue(transactions > 0);
            if (rejectWrites)
                return false;
            for (EntityFolder folder : folders)
                if (folder.id == folderId) {
                    folder.graph_folder_id = remoteId;
                    folder.graph_delta_link = null;
                    folder.graph_page_link = null;
                    writes++;
                    return true;
                }
            fail("Missing local folder");
            return false;
        }
    }
}

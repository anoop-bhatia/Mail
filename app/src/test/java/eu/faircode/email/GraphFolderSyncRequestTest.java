package eu.faircode.email;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GraphFolderSyncRequestTest {
    @Test
    public void newGraphApiAccountDefaultsToStandardSystemFolders() {
        List<EntityFolder> folders = new ArrayList<>();
        for (String name : Arrays.asList(
                "Inbox", "Deleted Items", "Drafts", "Sent Items", "Junk Email", "Archive", "Projects")) {
            EntityFolder folder = new EntityFolder(name, GraphFolderDefaults.getFolderType(name));
            folder.setProperties();
            NativeApiFolderDefaults.applyCreationDefaults(folder, true, true);
            folders.add(folder);
        }

        Set<String> expected = new HashSet<>(Arrays.asList(
                EntityFolder.INBOX, EntityFolder.DRAFTS, EntityFolder.SENT,
                EntityFolder.ARCHIVE, EntityFolder.TRASH, EntityFolder.JUNK));
        assertEquals(expected, selectedTypes(folders));
        assertEquals(expected, queuedTypes(folders));
        assertEquals(EntityFolder.TRASH, GraphFolderDefaults.getFolderType("Deleted Items"));
    }

    @Test
    public void explicitGraphFolderRefreshIsEligibleWhenOngoingSyncIsOff() {
        EntityFolder folder = new EntityFolder("Projects", EntityFolder.USER);
        folder.graph_folder_id = "remote-folder";
        folder.synchronize = false;
        folder.sync_state = "requested";

        assertTrue(GraphMailReceiver.isRequestedFolderSync(folder));
        assertFalse("An explicit refresh must not enable ongoing synchronization", folder.synchronize);

        folder.sync_state = null;
        assertFalse(GraphMailReceiver.isRequestedFolderSync(folder));
        assertFalse(folder.synchronize);
    }

    @Test
    public void onlyExplicitLoadMoreRequestsUseAnOlderPage() {
        assertFalse(GraphMailReceiver.isOlderPageRequest("[7,30,true,false,30,false]"));
        assertTrue(GraphMailReceiver.isOlderPageRequest("[7,90,true,false,90,false,true]"));
        assertFalse(GraphMailReceiver.isOlderPageRequest("not-json"));
    }

    @Test
    public void initialDeltaCutoffUsesFolderInitializationAndKeepWindow() {
        EntityFolder folder = new EntityFolder("Projects", EntityFolder.USER);
        folder.sync_days = 7;
        folder.keep_days = 90;
        folder.initialize = 60;
        long now = System.currentTimeMillis();

        Calendar expected = Calendar.getInstance();
        expected.setTimeInMillis(now);
        expected.add(Calendar.DAY_OF_MONTH, -60);
        expected.set(Calendar.HOUR_OF_DAY, 0);
        expected.set(Calendar.MINUTE, 0);
        expected.set(Calendar.SECOND, 0);
        expected.set(Calendar.MILLISECOND, 0);

        assertEquals(Math.max(0, expected.getTimeInMillis()),
                GraphMailReceiver.getInitialDeltaCutoff(folder, now));

        folder.initialize = 0;
        expected = Calendar.getInstance();
        expected.setTimeInMillis(now);
        expected.add(Calendar.DAY_OF_MONTH, -7);
        expected.set(Calendar.HOUR_OF_DAY, 0);
        expected.set(Calendar.MINUTE, 0);
        expected.set(Calendar.SECOND, 0);
        expected.set(Calendar.MILLISECOND, 0);
        assertEquals(Math.max(0, expected.getTimeInMillis()),
                GraphMailReceiver.getInitialDeltaCutoff(folder, now));

        folder.sync_days = Integer.MAX_VALUE;
        folder.keep_days = Integer.MAX_VALUE;
        folder.initialize = Integer.MAX_VALUE;
        assertEquals(0L, GraphMailReceiver.getInitialDeltaCutoff(folder, now));
    }

    private static Set<String> selectedTypes(List<EntityFolder> folders) {
        Set<String> selected = new HashSet<>();
        for (EntityFolder folder : folders)
            if (Boolean.TRUE.equals(folder.synchronize))
                selected.add(folder.type);
        return selected;
    }

    private static Set<String> queuedTypes(List<EntityFolder> folders) {
        Set<String> queued = new HashSet<>();
        for (EntityFolder folder : folders)
            if (NativeApiFolderDefaults.shouldQueueInitialSync(true, true, folder))
                queued.add(folder.type);
        return queued;
    }
}

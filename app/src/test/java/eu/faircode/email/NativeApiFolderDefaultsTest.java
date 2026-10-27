package eu.faircode.email;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeApiFolderDefaultsTest {
    private static final List<String> SYSTEM_FOLDER_TYPES = Arrays.asList(
            EntityFolder.INBOX, EntityFolder.DRAFTS, EntityFolder.SENT,
            EntityFolder.ARCHIVE, EntityFolder.TRASH, EntityFolder.JUNK);

    @Test
    public void newGmailApiAccountSelectsAndQueuesAllSystemFolders() {
        List<String> types = new ArrayList<>(SYSTEM_FOLDER_TYPES);
        types.add(EntityFolder.USER);
        List<EntityFolder> folders = new ArrayList<>();
        for (String type : types) {
            EntityFolder folder = new EntityFolder(type, type);
            folder.setProperties();
            NativeApiFolderDefaults.applyCreationDefaults(folder, true, true);
            folders.add(folder);
        }

        assertEquals(new HashSet<>(SYSTEM_FOLDER_TYPES), selectedTypes(folders));
        assertEquals("Only the standard system folders should be queued",
                new HashSet<>(SYSTEM_FOLDER_TYPES), queuedTypes(folders, true, true));
        assertNewApiSidebarDefaults(folders);
    }

    @Test
    public void gmailAllMailKeepsItsSeparateInitialNavigationDefault() {
        EntityFolder allMail = new EntityFolder("All Mail", EntityFolder.ARCHIVE);
        NativeApiFolderDefaults.applyCreationDefaults(allMail, true, true);
        assertTrue(allMail.synchronize);
        assertTrue(allMail.navigation);
        assertFalse(allMail.unified);

        GmailLabelFolder.applyAllMailDefaults(allMail, true);
        assertTrue(allMail.synchronize);
        assertTrue(allMail.navigation);
        assertFalse(allMail.unified);
        assertTrue(NativeApiFolderDefaults.shouldQueueInitialSync(true, true, allMail));
    }

    @Test
    public void newZohoApiAccountMapsSystemNamesAndSelectsAllSystemFolders() {
        List<String> names = Arrays.asList(
                "Inbox", "Drafts", "Sent", "Archive", "Bin", "Spam", "Projects");
        List<EntityFolder> folders = new ArrayList<>();
        for (String name : names) {
            String type = ZohoFolderDefaults.getFolderType(name);
            EntityFolder folder = new EntityFolder(name, type);
            folder.setProperties();
            NativeApiFolderDefaults.applyCreationDefaults(folder, true, true);
            folders.add(folder);
        }

        assertEquals(EntityFolder.TRASH, ZohoFolderDefaults.getFolderType("Trash"));
        assertEquals(EntityFolder.TRASH, ZohoFolderDefaults.getFolderType("Bin"));
        assertEquals(EntityFolder.TRASH, ZohoFolderDefaults.getFolderType("Deleted Items"));
        assertEquals(new HashSet<>(Arrays.asList("Inbox", "Drafts", "Sent", "Archive", "Bin", "Spam")),
                selectedNames(folders));
        assertEquals(new HashSet<>(Arrays.asList("Inbox", "Drafts", "Sent", "Archive", "Bin", "Spam")),
                queuedNames(folders, true, true));
        assertNewApiSidebarDefaults(folders);

        EntityFolder trash = new EntityFolder("Trash", ZohoFolderDefaults.getFolderType("Trash"));
        trash.setProperties();
        Integer syncDays = trash.sync_days;
        Integer keepDays = trash.keep_days;
        Integer pageStart = trash.zoho_page_start;
        NativeApiFolderDefaults.applyCreationDefaults(trash, true, true);
        assertTrue(trash.synchronize);
        assertTrue(NativeApiFolderDefaults.shouldQueueInitialSync(true, true, trash));
        assertEquals("Folder selection must not invent a sync-age cutoff", syncDays, trash.sync_days);
        assertEquals(keepDays, trash.keep_days);
        assertEquals(pageStart, trash.zoho_page_start);
    }

    @Test
    public void newGraphApiAccountMapsSystemNamesAndSelectsAllSystemFolders() {
        List<String> names = Arrays.asList(
                "Inbox", "Drafts", "Sent", "Archive", "Deleted Items", "Junk Email", "Projects");
        List<EntityFolder> folders = new ArrayList<>();
        for (String name : names) {
            String type = GraphFolderDefaults.getFolderType(name);
            EntityFolder folder = new EntityFolder(name, type);
            folder.setProperties();
            NativeApiFolderDefaults.applyCreationDefaults(folder, true, true);
            folders.add(folder);
        }

        assertEquals(EntityFolder.TRASH, GraphFolderDefaults.getFolderType("Deleted Items"));
        assertEquals(new HashSet<>(Arrays.asList("Inbox", "Drafts", "Sent", "Archive", "Deleted Items", "Junk Email")),
                selectedNames(folders));
        assertEquals(new HashSet<>(Arrays.asList("Inbox", "Drafts", "Sent", "Archive", "Deleted Items", "Junk Email")),
                queuedNames(folders, true, true));
        assertNewApiSidebarDefaults(folders);
    }

    @Test
    public void rediscoveryAndReauthorizationPreserveSyncAndNavigationChoices() {
        EntityFolder enabled = new EntityFolder("Projects", EntityFolder.USER);
        enabled.synchronize = true;
        enabled.navigation = false;
        NativeApiFolderDefaults.applyCreationDefaults(enabled, true, false);
        assertTrue(enabled.synchronize);
        assertFalse(enabled.navigation);

        EntityFolder disabled = new EntityFolder("Receipts", EntityFolder.USER);
        disabled.synchronize = false;
        disabled.navigation = true;
        NativeApiFolderDefaults.applyCreationDefaults(disabled, true, false);
        assertFalse(disabled.synchronize);
        assertTrue(disabled.navigation);

        EntityFolder enabledSystem = new EntityFolder("Drafts", EntityFolder.DRAFTS);
        enabledSystem.synchronize = true;
        enabledSystem.navigation = false;
        NativeApiFolderDefaults.applyCreationDefaults(enabledSystem, true, false);
        assertTrue(enabledSystem.synchronize);
        assertFalse(enabledSystem.navigation);

        EntityFolder disabledSystem = new EntityFolder("Trash", EntityFolder.TRASH);
        disabledSystem.synchronize = false;
        disabledSystem.navigation = true;
        NativeApiFolderDefaults.applyCreationDefaults(disabledSystem, true, false);
        assertFalse(disabledSystem.synchronize);
        assertTrue(disabledSystem.navigation);

        assertFalse("A folder discovered later on an existing account is not an initial-setup queue item",
                NativeApiFolderDefaults.shouldQueueInitialSync(false, true, enabled));
        assertFalse(NativeApiFolderDefaults.shouldQueueInitialSync(true, false, enabledSystem));
    }

    @Test
    public void standardGmailOauthFolderDefaultsRemainTheSixSystemFolders() {
        List<EntityFolder> folders = new ArrayList<>();
        for (String type : SYSTEM_FOLDER_TYPES) {
            EntityFolder folder = new EntityFolder(type, type);
            boolean before = folder.synchronize;
            NativeApiFolderDefaults.applyCreationDefaults(folder, false, true);
            assertEquals("Legacy OAuth folder default changed for " + type, before, folder.synchronize);
            folders.add(folder);
        }

        assertEquals(new HashSet<>(SYSTEM_FOLDER_TYPES), selectedTypes(folders));
        assertLegacySidebarDefaults(folders);
    }

    private static void assertNewApiSidebarDefaults(List<EntityFolder> folders) {
        for (EntityFolder folder : folders) {
            assertEquals("Only Inbox is unified by default: " + folder.type,
                    EntityFolder.INBOX.equals(folder.type), folder.unified);
            assertEquals("New API navigation should follow the sync default: " + folder.type,
                    NativeApiFolderDefaults.shouldSynchronizeByDefault(folder.type), folder.navigation);
        }
    }

    private static void assertLegacySidebarDefaults(List<EntityFolder> folders) {
        for (EntityFolder folder : folders) {
            assertEquals("Only Inbox is unified by default: " + folder.type,
                    EntityFolder.INBOX.equals(folder.type), folder.unified);
            assertFalse("Existing Gmail OAuth navigation defaults must not change: " + folder.type,
                    folder.navigation);
        }
    }

    private static Set<String> selectedTypes(List<EntityFolder> folders) {
        Set<String> selected = new HashSet<>();
        for (EntityFolder folder : folders)
            if (Boolean.TRUE.equals(folder.synchronize))
                selected.add(folder.type);
        return selected;
    }

    private static Set<String> selectedNames(List<EntityFolder> folders) {
        Set<String> selected = new HashSet<>();
        for (EntityFolder folder : folders)
            if (Boolean.TRUE.equals(folder.synchronize))
                selected.add(folder.name);
        return selected;
    }

    private static Set<String> queuedTypes(List<EntityFolder> folders, boolean newAccount,
                                           boolean foldersCreated) {
        Set<String> queued = new HashSet<>();
        for (EntityFolder folder : folders)
            if (NativeApiFolderDefaults.shouldQueueInitialSync(newAccount, foldersCreated, folder))
                queued.add(folder.type);
        return queued;
    }

    private static Set<String> queuedNames(List<EntityFolder> folders, boolean newAccount,
                                           boolean foldersCreated) {
        Set<String> queued = new HashSet<>();
        for (EntityFolder folder : folders)
            if (NativeApiFolderDefaults.shouldQueueInitialSync(newAccount, foldersCreated, folder))
                queued.add(folder.name);
        return queued;
    }
}

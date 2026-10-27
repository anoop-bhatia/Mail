package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class RepairHelperTest {
    @Test
    public void gmailRepairResetsHistoryAndPageCursorsButPreservesIdentityAndSettings() {
        EntityAccount account = account(ServiceAuthenticator.AUTH_TYPE_GMAIL);
        account.gmail_history_id = "history";
        account.gmail_full_sync_history_id = "full";
        account.gmail_full_sync_page_token = "page";
        account.gmail_history_page_token = "history-page";
        account.gmail_history_pending = "pending";
        EntityFolder folder = folder();
        folder.gmail_label_id = "INBOX";
        folder.gmail_page_token = "next";
        folder.synchronize = false;
        folder.sync_days = 42;

        RepairHelper.resetNativeState(account, Arrays.asList(folder));

        assertNull(account.gmail_history_id);
        assertNull(account.gmail_full_sync_history_id);
        assertNull(account.gmail_full_sync_page_token);
        assertNull(account.gmail_history_page_token);
        assertNull(account.gmail_history_pending);
        assertEquals("INBOX", folder.gmail_label_id);
        assertNull(folder.gmail_page_token);
        assertEquals(Boolean.FALSE, folder.synchronize);
        assertEquals(Integer.valueOf(42), folder.sync_days);
    }

    @Test
    public void graphRepairResetsOpaqueLinksButPreservesGraphFolderId() {
        EntityAccount account = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API);
        EntityFolder folder = folder();
        folder.graph_folder_id = "folder-id";
        folder.graph_page_link = "page";
        folder.graph_delta_link = "delta";
        folder.synchronize = false;
        folder.sync_days = 17;

        RepairHelper.resetNativeState(account, Arrays.asList(folder));

        assertEquals("folder-id", folder.graph_folder_id);
        assertNull(folder.graph_page_link);
        assertNull(folder.graph_delta_link);
        assertEquals(Boolean.FALSE, folder.synchronize);
        assertEquals(Integer.valueOf(17), folder.sync_days);
    }

    @Test
    public void graphRepairResetsEveryFolderWithoutTouchingProviderIdsOrSettings() {
        EntityAccount account = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API);
        EntityFolder inbox = folder();
        inbox.graph_folder_id = "graph-inbox";
        inbox.graph_page_link = "page-inbox";
        inbox.graph_delta_link = "delta-inbox";
        inbox.synchronize = false;
        inbox.sync_days = 11;
        EntityFolder archive = folder();
        archive.name = "Archive";
        archive.graph_folder_id = "graph-archive";
        archive.graph_page_link = "page-archive";
        archive.graph_delta_link = "delta-archive";
        archive.synchronize = true;
        archive.sync_days = 29;

        RepairHelper.resetNativeState(account, Arrays.asList(inbox, archive));

        assertEquals("graph-inbox", inbox.graph_folder_id);
        assertNull(inbox.graph_page_link);
        assertNull(inbox.graph_delta_link);
        assertEquals(Boolean.FALSE, inbox.synchronize);
        assertEquals(Integer.valueOf(11), inbox.sync_days);
        assertEquals("graph-archive", archive.graph_folder_id);
        assertNull(archive.graph_page_link);
        assertNull(archive.graph_delta_link);
        assertEquals(Boolean.TRUE, archive.synchronize);
        assertEquals(Integer.valueOf(29), archive.sync_days);
    }

    @Test
    public void zohoRepairRestartsBothScansButPreservesZohoFolderId() {
        EntityAccount account = account(ServiceAuthenticator.AUTH_TYPE_ZOHO);
        EntityFolder folder = folder();
        folder.zoho_folder_id = "folder-id";
        folder.zoho_page_start = 99;
        folder.zoho_recent_page_start = 7;
        folder.zoho_recent_scan_head_id = "head";
        folder.zoho_recent_boundary_id = "boundary";

        RepairHelper.resetNativeState(account, Arrays.asList(folder));

        assertEquals("folder-id", folder.zoho_folder_id);
        assertEquals(Integer.valueOf(1), folder.zoho_page_start);
        assertNull(folder.zoho_recent_page_start);
        assertNull(folder.zoho_recent_scan_head_id);
        assertNull(folder.zoho_recent_boundary_id);
    }

    @Test
    public void zohoRepairResetsEveryFolderWithoutTouchingProviderIdsOrSettings() {
        EntityAccount account = account(ServiceAuthenticator.AUTH_TYPE_ZOHO);
        EntityFolder first = folder();
        first.zoho_folder_id = "zoho-first";
        first.zoho_page_start = 42;
        first.zoho_recent_page_start = 3;
        first.zoho_recent_scan_head_id = "first-head";
        first.zoho_recent_boundary_id = "first-boundary";
        first.synchronize = false;
        EntityFolder second = folder();
        second.name = "Sent";
        second.zoho_folder_id = "zoho-second";
        second.zoho_page_start = 8;
        second.zoho_recent_page_start = 4;
        second.zoho_recent_scan_head_id = "second-head";
        second.zoho_recent_boundary_id = "second-boundary";
        second.sync_days = 31;

        RepairHelper.resetNativeState(account, Arrays.asList(first, second));

        assertEquals("zoho-first", first.zoho_folder_id);
        assertEquals(Integer.valueOf(1), first.zoho_page_start);
        assertNull(first.zoho_recent_page_start);
        assertNull(first.zoho_recent_scan_head_id);
        assertNull(first.zoho_recent_boundary_id);
        assertEquals(Boolean.FALSE, first.synchronize);
        assertEquals("zoho-second", second.zoho_folder_id);
        assertEquals(Integer.valueOf(1), second.zoho_page_start);
        assertNull(second.zoho_recent_page_start);
        assertNull(second.zoho_recent_scan_head_id);
        assertNull(second.zoho_recent_boundary_id);
        assertEquals(Integer.valueOf(31), second.sync_days);
    }

    @Test
    public void legacyImapAndPopRepairStateResetIsNoOp() {
        for (int protocol : new int[]{EntityAccount.TYPE_IMAP, EntityAccount.TYPE_POP}) {
            EntityAccount account = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD);
            account.protocol = protocol;
            EntityFolder folder = folder();
            folder.gmail_label_id = "legacy-label";
            folder.gmail_page_token = "legacy-page";
            folder.graph_folder_id = "legacy-graph-id";
            folder.graph_page_link = "legacy-page-link";
            folder.graph_delta_link = "legacy-delta";
            folder.zoho_folder_id = "legacy-zoho-id";
            folder.zoho_page_start = 9;
            folder.zoho_recent_page_start = 2;

            RepairHelper.resetNativeState(account, Arrays.asList(folder));

            assertEquals("legacy-label", folder.gmail_label_id);
            assertEquals("legacy-page", folder.gmail_page_token);
            assertEquals("legacy-graph-id", folder.graph_folder_id);
            assertEquals("legacy-page-link", folder.graph_page_link);
            assertEquals("legacy-delta", folder.graph_delta_link);
            assertEquals("legacy-zoho-id", folder.zoho_folder_id);
            assertEquals(Integer.valueOf(9), folder.zoho_page_start);
            assertEquals(Integer.valueOf(2), folder.zoho_recent_page_start);
        }
    }

    @Test
    public void gmailImapUsesAuthTypeToDistinguishNativeApiFromLegacy() {
        EntityFolder nativeFolder = folder();
        nativeFolder.gmail_label_id = "native-label";
        nativeFolder.gmail_page_token = "native-page";
        EntityAccount nativeAccount = account(ServiceAuthenticator.AUTH_TYPE_GMAIL);
        nativeAccount.protocol = EntityAccount.TYPE_IMAP;
        nativeAccount.host = "imap.gmail.com";
        nativeAccount.password = "ordinary-password";

        RepairHelper.resetNativeState(nativeAccount, Arrays.asList(nativeFolder));

        assertEquals("native-label", nativeFolder.gmail_label_id);
        assertNull(nativeFolder.gmail_page_token);

        EntityFolder legacyFolder = folder();
        legacyFolder.gmail_page_token = "legacy-page";
        EntityAccount legacyAccount = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD);
        legacyAccount.protocol = EntityAccount.TYPE_IMAP;
        legacyAccount.host = "imap.gmail.com";
        legacyAccount.password = "ordinary-password";

        RepairHelper.resetNativeState(legacyAccount, Arrays.asList(legacyFolder));

        assertEquals("legacy-page", legacyFolder.gmail_page_token);
    }

    @Test
    public void repairCannotResetStateAfterABoundedJoinGivesUp() throws Exception {
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean reset = new java.util.concurrent.atomic.AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                release.await();
            } catch (InterruptedException ex) {
                throw new AssertionError(ex);
            }
        });
        worker.start();
        try {
            worker.join(20);
            org.junit.Assert.assertTrue(worker.isAlive());
            try {
                RepairHelper.afterWorkersStopped(Arrays.asList(() -> false, worker::isAlive),
                        () -> reset.set(true));
                org.junit.Assert.fail("A live old worker could overwrite the repaired cursors");
            } catch (IllegalStateException expected) {
                org.junit.Assert.assertFalse(reset.get());
            }
        } finally {
            release.countDown();
            worker.join(5000);
        }
        org.junit.Assert.assertFalse(worker.isAlive());
        RepairHelper.afterWorkersStopped(Arrays.asList(() -> false, worker::isAlive),
                () -> reset.set(true));
        org.junit.Assert.assertTrue(reset.get());
    }

    @Test
    public void repairWithoutWorkersStillRuns() {
        java.util.concurrent.atomic.AtomicBoolean reset = new java.util.concurrent.atomic.AtomicBoolean();
        RepairHelper.afterWorkersStopped(java.util.Collections.emptyList(), () -> reset.set(true));
        org.junit.Assert.assertTrue(reset.get());
    }

    private static EntityAccount account(int authType) {
        EntityAccount account = new EntityAccount();
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = authType;
        return account;
    }

    private static EntityFolder folder() {
        EntityFolder folder = new EntityFolder();
        folder.name = "Inbox";
        folder.type = EntityFolder.INBOX;
        folder.local = false;
        folder.synchronize = true;
        folder.sync_days = EntityFolder.DEFAULT_SYNC;
        return folder;
    }
}

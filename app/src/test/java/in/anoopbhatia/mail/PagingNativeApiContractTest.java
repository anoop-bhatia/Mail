package in.anoopbhatia.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Regression contracts for the Paging 3 migration's native/legacy boundary. */
public class PagingNativeApiContractTest {
    @Test
    public void nativeProvidersKeepTenMessageInitialAndExplicitOlderPages() {
        assertEquals(10, FetchMoreRouting.NATIVE_PAGE_SIZE);
        assertEquals(10, GmailReceiver.PAGE_SIZE);
        assertEquals(10, ZohoReceiver.PAGE_SIZE);
        assertEquals(10, GraphMailReceiver.PAGE_SIZE);
        assertEquals(10, GraphMailApi.PAGE_SIZE);
    }

    @Test
    public void explicitOlderEligibilityHonorsProviderCursorsAndDownloadFlags() {
        EntityAccount gmail = nativeAccount(ServiceAuthenticator.AUTH_TYPE_GMAIL);
        EntityFolder g = folder("INBOX");
        g.gmail_label_id = "INBOX";
        g.download = false;
        assertTrue("Gmail header paging is independent of eager body download",
                NativeApiSyncRequest.hasOlderMessages(g, gmail));
        g.gmail_page_token = GmailReceiver.PAGE_TOKEN_EXHAUSTED;
        assertFalse(NativeApiSyncRequest.hasOlderMessages(g, gmail));

        EntityAccount zoho = nativeAccount(ServiceAuthenticator.AUTH_TYPE_ZOHO);
        EntityFolder z = folder("Archive");
        z.zoho_folder_id = "archive-id";
        z.download = false;
        assertFalse("Zoho still requires the folder download flag for paging",
                NativeApiSyncRequest.hasOlderMessages(z, zoho));
        z.download = true;
        z.zoho_page_start = 11;
        assertTrue(NativeApiSyncRequest.hasOlderMessages(z, zoho));
        z.zoho_page_start = ZohoReceiver.PAGE_START_EXHAUSTED;
        assertFalse(NativeApiSyncRequest.hasOlderMessages(z, zoho));
    }

    @Test
    public void graphOlderPagingRequiresContinuationAndDoesNotApplyAgeCutoffAtMaxWindow() {
        EntityAccount graph = nativeAccount(ServiceAuthenticator.AUTH_TYPE_GRAPH_API);
        EntityFolder f = folder("Projects");
        f.graph_folder_id = "projects-id";
        f.graph_delta_link = "delta-link";
        f.graph_page_link = "next-page";
        f.download = true;
        assertTrue(NativeApiSyncRequest.hasOlderMessages(f, graph));
        f.graph_page_link = null;
        assertFalse(NativeApiSyncRequest.hasOlderMessages(f, graph));

        f.sync_days = Integer.MAX_VALUE;
        f.keep_days = Integer.MAX_VALUE;
        f.initialize = Integer.MAX_VALUE;
        assertEquals("Maximum configured history remains uncapped",
                0L, GraphMailReceiver.getInitialDeltaCutoff(f, 123456789L));
    }

    @Test
    public void nativeFolderSyncIsExplicitForOlderPagesAndLegacyRemainsBoundaryPath() {
        assertTrue(NativeApiSyncRequest.shouldProcessNativeFolderSync(true, false));
        assertTrue(NativeApiSyncRequest.shouldProcessNativeFolderSync(false, true));
        assertFalse(NativeApiSyncRequest.shouldProcessNativeFolderSync(false, false));
        assertTrue(NativeApiSyncRequest.shouldProcessGmailFolderSync(false, true));
        assertFalse(NativeApiSyncRequest.shouldProcessGmailFolderSync(false, false));

        EntityAccount legacy = new EntityAccount();
        legacy.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        legacy.provider = "imap";
        EntityFolder folder = folder("INBOX");
        assertTrue("Non-native accounts stay on the legacy boundary path",
                NativeApiSyncRequest.hasOlderMessages(folder, legacy));
    }

    @Test
    public void graphPageAndReadbackSafeguardsRejectStaleOrIncompleteCursors() {
        assertTrue(GraphMailReceiver.canFetchMoreManually(true, "folder", "delta", "page"));
        assertFalse(GraphMailReceiver.canFetchMoreManually(true, "folder", "delta", null));
        assertFalse(GraphMailReceiver.canFetchMoreManually(false, "folder", null, "page"));
        assertTrue(GraphMailReceiver.isFolderPageExhausted(folderWithGraph("delta", null)));
        assertFalse(GraphMailReceiver.isFolderPageExhausted(folderWithGraph("delta", "page")));
    }

    private static EntityFolder folder(String name) {
        EntityFolder folder = new EntityFolder(name, EntityFolder.USER);
        folder.selectable = true;
        folder.download = true;
        return folder;
    }

    private static EntityFolder folderWithGraph(String delta, String page) {
        EntityFolder folder = folder("Graph");
        folder.graph_folder_id = "folder";
        folder.graph_delta_link = delta;
        folder.graph_page_link = page;
        return folder;
    }

    private static EntityAccount nativeAccount(int authType) {
        EntityAccount account = new EntityAccount();
        account.auth_type = authType;
        account.protocol = EntityAccount.TYPE_IMAP;
        account.provider = "native";
        return account;
    }
}

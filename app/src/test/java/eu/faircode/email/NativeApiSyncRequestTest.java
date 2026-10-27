package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeApiSyncRequestTest {
    @Test
    public void gmailOlderCursorIsVisibleUntilExactTokenExhaustion() {
        EntityAccount account = nativeAccount(ServiceAuthenticator.AUTH_TYPE_GMAIL);
        EntityFolder folder = new EntityFolder("INBOX", EntityFolder.INBOX);
        folder.selectable = true;
        folder.gmail_label_id = "INBOX";

        assertTrue("A missing token allows an explicit first-page request",
                NativeApiSyncRequest.hasOlderMessages(folder, account));
        folder.gmail_page_token = "gmail-page-2";
        assertTrue(NativeApiSyncRequest.hasOlderMessages(folder, account));
        folder.download = false;
        assertTrue("Gmail older pages fetch message references independently of eager MIME download",
                NativeApiSyncRequest.hasOlderMessages(folder, account));
        folder.download = true;
        folder.gmail_page_token = GmailReceiver.PAGE_TOKEN_EXHAUSTED;
        assertFalse(NativeApiSyncRequest.hasOlderMessages(folder, account));
    }

    @Test
    public void unsynchronizedSelectableGmailFolderCanStartManualPageWithoutAutomaticInitialization() {
        EntityAccount account = nativeAccount(ServiceAuthenticator.AUTH_TYPE_GMAIL);
        EntityFolder folder = new EntityFolder("Projects", EntityFolder.USER);
        folder.selectable = true;
        folder.gmail_label_id = "Label_42";
        folder.synchronize = false;
        folder.poll = false;

        assertTrue("A manual Fetch more starts at the first page when no cursor exists",
                NativeApiSyncRequest.hasOlderMessages(folder, account));
        assertFalse("Manual eligibility does not opt the label into startup page seeding",
                GmailReceiver.shouldInitializeFolder(folder));
        assertFalse("The folder remains outside automatic folder sync", folder.synchronize);
        assertFalse("The folder remains outside background polling", folder.poll);

        folder.gmail_page_token = GmailReceiver.PAGE_TOKEN_EXHAUSTED;
        assertFalse("Exhaustion still hides further manual requests",
                NativeApiSyncRequest.hasOlderMessages(folder, account));
    }

    @Test
    public void unsynchronizedZohoFolderCanStartAtFirstPageWithMissingCursor() {
        EntityAccount account = nativeAccount(ServiceAuthenticator.AUTH_TYPE_ZOHO);
        EntityFolder folder = new EntityFolder("Archive", EntityFolder.ARCHIVE);
        folder.selectable = true;
        folder.zoho_folder_id = "zoho-archive";
        folder.synchronize = false;

        folder.zoho_page_start = null;
        assertTrue("Zoho's manual worker treats a missing offset as page one",
                NativeApiSyncRequest.hasOlderMessages(folder, account));
        assertFalse("Manual eligibility does not enable automatic folder sync",
                ZohoReceiver.shouldSyncAutomatically(folder));
        folder.zoho_page_start = 11;
        assertTrue(NativeApiSyncRequest.hasOlderMessages(folder, account));
        folder.zoho_page_start = 0;
        assertFalse(NativeApiSyncRequest.hasOlderMessages(folder, account));
    }

    @Test
    public void unsynchronizedGraphFolderCanBootstrapManuallyWithoutAnExistingCursor() {
        EntityAccount account = nativeAccount(ServiceAuthenticator.AUTH_TYPE_GRAPH_API);
        EntityFolder folder = new EntityFolder("INBOX", EntityFolder.INBOX);
        folder.selectable = true;
        folder.graph_folder_id = "graph-inbox";
        folder.synchronize = false;

        assertTrue("A missing Graph cursor is eligible for an explicit bootstrap",
                NativeApiSyncRequest.hasOlderMessages(folder, account));
        assertFalse("Manual eligibility does not select this folder for automatic delta sync",
                GraphMailReceiver.shouldSyncSelectedFolder(folder));
        folder.graph_delta_link = "https://graph.example/delta/token";
        folder.graph_page_link = "https://graph.example/page/2";
        assertTrue(NativeApiSyncRequest.hasOlderMessages(folder, account));
        folder.graph_page_link = null;
        assertFalse(NativeApiSyncRequest.hasOlderMessages(folder, account));
    }

    @Test
    public void legacyGmailImapDoesNotUseNativeCursorExhaustionPolicy() {
        EntityAccount account = new EntityAccount();
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        EntityFolder folder = new EntityFolder("INBOX", EntityFolder.INBOX);
        folder.gmail_page_token = GmailReceiver.PAGE_TOKEN_EXHAUSTED;

        assertTrue(NativeApiSyncRequest.hasOlderMessages(folder, account));
    }

    @Test
    public void olderPageActionMarkerDoesNotClassifyOrdinaryForceSyncAsFetchMore() {
        assertFalse(NativeApiSyncRequest.isOlderPageRequest("[42,30,true,false,30,true]"));
        assertTrue(NativeApiSyncRequest.isOlderPageRequest("[42,30,true,false,30,false,true]"));
        assertFalse(NativeApiSyncRequest.isOlderPageRequest("[]"));
        assertFalse(NativeApiSyncRequest.isOlderPageRequest("not-json"));
    }

    @Test
    public void fetchMoreMarkerUpgradesAnExistingSyncArgsArrayWithoutLosingOtherParameters() {
        String ordinary = "[42,30,true,false,30,true]";
        String upgraded = NativeApiSyncRequest.markOlderPageRequest(ordinary);
        assertEquals("[42,30,true,false,30,true,true]", upgraded);
        assertTrue(NativeApiSyncRequest.isOlderPageRequest(upgraded));
        assertEquals("The existing force-sync flag is preserved", ordinary.substring(0, ordinary.length() - 1) + ",true]",
                upgraded);
        assertEquals("Marker upgrade is idempotent", upgraded,
                NativeApiSyncRequest.markOlderPageRequest(upgraded));
    }

    @Test
    public void queuedOlderPageReplacementIsGmailOnly() {
        String older = "[42,30,true,false,30,true,true]";
        assertTrue(NativeApiSyncRequest.isGmailOlderPageRequest(true, older));
        assertFalse(NativeApiSyncRequest.isGmailOlderPageRequest(false, older));
        assertFalse(NativeApiSyncRequest.isGmailOlderPageRequest(true,
                "[42,30,true,false,30,true]"));
    }

    @Test
    public void explicitOlderPageBypassesNativeRequestedStateButOrdinarySyncDoesNot() {
        assertTrue(NativeApiSyncRequest.shouldProcessNativeFolderSync(false, true));
        assertFalse(NativeApiSyncRequest.shouldProcessNativeFolderSync(false, false));
        assertTrue(NativeApiSyncRequest.shouldProcessNativeFolderSync(true, false));
    }

    private static EntityAccount nativeAccount(int authType) {
        EntityAccount account = new EntityAccount();
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = authType;
        return account;
    }
}

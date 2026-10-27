package eu.faircode.email;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class FolderActionCapabilitiesTest {
    private static EntityFolder folder(long account, String type) {
        EntityFolder folder = new EntityFolder(type, type);
        folder.account = account;
        folder.type = type;
        folder.selectable = true;
        return folder;
    }

    @Test
    public void gmailEmptyAndExpungeRequireItsMappedTrashLabel() {
        EntityAccount account = new EntityAccount();
        account.id = 3L;
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;

        EntityFolder trash = folder(account.id, EntityFolder.TRASH);
        trash.read_only = true;
        trash.gmail_label_id = "TRASH";
        assertTrue(FolderActionCapabilities.canEmptyFolder(account, trash));
        assertTrue(FolderActionCapabilities.canExpunge(account, trash));

        EntityFolder inbox = folder(account.id, EntityFolder.INBOX);
        inbox.gmail_label_id = "INBOX";
        assertFalse(FolderActionCapabilities.canEmptyFolder(account, inbox));
        assertFalse(FolderActionCapabilities.canExpunge(account, inbox));
    }

    @Test
    public void graphPurgeRequiresMappedWritableDeletedItemsAndNeverUsesExpunge() {
        EntityAccount account = new EntityAccount();
        account.id = 4L;
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;

        EntityFolder trash = folder(account.id, EntityFolder.TRASH);
        trash.graph_folder_id = "deleted-items-id";
        assertTrue(FolderActionCapabilities.canEmptyFolder(account, trash));
        assertFalse(FolderActionCapabilities.canExpunge(account, trash));

        trash.read_only = true;
        assertFalse(FolderActionCapabilities.canEmptyFolder(account, trash));
        trash.read_only = false;
        trash.graph_folder_id = null;
        assertFalse(FolderActionCapabilities.canEmptyFolder(account, trash));
    }

    @Test
    public void folderMenuShowsOnlyProviderSupportedEmptyActions() {
        TupleFolderEx graphTrash = new TupleFolderEx();
        graphTrash.account = 5L;
        graphTrash.accountProtocol = EntityAccount.TYPE_IMAP;
        graphTrash.type = EntityFolder.TRASH;
        graphTrash.selectable = true;
        graphTrash.graph_folder_id = "deleted-items-id";
        assertTrue(FolderActionCapabilities.canEmptyFolder(graphTrash));
        graphTrash.type = EntityFolder.JUNK;
        assertFalse(FolderActionCapabilities.canEmptyFolder(graphTrash));

        TupleFolderEx unmappedGraphTrash = new TupleFolderEx();
        unmappedGraphTrash.account = 7L;
        unmappedGraphTrash.accountProtocol = EntityAccount.TYPE_IMAP;
        unmappedGraphTrash.accountAuthType = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        unmappedGraphTrash.type = EntityFolder.TRASH;
        unmappedGraphTrash.selectable = true;
        assertFalse(FolderActionCapabilities.canEmptyFolder(unmappedGraphTrash));
        assertFalse(FolderActionCapabilities.canExpunge(unmappedGraphTrash));

        TupleFolderEx zohoTrash = new TupleFolderEx();
        zohoTrash.account = 8L;
        zohoTrash.accountProtocol = EntityAccount.TYPE_IMAP;
        zohoTrash.accountAuthType = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        zohoTrash.type = EntityFolder.TRASH;
        zohoTrash.selectable = true;
        zohoTrash.zoho_folder_id = "123";
        assertTrue(FolderActionCapabilities.canEmptyFolder(zohoTrash));
        assertTrue(FolderActionCapabilities.canExpunge(zohoTrash));
        zohoTrash.type = EntityFolder.JUNK;
        assertTrue(FolderActionCapabilities.canEmptyFolder(zohoTrash));
        assertFalse(FolderActionCapabilities.canExpunge(zohoTrash));
        zohoTrash.zoho_folder_id = "not-a-remote-id";
        assertFalse(FolderActionCapabilities.canEmptyFolder(zohoTrash));
        zohoTrash.zoho_folder_id = "123";
        zohoTrash.read_only = true;
        assertFalse(FolderActionCapabilities.canEmptyFolder(zohoTrash));
        zohoTrash.read_only = false;

        TupleFolderEx wrongProviderSpam = new TupleFolderEx();
        wrongProviderSpam.account = 9L;
        wrongProviderSpam.accountProtocol = EntityAccount.TYPE_IMAP;
        wrongProviderSpam.accountAuthType = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        wrongProviderSpam.type = EntityFolder.JUNK;
        wrongProviderSpam.selectable = true;
        wrongProviderSpam.zoho_folder_id = "123";
        assertFalse(FolderActionCapabilities.canEmptyFolder(wrongProviderSpam));

    }

    @Test
    public void fetchMoreUsesProviderCursorsAndGmailIgnoresDownloadDisabled() {
        TupleFolderEx gmailJunk = new TupleFolderEx();
        gmailJunk.accountAuthType = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        gmailJunk.selectable = true;
        gmailJunk.download = false;
        gmailJunk.gmail_label_id = "SPAM";
        gmailJunk.gmail_page_token = null;
        assertTrue("A selectable Gmail label can manually request its first page",
                FolderActionCapabilities.canFetchMore(gmailJunk));
        gmailJunk.gmail_page_token = "gmail-page-2";
        assertTrue("Gmail header-page eligibility is independent of eager MIME download",
                FolderActionCapabilities.canFetchMore(gmailJunk));
        gmailJunk.gmail_page_token = GmailReceiver.PAGE_TOKEN_EXHAUSTED;
        assertFalse("The Gmail end-of-pages sentinel still hides Fetch more",
                FolderActionCapabilities.canFetchMore(gmailJunk));
        gmailJunk.gmail_page_token = null;
        gmailJunk.selectable = false;
        assertFalse("A non-selectable Gmail container cannot fetch a message page",
                FolderActionCapabilities.canFetchMore(gmailJunk));

        TupleFolderEx graphTrash = new TupleFolderEx();
        graphTrash.accountAuthType = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        graphTrash.download = false;
        graphTrash.graph_delta_link = "https://graph.example/delta";
        graphTrash.graph_page_link = "https://graph.example/page/2";
        assertFalse(FolderActionCapabilities.canFetchMore(graphTrash));

        TupleFolderEx legacy = new TupleFolderEx();
        legacy.accountProtocol = EntityAccount.TYPE_IMAP;
        legacy.download = false;
        assertTrue("Legacy IMAP menu behavior is unchanged",
                FolderActionCapabilities.canFetchMore(legacy));
    }

    @Test
    public void fetchMoreShowsInitialPagesForMappedZohoAndGraphFolders() {
        TupleFolderEx zoho = new TupleFolderEx();
        zoho.accountAuthType = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        zoho.selectable = true;
        zoho.download = true;
        zoho.zoho_folder_id = "zoho-archive";
        zoho.zoho_page_start = null;
        assertTrue("A mapped Zoho folder can start manually at its first page",
                FolderActionCapabilities.canFetchMore(zoho));
        zoho.zoho_page_start = 0;
        assertFalse("The Zoho exhaustion offset still hides Fetch more",
                FolderActionCapabilities.canFetchMore(zoho));

        TupleFolderEx graph = new TupleFolderEx();
        graph.accountAuthType = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        graph.selectable = true;
        graph.download = true;
        graph.graph_folder_id = "graph-archive";
        assertTrue("A mapped Graph folder can manually bootstrap without a cursor",
                FolderActionCapabilities.canFetchMore(graph));
        graph.graph_delta_link = "https://graph.example/delta";
        assertFalse("A Graph folder with a delta checkpoint but no older page is exhausted",
                FolderActionCapabilities.canFetchMore(graph));
        graph.graph_page_link = "https://graph.example/page/2";
        assertTrue(FolderActionCapabilities.canFetchMore(graph));
        graph.download = false;
        assertFalse("The existing eager-download gate is preserved for Graph",
                FolderActionCapabilities.canFetchMore(graph));
    }

    @Test
    public void zohoSpamRequiresNativeZohoAuthTypeAndTheMappedSelectableFolder() {
        EntityAccount account = new EntityAccount();
        account.id = 13L;
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        EntityFolder spam = folder(account.id, EntityFolder.JUNK);
        spam.zoho_folder_id = "404";
        assertTrue(FolderActionCapabilities.canEmptyFolder(account, spam));
        assertFalse(FolderActionCapabilities.canExpunge(account, spam));
        spam.account = 14L;
        assertFalse(FolderActionCapabilities.canEmptyFolder(account, spam));
        spam.account = account.id;
        spam.zoho_folder_id = "bad-id";
        assertFalse(FolderActionCapabilities.canEmptyFolder(account, spam));
        spam.zoho_folder_id = "404";
        spam.selectable = false;
        assertFalse(FolderActionCapabilities.canEmptyFolder(account, spam));

        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        spam.selectable = true;
        assertFalse(FolderActionCapabilities.canEmptyFolder(account, spam));
    }

    @Test
    public void serverFolderManagementUsesLegacyImapOnlyNotTheRestProtocolSentinel() {
        TupleFolderEx imap = new TupleFolderEx();
        imap.account = 9L;
        imap.accountProtocol = EntityAccount.TYPE_IMAP;
        assertTrue(FolderActionCapabilities.canManageLegacyImapFolders(imap));

        TupleFolderEx gmail = new TupleFolderEx();
        gmail.account = 10L;
        gmail.accountProtocol = EntityAccount.TYPE_IMAP;
        gmail.accountAuthType = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        assertFalse(FolderActionCapabilities.canManageLegacyImapFolders(gmail));

        TupleFolderEx graph = new TupleFolderEx();
        graph.account = 11L;
        graph.accountProtocol = EntityAccount.TYPE_IMAP;
        graph.graph_folder_id = "graph-folder";
        assertFalse(FolderActionCapabilities.canManageLegacyImapFolders(graph));

        TupleFolderEx zoho = new TupleFolderEx();
        zoho.account = 12L;
        zoho.accountProtocol = EntityAccount.TYPE_IMAP;
        zoho.zoho_folder_id = "123";
        assertFalse(FolderActionCapabilities.canManageLegacyImapFolders(zoho));
    }
}

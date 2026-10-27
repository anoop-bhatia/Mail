package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;

public class AccountFolderPresentationTest {
    @Test
    public void gmailApiCustomLabelDisplaysHumanNameWithoutChangingStableIdentity() {
        EntityAccount account = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com");
        EntityFolder folder = new EntityFolder(
                GmailLabelFolder.getUserFolderName("Label_5"), EntityFolder.USER);
        folder.gmail_label_id = "Label_5";
        folder.display = "Travel";
        Long id = 123L;
        folder.id = id;

        assertEquals("Travel", AccountFolderPresentation.getDisplayName(account, folder));
        assertEquals("Label_5", folder.gmail_label_id);
        assertEquals(GmailLabelFolder.getUserFolderName("Label_5"), folder.name);
        assertSame(id, folder.id);
    }

    @Test
    public void gmailApiSystemFoldersUseReadableNamesIncludingAllMail() {
        EntityAccount account = account(ServiceAuthenticator.AUTH_TYPE_GMAIL, "imap.gmail.com");
        List<EntityFolder> folders = Arrays.asList(
                systemFolder(EntityFolder.INBOX, "INBOX", "Inbox"),
                systemFolder(EntityFolder.DRAFTS, "DRAFT", "Drafts"),
                systemFolder(EntityFolder.SENT, "SENT", "Sent"),
                systemFolder(EntityFolder.ARCHIVE, EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID, "All Mail"),
                systemFolder(EntityFolder.TRASH, "TRASH", "Trash"),
                systemFolder(EntityFolder.JUNK, "SPAM", "Spam"));
        String[] expected = {"Inbox", "Drafts", "Sent", "All Mail", "Trash", "Spam"};

        for (int i = 0; i < folders.size(); i++)
            assertEquals(expected[i], AccountFolderPresentation.getDisplayName(account, folders.get(i)));
        assertEquals(EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID, folders.get(3).gmail_label_id);
        assertEquals("All Mail", folders.get(3).name);
    }

    @Test
    public void legacyImapAndOtherApiProvidersKeepStoredFolderNames() {
        EntityFolder folder = new EntityFolder(
                GmailLabelFolder.getUserFolderName("Label_5"), EntityFolder.USER);
        folder.gmail_label_id = "Label_5";
        folder.display = "Travel";

        EntityAccount gmailImap = account(ServiceAuthenticator.AUTH_TYPE_PASSWORD, "imap.gmail.com");
        EntityAccount zohoApi = account(ServiceAuthenticator.AUTH_TYPE_ZOHO, "imap.zoho.com");
        EntityAccount graphApi = account(ServiceAuthenticator.AUTH_TYPE_GRAPH_API, "outlook.office365.com");
        String storedName = GmailLabelFolder.getUserFolderName("Label_5");

        for (EntityAccount account : Arrays.asList(gmailImap, zohoApi, graphApi))
            assertEquals(storedName, AccountFolderPresentation.getDisplayName(account, folder));
    }

    private static EntityFolder systemFolder(String type, String labelId, String display) {
        EntityFolder folder = new EntityFolder(display, type);
        folder.gmail_label_id = labelId;
        folder.display = display;
        return folder;
    }

    private static EntityAccount account(int authType, String host) {
        EntityAccount account = new EntityAccount();
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = authType;
        account.host = host;
        return account;
    }
}

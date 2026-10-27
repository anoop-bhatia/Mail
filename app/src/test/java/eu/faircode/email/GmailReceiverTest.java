package eu.faircode.email;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class GmailReceiverTest {
    @Test
    public void onlyGmailInlineAndCidBodyDataIsCachedEagerly() {
        EntityAttachment ordinary = new EntityAttachment();
        ordinary.provider_type = NativeApiContent.GMAIL_INLINE_PART;
        ordinary.disposition = "attachment";
        assertFalse(GmailReceiver.shouldCacheGmailInlineBytes(ordinary));

        ordinary.disposition = "inline";
        assertTrue(GmailReceiver.shouldCacheGmailInlineBytes(ordinary));
        ordinary.disposition = "attachment";
        ordinary.cid = "<logo@example.test>";
        assertTrue(GmailReceiver.shouldCacheGmailInlineBytes(ordinary));
    }

    @Test
    public void gmailFoldersMatchFairEmailSystemDefaultsAndCustomLabelsRemainOptIn() {
        EntityFolder inbox = new EntityFolder("INBOX", EntityFolder.INBOX);
        EntityFolder drafts = new EntityFolder(EntityFolder.DRAFTS, EntityFolder.DRAFTS);
        EntityFolder sent = new EntityFolder(EntityFolder.SENT, EntityFolder.SENT);
        EntityFolder archive = new EntityFolder(EntityFolder.ARCHIVE, EntityFolder.ARCHIVE);
        EntityFolder trash = new EntityFolder(EntityFolder.TRASH, EntityFolder.TRASH);
        EntityFolder junk = new EntityFolder(EntityFolder.JUNK, EntityFolder.JUNK);
        EntityFolder custom = new EntityFolder("User label", EntityFolder.USER);

        assertTrue(inbox.synchronize);
        assertFalse(inbox.poll);
        assertTrue(drafts.synchronize);
        assertTrue(drafts.poll);
        assertTrue(sent.synchronize);
        assertTrue(sent.poll);
        assertTrue(archive.synchronize);
        assertTrue(archive.poll);
        assertTrue(trash.synchronize);
        assertTrue(trash.poll);
        assertFalse(trash.download);
        assertTrue(junk.synchronize);
        assertTrue(junk.poll);
        assertFalse(junk.download);
        assertFalse("Custom labels are not in FairEmail's default system sync list",
                custom.synchronize);
        assertTrue(custom.poll);
        assertTrue(custom.download);
    }

    @Test
    public void rediscoveryPreservesAnExistingCustomLabelSyncChoice() {
        EntityFolder custom = new EntityFolder("User label", EntityFolder.USER);
        custom.synchronize = true;
        custom.poll = false;

        NativeApiFolderDefaults.applyCreationDefaults(custom, true, false);

        assertTrue(custom.synchronize);
        assertFalse(custom.poll);
    }

    @Test
    public void automaticImportHonorsSynchronizeSelectionNotDownloadAndAllMailViewMembership() {
        Map<String, EntityFolder> folders = new HashMap<>();
        EntityFolder inbox = new EntityFolder("INBOX", EntityFolder.INBOX);
        inbox.gmail_label_id = "INBOX";
        folders.put("INBOX", inbox);
        EntityFolder junk = new EntityFolder(EntityFolder.JUNK, EntityFolder.JUNK);
        junk.gmail_label_id = "SPAM";
        folders.put("SPAM", junk);
        EntityFolder custom = new EntityFolder("User label", EntityFolder.USER);
        custom.gmail_label_id = "Label_42";
        folders.put("Label_42", custom);
        EntityFolder archive = new EntityFolder(EntityFolder.ARCHIVE, EntityFolder.ARCHIVE);
        archive.gmail_label_id = GmailLabelFolder.ARCHIVE_VIEW_ID;
        folders.put(GmailLabelFolder.ARCHIVE_VIEW_ID, archive);

        assertTrue(GmailReceiver.shouldAutoImport(Arrays.asList("INBOX", "Label_42"), folders));
        assertTrue("Native API bodies are imported for a selected folder even when download=false",
                GmailReceiver.shouldAutoImport(Arrays.asList("SPAM", "Label_42"), folders));
        junk.synchronize = false;
        assertFalse("An unselected folder remains excluded",
                GmailReceiver.shouldAutoImport(Arrays.asList("SPAM", "Label_42"), folders));
        assertTrue("A label-only message belongs to Gmail All Mail",
                GmailReceiver.shouldAutoImport(Arrays.asList("Label_42"), folders));
        assertEquals("in:anywhere -in:spam -in:trash", GmailLabelFolder.ALL_MAIL_QUERY);
        assertTrue("All Mail includes Inbox messages",
                GmailLabelFolder.isMember(new String[]{"INBOX", "Label_42"},
                        GmailLabelFolder.ALL_MAIL_VIEW_ID));
        assertFalse("All Mail excludes Spam messages",
                GmailLabelFolder.isMember(new String[]{"SPAM", "Label_42"},
                        GmailLabelFolder.ALL_MAIL_VIEW_ID));
        assertFalse("All Mail excludes Trash messages",
                GmailLabelFolder.isMember(new String[]{"TRASH", "Label_42"},
                        GmailLabelFolder.ALL_MAIL_VIEW_ID));
        assertTrue("All Mail query explicitly requests Spam/Trash so q can exclude both",
                GmailReceiver.includeSpamTrashForLabel(null));

        archive.synchronize = false;
        assertFalse(GmailReceiver.shouldAutoImport(Arrays.asList("Label_42"), folders));
    }

    @Test
    public void trashWinsPhysicalStoragePrecedenceOverInboxAndSent() {
        Map<String, EntityFolder> folders = new HashMap<>();
        EntityFolder inbox = new EntityFolder("INBOX", EntityFolder.INBOX);
        EntityFolder sent = new EntityFolder(EntityFolder.SENT, EntityFolder.SENT);
        EntityFolder trash = new EntityFolder(EntityFolder.TRASH, EntityFolder.TRASH);
        folders.put("INBOX", inbox);
        folders.put("SENT", sent);
        folders.put("TRASH", trash);

        EntityFolder storage = GmailReceiver.getStorageFolder(
                Arrays.asList("INBOX", "SENT", "TRASH"), folders, inbox);

        assertTrue(storage == trash);
    }

    @Test
    public void everyAutomaticOrManualGmailPageIsLimitedToTenMessages() {
        assertEquals(10, GmailReceiver.PAGE_SIZE);
        assertEquals(GmailRateLimitBackoff.MAX_MESSAGES_PER_PASS, GmailReceiver.PAGE_SIZE);
    }

    @Test
    public void initialInboxWindowIsBoundedAndSkippedWhenInboxAutomaticFetchIsDisabled() {
        EntityFolder inbox = new EntityFolder("INBOX", EntityFolder.INBOX);
        inbox.gmail_label_id = "INBOX";
        Map<String, EntityFolder> folders = new HashMap<>();
        folders.put("INBOX", inbox);

        assertEquals(10, GmailReceiver.PAGE_SIZE);
        assertTrue(GmailReceiver.shouldFetchInitialInboxWindow(inbox, folders));

        inbox.synchronize = false;
        assertFalse(GmailReceiver.shouldFetchInitialInboxWindow(inbox, folders));
        inbox.synchronize = true;
        inbox.download = false;
        assertTrue(GmailReceiver.shouldFetchInitialInboxWindow(inbox, folders));
    }

    @Test
    public void eachSyncEnabledLabelFolderGetsOneInitialTenReferenceWindowRegardlessOfDownloadFlag() {
        EntityFolder enabled = new EntityFolder("Projects", EntityFolder.USER);
        enabled.gmail_label_id = "Label_42";
        enabled.synchronize = true;
        assertTrue(GmailReceiver.shouldInitializeFolder(enabled));
        assertEquals(10, GmailReceiver.PAGE_SIZE);

        enabled.gmail_page_token = "older-page-2";
        assertFalse("An established folder cursor must not repeat initial import",
                GmailReceiver.shouldInitializeFolder(enabled));
        enabled.gmail_page_token = null;
        enabled.download = false;
        assertTrue("download=false does not gate native REST body imports",
                GmailReceiver.shouldInitializeFolder(enabled));
        enabled.download = true;
        enabled.synchronize = false;
        assertFalse(GmailReceiver.shouldInitializeFolder(enabled));
    }

    @Test
    public void folderPageTokensAdvanceIndependentlyAndInboxKeepsInitialListFlags() {
        EntityFolder inbox = new EntityFolder("INBOX", EntityFolder.INBOX);
        EntityFolder custom = new EntityFolder("Projects", EntityFolder.USER);

        GmailReceiver.updateFolderPageToken(inbox,
                new GmailApi.MessagePage(Collections.<GmailApi.MessageReference>emptyList(), "inbox-page-2", 20));
        GmailReceiver.updateFolderPageToken(custom,
                new GmailApi.MessagePage(Collections.<GmailApi.MessageReference>emptyList(), "label-page-2", 30));

        assertEquals("inbox-page-2", inbox.gmail_page_token);
        assertEquals("label-page-2", custom.gmail_page_token);
        GmailReceiver.updateFolderPageToken(inbox,
                new GmailApi.MessagePage(Collections.<GmailApi.MessageReference>emptyList(), null, 20));
        assertEquals(GmailReceiver.PAGE_TOKEN_EXHAUSTED, inbox.gmail_page_token);
        assertEquals("A different folder's opaque token must remain unchanged",
                "label-page-2", custom.gmail_page_token);
        assertFalse(GmailReceiver.includeSpamTrashForLabel("INBOX"));
        assertTrue(GmailReceiver.includeSpamTrashForLabel("SPAM"));
        assertTrue(GmailReceiver.includeSpamTrashForLabel("TRASH"));
        assertTrue(GmailReceiver.includeSpamTrashForLabel("Label_42"));

        for (int page = 2; page <= 13; page++)
            GmailReceiver.updateFolderPageToken(inbox,
                    new GmailApi.MessagePage(Collections.<GmailApi.MessageReference>emptyList(),
                            "inbox-page-" + page, 130));
        assertEquals("inbox-page-13", inbox.gmail_page_token);
        GmailReceiver.updateFolderPageToken(inbox,
                new GmailApi.MessagePage(Collections.<GmailApi.MessageReference>emptyList(), null, 130));
        assertTrue(GmailReceiver.isFolderPageExhausted(inbox));
        for (int request = 0; request < 3; request++)
            assertTrue("Repeated Fetch more requests remain exhausted without a new list call",
                    GmailReceiver.isFolderPageExhausted(inbox));
        assertEquals("The independent label cursor must not move during Inbox paging",
                "label-page-2", custom.gmail_page_token);
    }

    @Test
    public void manualFirstPageStoresItsNextCursorOrExhaustionWithoutEnablingFolderSync() {
        EntityFolder folder = new EntityFolder("Projects", EntityFolder.USER);
        folder.selectable = true;
        folder.gmail_label_id = "Label_42";
        folder.synchronize = false;
        folder.poll = false;
        assertNull(folder.gmail_page_token);

        GmailReceiver.updateFolderPageToken(folder,
                new GmailApi.MessagePage(Collections.<GmailApi.MessageReference>emptyList(),
                        "manual-page-2", 14));
        assertEquals("manual-page-2", folder.gmail_page_token);
        assertFalse("Manual paging does not change the sync preference", folder.synchronize);
        assertFalse("Manual paging does not enable automatic polling", folder.poll);

        GmailReceiver.updateFolderPageToken(folder,
                new GmailApi.MessagePage(Collections.<GmailApi.MessageReference>emptyList(), null, 14));
        assertEquals(GmailReceiver.PAGE_TOKEN_EXHAUSTED, folder.gmail_page_token);
        assertTrue(GmailReceiver.isFolderPageExhausted(folder));
        EntityAccount account = new EntityAccount();
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        assertFalse("Exhaustion disables another explicit manual request",
                NativeApiSyncRequest.hasOlderMessages(folder, account));
        assertFalse("The folder remains excluded from automatic initialization",
                GmailReceiver.shouldInitializeFolder(folder));
    }

    @Test
    public void explicitOlderPageMarkerIsSeparateFromOrdinaryForceSyncArguments() {
        assertFalse(GmailReceiver.isOlderPageRequest("[7,30,true,false,30,true]"));
        assertTrue(GmailReceiver.isOlderPageRequest("[7,30,true,false,30,false,true]"));
    }

    @Test
    public void gmailInternalDateMillisecondsWinOverAnOldRfc822DateForReceivedOrdering() throws Exception {
        String raw = "Date: Tue, 1 Jan 2002 00:00:00 -0800\r\n" +
                "From: sender@example.com\r\n\r\nBody\r\n";
        MimeMessage mime = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));
        long rfcDate = mime.getSentDate().getTime();
        long internalDate = 1727700000000L;
        GmailApi.ReceivedMessage received = new GmailApi.ReceivedMessage(
                "gmail-id", "thread-id", "history-id", internalDate, 10,
                Collections.<String>emptyList(), new byte[0], mime);

        Long receivedAt = GmailReceiver.getReceivedAt(received);

        assertTrue("The fixture must distinguish the sender Date header from Gmail's receive time",
                rfcDate != internalDate);
        assertEquals("Gmail internalDate is already epoch milliseconds; do not use RFC Date or rescale it",
                internalDate, receivedAt.longValue());
    }

    @Test
    public void onlyForegroundRequestedFolderSyncQueuesASeparatePage() {
        EntityFolder folder = new EntityFolder("User label", EntityFolder.USER);
        assertFalse(GmailReceiver.isRequestedFolderSync(folder));
        folder.sync_state = "requested";
        assertTrue(GmailReceiver.isRequestedFolderSync(folder));
        folder.sync_state = "connected";
        assertFalse(GmailReceiver.isRequestedFolderSync(folder));
    }

    @Test
    public void explicitFolderSyncDefersOnlyAnEstablishedHistoryPage() {
        GmailSyncState history = new GmailSyncState("history-42", null, null, null, "{\"changes\":[]}");
        GmailSyncState full = GmailSyncState.empty().beginFullSync("history-10");

        assertTrue(GmailReceiver.shouldDeferHistoryForFolderSync(history, true));
        assertFalse(GmailReceiver.shouldDeferHistoryForFolderSync(history, false));
        assertFalse("The initial bounded Inbox page must still run",
                GmailReceiver.shouldDeferHistoryForFolderSync(full, true));
        assertEquals("history-42", history.historyId);
        assertEquals("{\"changes\":[]}", history.historyPendingJson);
    }
}

package eu.faircode.email;

import org.junit.Test;
import org.json.JSONObject;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZohoTransportRoutingTest {
    private static String tokenState() throws Exception {
        return ZohoState.authorized("access-fixture", "refresh-fixture",
                "https://www.zohoapis.eu", "https://accounts.zoho.eu", 3600)
                .jsonSerializeString();
    }

    @Test
    public void zohoApiAccountCannotFallBackToImapOrPopDespiteLegacyColumns() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        account.protocol = EntityAccount.TYPE_IMAP;
        account.host = "imap.zoho.eu";
        account.encryption = EmailService.ENCRYPTION_SSL;

        assertTrue(account.isZohoApi());
        assertTrue(account.isNativeRestApi());
        assertFalse(account.isGmailApi());
        assertFalse(account.isZoho());
        try {
            account.getProtocol();
            fail("A Zoho Mail API account must never resolve to IMAP or POP");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Zoho Mail API"));
        }
    }

    @Test
    public void nativeRestFolderCannotQueueLegacySubscribeButImapCan() {
        EntityAccount zoho = new EntityAccount();
        zoho.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        zoho.protocol = EntityAccount.TYPE_IMAP;
        assertFalse(EntityOperation.canQueueSubscribe(zoho));

        EntityAccount imap = new EntityAccount();
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertTrue(EntityOperation.canQueueSubscribe(imap));
    }

    @Test
    public void zohoApiFolderNavigationSkipsLegacyImapSearchButOrdinaryImapRemainsSupported() throws Exception {
        EntityAccount zoho = new EntityAccount();
        zoho.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        zoho.protocol = EntityAccount.TYPE_IMAP;
        assertFalse(zoho.supportsLegacyImapSearch());

        EntityAccount staleState = new EntityAccount();
        staleState.auth_type = ServiceAuthenticator.AUTH_TYPE_OAUTH;
        staleState.protocol = EntityAccount.TYPE_IMAP;
        staleState.password = tokenState();
        assertFalse(staleState.supportsLegacyImapSearch());

        EntityAccount imap = new EntityAccount();
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertTrue(imap.supportsLegacyImapSearch());
    }

    @Test
    public void serializedZohoStateRecoversApiRoutingWhenAuthTypeIsStale() throws Exception {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_OAUTH;
        account.protocol = EntityAccount.TYPE_POP;
        account.host = "imap.zoho.eu";
        account.password = tokenState();

        assertTrue(account.isZohoApi());
        assertTrue(account.isNativeRestApi());
        assertFalse(account.isZoho());
        try {
            account.getProtocol();
            fail("Versioned Zoho API state must not resolve to POP");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Zoho Mail API"));
        }
    }

    @Test
    public void zohoApiIdentityCannotFallBackToSmtp() {
        EntityIdentity identity = new EntityIdentity();
        identity.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        identity.host = "smtp.zoho.eu";
        identity.encryption = EmailService.ENCRYPTION_SSL;

        assertTrue(identity.isZohoApi());
        assertTrue(identity.isNativeRestApi());
        assertFalse(identity.isGmailApi());
        try {
            identity.getProtocol();
            fail("A Zoho Mail API identity must never resolve to SMTP");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Zoho Mail API"));
        }
    }

    @Test
    public void zohoMutationsAndSentSyncDoNotUseLegacyImapOperations() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        account.protocol = EntityAccount.TYPE_IMAP;

        assertTrue(EntityOperation.isZohoApiMutation(EntityOperation.DELETE));
        assertTrue(EntityOperation.isZohoApiMutation(EntityOperation.MOVE));
        assertTrue(EntityOperation.isZohoApiMutation(EntityOperation.SEEN));
        assertTrue(EntityOperation.isZohoApiMutation(EntityOperation.FLAG));
        assertFalse(EntityOperation.isZohoApiMutation(EntityOperation.SEND));
        assertFalse(EntityOperation.usesLegacyImapSyncSideEffects(account));
        assertFalse(EntityOperation.shouldQueueSentExists(account));
    }

    @Test
    public void onlyUnlinkedZohoDraftsAreLocalNativeRestRows() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        EntityFolder drafts = new EntityFolder();
        drafts.type = EntityFolder.DRAFTS;
        EntityMessage draft = new EntityMessage();

        assertTrue(EntityOperation.isZohoApiLocalDraft(account, drafts, draft));
        assertFalse(draft.isNativeRestMessage());

        draft.zoho_id = "remote-draft-fixture";
        assertFalse(EntityOperation.isZohoApiLocalDraft(account, drafts, draft));
        assertTrue(draft.isNativeRestMessage());
    }

    @Test
    public void writableZohoMessageRequiresItsOwnIdAndSourceFolderMembership() {
        EntityAccount account = new EntityAccount();
        account.id = 42L;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;

        EntityFolder folder = new EntityFolder("Inbox", EntityFolder.INBOX);
        folder.account = account.id;
        folder.zoho_folder_id = "101";
        folder.selectable = true;

        EntityMessage message = new EntityMessage();
        message.zoho_id = "201";
        message.zoho_folder_ids = "|101|";
        assertTrue(ZohoMailMutator.isWritableMessage(account, folder, message));

        EntityFolder target = new EntityFolder("Archive", EntityFolder.ARCHIVE);
        target.account = account.id;
        target.zoho_folder_id = "102";
        target.selectable = true;
        assertTrue(ZohoMailMutator.isValidZohoMoveTarget(account, account, target, false));
        assertFalse(ZohoMailMutator.isValidZohoMoveTarget(account, account, target, true));

        EntityAccount otherZoho = new EntityAccount();
        otherZoho.id = 43L;
        otherZoho.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        EntityFolder otherTarget = new EntityFolder("Archive", EntityFolder.ARCHIVE);
        otherTarget.account = otherZoho.id;
        otherTarget.zoho_folder_id = "103";
        otherTarget.selectable = true;
        assertFalse(ZohoMailMutator.isValidZohoMoveTarget(account, otherZoho, otherTarget, false));

        EntityAccount graph = new EntityAccount();
        graph.id = account.id;
        graph.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        assertFalse(ZohoMailMutator.isValidZohoMoveTarget(account, graph, target, false));

        EntityMessage graphOnly = new EntityMessage();
        graphOnly.graph_id = "same-remote-id";
        graphOnly.zoho_folder_ids = "|101|";
        assertFalse(ZohoMailMutator.isWritableMessage(account, folder, graphOnly));

        EntityMessage gmailOnly = new EntityMessage();
        gmailOnly.gmail_id = "same-remote-id";
        gmailOnly.zoho_folder_ids = "|101|";
        assertFalse(ZohoMailMutator.isWritableMessage(account, folder, gmailOnly));

        EntityMessage wrongSource = new EntityMessage();
        wrongSource.zoho_id = "202";
        wrongSource.zoho_folder_ids = "|102|";
        assertFalse(ZohoMailMutator.isWritableMessage(account, folder, wrongSource));
    }

    @Test
    public void zohoSentCopyBecomesSelectableAndWritableWhenSendResponseIsLinked() throws Exception {
        EntityAccount account = new EntityAccount();
        account.id = 42L;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;

        EntityFolder sent = new EntityFolder("Sent", EntityFolder.SENT);
        sent.account = account.id;
        sent.zoho_folder_id = "105";
        sent.selectable = true;

        EntityMessage localCopy = new EntityMessage();
        assertFalse(SelectionPredicateMessage.canSelectImapMessage(false, false, false, false, sent.read_only));
        assertFalse(ZohoMailMutator.isWritableMessage(account, sent, localCopy));

        String messageId = ZohoApi.getSentMessageId(
                new JSONObject("{\"data\":{\"messageId\":\"205\"}}"));
        localCopy.zoho_id = messageId;
        localCopy.zoho_folder_ids = ZohoMailMutator.addFolderId(null, sent.zoho_folder_id);

        assertTrue(SelectionPredicateMessage.canSelectImapMessage(false, false, true, false, sent.read_only));
        assertTrue(ZohoMailMutator.isWritableMessage(account, sent, localCopy));
        assertTrue(EntityOperation.isZohoApiMutation(EntityOperation.MOVE));

        EntityMessage graphOnly = new EntityMessage();
        graphOnly.graph_id = messageId;
        graphOnly.zoho_folder_ids = localCopy.zoho_folder_ids;
        assertFalse(ZohoMailMutator.isWritableMessage(account, sent, graphOnly));
    }
}

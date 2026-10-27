package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EntityGmailTransportTest {
    @Test
    public void gmailApiAccountCannotResolveToImapOrPopDespiteLegacyColumns() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        account.protocol = EntityAccount.TYPE_IMAP;
        account.host = "imap.gmail.com";
        account.encryption = EmailService.ENCRYPTION_SSL;

        assertTrue(account.isGmailApi());
        assertTrue(account.isGmail());
        try {
            account.getProtocol();
            fail("A Gmail API account must never resolve to a generic mail protocol");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Gmail API"));
        }
    }

    @Test
    public void gmailApiIdentityCannotResolveToSmtp() {
        EntityIdentity identity = new EntityIdentity();
        identity.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        identity.host = "smtp.gmail.com";
        identity.encryption = EmailService.ENCRYPTION_SSL;

        assertTrue(identity.isGmailApi());
        try {
            identity.getProtocol();
            fail("A Gmail API identity must never resolve to SMTP");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Gmail API"));
        }
    }

    @Test
    public void directTokenStateRecoversNativeGmailMappingWhenAuthTypeIsStale() {
        String password = GmailState.authorized("access-token", "user@gmail.com").jsonSerializeString();

        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_OAUTH;
        account.protocol = EntityAccount.TYPE_POP;
        account.host = "imap.gmail.com";
        account.password = password;
        account.encryption = EmailService.ENCRYPTION_SSL;
        assertTrue(account.isGmailApi());
        assertEquals(ServiceAuthenticator.AUTH_TYPE_GMAIL,
                ServiceAuthenticator.getEffectiveAuthType(account.auth_type, password));
        try {
            account.getProtocol();
            fail("Versioned Gmail API credentials must not resolve to POP");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("Gmail API"));
        }

        EntityIdentity identity = new EntityIdentity();
        identity.auth_type = ServiceAuthenticator.AUTH_TYPE_OAUTH;
        identity.host = "smtp.gmail.com";
        identity.password = password;
        assertTrue(identity.isGmailApi());
    }

    @Test
    public void legacyGmailIsDistinguishedFromNativeApiAuthAndOtherProvidersAreUnchanged() {
        EntityAccount legacy = new EntityAccount();
        legacy.auth_type = ServiceAuthenticator.AUTH_TYPE_OAUTH;
        legacy.protocol = EntityAccount.TYPE_IMAP;
        legacy.host = "imap.gmail.com";
        legacy.encryption = EmailService.ENCRYPTION_SSL;

        assertTrue(legacy.isGmail());
        assertFalse(legacy.isGmailApi());

        EntityAccount other = new EntityAccount();
        other.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        other.protocol = EntityAccount.TYPE_IMAP;
        other.host = "imap.example.net";
        other.encryption = EmailService.ENCRYPTION_SSL;
        assertFalse(other.isGmailApi());
        assertFalse(other.isGmail());
        assertEquals("imaps", other.getProtocol());
    }

    @Test
    public void nativeGmailReadOnlyActionsRequireModifyButSendingDoesNot() {
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.DELETE));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.MOVE));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.SEEN));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.FLAG));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.KEYWORD));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.LABEL));
        assertTrue(EntityOperation.isGmailApiMutation(EntityOperation.MOVE));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.ANSWERED));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.ADD));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.COPY));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.EXPUNGE));
        assertTrue(EntityOperation.isGmailApiWriteOperation(EntityOperation.EXPUNGE));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.SEND));
    }

    @Test
    public void gmailLongPressAndMoreActionsRequireGmailIdsAndOneWritableLabelAccount() {
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(
                false, true, false, false, true));
        assertFalse(SelectionPredicateMessage.canSelectImapMessage(
                false, false, false, false, true));
        assertTrue(MessageSelectionCapabilities.canQueueNativeReadFlag(
                true, true, true, false, false, false, false));
        assertFalse(MessageSelectionCapabilities.canQueueNativeReadFlag(
                true, true, false, false, false, false, false));

        assertTrue(MessageSelectionCapabilities.canMoveToGmailFolder(
                true, false, true, true));
        assertFalse(MessageSelectionCapabilities.canMoveToGmailFolder(
                true, true, true, true));
        assertFalse(MessageSelectionCapabilities.canMoveToGmailFolder(
                true, false, false, true));
        assertFalse(MessageSelectionCapabilities.canMoveToGmailFolder(
                true, false, true, false));

        // Selection IDs are local Room IDs; Graph and Zoho keep their own policy gates.
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(false, false, false, true, false));
        assertTrue(MessageSelectionCapabilities.canQueueZohoReadFlag(true, false));
        assertFalse(MessageSelectionCapabilities.canQueueZohoReadFlag(false, false));
        assertTrue(EntityOperation.isGraphApiRemoteWrite(EntityOperation.MOVE));
        assertFalse(EntityOperation.isGraphApiRemoteWrite(EntityOperation.COPY));
        assertTrue(EntityOperation.isZohoApiMutation(EntityOperation.DELETE));
        assertFalse(EntityOperation.isGmailApiMutation(EntityOperation.COPY));
    }

    @Test
    public void nativeGmailDoesNotUseLegacyImapFolderSyncSideEffects() {
        EntityAccount gmail = new EntityAccount();
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        gmail.protocol = EntityAccount.TYPE_IMAP;
        assertFalse(EntityOperation.usesLegacyImapSyncSideEffects(gmail));

        EntityAccount imap = new EntityAccount();
        imap.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertTrue(EntityOperation.usesLegacyImapSyncSideEffects(imap));

        imap.protocol = EntityAccount.TYPE_POP;
        assertFalse(EntityOperation.usesLegacyImapSyncSideEffects(imap));
    }

    @Test
    public void successfulGmailApiSendSkipsLegacyExistsButImapRetainsIt() {
        EntityAccount gmail = new EntityAccount();
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        gmail.protocol = EntityAccount.TYPE_IMAP;
        assertFalse(EntityOperation.shouldQueueSentExists(gmail));
        assertTrue(EntityOperation.isGmailApiLegacyExists(gmail, EntityOperation.EXISTS));
        assertFalse(EntityOperation.isGmailApiLegacyExists(gmail, EntityOperation.SYNC));

        EntityAccount imap = new EntityAccount();
        imap.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertTrue(EntityOperation.shouldQueueSentExists(imap));
        assertFalse(EntityOperation.isGmailApiLegacyExists(imap, EntityOperation.EXISTS));
        assertTrue(EntityOperation.shouldQueueSentExists(null));
    }

    @Test
    public void queuedManualSyncRunsOutsidePollingAndOnDemandButRespectsAccountSyncOff() {
        TupleAccountState account = new TupleAccountState();
        account.synchronize = true;
        account.ondemand = true;
        account.operations = 1;
        account.folders = 1;

        assertTrue(account.shouldRun(false));

        account.synchronize = false;
        assertFalse(account.shouldRun(false));
    }

    @Test
    public void onlyUnlinkedDraftsOnNativeGmailAreLocalDrafts() {
        EntityAccount gmail = new EntityAccount();
        gmail.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        EntityFolder drafts = new EntityFolder();
        drafts.type = EntityFolder.DRAFTS;
        EntityMessage localDraft = new EntityMessage();

        assertTrue(EntityOperation.isGmailApiLocalDraft(gmail, drafts, localDraft));

        localDraft.gmail_id = "remote-draft-id";
        assertFalse(EntityOperation.isGmailApiLocalDraft(gmail, drafts, localDraft));

        EntityAccount imap = new EntityAccount();
        imap.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        localDraft.gmail_id = null;
        assertFalse(EntityOperation.isGmailApiLocalDraft(imap, drafts, localDraft));
    }
}

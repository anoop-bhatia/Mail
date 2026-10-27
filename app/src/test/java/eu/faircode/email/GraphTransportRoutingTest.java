package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GraphTransportRoutingTest {
    @Test
    public void graphMailAccountAndIdentityCannotEnterImapOrSmtpPaths() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        account.protocol = EntityAccount.TYPE_IMAP; // Persistence sentinel only.
        account.host = "graph.microsoft.com";

        EntityIdentity identity = new EntityIdentity();
        identity.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        identity.host = account.host;

        assertTrue(account.isGraphApi());
        assertTrue(account.isNativeRestApi());
        assertFalse(account.supportsLegacyImapSearch());
        assertFalse(EntityOperation.usesLegacyImapSyncSideEffects(account));
        assertFalse(EntityOperation.shouldQueueSentExists(account));
        assertFalse(EntityOperation.isGraphApiMutation(EntityOperation.SEND));
        assertTrue(EntityOperation.isGraphApiMutation(EntityOperation.FLAG));
        assertTrue(EntityOperation.isGraphApiRemoteWrite(EntityOperation.DELETE));
        assertTrue(EntityOperation.isGraphApiRemoteWrite(EntityOperation.MOVE));
        assertTrue(EntityOperation.isGraphApiRemoteWrite(EntityOperation.SEEN));
        assertTrue(EntityOperation.isGraphApiRemoteWrite(EntityOperation.FLAG));
        assertFalse(EntityOperation.isGraphApiRemoteWrite(EntityOperation.COPY));
        assertFalse(EntityOperation.isGraphApiRemoteWrite(EntityOperation.KEYWORD));
        assertTrue(identity.isGraphApi());
        assertTrue(identity.isNativeRestApi());

        try {
            account.getProtocol();
            fail("A Graph API account must not be opened as IMAP/POP");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("do not use an IMAP or POP protocol"));
        }
        try {
            identity.getProtocol();
            fail("A Graph API identity must not be opened as SMTP");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("do not use SMTP"));
        }
    }

    @Test
    public void draftQueueDistinguishesLocalGraphDraftFromRemoteMessage() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        EntityFolder drafts = new EntityFolder("Drafts", EntityFolder.DRAFTS);
        EntityMessage local = new EntityMessage();
        assertTrue(EntityOperation.isGraphApiLocalDraft(account, drafts, local));

        local.graph_id = "remote-message-id";
        assertFalse(EntityOperation.isGraphApiLocalDraft(account, drafts, local));
    }

    @Test
    public void longPressSelectionAcceptsGraphIdsButNotReadOnlyOrUnidentifiedImapRows() {
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(false, true, false));
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(true, false, false));
        assertFalse(SelectionPredicateMessage.canSelectImapMessage(false, false, false));
        assertFalse(SelectionPredicateMessage.canSelectImapMessage(false, true, true));
    }

    @Test
    public void longPressGraphSelectionExposesSameAccountMoveInMoreActions() {
        // Graph rows have a remote graph_id but no IMAP UID; selection keys remain local message IDs.
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(false, true, false));
        assertTrue(MessageSelectionCapabilities.canMoveToFolder(
                false, true, false, true, false, null, false));
        assertFalse(EntityOperation.isGraphApiRemoteWrite(EntityOperation.COPY));
    }

    @Test
    public void nativeProviderSelectionUsesItsOwnRemoteId() {
        // Long-press selection eligibility never replaces the local Room message key.
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(false, true, false, false, false));
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(false, false, true, false, false));
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(false, false, false, true, false));
        assertTrue(SelectionPredicateMessage.canSelectImapMessage(false, true, false, false, true));
        assertFalse(SelectionPredicateMessage.canSelectImapMessage(false, false, true, false, true));
        assertFalse(SelectionPredicateMessage.canSelectImapMessage(false, false, false, true, true));
        assertFalse(SelectionPredicateMessage.canSelectImapMessage(false, false, false, false, false));
    }

    @Test
    public void nativeReadFlagRoutingDoesNotAliasProviderIds() {
        assertTrue(MessageSelectionCapabilities.canQueueNativeReadFlag(false, false, false, false, false));
        assertTrue(MessageSelectionCapabilities.canQueueNativeReadFlag(true, true, true, false, false));
        assertTrue(MessageSelectionCapabilities.canQueueNativeReadFlag(true, false, false, true, true));
        assertFalse(MessageSelectionCapabilities.canQueueNativeReadFlag(true, true, false, false, true));
        assertFalse(MessageSelectionCapabilities.canQueueNativeReadFlag(true, false, true, true, false));
        assertTrue(MessageSelectionCapabilities.canQueueNativeReadFlag(
                true, true, true, false, false, false, false));
        assertFalse(MessageSelectionCapabilities.canQueueNativeReadFlag(
                true, true, false, false, false, false, false));
        assertFalse(MessageSelectionCapabilities.canQueueNativeReadFlag(true, false, false, false, false));
        assertTrue(MessageSelectionCapabilities.canQueueZohoReadFlag(true, false));
        assertFalse(MessageSelectionCapabilities.canQueueZohoReadFlag(false, false));
        assertFalse(MessageSelectionCapabilities.canQueueZohoReadFlag(true, true));
    }

    @Test
    public void zohoMoveRequiresOneWritableZohoAccountAndFolderSet() {
        assertTrue(MessageSelectionCapabilities.canMoveToZohoFolder(false, false, true));
        assertFalse(MessageSelectionCapabilities.canMoveToZohoFolder(true, false, true));
        assertFalse(MessageSelectionCapabilities.canMoveToZohoFolder(false, true, true));
        assertFalse(MessageSelectionCapabilities.canMoveToZohoFolder(false, false, false));
    }

    @Test
    public void graphMoreMoveRequiresOneWritableGraphAccount() {
        assertFalse(MessageSelectionCapabilities.canMoveToFolder(
                false, true, true, true, false, null, true));
        assertFalse(MessageSelectionCapabilities.canMoveToFolder(
                false, true, false, false, false, null, true));
        assertFalse(MessageSelectionCapabilities.canMoveToFolder(
                true, true, false, true, false, null, true));
    }

    @Test
    public void imapAndPopMoveAvailabilityRemainsUnchanged() {
        assertTrue(MessageSelectionCapabilities.canMoveToFolder(
                false, false, false, false, false, null, true));
        assertTrue(MessageSelectionCapabilities.canMoveToFolder(
                false, false, false, false, true, true, true));
        assertFalse(MessageSelectionCapabilities.canMoveToFolder(
                false, false, false, false, true, false, true));
        assertFalse(MessageSelectionCapabilities.canMoveToFolder(
                false, false, false, false, false, null, false));
    }
}

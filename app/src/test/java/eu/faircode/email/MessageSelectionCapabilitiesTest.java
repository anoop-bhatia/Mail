package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageSelectionCapabilitiesTest {
    @Test
    public void showHeadersRequiresLocalDataOrAUidThatCanFetchIt() {
        assertTrue("Gmail API headers are rendered from the parsed local MIME headers",
                MessageSelectionCapabilities.canShowHeaders(false, true, false,
                        "From: sender@example.com"));
        assertTrue("UID-based IMAP messages can fetch headers when they are not cached",
                MessageSelectionCapabilities.canShowHeaders(true, false, false, null));
        assertFalse("API messages with no local headers cannot be fetched through IMAP fallback",
                MessageSelectionCapabilities.canShowHeaders(false, true, false, null));
        assertFalse("Empty header text is not displayable without a UID fetch path",
                MessageSelectionCapabilities.canShowHeaders(false, true, false, ""));
        assertTrue("POP retains its existing locally-cached header path",
                MessageSelectionCapabilities.canShowHeaders(false, false, true,
                        "From: sender@example.com"));
        assertFalse("Header availability alone does not broaden legacy IMAP behavior",
                MessageSelectionCapabilities.canShowHeaders(false, false, false,
                        "From: sender@example.com"));
    }

    @Test
    public void anyProviderReadOnlySelectionDisablesMutation() {
        assertTrue(MessageSelectionCapabilities.canModifySelection(false, false, false, false));
        assertFalse(MessageSelectionCapabilities.canModifySelection(false, false, true, false));
        assertFalse(MessageSelectionCapabilities.canModifySelection(false, true, false, false));
        assertFalse(MessageSelectionCapabilities.canModifySelection(false, false, false, true));
        assertFalse(MessageSelectionCapabilities.canModifySelection(true, false, false, false));
    }

    @Test
    public void uidlessNativeApiMessagesAreNeverDeletedLocallyAsLegacyImap() {
        assertFalse(MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                true, false, false, EntityAccount.TYPE_IMAP, false));
        assertFalse(MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                false, true, false, EntityAccount.TYPE_IMAP, false));
        assertFalse("Zoho API messages have no IMAP UID but still require their provider request",
                MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                        false, false, true, EntityAccount.TYPE_IMAP, false));
        assertTrue("Legacy IMAP local-only delete behavior remains unchanged",
                MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                        false, false, false, EntityAccount.TYPE_IMAP, false));
        assertFalse(MessageSelectionCapabilities.shouldDeleteLocallyWithoutUid(
                false, false, false, EntityAccount.TYPE_POP, false));
    }

    @Test
    public void explicitPermanentApiDeleteAlwaysRequiresConfirmation() {
        assertTrue(MessageSelectionCapabilities.shouldShowPermanentDeleteConfirmation(false, true));
        assertTrue(MessageSelectionCapabilities.shouldShowPermanentDeleteConfirmation(true, false));
        assertFalse(MessageSelectionCapabilities.shouldShowPermanentDeleteConfirmation(false, false));
    }

    @Test
    public void gmailPermanentDeleteContextIsLimitedToWritableTrashMessages() {
        assertTrue(GmailTrashDeleteAction.isPermanentDeleteInTrash(
                true, true, true, EntityFolder.TRASH));
        assertFalse(GmailTrashDeleteAction.isPermanentDeleteInTrash(
                true, true, true, EntityFolder.INBOX));
        assertFalse(GmailTrashDeleteAction.isPermanentDeleteInTrash(
                true, false, true, EntityFolder.TRASH));
        assertFalse(GmailTrashDeleteAction.isPermanentDeleteInTrash(
                true, true, false, EntityFolder.TRASH));
    }

    @Test
    public void labelChangeToastIsSuppressedOnlyForNativeGmailMessages() {
        assertFalse(MessageSelectionCapabilities.shouldShowLabelChangeToast(true));
        assertTrue(MessageSelectionCapabilities.shouldShowLabelChangeToast(false));
    }

    @Test
    public void expectedGmailReadStateDiffDoesNotShowAToast() {
        assertFalse(MessageSelectionCapabilities.shouldShowUiSeenChangeToast(true));
        assertTrue(MessageSelectionCapabilities.shouldShowUiSeenChangeToast(false));
        assertFalse("Gmail aggregate unread and open-state diffs stay in logs, not toasts",
                MessageSelectionCapabilities.shouldShowReadStateDiffToast(true));
        assertTrue(MessageSelectionCapabilities.shouldShowReadStateDiffToast(false));
    }

    @Test
    public void nativeDeleteMoreMenuUsesPermanentLabelForAllThreeApiProviders() {
        assertTrue("Native Gmail per-message delete remains explicitly permanent",
                MessageSelectionCapabilities.shouldLabelDeletePermanentlyMenu(
                        true, false, false, EntityFolder.INBOX));
        assertTrue("Graph More-menu action is the direct permanent-delete route",
                MessageSelectionCapabilities.shouldLabelDeletePermanentlyMenu(
                        false, true, false, EntityFolder.INBOX));
        assertTrue("Graph permanent-delete label is independent of source folder",
                MessageSelectionCapabilities.shouldLabelDeletePermanentlyMenu(
                        false, true, false, EntityFolder.TRASH));
        assertTrue("Graph permanent-delete label applies in a custom folder",
                MessageSelectionCapabilities.shouldLabelDeletePermanentlyMenu(
                        false, true, false, EntityFolder.USER));
        assertTrue("Zoho More-menu action uses the direct permanent-delete route",
                MessageSelectionCapabilities.shouldLabelDeletePermanentlyMenu(
                        false, false, true, EntityFolder.INBOX));
        assertTrue("Zoho direct permanent-delete label is independent of source folder",
                MessageSelectionCapabilities.shouldLabelDeletePermanentlyMenu(
                        false, false, true, EntityFolder.JUNK));
        assertTrue("Gmail Spam label policy remains unchanged",
                MessageSelectionCapabilities.shouldLabelDeletePermanentlyMenu(
                        true, false, false, EntityFolder.JUNK));
        assertFalse("Legacy IMAP Spam label remains unchanged",
                MessageSelectionCapabilities.shouldLabelDeletePermanentlyMenu(
                        false, false, false, EntityFolder.JUNK));
        assertFalse("Legacy Trash menu labels remain unchanged",
                MessageSelectionCapabilities.shouldLabelDeletePermanentlyMenu(
                        false, false, false, EntityFolder.TRASH));
    }

    @Test
    public void nativePermanentDeleteMoreActionIsHiddenOnlyInTrashAndSpam() {
        assertTrue(MessageSelectionCapabilities.shouldShowPermanentDeleteMoreAction(
                true, EntityFolder.INBOX));
        assertTrue(MessageSelectionCapabilities.shouldShowPermanentDeleteMoreAction(
                true, EntityFolder.USER));
        assertFalse(MessageSelectionCapabilities.shouldShowPermanentDeleteMoreAction(
                true, EntityFolder.TRASH));
        assertFalse(MessageSelectionCapabilities.shouldShowPermanentDeleteMoreAction(
                true, EntityFolder.JUNK));
        assertFalse(MessageSelectionCapabilities.shouldShowPermanentDeleteMoreAction(
                false, EntityFolder.INBOX));
    }

    @Test
    public void individualZohoDeleteIsPermanentOnlyFromTrashOrSpam() {
        assertTrue(MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                true, EntityFolder.TRASH));
        assertTrue(MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                true, EntityFolder.JUNK));
        assertFalse(MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                true, EntityFolder.INBOX));
        assertFalse(MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                true, EntityFolder.ARCHIVE));
        assertFalse(MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                true, EntityFolder.SENT));
        assertFalse("Graph remains outside the Zoho-specific individual route",
                MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                        false, EntityFolder.JUNK));
    }

    @Test
    public void graphMoveRequiresSameWritableGraphAccountAndFolder() {
        assertTrue(MessageSelectionCapabilities.canMoveGraphMessage(
                true, false, 7L, true, 7L, true, false, "folder-id"));
        assertFalse(MessageSelectionCapabilities.canMoveGraphMessage(
                true, true, 7L, true, 7L, true, false, "folder-id"));
        assertFalse(MessageSelectionCapabilities.canMoveGraphMessage(
                true, false, 7L, true, 8L, true, false, "folder-id"));
        assertFalse(MessageSelectionCapabilities.canMoveGraphMessage(
                true, false, 7L, true, 7L, true, true, "folder-id"));
        assertFalse(MessageSelectionCapabilities.canMoveGraphMessage(
                true, false, 7L, true, 7L, false, false, "folder-id"));
        assertFalse(MessageSelectionCapabilities.canMoveGraphMessage(
                true, false, 7L, true, 7L, true, false, ""));
    }

    @Test
    public void gmailSpamActionRequiresAValidSourceAndMappedTarget() {
        assertTrue(MessageSelectionCapabilities.canReportGmailSpam(
                true, true, true, true, false, false, false));
        assertFalse(MessageSelectionCapabilities.canReportGmailSpam(
                true, true, true, false, false, false, false));
        assertFalse(MessageSelectionCapabilities.canReportGmailSpam(
                true, true, true, true, true, false, false));
        assertFalse(MessageSelectionCapabilities.canReportGmailSpam(
                true, true, true, true, false, true, false));
        assertFalse(MessageSelectionCapabilities.canReportGmailSpam(
                true, true, true, true, false, false, true));
        assertFalse(MessageSelectionCapabilities.canReportGmailSpam(
                true, false, true, true, false, false, false));
        assertFalse(MessageSelectionCapabilities.canReportGmailSpam(
                true, true, false, true, false, false, false));
    }

    @Test
    public void gmailInboxActionUsesActiveViewRoleAndRejectsSentDraftAndAlreadyInboxCases() {
        assertTrue("A writable message in a custom Notifications view may return to Inbox",
                MessageSelectionCapabilities.canReturnGmailMessageToInbox(
                        true, true, true, false, false));
        assertFalse("Sent-only restrictions remain tied to the selected source view",
                MessageSelectionCapabilities.canReturnGmailMessageToInbox(
                        true, true, true, true, false));
        assertFalse("Gmail Drafts are not eligible for a MOVE-backed Inbox action",
                MessageSelectionCapabilities.canReturnGmailMessageToInbox(
                        true, false, true, false, false));
        assertFalse(MessageSelectionCapabilities.canReturnGmailMessageToInbox(
                true, true, true, false, true));
        assertFalse(MessageSelectionCapabilities.canReturnGmailMessageToInbox(
                true, true, false, false, false));
    }

    @Test
    public void importanceDispatchSupportsLegacyAndGmailButNotGraphOrZoho() {
        assertTrue(MessageSelectionCapabilities.canSetImportance(true, true, true, false, false));
        assertFalse(MessageSelectionCapabilities.canSetImportance(true, true, false, false, false));
        assertTrue(MessageSelectionCapabilities.canSetImportance(false, false, false, false, false));
        assertFalse(MessageSelectionCapabilities.canSetImportance(false, false, true, false, false));
        assertFalse(MessageSelectionCapabilities.canSetImportance(true, false, true, true, false));
        assertFalse(MessageSelectionCapabilities.canSetImportance(true, false, true, false, true));
    }

    @Test
    public void permanentDeletePlacementDependsOnProviderCapabilityAndTrashOrSpamContext() {
        assertTrue(MessageSelectionCapabilities.canDeletePermanently(
                true, false, false, false, false, false));
        assertTrue("Graph direct permanent deletion is available outside Trash/Spam",
                MessageSelectionCapabilities.canDeletePermanently(
                        true, false, true, false, false, false));
        assertFalse("Graph hides a second permanent-delete choice in Trash/Spam",
                MessageSelectionCapabilities.canDeletePermanently(
                        true, false, true, false, false, true));
        assertTrue("Zoho direct permanent deletion is available outside Trash/Spam",
                MessageSelectionCapabilities.canDeletePermanently(
                        true, false, false, true, true, false));
        assertFalse("Zoho hides a second permanent-delete choice in Trash/Spam",
                MessageSelectionCapabilities.canDeletePermanently(
                        true, false, false, true, true, true));
        assertFalse("Zoho provider write capability is still required",
                MessageSelectionCapabilities.canDeletePermanently(
                        true, false, false, true, false, false));
        assertTrue("Gmail direct permanent deletion is available outside Trash/Spam",
                MessageSelectionCapabilities.canDeletePermanently(
                        true, true, false, false, false, false));
        assertFalse("Gmail hides a separate permanent-delete choice in Trash/Spam",
                MessageSelectionCapabilities.canDeletePermanently(
                        true, true, false, false, false, true));
        assertFalse(MessageSelectionCapabilities.canDeletePermanently(
                false, false, true, false, false, false));
    }

    @Test
    public void copyToVisibilityHidesGraphWithoutChangingOtherProviderRules() {
        assertFalse("Native Graph Copy to is unsupported",
                MessageSelectionCapabilities.canShowCopyTo(
                        true, false, false, EntityAccount.TYPE_IMAP));
        assertFalse("Native Zoho Copy to remains hidden",
                MessageSelectionCapabilities.canShowCopyTo(
                        false, true, false, EntityAccount.TYPE_IMAP));
        assertFalse("Native Gmail Copy to remains hidden",
                MessageSelectionCapabilities.canShowCopyTo(
                        false, false, true, EntityAccount.TYPE_IMAP));
        assertTrue("Legacy IMAP Copy to remains visible",
                MessageSelectionCapabilities.canShowCopyTo(
                        false, false, false, EntityAccount.TYPE_IMAP));
        assertFalse("POP Copy to remains hidden",
                MessageSelectionCapabilities.canShowCopyTo(
                        false, false, false, EntityAccount.TYPE_POP));
    }

    @Test
    public void nativeMoreMenuHidesLegacyOnlyActionsButKeepsProviderSupportedRows() {
        assertFalse("Legacy-only controls are hidden for native Gmail, Graph and Zoho messages",
                MessageSelectionCapabilities.shouldShowLegacyOnlyMessageMoreAction(true));
        assertTrue("Legacy IMAP/POP behavior remains unchanged",
                MessageSelectionCapabilities.shouldShowLegacyOnlyMessageMoreAction(false));

        assertTrue("Supported native Gmail actions such as labels and importance remain visible",
                MessageSelectionCapabilities.shouldShowNativeMessageMoreAction(true, true));
        assertFalse("Unsupported native Graph/Zoho actions are hidden",
                MessageSelectionCapabilities.shouldShowNativeMessageMoreAction(true, false));
        assertTrue("Legacy visibility is preserved even when a legacy control is disabled",
                MessageSelectionCapabilities.shouldShowNativeMessageMoreAction(false, false));
    }

    @Test
    public void nativeMoreMenuKeepsGraphResyncAndRawSaveOnlyWhenSupported() {
        assertTrue("Graph message resync is routed through Graph folder synchronization",
                MessageSelectionCapabilities.shouldShowMessageMoreResync(true, true, true));
        assertFalse("Gmail and Zoho have no usable per-message resync callback",
                MessageSelectionCapabilities.shouldShowMessageMoreResync(true, false, true));
        assertTrue("Legacy IMAP/POP resync gates remain unchanged",
                MessageSelectionCapabilities.shouldShowMessageMoreResync(false, false, true));

        assertTrue("Native raw save is visible before its remote MIME is cached",
                MessageSelectionCapabilities.shouldShowMessageMoreRawSave(true, true));
        assertFalse("Native raw save remains hidden when there is no fetchable provider ID",
                MessageSelectionCapabilities.shouldShowMessageMoreRawSave(true, false));
        assertTrue("Legacy raw-save visibility is preserved",
                MessageSelectionCapabilities.shouldShowMessageMoreRawSave(false, false));
    }
}

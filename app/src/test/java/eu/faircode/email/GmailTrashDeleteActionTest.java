package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailTrashDeleteActionTest {
    @Test
    public void gmailTrashUsesPermanentLabelIconOperationAndMandatoryConfirmation() {
        GmailTrashDeleteAction.Presentation action = GmailTrashDeleteAction.resolve(
                true, true, true, false, true);

        assertTrue(action.visible);
        assertTrue(action.permanent);
        assertEquals(R.string.title_delete_permanently, action.label);
        assertEquals(R.drawable.twotone_delete_forever_24, action.icon);
        assertEquals(EntityOperation.GMAIL_PERMANENT_DELETE, action.operation);
        EntityAccount account = new EntityAccount();
        account.protocol = EntityAccount.TYPE_IMAP;
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        assertEquals("Confirmed Gmail Trash deletion routes to the REST permanent-delete worker",
                EntityOperation.GMAIL_PERMANENT_DELETE,
                GmailMailMutator.getDeleteOperation(account, action.permanent));
        assertTrue(action.requiresConfirmation);
        assertTrue("An explicit Gmail permanent route overrides the optional confirmation setting",
                MessageSelectionCapabilities.shouldShowPermanentDeleteConfirmation(
                        false, action.requiresConfirmation));
        assertFalse("Permanent Gmail deletion cannot use the remembered soft-delete skip",
                GmailMailMutator.shouldSkipSingleDeleteConfirmation(action.permanent, true));
        assertTrue(GmailTrashDeleteAction.isPermanentDeleteInTrash(
                true, true, true, EntityFolder.TRASH));
    }

    @Test
    public void gmailOutsideTrashRemainsAnOrdinaryMoveToTrashAction() {
        GmailTrashDeleteAction.Presentation action = GmailTrashDeleteAction.resolve(
                true, true, true, true, false);

        assertTrue(action.visible);
        assertFalse(action.permanent);
        assertEquals(R.string.title_trash, action.label);
        assertEquals(R.drawable.twotone_delete_24, action.icon);
        assertEquals(EntityOperation.MOVE, action.operation);
        assertFalse(action.requiresConfirmation);
        assertFalse(GmailTrashDeleteAction.isPermanentDeleteInTrash(
                true, true, true, EntityFolder.INBOX));
        assertFalse(MessageSelectionCapabilities.shouldShowPermanentDeleteConfirmation(
                false, action.requiresConfirmation));
    }

    @Test
    public void gmailSpamActionBarAlsoUsesDirectPermanentDelete() {
        GmailTrashDeleteAction.Presentation action = GmailTrashDeleteAction.resolve(
                true, true, true, true, true);
        assertTrue(action.visible);
        assertTrue(action.permanent);
        assertEquals(EntityOperation.GMAIL_PERMANENT_DELETE, action.operation);
        assertTrue(GmailTrashDeleteAction.isPermanentDeleteInTrashOrSpam(
                true, true, true, EntityFolder.JUNK));
    }

    @Test
    public void moreMenuPermanentDeleteIsOnlyAvailableOutsideTrashAndSpam() {
        assertFalse("Trash/Spam uses the action-bar permanent icon instead of a second menu item",
                GmailTrashDeleteAction.isPermanentDeleteMoreActionAvailable(
                        true, true, true));
        assertTrue("Gmail's message-scoped delete is available in ordinary folders, regardless of local folder read-only state",
                GmailTrashDeleteAction.isPermanentDeleteMoreActionAvailable(
                        true, true, false));
        assertFalse(GmailTrashDeleteAction.isPermanentDeleteMoreActionAvailable(
                true, false, false));
        assertFalse(GmailTrashDeleteAction.isPermanentDeleteMoreActionAvailable(
                false, true, false));
    }

    @Test
    public void draftsMissingGmailIdsAndNonwritableSourcesDoNotExposeTrashDelete() {
        assertFalse(GmailTrashDeleteAction.resolve(
                true, true, false, true, true).visible);
        assertFalse(GmailTrashDeleteAction.resolve(
                true, false, true, true, true).visible);
        assertFalse(GmailTrashDeleteAction.resolve(
                false, true, true, true, true).visible);
    }
}

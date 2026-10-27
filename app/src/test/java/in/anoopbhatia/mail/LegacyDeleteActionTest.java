package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LegacyDeleteActionTest {
    @Test
    public void imapTrashMovesToTrashOnlyWhenTheButtonQueuesAMove() {
        assertFalse("IMAP keeps a trash move in the inbox",
                LegacyDeleteAction.isPermanentDelete(
                        false, false, EntityFolder.INBOX, false));
        assertTrue("IMAP deletes for good when no move is queued",
                LegacyDeleteAction.isPermanentDelete(
                        false, false, EntityFolder.INBOX, true));
    }

    @Test
    public void imapTrashAndSpamDeletePermanently() {
        assertTrue(LegacyDeleteAction.isPermanentDelete(
                false, false, EntityFolder.TRASH, true));
        assertTrue(LegacyDeleteAction.isPermanentDelete(
                false, false, EntityFolder.JUNK, true));
    }

    @Test
    public void imapMessageWithoutATrashFolderDeletesPermanently() {
        assertTrue("A delete in place expunges, it must not claim to be a trash move",
                LegacyDeleteAction.isPermanentDelete(
                        false, false, EntityFolder.SENT, true));
        assertTrue(LegacyDeleteAction.isPermanentDelete(
                false, false, EntityFolder.OUTBOX, true));
    }

    @Test
    public void pop3InboxDeleteIsSoftOnlyWhenTheServerCopyIsKept() {
        assertFalse("POP3 delete from the inbox with leave_deleted only trashes the device copy",
                LegacyDeleteAction.isPermanentDelete(
                        true, true, EntityFolder.INBOX, true));
        assertTrue("POP3 delete from the inbox without leave_deleted removes the server copy",
                LegacyDeleteAction.isPermanentDelete(
                        true, false, EntityFolder.INBOX, true));
    }

    @Test
    public void pop3TrashAndDraftsDeletePermanently() {
        assertTrue("The local trash copy is erased, even with leave_deleted",
                LegacyDeleteAction.isPermanentDelete(
                        true, true, EntityFolder.TRASH, true));
        assertTrue(LegacyDeleteAction.isPermanentDelete(
                true, true, EntityFolder.DRAFTS, true));
    }

    @Test
    public void mixedPopThreadUsesTrashWordingUnlessEveryQueuedDeleteIsPermanent() {
        List<String> inboxAndTrash = Arrays.asList(EntityFolder.INBOX, EntityFolder.TRASH);
        List<String> trashAndInbox = Arrays.asList(EntityFolder.TRASH, EntityFolder.INBOX);

        assertFalse(LegacyDeleteAction.isPermanentDeleteForSelection(true, true, inboxAndTrash));
        assertFalse(LegacyDeleteAction.isPermanentDeleteForSelection(true, true, trashAndInbox));
        assertTrue(LegacyDeleteAction.isPermanentDeleteForSelection(true, false, inboxAndTrash));
        assertTrue(LegacyDeleteAction.isPermanentDeleteForSelection(false, false, inboxAndTrash));
    }

    @Test
    public void iconAndLabelFollowTheQueuedOperation() {
        assertEquals(R.drawable.twotone_delete_forever_24, LegacyDeleteAction.getIcon(true));
        assertEquals(R.drawable.twotone_delete_24, LegacyDeleteAction.getIcon(false));

        assertEquals(R.string.title_delete_permanently, LegacyDeleteAction.getLabel(true, false));
        assertEquals(R.string.title_delete_permanently, LegacyDeleteAction.getLabel(true, true));
        assertEquals(R.string.title_trash, LegacyDeleteAction.getLabel(false, false));
        assertEquals(R.string.title_trash_conversation, LegacyDeleteAction.getLabel(false, true));

        assertEquals(R.string.title_delete_permanently, LegacyDeleteAction.getDeleteMenuLabel(true));
        assertEquals(R.string.title_delete, LegacyDeleteAction.getDeleteMenuLabel(false));
    }
}

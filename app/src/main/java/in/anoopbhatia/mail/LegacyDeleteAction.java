package in.anoopbhatia.mail;

/**
 * Presentation policy for the message Delete/Trash button of the IMAP and POP3
 * providers, the legacy counterpart of {@link GmailTrashDeleteAction}.
 *
 * One button carries both functions: it moves the message to the trash folder, or it
 * deletes the message for good. Its icon and label therefore have to be derived from
 * the operation that is actually queued:
 *
 * - IMAP: moving to the trash folder is an {@link EntityOperation#MOVE}; a
 *   {@link EntityOperation#DELETE} marks the message \Deleted in the folder it lives
 *   in and expunges it, so anything that is not moved is deleted for good.
 * - POP3: {@link EntityOperation#queue} turns a delete from the inbox into a copy in
 *   the local trash folder and hides the device copy, so that delete is a soft delete
 *   (the server copy is kept too when "leave deleted messages on server" is set).
 *   From the local trash or drafts folder the same operation erases the message.
 */
final class LegacyDeleteAction {
    private LegacyDeleteAction() {
    }

    /**
     * @param pop           POP3 account (IMAP otherwise)
     * @param leaveDeleted  POP3 "leave deleted messages on server"
     * @param folderType    type of the folder the message is in
     * @param deleteAction  true when the button queues a delete instead of a move to trash
     * @return true when the button removes the message for good
     */
    static boolean isPermanentDelete(boolean pop, boolean leaveDeleted,
                                     String folderType, boolean deleteAction) {
        if (!deleteAction)
            return false;

        // Nothing left to move these from, so deleting them is always permanent.
        if (EntityFolder.TRASH.equals(folderType) ||
                EntityFolder.DRAFTS.equals(folderType) ||
                EntityFolder.OUTBOX.equals(folderType))
            return true;

        // IMAP delete expunges the message from the folder it lives in.
        if (!pop)
            return true;

        // POP3 delete from the inbox keeps the server copy when requested.
        return !leaveDeleted;
    }

    static boolean isPermanentDeleteForSelection(boolean pop, boolean leaveDeleted,
                                                 Iterable<String> folderTypes) {
        if (folderTypes == null)
            return false;

        boolean found = false;
        for (String folderType : folderTypes) {
            found = true;
            if (!isPermanentDelete(pop, leaveDeleted, folderType, true))
                return false;
        }
        return found;
    }

    static int getIcon(boolean permanent) {
        return (permanent ? R.drawable.twotone_delete_forever_24 : R.drawable.twotone_delete_24);
    }

    static int getLabel(boolean permanent, boolean conversation) {
        if (permanent)
            return R.string.title_delete_permanently;
        return (conversation ? R.string.title_trash_conversation : R.string.title_trash);
    }

    static int getDeleteMenuLabel(boolean permanent) {
        return (permanent ? R.string.title_delete_permanently : R.string.title_delete);
    }
}

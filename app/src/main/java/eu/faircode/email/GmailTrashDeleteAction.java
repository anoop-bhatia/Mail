package eu.faircode.email;

/** Presentation and routing policy for the Gmail REST message Trash button. */
final class GmailTrashDeleteAction {
    static final class Presentation {
        final boolean visible;
        final boolean permanent;
        final int label;
        final int icon;
        final String operation;
        final boolean requiresConfirmation;

        private Presentation(boolean visible, boolean permanent, int label, int icon,
                             String operation, boolean requiresConfirmation) {
            this.visible = visible;
            this.permanent = permanent;
            this.label = label;
            this.icon = icon;
            this.operation = operation;
            this.requiresConfirmation = requiresConfirmation;
        }
    }

    private GmailTrashDeleteAction() {
    }

    static Presentation resolve(boolean gmailApiMessage, boolean hasGmailId,
                                boolean writableNonDraft, boolean hasTrashTarget,
                                boolean inTrashOrSpam) {
        boolean eligible = (gmailApiMessage && hasGmailId && writableNonDraft);
        boolean permanent = (eligible && inTrashOrSpam);
        boolean visible = (eligible && (inTrashOrSpam || hasTrashTarget));
        return new Presentation(visible, permanent,
                (visible ? (permanent ? R.string.title_delete_permanently : R.string.title_trash) : 0),
                (visible ? (permanent ? R.drawable.twotone_delete_forever_24 : R.drawable.twotone_delete_24) : 0),
                (visible ? (permanent ? EntityOperation.GMAIL_PERMANENT_DELETE : EntityOperation.MOVE) : null),
                permanent);
    }

    static boolean isPermanentDeleteInTrash(boolean gmailApiMessage, boolean hasGmailId,
                                            boolean writableNonDraft, String folderType) {
        return (gmailApiMessage && hasGmailId && writableNonDraft &&
                EntityFolder.TRASH.equals(folderType));
    }

    static boolean isPermanentDeleteInTrashOrSpam(boolean gmailApiMessage, boolean hasGmailId,
                                                  boolean writableNonDraft, String folderType) {
        return (gmailApiMessage && hasGmailId && writableNonDraft &&
                MessageSelectionCapabilities.isTrashOrSpam(folderType));
    }

    static boolean isPermanentDeleteMoreActionAvailable(boolean gmailApiMessage,
                                                        boolean writableNonDraft,
                                                        boolean inTrashOrSpam) {
        // Gmail's DELETE is message-scoped and is available even when the local
        // label folder is marked read-only. Trash/Spam use the action-bar icon.
        return (gmailApiMessage && writableNonDraft && !inTrashOrSpam);
    }
}

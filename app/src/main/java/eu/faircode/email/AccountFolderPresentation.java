package eu.faircode.email;

/** Presentation-only names for folders shown by the Account Edit selectors. */
final class AccountFolderPresentation {
    private AccountFolderPresentation() {
    }

    static String getDisplayName(EntityAccount account, EntityFolder folder) {
        if (folder == null)
            return "";
        if (account == null || !account.isGmailApi() || folder.gmail_label_id == null)
            return folder.name;

        // Gmail API folder.name is an internal stable token for custom labels.
        // Render the API/localized label name without changing that token or the selected folder.
        String displayName = GmailLabelControl.getDisplayName(folder);
        return (displayName == null || displayName.length() == 0 ? folder.name : displayName);
    }
}

package eu.faircode.email;

/** Account-picker requirements for interactive Gmail API authorization. */
final class GmailAccountSelectionPolicy {
    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";

    private GmailAccountSelectionPolicy() {
    }

    static String[] getAllowedAccountTypes() {
        return new String[]{GOOGLE_ACCOUNT_TYPE};
    }

    static boolean alwaysPromptForAccount() {
        return true;
    }

    static boolean shouldUseBrowserOAuth(boolean longPress) {
        return longPress;
    }

    static boolean hasSelectedAccount(String accountName) {
        return accountName != null && accountName.trim().length() > 0;
    }

    static String requireSelectedAccount(String accountName) {
        String selected = (accountName == null ? null : accountName.trim());
        if (selected == null || selected.length() == 0)
            throw new IllegalArgumentException("Select a Google account to continue");
        return selected;
    }
}

package eu.faircode.email;

/** Presentation-only policy for account-list diagnostic details. */
final class AccountListPresentation {
    private AccountListPresentation() {
    }

    static String getProviderSuffix(EntityAccount account, boolean showDebugProvider) {
        if (account == null || account.isNativeRestApi() || !showDebugProvider || account.provider == null)
            return "";
        return " (" + account.provider + ")";
    }

    static String formatLastConnected(EntityAccount account, String lastConnected,
                                      String lastModified, boolean debugBuild) {
        String formatted = (lastConnected == null ? "-" : lastConnected);
        if (account == null || account.isNativeRestApi() || !debugBuild)
            return formatted;

        return formatted + "/" + (lastModified == null ? "-" : lastModified) +
                " " + account.poll_interval +
                "/" + account.keep_alive_ok +
                "/" + account.keep_alive_failed +
                "/" + account.keep_alive_succeeded;
    }

    static boolean showDebugId(boolean settings, boolean debugBuild, EntityAccount account) {
        return (settings && debugBuild && account != null && !account.isNativeRestApi());
    }
}

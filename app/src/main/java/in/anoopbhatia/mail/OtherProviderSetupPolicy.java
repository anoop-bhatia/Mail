package in.anoopbhatia.mail;

import java.util.Locale;

/** OAuth IMAP routing for the shared Other-provider setup, not native API setup. */
final class OtherProviderSetupPolicy {
    private OtherProviderSetupPolicy() {
    }

    static String getOAuthProviderId(String email) {
        if (email == null)
            return null;
        String address = email.trim();
        int at = address.lastIndexOf('@');
        if (at <= 0 || at == address.length() - 1 || address.indexOf('@') != at)
            return null;
        String domain = address.substring(at + 1).toLowerCase(Locale.ROOT);
        if ("gmail.com".equals(domain) || "googlemail.com".equals(domain))
            return "gmail";
        if (domain.matches("(?:outlook|hotmail|live)\\.[a-z]{2,}(?:\\.[a-z]{2,})?") ||
                "msn.com".equals(domain))
            return "microsoft";
        // Custom domains still use normal IMAP/POP provider discovery.
        return null;
    }
}

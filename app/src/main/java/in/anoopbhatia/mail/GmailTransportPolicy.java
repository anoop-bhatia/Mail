package in.anoopbhatia.mail;

/** Transport routing rules separating native Gmail API accounts from Gmail IMAP accounts. */
final class GmailTransportPolicy {
    private GmailTransportPolicy() {
    }

    static boolean shouldBlockGenericProtocol(String host, int auth, String password) {
        return EntityAccount.isGmailHost(host) &&
                ServiceAuthenticator.getEffectiveAuthType(auth, password) == ServiceAuthenticator.AUTH_TYPE_GMAIL;
    }

    static boolean shouldUseNativeApiSynchronization(EntityAccount account) {
        return account != null && account.isGmailApi();
    }

    static boolean shouldBlockLegacySmtp(EntityAccount account, EntityIdentity identity) {
        return account != null && account.isGmailApi() &&
                (identity == null || !identity.isGmailApi());
    }
}

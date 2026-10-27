package eu.faircode.email;

/** REST-only identity-editor boundaries; legacy SMTP identities retain their existing behavior. */
final class NativeRestIdentityPolicy {
    private NativeRestIdentityPolicy() {
    }

    static boolean isNativeRestIdentity(EntityIdentity identity, EntityAccount account) {
        return (identity == null
                ? AccountEditPolicy.isNativeRestApi(account)
                : identity.isNativeRestApi());
    }

    static boolean shouldMakeEmailReadOnly(EntityIdentity identity, boolean editingExisting) {
        return (editingExisting && identity != null && identity.isNativeRestApi());
    }

    static String getEmailForSave(EntityIdentity identity, boolean editingExisting, String requestedEmail) {
        return (shouldMakeEmailReadOnly(identity, editingExisting) ? identity.email : requestedEmail);
    }

    static boolean shouldValidateSmtp(EntityIdentity identity, EntityAccount account) {
        return !isNativeRestIdentity(identity, account);
    }

    static String getPasswordFieldValue(EntityIdentity identity, EntityAccount account) {
        if (isNativeRestIdentity(identity, account))
            return null;
        return (identity != null ? identity.password : (account == null ? null : account.password));
    }

    static int[] getNativeRestTransportViewIds() {
        return new int[]{
                R.id.tvProvider, R.id.spProvider,
                R.id.tvDomain, R.id.tvDomainHint, R.id.etDomain,
                R.id.btnAutoConfig, R.id.pbAutoConfig, R.id.cardServer,
                R.id.tvUser, R.id.etUser, R.id.tvPassword, R.id.tilPassword,
                R.id.tvPasswordStorage, R.id.cbLoginBeforeSend, R.id.tvLoginBeforeSendHint,
                R.id.btnCertificate, R.id.tvCertificate, R.id.tvRealm, R.id.etRealm,
                R.id.cbUseIp, R.id.tvUseIpHint, R.id.tvEhlo, R.id.etEhlo, R.id.ibEhlo,
                R.id.cbTrust
        };
    }

    static int[] getNativeRestSmtpAdvancedViewIds() {
        return new int[]{
                R.id.tvEnvelopeFrom, R.id.etEnvelopeFrom, R.id.tvEnvelopeFromHint,
                R.id.cbOctetMime
        };
    }
}

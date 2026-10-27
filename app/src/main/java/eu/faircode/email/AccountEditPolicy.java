package eu.faircode.email;

import java.util.List;

/** Account-edit visibility and persistence boundaries for native REST accounts. */
final class AccountEditPolicy {
    static final String NATIVE_GMAIL_API_PROVIDER_ID = GmailApi.PROVIDER_ID;
    static final String NATIVE_REST_PASSWORD_MASK = "********";

    private AccountEditPolicy() {
    }

    static boolean isNativeRestApi(EntityAccount account) {
        return account != null && account.isNativeRestApi();
    }

    static boolean shouldEnableProviderSelection(EntityAccount account) {
        return !isNativeRestApi(account);
    }

    static int getRelatedIdentityLabelResourceId(EntityAccount account, int identityCount) {
        if (!isNativeRestApi(account))
            return R.string.title_related_identity;
        return (shouldEditRelatedIdentity(account, identityCount)
                ? R.string.title_edit_related_identity : R.string.title_related_identity_api);
    }

    static boolean shouldEditRelatedIdentity(EntityAccount account, int identityCount) {
        return isNativeRestApi(account) && identityCount > 0;
    }

    static boolean shouldShowRelatedIdentityPicker(EntityAccount account, int identityCount) {
        return shouldShowRelatedIdentityPicker(isNativeRestApi(account), identityCount);
    }

    static boolean shouldShowRelatedIdentityPicker(boolean nativeRest, int identityCount) {
        return nativeRest && identityCount > 1;
    }

    static Long getSoleRelatedIdentityId(List<EntityIdentity> identities) {
        if (identities == null || identities.size() != 1)
            return null;
        return identities.get(0).id;
    }

    static String getNativeRestProviderId(EntityAccount account) {
        if (!isNativeRestApi(account))
            return null;
        if (account.isGmailApi())
            return NATIVE_GMAIL_API_PROVIDER_ID;
        if (account.isZohoApi())
            return ZohoOAuth.PROVIDER_ID;
        if (account.isGraphApi())
            return GraphOAuth.PROVIDER_ID;
        return null;
    }

    static int findNativeRestProviderPosition(List<EmailProvider> providers, EntityAccount account) {
        String id = getNativeRestProviderId(account);
        if (id == null || providers == null)
            return -1;
        for (int position = 0; position < providers.size(); position++) {
            EmailProvider provider = providers.get(position);
            if (provider != null && id.equals(provider.id))
                return position;
        }
        return -1;
    }

    static String getPasswordFieldValue(EntityAccount account) {
        return (account == null ? null
                : (isNativeRestApi(account) ? NATIVE_REST_PASSWORD_MASK : account.password));
    }

    static String getPasswordForSave(EntityAccount account, String requestedPassword) {
        return (isNativeRestApi(account) ? account.password : requestedPassword);
    }

    static boolean shouldEnablePasswordField(EntityAccount account, int authType) {
        return (!isNativeRestApi(account) && authType == ServiceAuthenticator.AUTH_TYPE_PASSWORD);
    }

    static Class<?> getNativeRestReauthorizationFragmentClass(EntityAccount account) {
        if (!isNativeRestApi(account))
            return null;
        if (account.isGmailApi())
            return FragmentGmailApi.class;
        if (account.isZohoApi() || account.isGraphApi())
            return FragmentOAuth.class;
        return null;
    }

    static boolean shouldSaveTransportSettings(EntityAccount account) {
        return !isNativeRestApi(account);
    }

    static boolean shouldShowSpecialFolderMappings(EntityAccount account) {
        // Native REST folder IDs are local row identifiers; users still map
        // those rows to FairEmail's special roles for unified views and actions.
        return true;
    }

    static boolean shouldShowNativeRestServerGroup(EntityAccount account) {
        return isNativeRestApi(account);
    }

    static boolean shouldShowNativeRestCalendarGroup(EntityAccount account, boolean playStoreRelease) {
        return isNativeRestApi(account) && !playStoreRelease;
    }

    static boolean shouldShowNativeRestAdvancedButton(EntityAccount account) {
        return isNativeRestApi(account);
    }

    static int[] getNativeRestTransportViewIds() {
        return new int[]{
                R.id.tvImap,
                R.id.cbDnsSec, R.id.tvDnsRemark,
                R.id.tvDomain, R.id.tvDomainHint, R.id.etDomain,
                R.id.btnAutoConfig, R.id.pbAutoConfig,
                R.id.tvHost, R.id.etHost,
                R.id.tvEncryption, R.id.rgEncryption,
                R.id.cbInsecure, R.id.tvInsecureRemark,
                R.id.cbDane, R.id.tvDaneRemark,
                R.id.tvPort, R.id.etPort
        };
    }

    static int[] getNativeRestCredentialViewIds() {
        return new int[]{
                R.id.tvCaseSensitive,
                R.id.tvAppPassword, R.id.tvPasswordStorage,
                R.id.btnCertificate, R.id.tvCertificate, R.id.tvRealm, R.id.etRealm,
                R.id.cbTrust, R.id.btnReauthorize
        };
    }

    static int[] getNativeRestProviderSelectionHiddenViewIds(EntityAccount account, int position) {
        if (!isNativeRestApi(account) || position <= 0)
            return new int[0];

        int[] transport = getNativeRestTransportViewIds();
        int[] credentials = getNativeRestCredentialViewIds();
        int[] hidden = new int[transport.length + credentials.length];
        System.arraycopy(transport, 0, hidden, 0, transport.length);
        System.arraycopy(credentials, 0, hidden, transport.length, credentials.length);
        return hidden;
    }

    interface NativeRestViewHider {
        void hide(int viewId);
    }

    static void hideNativeRestProviderSelectionViews(EntityAccount account, int position,
                                                      NativeRestViewHider hider) {
        if (hider == null)
            return;
        for (int viewId : getNativeRestProviderSelectionHiddenViewIds(account, position))
            hider.hide(viewId);
    }

    static int[] getNativeRestImapAdvancedViewIds() {
        return new int[]{
                R.id.cbNoop, R.id.tvNoopRemark,
                R.id.cbPartialFetch, R.id.tvPartialFetchRemark,
                R.id.cbRawFetch, R.id.tvRawFetchRemark,
                R.id.cbIgnoreSize, R.id.rgDate, R.id.tvDateRemark,
                R.id.cbUnicode
        };
    }
}

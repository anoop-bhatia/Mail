package eu.faircode.email;

/** Selects concise authorization introductions for the native Graph and Zoho forms. */
final class NativeApiSetupCopy {
    private NativeApiSetupCopy() {
    }

    static int getRationaleResource(String providerId, boolean graphMailApi) {
        if (graphMailApi)
            return R.string.title_setup_graph_api_rationale;
        if (ZohoOAuth.isProviderId(providerId))
            return R.string.title_setup_zoho_api_rationale;
        return 0;
    }

}

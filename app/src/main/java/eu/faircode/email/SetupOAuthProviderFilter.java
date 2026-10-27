package eu.faircode.email;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Menu policy for native API shortcuts and baseline generic OAuth choices. */
final class SetupOAuthProviderFilter {
    static final int GROUP_API = 0;
    static final int GROUP_PRIMARY = 1;
    static final int GROUP_ALTERNATE = 2;

    enum ApiChoice {
        GMAIL,
        ZOHO,
        GRAPH
    }

    private static final List<ApiChoice> API_ORDER = Collections.unmodifiableList(Arrays.asList(
            ApiChoice.GMAIL,
            ApiChoice.ZOHO,
            ApiChoice.GRAPH));

    private SetupOAuthProviderFilter() {
    }

    static List<ApiChoice> getApiOrder() {
        return API_ORDER;
    }

    static int getOAuthGroup(boolean alternate) {
        return (alternate ? GROUP_ALTERNATE : GROUP_PRIMARY);
    }

    static boolean isAllowed(String providerId) {
        if (providerId == null || providerId.trim().isEmpty())
            return false;

        // Zoho Mail API is added as a dedicated native API choice above the
        // divider, so do not add the same API provider again as generic OAuth.
        return !ZohoOAuth.isProviderId(providerId.trim());
    }

    static boolean isEligible(String providerId, boolean hasOAuth, boolean enabled,
                              boolean debugProvider, boolean debug,
                              String clientId, boolean alternate, boolean requestedAlternate) {
        return (hasOAuth &&
                (enabled || (debugProvider && debug)) &&
                clientId != null && !clientId.trim().isEmpty() &&
                isAllowed(providerId) &&
                alternate == requestedAlternate);
    }
}

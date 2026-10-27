package in.anoopbhatia.mail;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Menu policy for native API shortcuts and baseline generic OAuth choices. */
final class SetupOAuthProviderFilter {
    static final int GROUP_API = 0;
    static final int GROUP_PRIMARY = 1;
    static final int GROUP_ALTERNATE = 2;

    enum ApiChoice {
        GMAIL,
        GRAPH,
        ZOHO
    }

    private static final List<ApiChoice> API_ORDER = Collections.unmodifiableList(Arrays.asList(
            ApiChoice.GMAIL,
            ApiChoice.GRAPH,
            ApiChoice.ZOHO));

    private static final List<String> OAUTH_PROVIDER_IDS = Collections.unmodifiableList(Arrays.asList(
            "gmail", "microsoft"));

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

        String id = providerId.trim().toLowerCase(Locale.ROOT);
        return OAUTH_PROVIDER_IDS.contains(id);
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

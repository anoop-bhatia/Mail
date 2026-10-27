package in.anoopbhatia.mail;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SetupOAuthProviderFilterTest {
    @Test
    public void nativeApiChoicesLeadInRequestedOrderAndHaveTheirOwnDividerGroup() {
        assertEquals(Arrays.asList(
                        SetupOAuthProviderFilter.ApiChoice.GMAIL,
                        SetupOAuthProviderFilter.ApiChoice.GRAPH,
                        SetupOAuthProviderFilter.ApiChoice.ZOHO),
                SetupOAuthProviderFilter.getApiOrder());

        // Group dividers are enabled on the menu; changing from the API group
        // to the primary provider group places a divider immediately after Graph.
        assertFalse(SetupOAuthProviderFilter.GROUP_API == SetupOAuthProviderFilter.GROUP_PRIMARY);
        assertFalse(SetupOAuthProviderFilter.GROUP_PRIMARY == SetupOAuthProviderFilter.GROUP_ALTERNATE);
        assertEquals(SetupOAuthProviderFilter.GROUP_PRIMARY,
                SetupOAuthProviderFilter.getOAuthGroup(false));
        assertEquals(SetupOAuthProviderFilter.GROUP_ALTERNATE,
                SetupOAuthProviderFilter.getOAuthGroup(true));
    }

    @Test
    public void genericOAuthEligibilityUsesOnlyCanonicalProviders() {
        for (String providerId : Arrays.asList("gmail", "microsoft")) {
            assertTrue(providerId, eligible(providerId, true, true, false, false,
                    "client-id", false, false));
        }
        for (String providerId : Arrays.asList(
                "office365", "office365pcke", "outlookgraph", "outlook", "microsoft365", "netease"))
            assertFalse(providerId, SetupOAuthProviderFilter.isAllowed(providerId));
    }

    @Test
    public void legacyProviderIdsCannotReturnAsGenericOAuthMenuChoices() {
        for (String providerId : Arrays.asList(
                "yahoo", "yahoo2", "fastmail", "aol", "mailru", "yandex", "thundermail",
                "office365", "office365pcke", "outlookgraph", "outlook", "netease")) {
            assertFalse(providerId, SetupOAuthProviderFilter.isAllowed(providerId));
            assertFalse(providerId, eligible(providerId, true, true, false, false,
                    "client-id", false, false));
        }
    }

    @Test
    public void eligibilityPreservesProviderConfigurationDebugAndAlternateRules() {
        assertFalse(eligible("gmail", false, true, false, false,
                "client-id", false, false));
        assertFalse(eligible("gmail", true, false, false, false,
                "client-id", false, false));
        assertTrue(eligible("gmail", true, false, true, true,
                "client-id", false, false));
        assertFalse(eligible("gmail", true, false, true, false,
                "client-id", false, false));
        assertFalse(eligible("gmail", true, true, false, false,
                "", false, false));
        assertFalse(eligible("gmail", true, true, false, false,
                "client-id", true, false));
        assertTrue(eligible("microsoft", true, true, false, false,
                "client-id", true, true));
    }

    @Test
    public void onlyCanonicalOAuthIdsAreAllowed() {
        assertFalse(SetupOAuthProviderFilter.isAllowed(ZohoOAuth.PROVIDER_ID));
        assertFalse(SetupOAuthProviderFilter.isAllowed(null));
        assertFalse(SetupOAuthProviderFilter.isAllowed("  "));
        assertTrue(SetupOAuthProviderFilter.isAllowed("gmail"));
        assertTrue(SetupOAuthProviderFilter.isAllowed("MICROSOFT"));
        assertFalse(SetupOAuthProviderFilter.isAllowed("zoho"));
        assertFalse(SetupOAuthProviderFilter.isAllowed("outlookgraph"));
        assertFalse(SetupOAuthProviderFilter.isAllowed("netease"));
        assertFalse(SetupOAuthProviderFilter.isAllowed("yahoo"));
    }

    private static boolean eligible(String providerId, boolean hasOAuth, boolean enabled,
                                    boolean debugProvider, boolean debug, String clientId,
                                    boolean alternate, boolean requestedAlternate) {
        return SetupOAuthProviderFilter.isEligible(providerId, hasOAuth, enabled,
                debugProvider, debug, clientId, alternate, requestedAlternate);
    }
}

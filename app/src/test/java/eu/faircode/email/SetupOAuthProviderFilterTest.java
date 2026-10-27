package eu.faircode.email;

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
                        SetupOAuthProviderFilter.ApiChoice.ZOHO,
                        SetupOAuthProviderFilter.ApiChoice.GRAPH),
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
    public void genericOAuthEligibilityRestoresBaselineProvidersWithoutWhitelist() {
        for (String providerId : Arrays.asList(
                "gmail", "office365", "office365pcke", "outlookgraph", "outlook",
                "microsoft365", "yahoo", "fastmail", "yandex")) {
            assertTrue(providerId, eligible(providerId, true, true, false, false,
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
        assertTrue(eligible("outlookgraph", true, true, false, false,
                "client-id", true, true));
    }

    @Test
    public void nativeZohoApiIsNotDuplicatedButOtherOAuthIdsAreAllowed() {
        assertFalse(SetupOAuthProviderFilter.isAllowed(ZohoOAuth.PROVIDER_ID));
        assertFalse(SetupOAuthProviderFilter.isAllowed(null));
        assertFalse(SetupOAuthProviderFilter.isAllowed("  "));
        assertTrue(SetupOAuthProviderFilter.isAllowed("zoho"));
        assertTrue(SetupOAuthProviderFilter.isAllowed("outlookgraph"));
        assertTrue(SetupOAuthProviderFilter.isAllowed("yahoo"));
    }

    private static boolean eligible(String providerId, boolean hasOAuth, boolean enabled,
                                    boolean debugProvider, boolean debug, String clientId,
                                    boolean alternate, boolean requestedAlternate) {
        return SetupOAuthProviderFilter.isEligible(providerId, hasOAuth, enabled,
                debugProvider, debug, clientId, alternate, requestedAlternate);
    }
}

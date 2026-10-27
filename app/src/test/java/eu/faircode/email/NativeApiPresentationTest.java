package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NativeApiPresentationTest {
    @Test
    public void nativeProviderIdsAreStableAndSeparateFromLegacyProfiles() {
        assertEquals("gmailapi", GmailApi.PROVIDER_ID);
        assertEquals("zohomailapi", ZohoOAuth.PROVIDER_ID);
        assertEquals("msgraphapi", GraphOAuth.PROVIDER_ID);
        assertEquals("zoho-mail-api", ZohoOAuth.LEGACY_PROVIDER_ID);
        assertEquals("outlookgraph", GraphOAuth.LEGACY_PROVIDER_ID);
        assertTrue(ZohoOAuth.isProviderId(ZohoOAuth.LEGACY_PROVIDER_ID));
        assertTrue(GraphOAuth.isProviderId(GraphOAuth.LEGACY_PROVIDER_ID));
    }

    @Test
    public void providerCodesAreHiddenOnlyForNativeRestIdentityRows() {
        for (String provider : new String[]{"google", GmailApi.PROVIDER_ID, ZohoOAuth.PROVIDER_ID,
                GraphOAuth.PROVIDER_ID, GraphOAuth.LEGACY_PROVIDER_ID, ZohoOAuth.LEGACY_PROVIDER_ID}) {
            assertEquals("person@example.com", IdentityPresentation.formatEmail(
                    "person@example.com", provider, true, true));
            assertEquals("person@example.com", IdentityPresentation.formatEmail(
                    "person@example.com", provider, false, true));
        }
        assertEquals("person@example.com (legacy-provider)", IdentityPresentation.formatEmail(
                "person@example.com", "legacy-provider", true, false));
        assertEquals("person@example.com", IdentityPresentation.formatEmail(
                "person@example.com", "legacy-provider", false, false));
        assertEquals("person@example.com", IdentityPresentation.formatEmail(
                "person@example.com", null, true, false));
    }

    @Test
    public void newAccountNamesUseRequestedProviderPrefixesAndPreserveGmailConvention() {
        assertEquals("Gmail/person", NativeApiAccountNaming.forNewAccount(
                ServiceAuthenticator.AUTH_TYPE_GMAIL, "person@example.com"));
        assertEquals("Zoho Mail/person", NativeApiAccountNaming.forNewAccount(
                ServiceAuthenticator.AUTH_TYPE_ZOHO, "person@example.com"));
        assertEquals("Microsoft Mail/person", NativeApiAccountNaming.forNewAccount(
                ServiceAuthenticator.AUTH_TYPE_GRAPH_API, "person@example.com"));
        assertNull(NativeApiAccountNaming.forNewAccount(ServiceAuthenticator.AUTH_TYPE_PASSWORD,
                "person@example.com"));
        assertNull(NativeApiAccountNaming.forNewAccount(ServiceAuthenticator.AUTH_TYPE_GMAIL, null));
    }

    @Test
    public void unhandledToolbarUpUsesTheBackStackExitPathButOtherItemsDoNot() {
        assertTrue(ActivitySetupNavigationPolicy.shouldRouteToolbarUpToExit(android.R.id.home));
        assertFalse(ActivitySetupNavigationPolicy.shouldRouteToolbarUpToExit(R.id.menu_close));
        assertFalse(ActivitySetupNavigationPolicy.shouldRouteToolbarUpToExit(0));
    }
}

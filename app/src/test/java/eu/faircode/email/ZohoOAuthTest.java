package eu.faircode.email;

import org.junit.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ZohoOAuthTest {
    @Test
    public void requestedScopesIncludeApprovedZohoMutationPermissions() {
        assertTrue(ZohoOAuth.SCOPES.contains("ZohoMail.accounts.READ"));
        assertTrue(ZohoOAuth.SCOPES.contains("ZohoMail.folders.READ"));
        assertTrue(ZohoOAuth.SCOPES.contains("ZohoMail.messages.READ"));
        assertTrue(ZohoOAuth.SCOPES.contains("ZohoMail.messages.CREATE"));
        assertTrue(ZohoOAuth.SCOPES.contains("ZohoMail.messages.UPDATE"));
        assertTrue(ZohoOAuth.SCOPES.contains("ZohoMail.messages.DELETE"));
        assertTrue(ZohoOAuth.SCOPES.contains("ZohoMail.folders.UPDATE"));
    }

    @Test
    public void allowsOnlyKnownZohoRegionalServersAndApiHosts() {
        assertEquals("https://accounts.zoho.eu",
                ZohoOAuth.normalizeAccountsServer("https://accounts.zoho.eu/"));
        assertEquals("https://mail.zoho.eu",
                ZohoOAuth.getMailApiBase("https://www.zohoapis.eu"));
        assertRejected(() -> ZohoOAuth.normalizeAccountsServer("https://attacker.example"));
        assertRejected(() -> ZohoOAuth.normalizeAccountsServer("https://user@accounts.zoho.eu"));
        assertRejected(() -> ZohoOAuth.normalizeApiDomain("https://www.zohoapis.eu.evil.example"));
    }

    @Test
    public void acceptsEditableRedirectsHandledByTheAppCallbackScheme() {
        assertEquals(ZohoOAuth.DEFAULT_REDIRECT_URI,
                ZohoOAuth.validateRedirectUri(ZohoOAuth.DEFAULT_REDIRECT_URI));
        assertEquals("eu.faircode.email://custom-return/path",
                ZohoOAuth.validateRedirectUri("eu.faircode.email://custom-return/path"));
        assertRejected(() -> ZohoOAuth.validateRedirectUri("https://callback.example/oauth"));
        assertRejected(() -> ZohoOAuth.validateRedirectUri("EU.FAIRCODE.EMAIL://custom-return"));
        assertRejected(() -> ZohoOAuth.validateRedirectUri(""));
    }

    @Test
    public void rejectsMissingClientFieldsBeforeStartingAuthorization() {
        assertValidationMessage(() -> ZohoOAuth.validateClientId(""),
                "Enter the Zoho Client ID before authorizing.");
        assertValidationMessage(() -> ZohoOAuth.validateClientSecret("  "),
                "Enter the Zoho Client Secret before authorizing.");
    }

    @Test
    public void codeExchangeUsesAccountCredentialsAndPersistsThemWithRedirectUri() throws Exception {
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        ZohoOAuth.ConnectionFactory connections = url -> opened[0] = new ZohoTestHttpsConnection(
                url, 200, "{\"access_token\":\"access-fixture\",\"refresh_token\":\"refresh-fixture\"," +
                "\"api_domain\":\"https://www.zohoapis.eu\",\"expires_in\":3600}");
        String verifier = "A".repeat(43);
        String redirectUri = "eu.faircode.email://custom-return/path";
        ZohoState state = ZohoOAuth.exchange("code-fixture", verifier,
                redirectUri, "https://accounts.zoho.eu",
                "test-client-id", "unit-test-only-secret", connections);

        assertEquals("https://accounts.zoho.eu/oauth/v2/token", opened[0].getURL().toString());
        assertEquals("POST", opened[0].getRequestMethod());
        Map<String, String> form = parseForm(opened[0].requestBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals("authorization_code", form.get("grant_type"));
        assertEquals("test-client-id", form.get("client_id"));
        assertEquals("unit-test-only-secret", form.get("client_secret"));
        assertEquals("code-fixture", form.get("code"));
        assertEquals(verifier, form.get("code_verifier"));
        assertEquals(redirectUri, form.get("redirect_uri"));
        assertEquals("https://www.zohoapis.eu", state.apiDomain);
        assertEquals("test-client-id", state.clientId);
        assertEquals("unit-test-only-secret", state.clientSecret);
        assertEquals(redirectUri, state.redirectUri);
        ZohoState saved = ZohoState.jsonDeserialize(state.jsonSerializeString());
        assertEquals("test-client-id", saved.clientId);
        assertEquals("unit-test-only-secret", saved.clientSecret);
        assertEquals(redirectUri, saved.redirectUri);
        assertTrue(state.jsonSerializeString().contains("client_secret"));
    }

    @Test
    public void refreshRetainsMailboxAndExistingRefreshTokenWhenZohoOmitsIt() throws Exception {
        ZohoState current = ZohoState.authorized("old-access", "stable-refresh",
                "https://www.zohoapis.eu", "https://accounts.zoho.eu", 3600);
        current.accountId = "account-42";
        current.email = "test@example.invalid";
        current.displayName = "Test mailbox";
        current.clientId = "account-client-id";
        current.clientSecret = "account-only-secret";
        current.redirectUri = "eu.faircode.email://account-return";
        ZohoTestHttpsConnection[] opened = new ZohoTestHttpsConnection[1];
        ZohoOAuth.ConnectionFactory connections = url -> opened[0] = new ZohoTestHttpsConnection(
                url, 200, "{\"access_token\":\"new-access\",\"expires_in\":3600}");

        ZohoState refreshed = ZohoOAuth.refresh(current, connections);
        Map<String, String> form = parseForm(opened[0].requestBody.toString(StandardCharsets.UTF_8.name()));
        assertEquals("refresh_token", form.get("grant_type"));
        assertEquals("stable-refresh", form.get("refresh_token"));
        assertEquals("account-client-id", form.get("client_id"));
        assertEquals("account-only-secret", form.get("client_secret"));
        assertEquals("stable-refresh", refreshed.refreshToken);
        assertEquals("account-42", refreshed.accountId);
        assertEquals("test@example.invalid", refreshed.email);
        assertEquals("https://www.zohoapis.eu", refreshed.apiDomain);
        assertEquals("account-client-id", refreshed.clientId);
        assertEquals("account-only-secret", refreshed.clientSecret);
        assertEquals("eu.faircode.email://account-return", refreshed.redirectUri);
    }

    private static Map<String, String> parseForm(String form) throws Exception {
        Map<String, String> values = new HashMap<>();
        for (String pair : form.split("&")) {
            int equals = pair.indexOf('=');
            values.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8.name()),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8.name()));
        }
        return values;
    }

    private static void assertRejected(Runnable action) {
        try {
            action.run();
            fail("Unapproved endpoint must be rejected");
        } catch (IllegalArgumentException expected) {
            // Host/path validation rejects arbitrary token-server or API hosts.
        }
    }

    private static void assertValidationMessage(Runnable action, String message) {
        try {
            action.run();
            fail("Missing form value must be rejected");
        } catch (IllegalArgumentException expected) {
            assertEquals(message, expected.getMessage());
        }
    }
}

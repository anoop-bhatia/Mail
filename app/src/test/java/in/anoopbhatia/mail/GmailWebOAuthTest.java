package in.anoopbhatia.mail;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailWebOAuthTest {
    @Test
    public void nativeProfileLoadsOnlyItsDedicatedCredentialsAndReturnsIndependentValues() {
        ApiCredentials.Entry credentials = ApiCredentials.get("gmail");
        EmailProvider.OAuth profile = GmailWebOAuth.getProfile();
        assertEquals(credentials.clientId(BuildConfig.DEBUG), profile.clientId);
        assertEquals(credentials.redirectUri(BuildConfig.DEBUG), profile.redirectUri);
        assertEquals(credentials.authorizationEndpoint, profile.authorizationEndpoint);
        assertEquals(credentials.tokenEndpoint, profile.tokenEndpoint);
        assertEquals(credentials.scopes, java.util.Arrays.asList(profile.scopes));
        assertEquals(credentials.prompt, profile.prompt);
        assertEquals(credentials.parameters, profile.parameters);
        String expectedAccessType = credentials.parameters.get("access_type");
        profile.scopes[0] = "changed-by-caller";
        profile.parameters.put("access_type", "changed-by-caller");
        EmailProvider.OAuth fresh = GmailWebOAuth.getProfile();
        assertEquals(credentials.scopes, java.util.Arrays.asList(fresh.scopes));
        assertEquals(expectedAccessType, fresh.parameters.get("access_type"));
    }

    @Test
    public void browserOAuthUsesBuildSpecificGmailRestClientsAndKeepsRedirects() {
        ApiCredentials.Entry credentials = ApiCredentials.get("gmail");
        assertEquals(credentials.clientId(false), GmailWebOAuth.getClientId(false));
        assertEquals(credentials.clientId(true), GmailWebOAuth.getClientId(true));
        assertEquals(credentials.redirectUri(true),
                GmailWebOAuth.getRedirectUri(true, credentials.redirectUri(false)));
        assertEquals(credentials.redirectUri(false),
                GmailWebOAuth.getRedirectUri(false, credentials.redirectUri(false)));
    }

    @Test
    public void refreshUsesTheSamePublicClientAndPersistsRotatedRefreshToken() throws Exception {
        FakeConnection connection = new FakeConnection(
                "{\"access_token\":\"new-access\",\"refresh_token\":\"rotated-refresh\"}");
        GmailWebOAuth.RefreshResult result = GmailWebOAuth.refresh(
                GmailWebOAuth.REST_BROWSER_CLIENT_ID, "old refresh+token", url -> {
                    assertEquals(GmailWebOAuth.TOKEN_ENDPOINT, url.toString());
                    return connection;
                });

        assertEquals("new-access", result.accessToken);
        assertEquals("rotated-refresh", result.refreshToken);
        String request = connection.request.toString(StandardCharsets.UTF_8.name());
        assertTrue(request.contains("grant_type=refresh_token"));
        assertTrue(request.contains("client_id=" + GmailWebOAuth.REST_BROWSER_CLIENT_ID));
        assertTrue(request.contains("refresh_token=old+refresh%2Btoken"));
    }

    @Test
    public void refreshKeepsPriorRefreshTokenWhenGoogleDoesNotRotateIt() throws Exception {
        FakeConnection connection = new FakeConnection("{\"access_token\":\"new-access\"}");
        GmailWebOAuth.RefreshResult result = GmailWebOAuth.refresh(
                GmailWebOAuth.REST_BROWSER_CLIENT_ID, "stable-refresh", url -> connection);
        assertEquals("stable-refresh", result.refreshToken);
    }

    @Test
    public void longPressUsesGmailWebOAuthWithoutReplacingTapQuickSetup() throws Exception {
        String setup = new String(Files.readAllBytes(Paths.get(
                "src/main/java/in/anoopbhatia/mail/FragmentGmailApi.java")), StandardCharsets.UTF_8);
        String debugManifest = new String(Files.readAllBytes(Paths.get(
                "src/debug/AndroidManifest.xml")), StandardCharsets.UTF_8);

        assertTrue(setup.contains("btnSelect.setOnClickListener(v -> onSelect(false))"));
        assertTrue(setup.contains("btnSelect.setOnLongClickListener"));
        assertTrue(setup.contains("onSelect(true)"));
        assertTrue(setup.contains("launchActivityForResult(chooser, REQUEST_GMAIL_ACCOUNT_SELECTION)"));
        assertTrue(setup.contains("authorizeWithGoogleWeb()"));
        assertFalse(setup.contains("shouldFallbackToBrowserOAuth"));
        assertTrue(setup.contains("EmailProvider.OAuth oauth = GmailWebOAuth.getProfile();"));
        assertFalse(setup.contains("EmailProvider.getProvider(getContext(), \"gmail\")"));
        assertTrue(setup.contains("String clientId = GmailWebOAuth.getClientId(BuildConfig.DEBUG);"));
        assertTrue(setup.contains("GmailScopes.getRequested()"));
        assertTrue(setup.contains("GmailState.authorizedWeb"));
        // The debug manifest routes the custom scheme through a placeholder that the build fills
        // from providers.xml debugRedirectUri, so the literal scheme is no longer in the manifest.
        assertTrue(debugManifest.contains("android:scheme=\"${gmailRedirectScheme}\""));
        ApiCredentials.Entry credentials = ApiCredentials.get("gmail");
        assertEquals(credentials.redirectUri(true), GmailWebOAuth.DEBUG_REDIRECT_URI);
        assertEquals(credentials.clientId(false), GmailWebOAuth.getClientId(false));
        assertEquals(credentials.redirectUri(true),
                GmailWebOAuth.getRedirectUri(true, credentials.redirectUri(false)));
        assertEquals(credentials.redirectUri(false),
                GmailWebOAuth.getRedirectUri(false, credentials.redirectUri(false)));
    }

    private static final class FakeConnection extends HttpURLConnection {
        final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final byte[] response;

        FakeConnection(String response) throws Exception {
            super(new URL(GmailWebOAuth.TOKEN_ENDPOINT));
            this.response = response.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public OutputStream getOutputStream() {
            return request;
        }

        @Override
        public int getResponseCode() {
            return HTTP_OK;
        }

        @Override
        public ByteArrayInputStream getInputStream() {
            return new ByteArrayInputStream(response);
        }
    }
}

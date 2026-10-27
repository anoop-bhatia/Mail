package eu.faircode.email;

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
    public void browserOAuthUsesDedicatedGmailRestClientAndKeepsBuildSpecificRedirects() {
        assertEquals("803253368361-hr8kelm53hqodj7c6brdjeb2ctn5jg3p.apps.googleusercontent.com",
                GmailWebOAuth.getClientId());
        assertEquals("eu.faircode.email.debug:/",
                GmailWebOAuth.getRedirectUri(true, "eu.faircode.email:/"));
        assertEquals("eu.faircode.email:/",
                GmailWebOAuth.getRedirectUri(false, "eu.faircode.email:/"));
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
                "src/main/java/eu/faircode/email/FragmentGmailApi.java")), StandardCharsets.UTF_8);
        String debugManifest = new String(Files.readAllBytes(Paths.get(
                "src/debug/AndroidManifest.xml")), StandardCharsets.UTF_8);
        String providers = new String(Files.readAllBytes(Paths.get(
                "src/main/res/xml/providers.xml")), StandardCharsets.UTF_8);

        assertTrue(setup.contains("btnSelect.setOnClickListener(v -> onSelect(false))"));
        assertTrue(setup.contains("btnSelect.setOnLongClickListener"));
        assertTrue(setup.contains("onSelect(true)"));
        assertTrue(setup.contains("startActivityForResult(chooser, REQUEST_GMAIL_ACCOUNT_SELECTION)"));
        assertTrue(setup.contains("authorizeWithGoogleWeb()"));
        assertFalse(setup.contains("shouldFallbackToBrowserOAuth"));
        assertTrue(setup.contains("String clientId = GmailWebOAuth.getClientId();"));
        assertTrue(setup.contains("GmailScopes.getRequested()"));
        assertTrue(setup.contains("GmailState.authorizedWeb"));
        assertTrue(debugManifest.contains("android:scheme=\"eu.faircode.email.debug\""));
        assertTrue(providers.contains("clientId=\"803253368361-11ias0ee6bqhvdi4f21fs1lh7fsb0il2.apps.googleusercontent.com\""));
        assertTrue(providers.contains("redirectUri=\"eu.faircode.email:/\""));
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

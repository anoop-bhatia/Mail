package eu.faircode.email;

import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Browser-based Gmail OAuth helpers for the native Gmail REST account flow. */
final class GmailWebOAuth {
    static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    static final String REST_BROWSER_CLIENT_ID =
            "803253368361-hr8kelm53hqodj7c6brdjeb2ctn5jg3p.apps.googleusercontent.com";
    static final String DEBUG_REDIRECT_URI = "eu.faircode.email.debug:/";
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_RESPONSE_BYTES = 65536;

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    static final class RefreshResult {
        final String accessToken;
        final String refreshToken;

        RefreshResult(String accessToken, String refreshToken) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
        }
    }

    private GmailWebOAuth() {
    }

    static String getClientId() {
        return REST_BROWSER_CLIENT_ID;
    }

    static String getRedirectUri(boolean debug, String configuredRedirectUri) {
        if (debug)
            return DEBUG_REDIRECT_URI;
        if (isEmpty(configuredRedirectUri))
            throw new IllegalArgumentException("The FairEmail Gmail OAuth redirect URI is not configured");
        return configuredRedirectUri;
    }

    static RefreshResult refresh(String clientId, String refreshToken) throws IOException {
        return refresh(clientId, refreshToken, url -> (HttpURLConnection) url.openConnection());
    }

    static RefreshResult refresh(String clientId, String refreshToken, ConnectionFactory factory)
            throws IOException {
        if (isEmpty(clientId) || isEmpty(refreshToken))
            throw new IOException("Gmail browser authorization is missing its refresh credentials");

        HttpURLConnection connection = null;
        try {
            URL url = new URL(TOKEN_ENDPOINT);
            connection = factory.open(url);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");

            String form = "grant_type=refresh_token" +
                    "&client_id=" + formEncode(clientId) +
                    "&refresh_token=" + formEncode(refreshToken);
            byte[] request = form.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(request.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request);
            }

            int status = connection.getResponseCode();
            InputStream stream = (status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream());
            String body = readLimited(stream);
            JSONObject response;
            try {
                response = new JSONObject(body);
            } catch (JSONException ex) {
                throw new IOException("Google returned an invalid Gmail token response (HTTP " + status + ")", ex);
            }

            if (status < 200 || status >= 300) {
                String error = response.optString("error", "");
                if ("invalid_grant".equals(error) || "invalid_client".equals(error))
                    throw new GmailAuthorizationRequiredException(
                            "Gmail access needs your approval; choose Re-authorize Gmail to continue");
                throw new IOException("Google Gmail token refresh failed (HTTP " + status +
                        (isEmpty(error) ? "" : ", " + error) + ")");
            }

            String accessToken = response.optString("access_token", null);
            if (isEmpty(accessToken))
                throw new IOException("Google returned no Gmail access token during refresh");
            String rotated = response.optString("refresh_token", null);
            if (isEmpty(rotated))
                rotated = refreshToken;
            return new RefreshResult(accessToken, rotated);
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    private static String formEncode(String value) throws IOException {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ex) {
            throw new IOException("Unable to encode Gmail OAuth refresh request", ex);
        }
    }

    private static boolean isEmpty(String value) {
        return (value == null || value.trim().isEmpty());
    }

    private static String readLimited(InputStream stream) throws IOException {
        if (stream == null)
            return "";
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int count;
            while ((count = input.read(buffer)) >= 0) {
                total += count;
                if (total > MAX_RESPONSE_BYTES)
                    throw new IOException("Google returned an oversized Gmail token response");
                output.write(buffer, 0, count);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}

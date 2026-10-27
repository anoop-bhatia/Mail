package eu.faircode.email;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/** Zoho Mail Authorization Code + PKCE token exchange and refresh. */
final class ZohoOAuth {
    static final String PROVIDER_ID = "zohomailapi";
    static final String LEGACY_PROVIDER_ID = "zoho-mail-api";
    static final String DEFAULT_REDIRECT_URI = "eu.faircode.email://oauth2redirect";
    static final String PRIVACY_URI = "https://www.zoho.com/privacy.html";
    static final String SCOPES = "ZohoMail.accounts.READ,ZohoMail.folders.READ," +
            "ZohoMail.messages.READ,ZohoMail.messages.CREATE,ZohoMail.messages.UPDATE," +
            "ZohoMail.messages.DELETE,ZohoMail.folders.UPDATE";
    static final Map<String, String> LOCATION_ACCOUNTS_SERVERS;

    private static final Map<String, String> API_TO_MAIL_HOST;
    private static final int TIMEOUT_MS = 20000;
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;

    interface ConnectionFactory {
        HttpsURLConnection open(URL url) throws IOException;
    }

    static {
        Map<String, String> locations = new HashMap<>();
        locations.put("us", "https://accounts.zoho.com");
        locations.put("eu", "https://accounts.zoho.eu");
        locations.put("in", "https://accounts.zoho.in");
        locations.put("au", "https://accounts.zoho.com.au");
        locations.put("jp", "https://accounts.zoho.jp");
        locations.put("ca", "https://accounts.zohocloud.ca");
        locations.put("sa", "https://accounts.zoho.sa");
        locations.put("uk", "https://accounts.zoho.uk");
        locations.put("cn", "https://accounts.zoho.com.cn");
        LOCATION_ACCOUNTS_SERVERS = Collections.unmodifiableMap(locations);

        Map<String, String> domains = new HashMap<>();
        domains.put("www.zohoapis.com", "mail.zoho.com");
        domains.put("www.zohoapis.eu", "mail.zoho.eu");
        domains.put("www.zohoapis.in", "mail.zoho.in");
        domains.put("www.zohoapis.com.au", "mail.zoho.com.au");
        domains.put("www.zohoapis.jp", "mail.zoho.jp");
        domains.put("www.zohoapis.ca", "mail.zohocloud.ca");
        domains.put("www.zohoapis.sa", "mail.zoho.sa");
        domains.put("www.zohoapis.uk", "mail.zoho.uk");
        domains.put("www.zohoapis.com.cn", "mail.zoho.com.cn");
        domains.put("mail.zoho.com", "mail.zoho.com");
        domains.put("mail.zoho.eu", "mail.zoho.eu");
        domains.put("mail.zoho.in", "mail.zoho.in");
        domains.put("mail.zoho.com.au", "mail.zoho.com.au");
        domains.put("mail.zoho.jp", "mail.zoho.jp");
        domains.put("mail.zohocloud.ca", "mail.zohocloud.ca");
        domains.put("mail.zoho.sa", "mail.zoho.sa");
        domains.put("mail.zoho.uk", "mail.zoho.uk");
        domains.put("mail.zoho.com.cn", "mail.zoho.com.cn");
        API_TO_MAIL_HOST = Collections.unmodifiableMap(domains);
    }

    private ZohoOAuth() {
    }

    static boolean isProviderId(String id) {
        return PROVIDER_ID.equals(id) || LEGACY_PROVIDER_ID.equals(id);
    }

    static EmailProvider createProvider() {
        EmailProvider provider = new EmailProvider();
        provider.id = PROVIDER_ID;
        provider.name = "Zoho Mail API";
        provider.description = "Zoho Mail API";
        provider.enabled = true;
        provider.order = Integer.MAX_VALUE;
        provider.link = "https://www.zoho.com/mail/help/imap-access.html";
        provider.imap.host = "imap.zoho.com";
        provider.imap.port = 993;
        provider.imap.starttls = false;
        provider.smtp.host = "smtp.zoho.com";
        provider.smtp.port = 465;
        provider.smtp.starttls = false;

        EmailProvider.OAuth oauth = new EmailProvider.OAuth();
        oauth.enabled = true;
        oauth.askAccount = true;
        oauth.clientId = "";
        oauth.scopes = SCOPES.split(",");
        oauth.authorizationEndpoint = getAuthorizationEndpoint();
        oauth.tokenEndpoint = getTokenEndpoint();
        oauth.redirectUri = DEFAULT_REDIRECT_URI;
        oauth.privacy = PRIVACY_URI;
        oauth.prompt = "consent";
        oauth.parameters = new HashMap<>();
        oauth.parameters.put("access_type", "offline");
        provider.oauth = oauth;
        return provider;
    }

    private static ConnectionFactory defaultConnections() {
        return url -> (HttpsURLConnection) url.openConnection();
    }

    static String getAuthorizationEndpoint() {
        return normalizeAccountsServer(BuildConfig.ZOHO_AUTHORIZATION_SERVER) + "/oauth/v2/auth";
    }

    static String getTokenEndpoint() {
        return normalizeAccountsServer(BuildConfig.ZOHO_AUTHORIZATION_SERVER) + "/oauth/v2/token";
    }

    static String validateClientId(String value) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Enter the Zoho Client ID before authorizing.");
        return value.trim();
    }

    static String validateClientSecret(String value) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Enter the Zoho Client Secret before authorizing.");
        return value.trim();
    }

    static String validateRedirectUri(String value) {
        if (value == null || value.trim().isEmpty())
            throw new IllegalArgumentException("Enter the Zoho Redirect URI before authorizing.");
        String redirectUri = value.trim();
        URI uri = parseUri(redirectUri, "Enter a valid Zoho Redirect URI.");
        String applicationId = BuildConfig.APPLICATION_ID;
        String callbackScheme = (applicationId.endsWith(".debug")
                ? applicationId.substring(0, applicationId.length() - ".debug".length())
                : applicationId);
        if (!uri.isAbsolute() || uri.getScheme() == null ||
                !callbackScheme.equals(uri.getScheme()) ||
                uri.getRawUserInfo() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Zoho Redirect URI must use this app's callback scheme: " + callbackScheme);
        return redirectUri;
    }

    static String normalizeAccountsServer(String value) {
        if (value == null)
            throw new IllegalArgumentException("Missing Zoho accounts server");
        URI uri = parseUri(value, "Unsupported Zoho accounts server");
        String host = (uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null ||
                uri.getPort() != -1 || (uri.getPath() != null && !uri.getPath().isEmpty() &&
                !"/".equals(uri.getPath())) || uri.getQuery() != null || uri.getFragment() != null ||
                !LOCATION_ACCOUNTS_SERVERS.containsValue("https://" + host))
            throw new IllegalArgumentException("Unsupported Zoho accounts server");
        return "https://" + host;
    }

    static String getAccountsServer(Map<String, String> authorizationParameters) {
        if (authorizationParameters != null) {
            String server = authorizationParameters.get("accounts-server");
            if (server == null)
                server = authorizationParameters.get("accounts_server");
            if (server != null && !server.isEmpty())
                return normalizeAccountsServer(server);
            String location = authorizationParameters.get("location");
            if (location != null) {
                String mapped = LOCATION_ACCOUNTS_SERVERS.get(location.toLowerCase(Locale.ROOT));
                if (mapped == null)
                    throw new IllegalArgumentException("Unsupported Zoho data-center location");
                return mapped;
            }
        }
        return normalizeAccountsServer(BuildConfig.ZOHO_AUTHORIZATION_SERVER);
    }

    static String normalizeApiDomain(String value) {
        if (value == null)
            throw new IllegalArgumentException("Missing Zoho API domain");
        URI uri = parseUri(value, "Unsupported Zoho API domain");
        String host = (uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT));
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null ||
                uri.getPort() != -1 || (uri.getPath() != null && !uri.getPath().isEmpty() &&
                !"/".equals(uri.getPath())) || uri.getQuery() != null || uri.getFragment() != null ||
                !API_TO_MAIL_HOST.containsKey(host))
            throw new IllegalArgumentException("Unsupported Zoho API domain");
        return "https://" + host;
    }

    static String getMailApiBase(String apiDomain) {
        String normalized = normalizeApiDomain(apiDomain);
        String host = parseUri(normalized, "Unsupported Zoho API domain").getHost().toLowerCase(Locale.ROOT);
        return "https://" + API_TO_MAIL_HOST.get(host);
    }

    static ZohoState exchange(Context context, String code, String codeVerifier,
                              String redirectUri, String accountsServer,
                              String clientId, String clientSecret)
            throws IOException, JSONException {
        return exchange(code, codeVerifier, redirectUri, accountsServer,
                clientId, clientSecret, defaultConnections());
    }

    static ZohoState exchange(String code, String codeVerifier, String redirectUri,
                              String accountsServer, String clientId, String clientSecret,
                              ConnectionFactory connections) throws IOException, JSONException {
        clientId = validateClientId(clientId);
        clientSecret = validateClientSecret(clientSecret);
        if (code == null || code.isEmpty() || code.length() > 4096 ||
                codeVerifier == null || !codeVerifier.matches("[A-Za-z0-9._~-]{43,128}"))
            throw new IOException("Invalid Zoho authorization-code response");
        redirectUri = validateRedirectUri(redirectUri);
        String server = normalizeAccountsServer(accountsServer);
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("scope", SCOPES);
        form.put("code_verifier", codeVerifier);
        JSONObject response = request(server + "/oauth/v2/token", form, connections);
        ZohoState state = ZohoState.authorized(response.optString("access_token", null),
                response.optString("refresh_token", null),
                response.optString("api_domain", null), server,
                response.optLong("expires_in", 0));
        state.clientId = clientId;
        state.clientSecret = clientSecret;
        state.redirectUri = redirectUri;
        return state;
    }

    static ZohoState refresh(Context context, ZohoState current) throws IOException, JSONException {
        return refresh(current, defaultConnections());
    }

    static ZohoState refresh(ZohoState current, ConnectionFactory connections) throws IOException, JSONException {
        if (current == null)
            throw new IOException("Zoho requires account reauthorization");
        String clientId;
        String clientSecret;
        try {
            clientId = validateClientId(current.clientId);
            clientSecret = validateClientSecret(current.clientSecret);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Zoho account credentials are missing; reauthorize this account.");
        }
        if (current.refreshToken == null || current.refreshToken.isEmpty())
            throw new IOException("Zoho requires account reauthorization");
        String server = normalizeAccountsServer(current.accountsServer);
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("refresh_token", current.refreshToken);
        JSONObject response = request(server + "/oauth/v2/token", form, connections);
        String refreshedToken = response.optString("refresh_token", current.refreshToken);
        String apiDomain = response.optString("api_domain", current.apiDomain);
        ZohoState state = ZohoState.authorized(response.optString("access_token", null),
                refreshedToken, apiDomain, server, response.optLong("expires_in", 0));
        state.accountId = current.accountId;
        state.email = current.email;
        state.displayName = current.displayName;
        state.clientId = clientId;
        state.clientSecret = clientSecret;
        state.redirectUri = current.redirectUri;
        return state;
    }

    private static JSONObject request(String endpoint, Map<String, String> values,
                                      ConnectionFactory connections)
            throws IOException, JSONException {
        HttpsURLConnection connection = null;
        try {
            connection = connections.open(new URL(endpoint));
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
            byte[] body = encodeForm(values).getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(body);
            }
            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK)
                throw new IOException("Zoho OAuth token request failed (HTTP " + status + ")");
            JSONObject json;
            try (InputStream input = connection.getInputStream()) {
                try {
                    json = new JSONObject(readBounded(input));
                } catch (JSONException ex) {
                    throw new IOException("Zoho OAuth returned an invalid token response");
                }
            }
            if (json.has("error"))
                throw new IOException("Zoho OAuth token request was rejected");
            return json;
        } finally {
            if (connection != null)
                connection.disconnect();
        }
    }

    private static String encodeForm(Map<String, String> values) throws IOException {
        StringBuilder encoded = new StringBuilder();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (encoded.length() > 0)
                encoded.append('&');
            encoded.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8.name())).append('=')
                    .append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8.name()));
        }
        return encoded.toString();
    }

    private static URI parseUri(String value, String error) {
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            throw new IllegalArgumentException(error);
        }
    }

    private static String readBounded(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        for (int length = input.read(buffer); length != -1; length = input.read(buffer)) {
            total += length;
            if (total > MAX_RESPONSE_BYTES)
                throw new IOException("Zoho OAuth token response is too large");
            output.write(buffer, 0, length);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}

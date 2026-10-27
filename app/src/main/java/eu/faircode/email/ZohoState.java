package eu.faircode.email;

import org.json.JSONException;
import org.json.JSONObject;

/** Zoho Mail OAuth configuration, token, and regional mailbox state for one local account. */
final class ZohoState {
    static final int VERSION = 1;
    static final long REFRESH_SKEW_MS = 5 * 60 * 1000L;

    String accessToken;
    String refreshToken;
    String apiDomain;
    String accountsServer;
    String accountId;
    String email;
    String displayName;
    String clientId;
    String clientSecret;
    String redirectUri;
    long expiresAt;

    static ZohoState authorized(String accessToken, String refreshToken, String apiDomain,
                                String accountsServer, long expiresIn) {
        if (accessToken == null || accessToken.trim().isEmpty())
            throw new IllegalArgumentException("Missing Zoho access token");
        if (refreshToken == null || refreshToken.trim().isEmpty())
            throw new IllegalArgumentException("Missing Zoho refresh token");
        if (expiresIn <= 0 || expiresIn > 86400)
            throw new IllegalArgumentException("Invalid Zoho token lifetime");
        ZohoState state = new ZohoState();
        state.accessToken = accessToken;
        state.refreshToken = refreshToken;
        state.apiDomain = ZohoOAuth.normalizeApiDomain(apiDomain);
        state.accountsServer = ZohoOAuth.normalizeAccountsServer(accountsServer);
        state.expiresAt = System.currentTimeMillis() + expiresIn * 1000L;
        return state;
    }

    static boolean isApiState(String serialized) {
        if (serialized == null || serialized.trim().isEmpty())
            return false;
        try {
            return (new JSONObject(serialized).optInt("zoho_version", -1) == VERSION);
        } catch (JSONException ex) {
            return false;
        }
    }

    static ZohoState jsonDeserialize(String serialized) throws JSONException {
        if (!isApiState(serialized))
            throw new JSONException("Not a Zoho Mail REST token state");
        JSONObject json = new JSONObject(serialized);
        ZohoState state = new ZohoState();
        state.accessToken = json.optString("access_token", null);
        state.refreshToken = json.optString("refresh_token", null);
        state.apiDomain = ZohoOAuth.normalizeApiDomain(json.optString("api_domain", null));
        state.accountsServer = ZohoOAuth.normalizeAccountsServer(
                json.optString("accounts_server", null));
        state.accountId = emptyToNull(json.optString("account_id", null));
        state.email = emptyToNull(json.optString("email", null));
        state.displayName = emptyToNull(json.optString("display_name", null));
        state.clientId = emptyToNull(json.optString("client_id", null));
        state.clientSecret = emptyToNull(json.optString("client_secret", null));
        state.redirectUri = emptyToNull(json.optString("redirect_uri", null));
        state.expiresAt = json.optLong("expires_at", 0);
        if (state.accessToken == null || state.accessToken.isEmpty() ||
                state.refreshToken == null || state.refreshToken.isEmpty() || state.expiresAt <= 0)
            throw new JSONException("Incomplete Zoho Mail token state");
        return state;
    }

    boolean needsRefresh(long now, boolean force) {
        return (force || expiresAt - REFRESH_SKEW_MS <= now);
    }

    JSONObject jsonSerialize() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("zoho_version", VERSION);
        json.put("access_token", accessToken);
        json.put("refresh_token", refreshToken);
        json.put("api_domain", apiDomain);
        json.put("accounts_server", accountsServer);
        json.put("account_id", accountId);
        json.put("email", email);
        json.put("display_name", displayName);
        json.put("client_id", clientId);
        json.put("client_secret", clientSecret);
        json.put("redirect_uri", redirectUri);
        json.put("expires_at", expiresAt);
        return json;
    }

    String jsonSerializeString() throws JSONException {
        return jsonSerialize().toString();
    }

    private static String emptyToNull(String value) {
        return (value == null || value.isEmpty() ? null : value);
    }
}

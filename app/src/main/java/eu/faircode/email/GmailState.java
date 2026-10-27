package eu.faircode.email;
/*
    This file is part of FairEmail.
    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.
    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.
    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.
    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.content.Context;
import androidx.annotation.NonNull;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/** Persists Gmail access state from either Google Identity Services or browser OAuth. */
public class GmailState {
    private static final int LEGACY_VERSION = 2;
    private static final int VERSION = 3;
    static final String TYPE_GOOGLE = "com.google";
    private static final long TOKEN_LIFETIME = 55 * 60 * 1000L;
    private String token;
    private long acquired;
    private String account;
    private String refreshToken;
    private String clientId;

    private GmailState(String token, long acquired, String account, String refreshToken, String clientId) {
        this.token = token;
        this.acquired = acquired;
        this.account = account;
        this.refreshToken = refreshToken;
        this.clientId = clientId;
    }

    static GmailState authorized(String token, String account) {
        validateTokenAndAccount(token, account);
        return new GmailState(token, new Date().getTime(), account.trim(), null, null);
    }

    static GmailState authorizedWeb(String token, String refreshToken, String account, String clientId) {
        validateTokenAndAccount(token, account);
        if (isEmpty(refreshToken) || isEmpty(clientId))
            throw new IllegalArgumentException("Google returned incomplete Gmail browser authorization");
        return new GmailState(token, new Date().getTime(), account.trim(), refreshToken, clientId);
    }

    private static void validateTokenAndAccount(String token, String account) {
        if (isEmpty(token))
            throw new IllegalArgumentException("Google returned no Gmail access token");
        if (isEmpty(account))
            throw new IllegalArgumentException("Google returned no Gmail account address");
    }

    @NonNull
    String getAccessToken() throws AuthenticatorException {
        if (isEmpty(token))
            throw new AuthenticatorException("Gmail requires Google authorization");
        return token;
    }

    Long getAccessTokenExpirationTime() {
        if (isEmpty(token) || acquired <= 0)
            return null;
        return acquired + TOKEN_LIFETIME;
    }

    void refresh(@NonNull Context context, String id, @NonNull String user, boolean forceRefresh)
            throws AuthenticatorException, OperationCanceledException, IOException {
        long now = new Date().getTime();
        Long expiration = getAccessTokenExpirationTime();
        boolean missing = (isEmpty(token) || isEmpty(account));
        boolean expired = (expiration == null || expiration < now);
        boolean refresh = (missing || expired || (forceRefresh &&
                expiration - ServiceAuthenticator.MIN_FORCE_REFRESH_INTERVAL < now));

        Map<String, String> crumb = new HashMap<>();
        crumb.put("id", id);
        crumb.put("force", Boolean.toString(forceRefresh));
        crumb.put("need", Boolean.toString(refresh));
        crumb.put("token", Boolean.toString(!isEmpty(token)));
        crumb.put("expiration", expiration == null ? "n/a" : ((expiration - now) / 1000L) + " s");
        Log.breadcrumb("Gmail token refresh", crumb);
        EntityLog.log(context, EntityLog.Type.General,
                "Gmail OAuth token refresh user=" + id + ":" + user +
                        " force=" + forceRefresh + " need=" + refresh +
                        " expiration=" + (expiration == null ? null : new Date(expiration)));

        if (!refresh)
            return;
        try {
            String selectedAccount = (isEmpty(account)
                    ? user.replaceFirst("(?i)^recent:", "") : account);
            String newToken = acquireAccessToken(context, selectedAccount, forceRefresh || expired);
            if (isEmpty(newToken))
                throw new IOException("Google returned no Gmail access token");
            token = newToken;
            account = selectedAccount;
            acquired = new Date().getTime();
        } catch (GmailAuthorizationRequiredException ex) {
            AuthenticatorException required = new AuthenticatorException(ex.getMessage());
            required.initCause(ex);
            throw required;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Gmail OAuth token refresh failed", ex);
        }
    }

    /** Re-enter the existing Google grant after Gmail has rejected its bearer token. */
    void refreshAfterUnauthorized(@NonNull Context context, String id, @NonNull String user)
            throws IOException {
        String selectedAccount = (isEmpty(account)
                ? user.replaceFirst("(?i)^recent:", "") : account);
        try {
            String replacement = acquireAccessToken(context, selectedAccount, true);
            if (isEmpty(replacement))
                throw new IOException("Google returned no Gmail access token");
            if (replacement.equals(token))
                throw new GmailAuthorizationRequiredException(
                        "Gmail requires Google authorization; choose Re-authorize Gmail to replace the rejected token");
            token = replacement;
            account = selectedAccount;
            acquired = new Date().getTime();
            EntityLog.log(context, EntityLog.Type.General,
                    "Gmail OAuth reacquired an access token after HTTP 401 user=" + id + ":" + user);
        } catch (GmailAuthorizationRequiredException ex) {
            throw ex;
        } catch (IOException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IOException("Gmail OAuth token reacquisition failed", ex);
        }
    }

    private String acquireAccessToken(Context context, String selectedAccount, boolean forceRefresh)
            throws Exception {
        if (!isEmpty(refreshToken) && !isEmpty(clientId)) {
            GmailWebOAuth.RefreshResult result = GmailWebOAuth.refresh(clientId, refreshToken);
            refreshToken = result.refreshToken;
            return result.accessToken;
        }
        return GmailAuthorization.get().acquireAccessToken(
                context, selectedAccount, token, forceRefresh);
    }

    public String jsonSerializeString() {
        try {
            JSONObject json = new JSONObject();
            json.put("version", VERSION);
            json.put("token", token);
            json.put("acquired", acquired);
            json.put("account", account);
            if (!isEmpty(refreshToken))
                json.put("refreshToken", refreshToken);
            if (!isEmpty(clientId))
                json.put("clientId", clientId);
            return json.toString();
        } catch (JSONException ex) {
            Log.e(ex);
            return null;
        }
    }

    static GmailState jsonDeserialize(@NonNull String password) {
        if (isEmpty(password))
            return new GmailState(null, 0, null, null, null);
        try {
            JSONObject json = new JSONObject(password);
            int version = json.optInt("version", 0);
            if (version != LEGACY_VERSION && version != VERSION)
                return new GmailState(null, 0, null, null, null);
            String token = json.optString("token", null);
            long acquired = json.optLong("acquired", 0);
            String account = json.optString("account", null);
            String refreshToken = (version == VERSION ? json.optString("refreshToken", null) : null);
            String clientId = (version == VERSION ? json.optString("clientId", null) : null);
            if (isEmpty(refreshToken) || isEmpty(clientId)) {
                refreshToken = null;
                clientId = null;
            }
            return new GmailState(token, acquired, account, refreshToken, clientId);
        } catch (JSONException ex) {
            // Legacy plaintext and AccountManager token blobs are never sent to Gmail REST.
            Log.i("Discarding legacy Gmail token state");
            return new GmailState(null, 0, null, null, null);
        }
    }

    static boolean hasAuthorization(String password) {
        GmailState state = jsonDeserialize(password);
        return (!isEmpty(state.token) && !isEmpty(state.account));
    }

    static boolean isApiState(String password) {
        if (isEmpty(password))
            return false;
        try {
            int version = new JSONObject(password).optInt("version", 0);
            return version == LEGACY_VERSION || version == VERSION;
        } catch (JSONException ex) {
            return false;
        }
    }

    private static boolean isEmpty(String value) {
        return (value == null || value.trim().isEmpty());
    }
}

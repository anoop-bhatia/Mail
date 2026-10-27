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
import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;
import androidx.annotation.NonNull;
import com.google.android.gms.auth.api.identity.AuthorizationClient;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Direct Android AuthorizationClient integration; no AppAuth redirect or embedded client ID. */
public final class GmailAuthorizationGms implements GmailAuthorization {
    private static final String GOOGLE_ACCOUNT_TYPE = "com.google";
    private static final long AUTHORIZATION_TIMEOUT_SECONDS = 90;

    @Override
    public void authorize(@NonNull Activity activity, String account, @NonNull Callback callback) {
        try {
            AuthorizationClient client = Identity.getAuthorizationClient(activity);
            client.authorize(createRequest(account))
                    .addOnSuccessListener(result -> deliverResult(result, callback))
                    .addOnFailureListener(callback::onError);
        } catch (Throwable ex) {
            callback.onError(asException(ex));
        }
    }

    @Override
    public String getAccessTokenFromIntent(Context context, Intent data) throws Exception {
        if (data == null)
            throw new IOException("Google authorization returned no result");
        AuthorizationResult result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(data);
        return requireAccessToken(result);
    }

    @Override
    public String acquireAccessToken(Context context, String account, String currentToken,
                                     boolean forceRefresh) throws Exception {
        AuthorizationClient client = Identity.getAuthorizationClient(context);
        // play-services-auth 21.2.0 retains Android 23 support and AuthorizationClient,
        // but predates ClearTokenRequest; the direct authorization request lets GIS
        // refresh its own access grant or signal that interactive consent is required.
        AuthorizationResult result = Tasks.await(
                client.authorize(createRequest(account)),
                AUTHORIZATION_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (result.hasResolution())
            throw new GmailAuthorizationRequiredException(
                    "Gmail access needs your approval; choose Re-authorize Gmail to continue");
        return requireAccessToken(result);
    }

    private static AuthorizationRequest createRequest(String email) {
        List<Scope> scopes = new ArrayList<>();
        for (String scope : GmailScopes.getRequested())
            scopes.add(new Scope(scope));
        AuthorizationRequest.Builder builder = AuthorizationRequest.builder()
                .setRequestedScopes(scopes);
        if (!TextUtils.isEmpty(email))
            builder.setAccount(new Account(email, GOOGLE_ACCOUNT_TYPE));
        return builder.build();
    }

    private static void deliverResult(AuthorizationResult result, Callback callback) {
        if (result.hasResolution()) {
            PendingIntent pendingIntent = result.getPendingIntent();
            if (pendingIntent == null)
                callback.onError(new IOException("Google authorization did not return a consent screen"));
            else
                callback.onResolution(pendingIntent);
        } else
            try {
                callback.onAuthorized(requireAccessToken(result));
            } catch (Exception ex) {
                callback.onError(ex);
            }
    }

    private static String requireAccessToken(AuthorizationResult result) throws IOException {
        List<String> granted = result.getGrantedScopes();
        if (!GmailScopes.hasRequired(granted))
            throw new IOException("Google did not grant the Gmail read, modify, send, and permanent-delete permissions");
        String token = result.getAccessToken();
        if (TextUtils.isEmpty(token))
            throw new IOException("Google returned no Gmail access token");
        return token;
    }

    private static Exception asException(Throwable ex) {
        return (ex instanceof Exception ? (Exception) ex : new IOException(ex));
    }
}

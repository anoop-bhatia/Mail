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
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import java.io.IOException;
/** Gmail-only bridge to Google Identity Services, supplied only by GMS-enabled flavors. */
interface GmailAuthorization {
    interface Callback {
        void onAuthorized(String accessToken);
        void onResolution(PendingIntent pendingIntent);
        void onError(Exception ex);
    }
    void authorize(Activity activity, String account, Callback callback);
    String getAccessTokenFromIntent(Context context, Intent data) throws Exception;
    String acquireAccessToken(Context context, String account, String currentToken,
                              boolean forceRefresh) throws Exception;
    static GmailAuthorization get() throws IOException {
        try {
            Class<?> implementation = Class.forName("eu.faircode.email.GmailAuthorizationGms");
            return (GmailAuthorization) implementation.newInstance();
        } catch (Throwable ex) {
            throw new IOException("Google Identity Services authorization is unavailable in this build", ex);
        }
    }
}
/** Indicates that user interaction is required before Gmail REST can continue. */
final class GmailAuthorizationRequiredException extends IOException {
    GmailAuthorizationRequiredException(String message) {
        super(message);
    }
}

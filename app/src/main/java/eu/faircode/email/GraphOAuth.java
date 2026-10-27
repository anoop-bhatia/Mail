package eu.faircode.email;

/** Native Microsoft Graph Mail API authorization metadata. */
final class GraphOAuth {
    static final String PROVIDER_ID = "msgraphapi";
    static final String LEGACY_PROVIDER_ID = "outlookgraph";
    static final String CLIENT_ID = "835c3383-543c-4aa2-98ca-c465eb039987";
    static final String REDIRECT_URI = "msauth://eu.faircode.email.debug/PJ0gvpIEttFzi8fXtHIjtsBz%2BT8%3D";
    static final String AUTHORIZATION_ENDPOINT =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/authorize";
    static final String TOKEN_ENDPOINT =
            "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    static final String PRIVACY_URI = "https://privacy.microsoft.com/privacystatement";
    static final String[] SCOPES = {
            "offline_access",
            "https://graph.microsoft.com/Mail.Send"
    };

    private GraphOAuth() {
    }

    static boolean isProviderId(String id) {
        return PROVIDER_ID.equals(id) || LEGACY_PROVIDER_ID.equals(id);
    }

    static EmailProvider.OAuth getProfile() {
        EmailProvider.OAuth profile = new EmailProvider.OAuth();
        profile.enabled = true;
        profile.askAccount = true;
        profile.clientId = CLIENT_ID;
        profile.scopes = SCOPES.clone();
        profile.authorizationEndpoint = AUTHORIZATION_ENDPOINT;
        profile.tokenEndpoint = TOKEN_ENDPOINT;
        profile.tokenScopes = true;
        profile.redirectUri = REDIRECT_URI;
        profile.privacy = PRIVACY_URI;
        profile.prompt = "login";
        return profile;
    }
}

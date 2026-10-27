package in.anoopbhatia.mail;

/** Native Microsoft Graph Mail API authorization metadata. */
final class GraphOAuth {
    private static final ApiCredentials.Entry CREDENTIALS = ApiCredentials.get("graph");
    static final String PROVIDER_ID = CREDENTIALS.providerId;
    static final String LEGACY_PROVIDER_ID = CREDENTIALS.legacyProviderId;
    static final String CLIENT_ID = CREDENTIALS.clientId;
    static final String RELEASE_REDIRECT_URI = CREDENTIALS.redirectUri(false);
    static final String DEBUG_REDIRECT_URI = CREDENTIALS.redirectUri(true);
    static final String AUTHORIZATION_ENDPOINT = CREDENTIALS.authorizationEndpoint;
    static final String TOKEN_ENDPOINT = CREDENTIALS.tokenEndpoint;
    static final String PRIVACY_URI = CREDENTIALS.privacy;
    static final String[] SCOPES = CREDENTIALS.scopes.toArray(new String[0]);

    private GraphOAuth() {
    }

    static boolean isProviderId(String id) {
        return PROVIDER_ID.equals(id) || LEGACY_PROVIDER_ID.equals(id);
    }

    static String getRedirectUri(boolean debug) {
        return CREDENTIALS.redirectUri(debug);
    }

    static EmailProvider.OAuth getProfile() {
        EmailProvider.OAuth profile = new EmailProvider.OAuth();
        profile.enabled = true;
        profile.askAccount = true;
        profile.clientId = CREDENTIALS.clientId(BuildConfig.DEBUG);
        profile.scopes = CREDENTIALS.scopes.toArray(new String[0]);
        profile.authorizationEndpoint = AUTHORIZATION_ENDPOINT;
        profile.tokenEndpoint = TOKEN_ENDPOINT;
        profile.tokenScopes = true;
        profile.redirectUri = getRedirectUri(BuildConfig.DEBUG);
        profile.privacy = PRIVACY_URI;
        profile.prompt = CREDENTIALS.prompt;
        return profile;
    }
}

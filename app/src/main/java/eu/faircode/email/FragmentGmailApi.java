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
import static android.app.Activity.RESULT_OK;
import static eu.faircode.email.ServiceAuthenticator.AUTH_TYPE_GMAIL;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.Group;
import androidx.lifecycle.Lifecycle;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.GrantTypeValues;
import net.openid.appauth.NoClientAuthentication;
import net.openid.appauth.ResponseTypeValues;
import net.openid.appauth.TokenRequest;
import net.openid.appauth.TokenResponse;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Gmail API setup; Gmail never uses the generic IMAP, POP, SMTP, or AppAuth paths. */
public class FragmentGmailApi extends FragmentBase {
    private static final int REQUEST_GMAIL_ACCOUNT_SELECTION = 7320;
    private static final int REQUEST_GMAIL_AUTHORIZATION = 7321;
    private static final int REQUEST_GMAIL_WEB_AUTHORIZATION = 7322;
    private static final String PRIVACY_URI = "https://policies.google.com/privacy";
    private static final String STATE_WEB_OAUTH = "gmailApiWebOAuthState";
    private static final String CLIENT_WEB_OAUTH = "gmailApiWebOAuthClient";
    private static final String REDIRECT_WEB_OAUTH = "gmailApiWebOAuthRedirect";
    private String personal;
    private String address;
    private boolean update;
    private String pendingWebOAuthState;
    private String pendingWebOAuthClientId;
    private String pendingWebOAuthRedirectUri;
    private AuthorizationService webAuthorizationService;
    private ViewGroup view;
    private ScrollView scroll;
    private TextView tvTitle;
    private TextView tvPrivacy;
    private TextView tvPrivacyApp;
    private EditText etName;
    private CheckBox cbUpdate;
    private Button btnSelect;
    private ContentLoadingProgressBar pbSelect;
    private TextView tvError;
    private Button btnSupport;
    private Group grpError;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        if (args != null) {
            personal = args.getString("personal");
            address = args.getString("address");
            update = args.getBoolean("update", true);
        } else
            update = true;
        if (address != null && address.regionMatches(true, 0, "recent:", 0, 7))
            address = address.substring(7);
        if (savedInstanceState != null) {
            pendingWebOAuthState = savedInstanceState.getString(STATE_WEB_OAUTH);
            pendingWebOAuthClientId = savedInstanceState.getString(CLIENT_WEB_OAUTH);
            pendingWebOAuthRedirectUri = savedInstanceState.getString(REDIRECT_WEB_OAUTH);
        }
        lockOrientation();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putString(STATE_WEB_OAUTH, pendingWebOAuthState);
        outState.putString(CLIENT_WEB_OAUTH, pendingWebOAuthClientId);
        outState.putString(REDIRECT_WEB_OAUTH, pendingWebOAuthRedirectUri);
        super.onSaveInstanceState(outState);
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        setSubtitle(R.string.title_setup_gmail);
        setHasOptionsMenu(true);
        view = (ViewGroup) inflater.inflate(R.layout.fragment_gmail_api, container, false);
        scroll = view.findViewById(R.id.scroll);
        tvTitle = view.findViewById(R.id.tvTitle);
        tvPrivacy = view.findViewById(R.id.tvPrivacy);
        tvPrivacyApp = view.findViewById(R.id.tvPrivacyApp);
        etName = view.findViewById(R.id.etName);
        cbUpdate = view.findViewById(R.id.cbUpdate);
        btnSelect = view.findViewById(R.id.btnSelect);
        pbSelect = view.findViewById(R.id.pbSelect);
        tvError = view.findViewById(R.id.tvError);
        btnSupport = view.findViewById(R.id.btnSupport);
        grpError = view.findViewById(R.id.grpError);

        tvPrivacy.setPaintFlags(tvPrivacy.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tvPrivacy.setOnClickListener(v -> Helper.view(v.getContext(), Uri.parse(PRIVACY_URI), false));
        tvPrivacyApp.setPaintFlags(tvPrivacyApp.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tvPrivacyApp.setOnClickListener(v -> Helper.view(v.getContext(), Uri.parse(Helper.FAQ_BASE_URI), false));
        btnSupport.setOnClickListener(v -> Helper.view(v.getContext(),
                Helper.getSupportUri(v.getContext(), "Gmail:support"), false));
        btnSelect.setText(R.string.title_setup_gmail_authorize);
        btnSelect.setOnClickListener(v -> onSelect(false));
        btnSelect.setOnLongClickListener(v -> {
            onSelect(true);
            return true;
        });
        tvTitle.setText(R.string.title_setup_gmail_api_rationale);
        String displayName = personal;
        if (TextUtils.isEmpty(displayName) && !TextUtils.isEmpty(address))
            displayName = address.split("@", 2)[0];
        etName.setText(displayName);
        cbUpdate.setChecked(update);
        pbSelect.setVisibility(View.GONE);
        grpError.setVisibility(View.GONE);
        return view;
    }

    private void onSelect(boolean longPress) {
        if (GmailAccountSelectionPolicy.shouldUseBrowserOAuth(longPress))
            authorizeWithGoogleWeb();
        else
            selectGoogleAccount();
    }

    private void selectGoogleAccount() {
        grpError.setVisibility(View.GONE);
        etName.clearFocus();
        Helper.hideKeyboard(view);
        setBusy(true);
        try {
            Intent chooser = createGoogleAccountChooserIntent();
            startActivityForResult(chooser, REQUEST_GMAIL_ACCOUNT_SELECTION);
        } catch (Throwable ex) {
            setBusy(false);
            showError(Log.formatThrowable(ex, false));
        }
    }

    private Intent createGoogleAccountChooserIntent() throws Exception {
        String[] accountTypes = GmailAccountSelectionPolicy.getAllowedAccountTypes();
        try {
            return AccountManager.newChooseAccountIntent(
                    null, (ArrayList<Account>) null, accountTypes,
                    GmailAccountSelectionPolicy.alwaysPromptForAccount(),
                    null, null, null, null);
        } catch (NoSuchMethodError ex) {
            // Android 23-25 expose the deprecated seven-argument overload.
            Method method = AccountManager.class.getMethod("newChooseAccountIntent",
                    Account.class, ArrayList.class, String[].class, Boolean.TYPE,
                    String.class, String.class, String[].class);
            return (Intent) method.invoke(null, null, null, accountTypes,
                    GmailAccountSelectionPolicy.alwaysPromptForAccount(), null, null, null);
        }
    }

    private void authorize(String selectedAccount) {
        try {
            GmailAuthorization.get().authorize(getActivity(), selectedAccount, new GmailAuthorization.Callback() {
                @Override
                public void onAuthorized(String accessToken) {
                    if (isStarted())
                        saveAccount(accessToken);
                }

                @Override
                public void onResolution(PendingIntent pendingIntent) {
                    if (!isStarted())
                        return;
                    try {
                        startIntentSenderForResult(pendingIntent.getIntentSender(),
                                REQUEST_GMAIL_AUTHORIZATION, null, 0, 0, 0, null);
                    } catch (IntentSender.SendIntentException ex) {
                        setBusy(false);
                        showError(Log.formatThrowable(ex, false));
                    }
                }

                @Override
                public void onError(Exception ex) {
                    if (isStarted()) {
                        setBusy(false);
                        showError(Log.formatThrowable(ex, false));
                    }
                }
            });
        } catch (Throwable ex) {
            setBusy(false);
            showError(Log.formatThrowable(ex, false));
        }
    }

    private void authorizeWithGoogleWeb() {
        grpError.setVisibility(View.GONE);
        etName.clearFocus();
        Helper.hideKeyboard(view);
        setBusy(true);
        try {
            EmailProvider provider = EmailProvider.getProvider(getContext(), "gmail");
            EmailProvider.OAuth oauth = provider.oauth;
            if (oauth == null || !oauth.enabled)
                throw new IllegalStateException("FairEmail Gmail browser authorization is unavailable");
            if (!GmailWebOAuth.TOKEN_ENDPOINT.equals(oauth.tokenEndpoint))
                throw new IllegalStateException("The configured FairEmail Gmail token endpoint does not match the existing Google OAuth registration");

            String clientId = GmailWebOAuth.getClientId();
            String redirectUri = GmailWebOAuth.getRedirectUri(BuildConfig.DEBUG, oauth.redirectUri);
            AuthorizationServiceConfiguration configuration = new AuthorizationServiceConfiguration(
                    Uri.parse(oauth.authorizationEndpoint), Uri.parse(oauth.tokenEndpoint));
            Map<String, String> parameters = new LinkedHashMap<>();
            if (oauth.parameters != null)
                parameters.putAll(oauth.parameters);
            parameters.put("access_type", "offline");

            pendingWebOAuthState = UUID.randomUUID().toString();
            pendingWebOAuthClientId = clientId;
            pendingWebOAuthRedirectUri = redirectUri;
            AuthorizationRequest.Builder request = new AuthorizationRequest.Builder(
                    configuration, clientId, ResponseTypeValues.CODE, Uri.parse(redirectUri))
                    .setScopes(GmailScopes.getRequested().toArray(new String[0]))
                    .setState(pendingWebOAuthState)
                    .setAdditionalParameters(parameters);
            if (!TextUtils.isEmpty(address))
                request.setLoginHint(address);
            if (!TextUtils.isEmpty(oauth.prompt))
                request.setPrompt(oauth.prompt);

            webAuthorizationService = new AuthorizationService(getContext());
            Intent intent = webAuthorizationService.getAuthorizationRequestIntent(request.build());
            startActivityForResult(intent, REQUEST_GMAIL_WEB_AUTHORIZATION);
        } catch (Throwable ex) {
            clearPendingWebOAuth();
            closeWebAuthorizationService();
            setBusy(false);
            showError(Log.formatThrowable(ex, false));
        }
    }

    private void handleGoogleWebAuthorization(Intent data) {
        try {
            AuthorizationResponse auth = AuthorizationResponse.fromIntent(data);
            if (auth == null) {
                AuthorizationException error = AuthorizationException.fromIntent(data);
                if (error == null)
                    throw new IllegalArgumentException("Google browser authorization returned no response");
                throw error;
            }
            if (auth.request == null || TextUtils.isEmpty(auth.authorizationCode))
                throw new IllegalArgumentException("Google browser authorization returned no authorization code");
            if (TextUtils.isEmpty(pendingWebOAuthState) ||
                    !pendingWebOAuthState.equals(auth.state) ||
                    !pendingWebOAuthClientId.equals(auth.request.clientId) ||
                    !pendingWebOAuthRedirectUri.equals(auth.request.redirectUri.toString()))
                throw new SecurityException("Google browser authorization did not match the FairEmail request");

            TokenRequest request = new TokenRequest.Builder(
                    auth.request.configuration, auth.request.clientId)
                    .setGrantType(GrantTypeValues.AUTHORIZATION_CODE)
                    .setRedirectUri(auth.request.redirectUri)
                    .setCodeVerifier(auth.request.codeVerifier)
                    .setAuthorizationCode(auth.authorizationCode)
                    .setNonce(auth.request.nonce)
                    .build();
            AuthorizationService service = (webAuthorizationService == null
                    ? new AuthorizationService(getContext()) : webAuthorizationService);
            webAuthorizationService = service;
            service.performTokenRequest(request, NoClientAuthentication.INSTANCE,
                    new AuthorizationService.TokenResponseCallback() {
                        @Override
                        public void onTokenRequestCompleted(TokenResponse access, AuthorizationException error) {
                            try {
                                if (error != null)
                                    throw error;
                                if (access == null || TextUtils.isEmpty(access.accessToken))
                                    throw new IllegalStateException("Google returned no Gmail access token");
                                if (TextUtils.isEmpty(access.refreshToken))
                                    throw new IllegalStateException("Google returned no Gmail refresh token; try authorizing again");
                                if (!GmailScopes.hasRequired(access.getScopeSet()))
                                    throw new IllegalStateException("Google did not grant all required Gmail API permissions");
                                if (isStarted())
                                    saveAccount(access.accessToken, access.refreshToken, auth.request.clientId);
                            } catch (Throwable ex) {
                                if (isStarted())
                                    showError(Log.formatThrowable(ex, false));
                            } finally {
                                clearPendingWebOAuth();
                                closeWebAuthorizationService();
                            }
                        }
                    });
        } catch (Throwable ex) {
            clearPendingWebOAuth();
            closeWebAuthorizationService();
            setBusy(false);
            showError(Log.formatThrowable(ex, false));
        }
    }

    private void clearPendingWebOAuth() {
        pendingWebOAuthState = null;
        pendingWebOAuthClientId = null;
        pendingWebOAuthRedirectUri = null;
    }

    private void closeWebAuthorizationService() {
        if (webAuthorizationService != null) {
            webAuthorizationService.dispose();
            webAuthorizationService = null;
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_GMAIL_ACCOUNT_SELECTION) {
            if (resultCode == RESULT_OK)
                try {
                    String selected = (data == null ? null : data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME));
                    if (!GmailAccountSelectionPolicy.hasSelectedAccount(selected))
                        authorizeWithGoogleWeb();
                    else {
                        address = GmailAccountSelectionPolicy.requireSelectedAccount(selected);
                        authorize(address);
                    }
                } catch (Throwable ex) {
                    setBusy(false);
                    showError(Log.formatThrowable(ex, false));
                }
            else {
                setBusy(false);
                ToastEx.makeText(getContext(), android.R.string.cancel, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_GMAIL_AUTHORIZATION) {
            if (resultCode == RESULT_OK)
                try {
                    saveAccount(GmailAuthorization.get().getAccessTokenFromIntent(getContext(), data));
                } catch (Throwable ex) {
                    setBusy(false);
                    showError(Log.formatThrowable(ex, false));
                }
            else {
                setBusy(false);
                ToastEx.makeText(getContext(), android.R.string.cancel, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQUEST_GMAIL_WEB_AUTHORIZATION) {
            if (resultCode == RESULT_OK)
                handleGoogleWebAuthorization(data);
            else {
                clearPendingWebOAuth();
                closeWebAuthorizationService();
                setBusy(false);
                ToastEx.makeText(getContext(), android.R.string.cancel, Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == ActivitySetup.REQUEST_DONE)
            finish();
    }

    private void saveAccount(String accessToken) {
        saveAccount(accessToken, null, null);
    }

    private void saveAccount(String accessToken, String refreshToken, String clientId) {
        Bundle args = new Bundle();
        args.putString("name", etName.getText().toString().trim());
        args.putBoolean("update", cbUpdate.isChecked());
        args.putString("requestedAddress", address);
        args.putString("accessToken", accessToken);
        args.putString("refreshToken", refreshToken);
        args.putString("clientId", clientId);
        new SimpleTask<Void>() {
            @Override
            protected void onPreExecute(Bundle args) {
                setBusy(true);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                setBusy(false);
            }

            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                String token = args.getString("accessToken");
                String name = args.getString("name");
                boolean updateExisting = args.getBoolean("update");
                String requestedAddress = args.getString("requestedAddress");
                String refreshToken = args.getString("refreshToken");
                String clientId = args.getString("clientId");
                String mailbox = new GmailApi(token).getProfile().emailAddress;
                if (TextUtils.isEmpty(mailbox) || !Helper.EMAIL_ADDRESS.matcher(mailbox).matches())
                    throw new IllegalArgumentException("Google returned an invalid Gmail mailbox address");
                if (!TextUtils.isEmpty(refreshToken) && !TextUtils.isEmpty(requestedAddress) &&
                        !mailbox.equalsIgnoreCase(requestedAddress.trim()))
                    throw new IllegalArgumentException("The authorized Gmail account does not match the requested account");
                String user = (TextUtils.isEmpty(requestedAddress) ? mailbox : requestedAddress.trim());
                if (!Helper.EMAIL_ADDRESS.matcher(user).matches())
                    throw new IllegalArgumentException("The selected Gmail address is invalid");
                GmailState state = (TextUtils.isEmpty(refreshToken)
                        ? GmailState.authorized(token, mailbox)
                        : GmailState.authorizedWeb(token, refreshToken, mailbox, clientId));
                String password = state.jsonSerializeString();
                if (TextUtils.isEmpty(password))
                    throw new IllegalArgumentException(context.getString(R.string.title_no_password));

                DB db = DB.getInstance(context);
                EntityAccount existing = null;
                try {
                    db.beginTransaction();
                    if (updateExisting) {
                        for (EntityAccount candidate : db.account().getAccounts())
                            if (candidate.isGmail() && candidate.user != null &&
                                    user.equalsIgnoreCase(candidate.user.replaceFirst("(?i)^recent:", ""))) {
                                if (existing != null)
                                    throw new IllegalArgumentException(
                                            "More than one Gmail account matches this address; disable update to add another account");
                                existing = candidate;
                            }
                    }
                    if (existing == null) {
                        EntityAccount primary = db.account().getPrimaryAccount();
                        EntityAccount account = new EntityAccount();
                        // Keep the legacy IMAP protocol/host columns for local UI compatibility;
                        // AUTH_TYPE_GMAIL is the network transport selector and means Gmail REST.
                        account.protocol = EntityAccount.TYPE_IMAP;
                        account.host = "imap.gmail.com";
                        account.encryption = EmailService.ENCRYPTION_SSL;
                        account.port = 993;
                        account.auth_type = AUTH_TYPE_GMAIL;
                        account.provider = GmailApi.PROVIDER_ID;
                        account.user = user;
                        account.password = password;
                        account.name = NativeApiAccountNaming.forNewAccount(AUTH_TYPE_GMAIL, user);
                        account.synchronize = true;
                        account.primary = (primary == null);
                        account.created = new Date().getTime();
                        account.last_connected = account.created;
                        account.id = db.account().insertAccount(account);
                        args.putLong("account", account.id);
                        EntityFolder inbox = new EntityFolder("INBOX", EntityFolder.INBOX);
                        inbox.account = account.id;
                        inbox.synchronize = true;
                        NativeApiFolderDefaults.applyCreationDefaults(inbox, true, true);
                        inbox.unified = true;
                        inbox.notify = true;
                        inbox.sync_days = EntityFolder.DEFAULT_SYNC;
                        inbox.keep_days = EntityFolder.DEFAULT_KEEP;
                        inbox.setSpecials(account);
                        inbox.id = db.folder().insertFolder(inbox);
                        account.swipe_left = EntityMessage.SWIPE_ACTION_SEEN;
                        account.swipe_right = EntityMessage.SWIPE_ACTION_SEEN;
                        db.account().updateAccount(account);

                        if (TextUtils.isEmpty(name))
                            name = mailbox.split("@")[0];
                        EntityIdentity identity = new EntityIdentity();
                        identity.name = name;
                        identity.email = user;
                        identity.account = account.id;
                        // Legacy SMTP columns are retained as placeholders; AUTH_TYPE_GMAIL routes REST send.
                        identity.host = "smtp.gmail.com";
                        identity.encryption = EmailService.ENCRYPTION_SSL;
                        identity.port = 465;
                        identity.auth_type = AUTH_TYPE_GMAIL;
                        identity.provider = GmailApi.PROVIDER_ID;
                        identity.user = user;
                        identity.password = password;
                        identity.synchronize = true;
                        identity.primary = true;
                        db.identity().insertIdentity(identity);
                        EntityLog.log(context, "Gmail API account=" + account.name);
                        if (NativeApiFolderDefaults.shouldQueueInitialSync(true, true, inbox))
                            EntityOperation.sync(context, inbox.id, true);
                    } else {
                        args.putLong("account", existing.id);
                        existing.host = "imap.gmail.com";
                        existing.encryption = EmailService.ENCRYPTION_SSL;
                        existing.port = 993;
                        existing.auth_type = AUTH_TYPE_GMAIL;
                        existing.provider = GmailApi.PROVIDER_ID;
                        existing.user = user;
                        existing.password = password;
                        existing.synchronize = true;
                        // Keep the historic protocol value and all local messages/folders intact.
                        db.account().updateAccount(existing);
                        List<EntityIdentity> identities = db.identity().getIdentities(existing.id);
                        for (EntityIdentity identity : identities)
                            if (EntityAccount.isGmailHost(identity.host) ||
                                    identity.auth_type == AUTH_TYPE_GMAIL || "gmail".equals(identity.provider))
                                db.identity().setIdentityPasswordAuth(identity.id, password,
                                        AUTH_TYPE_GMAIL, GmailApi.PROVIDER_ID);
                        EntityFolder inbox = db.folder().getFolderByType(existing.id, EntityFolder.INBOX);
                        if (inbox == null) {
                            inbox = new EntityFolder("INBOX", EntityFolder.INBOX);
                            inbox.account = existing.id;
                            inbox.synchronize = true;
                            inbox.unified = true;
                            inbox.notify = true;
                            inbox.sync_days = EntityFolder.DEFAULT_SYNC;
                            inbox.keep_days = EntityFolder.DEFAULT_KEEP;
                            inbox.setSpecials(existing);
                            inbox.id = db.folder().insertFolder(inbox);
                        }
                        EntityLog.log(context, "Gmail API reauthorized account=" + existing.name +
                                " cached messages and folders preserved");
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                if (existing == null)
                    GmailReceiver.markNewAccountFolderDefaultsPending(context, args.getLong("account"));
                args.putBoolean("updated", existing != null);
                ServiceSynchronize.eval(context, "Gmail API authorization");
                FairEmailBackupAgent.dataChanged(context);
                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                if (args.getBoolean("updated")) {
                    ToastEx.makeText(getContext(), R.string.title_setup_oauth_updated,
                            Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    FragmentDialogAccount fragment = new FragmentDialogAccount();
                    fragment.setArguments(args);
                    fragment.setTargetFragment(FragmentGmailApi.this, ActivitySetup.REQUEST_DONE);
                    fragment.show(getParentFragmentManager(), "quick:review");
                }
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                Log.e(ex);
                showError(ex instanceof IllegalArgumentException
                        ? new ThrowableWrapper(ex).getSafeMessage()
                        : Log.formatThrowable(ex, false));
            }
        }.execute(this, args, "setup:gmail-api");
    }

    private void setBusy(boolean busy) {
        if (view == null)
            return;
        for (int viewId : getBusyControlIds())
            view.findViewById(viewId).setEnabled(!busy);
        pbSelect.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    static int[] getBusyControlIds() {
        return new int[]{
                R.id.etName, R.id.cbUpdate, R.id.btnSelect
        };
    }

    private boolean isStarted() {
        return getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED);
    }

    private void showError(String message) {
        setBusy(false);
        tvError.setText(message);
        grpError.setVisibility(View.VISIBLE);
        scroll.post(() -> scroll.smoothScrollTo(0, tvError.getBottom()));
    }
}

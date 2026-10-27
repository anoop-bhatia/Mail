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
import static eu.faircode.email.ServiceAuthenticator.AUTH_TYPE_GRAPH;
import static eu.faircode.email.ServiceAuthenticator.AUTH_TYPE_OAUTH;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.Group;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.Lifecycle;
import androidx.preference.PreferenceManager;

import net.openid.appauth.AppAuthConfiguration;
import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationManagementActivity;
import net.openid.appauth.AuthorizationRequest;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ClientAuthentication;
import net.openid.appauth.ClientSecretPost;
import net.openid.appauth.GrantTypeValues;
import net.openid.appauth.NoClientAuthentication;
import net.openid.appauth.ResponseTypeValues;
import net.openid.appauth.TokenRequest;
import net.openid.appauth.TokenResponse;
import net.openid.appauth.browser.BrowserDescriptor;
import net.openid.appauth.browser.BrowserMatcher;
import net.openid.appauth.browser.Browsers;
import net.openid.appauth.browser.VersionRange;
import net.openid.appauth.browser.VersionedBrowserMatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.net.URL;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.mail.AuthenticationFailedException;
import javax.mail.MessagingException;
import javax.net.ssl.HttpsURLConnection;

public class FragmentOAuth extends FragmentBase {
    private String id;
    private String name;
    private String privacy;
    private boolean askAccount;
    private boolean askTenant;
    private long zohoAccountId = -1;
    private boolean graphMailApi;

    private String personal;
    private String address;
    private boolean pop;
    private boolean recent;
    private boolean update;

    private ViewGroup view;
    private ScrollView scroll;

    private TextView tvTitle;
    private TextView tvPrivacy;
    private TextView tvPrivacyApp;
    private EditText etName;
    private EditText etEmail;
    private EditText etTenant;
    private CheckBox cbInboundOnly;
    private CheckBox cbPop;
    private CheckBox cbRecent;
    private CheckBox cbUpdate;
    private TextView tvEdge;
    private TextView tvBrave;
    private Button btnOAuth;
    private ContentLoadingProgressBar pbOAuth;
    private TextView tvConfiguring;
    private TextView tvGmailHint;
    private TextView tvGmailLoginHint;
    private TextView tvGmailLoginMax;
    private TextView tvOutlookHint;
    private TextView tvOutlookPasskeyHint;
    private EditText etZohoClientId;
    private EditText etZohoClientSecret;
    private EditText etZohoRedirectUri;

    private TextView tvError;
    private TextView tvOfficeAuthHint;
    private Button btnSupport;
    private Button btnHelp;

    private Group grpTenant;
    private Group grpZoho;
    private Group grpError;

    private static final String FAIREMAIL_RANDOM = "fairemail.random";
    private static final String FAIREMAIL_EXPIRE = "fairemail.expire";
    private static final int MAILRU_TIMEOUT = 20 * 1000; // milliseconds
    private static final long OAUTH_TIMEOUT = 20 * 60 * 1000L;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        id = args.getString("id");
        name = args.getString("name");
        privacy = args.getString("privacy");
        askAccount = args.getBoolean("askAccount", false);
        askTenant = args.getBoolean("askTenant", false);
        zohoAccountId = args.getLong("zohoAccount", -1);
        graphMailApi = args.getBoolean("graphMailApi", false);

        personal = args.getString("personal");
        address = args.getString("address");
        pop = args.getBoolean("pop", false);
        recent = args.getBoolean("recent", false);
        update = args.getBoolean("update", true);

        lockOrientation();
    }

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        setSubtitle(name);
        setHasOptionsMenu(true);

        view = (ViewGroup) inflater.inflate(R.layout.fragment_oauth, container, false);
        scroll = view.findViewById(R.id.scroll);

        // Get controls
        tvTitle = view.findViewById(R.id.tvTitle);
        tvPrivacy = view.findViewById(R.id.tvPrivacy);
        tvPrivacyApp = view.findViewById(R.id.tvPrivacyApp);
        etName = view.findViewById(R.id.etName);
        etEmail = view.findViewById(R.id.etEmail);
        etTenant = view.findViewById(R.id.etTenant);
        etZohoClientId = view.findViewById(R.id.etZohoClientId);
        etZohoClientSecret = view.findViewById(R.id.etZohoClientSecret);
        etZohoRedirectUri = view.findViewById(R.id.etZohoRedirectUri);
        cbInboundOnly = view.findViewById(R.id.cbInboundOnly);
        cbPop = view.findViewById(R.id.cbPop);
        cbRecent = view.findViewById(R.id.cbRecent);
        cbUpdate = view.findViewById(R.id.cbUpdate);
        tvEdge = view.findViewById(R.id.tvEdge);
        tvBrave = view.findViewById(R.id.tvBrave);
        btnOAuth = view.findViewById(R.id.btnOAuth);
        pbOAuth = view.findViewById(R.id.pbOAuth);
        tvConfiguring = view.findViewById(R.id.tvConfiguring);
        tvGmailHint = view.findViewById(R.id.tvGmailHint);
        tvGmailLoginHint = view.findViewById(R.id.tvGmailLoginHint);
        tvGmailLoginMax = view.findViewById(R.id.tvGmailLoginMax);
        tvOutlookHint = view.findViewById(R.id.tvOutlookHint);
        tvOutlookPasskeyHint = view.findViewById(R.id.tvOutlookPasskeyHint);

        tvError = view.findViewById(R.id.tvError);
        tvOfficeAuthHint = view.findViewById(R.id.tvOfficeAuthHint);
        btnSupport = view.findViewById(R.id.btnSupport);
        btnHelp = view.findViewById(R.id.btnHelp);

        grpTenant = view.findViewById(R.id.grpTenant);
        grpZoho = view.findViewById(R.id.grpZoho);
        grpError = view.findViewById(R.id.grpError);

        // Wire controls

        tvPrivacy.setPaintFlags(tvPrivacy.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tvPrivacy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (TextUtils.isEmpty(privacy))
                    Helper.view(v.getContext(), Uri.parse(Helper.PRIVACY_URI), false);
                else
                    Helper.view(v.getContext(), Uri.parse(privacy), false);
            }
        });

        tvPrivacy.setVisibility(TextUtils.isEmpty(privacy) ? View.GONE : View.VISIBLE);
        tvPrivacyApp.setPaintFlags(tvPrivacyApp.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        String appPrivacyUri = (graphMailApi || ZohoOAuth.isProviderId(id)
                ? Helper.FAQ_BASE_URI : Helper.PRIVACY_URI);
        tvPrivacyApp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Helper.view(v.getContext(), Uri.parse(appPrivacyUri), false);
            }
        });

        cbPop.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                cbRecent.setVisibility(checked && "gmail".equals(id) ? View.VISIBLE : View.GONE);
            }
        });

        if ("gmail".equals(id)) {
            // https://developers.google.com/identity/branding-guidelines
            final Context context = getContext();
            final boolean dark = Helper.isDarkTheme(context);
            int dp12 = Helper.dp2pixels(context, 12);
            int dp24 = Helper.dp2pixels(context, 24);
            Drawable g = ContextCompat.getDrawable(context, R.drawable.google_logo);
            g.setBounds(0, 0, g.getIntrinsicWidth(), g.getIntrinsicHeight());
            btnOAuth.setCompoundDrawablesRelative(g, null, null, null);
            btnOAuth.setCompoundDrawablePadding(dp24);
            btnOAuth.setText(R.string.title_setup_google_sign_in);
            btnOAuth.setTextColor(new ColorStateList(
                    new int[][]{
                            new int[]{android.R.attr.state_enabled},
                            new int[]{-android.R.attr.state_enabled},
                    },
                    new int[]{
                            dark ? Color.WHITE : Color.DKGRAY, // 0xff444444
                            Color.LTGRAY // 0xffcccccc
                    }
            ));
            btnOAuth.setBackground(ContextCompat.getDrawable(context, dark
                    ? R.drawable.google_signin_background_dark
                    : R.drawable.google_signin_background_light));
            btnOAuth.setPaddingRelative(dp12, 0, dp12, 0);
        }


        boolean edge = false;
        boolean brave = false;
        try {
            PackageManager pm = getContext().getPackageManager();
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .setData(Uri.parse("http://example.com"));
            int flags = (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ? 0 : PackageManager.MATCH_ALL);
            List<ResolveInfo> browsers = pm.queryIntentActivities(intent, flags);
            for (ResolveInfo browser : browsers)
                if (TextUtils.isEmpty(browser.activityInfo.packageName))
                    continue;
                else if (browser.activityInfo.packageName.startsWith("com.brave.browser")) // _beta _nightly
                    brave = true;
                else if (browser.activityInfo.packageName.startsWith("com.microsoft.emmx")) // .beta .canary .dev
                    edge = true;
                else if (browser.activityInfo.packageName.startsWith("org.mozilla.firefox"))
                    ;
                else if (browser.activityInfo.packageName.equals("org.mozilla.focus"))
                    ;
                else if (browser.activityInfo.packageName.equals("com.duckduckgo.mobile.android"))
                    ;
        } catch (Throwable ex) {
            Log.e(ex);
        }

        tvEdge.setVisibility(edge ? View.VISIBLE : View.GONE);
        tvBrave.setVisibility(brave ? View.VISIBLE : View.GONE);

        btnOAuth.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onAuthorize(graphMailApi);
            }
        });

        btnSupport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Helper.view(v.getContext(), Helper.getSupportUri(v.getContext(), "OAuth:support"), false);
            }
        });

        btnHelp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    EmailProvider provider = EmailProvider.getProvider(v.getContext(), id);
                    Helper.view(v.getContext(), Uri.parse(provider.link), false);
                } catch (Throwable ex) {
                    Log.e(ex);
                }
            }
        });

        boolean isOutlook = ("office365".equals(id) || "office365pcke".equals(id) || "outlookgraph".equals(id) || "outlook".equals(id));
        boolean zoho = ZohoOAuth.isProviderId(id);

        // Initialize
        int rationale = NativeApiSetupCopy.getRationaleResource(id, graphMailApi);
        if (rationale != 0)
            tvTitle.setText(rationale);
        else
            tvTitle.setText(getString(R.string.title_setup_oauth_rationale, name));
        etName.setVisibility(askAccount ? View.VISIBLE : View.GONE);
        etEmail.setVisibility(askAccount ? View.VISIBLE : View.GONE);
        grpTenant.setVisibility(askTenant ? View.VISIBLE : View.GONE);
        grpZoho.setVisibility(zoho ? View.VISIBLE : View.GONE);
        cbInboundOnly.setVisibility(graphMailApi ? View.GONE : View.VISIBLE);
        cbPop.setVisibility(pop && !graphMailApi ? View.VISIBLE : View.GONE);
        cbRecent.setVisibility(View.GONE);
        pbOAuth.setVisibility(View.GONE);
        tvConfiguring.setVisibility(View.GONE);
        tvGmailHint.setVisibility("gmail".equals(id) ? View.VISIBLE : View.GONE);
        tvGmailLoginHint.setVisibility("gmail".equals(id) ? View.VISIBLE : View.GONE);
        tvGmailLoginMax.setVisibility("gmail".equals(id) ? View.VISIBLE : View.GONE);
        tvOutlookHint.setVisibility("outlookgraph".equals(id) && !graphMailApi ? View.VISIBLE : View.GONE);
        tvOutlookPasskeyHint.setVisibility(isOutlook && !graphMailApi ? View.VISIBLE : View.GONE);
        hideError();

        etName.setText(personal);
        etEmail.setText(address);
        etTenant.setText(null);
        etZohoClientId.setText(null);
        etZohoClientSecret.setText(null);
        etZohoRedirectUri.setText(ZohoOAuth.DEFAULT_REDIRECT_URI);
        cbInboundOnly.setChecked(false);
        cbPop.setChecked(false);
        cbRecent.setChecked(false);
        cbUpdate.setChecked(update);

        if (zoho && zohoAccountId > 0)
            loadZohoAccountSettings(zohoAccountId);

        return view;
    }

    private void loadZohoAccountSettings(long accountId) {
        Bundle args = new Bundle();
        args.putLong("account", accountId);
        btnOAuth.setEnabled(false);
        new SimpleTask<ZohoState>() {
            @Override
            protected ZohoState onExecute(Context context, Bundle args) throws Throwable {
                EntityAccount account = DB.getInstance(context).account().getAccount(args.getLong("account"));
                if (account == null || !account.isZohoApi())
                    throw new IllegalArgumentException("Zoho account credentials are unavailable");
                return ZohoState.jsonDeserialize(account.password);
            }

            @Override
            protected void onExecuted(Bundle args, ZohoState state) {
                if (state.clientId != null)
                    etZohoClientId.setText(state.clientId);
                if (state.clientSecret != null)
                    etZohoClientSecret.setText(state.clientSecret);
                if (state.redirectUri != null)
                    etZohoRedirectUri.setText(state.redirectUri);
                btnOAuth.setEnabled(true);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                btnOAuth.setEnabled(true);
                showError(ex);
            }
        }.execute(this, args, "setup:zoho-account-settings");
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        try {
            switch (requestCode) {
                case ActivitySetup.REQUEST_OAUTH:
                    Helper.setAuthenticated(getContext());
                    if (resultCode == RESULT_OK && data != null)
                        onHandleOAuth(data);
                    else
                        onHandleCancel();
                    break;
                case ActivitySetup.REQUEST_DONE:
                    finish();
                    break;
            }
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    private void onAuthorize(boolean graph) {
        try {
            if (graphMailApi)
                graph = true;
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                return;

            final boolean zoho = (!graph && ZohoOAuth.isProviderId(id));

            if (askAccount) {
                String name = etName.getText().toString().trim();
                String email = etEmail.getText().toString().trim();

                if (EntityAccount.isTestUser(getContext(), email)) {
                    EntityAccount.createTestAccount(this, email, new Runnable() {
                        @Override
                        public void run() {
                            finish();
                        }
                    });
                    return;
                }

                if (TextUtils.isEmpty(name))
                    throw new IllegalArgumentException(getString(R.string.title_no_name));

                if (TextUtils.isEmpty(email))
                    throw new IllegalArgumentException(getString(R.string.title_no_email));

                int backslash = email.indexOf('\\');
                if (backslash > 0)
                    email = email.substring(0, backslash);

                if (!Helper.EMAIL_ADDRESS.matcher(email).matches())
                    throw new IllegalArgumentException(getString(R.string.title_email_invalid, email));
            }

            final String zohoClientId = (zoho
                    ? ZohoOAuth.validateClientId(etZohoClientId.getText().toString()) : null);
            final String zohoClientSecret = (zoho
                    ? ZohoOAuth.validateClientSecret(etZohoClientSecret.getText().toString()) : null);
            final String zohoRedirectUri = (zoho
                    ? ZohoOAuth.validateRedirectUri(etZohoRedirectUri.getText().toString()) : null);

            etName.clearFocus();
            etEmail.clearFocus();
            etTenant.clearFocus();
            Helper.hideKeyboard(view);

            setEnabled(false);
            btnOAuth.setEnabled(false);
            pbOAuth.setVisibility(View.VISIBLE);
            hideError();

            Log.breadcrumb("onAuthorize", "id", id);

            final Context context = getContext();
            EmailProvider provider = EmailProvider.getProvider(context, id);
            EmailProvider.OAuth oauth = getOAuthProfile(provider, graph);
            final String[] requestedScopes = getRequestedScopes(oauth.scopes, graphMailApi && graph);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            boolean oauth_tabs = prefs.getBoolean("oauth_tabs", false);

            AppAuthConfiguration.Builder appAuthConfig = new AppAuthConfiguration.Builder();

            AuthorizationService authService;
            try {
                appAuthConfig.setBrowserMatcher(getBrowserMatcher(context, oauth_tabs, provider));
                authService = new AuthorizationService(context, appAuthConfig.build());
            } catch (Throwable ex) {
                /*
                    Unihertz, works with Chrome
                    java.lang.SecurityException: Not allowed to bind to service Intent { act=android.support.customtabs.action.CustomTabsService pkg=org.mozilla.focus }
                        at android.app.ContextImpl.bindServiceCommon(ContextImpl.java:1985)
                        at android.app.ContextImpl.bindService(ContextImpl.java:1897)
                        at android.content.ContextWrapper.bindService(ContextWrapper.java:812)
                        at android.content.ContextWrapper.bindService(ContextWrapper.java:812)
                        at androidx.browser.customtabs.CustomTabsClient.bindCustomTabsService(SourceFile:26)
                        at net.openid.appauth.browser.CustomTabManager.bind(SourceFile:27)
                        at net.openid.appauth.AuthorizationService.<init>(SourceFile:12)
                        at net.openid.appauth.AuthorizationService.<init>(SourceFile:4)
                        at eu.faircode.email.FragmentOAuth.onAuthorize(SourceFile:431)
                 */
                Log.e(ex);
                appAuthConfig.setBrowserMatcher(getBrowserMatcher(context, false, provider));
                authService = new AuthorizationService(context, appAuthConfig.build());
            }

            String authorizationEndpoint = (zoho ? ZohoOAuth.getAuthorizationEndpoint() : oauth.authorizationEndpoint);
            String tokenEndpoint = (zoho ? ZohoOAuth.getTokenEndpoint() : oauth.tokenEndpoint);
            String tenant = etTenant.getText().toString().trim();

            if (TextUtils.isEmpty(tenant))
                tenant = "common";

            authorizationEndpoint = authorizationEndpoint.replace("{tenant}", tenant);
            tokenEndpoint = tokenEndpoint.replace("{tenant}", tenant);

            AuthorizationServiceConfiguration serviceConfig = new AuthorizationServiceConfiguration(
                    Uri.parse(authorizationEndpoint),
                    Uri.parse(tokenEndpoint));

            int random = Math.abs(new SecureRandom().nextInt());
            long expire = new Date().getTime() + OAUTH_TIMEOUT;
            AuthState authState = new AuthState(serviceConfig);
            String key = "oauth." + provider.id + (graph ? ":graph" : "");
            JSONObject jauthstate = authState.jsonSerialize();
            jauthstate.put(FAIREMAIL_RANDOM, random);
            jauthstate.put(FAIREMAIL_EXPIRE, expire);
            prefs.edit().putString(key, jauthstate.toString()).apply();

            Map<String, String> params = new LinkedHashMap<>();
            if (oauth.parameters != null)
                params.putAll(oauth.parameters);
            if (zoho)
                params.put("access_type", "offline");

            String clientId = (zoho ? zohoClientId : oauth.clientId);
            Uri redirectUri = Uri.parse(zoho ? zohoRedirectUri : oauth.redirectUri);
            if ("gmail".equals(id) && BuildConfig.DEBUG && false) {
                clientId = "803253368361-hr8kelm53hqodj7c6brdjeb2ctn5jg3p.apps.googleusercontent.com";
                redirectUri = Uri.parse("eu.faircode.email.debug:/");
            }

            // https://openid.net/specs/openid-connect-core-1_0.html#AuthRequest
            AuthorizationRequest.Builder authRequestBuilder =
                    new AuthorizationRequest.Builder(
                            serviceConfig,
                            clientId,
                            ResponseTypeValues.CODE,
                            redirectUri)
                            .setScopes(zoho ? new String[]{ZohoOAuth.SCOPES} : requestedScopes)
                            .setState(provider.id + ":" + random + ":" + (graph ? ":graph" : ""))
                            .setAdditionalParameters(params);

            if (askAccount) {
                String address = etEmail.getText().toString().trim();
                int backslash = address.indexOf('\\');
                if (backslash > 0)
                    authRequestBuilder.setLoginHint(address.substring(0, backslash));
                else
                    authRequestBuilder.setLoginHint(address);
            }

            if (!TextUtils.isEmpty(oauth.prompt))
                authRequestBuilder.setPrompt(oauth.prompt);

            AuthorizationRequest authRequest = authRequestBuilder.build();

            if (zoho)
                EntityLog.log(context, "OAuth request provider=" + provider.id);
            else
                EntityLog.log(context, "OAuth request provider=" + provider.id + " uri=" + authRequest.toUri());
            Intent authIntent;
            try {
                authIntent = authService.getAuthorizationRequestIntent(authRequest);
            } catch (ActivityNotFoundException ex) {
                Log.w(ex);
                authIntent =
                        AuthorizationManagementActivity.createStartForResultIntent(
                                context, authRequest,
                                new Intent(Intent.ACTION_VIEW, authRequest.toUri()));
            }

            startActivityForResult(authIntent, ActivitySetup.REQUEST_OAUTH);
        } catch (Throwable ex) {
            showError(ex);
        }
    }

    static String[] getRequestedScopes(String[] configured, boolean graphMailApi) {
        if (!graphMailApi)
            return configured;
        java.util.LinkedHashSet<String> scopes = new java.util.LinkedHashSet<>();
        if (configured != null)
            Collections.addAll(scopes, configured);
        scopes.add("openid");
        scopes.add("profile");
        scopes.add("email");
        scopes.add("https://graph.microsoft.com/Mail.ReadWrite");
        return scopes.toArray(new String[0]);
    }

    private EmailProvider.OAuth getOAuthProfile(EmailProvider provider, boolean graph) {
        if (graphMailApi)
            return GraphOAuth.getProfile();
        if (EntityAccount.isOutlook(provider.id)) {
            EmailProvider.OAuth legacy = (graph ? provider.legacyGraph : provider.legacyOauth);
            if (legacy != null)
                return legacy;
        }
        return (graph ? provider.graph : provider.oauth);
    }

    private static EmailProvider.OAuth getGraphOAuthProfile(EmailProvider provider) {
        if (EntityAccount.isOutlook(provider.id) && provider.legacyGraph != null)
            return provider.legacyGraph;
        return provider.graph;
    }

    private BrowserMatcher getBrowserMatcher(Context context, boolean tabs, EmailProvider provider) {
        return new BrowserMatcher() {
            // https://github.com/openid/AppAuth-Android/issues/116
            final BrowserMatcher SBROWSER = new VersionedBrowserMatcher(
                    Browsers.SBrowser.PACKAGE_NAME,
                    Browsers.SBrowser.SIGNATURE_SET,
                    false,
                    VersionRange.atMost("5.3"));
            final BrowserMatcher SBROWSER_TAB = new VersionedBrowserMatcher(
                    Browsers.SBrowser.PACKAGE_NAME,
                    Browsers.SBrowser.SIGNATURE_SET,
                    true,
                    VersionRange.atMost("5.3"));

            @Override
            public boolean matches(@NonNull BrowserDescriptor descriptor) {

                if (BuildConfig.DEBUG && false) {
                    boolean test = (descriptor.useCustomTab &&
                            "com.duckduckgo.mobile.android".equals(descriptor.packageName));
                    Log.i("MMM " + descriptor.packageName + ":" + descriptor.useCustomTab + "=" + test);
                    return test;
                }

                boolean accept = !(SBROWSER.matches(descriptor) || SBROWSER_TAB.matches(descriptor));

                if (descriptor.useCustomTab && !tabs)
                    accept = false;

                EntityLog.log(context,
                        "OAuth browser=" + descriptor.packageName +
                                ":" + descriptor.version +
                                " tabs=" + descriptor.useCustomTab + "/" + tabs +
                                " accept=" + accept +
                                " provider=" + provider.id);
                return accept;
            }
        };
    }

    private void onHandleOAuth(@NonNull Intent data) {
        try {
            if (!graphMailApi && !ZohoOAuth.isProviderId(id))
                setEnabled(true);

            Log.breadcrumb("onHandleOAuth", "id", id);

            AuthorizationResponse auth = AuthorizationResponse.fromIntent(data);
            if (auth == null) {
                AuthorizationException ex = AuthorizationException.fromIntent(data);
                if (ex == null)
                    throw new IllegalArgumentException("No response data");
                else
                    throw ex;
            }

            String id = auth.state.split(":")[0];
            int returnedRandom = Integer.parseInt(auth.state.split(":")[1]);
            boolean graph = auth.state.endsWith(":graph");
            final EmailProvider provider = EmailProvider.getProvider(getContext(), id);
            EmailProvider.OAuth oauth = getOAuthProfile(provider, graph);
            EmailProvider.OAuth graphOauth = getGraphOAuthProfile(provider);

            if (graphOauth != null &&
                    graphOauth.enabled &&
                    !graph)
                setEnabled(false);

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
            String key = "oauth." + provider.id + (graph ? ":graph" : "");
            String json = prefs.getString(key, null);
            JSONObject jauthstate = new JSONObject(json);
            int random = jauthstate.optInt(FAIREMAIL_RANDOM, -1);
            long expire = jauthstate.optLong(FAIREMAIL_EXPIRE, -1);
            jauthstate.remove(FAIREMAIL_RANDOM);
            prefs.edit().remove(key).apply();
            long now = new Date().getTime();

            if (random != returnedRandom)
                throw new SecurityException("random " + random + " <> " + returnedRandom);
            if (expire < now)
                throw new SecurityException("Session expired " + new Date(expire) + " < " + new Date(now));

            final AuthState authState = AuthState.jsonDeserialize(jauthstate);

            Log.i("OAuth get token provider=" + provider.id + " state=" + auth.state);
            authState.update(auth, null);

            if (ZohoOAuth.isProviderId(provider.id)) {
                if (graph)
                    throw new IllegalArgumentException("Zoho Mail does not use Microsoft Graph OAuth");
                if (auth.request == null || TextUtils.isEmpty(auth.request.codeVerifier) ||
                        auth.request.redirectUri == null || TextUtils.isEmpty(auth.request.clientId))
                    throw new SecurityException("Zoho PKCE callback did not match the configured redirect");
                String clientId = ZohoOAuth.validateClientId(auth.request.clientId);
                String enteredClientId = ZohoOAuth.validateClientId(etZohoClientId.getText().toString());
                if (!clientId.equals(enteredClientId))
                    throw new SecurityException("Zoho Client ID changed while authorization was in progress");
                String clientSecret = ZohoOAuth.validateClientSecret(etZohoClientSecret.getText().toString());
                String requestRedirect = ZohoOAuth.validateRedirectUri(auth.request.redirectUri.toString());
                String enteredRedirect = ZohoOAuth.validateRedirectUri(etZohoRedirectUri.getText().toString());
                if (!requestRedirect.equals(enteredRedirect))
                    throw new SecurityException("Zoho Redirect URI changed while authorization was in progress");
                Map<String, String> callbackParameters = (auth.additionalParameters == null
                        ? Collections.<String, String>emptyMap() : auth.additionalParameters);
                String accountsServer = ZohoOAuth.getAccountsServer(callbackParameters);
                onZohoAuthorized(auth.authorizationCode, auth.request.codeVerifier,
                        requestRedirect, accountsServer, clientId, clientSecret,
                        etName.getText().toString().trim(), etEmail.getText().toString().trim(),
                        cbUpdate.isChecked());
                return;
            }

            AuthorizationService authService = new AuthorizationService(getContext());

            ClientAuthentication clientAuth;
            if (oauth.clientSecret == null)
                clientAuth = NoClientAuthentication.INSTANCE;
            else
                clientAuth = new ClientSecretPost(oauth.clientSecret);

            TokenRequest.Builder builder = new TokenRequest.Builder(
                    auth.request.configuration,
                    auth.request.clientId)
                    .setGrantType(GrantTypeValues.AUTHORIZATION_CODE)
                    .setRedirectUri(auth.request.redirectUri)
                    .setCodeVerifier(auth.request.codeVerifier)
                    .setAuthorizationCode(auth.authorizationCode)
                    .setAdditionalParameters(Collections.<String, String>emptyMap())
                    .setNonce(auth.request.nonce);

            if (oauth.tokenScopes)
                builder.setScope(TextUtils.join(" ", getRequestedScopes(oauth.scopes, graphMailApi && graph)));

            TokenRequest request = builder.build();

            authService.performTokenRequest(
                    request,
                    clientAuth,
                    new AuthorizationService.TokenResponseCallback() {
                        @Override
                        public void onTokenRequestCompleted(TokenResponse access, AuthorizationException error) {
                            try {
                                if (access == null)
                                    throw error;

                                Set<String> scopeSet = access.getScopeSet();
                                String[] scopes = (scopeSet == null ? new String[0] : scopeSet.toArray(new String[0]));
                                Log.i("OAuth got token provider=" + provider.id +
                                        " state=" + auth.state +
                                        " scopes=" + TextUtils.join(",", scopes));

                                if (TextUtils.isEmpty(access.refreshToken))
                                    throw new IllegalStateException("No refresh token");

                                authState.update(access, null);

                                if (graphMailApi) {
                                    if (!graph)
                                        throw new SecurityException("Graph Mail API callback did not use the Microsoft Graph OAuth profile");
                                    onGraphMailAuthorized(access.accessToken, access.idToken, authState);
                                    return;
                                }

                                if (graphOauth == null || !graphOauth.enabled)
                                    onOAuthorized(
                                            new String[]{access.accessToken},
                                            new String[]{access.idToken},
                                            new AuthState[]{authState});
                                else {
                                    if (graph) {
                                        String key0 = "oauth." + provider.id;
                                        String json0 = prefs.getString(key0, null);
                                        prefs.edit().remove(key0).apply();
                                        AuthState state0 = AuthState.jsonDeserialize(json0);
                                        onOAuthorized(
                                                new String[]{state0.getAccessToken(), authState.getAccessToken()},
                                                new String[]{state0.getIdToken(), authState.getIdToken()},
                                                new AuthState[]{state0, authState});
                                    } else {
                                        prefs.edit().putString("oauth." + provider.id, authState.jsonSerializeString()).apply();
                                        onAuthorize(true);
                                    }
                                }
                            } catch (Throwable ex) {
                                showError(ex);
                            }
                        }
                    });
        } catch (Throwable ex) {
            showError(ex);
        }
    }

    private void onZohoAuthorized(String code, String verifier, String redirectUri,
                                  String accountsServer, String clientId, String clientSecret,
                                  String requestedName,
                                  String requestedAddress, boolean updateExisting) {
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
            return;

        Bundle args = new Bundle();
        args.putString("requestedName", requestedName);
        args.putString("requestedAddress", requestedAddress);
        args.putBoolean("update", updateExisting);

        new SimpleTask<Void>() {
            @Override
            protected void onPreExecute(Bundle args) {
                setEnabled(false);
                btnOAuth.setEnabled(false);
                tvConfiguring.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                tvConfiguring.setVisibility(View.GONE);
            }

            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                ZohoState state = ZohoOAuth.exchange(context, code, verifier, redirectUri,
                        accountsServer, clientId, clientSecret);
                ZohoApi discovery = new ZohoApi(state.accessToken, state.apiDomain);
                discovery.setProtocolLogContext(context);
                List<ZohoApi.Account> mailboxes = discovery.listAccounts();

                String requested = args.getString("requestedAddress");
                ZohoApi.Account mailbox = null;
                if (!TextUtils.isEmpty(requested))
                    for (ZohoApi.Account candidate : mailboxes)
                        if (requested.trim().equalsIgnoreCase(candidate.email)) {
                            mailbox = candidate;
                            break;
                        }
                if (mailbox == null && TextUtils.isEmpty(requested) && !mailboxes.isEmpty())
                    mailbox = mailboxes.get(0);
                if (mailbox == null)
                    throw new IllegalArgumentException("The selected address was not found in the authorized Zoho mailboxes");
                if (!Helper.EMAIL_ADDRESS.matcher(mailbox.email).matches())
                    throw new IllegalArgumentException("Zoho returned an invalid mailbox address");

                String user = mailbox.email.trim();
                String displayName = (TextUtils.isEmpty(args.getString("requestedName"))
                        ? mailbox.displayName : args.getString("requestedName").trim());
                if (TextUtils.isEmpty(displayName))
                    displayName = user.substring(0, user.indexOf('@'));
                state.accountId = mailbox.id;
                state.email = mailbox.email;
                state.displayName = displayName;
                String password = state.jsonSerializeString();

                ZohoApi api = new ZohoApi(state.accessToken, state.apiDomain, state.accountId);
                api.setProtocolLogContext(context);
                List<ZohoApi.Folder> remoteFolders = api.listFolders();
                boolean hasInbox = false;
                for (ZohoApi.Folder folder : remoteFolders)
                    if (EntityFolder.INBOX.equals(getZohoFolderType(folder)))
                        hasInbox = true;
                if (!hasInbox)
                    throw new IllegalArgumentException("Zoho did not return an Inbox folder");

                DB db = DB.getInstance(context);
                EntityAccount existing = null;
                Long accountId;
                try {
                    db.beginTransaction();
                    if (args.getBoolean("update"))
                        for (EntityAccount candidate : db.account().getAccounts())
                            if (candidate.isZohoApi() && user.equalsIgnoreCase(candidate.user)) {
                                if (existing != null)
                                    throw new IllegalArgumentException("More than one Zoho API account matches this address");
                                existing = candidate;
                            }

                    EntityAccount account;
                    if (existing == null) {
                        EntityAccount primary = db.account().getPrimaryAccount();
                        account = new EntityAccount();
                        account.protocol = EntityAccount.TYPE_IMAP;
                        account.host = "imap.zoho.com";
                        account.encryption = EmailService.ENCRYPTION_SSL;
                        account.port = 993;
                        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
                        account.provider = ZohoOAuth.PROVIDER_ID;
                        account.user = user;
                        account.password = password;
                        account.name = NativeApiAccountNaming.forNewAccount(
                                ServiceAuthenticator.AUTH_TYPE_ZOHO, user);
                        account.synchronize = true;
                        account.primary = (primary == null);
                        account.created = new Date().getTime();
                        account.last_connected = account.created;
                        account.id = db.account().insertAccount(account);
                        accountId = account.id;
                    } else {
                        account = existing;
                        account.protocol = EntityAccount.TYPE_IMAP;
                        account.host = "imap.zoho.com";
                        account.encryption = EmailService.ENCRYPTION_SSL;
                        account.port = 993;
                        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
                        account.provider = ZohoOAuth.PROVIDER_ID;
                        account.user = user;
                        account.password = password;
                        account.synchronize = true;
                        db.account().updateAccount(account);
                        accountId = account.id;
                    }
                    boolean newAccount = (existing == null);
                    args.putLong("account", accountId);

                    EntityFolder inbox = null;
                    for (ZohoApi.Folder remote : remoteFolders) {
                        if (!remote.selectable)
                            continue;
                        String type = getZohoFolderType(remote);
                        EntityFolder folder = db.folder().getFolderByZohoId(accountId, remote.id);
                        if (folder == null)
                            folder = db.folder().getFolderByName(accountId, remote.name);
                        boolean created = (folder == null);
                        if (created) {
                            folder = new EntityFolder(remote.name, type);
                            folder.account = accountId;
                            folder.zoho_folder_id = remote.id;
                            folder.synchronize = (newAccount
                                    ? NativeApiFolderDefaults.shouldSynchronizeByDefault(type)
                                    : true);
                            NativeApiFolderDefaults.applyCreationDefaults(folder, newAccount, true);
                            folder.selectable = true;
                            folder.setSpecials(account);
                            folder.id = db.folder().insertFolder(folder);
                        } else {
                            folder.zoho_folder_id = remote.id;
                            folder.type = type;
                            // Rediscovery/reauthorization updates remote metadata only;
                            // preserve the user's existing folder sync selection.
                            if (EntityFolder.INBOX.equals(type)) {
                                folder.unified = true;
                                folder.notify = true;
                            }
                            db.folder().updateFolder(folder);
                        }
                        if (EntityFolder.INBOX.equals(type))
                            inbox = folder;
                        if (newAccount
                                ? NativeApiFolderDefaults.shouldQueueInitialSync(true, created, folder)
                                : (created && Boolean.TRUE.equals(folder.synchronize)))
                            EntityOperation.sync(context, folder.id, true);
                    }
                    if (inbox == null)
                        throw new IllegalArgumentException("Zoho did not return a selectable Inbox folder");

                    if (existing == null) {
                        EntityIdentity identity = new EntityIdentity();
                        identity.name = displayName;
                        identity.email = user;
                        identity.account = accountId;
                        identity.host = "smtp.zoho.com";
                        identity.encryption = EmailService.ENCRYPTION_SSL;
                        identity.port = 465;
                        identity.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
                        identity.provider = ZohoOAuth.PROVIDER_ID;
                        identity.user = user;
                        identity.password = password;
                        identity.synchronize = true;
                        identity.primary = true;
                        db.identity().insertIdentity(identity);
                        account.swipe_left = EntityMessage.SWIPE_ACTION_SEEN;
                        account.swipe_right = EntityMessage.SWIPE_ACTION_SEEN;
                        FragmentDialogSwipes.setDefaultFolderActions(context, account);
                        db.account().updateAccount(account);
                    } else {
                        boolean foundIdentity = false;
                        for (EntityIdentity identity : db.identity().getIdentities(accountId))
                            if (identity.isZohoApi() || ZohoOAuth.isProviderId(identity.provider)) {
                                identity.name = displayName;
                                identity.email = user;
                                identity.host = "smtp.zoho.com";
                                identity.encryption = EmailService.ENCRYPTION_SSL;
                                identity.port = 465;
                                identity.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
                                identity.provider = ZohoOAuth.PROVIDER_ID;
                                identity.user = user;
                                identity.password = password;
                                identity.synchronize = true;
                                db.identity().updateIdentity(identity);
                                foundIdentity = true;
                            }
                        if (!foundIdentity) {
                            EntityIdentity identity = new EntityIdentity();
                            identity.name = displayName;
                            identity.email = user;
                            identity.account = accountId;
                            identity.host = "smtp.zoho.com";
                            identity.encryption = EmailService.ENCRYPTION_SSL;
                            identity.port = 465;
                            identity.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
                            identity.provider = ZohoOAuth.PROVIDER_ID;
                            identity.user = user;
                            identity.password = password;
                            identity.synchronize = true;
                            identity.primary = true;
                            db.identity().insertIdentity(identity);
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }

                args.putBoolean("updated", existing != null);
                EntityLog.log(context, "Zoho Mail API account configured");
                ServiceSynchronize.eval(context, "Zoho Mail API authorization");
                FairEmailBackupAgent.dataChanged(context);
                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                pbOAuth.setVisibility(View.GONE);
                if (args.getBoolean("updated")) {
                    finish();
                    ToastEx.makeText(getContext(), R.string.title_setup_oauth_updated, Toast.LENGTH_LONG).show();
                } else {
                    setAuthorizationIdle();
                    FragmentDialogAccount fragment = new FragmentDialogAccount();
                    fragment.setArguments(args);
                    fragment.setTargetFragment(FragmentOAuth.this, ActivitySetup.REQUEST_DONE);
                    fragment.show(getParentFragmentManager(), "zoho:review");
                }
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                showError(ex);
            }
        }.execute(this, args, "setup:zoho-mail-api");
    }

    private void onGraphMailAuthorized(String accessToken, String idToken, AuthState authState) {
        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
            return;

        Bundle args = new Bundle();
        args.putString("requestedName", etName.getText().toString().trim());
        args.putString("requestedAddress", etEmail.getText().toString().trim());
        args.putBoolean("update", cbUpdate.isChecked());
        args.putString("authState", authState.jsonSerializeString());

        new SimpleTask<Void>() {
            @Override
            protected void onPreExecute(Bundle args) {
                setEnabled(false);
                btnOAuth.setEnabled(false);
                tvConfiguring.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                tvConfiguring.setVisibility(View.GONE);
            }

            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                String user = args.getString("requestedAddress");
                if (user != null)
                    user = user.trim();
                if (TextUtils.isEmpty(user) || !Helper.EMAIL_ADDRESS.matcher(user).matches())
                    throw new IllegalArgumentException("Enter a valid Microsoft mailbox address");
                String displayName = args.getString("requestedName");
                if (TextUtils.isEmpty(displayName))
                    displayName = user.substring(0, user.indexOf('@'));

                final String[] savedPassword = new String[]{args.getString("authState")};
                ServiceAuthenticator authenticator = new ServiceAuthenticator(
                        context, ServiceAuthenticator.AUTH_TYPE_GRAPH_API,
                        GraphOAuth.LEGACY_PROVIDER_ID, user, savedPassword[0],
                        new ServiceAuthenticator.IAuthenticated() {
                            @Override
                            public void onPasswordChanged(Context context, String newPassword) {
                                savedPassword[0] = newPassword;
                            }
                        });
                String token = authenticator.refreshToken(false);
                GraphMailApi api = new GraphMailApi(context, token);
                api.setTokenRefresher(rejected -> {
                    try {
                        return authenticator.refreshGraphTokenAfterUnauthorized();
                    } catch (org.json.JSONException ex) {
                        throw new java.io.IOException("Microsoft Graph mail token refresh failed", ex);
                    }
                });
                List<GraphMailApi.MailFolder> remoteFolders = api.listFolders();
                boolean hasInbox = false;
                for (GraphMailApi.MailFolder remote : remoteFolders)
                    if (EntityFolder.INBOX.equals(GraphFolderDefaults.getFolderType(remote.name)))
                        hasInbox = true;
                if (!hasInbox)
                    throw new IllegalArgumentException("Microsoft Graph did not return a selectable Inbox folder");

                String password = savedPassword[0];
                DB db = DB.getInstance(context);
                EntityAccount existing = null;
                Long accountId;
                try {
                    db.beginTransaction();
                    if (args.getBoolean("update"))
                        for (EntityAccount candidate : db.account().getAccounts())
                            if (candidate.isGraphApi() && user.equalsIgnoreCase(candidate.user)) {
                                if (existing != null)
                                    throw new IllegalArgumentException("More than one Graph API account matches this address");
                                existing = candidate;
                            }

                    EntityAccount account;
                    if (existing == null) {
                        EntityAccount primary = db.account().getPrimaryAccount();
                        account = new EntityAccount();
                        account.protocol = EntityAccount.TYPE_IMAP;
                        account.host = "graph.microsoft.com";
                        account.encryption = EmailService.ENCRYPTION_SSL;
                        account.port = 443;
                        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
                        account.provider = GraphOAuth.PROVIDER_ID;
                        account.user = user;
                        account.password = password;
                        account.name = NativeApiAccountNaming.forNewAccount(
                                ServiceAuthenticator.AUTH_TYPE_GRAPH_API, user);
                        account.synchronize = true;
                        account.primary = (primary == null);
                        account.created = new Date().getTime();
                        account.last_connected = account.created;
                        account.id = db.account().insertAccount(account);
                        accountId = account.id;
                    } else {
                        account = existing;
                        account.protocol = EntityAccount.TYPE_IMAP;
                        account.host = "graph.microsoft.com";
                        account.encryption = EmailService.ENCRYPTION_SSL;
                        account.port = 443;
                        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
                        account.provider = GraphOAuth.PROVIDER_ID;
                        account.user = user;
                        account.password = password;
                        account.synchronize = true;
                        db.account().updateAccount(account);
                        accountId = account.id;
                    }
                    boolean newAccount = (existing == null);
                    args.putLong("account", accountId);

                    EntityFolder inbox = null;
                    for (GraphMailApi.MailFolder remote : remoteFolders) {
                        String type = GraphFolderDefaults.getFolderType(remote.name);
                        EntityFolder folder = db.folder().getFolderByGraphId(accountId, remote.id);
                        if (folder == null)
                            folder = db.folder().getFolderByName(accountId, remote.name);
                        boolean created = (folder == null);
                        Long parentId = null;
                        if (!TextUtils.isEmpty(remote.parentId) && !"msgfolderroot".equalsIgnoreCase(remote.parentId)) {
                            EntityFolder parent = db.folder().getFolderByGraphId(accountId, remote.parentId);
                            if (parent != null)
                                parentId = parent.id;
                        }
                        if (created) {
                            folder = new EntityFolder(remote.name, type);
                            folder.account = accountId;
                            folder.graph_folder_id = remote.id;
                            folder.parent = parentId;
                            folder.selectable = true;
                            NativeApiFolderDefaults.applyCreationDefaults(folder, newAccount, true);
                            folder.setSpecials(account);
                            folder.id = db.folder().insertFolder(folder);
                        } else {
                            boolean remoteIdChanged = !TextUtils.equals(folder.graph_folder_id, remote.id);
                            folder.name = remote.name;
                            folder.type = type;
                            folder.graph_folder_id = remote.id;
                            folder.parent = parentId;
                            folder.selectable = true;
                            if (remoteIdChanged) {
                                folder.graph_page_link = null;
                                folder.graph_delta_link = null;
                            }
                            folder.setSpecials(account);
                            db.folder().updateFolder(folder);
                        }
                        if (remote.totalItemCount != null)
                            db.folder().setFolderTotal(folder.id, remote.totalItemCount);
                        if (EntityFolder.INBOX.equals(type))
                            inbox = folder;
                        if (created && Boolean.TRUE.equals(folder.synchronize) &&
                                !EntityFolder.INBOX.equals(type))
                            EntityOperation.sync(context, folder.id, true);
                    }
                    if (inbox == null)
                        throw new IllegalArgumentException("Microsoft Graph did not return a selectable Inbox folder");

                    if (existing == null) {
                        EntityIdentity identity = new EntityIdentity();
                        identity.name = displayName;
                        identity.email = user;
                        identity.account = accountId;
                        identity.host = "graph.microsoft.com";
                        identity.encryption = EmailService.ENCRYPTION_SSL;
                        identity.port = 443;
                        identity.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
                        identity.provider = GraphOAuth.PROVIDER_ID;
                        identity.user = user;
                        identity.password = password;
                        identity.synchronize = true;
                        identity.primary = true;
                        db.identity().insertIdentity(identity);
                        account.swipe_left = EntityMessage.SWIPE_ACTION_SEEN;
                        account.swipe_right = EntityMessage.SWIPE_ACTION_SEEN;
                        FragmentDialogSwipes.setDefaultFolderActions(context, account);
                        db.account().updateAccount(account);
                    } else {
                        boolean foundIdentity = false;
                        for (EntityIdentity identity : db.identity().getIdentities(accountId))
                            if (identity.isGraphApi()) {
                                identity.name = displayName;
                                identity.email = user;
                                identity.host = "graph.microsoft.com";
                                identity.encryption = EmailService.ENCRYPTION_SSL;
                                identity.port = 443;
                                identity.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
                                identity.provider = GraphOAuth.PROVIDER_ID;
                                identity.user = user;
                                identity.password = password;
                                identity.synchronize = true;
                                db.identity().updateIdentity(identity);
                                foundIdentity = true;
                            }
                        if (!foundIdentity) {
                            EntityIdentity identity = new EntityIdentity();
                            identity.name = displayName;
                            identity.email = user;
                            identity.account = accountId;
                            identity.host = "graph.microsoft.com";
                            identity.encryption = EmailService.ENCRYPTION_SSL;
                            identity.port = 443;
                            identity.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
                            identity.provider = GraphOAuth.PROVIDER_ID;
                            identity.user = user;
                            identity.password = password;
                            identity.synchronize = true;
                            identity.primary = true;
                            db.identity().insertIdentity(identity);
                        }
                    }
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }

                args.putBoolean("updated", existing != null);
                EntityLog.log(context, "Microsoft Graph API account configured");
                ServiceSynchronize.eval(context, "Microsoft Graph API authorization");
                FairEmailBackupAgent.dataChanged(context);
                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                pbOAuth.setVisibility(View.GONE);
                if (args.getBoolean("updated")) {
                    finish();
                    ToastEx.makeText(getContext(), R.string.title_setup_oauth_updated, Toast.LENGTH_LONG).show();
                } else {
                    setAuthorizationIdle();
                    FragmentDialogAccount fragment = new FragmentDialogAccount();
                    fragment.setArguments(args);
                    fragment.setTargetFragment(FragmentOAuth.this, ActivitySetup.REQUEST_DONE);
                    fragment.show(getParentFragmentManager(), "graph-mail:review");
                }
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                showError(ex);
            }
        }.execute(this, args, "setup:graph-mail-api");
    }

    private static String getZohoFolderType(ZohoApi.Folder folder) {
        return ZohoFolderDefaults.getFolderType(folder.name);
    }

    private void onOAuthorized(String[] accessToken, String[] idToken, AuthState[] state) {
        Log.breadcrumb("onOAuthorized", "id", id);

        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
            return;

        List<String> states = new ArrayList<>();
        for (AuthState s : state)
            states.add(s.jsonSerializeString());

        Bundle args = new Bundle();
        args.putString("id", id);
        args.putString("name", name);
        args.putStringArray("token", accessToken);
        args.putStringArray("jwt", idToken);
        args.putStringArray("state", states.toArray(new String[0]));
        args.putBoolean("askAccount", askAccount);
        args.putString("personal", etName.getText().toString().trim());
        args.putString("address", etEmail.getText().toString().trim());
        args.putBoolean("inbound_only", cbInboundOnly.isChecked());
        args.putBoolean("pop", cbPop.isChecked());
        args.putBoolean("recent", cbRecent.isChecked());
        args.putBoolean("update", cbUpdate.isChecked());

        new SimpleTask<Void>() {
            @Override
            protected void onPreExecute(Bundle args) {
                tvConfiguring.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                tvConfiguring.setVisibility(View.GONE);
            }

            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                String id = args.getString("id");
                String name = args.getString("name");
                String[] token = args.getStringArray("token");
                String[] jwt = args.getStringArray("jwt");
                String[] state = args.getStringArray("state");
                boolean askAccount = args.getBoolean("askAccount", false);
                String personal = args.getString("personal");
                String address = args.getString("address");
                boolean inbound_only = args.getBoolean("inbound_only");
                boolean pop = args.getBoolean("pop");
                boolean recent = args.getBoolean("recent");

                EmailProvider provider = EmailProvider.getProvider(context, id);
                if (provider.pop == null)
                    pop = false;
                EmailProvider.Server inbound = (pop ? provider.pop : provider.imap);
                String aprotocol = (pop ? (inbound.starttls ? "pop3" : "pop3s") : (inbound.starttls ? "imap" : "imaps"));
                int aencryption = (inbound.starttls ? EmailService.ENCRYPTION_STARTTLS : EmailService.ENCRYPTION_SSL);
                String iprotocol = (provider.smtp.starttls ? "smtp" : "smtps");
                int iencryption = (provider.smtp.starttls ? EmailService.ENCRYPTION_STARTTLS : EmailService.ENCRYPTION_SSL);

                /*
                 * Outlook shared mailbox
                 * Authenticate: main/shared account
                 * IMAP: shared account
                 * SMTP: main account
                 * https://docs.microsoft.com/en-us/exchange/client-developer/legacy-protocols/how-to-authenticate-an-imap-pop-smtp-application-by-using-oauth#sasl-xoauth2-authentication-for-shared-mailboxes-in-office-365
                 */

                String username;
                String sharedname;
                int backslash = address.indexOf('\\');
                if (backslash > 0) {
                    username = address.substring(0, backslash);
                    sharedname = address.substring(backslash + 1);
                } else {
                    username = address;
                    sharedname = null;
                }

                List<String> usernames = new ArrayList<>();
                usernames.add(sharedname == null ? username : sharedname);

                EntityLog.log(context, "OAuth id=" + id + " user=" + username + " shared=" + sharedname);

                if (token[0] != null && sharedname == null && !"gmail".equals(id)) {
                    // https://docs.microsoft.com/en-us/azure/active-directory/develop/access-tokens
                    String[] segments = token[0].split("\\.");
                    if (segments.length > 1)
                        try {
                            String payload = new String(Base64.decode(segments[1], Base64.DEFAULT));
                            EntityLog.log(context, "token payload=" + payload);
                            JSONObject jpayload = new JSONObject(payload);

                            if (jpayload.has("preferred_username")) {
                                String u = jpayload.getString("preferred_username");
                                if (!TextUtils.isEmpty(u) && !usernames.contains(u))
                                    usernames.add(u);
                            }

                            if (jpayload.has("unique_name")) {
                                String u = jpayload.getString("unique_name");
                                if (!TextUtils.isEmpty(u) && !usernames.contains(u))
                                    usernames.add(u);
                            }

                            if (jpayload.has("upn")) {
                                String u = jpayload.getString("upn");
                                if (!TextUtils.isEmpty(u) && !usernames.contains(u))
                                    usernames.add(u);
                            }
                        } catch (Throwable ex) {
                            Log.w(ex);
                        }
                }

                if (jwt[0] != null && sharedname == null) {
                    // https://docs.microsoft.com/en-us/azure/active-directory/develop/id-tokens
                    String[] segments = jwt[0].split("\\.");
                    if (segments.length > 1)
                        try {
                            // https://jwt.ms/
                            String payload = new String(Base64.decode(segments[1], Base64.DEFAULT));
                            EntityLog.log(context, "jwt payload=" + payload);
                            JSONObject jpayload = new JSONObject(payload);

                            if (jpayload.has("iat"))
                                Log.i("Issued at " + new Date(jpayload.getLong("iat") * 1000L));
                            if (jpayload.has("nbf"))
                                Log.i("Not before " + new Date(jpayload.getLong("nbf") * 1000L));
                            if (jpayload.has("exp"))
                                Log.i("Expiration time " + new Date(jpayload.getLong("exp") * 1000L));

                            if (jpayload.has("preferred_username")) {
                                String u = jpayload.getString("preferred_username");
                                if (!TextUtils.isEmpty(u) && !usernames.contains(u))
                                    usernames.add(u);
                            }

                            if (jpayload.has("email")) {
                                String u = jpayload.getString("email");
                                if (!TextUtils.isEmpty(u) && !usernames.contains(u))
                                    usernames.add(u);
                            }

                            if (jpayload.has("unique_name")) {
                                String u = jpayload.getString("unique_name");
                                if (!TextUtils.isEmpty(u) && !usernames.contains(u))
                                    usernames.add(u);
                            }

                            if (jpayload.has("verified_primary_email")) {
                                JSONArray jsecondary =
                                        jpayload.getJSONArray("verified_primary_email");
                                for (int i = 0; i < jsecondary.length(); i++) {
                                    String u = jsecondary.getString(i);
                                    if (!TextUtils.isEmpty(u) && !usernames.contains(u))
                                        usernames.add(u);
                                }
                            }

                            if (jpayload.has("verified_secondary_email")) {
                                JSONArray jsecondary =
                                        jpayload.getJSONArray("verified_secondary_email");
                                for (int i = 0; i < jsecondary.length(); i++) {
                                    String u = jsecondary.getString(i);
                                    if (!TextUtils.isEmpty(u) && !usernames.contains(u))
                                        usernames.add(u);
                                }
                            }
                        } catch (Throwable ex) {
                            Log.e(ex);
                        }
                }

                if (usernames.size() > 1)
                    for (String alt : usernames) {
                        EntityLog.log(context, "Trying username=" + alt);
                        try {
                            try (EmailService aservice = new EmailService(context,
                                    aprotocol, null, aencryption, false, false, false,
                                    EmailService.PURPOSE_CHECK, true)) {
                                aservice.connect(
                                        false, inbound.host, inbound.port,
                                        AUTH_TYPE_OAUTH, provider.id,
                                        alt, state[0],
                                        null, null);
                            }
                            if (state.length == 1) {
                                try (EmailService iservice = new EmailService(context,
                                        iprotocol, null, iencryption, false, false, false,
                                        EmailService.PURPOSE_CHECK, true)) {
                                    iservice.connect(
                                            false, provider.smtp.host, provider.smtp.port,
                                            AUTH_TYPE_OAUTH, provider.id,
                                            alt, state[0],
                                            null, null);
                                }
                            }
                            EntityLog.log(context, "Using username=" + alt);
                            username = alt;
                            break;
                        } catch (Throwable ex) {
                            Log.w(ex);
                        }
                    }

                List<Pair<String, String>> identities = new ArrayList<>();

                if (askAccount)
                    identities.add(new Pair<>(username, personal));
                else if ("mailru".equals(id)) {
                    URL url = new URL("https://oauth.mail.ru/userinfo?access_token=" + token[0]);
                    Log.i("GET " + url);
                    HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");
                    connection.setReadTimeout(MAILRU_TIMEOUT);
                    connection.setConnectTimeout(MAILRU_TIMEOUT);
                    ConnectionHelper.setUserAgent(context, connection);
                    connection.connect();

                    try {
                        int status = connection.getResponseCode();
                        if (status != HttpsURLConnection.HTTP_OK)
                            throw new FileNotFoundException("Error " + status + ": " + connection.getResponseMessage());

                        String json = Helper.readStream(connection.getInputStream());
                        Log.i("json=" + json);
                        JSONObject data = new JSONObject(json);
                        name = data.getString("name");
                        username = data.getString("email");
                        identities.add(new Pair<>(username, name));
                    } finally {
                        connection.disconnect();
                    }

                } else
                    throw new IllegalArgumentException("Unknown provider=" + id);

                ConnectivityManager cm = Helper.getSystemService(context, ConnectivityManager.class);
                NetworkInfo ani = (cm == null ? null : cm.getActiveNetworkInfo());
                if (ani == null || !ani.isConnected())
                    throw new IllegalArgumentException(context.getString(R.string.title_no_internet));

                if (pop && recent && "gmail".equals(id))
                    username = "recent:" + username;

                EntityLog.log(context, "OAuth username=" + username + " shared=" + sharedname);
                for (Pair<String, String> identity : identities)
                    EntityLog.log(context, "OAuth identity=" + identity.first + "/" + identity.second);

                List<EntityFolder> folders;

                EntityLog.log(context, "OAuth checking IMAP/POP3 provider=" + provider.id);
                try (EmailService aservice = new EmailService(context,
                        aprotocol, null, aencryption, false, false, false,
                        EmailService.PURPOSE_CHECK, true)) {
                    aservice.connect(
                            false, inbound.host, inbound.port,
                            AUTH_TYPE_OAUTH, provider.id,
                            sharedname == null ? username : sharedname, state[0],
                            null, null);

                    if (pop)
                        folders = EntityFolder.getPopFolders(context);
                    else
                        folders = aservice.getFolders(inbound.host);
                }

                Long max_size = null;
                if (!inbound_only && state.length == 1) {
                    EntityLog.log(context, "OAuth checking SMTP provider=" + provider.id);

                    if (false && BuildConfig.DEBUG)
                        throw new AuthenticationFailedException("535 5.7.139 Authentication unsuccessful, SmtpClientAuthentication is disabled for the Tenant.");

                    try (EmailService iservice = new EmailService(context,
                            iprotocol, null, iencryption, false, false, false,
                            EmailService.PURPOSE_CHECK, true)) {
                        iservice.connect(
                                false, provider.smtp.host, provider.smtp.port,
                                AUTH_TYPE_OAUTH, provider.id,
                                username, state[0],
                                null, null);
                        max_size = iservice.getMaxSize();
                    }
                }

                EntityLog.log(context, "OAuth passed provider=" + provider.id);

                EntityAccount update = null;
                int protocol = (pop ? EntityAccount.TYPE_POP : EntityAccount.TYPE_IMAP);
                DB db = DB.getInstance(context);
                try {
                    db.beginTransaction();

                    if (args.getBoolean("update")) {
                        List<EntityAccount> accounts = db.account().getAccounts(sharedname == null ? username : sharedname, protocol);
                        if (accounts != null && accounts.size() == 1)
                            update = accounts.get(0);
                    }

                    if (update == null) {
                        EntityAccount primary = db.account().getPrimaryAccount();

                        // Create account
                        EntityAccount account = new EntityAccount();

                        account.protocol = protocol;
                        account.host = inbound.host;
                        account.encryption = aencryption;
                        account.port = inbound.port;
                        account.auth_type = AUTH_TYPE_OAUTH;
                        account.provider = provider.id;
                        account.user = (sharedname == null ? username : sharedname);
                        account.password = state[0];

                        int at = account.user.indexOf('@');
                        String user = account.user.substring(0, at);

                        if (TextUtils.isEmpty(provider.name))
                            account.name = account.user;
                        else
                            account.name = provider.name + "/" + user;

                        account.synchronize = true;
                        account.primary = (primary == null);

                        if (provider.keepalive > 0)
                            account.poll_interval = provider.keepalive;
                        account.keep_alive_noop = provider.noop;

                        account.partial_fetch = provider.partial;
                        account.raw_fetch = provider.raw;

                        account.created = new Date().getTime();
                        account.last_connected = account.created;

                        account.id = db.account().insertAccount(account);
                        args.putLong("account", account.id);
                        EntityLog.log(context, "OAuth account=" + account.name);

                        // Create folders
                        for (EntityFolder folder : folders) {
                            EntityFolder existing = db.folder().getFolderByName(account.id, folder.name);
                            if (existing == null) {
                                folder.account = account.id;
                                folder.setSpecials(account);
                                folder.id = db.folder().insertFolder(folder);
                                EntityLog.log(context, "OAuth folder=" + folder.name + " type=" + folder.type);
                                if (folder.synchronize)
                                    EntityOperation.sync(context, folder.id, true);
                            }
                        }

                        // Set swipe left/right folder
                        if (pop) {
                            account.swipe_left = EntityMessage.SWIPE_ACTION_DELETE;
                            account.swipe_right = EntityMessage.SWIPE_ACTION_SEEN;
                        } else
                            FragmentDialogSwipes.setDefaultFolderActions(context, account);

                        db.account().updateAccount(account);

                        // Create identities
                        if (!inbound_only)
                            for (Pair<String, String> identity : identities) {
                                EntityIdentity ident = new EntityIdentity();
                                ident.name = identity.second;
                                ident.email = identity.first;
                                ident.account = account.id;

                                ident.host = provider.smtp.host;
                                ident.encryption = iencryption;
                                ident.port = provider.smtp.port;
                                ident.auth_type = (state.length == 1 ? AUTH_TYPE_OAUTH : AUTH_TYPE_GRAPH);
                                ident.provider = provider.id;
                                ident.user = username;
                                ident.password = state[state.length - 1];
                                ident.use_ip = provider.useip;
                                ident.synchronize = true;
                                ident.primary = ident.user.equals(ident.email);
                                ident.max_size = max_size;

                                ident.id = db.identity().insertIdentity(ident);
                                EntityLog.log(context, "OAuth identity=" + ident.name + " email=" + ident.email);
                            }

                        args.putBoolean("pop", pop);
                    } else {
                        args.putLong("account", update.id);
                        EntityLog.log(context, "OAuth update account=" + update.name);
                        db.account().setAccountSynchronize(update.id, true);
                        db.account().setAccountPassword(update.id, state[0], AUTH_TYPE_OAUTH, provider.id);
                        db.identity().setIdentityPassword(update.id, username,
                                state[state.length - 1],
                                null,
                                (state.length == 1 ? AUTH_TYPE_OAUTH : AUTH_TYPE_GRAPH),
                                provider.id);
                    }

                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }

                EntityLog.log(context, "OAuth done");

                ServiceSynchronize.eval(context, "OAuth");
                args.putBoolean("updated", update != null);

                FairEmailBackupAgent.dataChanged(context);

                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                pbOAuth.setVisibility(View.GONE);

                boolean updated = args.getBoolean("updated");
                if (updated) {
                    finish();
                    ToastEx.makeText(getContext(), R.string.title_setup_oauth_updated, Toast.LENGTH_LONG).show();
                } else {
                    FragmentDialogAccount fragment = new FragmentDialogAccount();
                    fragment.setArguments(args);
                    fragment.setTargetFragment(FragmentOAuth.this, ActivitySetup.REQUEST_DONE);
                    fragment.show(getParentFragmentManager(), "oauth:review");
                }
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                showError(ex);
            }
        }.execute(this, args, "oauth:configure");
    }

    private void onHandleCancel() {
        setAuthorizationIdle();
    }

    static int[] getAuthorizationControlIds(String providerId, boolean graphMailApi) {
        if (!graphMailApi && ZohoOAuth.isProviderId(providerId))
            return new int[]{
                    R.id.etName, R.id.etEmail, R.id.etTenant,
                    R.id.cbInboundOnly, R.id.cbPop, R.id.cbRecent, R.id.cbUpdate,
                    R.id.etZohoClientId, R.id.etZohoClientSecret, R.id.etZohoRedirectUri
            };
        return new int[]{
                R.id.etName, R.id.etEmail, R.id.etTenant,
                R.id.cbInboundOnly, R.id.cbPop, R.id.cbRecent, R.id.cbUpdate
        };
    }

    private void setEnabled(boolean enabled) {
        for (int viewId : getAuthorizationControlIds(id, graphMailApi))
            view.findViewById(viewId).setEnabled(enabled);
    }

    private void setAuthorizationIdle() {
        setEnabled(true);
        btnOAuth.setEnabled(true);
        pbOAuth.setVisibility(View.GONE);
    }

    private void showError(Throwable ex) {
        Log.e(ex);

        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
            return;

        if (ex instanceof IllegalArgumentException)
            tvError.setText(new ThrowableWrapper(ex).getSafeMessage());
        else
            tvError.setText(Log.formatThrowable(ex, false));

        grpError.setVisibility(View.VISIBLE);

        if (EntityAccount.isOutlook(id) && !graphMailApi) {
            if (ex instanceof AuthenticationFailedException) {
                if (ex.getMessage() != null &&
                        ex.getMessage().contains("535 5.7.3 Authentication unsuccessful"))
                    tvOfficeAuthHint.setText(R.string.title_setup_office_auth_5_7_3);
                else if (ex.getMessage() != null &&
                        ex.getMessage().contains("SmtpClientAuthentication is disabled"))
                    // 535 5.7.139 Authentication unsuccessful, SmtpClientAuthentication is disabled for the Tenant
                    tvOfficeAuthHint.setText(R.string.title_setup_office_auth_5_7_139);
                else
                    tvOfficeAuthHint.setText(R.string.title_setup_office_auth);
                tvOfficeAuthHint.setVisibility(View.VISIBLE);
            } else if (ex instanceof MessagingException) {
                boolean notconnected = false;
                Throwable e = ex;
                while (e != null) {
                    String msg = e.getMessage();
                    if (msg != null && msg.contains("User is authenticated but not connected")) {
                        notconnected = true;
                        break;
                    }
                    e = e.getCause();
                }
                if (notconnected) {
                    tvOfficeAuthHint.setText(R.string.title_setup_office_not_connected);
                    tvOfficeAuthHint.setVisibility(View.VISIBLE);
                }
            }
        }

        EmailProvider provider;
        try {
            provider = EmailProvider.getProvider(getContext(), id);
        } catch (Throwable exex) {
            Log.e(exex);
            provider = null;
        }

        btnHelp.setVisibility(provider != null && provider.link != null ? View.VISIBLE : View.GONE);

        setAuthorizationIdle();

        getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                    return;
                scroll.smoothScrollTo(0, tvError.getBottom());
            }
        });
    }

    private void hideError() {
        btnHelp.setVisibility(View.GONE);
        grpError.setVisibility(View.GONE);
        tvOfficeAuthHint.setVisibility(View.GONE);
    }
}

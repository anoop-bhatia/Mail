package in.anoopbhatia.mail;

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
import static in.anoopbhatia.mail.GmailState.TYPE_GOOGLE;
import static in.anoopbhatia.mail.ServiceAuthenticator.AUTH_TYPE_GMAIL;

import android.Manifest;
import android.accounts.Account;
import android.accounts.AccountManager;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Paint;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
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
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.constraintlayout.widget.Group;
import androidx.lifecycle.Lifecycle;

import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class FragmentGmail extends FragmentBase {
    private String personal;
    private String address;
    private boolean pop;
    private boolean recent;
    private boolean update;

    private ViewGroup view;
    private ScrollView scroll;

    private TextView tvTitle;
    private TextView tvPrivacy;
    private Button btnGrant;
    private TextView tvGranted;
    private EditText etName;
    private CheckBox cbPop;
    private CheckBox cbRecent;
    private CheckBox cbUpdate;
    private Button btnSelect;
    private ContentLoadingProgressBar pbSelect;

    private TextView tvOnDevice;
    private TextView tvAppPassword;

    private TextView tvError;
    private Button btnSupport;

    private Group grpError;
    private ActivityResultLauncher<String[]> requestOAuthPermissions;

    private static final long GET_TOKEN_TIMEOUT = 20 * 1000L; // milliseconds
    private static final String PRIVACY_URI = "https://policies.google.com/privacy";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestOAuthPermissions = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                results -> {
                    boolean granted = !results.isEmpty();
                    for (Boolean result : results.values())
                        granted &= Boolean.TRUE.equals(result);
                    setGranted(granted);
                });

        Bundle args = getArguments();
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
        setSubtitle(R.string.title_setup_gmail);
        setAppMenuEnabled(true);

        view = (ViewGroup) inflater.inflate(R.layout.fragment_gmail, container, false);
        scroll = view.findViewById(R.id.scroll);

        // Get controls
        tvTitle = view.findViewById(R.id.tvTitle);
        tvPrivacy = view.findViewById(R.id.tvPrivacy);
        btnGrant = view.findViewById(R.id.btnGrant);
        tvGranted = view.findViewById(R.id.tvGranted);
        etName = view.findViewById(R.id.etName);
        cbPop = view.findViewById(R.id.cbPop);
        cbRecent = view.findViewById(R.id.cbRecent);
        cbUpdate = view.findViewById(R.id.cbUpdate);
        btnSelect = view.findViewById(R.id.btnSelect);
        pbSelect = view.findViewById(R.id.pbSelect);

        tvOnDevice = view.findViewById(R.id.tvOnDevice);
        tvAppPassword = view.findViewById(R.id.tvAppPassword);

        tvError = view.findViewById(R.id.tvError);
        btnSupport = view.findViewById(R.id.btnSupport);

        grpError = view.findViewById(R.id.grpError);

        // Wire controls

        tvPrivacy.setPaintFlags(tvPrivacy.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tvPrivacy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Helper.view(v.getContext(), Uri.parse(PRIVACY_URI), false);
            }
        });

        btnGrant.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    requestOAuthPermissions.launch(Helper.getOAuthPermissions());
                } catch (Throwable ex) {
                    Log.unexpectedError(getParentFragmentManager(), ex);
                }
            }
        });

        cbPop.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean checked) {
                cbRecent.setVisibility(checked ? View.VISIBLE : View.GONE);
            }
        });

        btnSelect.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    grpError.setVisibility(View.GONE);

                    String name = etName.getText().toString().trim();
                    if (TextUtils.isEmpty(name))
                        throw new IllegalArgumentException(getString(R.string.title_no_name));

                    etName.clearFocus();
                    Helper.hideKeyboard(view);

                    Intent intent = AccountManager.newChooseAccountIntent(
                            null,
                            (List<Account>) null,
                            new String[]{TYPE_GOOGLE},
                            null,
                            null,
                            null,
                            null);
                    PackageManager pm = getContext().getPackageManager();
                    if (intent.resolveActivity(pm) == null) // system whitelisted
                        Log.e("newChooseAccountIntent unavailable");
                    launchActivityForResult(intent, ActivitySetup.REQUEST_CHOOSE_ACCOUNT);
                } catch (Throwable ex) {
                    Log.e(ex);
                    if (ex instanceof IllegalArgumentException)
                        tvError.setText(new ThrowableWrapper(ex).getSafeMessage());
                    else
                        tvError.setText(Log.formatThrowable(ex, false));
                    grpError.setVisibility(View.VISIBLE);
                }
            }
        });

        tvOnDevice.setPaintFlags(tvOnDevice.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tvOnDevice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Helper.viewFAQ(v.getContext(), 111);
            }
        });

        tvAppPassword.setPaintFlags(tvAppPassword.getPaintFlags() | Paint.UNDERLINE_TEXT_FLAG);
        tvAppPassword.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Helper.viewFAQ(v.getContext(), 6);
            }
        });

        btnSupport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Helper.view(v.getContext(), Helper.getSupportUri(v.getContext(), "Gmail:support"), false);
            }
        });

        // Initialize
        Helper.setViewsEnabled(view, false);
        tvTitle.setText(getString(R.string.title_setup_oauth_rationale, "Gmail"));
        etName.setText(personal);
        cbPop.setChecked(false);
        cbRecent.setChecked(false);
        cbRecent.setVisibility(pop ? View.VISIBLE : View.GONE);
        cbUpdate.setChecked(update);
        pbSelect.setVisibility(View.GONE);
        grpError.setVisibility(View.GONE);

        boolean granted = Helper.hasPermissions(getContext(), Helper.getOAuthPermissions());
        setGranted(granted);

        return view;
    }

    @Override
    public void dispatchActivityResult(int requestCode, int resultCode, Intent data) {
        super.dispatchActivityResult(requestCode, resultCode, data);

        try {
            switch (requestCode) {
                case ActivitySetup.REQUEST_CHOOSE_ACCOUNT:
                    if (resultCode == RESULT_OK && data != null)
                        onAccountSelected(data);
                    else
                        onNoAccountSelected(resultCode, data);
                    break;
                case ActivitySetup.REQUEST_DONE:
                    finish();
                    break;
            }
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    private void setGranted(boolean granted) {
        btnGrant.setEnabled(!granted);
        tvGranted.setVisibility(granted ? View.VISIBLE : View.GONE);

        etName.setEnabled(granted);
        cbPop.setEnabled(granted);
        cbRecent.setEnabled(granted);
        cbUpdate.setEnabled(granted);
        btnSelect.setEnabled(granted);

        getMainHandler().post(new Runnable() {
            @Override
            public void run() {
                if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                    return;
                etName.requestFocus();
            }
        });
    }

    private void onNoAccountSelected(int resultCode, Intent data) {
        AccountManager am = AccountManager.get(getContext().getApplicationContext());
        Account[] accounts = am.getAccountsByType(TYPE_GOOGLE);
        if (accounts.length == 0)
            Log.e("newChooseAccountIntent without result=" + resultCode + " data=" + data);

        if (resultCode == RESULT_OK) {
            tvError.setText(getString(R.string.title_no_account) + " (" + accounts.length + ")");
            grpError.setVisibility(View.VISIBLE);
        } else
            ToastEx.makeText(getContext(), android.R.string.cancel, Toast.LENGTH_SHORT).show();
    }

    private void onAccountSelected(Intent data) {
        String name = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
        String type = data.getStringExtra(AccountManager.KEY_ACCOUNT_TYPE);

        final Handler handler = getMainHandler();
        final String disabled = getString(R.string.title_setup_advanced_protection);

        boolean found = false;
        AccountManager am = AccountManager.get(getContext().getApplicationContext());
        Account[] accounts = am.getAccountsByType(type);
        for (final Account account : accounts)
            if (name.equalsIgnoreCase(account.name)) {
                found = true;
                Log.i("Requesting token name=" + account.name);
                am.getAuthToken(
                        account,
                        ServiceAuthenticator.getAuthTokenType(type),
                        new Bundle(),
                        getActivity(),
                        new AccountManagerCallback<Bundle>() {
                            @Override
                            public void run(AccountManagerFuture<Bundle> future) {
                                try {
                                    Bundle bundle = future.getResult(GET_TOKEN_TIMEOUT, TimeUnit.MILLISECONDS);
                                    if (future.isCancelled())
                                        throw new IllegalArgumentException("Android failed to return a token");

                                    String token = bundle.getString(AccountManager.KEY_AUTHTOKEN);
                                    if (token == null)
                                        throw new IllegalArgumentException("Android returned no token");
                                    Log.i("Got token name=" + account.name);

                                    if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                                        return;

                                    onAuthorized(name, token);
                                } catch (Throwable ex) {
                                    // android.accounts.OperationCanceledException = ServiceDisabled?
                                    if (ex instanceof AuthenticatorException &&
                                            ("ERROR".equals(ex.getMessage())||
                                                    "ServiceDisabled".equals(ex.getMessage())))
                                        ex = new IllegalArgumentException(disabled, ex);

                                    Log.e(ex);

                                    if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                                        return;

                                    tvError.setText(Log.formatThrowable(ex, false));
                                    grpError.setVisibility(View.VISIBLE);

                                    getMainHandler().post(new Runnable() {
                                        @Override
                                        public void run() {
                                            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                                                return;
                                            scroll.smoothScrollTo(0, tvError.getBottom());
                                        }
                                    });
                                }
                            }
                        },
                        handler);
                break;
            }

        if (!found) {
            boolean permission = Helper.hasPermission(getContext(), Manifest.permission.GET_ACCOUNTS);

            Map<String, String> crumb = new HashMap<>();
            crumb.put("type", type);
            crumb.put("count", Integer.toString(accounts.length));
            crumb.put("permission", Boolean.toString(permission));
            Log.breadcrumb("Gmail", crumb);

            Log.e("Account missing");

            tvError.setText(getString(R.string.title_no_account));
            grpError.setVisibility(View.VISIBLE);
        }
    }

    private void onAuthorized(String user, String token) {
        GmailState state = GmailState.jsonDeserialize(token);

        Bundle args = new Bundle();
        args.putString("name", etName.getText().toString().trim());
        args.putBoolean("pop", cbPop.isChecked());
        args.putBoolean("recent", cbRecent.isChecked());
        args.putBoolean("update", cbUpdate.isChecked());
        args.putString("user", user);
        args.putString("password", state.jsonSerializeString());

        new SimpleTask<Void>() {
            @Override
            protected void onPreExecute(Bundle args) {
                etName.setEnabled(false);
                cbPop.setEnabled(false);
                cbRecent.setEnabled(false);
                cbUpdate.setEnabled(false);
                btnSelect.setEnabled(false);
                pbSelect.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                etName.setEnabled(true);
                cbPop.setEnabled(true);
                cbRecent.setEnabled(true);
                cbUpdate.setEnabled(true);
                btnSelect.setEnabled(true);
                pbSelect.setVisibility(View.GONE);
            }

            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                String name = args.getString("name");
                boolean pop = args.getBoolean("pop");
                boolean recent = args.getBoolean("recent");
                String user = args.getString("user");
                String password = args.getString("password");

                // Safety checks
                if (!Helper.EMAIL_ADDRESS.matcher(user).matches())
                    throw new IllegalArgumentException(context.getString(R.string.title_email_invalid, user));
                if (TextUtils.isEmpty(password))
                    throw new IllegalArgumentException(context.getString(R.string.title_no_password));

                if (!ConnectionHelper.isConnected(context))
                    throw new IllegalArgumentException(context.getString(R.string.title_no_internet));

                int at = user.indexOf('@');
                String username = user.substring(0, at);

                EmailProvider provider = EmailProvider
                        .fromDomain(context, "gmail.com", EmailProvider.Discover.ALL)
                        .get(0);
                if (provider.pop == null)
                    pop = false;

                if (pop && recent)
                    user = "recent:" + user;

                List<EntityFolder> folders;

                EmailProvider.Server inbound = (pop ? provider.pop : provider.imap);
                String aprotocol = (pop ? (inbound.starttls ? "pop3" : "pop3s") : (inbound.starttls ? "imap" : "imaps"));
                int aencryption = (inbound.starttls ? EmailService.ENCRYPTION_STARTTLS : EmailService.ENCRYPTION_SSL);
                try (EmailService aservice = new EmailService(context,
                        aprotocol, null, aencryption, false, false, false,
                        EmailService.PURPOSE_CHECK, true)) {
                    aservice.connect(
                            false, inbound.host, inbound.port,
                            AUTH_TYPE_GMAIL, null,
                            user, password,
                            null, null);

                    if (pop)
                        folders = EntityFolder.getPopFolders(context);
                    else
                        folders = aservice.getFolders(inbound.host);
                }

                Long max_size;
                String iprotocol = (provider.smtp.starttls ? "smtp" : "smtps");
                int iencryption = (provider.smtp.starttls ? EmailService.ENCRYPTION_STARTTLS : EmailService.ENCRYPTION_SSL);
                try (EmailService iservice = new EmailService(context,
                        iprotocol, null, iencryption, false, false, false,
                        EmailService.PURPOSE_CHECK, true)) {
                    iservice.connect(
                            false, provider.smtp.host, provider.smtp.port,
                            AUTH_TYPE_GMAIL, null,
                            user, password,
                            null, null);
                    max_size = iservice.getMaxSize();
                }

                int protocol = (pop ? EntityAccount.TYPE_POP : EntityAccount.TYPE_IMAP);
                DB db = DB.getInstance(context);
                final String accountIdentityName = name;
                final String accountUser = user;
                final boolean accountPop = pop;
                EntityAccount update = db.callInTransactionWithCleanup(() -> {
                    String identityName = accountIdentityName;
                    EntityAccount matchedAccount = null;

                    if (args.getBoolean("update")) {
                        List<EntityAccount> accounts = db.account().getAccounts(accountUser, protocol);
                        if (accounts != null && accounts.size() == 1)
                            matchedAccount = accounts.get(0);
                    }

                    if (matchedAccount == null) {
                        EntityAccount primary = db.account().getPrimaryAccount();

                        // Create account
                        EntityAccount account = new EntityAccount();

                        account.protocol = protocol;
                        account.host = inbound.host;
                        account.encryption = aencryption;
                        account.port = inbound.port;
                        account.auth_type = AUTH_TYPE_GMAIL;
                        account.user = accountUser;
                        account.password = password;

                        account.name = provider.name + "/" + username;

                        account.synchronize = true;
                        account.primary = (primary == null);

                        // https://support.google.com/mail/answer/7104828
                        if (accountPop)
                            account.leave_on_device = true;

                        account.created = new Date().getTime();
                        account.last_connected = account.created;

                        account.id = db.account().insertAccount(account);
                        args.putLong("account", account.id);
                        EntityLog.log(context, "Gmail account=" + account.name);

                        // Create folders
                        for (EntityFolder folder : folders) {
                            EntityFolder existing = db.folder().getFolderByName(account.id, folder.name);
                            if (existing == null) {
                                folder.account = account.id;
                                folder.setSpecials(account);
                                folder.id = db.folder().insertFolder(folder);
                                EntityLog.log(context, "Gmail folder=" + folder.name + " type=" + folder.type);
                                if (folder.synchronize)
                                    EntityOperation.sync(context, folder.id, true);
                            }
                        }

                        // Set swipe left/right folder
                        if (accountPop) {
                            account.swipe_left = EntityMessage.SWIPE_ACTION_DELETE;
                            account.swipe_right = EntityMessage.SWIPE_ACTION_SEEN;
                        } else
                            FragmentDialogSwipes.setDefaultFolderActions(context, account);

                        db.account().updateAccount(account);

                        if (TextUtils.isEmpty(identityName))
                            identityName = accountUser.split("@")[0];

                        // Create identity
                        EntityIdentity identity = new EntityIdentity();
                        identity.name = identityName;
                        identity.email = accountUser;
                        identity.account = account.id;

                        identity.host = provider.smtp.host;
                        identity.encryption = iencryption;
                        identity.port = provider.smtp.port;
                        identity.auth_type = AUTH_TYPE_GMAIL;
                        identity.user = accountUser;
                        identity.password = password;
                        identity.synchronize = true;
                        identity.primary = true;
                        identity.max_size = max_size;

                        identity.id = db.identity().insertIdentity(identity);
                        EntityLog.log(context, "Gmail identity=" + identity.name + " email=" + identity.email);
                    } else {
                        args.putLong("account", matchedAccount.id);
                        EntityLog.log(context, "Gmail update account=" + matchedAccount.name);
                        db.account().setAccountSynchronize(matchedAccount.id, true);
                        db.account().setAccountPassword(matchedAccount.id, password, AUTH_TYPE_GMAIL, null);
                        db.identity().setIdentityPassword(matchedAccount.id, matchedAccount.user, password, matchedAccount.auth_type, AUTH_TYPE_GMAIL, null);
                    }
                    return matchedAccount;
                });

                ServiceSynchronize.eval(context, "Gmail");
                args.putBoolean("updated", update != null);

                MailBackupAgent.dataChanged(context);

                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                boolean updated = args.getBoolean("updated");
                if (updated) {
                    ToastEx.makeText(getContext(), R.string.title_setup_oauth_updated, Toast.LENGTH_LONG).show();
                    finish();
                } else {
                    FragmentDialogAccount fragment = new FragmentDialogAccount();
                    fragment.setArguments(args);
                    fragment.setResultTarget(FragmentGmail.this, ActivitySetup.REQUEST_DONE);
                    fragment.show(getParentFragmentManager(), "quick:review");
                }
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                Log.e(ex);

                if (ex instanceof IllegalArgumentException)
                    tvError.setText(new ThrowableWrapper(ex).getSafeMessage());
                else
                    tvError.setText(Log.formatThrowable(ex, false));
                grpError.setVisibility(View.VISIBLE);

                getMainHandler().post(new Runnable() {
                    @Override
                    public void run() {
                        if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED))
                            return;
                        scroll.smoothScrollTo(0, tvError.getBottom());
                    }
                });
            }
        }.execute(this, args, "setup:gmail");
    }
}

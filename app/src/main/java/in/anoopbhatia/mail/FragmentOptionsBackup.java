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
import static in.anoopbhatia.mail.ServiceAuthenticator.AUTH_TYPE_PASSWORD;

import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.OperationCanceledException;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Pair;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.documentfile.provider.DocumentFile;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.PreferenceManager;


import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;

public class FragmentOptionsBackup extends FragmentBase {
    private View view;
    private ImageButton ibHelp;
    private Button btnExport;
    private TextView tvExportPro;
    private Button btnImport;
    private TextView tvBackupMessages;

    private static final int REQUEST_EXPORT_SELECT = 1;
    private static final int REQUEST_IMPORT_SELECT = 2;
    private static final int REQUEST_EXPORT_HANDLE = 3;
    private static final int REQUEST_IMPORT_HANDLE = 4;

    final static List<String> DEFER_OPTIONS = Collections.unmodifiableList(Arrays.asList(
            "theme",
            "beige"
    ));

    final static List<String> RESTART_OPTIONS = Collections.unmodifiableList(Arrays.asList(
            "secure", // privacy
            "load_emoji", // privacy
            "shortcuts", // misc
            "language", // misc
            "wal", // misc
            "sqlite_integrity_check" // misc
    ));

    static final String[][] RESET_ALL_OPTIONS = {
            FragmentOptionsSynchronize.RESET_OPTIONS.toArray(new String[0]),
            FragmentOptionsSend.RESET_OPTIONS.toArray(new String[0]),
            FragmentOptionsConnection.RESET_OPTIONS.toArray(new String[0]),
            FragmentOptionsDisplay.RESET_OPTIONS.toArray(new String[0]),
            FragmentOptionsBehavior.RESET_OPTIONS.toArray(new String[0]),
            FragmentOptionsPrivacy.RESET_OPTIONS.toArray(new String[0]),
            FragmentOptionsEncryption.RESET_OPTIONS.toArray(new String[0]),
            FragmentOptionsNotifications.RESET_OPTIONS.toArray(new String[0]),
            //FragmentOptionsIntegrations.RESET_OPTIONS.toArray(new String[0]),
            FragmentOptionsMisc.RESET_OPTIONS.toArray(new String[0])
    };

    @Override
    @Nullable
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        setSubtitle(R.string.title_setup);

        view = inflater.inflate(R.layout.fragment_options_backup, container, false);

        // Get controls

        ibHelp = view.findViewById(R.id.ibHelp);
        btnExport = view.findViewById(R.id.btnExport);
        tvExportPro = view.findViewById(R.id.tvExportPro);
        btnImport = view.findViewById(R.id.btnImport);
        tvBackupMessages = view.findViewById(R.id.tvBackupMessages);

        // Wire controls

        ibHelp.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Helper.view(v.getContext(), Helper.getSupportUri(v.getContext(), "Options:backup"), false);
            }
        });

        btnExport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onExportDialog();
            }
        });

        btnImport.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onImportDialog();
            }
        });

        tvBackupMessages.getPaint().setUnderlineText(true);
        tvBackupMessages.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Helper.viewFAQ(v.getContext(), 151);
            }
        });

        // Initialize
        Helper.linkPro(tvExportPro);

        return view;
    }

    @Override
    public void dispatchActivityResult(int requestCode, int resultCode, Intent data) {
        super.dispatchActivityResult(requestCode, resultCode, data);

        try {
            switch (requestCode) {
                case REQUEST_EXPORT_SELECT:
                    if (resultCode == RESULT_OK)
                        onExportSelect();
                    break;
                case REQUEST_IMPORT_SELECT:
                    if (resultCode == RESULT_OK)
                        onImportSelect();
                    break;
                case REQUEST_EXPORT_HANDLE:
                    if (resultCode == RESULT_OK && data != null)
                        handleExport(data);
                    break;
                case REQUEST_IMPORT_HANDLE:
                    if (resultCode == RESULT_OK && data != null)
                        handleImport(data);
                    break;
            }
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    private void onExportDialog() {
        askPassword(true);

    }

    private void onImportDialog() {
        askPassword(false);
    }

    private void onExportSelect() {
        launchActivityForResult(
                Helper.getChooser(getContext(), getIntentExport(getContext())), REQUEST_EXPORT_HANDLE);
    }

    private void onImportSelect() {
        launchActivityForResult(
                Helper.getChooser(getContext(), getIntentImport(getContext())), REQUEST_IMPORT_HANDLE);
    }

    private void handleExport(Intent data) {
        ViewModelExport vme = new ViewModelProvider(getActivity()).get(ViewModelExport.class);

        Bundle args = new Bundle();
        args.putParcelable("uri", data.getData());
        args.putString("password", vme.getPassword());

        new SimpleTask<Void>() {
            private Toast toast = null;

            @Override
            protected void onPreExecute(Bundle args) {
                toast = ToastEx.makeText(getContext(), R.string.title_executing, Toast.LENGTH_LONG);
                toast.show();
            }

            @Override
            protected void onPostExecute(Bundle args) {
                if (toast != null)
                    toast.cancel();
            }

            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                Uri uri = Helper.getParcelable(args, "uri", Uri.class);

                if (uri == null)
                    throw new FileNotFoundException();

                String password = args.getString("password");
                EntityLog.log(context, "Exporting " + uri);

                if (!"content".equals(uri.getScheme())) {
                    Log.w("Export uri=" + uri);
                    throw new IllegalArgumentException(context.getString(R.string.title_no_stream));
                }

                Log.i("Collecting data");
                DB db = DB.getInstance(context);
                NotificationManager nm = Helper.getSystemService(context, NotificationManager.class);

                // Accounts
                JSONArray jaccounts = new JSONArray();
                for (EntityAccount account : db.account().getAccounts()) {
                    // Account
                    JSONObject jaccount = account.toJSON();

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        if (account.notify) {
                            NotificationChannel channel = nm.getNotificationChannel(
                                    EntityAccount.getNotificationChannelId(account.id));
                            if (channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE) {
                                JSONObject jchannel = NotificationHelper.channelToJSON(channel);
                                jaccount.put("channel", jchannel);
                                Log.i("Exported account channel=" + jchannel);
                            }
                        }
                    }

                    // Identities
                    JSONArray jidentities = new JSONArray();
                    for (EntityIdentity identity : db.identity().getIdentities(account.id))
                        jidentities.put(identity.toJSON());
                    jaccount.put("identities", jidentities);

                    // Folders
                    JSONArray jfolders = new JSONArray();
                    for (EntityFolder folder : db.folder().getFolders(account.id, false, true)) {
                        JSONObject jfolder = folder.toJSON();

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            NotificationChannel channel = nm.getNotificationChannel(
                                    EntityFolder.getNotificationChannelId(folder.id));
                            if (channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE) {
                                JSONObject jchannel = NotificationHelper.channelToJSON(channel);
                                jfolder.put("channel", jchannel);
                                Log.i("Exported folder channel=" + jchannel);
                            }
                        }

                        JSONArray jrules = new JSONArray();
                        for (EntityRule rule : db.rule().getRules(folder.id)) {
                            try {
                                JSONObject jaction = new JSONObject(rule.action);
                                int type = jaction.getInt("type");
                                switch (type) {
                                    case EntityRule.TYPE_MOVE:
                                    case EntityRule.TYPE_COPY:
                                        long target = jaction.optLong("target", -1L);
                                        EntityFolder f = db.folder().getFolder(target);
                                        EntityAccount a = (f == null ? null : db.account().getAccount(f.account));
                                        if (a != null)
                                            jaction.put("targetAccountUuid", a.uuid);
                                        if (f != null)
                                            jaction.put("targetFolderName", f.name);
                                        break;
                                    case EntityRule.TYPE_ANSWER:
                                        long identity = jaction.optLong("identity", -1L);
                                        long answer = jaction.optLong("answer", -1L);
                                        EntityIdentity i = db.identity().getIdentity(identity);
                                        EntityAnswer t = db.answer().getAnswer(answer);
                                        if (i != null)
                                            jaction.put("identityUuid", i.uuid);
                                        if (t != null)
                                            jaction.put("answerUuid", t.uuid);
                                        break;
                                }
                                rule.action = jaction.toString();
                            } catch (Throwable ex) {
                                Log.e(ex);
                            }

                            jrules.put(rule.toJSON());
                        }
                        jfolder.put("rules", jrules);

                        jfolders.put(jfolder);
                    }
                    jaccount.put("folders", jfolders);

                    // Contacts
                    JSONArray jcontacts = new JSONArray();
                    for (EntityContact contact : db.contact().getContacts(account.id))
                        jcontacts.put(contact.toJSON());
                    jaccount.put("contacts", jcontacts);

                    jaccounts.put(jaccount);
                }

                // Answers
                JSONArray janswers = new JSONArray();
                for (EntityAnswer answer : db.answer().getAnswers(true))
                    janswers.put(answer.toJSON());

                // Searches
                JSONArray jsearches = new JSONArray();
                for (EntitySearch search : db.search().getSearches()) {
                    if (Objects.equals(search.name, context.getString(R.string.title_search_with_flagged)))
                        continue;
                    if (Objects.equals(search.name, context.getString(R.string.title_search_with_unseen)))
                        continue;
                    jsearches.put(search.toJSON());
                }

                // Certificates
                JSONArray jcertificates = new JSONArray();
                for (EntityCertificate certificate : db.certificate().getCertificates())
                    jcertificates.put(certificate.toJSON());

                // Settings
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                JSONArray jsettings = new JSONArray();
                for (String key : prefs.getAll().keySet()) {
                    JSONObject jsetting = new JSONObject();
                    Object value = prefs.getAll().get(key);
                    jsetting.put("key", key);
                    jsetting.put("value", value);
                    if (value instanceof Boolean)
                        jsetting.put("type", "bool");
                    else if (value instanceof Integer)
                        jsetting.put("type", "int");
                    else if (value instanceof Long)
                        jsetting.put("type", "long");
                    else if (value instanceof Float)
                        jsetting.put("type", "float");
                    else if (value instanceof String)
                        jsetting.put("type", "string");
                    else if (value != null) {
                        String type = value.getClass().getName();
                        Log.w("Unknown type=" + type);
                        jsetting.put("type", type);
                    }
                    jsettings.put(jsetting);
                }

                JSONObject jsearch = new JSONObject();
                jsearch.put("key", "external_search");
                jsearch.put("value", Helper.isComponentEnabled(context, ActivitySearch.class));
                jsearch.put("type", "bool");
                jsettings.put(jsearch);

                JSONObject jexport = new JSONObject();
                jexport.put("accounts", jaccounts);
                jexport.put("answers", janswers);
                jexport.put("searches", jsearches);
                jexport.put("certificates", jcertificates);
                jexport.put("settings", jsettings);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    JSONArray jchannels = new JSONArray();
                    for (NotificationChannel channel : nm.getNotificationChannels()) {
                        String id = channel.getId();
                        if (id.startsWith("notification.") && id.contains("@") &&
                                channel.getImportance() != NotificationManager.IMPORTANCE_NONE) {
                            JSONObject jchannel = NotificationHelper.channelToJSON(channel);
                            jchannels.put(jchannel);
                            Log.i("Exported contact channel=" + jchannel);
                        }
                    }
                    jexport.put("channels", jchannels);
                }

                ContentResolver resolver = context.getContentResolver();
                DocumentFile file = DocumentFile.fromSingleUri(context, uri);
                try (OutputStream raw = resolver.openOutputStream(uri)) {
                    Log.i("Writing URI=" + uri + " name=" + file.getName() + " virtual=" + file.isVirtual());
                    if (raw == null)
                        throw new FileNotFoundException(uri.toString());

                    if (TextUtils.isEmpty(password))
                        raw.write(jexport.toString(2).getBytes());
                    else {
                        // https://developer.android.com/reference/javax/crypto/Cipher
                        // https://developer.android.com/reference/kotlin/javax/crypto/SecretKeyFactory
                        int version = 0;
                        int ivLen = (version == 0 ? 16 : 12);
                        String derivation = (version == 0 ? "PBKDF2WithHmacSHA1" : "PBKDF2WithHmacSHA512");
                        int iterations = (version == 0 ? 65536 : 120000);
                        int keyLen = 256;
                        String transformation = (version == 0 ? "AES/CBC/PKCS5Padding" : "AES/GCM/NoPadding");
                        Log.i("Export version=" + version +
                                " ivLen=" + ivLen +
                                " derivation=" + derivation +
                                " iterations=" + iterations +
                                " keyLen=" + keyLen +
                                " transformation=" + transformation);

                        byte[] salt = new byte[16];
                        SecureRandom random = new SecureRandom();
                        random.nextBytes(salt);

                        // https://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#Cipher
                        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(derivation);
                        KeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, iterations, keyLen);
                        SecretKey secret = keyFactory.generateSecret(keySpec);
                        Cipher cipher = Cipher.getInstance(transformation);
                        cipher.init(Cipher.ENCRYPT_MODE, secret);

                        if (version > 0) {
                            raw.write("___FairEmail___".getBytes(StandardCharsets.US_ASCII));
                            raw.write(version); // version
                        }
                        raw.write(salt);
                        raw.write(cipher.getIV());

                        OutputStream cout = new CipherOutputStream(raw, cipher);
                        cout.write(jexport.toString(2).getBytes());
                        cout.flush();
                        raw.write(cipher.doFinal());
                    }
                }

                Log.i("Exported data");

                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                ToastEx.makeText(getContext(), R.string.title_setup_exported, Toast.LENGTH_LONG).show();
            }

            @Override
            protected void onDestroyed(Bundle args) {
                if (toast != null) {
                    toast.cancel();
                    toast = null;
                }
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                boolean expected =
                        (ex instanceof IllegalArgumentException ||
                                ex instanceof FileNotFoundException ||
                                ex instanceof SecurityException);
                if (ex instanceof SecurityException)
                    ex = new Throwable("No write permission has been granted by the file selector", ex);
                Log.unexpectedError(getParentFragmentManager(), ex, !expected);
            }
        }.execute(this, args, "setup:export");
    }

    private void handleImport(Intent data) {
        final Context context = getContext();

        Uri uri = data.getData();

        if (uri != null)
            try {
                DocumentFile df = DocumentFile.fromSingleUri(context, uri);
                if (df != null) {
                    String name = df.getName();
                    String ext = Helper.getExtension(name);
                    if ("k9s".equals(ext)) {
                        handleK9Import(uri);
                        return;
                    } else if ("mobileconfig".equals(ext)) {
                        handleAppleImport(uri);
                        return;
                    }
                }
            } catch (Throwable ex) {
                Log.w(ex);
            }

        final int colorWarning = Helper.resolveColor(context, R.attr.colorWarning);

        View dview = LayoutInflater.from(context).inflate(R.layout.dialog_import_progress, null);
        ProgressBar pbWait = dview.findViewById(R.id.pbWait);
        TextView tvLog = dview.findViewById(R.id.tvLog);
        tvLog.setText(null);

        Map<String, Object> defer = Collections.synchronizedMap(new HashMap<>());

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dview)
                .setPositiveButton(android.R.string.ok, null)
                .setCancelable(false)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialogInterface) {
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(dview.getContext());
                        SharedPreferences.Editor editor = prefs.edit();

                        for (String key : defer.keySet()) {
                            Object value = defer.get(key);
                            if (value instanceof Boolean)
                                editor.putBoolean(key, (Boolean) value);
                            else if (value instanceof String)
                                editor.putString(key, (String) value);
                        }

                        editor.commit();
                    }
                })
                .show();

        Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        ok.setEnabled(false);

        ViewModelExport vme = new ViewModelProvider(getActivity()).get(ViewModelExport.class);

        Bundle args = new Bundle();
        args.putParcelable("uri", uri);
        args.putString("password", vme.getPassword());
        args.putBoolean("import_accounts", vme.getOption("accounts"));
        args.putBoolean("import_delete", vme.getOption("delete"));
        args.putBoolean("import_rules", vme.getOption("rules"));
        args.putBoolean("import_contacts", vme.getOption("contacts"));
        args.putBoolean("import_answers", vme.getOption("answers"));
        args.putBoolean("import_searches", vme.getOption("searches"));
        args.putBoolean("import_settings", vme.getOption("settings"));

        new SimpleTask<Void>() {
            private SpannableStringBuilder ssb = new SpannableStringBuilderEx();

            @Override
            protected void onPreExecute(Bundle args) {
                pbWait.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onProgress(CharSequence status, Bundle data) {
                ssb.append(status).append("\n");
                tvLog.setText(ssb);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                pbWait.setVisibility(View.GONE);
                ok.setEnabled(true);
            }

            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                Uri uri = Helper.getParcelable(args, "uri", Uri.class);
                String password = args.getString("password");
                boolean import_accounts = args.getBoolean("import_accounts");
                boolean import_delete = args.getBoolean("import_delete");
                boolean import_rules = args.getBoolean("import_rules");
                boolean import_contacts = args.getBoolean("import_contacts");
                boolean import_answers = args.getBoolean("import_answers");
                boolean import_searches = args.getBoolean("import_searches");
                boolean import_settings = args.getBoolean("import_settings");
                EntityLog.log(context, "Importing " + uri +
                        " accounts=" + import_accounts +
                        " delete=" + import_delete +
                        " rules=" + import_rules +
                        " contacts=" + import_contacts +
                        " answers=" + import_answers +
                        " searches=" + import_searches +
                        " settings=" + import_settings);

                NoStreamException.check(uri, context);

                ServiceSynchronize.stop(context);
                ServiceSend.stop(context);

                StringBuilder data = new StringBuilder();
                Log.i("Reading URI=" + uri);
                ContentResolver resolver = context.getContentResolver();
                InputStream is = resolver.openInputStream(uri);
                if (is == null)
                    throw new FileNotFoundException(uri.toString());
                try (InputStream raw = new BufferedInputStream(is)) {
                    InputStream in;
                    if (TextUtils.isEmpty(password))
                        in = raw;
                    else {
                        byte[] salt = new byte[16];
                        Helper.readBuffer(raw, salt);

                        int version = 0;
                        String magic = new String(salt, 0, 15, StandardCharsets.US_ASCII);
                        if ("___FairEmail___".equals(magic)) {
                            version = salt[15];
                            Helper.readBuffer(raw, salt);
                        }

                        int ivLen = (version == 0 ? 16 : 12);
                        String derivation = (version == 0 ? "PBKDF2WithHmacSHA1" : "PBKDF2WithHmacSHA512");
                        int iterations = (version == 0 ? 65536 : 120000);
                        int keyLen = 256;
                        String transformation = (version == 0 ? "AES/CBC/PKCS5Padding" : "AES/GCM/NoPadding");
                        Log.i("Import version=" + version +
                                " ivLen=" + ivLen +
                                " derivation=" + derivation +
                                " iterations=" + iterations +
                                " keyLen=" + keyLen +
                                " transformation=" + transformation);

                        byte[] iv = new byte[ivLen];
                        Helper.readBuffer(raw, iv);

                        SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(derivation);
                        KeySpec keySpec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
                        SecretKey secret = keyFactory.generateSecret(keySpec);
                        Cipher cipher = Cipher.getInstance(transformation);
                        IvParameterSpec ivSpec = new IvParameterSpec(iv);
                        cipher.init(Cipher.DECRYPT_MODE, secret, ivSpec);

                        in = new CipherInputStream(raw, cipher);
                    }

                    BufferedReader reader = new BufferedReader(new InputStreamReader(in));
                    String line;
                    while ((line = reader.readLine()) != null)
                        data.append(line);
                }

                String json = data.toString();
                if (!json.startsWith("{") || !json.endsWith("}")) {
                    Log.i("Invalid JSON");
                    throw new IllegalArgumentException(context.getString(R.string.title_setup_password_invalid));
                }

                Log.i("Importing data");
                JSONObject jimport = new JSONObject(json);

                DB db = DB.getInstance(context);
                NotificationManager nm = Helper.getSystemService(context, NotificationManager.class);
                db.callInTransactionWithCleanup(() -> {

                    Map<Long, Long> xAnswer = new HashMap<>();
                    Map<Long, Long> xIdentity = new HashMap<>();
                    Map<Long, Long> xFolder = new HashMap<>();
                    List<EntityRule> rules = new ArrayList<>();

                    if (import_answers) {
                        postProgress(context.getString(R.string.title_setup_import_answers), null);

                        // Answers
                        JSONArray janswers = jimport.getJSONArray("answers");
                        for (int a = 0; a < janswers.length(); a++) {
                            JSONObject janswer = (JSONObject) janswers.get(a);
                            EntityAnswer answer = EntityAnswer.fromJSON(janswer);
                            long id = answer.id;
                            answer.id = null;

                            EntityAnswer existing = db.answer().getAnswerByUUID(answer.uuid);
                            if (existing != null)
                                db.answer().deleteAnswer(existing.id);

                            answer.id = db.answer().insertAnswer(answer);
                            xAnswer.put(id, answer.id);

                            Log.i("Imported answer=" + answer.name + " id=" + answer.id + " (" + id + ")");
                        }
                    }

                    if (import_searches && jimport.has("searches")) {
                        postProgress(context.getString(R.string.title_setup_import_searches), null);

                        JSONArray jsearches = jimport.getJSONArray("searches");
                        for (int s = 0; s < jsearches.length(); s++) {
                            JSONObject jsearch = (JSONObject) jsearches.get(s);
                            EntitySearch search = EntitySearch.fromJSON(jsearch);

                            boolean found = false;
                            for (EntitySearch other : db.search().getSearches())
                                if (other.equals(search)) {
                                    found = true;
                                    break;
                                }

                            if (!found) {
                                search.id = null;
                                db.search().insertSearch(search);
                            }
                        }
                    }

                    if (import_accounts) {
                        // Accounts
                        JSONArray jaccounts = jimport.getJSONArray("accounts");
                        for (int a = 0; a < jaccounts.length(); a++) {
                            JSONObject jaccount = (JSONObject) jaccounts.get(a);
                            EntityAccount account = EntityAccount.fromJSON(jaccount);
                            postProgress(context.getString(R.string.title_importing_account, account.name));

                            if (import_delete) {
                                EntityAccount delete = db.account().getAccount(account.auth_type, account.user);
                                if (delete != null)
                                    db.account().deleteAccount(delete.id);
                            }

                            EntityAccount existing = db.account().getAccountByUUID(account.uuid);
                            if (existing != null) {
                                SpannableStringBuilder ssb = new SpannableStringBuilderEx();
                                ssb.append(context.getString(R.string.title_importing_exists));
                                ssb.setSpan(new StyleSpan(Typeface.BOLD), 0, ssb.length(), 0);
                                postProgress(ssb);
                                EntityLog.log(context, "Existing account=" + account.name +
                                        "id=" + account.id);
                                continue;
                            }

                            if (account.isGmailApi()) {
                                account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
                                if (!GmailState.hasAuthorization(account.password)) {
                                    SpannableStringBuilder ssb = new SpannableStringBuilderEx();
                                    ssb.append(account.name).append(": ");
                                    ssb.append(context.getString(R.string.title_importing_wizard));
                                    ssb.setSpan(new StyleSpan(Typeface.BOLD), 0, ssb.length(), 0);
                                    ssb.setSpan(new ForegroundColorSpan(colorWarning), 0, ssb.length(), 0);
                                    postProgress(ssb);
                                    EntityLog.log(context, "Run wizard account=" + account.name +
                                            "id=" + account.id);
                                    account.synchronize = false;
                                }
                            }

                            Long aid = account.id;
                            account.id = null;

                            if (jaccounts.length() == 1)
                                account.primary = true;

                            EntityAccount primary = db.account().getPrimaryAccount();
                            if (primary != null)
                                account.primary = false;

                            // Forward referenced
                            Long swipe_left = account.swipe_left;
                            Long swipe_right = account.swipe_right;
                            Long move_to = account.move_to;
                            if (account.swipe_left != null && account.swipe_left > 0)
                                account.swipe_left = null;
                            if (account.swipe_right != null && account.swipe_right > 0)
                                account.swipe_right = null;
                            account.move_to = null;

                            account.created = new Date().getTime();
                            account.id = db.account().insertAccount(account);
                            EntityLog.log(context, "Imported account=" + account.name +
                                    " id=" + account.id + " (" + aid + ")");

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                account.deleteNotificationChannel(context);

                                if (account.notify)
                                    if (jaccount.has("channel"))
                                        try {
                                            NotificationChannelGroup group = new NotificationChannelGroup("group." + account.id, account.name);
                                            nm.createNotificationChannelGroup(group);

                                            JSONObject jchannel = (JSONObject) jaccount.get("channel");
                                            jchannel.put("id", EntityAccount.getNotificationChannelId(account.id));
                                            jchannel.put("group", group.getId());
                                            nm.createNotificationChannel(NotificationHelper.channelFromJSON(context, jchannel));

                                            Log.i("Imported account channel=" + jchannel);
                                        } catch (Throwable ex) {
                                            Log.e(ex);
                                            account.createNotificationChannel(context);
                                        }
                                    else
                                        account.createNotificationChannel(context);
                            }

                            JSONArray jidentities = (JSONArray) jaccount.get("identities");
                            for (int i = 0; i < jidentities.length(); i++) {
                                JSONObject jidentity = (JSONObject) jidentities.get(i);
                                EntityIdentity identity = EntityIdentity.fromJSON(jidentity);
                                postProgress(context.getString(R.string.title_importing_identity, identity.email));

                                long id = identity.id;
                                identity.id = null;

                                identity.account = account.id;
                                identity.id = db.identity().insertIdentity(identity);
                                xIdentity.put(id, identity.id);

                                Log.i("Imported identity=" + identity.email + " id=" + identity + id + " (" + id + ")");
                            }

                            JSONArray jfolders = (JSONArray) jaccount.get("folders");
                            for (int f = 0; f < jfolders.length(); f++) {
                                JSONObject jfolder = (JSONObject) jfolders.get(f);
                                EntityFolder folder = EntityFolder.fromJSON(jfolder);
                                long id = folder.id;
                                folder.id = null;

                                folder.account = account.id;
                                folder.id = db.folder().insertFolder(folder);
                                xFolder.put(id, folder.id);

                                if (Objects.equals(swipe_left, id))
                                    account.swipe_left = folder.id;
                                if (Objects.equals(swipe_right, id))
                                    account.swipe_right = folder.id;
                                if (Objects.equals(move_to, id))
                                    account.move_to = folder.id;

                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    String channelId = EntityFolder.getNotificationChannelId(folder.id);
                                    nm.deleteNotificationChannel(channelId);

                                    if (jfolder.has("channel"))
                                        try {
                                            NotificationChannelGroup group = new NotificationChannelGroup("group." + account.id, account.name);
                                            nm.createNotificationChannelGroup(group);

                                            JSONObject jchannel = (JSONObject) jfolder.get("channel");
                                            jchannel.put("id", channelId);
                                            jchannel.put("group", group.getId());
                                            nm.createNotificationChannel(NotificationHelper.channelFromJSON(context, jchannel));

                                            Log.i("Imported folder channel=" + jchannel);
                                        } catch (Throwable ex) {
                                            Log.e(ex);
                                        }
                                }

                                if (jfolder.has("rules")) {
                                    JSONArray jrules = jfolder.getJSONArray("rules");
                                    for (int r = 0; r < jrules.length(); r++) {
                                        JSONObject jrule = (JSONObject) jrules.get(r);
                                        EntityRule rule = EntityRule.fromJSON(jrule);
                                        rule.folder = folder.id;
                                        rules.add(rule);
                                    }
                                }
                                Log.i("Imported folder=" + folder.name + " id=" + folder.id + " (" + id + ")");
                            }

                            if (import_contacts) {
                                // Contacts
                                if (jaccount.has("contacts")) {
                                    postProgress(context.getString(R.string.title_setup_import_contacts), null);

                                    JSONArray jcontacts = jaccount.getJSONArray("contacts");
                                    for (int c = 0; c < jcontacts.length(); c++) {
                                        JSONObject jcontact = (JSONObject) jcontacts.get(c);
                                        EntityContact contact = EntityContact.fromJSON(jcontact);
                                        contact.account = account.id;
                                        contact.identity = xIdentity.get(contact.identity);
                                        if (db.contact().getContact(contact.account, contact.type, contact.email) == null)
                                            contact.id = db.contact().insertContact(contact);
                                    }
                                    Log.i("Imported contacts=" + jcontacts.length());
                                }
                            }

                            // Update swipe left/right
                            db.account().updateAccount(account);
                        }

                        if (import_rules) {
                            postProgress(context.getString(R.string.title_setup_import_rules), null);
                            for (EntityRule rule : rules) {
                                try {
                                    JSONObject jaction = new JSONObject(rule.action);

                                    int type = jaction.getInt("type");
                                    switch (type) {
                                        case EntityRule.TYPE_MOVE:
                                        case EntityRule.TYPE_COPY:
                                            String targetAccountUuid = jaction.optString("targetAccountUuid");
                                            String targetFolderName = jaction.optString("targetFolderName");
                                            if (!TextUtils.isEmpty(targetAccountUuid) && !TextUtils.isEmpty(targetFolderName)) {
                                                EntityAccount a = db.account().getAccountByUUID(targetAccountUuid);
                                                if (a != null) {
                                                    EntityFolder f = db.folder().getFolderByName(a.id, targetFolderName);
                                                    if (f != null) {
                                                        jaction.put("target", f.id);
                                                        break;
                                                    }
                                                }
                                            }

                                            // Legacy
                                            long target = jaction.getLong("target");
                                            Long tid = xFolder.get(target);
                                            Log.i("XLAT target " + target + " > " + tid);
                                            if (tid != null)
                                                jaction.put("target", tid);
                                            break;
                                        case EntityRule.TYPE_ANSWER:
                                            String identityUuid = jaction.optString("identityUuid");
                                            String answerUuid = jaction.optString("answerUuid");
                                            if (!TextUtils.isEmpty(identityUuid) && !TextUtils.isEmpty(answerUuid)) {
                                                EntityIdentity i = db.identity().getIdentityByUUID(identityUuid);
                                                EntityAnswer a = db.answer().getAnswerByUUID(answerUuid);
                                                if (i != null && a != null) {
                                                    jaction.put("identity", i.id);
                                                    jaction.put("answer", a.id);
                                                    break;
                                                }
                                            }

                                            // Legacy
                                            long identity = jaction.getLong("identity");
                                            long answer = jaction.getLong("answer");
                                            Long iid = xIdentity.get(identity);
                                            Long aid = xAnswer.get(answer);
                                            Log.i("XLAT identity " + identity + " > " + iid);
                                            Log.i("XLAT answer " + answer + " > " + aid);
                                            jaction.put("identity", iid);
                                            jaction.put("answer", aid);
                                            break;
                                    }

                                    rule.action = jaction.toString();
                                } catch (JSONException ex) {
                                    Log.e(ex);
                                }

                                db.rule().insertRule(rule);
                            }
                        }
                    }

                    if (import_settings) {
                        postProgress(context.getString(R.string.title_setup_import_settings), null);

                        // Certificates
                        if (jimport.has("certificates")) {
                            JSONArray jcertificates = jimport.getJSONArray("certificates");
                            for (int c = 0; c < jcertificates.length(); c++) {
                                JSONObject jcertificate = (JSONObject) jcertificates.get(c);
                                EntityCertificate certificate = EntityCertificate.fromJSON(jcertificate);
                                EntityCertificate record = db.certificate().getCertificate(certificate.fingerprint, certificate.email);
                                if (record == null) {
                                    db.certificate().insertCertificate(certificate);
                                    Log.i("Imported certificate=" + certificate.email);
                                }
                            }
                        }

                        // Settings
                        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                        SharedPreferences.Editor editor = prefs.edit();

                        for (String[] options : RESET_ALL_OPTIONS)
                            for (String key : options)
                                if (!skipOption(key) &&
                                        !DEFER_OPTIONS.contains(key) &&
                                        !RESTART_OPTIONS.contains(key)) {
                                    if (BuildConfig.DEBUG && prefs.contains(key))
                                        postProgress("Clearing " + key);
                                    editor.remove(key);
                                }

                        JSONArray jsettings = jimport.getJSONArray("settings");
                        for (int s = 0; s < jsettings.length(); s++) {
                            JSONObject jsetting = (JSONObject) jsettings.get(s);
                            String key = jsetting.getString("key");

                            if (DEFER_OPTIONS.contains(key)) {
                                defer.put(key, jsetting.get("value"));
                                continue;
                            }

                            // Prevent restart
                            if (RESTART_OPTIONS.contains(key)) {
                                postProgress("Skipping " + key + "=" + jsetting.get("value"));
                                continue;
                            }

                            if (skipOption(key))
                                continue;

                            Object value = jsetting.get("value");
                            String type = jsetting.optString("type");
                            Log.i("Setting name=" + key + " value=" + value + " type=" + type);
                            switch (type) {
                                case "bool":
                                    editor.putBoolean(key, (Boolean) value);
                                    break;
                                case "int":
                                    editor.putInt(key, (Integer) value);
                                    break;
                                case "long":
                                    if (value instanceof Integer)
                                        editor.putLong(key, Long.valueOf((Integer) value));
                                    else
                                        editor.putLong(key, (Long) value);
                                    break;
                                case "float":
                                    if (value instanceof Double)
                                        editor.putFloat(key, ((Double) value).floatValue());
                                    else if (value instanceof Integer)
                                        editor.putFloat(key, ((Integer) value).floatValue());
                                    else
                                        editor.putFloat(key, (Float) value);
                                    break;
                                case "string":
                                    editor.putString(key, (String) value);
                                    break;
                                default:
                                    Log.w("Inferring type of value=" + value);
                                    if (value instanceof Boolean)
                                        editor.putBoolean(key, (Boolean) value);
                                    else if (value instanceof Integer) {
                                        Integer i = (Integer) value;
                                        if (key.endsWith(".account"))
                                            editor.putLong(key, Long.valueOf(i));
                                        else
                                            editor.putInt(key, i);
                                    } else if (value instanceof Long)
                                        editor.putLong(key, (Long) value);
                                    else if (value instanceof Double)
                                        editor.putFloat(key, ((Double) value).floatValue());
                                    else if (value instanceof Float)
                                        editor.putFloat(key, (Float) value);
                                    else if (value instanceof String)
                                        editor.putString(key, (String) value);
                                    else {
                                        String msg = "Unknown settings type" +
                                                " key=" + key +
                                                " value=" + value +
                                                " type=" + (value == null ? null : value.getClass());
                                        Log.e(msg);
                                        postProgress(msg);
                                    }
                            }

                            Log.i("Imported setting=" + key);
                        }
                        editor.apply();
                        ApplicationEx.upgrade(context);
                    }

                    if (import_accounts) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            if (jimport.has("channels")) {
                                JSONArray jchannels = jimport.getJSONArray("channels");
                                for (int i = 0; i < jchannels.length(); i++)
                                    try {
                                        JSONObject jchannel = (JSONObject) jchannels.get(i);

                                        String channelId = jchannel.getString("id");
                                        nm.deleteNotificationChannel(channelId);

                                        nm.createNotificationChannel(NotificationHelper.channelFromJSON(context, jchannel));

                                        Log.i("Imported contact channel=" + jchannel);
                                    } catch (Throwable ex) {
                                        Log.e(ex);
                                    }
                            }
                        }
                    }

                    return null;
                });

                Log.i("Imported data");

                SpannableStringBuilder ssb = new SpannableStringBuilderEx();
                ssb.append(context.getString(R.string.title_setup_imported));
                ssb.setSpan(new StyleSpan(Typeface.BOLD), 0, ssb.length(), 0);
                postProgress(ssb, null);
                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                ServiceSynchronize.eval(context, "import");
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                if (ex instanceof NoStreamException)
                    ((NoStreamException) ex).report(getActivity());
                else {
                    if (ex instanceof SecurityException)
                        ex = new Throwable("No read permission has been granted by the file selector", ex);

                    SpannableStringBuilder ssb = new SpannableStringBuilderEx();
                    if (ex.getCause() instanceof BadPaddingException /* GCM: AEADBadTagException */)
                        ssb.append(getString(R.string.title_setup_password_invalid));
                    else if (ex instanceof IOException && ex.getCause() instanceof IllegalBlockSizeException)
                        ssb.append(getString(R.string.title_setup_import_invalid));
                    if (ssb.length() > 0) {
                        ssb.setSpan(new StyleSpan(Typeface.BOLD), 0, ssb.length(), 0);
                        ssb.setSpan(new ForegroundColorSpan(colorWarning), 0, ssb.length(), 0);
                        ssb.append("\n\n");
                    }
                    ssb.append(new ThrowableWrapper(ex).toSafeString());
                    onProgress(ssb, null);
                }
            }
        }.setHandler(tvLog.getHandler()).execute(this, args, "setup:import");
    }

    private static boolean skipOption(String key) {

        if ("accept_unsupported".equals(key))
            return true;

        if ("biometrics".equals(key) || "pin".equals(key))
            return true;

        if ("alert_once".equals(key) && !Helper.isXiaomi())
            return true;

        if ("default_folder".equals(key))
            return true;

        if ("background_service".equals(key) &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            return true;

        if ("tcp_keep_alive".equals(key))
            return true;

        if ("external_storage".equals(key))
            return true;

        if ("reformatted_hint".equals(key))
            return true;

        if ("external_search".equals(key))
            return true;

        if (key != null && key.startsWith("widget."))
            return true;
        if (key != null && key.startsWith("unset."))
            return true;

        return false;
    }

    private void handleK9Import(Uri uri) {
        Bundle args = new Bundle();
        args.putParcelable("uri", uri);

        new SimpleTask<Void>() {
            @Override
            protected Void onExecute(Context context, Bundle args) throws Throwable {
                Uri uri = Helper.getParcelable(args, "uri", Uri.class);

                DB db = DB.getInstance(context);
                ContentResolver resolver = context.getContentResolver();
                InputStream is = resolver.openInputStream(uri);
                if (is == null)
                    throw new FileNotFoundException(uri.toString());
                try (InputStream bis = new BufferedInputStream(is)) {
                    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                    XmlPullParser xml = factory.newPullParser();
                    xml.setInput(new InputStreamReader(bis));

                    EntityAccount account = null;
                    EntityIdentity identity = null;
                    boolean inIdentity = false;
                    String iname = null;
                    String iemail = null;
                    List<Pair<String, String>> identities = new ArrayList<>();

                    int eventType = xml.getEventType();
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            String name = xml.getName();
                            switch (name) {
                                case "account":
                                    String uuid = xml.getAttributeValue(null, "uuid");
                                    account = new EntityAccount();
                                    if (!TextUtils.isEmpty(uuid))
                                        account.uuid = uuid;
                                    account.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
                                    account.password = "";
                                    account.synchronize = false;
                                    account.primary = false;
                                    break;
                                case "incoming-server":
                                    if (account != null) {
                                        String itype = xml.getAttributeValue(null, "type");
                                        if ("IMAP".equals(itype))
                                            account.protocol = EntityAccount.TYPE_IMAP;
                                        else if ("POP3".equals(itype))
                                            account.protocol = EntityAccount.TYPE_POP;
                                        else
                                            account = null;
                                    }
                                    break;
                                case "outgoing-server":
                                    String otype = xml.getAttributeValue(null, "type");
                                    if ("SMTP".equals(otype)) {
                                        identity = new EntityIdentity();
                                        identity.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
                                        identity.password = "";
                                        identity.synchronize = false;
                                        identity.primary = false;
                                    }
                                    break;
                                case "host":
                                    eventType = xml.next();
                                    if (eventType == XmlPullParser.TEXT) {
                                        String host = xml.getText();
                                        if (identity != null)
                                            identity.host = host;
                                        else if (account != null)
                                            account.host = host;
                                    }
                                    break;
                                case "port":
                                    eventType = xml.next();
                                    if (eventType == XmlPullParser.TEXT) {
                                        int port = Integer.parseInt(xml.getText());
                                        if (identity != null)
                                            identity.port = port;
                                        else if (account != null)
                                            account.port = port;
                                    }
                                    break;
                                case "connection-security":
                                    eventType = xml.next();
                                    if (eventType == XmlPullParser.TEXT) {
                                        String etype = xml.getText();

                                        int encryption;
                                        if ("STARTTLS_REQUIRED".equals(etype))
                                            encryption = EmailService.ENCRYPTION_STARTTLS;
                                        else if ("SSL_TLS_REQUIRED".equals(etype))
                                            encryption = EmailService.ENCRYPTION_SSL;
                                        else
                                            encryption = EmailService.ENCRYPTION_NONE;

                                        if (identity != null)
                                            identity.encryption = encryption;
                                        else if (account != null)
                                            account.encryption = encryption;
                                    }
                                    break;
                                case "authentication-type":
                                    eventType = xml.next();
                                    if (eventType == XmlPullParser.TEXT) {
                                        String atype = xml.getText();
                                        // PLAIN, CRAM_MD5
                                    }
                                    break;
                                case "username":
                                    eventType = xml.next();
                                    if (eventType == XmlPullParser.TEXT) {
                                        String user = xml.getText();
                                        if (identity != null)
                                            identity.user = user;
                                        else if (account != null)
                                            account.user = user;
                                    }
                                    break;
                                case "name":
                                    eventType = xml.next();
                                    if (eventType == XmlPullParser.TEXT) {
                                        if (inIdentity)
                                            iname = xml.getText();
                                        else {
                                            if (account != null)
                                                account.name = xml.getText();
                                        }
                                    }
                                    break;
                                case "email":
                                    eventType = xml.next();
                                    if (eventType == XmlPullParser.TEXT) {
                                        if (inIdentity)
                                            iemail = xml.getText();
                                    }
                                    break;
                                case "identity":
                                    inIdentity = true;
                                    break;
                            }

                        } else if (eventType == XmlPullParser.END_TAG) {
                            String name = xml.getName();
                            switch (name) {
                                case "account":
                                    try {
                                        if (account != null && identity != null) {
                                            if (TextUtils.isEmpty(account.name))
                                                account.name = account.user;
                                            if (BuildConfig.DEBUG)
                                                account.name = "K9/" + account.name;

                                            final EntityAccount importedAccount = account;
                                            final EntityIdentity importedIdentity = identity;
                                            db.runInTransactionWithCleanup(() -> {
                                                EntityAccount existing = db.account().getAccountByUUID(importedAccount.uuid);
                                                if (existing == null) {

                                                    importedAccount.id = db.account().insertAccount(importedAccount);
                                                    importedIdentity.account = importedAccount.id;
                                                    for (Pair<String, String> i : identities) {
                                                        importedIdentity.id = null;
                                                        importedIdentity.name = i.first;
                                                        importedIdentity.email = i.second;
                                                        if (TextUtils.isEmpty(importedIdentity.name))
                                                            importedIdentity.name = importedIdentity.user;
                                                        if (TextUtils.isEmpty(importedIdentity.email))
                                                            importedIdentity.email = importedIdentity.user;
                                                        importedIdentity.id = db.identity().insertIdentity(importedIdentity);
                                                    }
                                                }
                                            });
                                        }
                                    } finally {
                                        account = null;
                                        identity = null;
                                        identities.clear();
                                    }
                                    break;
                                case "identity":
                                    identities.add(new Pair<>(iname, iemail));
                                    iname = null;
                                    iemail = null;
                                    inIdentity = false;
                                    break;
                            }
                        }

                        eventType = xml.next();
                    }
                }

                return null;
            }

            @Override
            protected void onExecuted(Bundle args, Void data) {
                ToastEx.makeText(getContext(), R.string.title_setup_imported, Toast.LENGTH_LONG).show();
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                Log.unexpectedError(getParentFragmentManager(), ex);
            }
        }.execute(this, args, "setup:k9");
    }

    private void handleAppleImport(Uri uri) {
        Bundle args = new Bundle();
        args.putParcelable("uri", uri);

        ViewModelExport vme = new ViewModelProvider(getActivity()).get(ViewModelExport.class);
        args.putString("password", vme.getPassword());

        new SimpleTask<EntityAccount>() {
            @Override
            protected EntityAccount onExecute(Context context, Bundle args) throws Throwable {
                Uri uri = Helper.getParcelable(args, "uri", Uri.class);
                String password = args.getString("password");

                EntityAccount account = new EntityAccount();
                EntityIdentity identity = new EntityIdentity();

                ContentResolver resolver = context.getContentResolver();
                InputStream is = resolver.openInputStream(uri);
                if (is == null)
                    throw new FileNotFoundException(uri.toString());
                try (InputStream bis = new BufferedInputStream(is)) {
                    XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
                    XmlPullParser xml = factory.newPullParser();
                    xml.setInput(new InputStreamReader(bis));

                    account.auth_type = AUTH_TYPE_PASSWORD;
                    account.password = password;
                    account.encryption = EmailService.ENCRYPTION_STARTTLS;
                    account.synchronize = false;
                    account.primary = false;

                    identity.auth_type = AUTH_TYPE_PASSWORD;
                    identity.password = password;
                    identity.encryption = EmailService.ENCRYPTION_STARTTLS;
                    identity.synchronize = false;
                    identity.primary = true;

                    // https://developer.apple.com/documentation/devicemanagement/mail
                    String key = null;
                    int eventType = xml.getEventType();
                    while (eventType != XmlPullParser.END_DOCUMENT) {
                        if (eventType == XmlPullParser.START_TAG) {
                            String name = xml.getName();
                            if ("key".equals(name)) {
                                eventType = xml.next();
                                key = (eventType == XmlPullParser.TEXT ? xml.getText() : null);
                            } else if (key != null &&
                                    ("string".equals(name) || "integer".equals(name))) {
                                eventType = xml.next();
                                if (eventType == XmlPullParser.TEXT) {
                                    String value = xml.getText();
                                    eventType = xml.next();
                                    if (eventType == XmlPullParser.END_TAG) {
                                        Log.i("mobileconfig " + key + "=" + value);
                                        switch (key) {
                                            case "allowMailDrop":
                                                break;
                                            case "disableMailRecentsSyncing":
                                                break;

                                            case "EmailAccountDescription":
                                                account.name = value;
                                                break;
                                            case "EmailAccountName":
                                                identity.name = value;
                                                break;
                                            case "EmailAccountType": // required
                                                if ("EmailTypeIMAP".equals(value))
                                                    account.protocol = EntityAccount.TYPE_IMAP;
                                                else if ("EmailTypePOP".equals(value))
                                                    account.protocol = EntityAccount.TYPE_POP;
                                                break;
                                            case "EmailAddress":
                                                String[] email = value.split(",");
                                                if (email.length > 0)
                                                    identity.email = email[0];
                                                break;

                                            case "IncomingMailServerAuthentication": // required
                                                // EmailAuthNone, EmailAuthPassword, EmailAuthCRAMMD5, EmailAuthNTLM, EmailAuthHTTPMD5
                                                break;
                                            case "IncomingMailServerHostName": // required
                                                account.host = value;
                                                break;
                                            case "IncomingMailServerIMAPPathPrefix":
                                                break;
                                            case "IncomingMailServerPortNumber":
                                                account.port = Integer.parseInt(value);
                                                break;
                                            case "IncomingMailServerUsername":
                                                account.user = value;
                                                break;
                                            case "IncomingMailServerUseSSL":
                                                account.encryption = EmailService.ENCRYPTION_SSL;
                                                break;
                                            case "IncomingPassword":
                                                account.password = value;
                                                break;

                                            case "OutgoingMailServerAuthentication": // required
                                                // EmailAuthNone, EmailAuthPassword, EmailAuthCRAMMD5, EmailAuthNTLM, EmailAuthHTTPMD5
                                                break;
                                            case "OutgoingMailServerHostName": // required
                                                identity.host = value;
                                                break;
                                            case "OutgoingMailServerPortNumber":
                                                identity.port = Integer.parseInt(value);
                                                break;
                                            case "OutgoingMailServerUsername":
                                                identity.user = value;
                                                break;
                                            case "OutgoingMailServerUseSSL":
                                                identity.encryption = EmailService.ENCRYPTION_SSL;
                                                break;
                                            case "OutgoingPassword":
                                                identity.password = value;
                                                break;
                                            case "OutgoingPasswordSameAsIncomingPassword":
                                                identity.password = account.password;
                                                break;
                                        }
                                    }
                                } else
                                    key = null;
                            } else if (key != null && "true".equals(name)) {
                                Log.i("mobileconfig " + key + "=" + name);
                            } else
                                key = null;
                        }
                        eventType = xml.next();
                    }

                    if (account.port == null)
                        account.port = 993;
                    if (account.password == null)
                        account.password = "";
                    if (identity.port == null)
                        identity.port = 587;
                    if (identity.password == null)
                        identity.password = "";
                }

                DB db = DB.getInstance(context);
                db.runInTransactionWithCleanup(() -> {

                    account.id = db.account().insertAccount(account);
                    identity.account = account.id;
                    identity.id = db.identity().insertIdentity(identity);

                });

                return account;
            }

            @Override
            protected void onExecuted(Bundle args, EntityAccount account) {
                if (account == null)
                    return;

                AppBroadcastManager lbm = AppBroadcastManager.getInstance(getContext());
                lbm.sendBroadcast(
                        new Intent(ActivitySetup.ACTION_EDIT_ACCOUNT)
                                .putExtra("id", account.id)
                                .putExtra("protocol", account.protocol));
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                Log.unexpectedError(getParentFragmentManager(), ex);
            }
        }.execute(this, args, "setup:apple");
    }

    private void askPassword(final boolean export) {
        final Context context = getContext();
        Intent intent = (export ? getIntentExport(context) : getIntentImport(context));
        PackageManager pm = context.getPackageManager();
        if (intent.resolveActivity(pm) == null) { // system/GET_CONTENT whitelisted
            Log.unexpectedError(getParentFragmentManager(),
                    new IllegalArgumentException(context.getString(R.string.title_no_saf)), 25);
            return;
        }

        try {
            FragmentDialogBase fragment =
                    (export ? new FragmentDialogExport() : new FragmentDialogImport());
            fragment.setResultTarget(this, export ? REQUEST_EXPORT_SELECT : REQUEST_IMPORT_SELECT);
            fragment.show(getParentFragmentManager(), "password");
        } catch (Throwable ex) {
            Log.unexpectedError(FragmentOptionsBackup.this, ex);
        }
    }

    private static Intent getIntentExport(Context context) {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_TITLE, "mail_" +
                new SimpleDateFormat("yyyyMMdd").format(new Date().getTime()) + ".backup");
        Helper.openAdvanced(context, intent);
        return intent;
    }

    private static Intent getIntentImport(Context context) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setType("*/*");
        return intent;
    }










}

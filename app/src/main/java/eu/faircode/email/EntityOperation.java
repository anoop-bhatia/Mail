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

import static androidx.room.ForeignKey.CASCADE;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteConstraintException;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.mail.internet.InternetAddress;

@Entity(
        tableName = EntityOperation.TABLE_NAME,
        foreignKeys = {
                @ForeignKey(childColumns = "folder", entity = EntityFolder.class, parentColumns = "id", onDelete = CASCADE),
                @ForeignKey(childColumns = "message", entity = EntityMessage.class, parentColumns = "id", onDelete = CASCADE)
        },
        indices = {
                @Index(value = {"account"}),
                @Index(value = {"folder"}),
                @Index(value = {"message"}),
                @Index(value = {"name"}),
                @Index(value = {"state"})
        }
)
public class EntityOperation {
    static final String TABLE_NAME = "operation";

    @PrimaryKey(autoGenerate = true)
    public Long id;
    public Long account; // performance
    @NonNull
    public Long folder;
    public Long message;
    @NonNull
    public String name;
    @NonNull
    public String args;
    @NonNull
    public Long created;
    @NonNull
    public int tries = 0;
    public String state;
    public String error;

    static final String ADD = "add";
    static final String MOVE = "move";
    static final String COPY = "copy";
    static final String FETCH = "fetch";
    static final String DELETE = "delete";
    static final String SEEN = "seen";
    static final String ANSWERED = "answered";
    static final String FLAG = "flag";
    static final String KEYWORD = "keyword";
    static final String LABEL = "label"; // Gmail
    static final String HEADERS = "headers";
    static final String RAW = "raw";
    static final String BODY = "body";
    static final String ATTACHMENT = "attachment";
    static final String DETACH = "detach";
    static final String SYNC = "sync";
    static final String SUBSCRIBE = "subscribe";
    static final String SEND = "send";
    static final String EXISTS = "exists";
    static final String RULE = "rule";
    static final String PURGE = "purge";
    static final String EXPUNGE = "expunge";
    static final String REPORT = "report";
    static final String DOWNLOAD = "download";
    static final String SUBJECT = "subject";
    static final String GRAPH_PURGE_TRASH = "graph_purge_trash";
    static final String GRAPH_PERMANENT_DELETE = "graph_permanent_delete";
    static final String GRAPH_DELETE_PERMANENT = "graph_delete_permanent";
    static final String ZOHO_PURGE_TRASH = "zoho_purge_trash";
    static final String ZOHO_PURGE_SPAM = "zoho_purge_spam";
    static final String ZOHO_DELETE_SPAM_PERMANENT = "zoho_delete_spam_permanent";
    static final String ZOHO_PERMANENT_DELETE = "zoho_permanent_delete";
    static final String GMAIL_PERMANENT_DELETE = "gmail_permanent_delete";
    static final String GRAPH_API_WRITE_NAMES_SQL =
            "'" + DELETE + "', '" + MOVE + "', '" + SEEN + "', '" + FLAG + "', '" +
                    GRAPH_PURGE_TRASH + "', '" + GRAPH_PERMANENT_DELETE + "', '" +
                    GRAPH_DELETE_PERMANENT + "'";
    static final String ZOHO_API_WRITE_NAMES_SQL =
            "'" + DELETE + "', '" + MOVE + "', '" + SEEN + "', '" + FLAG + "', '" +
                    ZOHO_PURGE_TRASH + "', '" + ZOHO_PURGE_SPAM + "', '" +
                    ZOHO_DELETE_SPAM_PERMANENT + "', '" + ZOHO_PERMANENT_DELETE + "'";
    static final String SEND_OUTCOME_UNKNOWN_ERROR =
            "Graph send outcome unknown; automatic retry suppressed to avoid duplicates.";
    static final String GMAIL_SEND_OUTCOME_UNKNOWN_ERROR =
            "Gmail send outcome unknown; automatic retry suppressed to avoid duplicates.";

    static boolean isGmailApiMutation(String name) {
        return (DELETE.equals(name) || GMAIL_PERMANENT_DELETE.equals(name) ||
                MOVE.equals(name) || SEEN.equals(name) || FLAG.equals(name) ||
                KEYWORD.equals(name) || LABEL.equals(name));
    }

    static boolean isGmailApiWriteOperation(String name) {
        return (isGmailApiMutation(name) || EXPUNGE.equals(name));
    }

    private static boolean isLegacyNativeRestMutation(String name) {
        return (ADD.equals(name) || DELETE.equals(name) || MOVE.equals(name) || COPY.equals(name) ||
                SEEN.equals(name) || FLAG.equals(name) || ANSWERED.equals(name) ||
                KEYWORD.equals(name) || LABEL.equals(name) || EXPUNGE.equals(name));
    }

    static boolean isZohoApiMutation(String name) {
        return (isLegacyNativeRestMutation(name) || ZOHO_DELETE_SPAM_PERMANENT.equals(name) ||
                ZOHO_PERMANENT_DELETE.equals(name));
    }

    static boolean isZohoApiMessageWrite(String name) {
        return ZohoMailMutator.isMessageWrite(name);
    }

    static boolean isGraphApiMutation(String name) {
        return (isLegacyNativeRestMutation(name) || GRAPH_PERMANENT_DELETE.equals(name) ||
                GRAPH_DELETE_PERMANENT.equals(name));
    }

    static boolean isGraphApiRemoteWrite(String name) {
        return (DELETE.equals(name) || MOVE.equals(name) || SEEN.equals(name) || FLAG.equals(name) ||
                GRAPH_PERMANENT_DELETE.equals(name) || GRAPH_DELETE_PERMANENT.equals(name));
    }
    static boolean isGraphApiWriteOperation(String name) {
        return (isGraphApiRemoteWrite(name) || GRAPH_PURGE_TRASH.equals(name));
    }

    static boolean canQueueNativeRestDestructiveAction(EntityAccount account,
                                                       EntityFolder source, String name) {
        if (source == null)
            return false;

        boolean gmailRestMutation = (account != null && account.isGmailApi() &&
                isGmailApiMutation(name));
        return ((!source.read_only || gmailRestMutation) &&
                (source.selectable || EntityFolder.OUTBOX.equals(source.type) ||
                        EntityFolder.DRAFTS.equals(source.type)));
    }

    static boolean isGmailApiLocalDraft(EntityAccount account, EntityFolder folder, EntityMessage message) {
        return (account != null && account.isGmailApi() && folder != null &&
                EntityFolder.DRAFTS.equals(folder.type) && message != null && message.gmail_id == null);
    }

    static boolean isZohoApiLocalDraft(EntityAccount account, EntityFolder folder, EntityMessage message) {
        return (account != null && account.isZohoApi() && folder != null &&
                EntityFolder.DRAFTS.equals(folder.type) && message != null && message.zoho_id == null);
    }

    static boolean isGraphApiLocalDraft(EntityAccount account, EntityFolder folder, EntityMessage message) {
        return (account != null && account.isGraphApi() && folder != null &&
                EntityFolder.DRAFTS.equals(folder.type) && message != null && message.graph_id == null);
    }

    static boolean usesLegacyImapSyncSideEffects(EntityAccount account) {
        return (account != null && !account.isNativeRestApi() &&
                account.protocol == EntityAccount.TYPE_IMAP);
    }

    static boolean shouldQueueSentExists(EntityAccount account) {
        return (account == null || usesLegacyImapSyncSideEffects(account));
    }

    static boolean shouldCreateLocalMoveCopy(EntityAccount account) {
        // Gmail REST keeps one local row per Gmail message; membership is many-to-many labels.
        return (account == null || !account.isGmailApi());
    }

    static boolean isSendOutcomeUnknown(EntityOperation operation) {
        return (operation != null && SEND.equals(operation.name) &&
                (SEND_OUTCOME_UNKNOWN_ERROR.equals(operation.error) ||
                        GMAIL_SEND_OUTCOME_UNKNOWN_ERROR.equals(operation.error)));
    }

    static boolean shouldRecoverSendOnSync(EntityOperation operation) {
        return (operation == null || (!"executing".equals(operation.state) &&
                !isSendOutcomeUnknown(operation)));
    }

    static boolean isGmailApiLegacyExists(EntityAccount account, String name) {
        return (account != null && account.isGmailApi() && EXISTS.equals(name));
    }

    private static final int MAX_FETCH = 100; // operations
    private static final long FORCE_WITHIN = 30 * 1000; // milliseconds
    private static final long ACROSS_BUSY = 2 * 3600 * 1000L; // milliseconds

    static JSONArray createOperationArgs(Object... values) {
        JSONArray jargs = new JSONArray();
        for (Object value : values)
            jargs.put(value);
        return jargs;
    }

    static void queue(Context context, EntityMessage message, String name, Object... values) {
        queueWithWakePolicy(context, message, name, true, values);
    }

    private static void queueWithWakePolicy(Context context, EntityMessage message, String name,
                                            boolean wakeGmailApi, Object... values) {
        DB db = DB.getInstance(context);
        EntityAccount account = db.account().getAccount(message.account);

        if (account != null && account.isNativeRestApi() &&
                (DELETE.equals(name) || MOVE.equals(name) || COPY.equals(name) ||
                        ZOHO_DELETE_SPAM_PERMANENT.equals(name) ||
                        ZOHO_PERMANENT_DELETE.equals(name) ||
                        GRAPH_DELETE_PERMANENT.equals(name) ||
                        GMAIL_PERMANENT_DELETE.equals(name))) {
            EntityMessage current = db.message().getMessage(message.id);
            if (current == null || !Objects.equals(current.account, message.account) ||
                    !Objects.equals(current.folder, message.folder)) {
                Log.i("Refused stale message action=" + name + " message=" + message.id);
                return;
            }
            message = current;
        }

        if (account != null && account.isTestAccount(context)) {
            if (DELETE.equals(name) || SEND.equals(name))
                db.message().deleteMessage(message.id);
            return;
        }

        if (GMAIL_PERMANENT_DELETE.equals(name) &&
                (account == null || !account.isGmailApi())) {
            Log.i("Gmail permanent delete refused for a non-Gmail account message=" + message.id);
            return;
        }

        if (ZOHO_DELETE_SPAM_PERMANENT.equals(name) &&
                (account == null || !account.isZohoApi())) {
            Log.i("Zoho Spam permanent delete refused for a non-Zoho account message=" + message.id);
            return;
        }

        if (ZOHO_PERMANENT_DELETE.equals(name) &&
                (account == null || !account.isZohoApi())) {
            Log.i("Zoho permanent delete refused for a non-Zoho account message=" + message.id);
            return;
        }

        if (GRAPH_DELETE_PERMANENT.equals(name) &&
                (account == null || !account.isGraphApi())) {
            Log.i("Graph permanent delete refused for a non-Graph account message=" + message.id);
            return;
        }

        if (account != null && account.isGmailApi()) {
            EntityFolder folder = db.folder().getFolder(message.folder);
            if (isGmailApiLegacyExists(account, name)) {
                Log.i("Gmail API legacy EXISTS operation skipped message=" + message.id);
                return;
            }
            if (message.gmail_id != null && isLegacyNativeRestMutation(name) &&
                    !isGmailApiMutation(name)) {
                Log.i("Gmail API unsupported operation refused=" + name + " message=" + message.id);
                return;
            }
            if (isGmailApiLocalDraft(account, folder, message)) {
                if (ADD.equals(name)) {
                    Log.i("Gmail API local draft saved locally message=" + message.id);
                    return;
                }
                if (DELETE.equals(name) || GMAIL_PERMANENT_DELETE.equals(name)) {
                    db.message().deleteMessage(message.id);
                    return;
                }
                if (MOVE.equals(name) || COPY.equals(name)) {
                    if (MOVE.equals(name) && values != null && values.length > 0 &&
                            values[0] instanceof Number) {
                        EntityFolder target = db.folder().getFolder(((Number) values[0]).longValue());
                        if (target != null && EntityFolder.TRASH.equals(target.type))
                            db.message().deleteMessage(message.id);
                    }
                    return;
                }
            }
        }

        if (account != null && account.isGmailApi() && GMAIL_PERMANENT_DELETE.equals(name)) {
            EntityFolder folder = db.folder().getFolder(message.folder);
            if (!GmailMailMutator.canDeleteFromGmail(account, folder, message)) {
                Log.i("Gmail API refused permanent delete for a non-message or unsupported draft message=" +
                        message.id);
                return;
            }
            if (db.operation().getOperation(message.id, GMAIL_PERMANENT_DELETE) != null)
                return;
        }

        if (account != null && account.isGmailApi() && isGmailApiMutation(name)) {
            // Clear only stale optimistic UI overlays from earlier interrupted clients;
            // actual labels/folder/read state will be reconciled from Gmail by the worker.
            db.message().setMessageUiHide(message.id, false);
            db.message().setMessageUiBusy(message.id, null);
            db.message().setMessageUiDeleted(message.id, false);
        }

        if (account != null && account.isZohoApi()) {
            EntityFolder folder = db.folder().getFolder(message.folder);
            if (isZohoApiMutation(name)) {
                if (isZohoApiLocalDraft(account, folder, message)) {
                    if (ADD.equals(name)) {
                        Log.i("Zoho API local draft saved locally message=" + message.id);
                        return;
                    }
                    if (DELETE.equals(name)) {
                        db.message().deleteMessage(message.id);
                        return;
                    }
                    if (MOVE.equals(name) || COPY.equals(name)) {
                        if (MOVE.equals(name) && values != null && values.length > 0 &&
                                values[0] instanceof Number) {
                            EntityFolder target = db.folder().getFolder(((Number) values[0]).longValue());
                            if (target != null && EntityFolder.TRASH.equals(target.type))
                                db.message().deleteMessage(message.id);
                        }
                        return;
                    }
                }
                if (!isZohoApiMessageWrite(name) || message.zoho_id == null || folder == null ||
                        !Objects.equals(folder.account, account.id) || !Objects.equals(message.account, account.id) ||
                        folder.read_only || !folder.selectable ||
                        !ZohoApi.isValidRemoteId(folder.zoho_folder_id) ||
                        !ZohoApi.isValidRemoteId(message.zoho_id)) {
                    Log.i("Zoho API unsupported/local-only operation skipped=" + name + " message=" + message.id);
                    return;
                }
                if (ZOHO_DELETE_SPAM_PERMANENT.equals(name) &&
                        !EntityFolder.JUNK.equals(folder.type)) {
                    Log.i("Zoho Spam permanent delete refused outside Spam/Junk message=" + message.id);
                    return;
                }
                if (MOVE.equals(name)) {
                    EntityFolder target = null;
                    try {
                        if (values != null && values.length > 0 && values[0] instanceof Number)
                            target = db.folder().getFolder(((Number) values[0]).longValue());
                    } catch (RuntimeException ignored) {
                    }
                    if (target == null || !Objects.equals(target.account, account.id) ||
                            target.read_only || !target.selectable ||
                            !ZohoApi.isValidRemoteId(target.zoho_folder_id) ||
                            Objects.equals(target.id, folder.id)) {
                        Log.i("Zoho API invalid/local-only move skipped message=" + message.id);
                        return;
                    }
                }
            }
        }

        if (account != null && account.isGraphApi()) {
            EntityFolder folder = db.folder().getFolder(message.folder);
            if (isGraphApiMutation(name)) {
                if (isGraphApiLocalDraft(account, folder, message)) {
                    if (ADD.equals(name))
                        return;
                    if (DELETE.equals(name)) {
                        db.message().deleteMessage(message.id);
                        return;
                    }
                    if (MOVE.equals(name) || COPY.equals(name)) {
                        if (MOVE.equals(name) && values != null && values.length > 0 &&
                                values[0] instanceof Number) {
                            EntityFolder target = db.folder().getFolder(((Number) values[0]).longValue());
                            if (target != null && EntityFolder.TRASH.equals(target.type))
                                db.message().deleteMessage(message.id);
                        }
                        return;
                    }
                }
                if (message.graph_id == null || folder == null ||
                        !Objects.equals(folder.account, account.id) || !Objects.equals(message.account, account.id) ||
                        folder.graph_folder_id == null || folder.graph_folder_id.length() == 0 ||
                        folder.read_only || !folder.selectable || !isGraphApiRemoteWrite(name)) {
                    Log.i("Microsoft Graph API local-only operation skipped=" + name + " message=" + message.id);
                    return;
                }
                if (MOVE.equals(name)) {
                    EntityFolder target = null;
                    EntityAccount targetAccount = null;
                    try {
                        if (values != null && values.length > 0 && values[0] instanceof Number) {
                            target = db.folder().getFolder(((Number) values[0]).longValue());
                            if (target != null && target.account != null)
                                targetAccount = db.account().getAccount(target.account);
                        }
                    } catch (RuntimeException ignored) {
                    }
                    if (target == null || targetAccount == null ||
                            !MessageSelectionCapabilities.canMoveGraphMessage(true, false,
                                    message.account, targetAccount.isGraphApi(), target.account,
                                    target.selectable, target.read_only, target.graph_folder_id)) {
                        Log.i("Microsoft Graph invalid/local-only move skipped message=" + message.id);
                        return;
                    }
                }
            }
        }

        if (account != null && account.isNativeRestApi() &&
                (DELETE.equals(name) || MOVE.equals(name) ||
                        ZOHO_DELETE_SPAM_PERMANENT.equals(name) ||
                        ZOHO_PERMANENT_DELETE.equals(name) ||
                        GRAPH_DELETE_PERMANENT.equals(name) ||
                        GMAIL_PERMANENT_DELETE.equals(name))) {
            EntityFolder source = db.folder().getFolder(message.folder);
            if (!canQueueNativeRestDestructiveAction(account, source, name)) {
                Log.i("Refused stale/unsupported destructive action=" + name + " message=" + message.id);
                return;
            }
        }

        try {
            JSONArray jargs = createOperationArgs(values);

            if (SEEN.equals(name)) {
                boolean seen = jargs.getBoolean(0);
                boolean ignore = jargs.optBoolean(1, true);
                if (account != null && account.isGmailApi()) {
                    // Gmail labelIds are authoritative; the REST response or a later
                    // readback commits read/unread state.
                    db.message().setMessageUiIgnored(message.id, ignore);
                    queue(context, message.account, message.folder, message.id, name, jargs,
                            wakeGmailApi);
                    return;
                }
                for (EntityMessage similar : db.message().getMessagesBySimilarity(message.account, message.id, message.msgid, message.hash)) {
                    if ((account != null && !account.isGmail() && !account.isWebDe()) &&
                            !Objects.equals(message.id, similar.id) &&
                            Objects.equals(message.msgid, similar.msgid))
                        continue;
                    if (similar.ui_seen != seen || similar.ui_ignored != ignore) {
                        db.message().setMessageUiSeen(similar.id, seen);
                        db.message().setMessageUiIgnored(similar.id, ignore);
                        queue(context, similar.account, similar.folder, similar.id, name, jargs);
                    }
                }
                return;

            } else if (FLAG.equals(name)) {
                boolean flagged = jargs.getBoolean(0);
                Integer color = (jargs.length() > 1 && !jargs.isNull(1) ? jargs.getInt(1) : null);
                if (account != null && account.isGmailApi()) {
                    queue(context, message.account, message.folder, message.id, name, jargs,
                            wakeGmailApi);
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                    boolean auto_important = prefs.getBoolean("auto_important", false);
                    if (auto_important && jargs.optBoolean(2, true)) {
                        queueWithWakePolicy(context, message, KEYWORD, wakeGmailApi,
                                MessageHelper.FLAG_LOW_IMPORTANCE, false);
                        queueWithWakePolicy(context, message, KEYWORD, wakeGmailApi,
                                MessageHelper.FLAG_HIGH_IMPORTANCE, flagged);
                    }
                    return;
                }
                for (EntityMessage similar : db.message().getMessagesBySimilarity(message.account, message.id, message.msgid, message.hash)) {
                    if ((account != null && !account.isGmail()) &&
                            !Objects.equals(message.id, similar.id) &&
                            Objects.equals(message.msgid, similar.msgid))
                        continue;
                    if (similar.ui_flagged != flagged || !Objects.equals(similar.color, color)) {
                        db.message().setMessageUiFlagged(similar.id, flagged, flagged ? color : null);
                        queue(context, similar.account, similar.folder, similar.id, name, jargs);
                    }
                }

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                boolean auto_important = prefs.getBoolean("auto_important", false);
                if (auto_important && (account == null || !account.isGraphApi()) && jargs.optBoolean(2, true)) {
                    db.message().setMessageImportance(message.id, flagged ? EntityMessage.PRIORITIY_HIGH : null);
                    queue(context, message, KEYWORD, MessageHelper.FLAG_LOW_IMPORTANCE, false);
                    queue(context, message, KEYWORD, MessageHelper.FLAG_HIGH_IMPORTANCE, flagged);
                }

                return;

            } else if (ANSWERED.equals(name)) {
                for (EntityMessage similar : db.message().getMessagesBySimilarity(message.account, message.id, message.msgid, message.hash)) {
                    if ((account != null && !account.isGmail()) &&
                            !Objects.equals(message.id, similar.id) &&
                            Objects.equals(message.msgid, similar.msgid))
                        continue;
                    db.message().setMessageUiAnswered(similar.id, jargs.getBoolean(0));
                    queue(context, similar.account, similar.folder, similar.id, name, jargs);
                }
                return;

            } else if (KEYWORD.equals(name)) {
                String keyword = jargs.getString(0);
                boolean set = jargs.getBoolean(1);

                if (account != null && account.isGmailApi()) {
                    queue(context, message.account, message.folder, message.id, name, jargs,
                            wakeGmailApi);
                    return;
                }

                List<String> keywords = new ArrayList<>(Arrays.asList(message.keywords));
                if (set == keywords.contains(keyword))
                    return;

                while (keywords.remove(keyword))
                    ;
                if (set)
                    keywords.add(keyword);

                Collections.sort(keywords);

                message.keywords = keywords.toArray(new String[0]);
                db.message().setMessageKeywords(message.id, DB.Converters.fromStringArray(message.keywords));

                if (set) {
                    EntityFolder folder = db.folder().getFolder(message.folder);
                    if (folder != null) {
                        List<String> fkeywords = new ArrayList<>();
                        if (folder.keywords != null)
                            fkeywords.addAll(Arrays.asList(folder.keywords));
                        if (!fkeywords.contains(keyword))
                            fkeywords.add(keyword);
                        Collections.sort(fkeywords);
                        db.folder().setFolderKeywords(folder.id,
                                DB.Converters.fromStringArray(fkeywords.toArray(new String[0])));
                    }
                } else {
                    if (account != null && account.protocol == EntityAccount.TYPE_POP) {
                        EntityFolder folder = db.folder().getFolder(message.folder);
                        if (folder != null) {
                            List<String> fkeywords = new ArrayList<>();
                            List<String[]> mkeywords = db.message().getKeywords(folder.id);
                            if (mkeywords != null)
                                for (String[] kws : mkeywords) {
                                    for (String kw : kws)
                                        if (!fkeywords.contains(kw))
                                            fkeywords.add(kw);
                                }
                            Collections.sort(fkeywords);
                            db.folder().setFolderKeywords(folder.id,
                                    DB.Converters.fromStringArray(fkeywords.toArray(new String[0])));
                        }
                    }
                }

            } else if (LABEL.equals(name)) {
                String label = jargs.getString(0);
                boolean set = jargs.getBoolean(1);

                if (account != null && account.isGmailApi()) {
                    queue(context, message.account, message.folder, message.id, name, jargs,
                            wakeGmailApi);
                    return;
                }

                if (message.setLabel(label, set))
                    db.message().setMessageLabels(message.id, DB.Converters.fromStringArray(message.labels));

            } else if (MOVE.equals(name)) {
                // Parameters in:
                // 0: target folder
                // 1: mark seen
                // 2: auto classified
                // 3: no block sender

                // Parameters out:
                // 0: target folder
                // 1: mark seen
                // 2: temporary message
                // 3: remove flag
                // 4: permanently delete

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                boolean autoread = prefs.getBoolean("autoread", false);
                boolean autounflag = prefs.getBoolean("autounflag", false);
                boolean reset_importance = prefs.getBoolean("reset_importance", false) &&
                        (account == null || !account.isGraphApi());
                boolean reset_snooze = prefs.getBoolean("reset_snooze", true);
                boolean auto_block_sender = prefs.getBoolean("auto_block_sender", true);

                if (jargs.opt(1) != null) {
                    // rules, classify
                    autoread = jargs.getBoolean(1);
                    autounflag = false;
                }

                boolean auto_classified = false;
                if (jargs.opt(2) != null) {
                    auto_classified = jargs.getBoolean(2);
                    jargs.remove(2);
                }

                EntityFolder source = db.folder().getFolder(message.folder);
                EntityFolder target = db.folder().getFolder(jargs.getLong(0));
                if (source == null || target == null || source.id.equals(target.id))
                    return;
                if (account != null && account.isGmailApi() &&
                        !GmailMailMutator.canMoveToTarget(account, source, message, target)) {
                    Log.i("Gmail API invalid/local-only move skipped message=" + message.id +
                            " target=" + target.id);
                    return;
                }

                if (message.from != null && message.from.length == 1 &&
                        EntityFolder.USER.equals(target.type)) {
                    String email = ((InternetAddress) message.from[0]).getAddress();
                    if (!TextUtils.isEmpty(email)) {
                        EntityContact contact = db.contact().getContact(target.account, EntityContact.TYPE_FROM, email);
                        if (contact != null)
                            db.contact().setContactFolder(contact.id, target.id);
                    }
                }

                if (EntityFolder.JUNK.equals(target.type) &&
                        Objects.equals(source.account, target.account)) {
                    message.show_images = false;
                    message.show_full = false;

                    Boolean noblock = (Boolean) jargs.opt(3);
                    jargs.remove(3);
                    boolean block = (noblock == null ? auto_block_sender : !noblock);
                    if (block) {
                        // Prevent blocking self
                        List<TupleIdentityEx> identities = db.identity().getComposableIdentities(null);
                        if (!message.fromSelf(identities)) {
                            EntityLog.log(context, "Auto block sender=" + MessageHelper.formatAddresses(message.from));
                            EntityContact.update(context,
                                    message.account, message.identity, message.from,
                                    EntityContact.TYPE_JUNK, message.received);
                        }
                    }
                }

                if (EntityFolder.DRAFTS.equals(source.type) &&
                        EntityFolder.TRASH.equals(target.type))
                    autoread = true;

                if (EntityFolder.JUNK.equals(source.type) &&
                        EntityFolder.INBOX.equals(target.type))
                    autoread = false;

                jargs.put(1, autoread);
                jargs.put(3, autounflag);

                EntityLog.log(context, EntityLog.Type.General, message,
                        "Move message=" + message.id +
                                "@" + new Date(message.received) +
                                ":" + message.subject +
                                " source=" + source.id + ":" + source.type + ":" + source.name + "" +
                                " target=" + target.id + ":" + target.type + ":" + target.name +
                                " auto read=" + autoread + " flag=" + autounflag + " importance=" + reset_importance);

                if (autoread || autounflag || reset_importance) {
                    for (EntityMessage similar : db.message().getMessagesBySimilarity(message.account, message.id, message.msgid, message.hash)) {
                        if ((account != null && !account.isGmail()) &&
                                !Objects.equals(message.id, similar.id) &&
                                Objects.equals(message.msgid, similar.msgid))
                            continue;
                        if (autoread)
                            queueWithWakePolicy(context, similar, SEEN, wakeGmailApi, true);
                        if (autounflag)
                            queueWithWakePolicy(context, similar, FLAG, wakeGmailApi, false);
                        if (reset_importance) {
                            if (account == null || !account.isGmailApi())
                                db.message().setMessageImportance(similar.id, null);
                            queueWithWakePolicy(context, similar, KEYWORD, wakeGmailApi,
                                    MessageHelper.FLAG_LOW_IMPORTANCE, false);
                            queueWithWakePolicy(context, similar, KEYWORD, wakeGmailApi,
                                    MessageHelper.FLAG_HIGH_IMPORTANCE, false);
                        }
                    }
                }

                if (message.ui_found)
                    db.message().setMessageFound(message.id, false);

                boolean premove = true;
                if (source.account.equals(target.account)) {
                    if (account != null && account.isGmail() && !account.isGmailApi()) {
                        if (EntityFolder.ARCHIVE.equals(source.type) &&
                                !(EntityFolder.SENT.equals(target.type) ||
                                        EntityFolder.TRASH.equals(target.type) ||
                                        EntityFolder.JUNK.equals(target.type)))
                            name = COPY;
                        else {
                            Log.i("Move: hide source=" + message.id);
                            if (!message.ui_deleted)
                                db.message().setMessageUiHide(message.id, true);
                        }

                        if (!TextUtils.isEmpty(message.msgid) && !TextUtils.isEmpty(message.hash) &&
                                (EntityFolder.SENT.equals(target.type) ||
                                        EntityFolder.TRASH.equals(target.type) ||
                                        EntityFolder.JUNK.equals(target.type))) {
                            EntityMessage archived = db.message().getMessage(message.account, EntityFolder.ARCHIVE, message.msgid);
                            if (archived != null && message.hash.equals(archived.hash)) {
                                Log.i("Move: hide archived=" + archived.id);
                                db.message().setMessageUiHide(archived.id, true);
                            }
                        }

                        if (EntityFolder.DRAFTS.equals(source.type) || EntityFolder.DRAFTS.equals(target.type))
                            premove = false;
                    } else {
                        if (account == null || !account.isGmailApi()) {
                            Log.i("Move: hide other=" + message.id);
                            if (!message.ui_deleted)
                                db.message().setMessageUiHide(message.id, true);
                        }
                    }
                } else {
                    if ((account == null || !account.isGmailApi()) && !message.ui_deleted) {
                        db.message().setMessageUiHide(message.id, true);
                        db.message().setMessageUiBusy(message.id, new Date().getTime() + ACROSS_BUSY);
                    }
                }

                if (message.ui_snoozed != null &&
                        (reset_snooze ||
                                EntityFolder.ARCHIVE.equals(target.type) ||
                                EntityFolder.TRASH.equals(target.type) ||
                                EntityFolder.JUNK.equals(target.type))) {
                    message.ui_snoozed = null;
                    message.ui_ignored = true;
                    db.message().setMessageSnoozed(message.id, null);
                    db.message().setMessageUiIgnored(message.id, true);
                    EntityMessage.snooze(context, message.id, null);
                }

                if (EntityFolder.JUNK.equals(source.type)) {
                    List<EntityRule> rules = db.rule().getRules(target.id);
                    for (EntityRule rule : rules)
                        if (rule.isBlockingSender(message, source))
                            db.rule().deleteRule(rule.id);

                    EntityContact.delete(context, message.account, message.from,
                            EntityContact.TYPE_JUNK);
                    EntityContact.update(context, message.account, message.identity, message.from,
                            EntityContact.TYPE_NO_JUNK, message.received);
                }

                if (EntityFolder.JUNK.equals(target.type))
                    EntityContact.delete(context, message.account, message.from, EntityContact.TYPE_NO_JUNK);

                // Create copy without uid in target folder
                // Message with same msgid can be in archive
                if (premove &&
                        message.uid != null &&
                        !TextUtils.isEmpty(message.msgid) &&
                        shouldCreateLocalMoveCopy(account) &&
                        db.message().countMessageByMsgId(target.id, message.msgid, false) == 0) {
                    File msource = message.getFile(context);

                    // Copy message to target folder
                    long _id = message.id;
                    Long _identity = message.identity;
                    long _uid = message.uid;
                    Boolean _raw = message.raw;
                    Long _stored = message.stored;
                    int _notifying = message.notifying;
                    boolean _fts = message.fts;
                    boolean _auto_classified = message.auto_classified;
                    Integer _importance = message.importance;
                    boolean _seen = message.seen;
                    boolean _flagged = message.flagged;
                    boolean _ui_seen = message.ui_seen;
                    boolean _ui_flagged = message.ui_flagged;
                    boolean _ui_hide = message.ui_hide;
                    boolean _ui_found = message.ui_found;
                    boolean _ui_browsed = message.ui_browsed;
                    Long ui_busy = message.ui_busy;
                    Integer _color = message.color;
                    String _error = message.error;

                    message.id = null;
                    message.account = target.account;
                    message.folder = target.id;
                    message.identity = null;
                    message.uid = null;
                    message.raw = null;
                    message.stored = new Date().getTime();
                    message.notifying = 0;
                    message.fts = false;
                    message.auto_classified = auto_classified;
                    if (reset_importance)
                        message.importance = null;
                    if (autoread) {
                        message.seen = true;
                        message.ui_seen = true;
                    }
                    if (autounflag) {
                        message.flagged = false;
                        message.ui_flagged = false;
                        message.color = null;
                    }
                    message.ui_hide = false;
                    message.ui_found = false;
                    message.ui_browsed = false;
                    message.ui_busy = null;
                    message.error = null;
                    message.id = db.message().insertMessage(message);

                    File mtarget = message.getFile(context);
                    long tmpid = message.id;
                    jargs.put(2, tmpid);

                    message.id = _id;
                    message.account = source.account;
                    message.folder = source.id;
                    message.identity = _identity;
                    message.uid = _uid;
                    message.raw = _raw;
                    message.stored = _stored;
                    message.notifying = _notifying;
                    message.fts = _fts;
                    message.auto_classified = _auto_classified;
                    message.importance = _importance;
                    message.seen = _seen;
                    message.flagged = _flagged;
                    message.ui_seen = _ui_seen;
                    message.ui_flagged = _ui_flagged;
                    message.ui_hide = _ui_hide;
                    message.ui_found = _ui_found;
                    message.ui_browsed = _ui_browsed;
                    message.ui_busy = ui_busy;
                    message.color = _color;
                    message.error = _error;

                    if (message.content)
                        try {
                            Helper.copy(msource, mtarget);
                        } catch (IOException ex) {
                            Log.e(ex);
                            db.message().resetMessageContent(tmpid);
                        }

                    EntityAttachment.copy(context, message.id, tmpid);

                    if (message.ui_snoozed != null)
                        EntityMessage.snooze(context, tmpid, message.ui_snoozed);
                }

                // Cross account move
                if (account != null && account.isGraphApi() && message.graph_id != null) {
                    String trace = ApiMutationLog.safeTraceId(ApiMutationLog.currentTraceId());
                    if (trace != null) {
                        // Graph reads only the stable MOVE fields 0-4; keep correlation beyond them.
                        if (jargs.length() < 5)
                            jargs.put(4, false);
                        jargs.put(Math.max(5, jargs.length()), trace);
                    }
                }
                if (source.account.equals(target.account))
                    queue(context, message.account, source.id, message.id, name, jargs,
                            wakeGmailApi);
                else {
                    if (message.raw != null && message.raw)
                        queue(context, target.account, target.id, message.id, ADD, jargs);
                    else
                        queue(context, source.account, source.id, message.id, RAW, jargs);
                }

                return;
            } else if (COPY.equals(name)) {
                // Parameters in:
                // 0: target folder
                // 1: mark seen

                EntityFolder source = db.folder().getFolder(message.folder);
                EntityFolder target = db.folder().getFolder(jargs.getLong(0));
                if (source == null || target == null)
                    return;

                // Cross account copy
                if (!source.account.equals(target.account)) {
                    jargs.put(2, true); // copy
                    if (message.raw != null && message.raw)
                        queue(context, target.account, target.id, message.id, ADD, jargs);
                    else
                        queue(context, source.account, source.id, message.id, RAW, jargs);
                    return;
                }

            } else if (DELETE.equals(name)) {
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                boolean perform_expunge = prefs.getBoolean("perform_expunge", true);

                if (account != null && account.isGmailApi()) {
                    EntityFolder source = db.folder().getFolder(message.folder);
                    if (!GmailMailMutator.canDeleteFromGmail(account, source, message)) {
                        Log.i("Gmail API refused ordinary delete for a non-message or unsupported draft message=" +
                                message.id);
                        return;
                    }
                    if (GmailMailMutator.shouldRouteDeleteToTrash(account, source, message)) {
                        EntityFolder trash = db.folder().getFolderByType(message.account, EntityFolder.TRASH);
                        if (trash != null && GmailMailMutator.isValidTarget(account, trash))
                            queueWithWakePolicy(context, message, MOVE, wakeGmailApi,
                                    trash.id, null, null,
                                    true, null, source.gmail_label_id);
                        else
                            Log.i("Gmail API refused ordinary delete without a mapped Trash folder message=" +
                                    message.id);
                        return;
                    }
                    // Gmail REST visibility follows confirmed server labels.
                    // Keep the row visible until the response is reconciled.
                } else if (perform_expunge ||
                        account == null ||
                        account.protocol != EntityAccount.TYPE_IMAP ||
                        account.isZohoApi()) {
                    message.ui_hide = true;
                    db.message().setMessageUiHide(message.id, message.ui_hide);

                    if (perform_expunge &&
                            account != null &&
                            account.protocol == EntityAccount.TYPE_IMAP &&
                            account.isGmail()) {
                        EntityFolder source = db.folder().getFolder(message.folder);
                        if (source != null && EntityFolder.ARCHIVE.equals(source.type)) {
                            EntityFolder trash = db.folder().getFolderByType(message.account, EntityFolder.TRASH);
                            if (trash != null && !trash.id.equals(message.folder)) {
                                jargs.put(0, trash.id); // target
                                jargs.put(4, true); // delete
                                queue(context, message.account, message.folder, message.id, EntityOperation.MOVE, jargs);
                                return;
                            }
                        }
                    }
                } else {
                    message.ui_deleted = !message.ui_deleted;
                    db.message().setMessageUiDeleted(message.id, message.ui_deleted);
                    if (message.ui_deleted) {
                        message.ui_ignored = true;
                        db.message().setMessageUiIgnored(message.id, message.ui_ignored);
                    }
                }
/*
                if (message.hash != null) {
                    List<EntityMessage> sames = db.message().getMessagesByHash(message.account, message.hash);
                    for (EntityMessage same : sames) {
                        db.message().setMessageUiHide(same.id, true);
                        queue(context, same.account, same.folder, same.id, name, jargs);
                    }
                }
*/
            } else if (ATTACHMENT.equals(name))
                db.attachment().setProgress(jargs.getLong(0), 0);

            else if (SUBJECT.equals(name))
                db.message().setMessageUiHide(message.id, true);

            else if (DETACH.equals(name))
                db.message().setMessageUiHide(message.id, true);

            queue(context, message.account, message.folder, message.id, name, jargs,
                    wakeGmailApi);

        } catch (JSONException ex) {
            Log.e(ex);
        } catch (SQLiteConstraintException ex) {
            Log.w(ex);
            // folder or message gone
        }
    }

    static void queue(Context context, EntityFolder folder, String name, Object... values) {
        if (folder == null)
            return;
        DB db = DB.getInstance(context);
        EntityAccount account = (folder.account == null ? null : db.account().getAccount(folder.account));

        if (account != null && account.isNativeRestApi()) {
            EntityFolder current = db.folder().getFolder(folder.id);
            if (current == null || !Objects.equals(current.account, folder.account) ||
                    !Objects.equals(current.type, folder.type)) {
                Log.i("Refused stale folder action=" + name + " folder=" + folder.id);
                return;
            }
            folder = current;
            account = db.account().getAccount(folder.account);

            if (EXPUNGE.equals(name) && !FolderActionCapabilities.canExpunge(account, folder)) {
                Log.i("Refused unsupported folder expunge account=" + folder.account + " folder=" + folder.id);
                return;
            }
            if (PURGE.equals(name) && !FolderActionCapabilities.canEmptyFolder(account, folder)) {
                Log.i("Refused unsupported folder purge account=" + folder.account + " folder=" + folder.id);
                return;
            }
            if (account.isGraphApi() && (EXPUNGE.equals(name) || PURGE.equals(name))) {
                queueGraphTrashPurge(context, folder);
                return;
            }
        }

        if (account != null && account.isGmailApi()) {
            String mapped = GmailMailMutator.mapFolderOperation(true, folder.selectable,
                    folder.type, folder.gmail_label_id, name);
            if (mapped == null) {
                Log.i("Gmail API refused purge/expunge outside the Gmail Trash label folder=" + folder.id);
                return;
            }
            name = mapped;
            if (EXPUNGE.equals(name) && db.operation().getOperationCount(folder.id, EXPUNGE) > 0)
                return;
        }
        if (account != null && account.isZohoApi()) {
            String mapped = ZohoOperationWakeup.mapFolderOperation(true, folder.type, name);
            if (mapped == null) {
                Log.i("Zoho API whole-folder operation refused outside Trash name=" + name +
                        " folder=" + folder.id);
                return;
            }
            if (ZOHO_PURGE_TRASH.equals(mapped) || ZOHO_PURGE_SPAM.equals(mapped)) {
                if (!ZohoMailMutator.isEmptyFolderOperation(mapped, folder.type) ||
                        !ZohoMailMutator.canEmptyFolder(account, folder))
                    throw new IllegalArgumentException("Only the mapped Zoho Trash/Bin or Spam/Junk folder can be emptied");
                if (db.operation().getOperationCount(folder.id, mapped) > 0)
                    return;
                name = mapped;
            }
        }
        JSONArray jargs = new JSONArray();
        for (Object value : values)
            jargs.put(value);

        queue(context, folder.account, folder.id, null, name, jargs);
    }

    static void queueGraphTrashPurge(Context context, EntityFolder folder) {
        if (folder == null)
            throw new IllegalArgumentException("A mapped Microsoft Graph Deleted Items folder is required");
        DB db = DB.getInstance(context);
        EntityFolder current = db.folder().getFolder(folder.id);
        if (current == null || !Objects.equals(current.account, folder.account) ||
                !Objects.equals(current.type, folder.type) || current.account == null ||
                !EntityFolder.TRASH.equals(current.type) || current.graph_folder_id == null ||
                current.graph_folder_id.length() == 0)
            throw new IllegalArgumentException("A mapped Microsoft Graph Deleted Items folder is required");
        EntityAccount account = db.account().getAccount(current.account);
        if (account == null || !account.isGraphApi() ||
                !FolderActionCapabilities.canEmptyFolder(account, current))
            throw new IllegalArgumentException("A writable mapped Microsoft Graph Deleted Items folder is required");
        if (db.operation().getOperationCount(current.id, GRAPH_PURGE_TRASH) == 0)
            queue(context, current, GRAPH_PURGE_TRASH);
    }

    static boolean queueGmailApiMutation(Context context, EntityMessage message,
                                         String name, Object... values) {
        return (queueGmailApiMutationResult(context, message, name, true, null, values) ==
                GmailMutationLog.QueueResult.QUEUED);
    }

    static boolean queueGmailApiMutationDeferred(Context context, EntityMessage message,
                                                 String name, Object... values) {
        return (queueGmailApiMutationResult(context, message, name, false, null, values) ==
                GmailMutationLog.QueueResult.QUEUED);
    }

    static GmailMutationLog.QueueResult queueGmailApiMutationDeferredTraced(
            Context context, EntityMessage message, String name, String attemptToken,
            Object... values) {
        return queueGmailApiMutationResult(context, message, name, false, attemptToken, values);
    }

    private static GmailMutationLog.QueueResult queueGmailApiMutationResult(
            Context context, EntityMessage message, String name, boolean wakeGmailApi,
            String attemptToken, Object... values) {
        if (message == null || !isGmailApiMutation(name))
            return GmailMutationLog.QueueResult.NOT_WRITABLE;
        DB db = DB.getInstance(context);
        EntityAccount account = db.account().getAccount(message.account);
        EntityFolder folder = db.folder().getFolder(message.folder);
        if (!GmailMailMutator.isWritableMessage(account, folder, message))
            return GmailMutationLog.QueueResult.NOT_WRITABLE;
        int before = db.operation().getOperationCount(message.folder, message.id);
        String safeAttempt = GmailMutationLog.safeAttemptToken(attemptToken);
        Object[] queueValues = (safeAttempt == null ? values :
                addGmailAttemptToken(values, safeAttempt));
        queueWithWakePolicy(context, message, name, wakeGmailApi, queueValues);
        return (db.operation().getOperationCount(message.folder, message.id) > before
                ? GmailMutationLog.QueueResult.QUEUED
                : GmailMutationLog.QueueResult.NOT_INSERTED);
    }

    static Object[] addGmailAttemptToken(Object[] values, String attemptToken) {
        int count = (values == null ? 0 : values.length);
        if (count > GmailMutationLog.ATTEMPT_TOKEN_INDEX)
            return values;
        Object[] result = new Object[GmailMutationLog.ATTEMPT_TOKEN_INDEX + 1];
        if (count > 0)
            System.arraycopy(values, 0, result, 0, count);
        result[GmailMutationLog.ATTEMPT_TOKEN_INDEX] = attemptToken;
        return result;
    }

    private static void queue(Context context, Long account, long folder, Long message, String name, JSONArray jargs) {
        queue(context, account, folder, message, name, jargs, true);
    }

    private static void queue(Context context, Long account, long folder, Long message,
                              String name, JSONArray jargs, boolean wakeGmailApi) {
        DB db = DB.getInstance(context);

        if (FETCH.equals(name)) {
            int count = db.operation().getOperationCount(folder, name);
            if (count >= MAX_FETCH) {
                Log.i("Replacing fetch by sync folder=" + folder + " args=" + jargs + " count=" + count);
                sync(context, folder, false, false);
                return;
            }
        }

        // Check for offline POP3 operations
        if (inlinePOP3(context, account, folder, message, name, jargs))
            return;

        EntityOperation op = new EntityOperation();
        op.account = account;
        op.folder = folder;
        op.message = message;
        op.name = name;
        op.args = jargs.toString();
        op.created = new Date().getTime();
        EntityAccount queueOwner = (account == null ? null : db.account().getAccount(account));
        String apiProvider = ApiMutationLog.provider(queueOwner);
        try {
            op.id = db.operation().insertOperation(op);
        } catch (RuntimeException ex) {
            if (apiProvider != null && isApiMutationQueue(apiProvider, name)) {
                if ("gmail".equals(apiProvider)) {
                    String attemptToken = GmailMutationLog.attemptToken(jargs);
                    GmailMutationLog.event("queue_insert", name, "not_inserted", attemptToken);
                    GmailMutationLog.event("failure_summary", name, "failure", attemptToken);
                } else
                    ApiMutationLog.failure(apiProvider, "queue_insert", name, "failure", ex);
            }
            throw ex;
        }

        if (apiProvider != null && isApiMutationQueue(apiProvider, name)) {
            if ("gmail".equals(apiProvider))
                GmailMutationLog.event("queue_insert", name, "stored", GmailMutationLog.attemptToken(jargs));
            else
                ApiMutationLog.event(apiProvider, "queue_insert", name, "stored");
        }

        Log.i("Queued op=" + op.id + "/" + op.name +
                " folder=" + op.folder + " msg=" + op.message +
                " args=" + op.args);

        Map<String, String> crumb = new HashMap<>();
        crumb.put("name", op.name);
        crumb.put("args", op.args);
        crumb.put("folder", op.account + ":" + op.folder);
        if (op.message != null)
            crumb.put("message", Long.toString(op.message));
        Log.breadcrumb("queued", crumb);

        if (account != null && GraphOperationWakeup.isGraphWriteOperation(name) &&
                queueOwner != null && queueOwner.isGraphApi()) {
            ServiceSynchronize.eval(context, account, "Graph write queued");
            ApiMutationLog.event("graph", "refresh_summary", "refresh", "scheduled");
        }
        if (account != null && ZohoOperationWakeup.isZohoWriteOperation(name) &&
                queueOwner != null && queueOwner.isZohoApi()) {
            ServiceSynchronize.eval(context, account, "Zoho Mail REST write queued");
            ApiMutationLog.event("zoho", "refresh_summary", "refresh", "scheduled");
        }
        if (wakeGmailApi && account != null && isGmailApiWriteOperation(name) &&
                queueOwner != null && queueOwner.isGmailApi()) {
            ServiceSynchronize.eval(context, account, "Gmail REST write queued");
            GmailMutationLog.event("refresh_summary", "refresh", "scheduled", GmailMutationLog.attemptToken(jargs));
        }
    }

    private static boolean isApiMutationQueue(String provider, String name) {
        if ("gmail".equals(provider))
            return isGmailApiWriteOperation(name);
        if ("graph".equals(provider))
            return GraphOperationWakeup.isGraphWriteOperation(name);
        if ("zoho".equals(provider))
            return ZohoOperationWakeup.isZohoWriteOperation(name);
        return false;
    }

    private static boolean inlinePOP3(Context context, Long account, long folder, Long message, String name, JSONArray jargs) {
        if (account == null || message == null)
            return false;

        DB db = DB.getInstance(context);
        EntityAccount a = db.account().getAccount(account);
        if (a == null || a.protocol != EntityAccount.TYPE_POP)
            return false;

        // TODO: special case for PURGE

        if (SEEN.equals(name) ||
                FLAG.equals(name) ||
                ANSWERED.equals(name) ||
                KEYWORD.equals(name) ||
                ADD.equals(name) ||
                REPORT.equals(name)) {
            Log.i("POP3: skipping op=" + name);
            return true;
        }

        if (MOVE.equals(name)) {
            try {
                long target = jargs.getLong(0);
                boolean seen = jargs.optBoolean(1);
                boolean unflag = jargs.optBoolean(3);

                EntityFolder f = db.folder().getFolder(folder);
                EntityFolder t = db.folder().getFolder(target);
                if (f == null || t == null || f.id.equals(t.id)) {
                    Log.e("POP3: invalid MOVE/folders");
                    return true;
                }

                if (a.leave_deleted &&
                        EntityFolder.INBOX.equals(f.type) &&
                        EntityFolder.TRASH.equals(t.type)) {
                    Log.i("POP3 convert MOVE into DELETE");
                    name = DELETE;
                } else {
                    EntityMessage m = db.message().getMessage(message);
                    if (m == null) {
                        Log.e("POP3: invalid MOVE/message");
                        return true;
                    }

                    Log.i("POP3: local MOVE " + f.type + " > " + t.type);

                    m.folder = t.id;
                    if (seen)
                        m.ui_seen = seen;
                    if (unflag)
                        m.ui_flagged = false;
                    m.ui_hide = false;

                    db.message().updateMessage(m);
                    return true;
                }
            } catch (JSONException ex) {
                Log.e(ex);
                return true;
            }
        }

        if (DELETE.equals(name)) {
            EntityFolder f = db.folder().getFolder(folder);
            EntityMessage m = db.message().getMessage(message);
            if (f == null || m == null) {
                Log.e("POP3: invalid DELETE");
                return true;
            }

            if (!EntityFolder.DRAFTS.equals(f.type) &&
                    !EntityFolder.TRASH.equals(f.type)) {

                Log.i("POP3: local TRASH " + f.type);

                EntityFolder trash = db.folder().getFolderByType(m.account, EntityFolder.TRASH);
                if (trash == null) {
                    trash = new EntityFolder();
                    trash.account = m.id;
                    trash.name = context.getString(R.string.title_folder_trash);
                    trash.type = EntityFolder.TRASH;
                    trash.synchronize = false;
                    trash.unified = false;
                    trash.notify = false;
                    trash.sync_days = Integer.MAX_VALUE;
                    trash.keep_days = Integer.MAX_VALUE;
                    trash.initialize = 0;
                    trash.id = db.folder().insertFolder(trash);
                }

                long id = m.id;

                m.id = null;
                m.folder = trash.id;
                m.msgid = null; // virtual message
                m.ui_hide = false;
                m.ui_seen = true;
                m.id = db.message().insertMessage(m);

                try {
                    File source = EntityMessage.getFile(context, id);
                    File target = m.getFile(context);
                    Helper.copy(source, target);
                } catch (IOException ex) {
                    Log.e(ex);
                }

                EntityAttachment.copy(context, id, m.id);

                m.id = id;
            }

            // Delete from device
            if (EntityFolder.INBOX.equals(f.type)) {
                if (a.leave_deleted) {
                    // Remove message/attachments files on cleanup
                    Log.i("POP3: DELETE reset content");
                    db.message().resetMessageContent(m.id);
                    db.attachment().resetAvailable(m.id);
                }

                // Synchronize will delete messages when needed
                Log.i("POP3: DELETE hide " + f.type);
                db.message().setMessageUiHide(m.id, true);
            } else {
                Log.i("POP3: local DELETE " + f.type);
                db.message().deleteMessage(m.id);
            }

            if (EntityFolder.INBOX.equals(f.type) && !a.leave_deleted) {
                Log.i("POP3: DELETE remote " + f.type);
                return false;
            } else {
                Log.i("POP3: local only " + f.type);
                return true;
            }
        }

        return false;
    }

    static void poll(Context context, long fid) throws JSONException {
        DB db = DB.getInstance(context);

        EntityFolder folder = db.folder().getFolder(fid);
        EntityAccount account = (folder == null || folder.account == null
                ? null : db.account().getAccount(folder.account));
        boolean gmailApi = (account != null && account.isGmailApi());
        boolean force = false;
        boolean olderPage = false;
        List<EntityOperation> ops = db.operation().getOperationsByFolder(fid, SYNC);
        if (ops != null)
            for (EntityOperation op : ops) {
                if (EntityFolder.isSyncForced(op.args)) {
                    force = true;
                }
                if (gmailApi && NativeApiSyncRequest.isGmailOlderPageRequest(true, op.args))
                    olderPage = true;
            }

        int count = db.operation().deleteOperations(fid, SYNC);

        Map<String, String> crumb = new HashMap<>();
        crumb.put("folder", Long.toString(fid));
        crumb.put("stale", Integer.toString(count));
        crumb.put("force", Boolean.toString(force));
        Log.breadcrumb("Poll", crumb);

        sync(context, fid, false, force, false, olderPage);
    }

    static boolean sync(Context context, long fid, boolean foreground) {
        return sync(context, fid, foreground, false);
    }

    static boolean sync(Context context, long fid, boolean foreground, boolean force) {
        return sync(context, fid, foreground, force, false);
    }

    static boolean sync(Context context, long fid, boolean foreground, boolean force, boolean outbox) {
        return sync(context, fid, foreground, force, outbox, false);
    }

    static boolean syncMore(Context context, long fid, boolean foreground) {
        return sync(context, fid, foreground, false, false, true);
    }

    static JSONArray createSyncArgs(EntityFolder folder, EntityAccount account,
                                    boolean force, boolean olderPage) {
        JSONArray args = folder.getSyncArgs(force);
        if (olderPage && account != null && account.isNativeRestApi())
            args.put(true);
        return args;
    }

    private static boolean sync(Context context, long fid, boolean foreground, boolean force,
                                boolean outbox, boolean olderPage) {
        DB db = DB.getInstance(context);

        EntityFolder folder = db.folder().getFolder(fid);
        if (folder == null)
            return force;

        EntityAccount account = (folder.account == null ? null : db.account().getAccount(folder.account));
        if (olderPage && account != null && account.isNativeRestApi() &&
                !NativeApiSyncRequest.hasOlderMessages(folder, account))
            return false;

        if (foreground) {
            long now = new Date().getTime();
            if (folder.last_sync_foreground != null &&
                    now - folder.last_sync_foreground < FORCE_WITHIN) {
                Log.i(folder.name + " Auto force");
                force = true;
            }
            db.folder().setFolderLastSyncForeground(folder.id, now);
        }

        boolean queuedGmailOlderPage = false;
        if (force && account != null && account.isGmailApi()) {
            List<EntityOperation> queued = db.operation().getOperationsByFolder(fid, SYNC);
            if (queued != null)
                for (EntityOperation operation : queued)
                    if (NativeApiSyncRequest.isGmailOlderPageRequest(true, operation.args)) {
                        queuedGmailOlderPage = true;
                        break;
                    }
        }

        if (force)
            db.operation().deleteOperations(fid, SYNC);

        // TODO: replace sync parameters?
        int syncCount = db.operation().getOperationCount(fid, SYNC);
        if (syncCount == 0) {
            EntityOperation operation = new EntityOperation();
            operation.account = folder.account;
            operation.folder = folder.id;
            operation.message = null;
            operation.name = SYNC;
            JSONArray args = createSyncArgs(folder, account, force,
                    olderPage || queuedGmailOlderPage);
            operation.args = args.toString();
            operation.created = new Date().getTime();
            operation.id = db.operation().insertOperation(operation);

            Log.i("Queued sync folder=" + folder + " force=" + force);
        } else if (olderPage && account != null && account.isGmailApi()) {
            // Gmail Fetch more shares the sync queue with ordinary refreshes.
            // Preserve existing parameters but carry the explicit older-page request.
            List<EntityOperation> operations = db.operation().getOperationsByFolder(fid, SYNC);
            if (operations != null)
                for (EntityOperation operation : operations)
                    db.operation().setOperationArgs(operation.id,
                            NativeApiSyncRequest.markOlderPageRequest(operation.args));
        }

        if (foreground && folder.sync_state == null) // Show spinner
            db.folder().setFolderSyncState(fid, "requested");

        if (force && foreground && EntityFolder.DRAFTS.equals(folder.type)) {
            if (usesLegacyImapSyncSideEffects(account)) {
                List<EntityMessage> orphans = db.message().getDraftOrphans(folder.id);
                if (orphans != null) {
                    EntityLog.log(context, "Draft orphans=" + orphans.size());
                    SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                    boolean save_drafts = prefs.getBoolean("save_drafts", true);
                    if (save_drafts)
                        for (EntityMessage orphan : orphans)
                            EntityOperation.queue(context, orphan, EntityOperation.ADD);
                }
            }
        }

        if (foreground && EntityFolder.SENT.equals(folder.type)) {
            if (usesLegacyImapSyncSideEffects(account)) {
                List<EntityMessage> orphans = db.message().getSentOrphans(folder.id);
                if (orphans != null) {
                    EntityLog.log(context, "Sent orphans=" + orphans.size());
                    for (EntityMessage orphan : orphans)
                        EntityOperation.queue(context, orphan, EntityOperation.EXISTS);
                }
            }
        }

        if (folder.account == null) // Outbox
            if (!outbox) {
                Log.e("outbox");
                ServiceSend.start(context);
            }

        return force;
    }

    static boolean canQueueSubscribe(EntityAccount account) {
        return (account != null && !account.isNativeRestApi());
    }

    static void subscribe(Context context, long fid, boolean subscribe) {
        DB db = DB.getInstance(context);

        EntityFolder folder = db.folder().getFolder(fid);
        EntityAccount account = (folder == null || folder.account == null ? null :
                db.account().getAccount(folder.account));
        if (!canQueueSubscribe(account)) {
            Log.i("Refused legacy folder subscribe for native REST/missing account folder=" + fid);
            return;
        }

        JSONArray jargs = new JSONArray();
        jargs.put(subscribe);

        EntityOperation operation = new EntityOperation();
        operation.account = folder.account;
        operation.folder = folder.id;
        operation.message = null;
        operation.name = SUBSCRIBE;
        operation.args = jargs.toString();
        operation.created = new Date().getTime();
        operation.id = db.operation().insertOperation(operation);

        Log.i("Queued subscribe=" + subscribe + " folder=" + folder);
    }

    void cleanup(Context context, boolean fetch) {
        DB db = DB.getInstance(context);

        EntityLog.log(context, "Cleanup op=" + id + "/" + name +
                " folder=" + folder + " message=" + message + " fetch=" + fetch);

        if (message != null) {
            if (MOVE.equals(name) || DELETE.equals(name))
                db.message().setMessageUiHide(message, false);

            if (MOVE.equals(name))
                try {
                    JSONArray jargs = new JSONArray(args);
                    long target = jargs.getLong(0);
                    db.operation().deleteOperations(target, PURGE);
                } catch (Throwable ex) {
                    Log.e(ex);
                }

            if (SEEN.equals(name)) {
                EntityMessage m = db.message().getMessage(message);
                if (m != null) {
                    boolean seen = m.seen;
                    try {
                        JSONArray jargs = new JSONArray(args);
                        seen = jargs.getBoolean(0);
                    } catch (Throwable ex) {
                        Log.e(ex);
                    }
                    db.message().setMessageUiSeen(m.id, seen);
                }
            }

            if (FLAG.equals(name)) {
                EntityMessage m = db.message().getMessage(message);
                if (m != null) {
                    boolean flagged = m.flagged;
                    Integer color = m.color;
                    try {
                        JSONArray jargs = new JSONArray(args);
                        flagged = jargs.getBoolean(0);
                        color = (jargs.length() > 1 && !jargs.isNull(1) ? jargs.getInt(1) : null);
                    } catch (Throwable ex) {
                        Log.e(ex);
                    }
                    db.message().setMessageUiFlagged(m.id, flagged, color);
                }
            }
        }

        if (MOVE.equals(name)) {
            int count = db.operation().deleteOperations(folder, PURGE);
            if (count > 0)
                sync(context, folder, false);
        }

        if (MOVE.equals(name) ||
                ADD.equals(name) ||
                RAW.equals(name))
            try {
                JSONArray jargs = new JSONArray(args);
                long tmpid = jargs.optLong(2, -1);
                if (tmpid < 0)
                    return;

                db.message().deleteMessage(tmpid);
            } catch (JSONException ex) {
                Log.e(ex);
            }

        if (EXISTS.equals(name)) {
            EntityMessage m = db.message().getMessage(message);
            if (m != null)
                queue(context, m, ADD);
        }

        if (ATTACHMENT.equals(name))
            try {
                JSONArray jargs = new JSONArray(args);
                long id = jargs.getLong(0);
                db.attachment().setProgress(id, null);
                db.attachment().setError(id, error);
                return;
            } catch (JSONException ex) {
                Log.e(ex);
            }

        if (SUBJECT.equals(name) && message != null)
            db.message().setMessageUiHide(message, false);

        if (DETACH.equals(name) && message != null)
            db.message().setMessageUiHide(message, false);

        if (SYNC.equals(name))
            db.folder().setFolderSyncState(folder, null);

        if (fetch && message != null &&
                !SEEN.equals(name) &&
                !FLAG.equals(name)) {
            EntityMessage m = db.message().getMessage(message);
            if (m == null || m.uid == null)
                return;

            EntityFolder f = db.folder().getFolder(folder);
            if (f == null)
                return;

            if (FETCH.equals(name))
                sync(context, f.id, false);
            else
                queue(context, f, FETCH, m.uid);
        }
    }

    @Override
    public boolean equals(Object obj) {
        if (obj instanceof EntityOperation) {
            EntityOperation other = (EntityOperation) obj;
            return (this.folder.equals(other.folder) &&
                    Objects.equals(this.message, other.message) &&
                    this.name.equals(other.name) &&
                    this.args.equals(other.args) &&
                    this.created.equals(other.created) &&
                    Objects.equals(this.state, other.state) &&
                    Objects.equals(this.error, other.error));
        } else
            return false;
    }

    @Override
    public String toString() {
        return Long.toString(id);
    }
}

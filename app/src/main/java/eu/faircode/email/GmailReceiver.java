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

import android.content.Context;
import android.content.SharedPreferences;
import android.os.OperationCanceledException;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import org.json.JSONException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.internet.MimeMessage;

/**
 * Imports Gmail REST messages without converting Gmail labels into exclusive mailboxes.
 * Each Gmail message remains one local row; folder membership comes from its labelIds.
 */
final class GmailReceiver {
    // Initial and explicit folder fetches download ten messages; each worker pass is bounded.
    static final int PAGE_SIZE = GmailRateLimitBackoff.MAX_MESSAGES_PER_PASS;
    static final String PAGE_TOKEN_EXHAUSTED = "!gmail-api-page-exhausted!";
    private static final String INITIAL_FOLDER_DEFAULTS_PENDING = "gmail_api_initial_folder_defaults_pending.";
    private static final String ALL_MAIL_DEFAULTS_INITIALIZED = "gmail_api_all_mail_defaults_initialized.";

    private GmailReceiver() {
    }

    static void markNewAccountFolderDefaultsPending(Context context, long accountId) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(INITIAL_FOLDER_DEFAULTS_PENDING + accountId, true).apply();
    }

    private static boolean isNewAccountFolderDefaultsPending(Context context, long accountId) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean(INITIAL_FOLDER_DEFAULTS_PENDING + accountId, false);
    }

    static SyncResult syncInbox(Context context, DB db, EntityAccount account,
                                GmailApi api, Core.State state)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        return syncInbox(context, db, account, api, state, null);
    }

    static SyncResult syncInbox(Context context, DB db, EntityAccount account,
                                GmailApi api, Core.State state, List<GmailApi.Label> cachedLabels)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        return syncInbox(context, db, account, api, state, cachedLabels, false);
    }

    static SyncResult syncInbox(Context context, DB db, EntityAccount account,
                                GmailApi api, Core.State state, List<GmailApi.Label> cachedLabels,
                                boolean folderSyncRequested)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        state.ensureRunning("Gmail REST label synchronization");
        List<GmailApi.Label> labels = (cachedLabels == null ? api.listLabels() : cachedLabels);
        state.ensureRunning("Gmail REST label synchronization completion");
        Map<String, EntityFolder> folders = syncLabelFolders(context, db, account, labels);
        EntityFolder inbox = folders.get("INBOX");
        if (inbox == null)
            throw new IOException("Gmail account has no local Inbox label folder");

        EntityAccount persisted = db.account().getAccount(account.id);
        GmailSyncState cursor = GmailSyncState.from(persisted);
        if (cursor.needsProfile()) {
            state.ensureRunning("Gmail REST full-sync cursor initialization");
            GmailApi.Profile profile = api.getProfile();
            state.ensureRunning("Gmail REST full-sync cursor completion");
            cursor = cursor.beginFullSync(profile.historyId);
            saveCursor(db, account.id, cursor);
        }

        if (cursor.getStage() == GmailSyncState.Stage.FULL)
            return syncFullPage(context, db, account, api, state, cursor, folders, inbox, labels);

        if (shouldDeferHistoryForFolderSync(cursor, folderSyncRequested))
            // Keep the cursor and any pending response untouched; the next pass resumes history.
            return new SyncResult(0, true, "folder-priority", labels);

        try {
            return syncHistoryPage(context, db, account, api, state, cursor, folders, inbox, labels);
        } catch (GmailApi.HistoryExpiredException ex) {
            state.ensureRunning("Gmail REST expired-history recovery");
            EntityLog.log(context, EntityLog.Type.Account, account,
                    "Gmail history cursor expired; starting a bounded full resync");
            cursor = GmailSyncState.expiredHistory();
            saveCursor(db, account.id, cursor);
            state.ensureRunning("Gmail REST history resync initialization");
            GmailApi.Profile profile = api.getProfile();
            state.ensureRunning("Gmail REST history resync completion");
            cursor = cursor.beginFullSync(profile.historyId);
            saveCursor(db, account.id, cursor);
            return syncFullPage(context, db, account, api, state, cursor, folders, inbox, labels);
        }
    }

    private static SyncResult syncFullPage(Context context, DB db, EntityAccount account,
                                           GmailApi api, Core.State state, GmailSyncState cursor,
                                           Map<String, EntityFolder> folders, EntityFolder inbox,
                                           List<GmailApi.Label> labels)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        if (!shouldFetchInitialInboxWindow(inbox, folders)) {
            cursor = cursor.afterFullPage(null);
            saveCursor(db, account.id, cursor);
            return new SyncResult(0, cursor.hasMoreWork(), "full-skipped-disabled-inbox", labels);
        }
        state.ensureRunning("Gmail REST full-sync page");
        if (!TextUtils.isEmpty(cursor.fullSyncPageToken)) {
            // Version 304 could persist a token for an all-mail, 100-message scan.
            // Keep the original baseline and restart at the new Inbox-only window.
            cursor = cursor.restartFullPage();
            saveCursor(db, account.id, cursor);
        }
        GmailApi.MessagePage page = api.listMessagesForFolder("INBOX", null,
                null, PAGE_SIZE, includeSpamTrashForLabel("INBOX"));
        state.ensureRunning("Gmail REST full-sync page completion");

        int imported = 0;
        for (GmailApi.MessageReference reference : page.messages) {
            state.ensureRunning("Gmail REST message receive");
            EntityMessage cached = db.message().getMessageByGmailId(account.id, reference.id);
            if (cached != null)
                // History will refresh changed labels; avoid a redundant messages.get per cached ID.
                continue;

            try {
                PreparedMessage prepared = prepareMessage(context, api, state,
                        reference.id, folders, inbox, null);
                if (prepared == null)
                    continue;
                if (importMessage(context, db, account, prepared.storage, prepared.received,
                        folders, inbox))
                    imported++;
            } catch (GmailApi.MessageUnavailableException ignored) {
                // A message can disappear between list and get; do not strand the page cursor.
            }
        }

        if (inbox.gmail_page_token == null) {
            updateFolderPageToken(inbox, page);
            db.folder().setFolderGmailPageToken(inbox.id, inbox.gmail_page_token);
        }
        // Older Inbox pages are requested by a folder-specific sync, never by a mailbox sweep.
        cursor = cursor.afterFullPage(null);
        saveCursor(db, account.id, cursor);
        return new SyncResult(imported, cursor.hasMoreWork(), "full", labels);
    }

    private static SyncResult syncHistoryPage(Context context, DB db, EntityAccount account,
                                              GmailApi api, Core.State state, GmailSyncState cursor,
                                              Map<String, EntityFolder> folders, EntityFolder inbox,
                                              List<GmailApi.Label> labels)
        throws IOException, JSONException, MessagingException, OperationCanceledException {
        state.ensureRunning("Gmail REST history page");
        GmailApi.HistoryPage page = (cursor.historyPendingJson == null
                ? api.listHistory(cursor.historyId, cursor.historyPageToken, PAGE_SIZE)
                : GmailApi.HistoryPage.fromPendingJson(cursor.historyPendingJson));
        if (cursor.historyPendingJson == null && !page.changes.isEmpty()) {
            // Persist the entire response before message GETs. A quota error or process
            // death therefore retries this same uncommitted history page safely.
            cursor = cursor.withHistoryPending(page.toPendingJson());
            saveCursor(db, account.id, cursor);
        }
        state.ensureRunning("Gmail REST history page completion");
        int imported = 0;
        int processed = 0;
        for (int i = 0; i < page.changes.size() && processed < PAGE_SIZE; i++) {
            GmailApi.MessageChange change = page.changes.get(i);
            state.ensureRunning("Gmail REST history change");
            EntityMessage cached = db.message().getMessageByGmailId(account.id, change.id);
            if (change.deleted) {
                if (cached != null)
                    markDeleted(db, cached);
            } else if (cached != null) {
                try {
                    refreshLabels(db, cached, api.getMessageLabels(change.id), folders, inbox);
                } catch (GmailApi.MessageUnavailableException ex) {
                    markDeleted(db, cached);
                }
            } else {
                try {
                    List<String> labelsForImport = (change.labelsPresent
                            ? change.labels : api.getMessageLabels(change.id));
                    if (shouldAutoImport(labelsForImport, folders)) {
                        PreparedMessage prepared = prepareMessage(context, api, state,
                                change.id, folders, inbox, null);
                        if (prepared != null && shouldAutoImport(prepared.received.labels, folders) &&
                                importMessage(context, db, account, prepared.storage,
                                        prepared.received, folders, inbox))
                            imported++;
                    }
                } catch (GmailApi.MessageUnavailableException ignored) {
                    // Ignore messages deleted after the history record was written.
                }
            }

            processed++;
            if (i + 1 < page.changes.size()) {
                GmailApi.HistoryPage remaining = new GmailApi.HistoryPage(
                        page.changes.subList(i + 1, page.changes.size()),
                        page.nextPageToken, page.historyId);
                cursor = cursor.withHistoryPending(remaining.toPendingJson());
                saveCursor(db, account.id, cursor);
            }
        }

        if (processed < page.changes.size())
            return new SyncResult(imported, true, "history", labels);

        cursor = cursor.afterHistoryPage(page.nextPageToken, page.historyId);
        saveCursor(db, account.id, cursor);
        return new SyncResult(imported, cursor.hasMoreWork(), "history", labels);
    }

    static SyncResult syncFolderPage(Context context, DB db, EntityAccount account,
                                     GmailApi api, Core.State state, EntityFolder requestedFolder,
                                     List<GmailApi.Label> cachedLabels)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        if (requestedFolder == null || requestedFolder.id == null)
            throw new IOException("Gmail folder sync request did not contain a folder");
        List<GmailApi.Label> labels = (cachedLabels == null ? api.listLabels() : cachedLabels);
        Map<String, EntityFolder> folders = syncLabelFolders(context, db, account, labels);
        EntityFolder folder = db.folder().getFolder(requestedFolder.id);
        if (folder == null || TextUtils.isEmpty(folder.gmail_label_id))
            throw new IOException("Gmail folder sync request no longer maps to a Gmail label");
        if (isFolderPageExhausted(folder))
            return new SyncResult(0, false, "folder-exhausted", labels);

        String labelId = folder.gmail_label_id;
        String query = null;
        if (GmailLabelFolder.ALL_MAIL_VIEW_ID.equals(labelId)) {
            labelId = null;
            query = GmailLabelFolder.ALL_MAIL_QUERY;
        }
        boolean includeSpamTrash = includeSpamTrashForLabel(labelId);
        GmailApi.MessagePage page;
        try {
            page = api.listMessagesForFolder(labelId, query, folder.gmail_page_token,
                    PAGE_SIZE, includeSpamTrash);
        } catch (GmailApi.InvalidPageTokenException ex) {
            // A stale opaque page token is recoverable; restart this folder's page from its first result.
            page = api.listMessagesForFolder(labelId, query, null, PAGE_SIZE, includeSpamTrash);
        }
        state.ensureRunning("Gmail REST manual folder page completion");

        EntityFolder inbox = folders.get("INBOX");
        int imported = 0;
        for (GmailApi.MessageReference reference : page.messages) {
            state.ensureRunning("Gmail REST manual folder message");
            EntityMessage cached = db.message().getMessageByGmailId(account.id, reference.id);
            if (cached != null) {
                try {
                    List<String> currentLabels = api.getMessageLabels(reference.id);
                    state.ensureRunning("Gmail folder cached-label reconciliation");
                    refreshLabels(db, cached, currentLabels, folders, inbox);
                    if (needsReceivedDateRepair(cached))
                        repairReceivedDateIfNeeded(api, cached,
                                (id, received) -> db.message().setMessageGmailReceived(id, received));
                } catch (GmailApi.MessageUnavailableException ignored) {
                    // Ignore a message deleted after Gmail returned its list reference.
                }
                continue;
            }
            try {
                PreparedMessage prepared = prepareMessage(context, api, state,
                        reference.id, folders, inbox, folder.gmail_label_id);
                if (prepared != null && importMessage(context, db, account, prepared.storage,
                        prepared.received, folders, inbox))
                    imported++;
            } catch (GmailApi.MessageUnavailableException ignored) {
                // Ignore a message deleted after Gmail returned its list reference.
            }
        }

        updateFolderPageToken(folder, page);
        db.folder().setFolderGmailPageToken(folder.id, folder.gmail_page_token);
        return new SyncResult(imported, false, "folder", labels);
    }

    static boolean shouldInitializeFolder(EntityFolder folder) {
        return (folder != null && folder.gmail_label_id != null && folder.gmail_label_id.length() > 0 &&
                Boolean.TRUE.equals(folder.synchronize) &&
                folder.gmail_page_token == null);
    }

    static boolean canFetchMoreManually(Boolean selectable, String labelId, String pageToken) {
        return (Boolean.TRUE.equals(selectable) && labelId != null && labelId.length() > 0 &&
                !PAGE_TOKEN_EXHAUSTED.equals(pageToken));
    }

    static int countInitialFolderPages(DB db, long accountId) {
        int count = 0;
        for (EntityFolder folder : db.folder().getFolders(accountId, false, false))
            if (shouldInitializeFolder(folder))
                count++;
        return count;
    }

    static SyncResult syncInitialFolderPages(Context context, DB db, EntityAccount account,
                                             GmailApi api, Core.State state,
                                             List<GmailApi.Label> labels, int pageLimit)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        List<EntityFolder> pending = new ArrayList<>();
        for (EntityFolder folder : db.folder().getFolders(account.id, false, false))
            if (shouldInitializeFolder(folder))
                pending.add(folder);

        int imported = 0;
        int pages = 0;
        int limit = Math.max(0, pageLimit);
        for (EntityFolder folder : pending) {
            if (pages >= limit)
                break;
            SyncResult result = syncFolderPage(context, db, account, api, state, folder, labels);
            imported += result.imported;
            pages++;
        }
        return new SyncResult(imported, pending.size() > pages,
                "initial-label-folders", labels, pages);
    }

    static boolean isOlderPageRequest(String args) {
        return NativeApiSyncRequest.isOlderPageRequest(args);
    }

    static boolean isFolderPageExhausted(EntityFolder folder) {
        return (folder != null && PAGE_TOKEN_EXHAUSTED.equals(folder.gmail_page_token));
    }

    static boolean includeSpamTrashForLabel(String labelId) {
        // Keep Inbox continuation requests identical to the initial Inbox list.
        // Spam and Trash need includeSpamTrash=true to be listable by their labels.
        return (!"INBOX".equals(labelId));
    }

    static boolean shouldDeferHistoryForFolderSync(GmailSyncState cursor, boolean folderSyncRequested) {
        return (folderSyncRequested && cursor != null &&
                cursor.getStage() == GmailSyncState.Stage.HISTORY);
    }

    static void updateFolderPageToken(EntityFolder folder, GmailApi.MessagePage page) {
        folder.gmail_page_token = (page.nextPageToken == null
                ? PAGE_TOKEN_EXHAUSTED : page.nextPageToken);
    }

    private static void saveCursor(DB db, long accountId, GmailSyncState cursor) {
        db.account().setGmailSyncState(accountId, cursor.historyId,
                cursor.fullSyncHistoryId, cursor.fullSyncPageToken, cursor.historyPageToken,
                cursor.historyPendingJson);
    }

    static boolean shouldAutoImport(List<String> labels, Map<String, EntityFolder> folders) {
        if (labels != null)
            for (String id : labels) {
                EntityFolder folder = folders.get(id);
                if (folder != null && Boolean.TRUE.equals(folder.synchronize))
                    return true;
            }

        EntityFolder allMail = folders.get(GmailLabelFolder.ALL_MAIL_VIEW_ID);
        return (allMail != null && Boolean.TRUE.equals(allMail.synchronize) &&
                GmailLabelFolder.isMember(labels == null ? null : labels.toArray(new String[0]),
                        GmailLabelFolder.ALL_MAIL_VIEW_ID));
    }

    static boolean shouldCacheGmailInlineBytes(EntityAttachment attachment) {
        return (attachment != null &&
                NativeApiContent.GMAIL_INLINE_PART.equals(attachment.provider_type) &&
                (Part.INLINE.equalsIgnoreCase(attachment.disposition) || attachment.cid != null ||
                        Boolean.TRUE.equals(attachment.related)));
    }


    static boolean shouldFetchInitialInboxWindow(EntityFolder inbox, Map<String, EntityFolder> folders) {
        return (inbox != null && Boolean.TRUE.equals(inbox.synchronize) &&
                shouldAutoImport(Collections.singletonList("INBOX"), folders));
    }

    static boolean isRequestedFolderSync(EntityFolder folder) {
        return (folder != null && "requested".equals(folder.sync_state));
    }

    static Long getReceivedAt(GmailApi.ReceivedMessage received) {
        return (received == null || received.internalDate < 0 ? null : received.internalDate);
    }

    static boolean needsReceivedDateRepair(EntityMessage message) {
        return (message != null && message.gmail_id != null && !message.gmail_id.isEmpty() &&
                !Boolean.TRUE.equals(message.gmail_received_checked));
    }

    interface ReceivedDateUpdater {
        int update(long messageId, long received);
    }

    static boolean repairReceivedDateIfNeeded(GmailApi api, EntityMessage message,
                                              ReceivedDateUpdater updater)
            throws IOException, JSONException {
        if (!needsReceivedDateRepair(message))
            return false;
        GmailApi.MessageMetadata metadata = api.getMessageMetadata(message.gmail_id);
        if (!message.gmail_id.equals(metadata.id))
            throw new IOException("Gmail date-repair response returned an inconsistent message id");
        long received = metadata.internalDate;
        if (received < 0)
            throw new IOException("Gmail date-repair response contained an invalid internalDate");
        if (updater.update(message.id, received) <= 0)
            return false;
        // Update only the local receive-order timestamp and the one-time marker.
        message.received = received;
        message.gmail_received_checked = true;
        return true;
    }

    private static void markDeleted(DB db, EntityMessage message) {
        db.message().setMessageDeleted(message.id, true);
        db.message().setMessageUiDeleted(message.id, false);
    }

    static final class SyncResult {
        final int imported;
        final boolean morePending;
        final String mode;
        final List<GmailApi.Label> labels;
        final int pages;

        SyncResult(int imported, boolean morePending, String mode, List<GmailApi.Label> labels) {
            this(imported, morePending, mode, labels, 0);
        }

        SyncResult(int imported, boolean morePending, String mode, List<GmailApi.Label> labels,
                   int pages) {
            this.imported = imported;
            this.morePending = morePending;
            this.mode = mode;
            this.labels = labels;
            this.pages = pages;
        }
    }

    private static Map<String, EntityFolder> syncLabelFolders(Context context, DB db, EntityAccount account,
                                                               List<GmailApi.Label> labels) {
        Map<String, EntityFolder> folders = new HashMap<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean initializeNewAccountFolders = isNewAccountFolderDefaultsPending(context, account.id);
        for (GmailApi.Label label : labels) {
            String type = GmailLabelFolder.getFolderType(label.id, label.type);
            if (type == null)
                continue;
            String displayName = GmailLabelFolder.getFolderDisplayName(context, label, type);

            EntityFolder folder = db.folder().getFolderByGmailLabelId(account.id, label.id);
            if (folder == null && !EntityFolder.USER.equals(type))
                folder = db.folder().getFolderByType(account.id, type);
            boolean created = (folder == null);
            if (created) {
                String name = (EntityFolder.USER.equals(type)
                        ? GmailLabelFolder.getUserFolderName(label.id)
                        : getSystemFolderName(type));
                folder = new EntityFolder(name, type);
                folder.account = account.id;
                if (!EntityFolder.USER.equals(type))
                    folder.display = displayName;
            }

            folder.account = account.id;
            folder.type = type;
            folder.gmail_label_id = label.id;
            if (EntityFolder.USER.equals(type)) {
                folder.name = GmailLabelFolder.getUserFolderName(label.id);
                folder.display = GmailLabelFolder.getUserFolderDisplayName(
                        folder.display, label.id, folder.name, label.name);
            } else if (GmailLabelFolder.shouldLocalizeSystemDisplayName(
                    folder.display, label.id, label.name))
                // Replace stale raw API names while preserving explicit customization.
                folder.display = displayName;
            folder.read_only = true;
            folder.selectable = true;
            if (created) {
                folder.setProperties();
                NativeApiFolderDefaults.applyCreationDefaults(folder, initializeNewAccountFolders, true);
                folder.read_only = true;
                folder.id = db.folder().insertFolder(folder);
                if (NativeApiFolderDefaults.shouldQueueInitialSync(
                        initializeNewAccountFolders, true, folder))
                    EntityOperation.sync(context, folder.id, true);
            } else {
                // Keep the user's saved synchronize/poll settings on rediscovery.
                db.folder().updateFolder(folder);
            }
            folders.put(label.id, folder);
        }

        // This is Gmail's All Mail view (all messages except Spam and Trash), not an Archive label.
        // Keep the historical database token so existing folder preferences/page cursors survive upgrade.
        EntityFolder allMail = db.folder().getFolderByGmailLabelId(
                account.id, EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID);
        if (allMail == null)
            allMail = db.folder().getFolderByType(account.id, EntityFolder.ARCHIVE);
        boolean newAllMail = (allMail == null);
        boolean initializeAllMail = !prefs.getBoolean(ALL_MAIL_DEFAULTS_INITIALIZED + account.id, false);
        String allMailDisplay = context.getString(R.string.title_folder_gmail_all_mail);
        if (newAllMail) {
            allMail = new EntityFolder("All Mail", EntityFolder.ARCHIVE);
            allMail.account = account.id;
            allMail.display = allMailDisplay;
        } else if (allMail.display == null || allMail.display.equals(allMail.name) ||
                allMail.display.equals(context.getString(R.string.title_folder_all)) ||
                "Archive".equalsIgnoreCase(allMail.display))
            allMail.display = allMailDisplay;
        allMail.account = account.id;
        allMail.type = EntityFolder.ARCHIVE;
        allMail.gmail_label_id = EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID;
        allMail.read_only = true;
        allMail.selectable = true;
        if (newAllMail) {
            allMail.setProperties();
            NativeApiFolderDefaults.applyCreationDefaults(allMail, initializeNewAccountFolders, true);
            GmailLabelFolder.applyAllMailDefaults(allMail, initializeAllMail);
            allMail.read_only = true;
            allMail.display = allMailDisplay;
            allMail.id = db.folder().insertFolder(allMail);
        } else {
            GmailLabelFolder.applyAllMailDefaults(allMail, initializeAllMail);
            // Keep the user's saved synchronize/poll settings on rediscovery.
            db.folder().updateFolder(allMail);
        }
        if (initializeAllMail) {
            // Initial import uses the existing bounded per-folder sync window and page size.
            EntityOperation.sync(context, allMail.id, true);
            prefs.edit().putBoolean(ALL_MAIL_DEFAULTS_INITIALIZED + account.id, true).apply();
        }
        folders.put(GmailLabelFolder.ALL_MAIL_VIEW_ID, allMail);
        if (initializeNewAccountFolders)
            prefs.edit().remove(INITIAL_FOLDER_DEFAULTS_PENDING + account.id).apply();
        return folders;
    }

    private static String getSystemFolderName(String type) {
        if (EntityFolder.INBOX.equals(type))
            return "INBOX";
        if (EntityFolder.DRAFTS.equals(type))
            return EntityFolder.DRAFTS;
        if (EntityFolder.SENT.equals(type))
            return EntityFolder.SENT;
        if (EntityFolder.TRASH.equals(type))
            return EntityFolder.TRASH;
        if (EntityFolder.JUNK.equals(type))
            return EntityFolder.JUNK;
        throw new IllegalArgumentException("Unsupported Gmail system folder type=" + type);
    }

    static EntityFolder getStorageFolder(List<String> labels,
                                        Map<String, EntityFolder> folders,
                                        EntityFolder inbox) {
        // Keep a deterministic primary local location; SQL label membership remains many-to-many.
        String[] preferred = new String[]{"TRASH", "INBOX", "DRAFT", "SENT", "SPAM"};
        for (String id : preferred)
            if (GmailLabelFolder.hasLabel(labels, id)) {
                EntityFolder folder = folders.get(id);
                if (folder != null)
                    return folder;
            }
        if (labels != null)
            for (String label : labels) {
                EntityFolder folder = folders.get(label);
                if (folder != null && EntityFolder.USER.equals(folder.type))
                    return folder;
            }
        EntityFolder allMail = folders.get(EntityFolder.GMAIL_API_ALL_MAIL_VIEW_ID);
        return (allMail == null ? inbox : allMail);
    }

    private static void refreshLabels(DB db, EntityMessage message, List<String> labels,
                                      Map<String, EntityFolder> folders, EntityFolder inbox) {
        GmailMessageLabelState labelState = GmailMessageLabelState.from(labels);
        String[] values = labelState.labels.toArray(new String[0]);
        EntityFolder storage = getStorageFolder(labelState.labels, folders, inbox);
        db.beginTransaction();
        try {
            if (storage != null)
                db.message().setMessageFolder(message.id, storage.id);
            db.message().setMessageLabels(message.id, DB.Converters.fromStringArray(values));
            db.message().setMessageSeen(message.id, labelState.seen);
            db.message().setMessageUiSeen(message.id, labelState.seen);
            db.message().setMessageFlagged(message.id, labelState.flagged);
            db.message().setMessageUiFlagged(message.id, labelState.flagged,
                    labelState.flagged ? message.color : null);
            db.message().setMessageImportance(message.id, labelState.importance);
            db.message().setMessageDeleted(message.id, labelState.deleted);
            db.message().setMessageUiDeleted(message.id, false);
            db.message().setMessageAnswered(message.id, labelState.answered);
            db.message().setMessageUiAnswered(message.id, labelState.answered);
            db.message().setMessageUiHide(message.id, false);
            db.message().setMessageUiBusy(message.id, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        GmailMailMutator.reconcileFailedOperations(db, message.id, labelState.labels);
    }

    private static final class PreparedMessage {
        final GmailApi.ReceivedMessage received;
        final EntityFolder storage;

        PreparedMessage(GmailApi.ReceivedMessage received, EntityFolder storage) {
            this.received = received;
            this.storage = storage;
        }
    }

    private static PreparedMessage prepareMessage(Context context, GmailApi api, Core.State state,
                                                 String messageId,
                                                 Map<String, EntityFolder> folders,
                                                 EntityFolder inbox, String requiredLabel)
            throws IOException, JSONException, MessagingException {
        // Fetch the readable body during native REST sync; ordinary attachment files remain lazy.
        state.ensureRunning("Gmail REST message metadata");
        GmailApi.ReceivedMessage received = api.getMessage(messageId, true);
        if (!messageId.equals(received.id))
            throw new IOException("Gmail message response returned an inconsistent message id");
        if (!shouldAutoImport(received.labels, folders) ||
                (requiredLabel != null && !GmailLabelFolder.isMember(
                        received.labels.toArray(new String[0]), requiredLabel)))
            return null;
        EntityFolder storage = getStorageFolder(received.labels, folders, inbox);
        return new PreparedMessage(received, storage);
    }

    private static boolean importMessage(Context context, DB db, EntityAccount account,
                                         EntityFolder storage, GmailApi.ReceivedMessage received,
                                         Map<String, EntityFolder> folders, EntityFolder inbox)
            throws IOException, MessagingException {
        MimeMessage mime = received.mimeMessage;
        MessageHelper helper = new MessageHelper(mime, context);
        String msgid = helper.getMessageID();
        if (!TextUtils.isEmpty(msgid))
            for (EntityMessage cached : db.message().getMessagesByMsgId(account.id, msgid))
                if (cached.gmail_id == null) {
                    db.message().setMessageGmailIds(cached.id, received.id, received.threadId);
                    Long receivedAt = getReceivedAt(received);
                    if (receivedAt != null)
                        // The linked legacy row may have used RFC Date or IMAP date preferences.
                        db.message().setMessageGmailReceived(cached.id, receivedAt);
                    refreshLabels(db, cached, received.labels, folders, inbox);
                    EntityLog.log(context, "Linked cached Gmail message=" + cached.id +
                            " gmail_id=" + received.id);
                    return false;
                }
        MessageHelper.MessageParts parts = helper.getMessageParts();
        parts.normalize();
        List<MessageHelper.AttachmentPart> mimeAttachments = parts.getAttachmentParts();
        List<GmailApiAttachmentCandidates.Candidate> remoteCandidates =
                GmailApiAttachmentCandidates.collect(mime);
        Set<String> mappedRemoteAttachments = new HashSet<>();
        for (MessageHelper.AttachmentPart part : mimeAttachments) {
            NativeApiContent.applyRemoteMetadata(part.part, part.attachment);
            String key = getRemoteAttachmentKey(part.attachment);
            if (key != null)
                mappedRemoteAttachments.add(key);
        }
        List<GmailApiAttachmentCandidates.Candidate> fallbackAttachments = new ArrayList<>();
        for (GmailApiAttachmentCandidates.Candidate candidate : remoteCandidates) {
            EntityAttachment attachment = GmailApiAttachmentCandidates.toEntityAttachment(candidate);
            String key = getRemoteAttachmentKey(attachment);
            if (key == null || mappedRemoteAttachments.add(key))
                fallbackAttachments.add(candidate);
        }
        NativeApiDiagnostics.logGmailMime(context, "import", received);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean downloadPlain = prefs.getBoolean("download_plain", false);
        String body = (received.bodyIncluded ? parts.getHtml(context, downloadPlain) : null);
        String text = HtmlHelper.getFullText(context, body);

        EntityMessage message = new EntityMessage();
        message.account = account.id;
        message.folder = storage.id;
        message.uid = null;
        message.uidl = null;
        message.gmail_id = received.id;
        message.gmail_thread_id = received.threadId;
        message.msgid = msgid;
        message.hash = helper.getHash();
        message.references = TextUtils.join(" ", helper.getReferences());
        message.inreplyto = helper.getInReplyTo();
        message.deliveredto = helper.getDeliveredTo();
        message.thread = (TextUtils.isEmpty(received.threadId)
                ? null : "gmail:" + account.id + ":" + received.threadId);
        message.priority = helper.getPriority();
        message.auto_submitted = helper.getAutoSubmitted();
        message.receipt_request = helper.getReceiptRequested();
        message.receipt_to = helper.getReceiptTo();
        message.from = helper.getFrom();
        message.to = helper.getTo();
        message.cc = helper.getCc();
        message.bcc = helper.getBcc();
        message.reply = helper.getReply();
        message.list_post = helper.getListPost();
        message.unsubscribe = helper.getListUnsubscribe();
        message.headers = helper.getHeaders();
        message.infrastructure = helper.getInfrastructure();
        message.subject = helper.getSubject();
        message.size = (received.bodyIncluded ? parts.getBodySize() : null);
        message.total = (received.sizeEstimate < 0 ? helper.getSize() : received.sizeEstimate);
        if (message.size == null)
            message.size = message.total;
        message.attachments = mimeAttachments.size() + fallbackAttachments.size();
        message.encrypt = parts.getEncryption();
        message.ui_encrypt = message.encrypt;
        Long receivedAt = getReceivedAt(received);
        message.received = (receivedAt == null ? new Date().getTime() : receivedAt);
        message.gmail_received_checked = (receivedAt != null);
        message.sent = helper.getSent();
        GmailMessageLabelState labelState = GmailMessageLabelState.from(received.labels);
        message.labels = labelState.labels.toArray(new String[0]);
        message.seen = labelState.seen;
        message.answered = labelState.answered;
        message.flagged = labelState.flagged;
        message.deleted = labelState.deleted;
        message.importance = labelState.importance;
        message.ui_seen = message.seen;
        message.ui_answered = message.answered;
        message.ui_flagged = message.flagged;
        message.ui_deleted = false;
        message.sender = MessageHelper.getSortKey(message.from);
        message.preview = HtmlHelper.getPreview(text);
        if (TextUtils.isEmpty(message.preview) && received.snippet != null)
            message.preview = HtmlHelper.getPreview(received.snippet);
        message.language = HtmlHelper.getLanguage(context, message.subject, text);
        message.plain_only = parts.isPlainOnly(downloadPlain);
        message.warning = parts.getWarnings(message.warning);
        message.content = received.bodyIncluded;
        message.raw = false;
        message.notifying = EntityMessage.NOTIFYING_IGNORE;

        List<File> files = new ArrayList<>();
        int attachmentRows = 0;
        try {
            db.beginTransaction();
            try {
                message.id = db.message().insertMessage(message);
                File bodyFile = message.getFile(context);
                files.add(bodyFile);
                if (received.bodyIncluded)
                    Helper.writeText(bodyFile, (body == null ? "" : body));

                int sequence = 1;
                for (MessageHelper.AttachmentPart part : mimeAttachments) {
                    EntityAttachment attachment = part.attachment;
                    NativeApiContent.applyRemoteMetadata(part.part, attachment);
                    attachment.message = message.id;
                    attachment.sequence = sequence++;
                    attachment.id = db.attachment().insertAttachment(attachment);
                    attachmentRows++;
                    cacheGmailInlineBytes(context, db, attachment, part.part, files);
                }

                for (GmailApiAttachmentCandidates.Candidate candidate : fallbackAttachments) {
                    EntityAttachment attachment = GmailApiAttachmentCandidates.toEntityAttachment(candidate);
                    attachment.message = message.id;
                    attachment.sequence = sequence++;
                    attachment.id = db.attachment().insertAttachment(attachment);
                    attachmentRows++;
                    cacheGmailInlineBytes(context, db, attachment, candidate.part, files);
                }

                db.message().setMessageContent(message.id, received.bodyIncluded,
                        message.language, message.plain_only, message.preview, message.warning);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (RuntimeException | IOException ex) {
            for (File file : files)
                if (file.exists() && !file.delete())
                    Log.w("Gmail message cleanup failed: " + file);
            throw ex;
        }
        NativeApiDiagnostics.logGmailAttachmentImport(context, received.attachmentParts,
                mimeAttachments.size(), remoteCandidates.size(), fallbackAttachments.size(), attachmentRows);
        return true;
    }

    private static String getRemoteAttachmentKey(EntityAttachment attachment) {
        if (attachment == null || attachment.provider_id == null || attachment.provider_id.isEmpty() ||
                attachment.provider_type == null || attachment.provider_type.isEmpty())
            return null;
        return attachment.provider_type + "\u0000" + attachment.provider_id;
    }

    private static void cacheGmailInlineBytes(Context context, DB db,
                                              EntityAttachment attachment, Part part,
                                              List<File> files) {
        try {
            if (!shouldCacheGmailInlineBytes(attachment) || part.getSize() <= 0)
                return;
            File file = attachment.getFile(context);
            files.add(file);
            try (InputStream input = new BufferedInputStream(part.getInputStream());
                 OutputStream output = new BufferedOutputStream(new FileOutputStream(file))) {
                copy(input, output);
                db.attachment().setDownloaded(attachment.id, file.length());
            } catch (IOException | MessagingException ex) {
                if (file.exists() && !file.delete())
                    Log.w("Gmail inline attachment cleanup failed: " + file);
                db.attachment().setError(attachment.id, Log.formatThrowable(ex, false));
            }
        } catch (MessagingException ex) {
            Log.w(ex);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[8192];
        for (int length = input.read(buffer); length != -1; length = input.read(buffer))
            output.write(buffer, 0, length);
    }

}

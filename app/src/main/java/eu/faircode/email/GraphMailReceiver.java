package eu.faircode.email;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.OperationCanceledException;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONArray;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

/** Bounded native REST receive path for Microsoft Graph mail. */
final class GraphMailReceiver {
    static final int PAGE_SIZE = GraphMailApi.PAGE_SIZE;

    static final class SyncResult {
        final int imported;
        final boolean morePending;
        final String mode;
        final int pages;

        SyncResult(int imported, boolean morePending, String mode, int pages) {
            this.imported = imported;
            this.morePending = morePending;
            this.mode = mode;
            this.pages = pages;
        }
    }

    private GraphMailReceiver() {
    }

    static boolean isRequestedFolderSync(EntityFolder folder) {
        return (folder != null && "requested".equals(folder.sync_state));
    }

    static boolean isOlderPageRequest(String args) {
        return NativeApiSyncRequest.isOlderPageRequest(args);
    }

    static SyncResult syncInbox(Context context, DB db, EntityAccount account,
                                GraphMailApi api, Core.State state)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        EntityFolder inbox = db.folder().getFolderByType(account.id, EntityFolder.INBOX);
        if (inbox == null || TextUtils.isEmpty(inbox.graph_folder_id))
            throw new IOException("Microsoft Graph account has no mapped Inbox folder");
        if (!shouldSyncAutomatically(inbox))
            return new SyncResult(0, false, "inbox-sync-disabled", 0);
        return syncFolderDelta(context, db, account, api, state, inbox);
    }

    static SyncResult syncFolderDelta(Context context, DB db, EntityAccount account,
                                      GraphMailApi api, Core.State state, EntityFolder requested)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        if (requested == null || requested.id == null || TextUtils.isEmpty(requested.graph_folder_id))
            throw new IOException("Microsoft Graph folder has no remote folder mapping");
        EntityFolder folder = db.folder().getFolder(requested.id);
        if (folder == null || TextUtils.isEmpty(folder.graph_folder_id))
            throw new IOException("Microsoft Graph folder mapping no longer exists");
        SyncResult result;
        if (shouldBootstrapFolder(folder))
            result = bootstrap(context, db, account, api, state, folder);
        else
            try {
                result = applyDelta(context, db, account, api, state, folder, folder.graph_delta_link, false);
            } catch (GraphMailApi.ApiException ex) {
                if (!ex.isDeltaExpired())
                    throw ex;
                folder.graph_delta_link = null;
                folder.graph_page_link = null;
                db.folder().updateFolder(folder);
                EntityLog.log(context, EntityLog.Type.Account, account.id, null, null,
                        "Microsoft Graph delta cursor expired; restarting a bounded recent-message sync");
                result = bootstrap(context, db, account, api, state, folder);
            }
        finishFolderSync(context, db, api, folder);
        clearInitialization(db, folder);
        return result;
    }

    static SyncResult syncFolderPage(Context context, DB db, EntityAccount account,
                                     GraphMailApi api, Core.State state, EntityFolder requested)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        // A one-off explicit request is valid even when ongoing folder synchronization is disabled.
        if (requested == null || requested.id == null || TextUtils.isEmpty(requested.graph_folder_id))
            throw new IOException("Microsoft Graph folder page has no remote folder mapping");
        EntityFolder folder = db.folder().getFolder(requested.id);
        if (folder == null || TextUtils.isEmpty(folder.graph_folder_id))
            throw new IOException("Microsoft Graph folder page no longer maps to a remote folder");

        if (TextUtils.isEmpty(folder.graph_delta_link))
            return bootstrap(context, db, account, api, state, folder);
        if (isFolderPageExhausted(folder))
            return new SyncResult(0, false, "older-pages-exhausted", 0);
        if (TextUtils.isEmpty(folder.graph_page_link)) {
            return bootstrap(context, db, account, api, state, folder);
        }

        state.ensureRunning("Microsoft Graph requested older-message page");
        GraphMailApi.Page page = api.listMessages(folder.graph_page_link, true);
        int imported = importReferences(context, db, account, api, state, folder, page.messages);
        updateFolderPageLink(folder, page);
        db.folder().updateFolder(folder);
        finishFolderSync(context, db, api, folder);
        clearInitialization(db, folder);
        return new SyncResult(imported, false, "older-page", 1);
    }

    static SyncResult syncOtherSelectedFolders(Context context, DB db, EntityAccount account,
                                               GraphMailApi api, Core.State state)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        int imported = 0;
        int pages = 0;
        for (EntityFolder folder : db.folder().getFolders(account.id, false, false)) {
            if (!shouldSyncSelectedFolder(folder))
                continue;
            state.ensureRunning("Microsoft Graph selected-folder delta");
            // A selected folder without a delta cursor needs its one bounded initial window;
            // established cursors are then used only for incremental changes.
            SyncResult result = syncFolderDelta(context, db, account, api, state, folder);
            imported += result.imported;
            pages += result.pages;
        }
        return new SyncResult(imported, false, "selected-folder-delta", pages);
    }

    static boolean shouldSyncAutomatically(EntityFolder folder) {
        return (folder != null && Boolean.TRUE.equals(folder.synchronize));
    }

    static boolean shouldSyncSelectedFolder(EntityFolder folder) {
        return (shouldSyncAutomatically(folder) &&
                !EntityFolder.INBOX.equals(folder.type) &&
                folder.graph_folder_id != null && folder.graph_folder_id.length() > 0);
    }

    static boolean shouldBootstrapFolder(EntityFolder folder) {
        return (folder != null &&
                (folder.graph_delta_link == null || folder.graph_delta_link.length() == 0));
    }

    static boolean isFolderPageExhausted(EntityFolder folder) {
        return (folder != null &&
                (folder.graph_page_link == null || folder.graph_page_link.length() == 0) &&
                folder.graph_delta_link != null && folder.graph_delta_link.length() > 0);
    }

    static boolean canFetchMoreManually(Boolean selectable, String folderId,
                                        String deltaLink, String pageLink) {
        boolean hasDelta = (deltaLink != null && deltaLink.length() > 0);
        boolean hasPage = (pageLink != null && pageLink.length() > 0);
        return (Boolean.TRUE.equals(selectable) && folderId != null && folderId.length() > 0 &&
                (!hasDelta || hasPage));
    }

    static boolean hasDeltaCheckpoint(GraphMailApi.Page page) {
        return (page != null && page.deltaLink != null && page.deltaLink.length() > 0);
    }

    static boolean hasDeltaContinuation(GraphMailApi.Page page) {
        return (page != null && page.nextLink != null && page.nextLink.length() > 0);
    }

    static void updateFolderPageLink(EntityFolder folder, GraphMailApi.Page page) {
        if (folder == null)
            throw new IllegalArgumentException("Missing Graph folder cursor");
        folder.graph_page_link = (page == null ? null : page.nextLink);
    }

    private static void clearInitialization(DB db, EntityFolder folder) {
        if (folder.initialize != 0) {
            folder.initialize = 0;
            db.folder().setFolderInitialize(folder.id, 0);
        }
    }

    static long getInitialDeltaCutoff(EntityFolder folder, long now) {
        int days = folder.getSyncArgs(false).optInt(0, folder.sync_days);
        if (days == Integer.MAX_VALUE)
            return 0;
        return getDayStart(now, days);
    }

    private static void finishFolderSync(Context context, DB db, GraphMailApi api,
                                         EntityFolder folder)
            throws IOException {
        long now = System.currentTimeMillis();
        try {
            GraphMailApi.FolderStats stats = api.getFolderStats(folder.graph_folder_id);
            if (stats.totalItemCount != null)
                db.folder().setFolderTotal(folder.id, stats.totalItemCount, now);
            else
                db.folder().setFolderLastSync(folder.id, now);
        } catch (GraphMailApi.ApiException ex) {
            if (ex.isRateLimitFailure())
                throw ex;
            Log.w("Graph folder count unavailable folder=" + folder.id, ex);
            db.folder().setFolderLastSync(folder.id, now);
        } catch (JSONException | IOException ex) {
            Log.w("Graph folder count unavailable folder=" + folder.id, ex);
            db.folder().setFolderLastSync(folder.id, now);
        }

        if (folder.auto_delete) {
            EntityLog.log(context, EntityLog.Type.Account, folder.account, null, null,
                    "Microsoft Graph automatic retention delete/move remains disabled; only explicit message actions are in scope");
            return;
        }

        int keepDays = folder.keep_days;
        int syncDays = folder.getSyncArgs(false).optInt(0, folder.sync_days);
        if (keepDays == syncDays && keepDays != Integer.MAX_VALUE)
            keepDays++;
        if (keepDays == Integer.MAX_VALUE)
            return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean deleteUnseen = prefs.getBoolean("delete_unseen", true);
        boolean syncUnseen = prefs.getBoolean("sync_unseen", false);
        long keepTime = getDayStart(now, keepDays);
        int keepUnreadDays = (deleteUnseen ? keepDays :
                (int) Math.min(Integer.MAX_VALUE, Math.max(keepDays * 6L,
                        EntityFolder.DEFAULT_KEEP * 6L)));
        long keepUnreadTime = (!deleteUnseen || syncUnseen ? 0 : getDayStart(now, keepUnreadDays));
        long deleteTime = now - 3600 * 1000L;
        int removed = db.message().deleteGraphMessagesBefore(
                folder.id, deleteTime, keepTime, keepUnreadTime);
        if (removed > 0)
            EntityLog.log(context, EntityLog.Type.Account, folder.account, null, null,
                    "Microsoft Graph local cache retention removed=" + removed + " keepDays=" + keepDays);
    }

    private static long getDayStart(long now, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.add(Calendar.DAY_OF_MONTH, -Math.max(0, days));
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return Math.max(0, calendar.getTimeInMillis());
    }

    private static SyncResult bootstrap(Context context, DB db, EntityAccount account,
                                        GraphMailApi api, Core.State state, EntityFolder folder)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        state.ensureRunning("Microsoft Graph recent-message bootstrap");
        GraphMailApi.Page recent = api.listMessages(folder.graph_folder_id);
        GraphInitialWindow initial = new GraphInitialWindow(PAGE_SIZE);
        initial.addAll(recent.messages);
        folder.graph_page_link = recent.nextLink;
        db.folder().updateFolder(folder);

        long cutoff = getInitialDeltaCutoff(folder, System.currentTimeMillis());
        for (GraphMailApi.MailMessage reference : recent.messages)
            if (reference.received > 0)
                cutoff = Math.min(cutoff, reference.received);

        // A receivedDateTime filter starts delta tracking at the oldest message in the bounded
        // ten-message bootstrap window. It avoids walking the mailbox's full historical contents.
        GraphMailApi.Page page = api.listDelta(folder.graph_folder_id, cutoff);
        int pages = 0;
        String deltaLink;
        while (true) {
            state.ensureRunning("Microsoft Graph initial delta page");
            initial.addAll(page.messages);
            pages++;
            if (hasDeltaCheckpoint(page)) {
                deltaLink = page.deltaLink;
                break;
            }
            if (!hasDeltaContinuation(page))
                throw new IOException("Microsoft Graph delta response contained no continuation or delta link");
            page = api.listDelta(page.nextLink, true);
        }
        int imported = importReferences(context, db, account, api, state, folder, initial.newestFirst());
        // Commit only after the bounded initial import succeeds. If it fails, the next
        // attempt replays bootstrap and the stable Graph IDs prevent duplicate rows.
        folder.graph_delta_link = deltaLink;
        db.folder().updateFolder(folder);
        return new SyncResult(imported, false, "recent-ten-and-delta-bootstrap", pages + 1);
    }

    private static SyncResult applyDelta(Context context, DB db, EntityAccount account,
                                         GraphMailApi api, Core.State state, EntityFolder folder,
                                         String startLink, boolean unused)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        String link = startLink;
        int imported = 0;
        int pages = 0;
        while (true) {
            state.ensureRunning("Microsoft Graph delta page");
            GraphMailApi.Page page = api.listDelta(link, true);
            imported += importReferences(context, db, account, api, state, folder, page.messages);
            pages++;
            if (hasDeltaCheckpoint(page)) {
                folder.graph_delta_link = page.deltaLink;
                db.folder().updateFolder(folder);
                break;
            }
            if (!hasDeltaContinuation(page))
                throw new IOException("Microsoft Graph delta response contained no continuation or delta link");
            link = page.nextLink;
        }
        return new SyncResult(imported, false, "delta", pages);
    }

    private static int importReferences(Context context, DB db, EntityAccount account,
                                        GraphMailApi api, Core.State state, EntityFolder folder,
                                        List<GraphMailApi.MailMessage> references)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        int imported = 0;
        for (GraphMailApi.MailMessage reference : references) {
            state.ensureRunning("Microsoft Graph original message");
            EntityMessage cached = db.message().getMessageByGraphId(account.id, reference.id);
            if (reference.removed) {
                if (cached != null)
                    db.message().deleteMessage(cached.id);
                continue;
            }
            if (cached == null && EntityFolder.SENT.equals(folder.type)) {
                EntityMessage local = findLocalSentCopy(
                        db.message().getMessagesByMsgId(account.id, reference.internetMessageId),
                        account.id, folder, reference);
                if (local != null) {
                    String thread = getThreadKey(account.id, reference.id, reference.conversationId);
                    if (db.message().setMessageGraphIdAndThread(local.id, reference.id, thread) > 0) {
                        local.graph_id = reference.id;
                        local.thread = thread;
                        cached = local;
                    } else
                        cached = db.message().getMessageByGraphId(account.id, reference.id);
                }
            }
            if (cached != null) {
                updateServerState(db, cached, reference);
                updateMessageThread(db, cached,
                        getThreadKey(account.id, reference.id, reference.conversationId));
                if (cached.content)
                    continue;
            }
            try {
                boolean contentFetched = true;
                GraphMailApi.MessageDetails details = api.getMessage(reference.id, true);
                if (!reference.id.equals(details.id))
                    throw new IOException("Microsoft Graph list/get returned inconsistent message IDs");
                if (cached == null) {
                    // Graph's attachment list is metadata-only: contentBytes is deliberately not selected.
                    List<NativeApiContent.Attachment> attachments = api.listMessageAttachments(reference.id);
                    MimeMessage mime = createMimeMessage(
                            Session.getInstance(MessageHelper.getSessionProperties(true), null),
                            details, contentFetched, attachments);
                    MessageHelper helper = new MessageHelper(mime, context);
                    if (importMessage(context, db, account, folder, reference, details,
                            mime, helper, contentFetched))
                        imported++;
                } else if (contentFetched) {
                    MimeMessage mime = createMimeMessage(
                            Session.getInstance(MessageHelper.getSessionProperties(true), null),
                            details, true, new ArrayList<NativeApiContent.Attachment>());
                    MessageHelper helper = new MessageHelper(mime, context);
                    installCachedBody(context, db, folder, cached, details, helper);
                }
            } catch (GraphMailApi.ApiException ex) {
                // A concurrent delete/move can invalidate an ID returned by the collection query.
                if (ex.status == 404 || ex.status == 410)
                    continue;
                throw ex;
            }
        }
        return imported;
    }

    static MimeMessage createMimeMessage(Session session, GraphMailApi.MessageDetails details,
                                         boolean includeBody,
                                         List<NativeApiContent.Attachment> attachments)
            throws IOException, MessagingException {
        String contentType = ("text".equalsIgnoreCase(details.bodyType)
                ? "text/plain; charset=UTF-8" : "text/html; charset=UTF-8");
        return NativeApiContent.createMessage(session, null, details.headers,
                (includeBody ? contentType : null),
                (includeBody ? (details.body == null ? "" : details.body) : null),
                attachments);
    }

    private static void installCachedBody(Context context, DB db, EntityFolder folder,
                                          EntityMessage message, GraphMailApi.MessageDetails details,
                                          MessageHelper helper)
            throws IOException, MessagingException {
        MessageHelper.MessageParts parts = helper.getMessageParts();
        parts.normalize();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean downloadPlain = prefs.getBoolean("download_plain", false);
        String body = parts.getHtml(context, downloadPlain);
        String text = HtmlHelper.getFullText(context, body);
        String preview = HtmlHelper.getPreview(text);
        if (TextUtils.isEmpty(preview) && details.preview != null)
            preview = HtmlHelper.getPreview(details.preview);
        String language = HtmlHelper.getLanguage(context, message.subject, text);
        Integer plainOnly = parts.isPlainOnly(downloadPlain);
        String warning = parts.getWarnings(message.warning);
        String headers = helper.getHeaders();
        Long size = parts.getBodySize();
        Long total = (details.size == null ? helper.getSize() : details.size);

        File destination = message.getFile(context);
        File parent = destination.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs()))
            throw new IOException("Unable to create the Graph message body cache directory");
        File temporary = File.createTempFile("graph-body-", ".tmp", parent);
        try {
            Helper.writeText(temporary, (body == null ? "" : body));
            if (destination.exists() && !destination.delete())
                throw new IOException("Unable to replace the incomplete Graph message body cache");
            if (!temporary.renameTo(destination))
                throw new IOException("Unable to install the Graph message body cache");
            temporary = null;
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete())
                Log.w("Graph message-body temporary-file cleanup failed: " + temporary);
        }

        db.beginTransaction();
        try {
            db.message().setMessageContent(message.id, true, language, plainOnly, preview, warning);
            db.message().setMessageHeaders(message.id, headers);
            db.message().setMessageSize(message.id, size, total);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        message.content = true;
        message.headers = headers;
        message.size = size;
        message.total = total;
        message.language = language;
        message.plain_only = plainOnly;
        message.preview = preview;
        message.warning = warning;
        MessageClassifier.classify(message, folder, true, context);
    }

    static String getThreadKey(long accountId, String messageId, String conversationId) {
        String providerConversationId = (conversationId == null || conversationId.length() == 0
                ? messageId : conversationId);
        return "graph:" + accountId + ":" + providerConversationId;
    }

    static EntityMessage findLocalSentCopy(List<EntityMessage> candidates, long accountId,
                                           EntityFolder folder, GraphMailApi.MailMessage remote) {
        if (remote == null)
            return null;
        return NativeSentMessageMatcher.findUnique(candidates, accountId, folder,
                remote.internetMessageId, remote.sent);
    }

    private static void updateMessageThread(DB db, EntityMessage message, String thread) {
        if (message != null && !TextUtils.equals(message.thread, thread)) {
            db.message().setMessageThread(message.id, thread);
            message.thread = thread;
        }
    }

    private static void updateServerState(DB db, EntityMessage message,
                                         GraphMailApi.MailMessage remote) {
        if (message.seen != remote.seen)
            db.message().setMessageSeen(message.id, remote.seen);
        if (message.ui_seen != remote.seen)
            db.message().setMessageUiSeen(message.id, remote.seen);
        message.seen = remote.seen;
        message.ui_seen = remote.seen;
        if (message.flagged != remote.flagged)
            db.message().setMessageFlagged(message.id, remote.flagged);
        if (message.ui_flagged != remote.flagged)
            db.message().setMessageUiFlagged(message.id, remote.flagged, message.color);
        message.flagged = remote.flagged;
        message.ui_flagged = remote.flagged;
    }

    private static boolean importMessage(Context context, DB db, EntityAccount account,
                                         EntityFolder storage, GraphMailApi.MailMessage remote,
                                         GraphMailApi.MessageDetails details, MimeMessage mime,
                                         MessageHelper helper, boolean contentFetched)
            throws IOException, MessagingException {
        MessageHelper.MessageParts parts = helper.getMessageParts();
        parts.normalize();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean downloadPlain = prefs.getBoolean("download_plain", false);
        String body = parts.getHtml(context, downloadPlain);
        String text = HtmlHelper.getFullText(context, body);

        EntityMessage message = new EntityMessage();
        message.account = account.id;
        message.folder = storage.id;
        message.uid = null;
        message.uidl = null;
        message.graph_id = remote.id;
        message.msgid = helper.getMessageID();
        message.hash = helper.getHash();
        message.references = TextUtils.join(" ", helper.getReferences());
        message.inreplyto = helper.getInReplyTo();
        message.deliveredto = helper.getDeliveredTo();
        message.thread = getThreadKey(account.id, remote.id, remote.conversationId);
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
        message.size = (contentFetched ? parts.getBodySize() : null);
        message.total = (details.size == null ? helper.getSize() : details.size);
        if (message.size == null)
            message.size = message.total;
        message.attachments = parts.getAttachments().size();
        message.encrypt = parts.getEncryption();
        message.ui_encrypt = message.encrypt;
        message.received = (remote.received <= 0 ? new Date().getTime() : remote.received);
        message.sent = (remote.sent <= 0 ? helper.getSent() : remote.sent);
        message.seen = remote.seen;
        message.answered = false;
        message.flagged = remote.flagged;
        message.deleted = false;
        message.ui_seen = message.seen;
        message.ui_answered = false;
        message.ui_flagged = message.flagged;
        message.ui_deleted = false;
        message.sender = MessageHelper.getSortKey(message.from);
        message.preview = HtmlHelper.getPreview(text);
        if (TextUtils.isEmpty(message.preview) && details.preview != null)
            message.preview = HtmlHelper.getPreview(details.preview);
        message.language = HtmlHelper.getLanguage(context, message.subject, text);
        message.plain_only = parts.isPlainOnly(downloadPlain);
        message.warning = parts.getWarnings(message.warning);
        message.content = contentFetched;
        message.raw = false;
        message.notifying = EntityMessage.NOTIFYING_IGNORE;

        List<File> files = new ArrayList<>();
        try {
            db.beginTransaction();
            try {
                message.id = db.message().insertMessage(message);
                File bodyFile = message.getFile(context);
                files.add(bodyFile);
                if (contentFetched)
                    Helper.writeText(bodyFile, (body == null ? "" : body));

                int sequence = 1;
                for (MessageHelper.AttachmentPart part : parts.getAttachmentParts()) {
                    EntityAttachment attachment = part.attachment;
                    NativeApiContent.applyRemoteMetadata(part.part, attachment);
                    attachment.message = message.id;
                    attachment.sequence = sequence++;
                    attachment.id = db.attachment().insertAttachment(attachment);
                }

                db.message().setMessageContent(message.id, contentFetched,
                        message.language, message.plain_only, message.preview, message.warning);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (RuntimeException | IOException ex) {
            for (File file : files)
                if (file.exists() && !file.delete())
                    Log.w("Graph message cleanup failed: " + file);
            throw ex;
        }
        return true;
    }

}

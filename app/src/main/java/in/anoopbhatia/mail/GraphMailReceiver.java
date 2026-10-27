package in.anoopbhatia.mail;

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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

/** Bounded native REST receive path for Microsoft Graph mail. */
final class GraphMailReceiver {
    static final int PAGE_SIZE = GraphMailApi.PAGE_SIZE;
    static final int FILTERED_DELTA_RESULT_LIMIT = 5000;
    /** Local ceiling for one delta walk: ten messages a page, so this bounds a corrupt server. */
    static final int MAX_DELTA_PAGES = 10_000;

    // Delta cursors cannot replay membership changes already skipped by an older client.
    // Repair the existing cache in small rotating batches, not an unbounded mailbox scan.
    static final long MEMBERSHIP_CHECK_INTERVAL_MS = 5 * 60 * 1000L;
    private static final ConcurrentHashMap<Long, MembershipCheckpoint> membershipChecks = new ConcurrentHashMap<>();

    private static final class MembershipCheckpoint {
        final long afterId;
        final long checkedAt;

        MembershipCheckpoint(long afterId, long checkedAt) {
            this.afterId = afterId;
            this.checkedAt = checkedAt;
        }
    }

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
        reconcileCachedMemberships(context, db, account, api, state, folder);
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

    /**
     * A delta cursor must advance. Following a link that was already followed replays the same
     * page forever inside the pass, so a repeated link fails the folder instead, and the walk is
     * bounded so no server can keep it running indefinitely.
     */
    static String requireNewDeltaContinuation(GraphMailApi.Page page, Set<String> followed, int pages)
            throws IOException {
        if (pages > MAX_DELTA_PAGES)
            throw new IOException("Microsoft Graph delta exceeded the local page ceiling");
        if (!hasDeltaContinuation(page))
            throw new IOException("Microsoft Graph delta response contained no continuation or delta link");
        if (!followed.add(page.nextLink))
            throw new IOException("Microsoft Graph repeated a delta continuation link");
        return page.nextLink;
    }

    static boolean filteredDeltaLimitReached(long returnedMessages) {
        return returnedMessages >= FILTERED_DELTA_RESULT_LIMIT;
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
            Log.w("Graph folder count unavailable: " + NativeApiDiagnostics.formatFailure(ex));
            db.folder().setFolderLastSync(folder.id, now);
        } catch (JSONException | IOException ex) {
            Log.w("Graph folder count unavailable: " + NativeApiDiagnostics.formatFailure(ex));
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
        // ten-message window; if Graph caps filtered results, the code below restarts unfiltered.
        GraphMailApi.Page page = api.listDelta(folder.graph_folder_id, cutoff);
        int pages = 0;
        long filteredMessages = 0;
        boolean fullBaseline = false;
        String deltaLink;
        Set<String> followed = new HashSet<>();
        while (true) {
            state.ensureRunning("Microsoft Graph initial delta page");
            pages++;
            if (!fullBaseline) {
                filteredMessages += page.messages.size();
                if (filteredDeltaLimitReached(filteredMessages)) {
                    // Filtered message delta is capped at 5,000 results. Discard its partial
                    // baseline and restart unfiltered so the saved cursor covers the whole folder.
                    EntityLog.log(context, EntityLog.Type.Account, account.id, null, null,
                            "Microsoft Graph filtered initial delta reached its 5,000-result cap; restarting a complete baseline");
                    // The full unfiltered traversal establishes checkpoint coverage only; the
                    // configured initial import remains the same newest-ten window as filtered bootstrap.
                    initial = new GraphInitialWindow(PAGE_SIZE);
                    initial.addAll(recent.messages);
                    fullBaseline = true;
                    page = api.listDelta(folder.graph_folder_id);
                    continue;
                }
            }
            initial.addAll(page.messages);
            if (hasDeltaCheckpoint(page)) {
                deltaLink = page.deltaLink;
                break;
            }
            page = api.listDelta(requireNewDeltaContinuation(page, followed, pages), true);
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
        Set<String> followed = new HashSet<>();
        followed.add(link);
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
            link = requireNewDeltaContinuation(page, followed, pages);
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
                if (shouldDeleteCachedMessage(cached, folder))
                    reconcileCachedMembership(context, db, api, state, folder, cached);
                continue;
            }
            if (cached == null && EntityFolder.SENT.equals(folder.type)) {
                EntityMessage local = findLocalSentCopy(
                        db.message().getMessagesByMsgId(account.id, reference.internetMessageId),
                        account.id, folder, reference);
                if (local != null) {
                    String thread = getCachedThreadKey(account.id, reference.id,
                            reference.conversationId, local.thread);
                    if (db.message().setMessageGraphIdAndThread(local.id, reference.id, thread) > 0) {
                        local.graph_id = reference.id;
                        local.thread = thread;
                        cached = local;
                    } else
                        cached = db.message().getMessageByGraphId(account.id, reference.id);
                }
            }
            if (cached != null) {
                // A folder delta is an event stream, not a current-location assertion. A
                // later move can precede this read, including a stale source-folder event.
                cached = reconcileCachedMembership(context, db, api, state, folder, cached);
                if (cached == null)
                    continue;
                updateMessageThread(db, cached,
                        getCachedThreadKey(account.id, reference.id, reference.conversationId,
                                cached.thread));
                if (cached.content)
                    continue;
            }
            try {
                boolean contentFetched = true;
                String currentId = (cached == null ? reference.id : cached.graph_id);
                GraphMailApi.MessageDetails details = api.getMessage(currentId, true);
                if (!currentId.equals(details.id))
                    throw new IOException("Microsoft Graph list/get returned inconsistent message IDs");
                if (TextUtils.isEmpty(details.state.parentFolderId))
                    throw new IOException("Microsoft Graph message response is missing its current folder");
                EntityFolder actual = GraphFolderMembership.resolveFolder(
                        db.folder(), account.id, folder, details.state);
                if (actual == null)
                    throw new IOException("Microsoft Graph message moved to an unmapped folder; retry after folder discovery");
                if (cached == null) {
                    EntityMessage candidate = GraphFolderMembership.findUniqueMovedCandidate(
                            db.message().getMessagesByMsgId(account.id, reference.internetMessageId),
                            account.id, reference.id);
                    if (candidate != null) {
                        GraphMailApi.MessageState located = GraphMessageSync.resolve(api, candidate);
                        // Matching RFC Message-ID alone must never merge a copied message.
                        if (located != null && Objects.equals(located.id, details.id)) {
                            cached = applyCurrentMembership(context, db, candidate, details.state, actual);
                            if (cached == null)
                                continue;
                        }
                    }
                }
                if (cached == null) {
                    // Graph's attachment list is metadata-only: contentBytes is deliberately not selected.
                    List<NativeApiContent.Attachment> attachments = api.listMessageAttachments(details.id);
                    MimeMessage mime = createMimeMessage(
                            Session.getInstance(MessageHelper.getSessionProperties(true), null),
                            details, contentFetched, attachments);
                    MessageHelper helper = new MessageHelper(mime, context);
                    // /me/messages GET is mailbox-wide. Its parentFolderId wins over the
                    // source collection, otherwise a moved item is re-created in Inbox.
                    if (importMessage(context, db, account, actual, reference, details,
                            mime, helper, contentFetched))
                        imported++;
                } else if (contentFetched) {
                    cached = applyCurrentMembership(context, db, cached, details.state, actual);
                    if (cached == null)
                        continue;
                    MimeMessage mime = createMimeMessage(
                            Session.getInstance(MessageHelper.getSessionProperties(true), null),
                            details, true, new ArrayList<NativeApiContent.Attachment>());
                    MessageHelper helper = new MessageHelper(mime, context);
                    installCachedBody(context, db, actual, cached, details, helper);
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

    private static void reconcileCachedMemberships(Context context, DB db, EntityAccount account,
                                                    GraphMailApi api, Core.State state, EntityFolder folder)
            throws IOException, JSONException, OperationCanceledException {
        long now = android.os.SystemClock.elapsedRealtime();
        MembershipCheckpoint checkpoint = membershipChecks.get(folder.id);
        if (checkpoint != null && !isRequestedFolderSync(folder) &&
                now - checkpoint.checkedAt < MEMBERSHIP_CHECK_INTERVAL_MS)
            return;
        long afterId = (checkpoint == null ? 0 : checkpoint.afterId);
        List<EntityMessage> messages = db.message().getGraphMessagesForMembership(
                account.id, folder.id, afterId, PAGE_SIZE);
        for (EntityMessage message : messages) {
            state.ensureRunning("Microsoft Graph cached membership verification");
            reconcileCachedMembership(context, db, api, state, folder, message);
            afterId = message.id;
        }
        membershipChecks.put(folder.id, new MembershipCheckpoint(
                messages.size() < PAGE_SIZE ? 0 : afterId, now));
    }

    static EntityMessage reconcileCachedMembership(Context context, DB db, GraphMailApi api,
                                                       Core.State state, EntityFolder source,
                                                       EntityMessage cached)
            throws IOException, JSONException, OperationCanceledException {
        // A preceding item in this batch may already have merged this duplicate.
        cached = db.message().getMessage(cached.id);
        if (cached == null || TextUtils.isEmpty(cached.graph_id))
            return null;
        GraphMailApi.MessageState remote = GraphFolderMembership.resolveRemoval(api, cached);
        state.ensureRunning("Microsoft Graph membership readback completion");
        if (remote == null) {
            // A successful lookup yielding no unique live item follows a confirmed ID 404.
            // Remove only the old source row, never a row already relocated by another event.
            List<EntityAttachment> attachments = db.attachment().getAttachments(cached.id);
            if (shouldDeleteCachedMessage(cached, source) &&
                    db.message().deleteGraphMessageIfUnchanged(
                            cached.id, source.id, cached.graph_id) > 0) {
                logMembership(context, cached, source, "source-absent");
                // The provider has no copy to download from any more, so its cached bytes
                // must not outlive the row.
                NativeAbsentMessage.deleteCachedFiles(context, cached, attachments);
            }
            return null;
        }
        if (TextUtils.isEmpty(remote.id) || TextUtils.isEmpty(remote.parentFolderId))
            throw new IOException("Microsoft Graph membership response is incomplete");
        EntityFolder actual = GraphFolderMembership.resolveFolder(
                db.folder(), cached.account, source, remote);
        if (actual == null) {
            GraphMessageSync.ensureDestination(db, db.account().getAccount(cached.account), api, remote);
            actual = GraphFolderMembership.resolveFolder(db.folder(), cached.account, source, remote);
        }
        if (actual == null)
            throw new IOException("Microsoft Graph current folder is not mapped; retry after folder discovery");
        return applyCurrentMembership(context, db, cached, remote, actual);
    }

    private static EntityMessage applyCurrentMembership(Context context, DB db, EntityMessage cached,
                                                  GraphMailApi.MessageState remote, EntityFolder actual)
            throws IOException {
        EntityFolder source = db.folder().getFolder(cached.folder);
        boolean relocated = GraphFolderMembership.isRelocation(cached, actual) ||
                !Objects.equals(cached.graph_id, remote.id);
        final EntityMessage initialCached = cached;
        cached = db.completeInTransaction(() -> {
            EntityMessage working = initialCached;
            EntityMessage current = db.message().getMessage(working.id);
            if (current == null || !Objects.equals(current.graph_id, working.graph_id))
                return TransactionResult.rollback(null);
            working = current;
            if (relocated && GraphFolderMembership.hasPendingLocationChange(db.operation(), working.id))
                return TransactionResult.rollback(null);
            EntityMessage duplicate = db.message().getMessageByGraphId(working.account, remote.id);
            if (duplicate != null && !Objects.equals(duplicate.id, working.id)) {
                // Older syncs may have imported the destination before resolving the source.
                // Keep queued user work and the most recently opened reader anchor.
                EntityMessage survivor = GraphFolderMembership.chooseSurvivor(working, duplicate,
                        !db.operation().getOperationsForMessage(working.id).isEmpty(),
                        !db.operation().getOperationsForMessage(duplicate.id).isEmpty());
                if (survivor == null)
                    return TransactionResult.rollback(null);
                EntityMessage obsolete = (Objects.equals(survivor.id, working.id) ? duplicate : working);
                if (db.message().deleteGraphMessageIfUnchanged(
                        obsolete.id, obsolete.folder, obsolete.graph_id) == 0)
                    throw new IOException("Microsoft Graph duplicate has pending work; defer membership merge");
                working = survivor;
            }
            if (GraphFolderMembership.isRelocation(working, actual))
                GraphFolderMembership.relocate(db.message(), working, actual);
            GraphMessageSync.apply(context, db, working, remote);
            return TransactionResult.commit(working);
        });
        if (cached == null)
            return null;
        if (relocated) {
            GraphMessageSync.refresh(context, source);
            GraphMessageSync.refresh(context, actual);
            logMembership(context, cached, actual, "relocated");
        }
        return cached;
    }

    private static void logMembership(Context context, EntityMessage message, EntityFolder folder,
                                      String outcome) {
        EntityLog.log(context, EntityLog.Type.Account, message.account, folder.id, message.id,
                "Microsoft Graph membership outcome=" + outcome);
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
                Log.w("Graph message-body temporary-file cleanup failed");
        }

        final String cachedPreview = preview;
        db.runInTransaction(() -> {
            db.message().setMessageContent(message.id, true, language, plainOnly, cachedPreview, warning);
            db.message().setMessageHeaders(message.id, headers);
            db.message().setMessageSize(message.id, size, total);
        });
        message.content = true;
        message.headers = headers;
        message.size = size;
        message.total = total;
        message.language = language;
        message.plain_only = plainOnly;
        message.preview = preview;
        message.warning = warning;
    }

    static String getThreadKey(long accountId, String messageId, String conversationId) {
        String providerConversationId = (conversationId == null || conversationId.length() == 0
                ? messageId : conversationId);
        return "graph:" + accountId + ":" + providerConversationId;
    }

    // Delta references can omit conversationId. Keep the cached conversation in that case;
    // only a new import is allowed to use the message id fallback.
    static String getCachedThreadKey(long accountId, String messageId, String conversationId,
                                     String cachedThread) {
        if ((conversationId == null || conversationId.length() == 0) && cachedThread != null)
            return cachedThread;
        return getThreadKey(accountId, messageId, conversationId);
    }

    static boolean shouldDeleteCachedMessage(EntityMessage cached, EntityFolder folder) {
        return cached != null && folder != null && folder.id != null &&
                folder.id.equals(cached.folder);
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
        message.seen = details.seen;
        message.answered = false;
        message.flagged = details.flagged;
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
            db.<Void, IOException, MessagingException>callInTransactionChecked(() -> {
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
                return null;
            });
        } catch (RuntimeException | IOException ex) {
            for (File file : files)
                if (file.exists() && !file.delete())
                    Log.w("Graph message cleanup failed");
            throw ex;
        }
        db.message().setMessageNotifying(message.id, 0);
        return true;
    }

}

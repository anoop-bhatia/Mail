package eu.faircode.email;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.OperationCanceledException;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

/** Bounded native REST receive path for Zoho Mail. */
final class ZohoReceiver {
    static final int PAGE_SIZE = ZohoApi.PAGE_SIZE;
    static final int ROUTINE_HEAD_START = 1;
    static final int PAGE_START_EXHAUSTED = 0;
    private static final long MAX_CACHED_BODY_RECOVERY_BYTES = 256 * 1024L;

    static final class SyncResult {
        final int imported;
        final boolean morePending;
        final String mode;
        final int pages;
        final ZohoPageStats pageStats;
        final Set<Long> folderIds;

        SyncResult(int imported, boolean morePending, String mode) {
            this(imported, morePending, mode, 1, null, null);
        }

        SyncResult(int imported, boolean morePending, String mode, int pages,
                   ZohoPageStats pageStats) {
            this(imported, morePending, mode, pages, pageStats, null);
        }

        SyncResult(int imported, boolean morePending, String mode, int pages,
                   ZohoPageStats pageStats, Set<Long> folderIds) {
            this.imported = imported;
            this.morePending = morePending;
            this.mode = mode;
            this.pages = pages;
            this.pageStats = pageStats;
            this.folderIds = (folderIds == null ? new HashSet<Long>() : folderIds);
        }
    }

    private ZohoReceiver() {
    }

    static boolean isFolderPageExhausted(EntityFolder folder) {
        return (folder != null && folder.zoho_page_start != null &&
                folder.zoho_page_start <= PAGE_START_EXHAUSTED);
    }

    static boolean canFetchMoreManually(Boolean selectable, String folderId, Integer pageStart) {
        return (Boolean.TRUE.equals(selectable) && folderId != null && folderId.length() > 0 &&
                (pageStart == null || pageStart > PAGE_START_EXHAUSTED));
    }

    static boolean shouldStopAtNewestWindow(EntityFolder folder, boolean hasBoundary) {
        return (!hasBoundary && isFolderPageExhausted(folder));
    }

    static boolean shouldSyncAutomatically(EntityFolder folder) {
        return (folder != null && Boolean.TRUE.equals(folder.synchronize));
    }

    static int initialPageNextStart(Integer currentStart, ZohoApi.Page page) {
        int start = (currentStart == null ? 1 : currentStart);
        return (start == 1 ? nextPageStart(start, page) : start);
    }

    static int nextPageStart(int start, ZohoApi.Page page) {
        if (start <= PAGE_START_EXHAUSTED || page == null || page.resultItemCount <= 0)
            return PAGE_START_EXHAUSTED;
        return start + page.resultItemCount;
    }

    static boolean shouldFetchOriginalMessage(EntityMessage cached) {
        return (cached == null);
    }

    static boolean shouldFetchCachedBodyOnSync(EntityMessage cached) {
        return (cached != null && !cached.content);
    }

    static boolean shouldRefetchCachedBody(EntityMessage cached) {
        return shouldRefetchCachedBody(cached, null);
    }

    static boolean shouldRefetchCachedBody(EntityMessage cached, String cachedContent) {
        return (cached != null && cached.content &&
                (hasPlainMultipartFallbackWarning(cached.warning) ||
                        (ZohoContentNormalizer.hasXPlainWrapper(cachedContent) &&
                                ZohoContentNormalizer.hasMultipartBoundary(cachedContent))));
    }

    private static boolean hasPlainMultipartFallbackWarning(String existing) {
        if (existing == null)
            return false;
        for (String warning : existing.split(", "))
            if (ZohoApiDiagnostics.isPlainMultipartFallbackWarning(warning))
                return true;
        return false;
    }

    static String removePlainMultipartFallbackWarnings(String existing) {
        if (existing == null)
            return null;
        StringBuilder result = new StringBuilder();
        for (String warning : existing.split(", ")) {
            if (ZohoApiDiagnostics.isPlainMultipartFallbackWarning(warning))
                continue;
            if (result.length() > 0)
                result.append(", ");
            result.append(warning);
        }
        return (result.length() == 0 ? null : result.toString());
    }

    private static String readCachedBodyForRecovery(Context context, EntityMessage cached) {
        if (cached == null || !cached.content || cached.id == null)
            return null;
        File file = cached.getFile(context);
        if (!file.isFile() || file.length() > MAX_CACHED_BODY_RECOVERY_BYTES)
            return null;
        try {
            return Helper.readText(file);
        } catch (IOException ignored) {
            return null;
        }
    }

    static boolean isRequestedFolderSync(EntityFolder folder) {
        return (folder != null && "requested".equals(folder.sync_state));
    }

    static boolean isOlderPageRequest(String args) {
        return NativeApiSyncRequest.isOlderPageRequest(args);
    }

    static SyncResult syncInbox(Context context, DB db, EntityAccount account,
                                ZohoApi api, Core.State state)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        state.ensureRunning("Zoho REST inbox page");
        EntityFolder inbox = db.folder().getFolderByType(account.id, EntityFolder.INBOX);
        if (inbox == null || TextUtils.isEmpty(inbox.zoho_folder_id))
            throw new IOException("Zoho account has no mapped Inbox folder");
        if (!shouldSyncAutomatically(inbox))
            return new SyncResult(0, false, "inbox-sync-disabled", 0, null);
        return syncHeadPages(context, db, account, api, state, inbox, "inbox");
    }

    static SyncResult syncRequestedFolderHead(Context context, DB db, EntityAccount account,
                                              ZohoApi api, Core.State state, EntityFolder requested)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        if (requested == null || requested.id == null || TextUtils.isEmpty(requested.zoho_folder_id))
            throw new IOException("Zoho requested folder has no remote folder mapping");
        EntityFolder folder = db.folder().getFolder(requested.id);
        if (folder == null || TextUtils.isEmpty(folder.zoho_folder_id))
            throw new IOException("Zoho requested folder mapping no longer exists");
        return syncHeadPages(context, db, account, api, state, folder, "requested-head");
    }

    static SyncResult syncSelectedFolderHeads(Context context, DB db, EntityAccount account,
                                              ZohoApi api, Core.State state,
                                              Set<Long> skipFolderIds)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        int imported = 0;
        int pages = 0;
        boolean morePending = false;
        Set<Long> processed = new HashSet<>();
        List<EntityFolder> candidates = new ArrayList<>();
        for (EntityFolder folder : db.folder().getFolders(account.id, false, false)) {
            if (!shouldSyncAutomatically(folder) || EntityFolder.INBOX.equals(folder.type) ||
                    TextUtils.isEmpty(folder.zoho_folder_id) ||
                    (skipFolderIds != null && skipFolderIds.contains(folder.id)) ||
                    isRequestedFolderSync(folder))
                continue;
            candidates.add(folder);
        }
        Collections.sort(candidates, new Comparator<EntityFolder>() {
            @Override
            public int compare(EntityFolder left, EntityFolder right) {
                if (left.last_sync == null && right.last_sync != null)
                    return -1;
                if (left.last_sync != null && right.last_sync == null)
                    return 1;
                if (left.last_sync != null) {
                    int date = Long.compare(left.last_sync, right.last_sync);
                    if (date != 0)
                        return date;
                }
                return Long.compare(left.id, right.id);
            }
        });

        for (EntityFolder folder : candidates) {
            SyncResult result = syncHeadPages(context, db, account, api, state, folder,
                    "selected-head");
            imported += result.imported;
            pages += result.pages;
            morePending |= result.morePending;
            processed.add(folder.id);
        }
        return new SyncResult(imported, morePending, "selected-newest", pages, null, processed);
    }

    private static SyncResult syncHeadPages(Context context, DB db, EntityAccount account,
                                            ZohoApi api, Core.State state, EntityFolder folder,
                                            String pageType)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        Set<Long> processed = new HashSet<>();
        processed.add(folder.id);

        ZohoRecentPageScan scan = new ZohoRecentPageScan();
        int imported = 0;
        int pages = 0;
        ZohoPageStats lastStats = null;
        boolean initial = (folder.zoho_page_start == null || folder.zoho_page_start == 1);

        if (initial) {
            ZohoPageStats stats = new ZohoPageStats();
            lastStats = stats;
            try {
                state.ensureRunning("Zoho REST " + pageType + " initial page");
                stats.markListAttempted();
                ZohoApi.Page page = api.listMessages(folder.zoho_folder_id, ROUTINE_HEAD_START, PAGE_SIZE);
                stats.recordListResult(page.httpStatus, page.resultItemCount);
                state.ensureRunning("Zoho REST " + pageType + " initial page completion");
                PageImportResult result = importPage(context, db, account, api, state, folder,
                        page, stats, scan, page.start, null, false, false);
                imported += result.imported;
                pages++;
                int next = initialPageNextStart(folder.zoho_page_start, page);
                if (folder.zoho_page_start == null || folder.zoho_page_start == 1) {
                    folder.zoho_page_start = next;
                    db.folder().setFolderZohoPageStart(folder.id, next);
                }
                setRecentBoundary(db, folder, ZohoRecentPageScan.lastMessageId(page));
                setRecentScanHead(db, folder, null);
                setRecentPageStart(db, folder, null);
                finishHeadFolder(db, folder);
            } finally {
                finishPageLog(context, account, api, pageType, stats);
            }
            return new SyncResult(imported, false, pageType + "-initial-newest", pages, lastStats, processed);
        }

        String boundaryId = folder.zoho_recent_boundary_id;
        if (TextUtils.isEmpty(boundaryId)) {
            List<String> recentIds = db.message().getZohoRecentMessageIds(
                    account.id, folder.zoho_folder_id, PAGE_SIZE);
            if (recentIds != null && !recentIds.isEmpty()) {
                boundaryId = recentIds.get(recentIds.size() - 1);
                setRecentBoundary(db, folder, boundaryId);
            }
        }

        Integer checkpoint = folder.zoho_recent_page_start;
        boolean pending = (checkpoint != null);
        int resumeStart = ZohoRecentPageScan.resumeStart(checkpoint);
        boolean resumeInFlight = ZohoRecentPageScan.isInFlight(checkpoint);
        boolean headChanged = false;
        String headBoundary;
        ZohoApi.Page head;
        PageImportResult headImport;
        ZohoPageStats headStats = new ZohoPageStats();
        lastStats = headStats;
        try {
            state.ensureRunning("Zoho REST " + pageType + " newest page");
            headStats.markListAttempted();
            head = api.listMessages(folder.zoho_folder_id, ROUTINE_HEAD_START, PAGE_SIZE);
            headStats.recordListResult(head.httpStatus, head.resultItemCount);
            state.ensureRunning("Zoho REST " + pageType + " newest page completion");
            headBoundary = ZohoRecentPageScan.lastMessageId(head);
            headChanged = (pending && !ZohoRecentPageScan.sameHead(
                    folder.zoho_recent_scan_head_id, headBoundary));
            if (!pending || headChanged) {
                setRecentScanHead(db, folder, headBoundary);
                setRecentPageStart(db, folder, ZohoRecentPageScan.inFlight(ROUTINE_HEAD_START));
            }
            headImport = importPage(context, db, account, api, state, folder, head, headStats,
                    scan, head.start, boundaryId, true, true);
            imported += headImport.imported;
            pages++;
        } finally {
            finishPageLog(context, account, api, pageType, headStats);
        }

        if (headImport.boundaryReached || headImport.olderLimitReached ||
                head.resultItemCount < PAGE_SIZE ||
                shouldStopAtNewestWindow(folder, !TextUtils.isEmpty(boundaryId))) {
            return finishRecentHeadScan(context, db, account, api, state, folder, pageType,
                    headBoundary, imported, pages, lastStats, processed);
        }

        int start;
        if (pending && headChanged) {
            start = ZohoRecentPageScan.nextStart(head.start, head.resultItemCount);
            setRecentPageStart(db, folder, start);
        } else if (pending) {
            checkpoint = ZohoRecentPageScan.shiftCheckpoint(checkpoint, headImport.folderPositionsAdded);
            if (resumeInFlight && resumeStart == ROUTINE_HEAD_START)
                checkpoint = ZohoRecentPageScan.nextStart(ROUTINE_HEAD_START, head.resultItemCount);
            setRecentPageStart(db, folder, checkpoint);
            start = ZohoRecentPageScan.resumeStart(checkpoint);
        } else {
            start = ZohoRecentPageScan.nextStart(head.start, head.resultItemCount);
            setRecentPageStart(db, folder, start);
        }

        while (start > 0) {
            ZohoPageStats stats = new ZohoPageStats();
            lastStats = stats;
            ZohoApi.Page page;
            PageImportResult result;
            try {
                state.ensureRunning("Zoho REST " + pageType + " continuation page");
                stats.markListAttempted();
                page = api.listMessages(folder.zoho_folder_id, start, PAGE_SIZE);
                stats.recordListResult(page.httpStatus, page.resultItemCount);
                state.ensureRunning("Zoho REST " + pageType + " continuation completion");
                setRecentPageStart(db, folder, ZohoRecentPageScan.inFlight(start));
                result = importPage(context, db, account, api, state, folder, page, stats,
                        scan, start, boundaryId, true, true);
                imported += result.imported;
                pages++;
            } finally {
                finishPageLog(context, account, api, pageType, stats);
            }

            if (result.boundaryReached || result.olderLimitReached || page.resultItemCount < PAGE_SIZE) {
                return finishRecentHeadScan(context, db, account, api, state, folder, pageType,
                        headBoundary, imported, pages, lastStats, processed);
            }

            start = ZohoRecentPageScan.nextStart(start, page.resultItemCount);
            setRecentPageStart(db, folder, start);
        }

        finishHeadFolder(db, folder);
        return new SyncResult(imported, folder.zoho_recent_page_start != null,
                pageType + "-newest-pending", pages, lastStats, processed);
    }

    private static SyncResult finishRecentHeadScan(Context context, DB db, EntityAccount account,
                                                   ZohoApi api, Core.State state, EntityFolder folder,
        String pageType, String headBoundary,
                                                   int imported, int pages,
                                                   ZohoPageStats lastStats, Set<Long> processed)
            throws IOException, JSONException, OperationCanceledException {
        ZohoPageStats verifyStats = new ZohoPageStats();
        try {
            state.ensureRunning("Zoho REST " + pageType + " head verification");
            verifyStats.markListAttempted();
            ZohoApi.Page verified = api.listMessages(
                    folder.zoho_folder_id, ROUTINE_HEAD_START, PAGE_SIZE);
            verifyStats.recordListResult(verified.httpStatus, verified.resultItemCount);
            pages++;
            String verifiedHead = ZohoRecentPageScan.lastMessageId(verified);
            if (!ZohoRecentPageScan.sameHead(headBoundary, verifiedHead)) {
                setRecentScanHead(db, folder, verifiedHead);
                setRecentPageStart(db, folder, ZohoRecentPageScan.inFlight(ROUTINE_HEAD_START));
                finishHeadFolder(db, folder);
                return new SyncResult(imported, true, pageType + "-head-changed", pages,
                        verifyStats, processed);
            }
            setRecentBoundary(db, folder, headBoundary);
            setRecentScanHead(db, folder, null);
            setRecentPageStart(db, folder, null);
            finishHeadFolder(db, folder);
            return new SyncResult(imported, false, pageType + "-newest-complete", pages,
                    verifyStats, processed);
        } finally {
            finishPageLog(context, account, api, pageType + "-verify", verifyStats);
        }
    }

    static SyncResult syncFolderPage(Context context, DB db, EntityAccount account,
                                     ZohoApi api, Core.State state, EntityFolder requested)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        if (requested == null || requested.id == null || TextUtils.isEmpty(requested.zoho_folder_id))
            throw new IOException("Zoho folder sync request has no remote folder mapping");
        EntityFolder folder = db.folder().getFolder(requested.id);
        if (folder == null || TextUtils.isEmpty(folder.zoho_folder_id))
            throw new IOException("Zoho folder sync request no longer maps to a remote folder");
        int start = (folder.zoho_page_start == null ? 1 : folder.zoho_page_start);
        ZohoPageStats stats = new ZohoPageStats();
        if (start <= PAGE_START_EXHAUSTED) {
            logPage(context, account, "folder-exhausted", stats);
            Set<Long> processed = new HashSet<>();
            processed.add(folder.id);
            return new SyncResult(0, false, "folder-exhausted", 0, stats, processed);
        }
        try {
            state.ensureRunning("Zoho REST manual folder page");
            stats.markListAttempted();
            ZohoApi.Page page = api.listMessages(folder.zoho_folder_id, start, PAGE_SIZE);
            stats.recordListResult(page.httpStatus, page.resultItemCount);
            state.ensureRunning("Zoho REST manual folder page completion");
            importPage(context, db, account, api, state, folder, page, stats,
                    new ZohoRecentPageScan(), page.start, null, false, false);
            int next = nextPageStart(start, page);
            db.folder().setFolderZohoPageStart(folder.id, next);
            Set<Long> processed = new HashSet<>();
            processed.add(folder.id);
            return new SyncResult(stats.imported, false, "folder-page-" + start,
                    (stats.listAttempted ? 1 : 0), stats, processed);
        } finally {
            finishPageLog(context, account, api, "folder", stats);
        }
    }

    private static final class PageImportResult {
        int imported;
        int folderPositionsAdded;
        boolean boundaryReached;
        boolean olderLimitReached;
    }

    private static PageImportResult importPage(Context context, DB db, EntityAccount account,
                                               ZohoApi api, Core.State state, EntityFolder folder,
                                               ZohoApi.Page page, ZohoPageStats stats,
                                               ZohoRecentPageScan scan, int pageStart,
                                               String boundaryId, boolean stopAtOlderCursor,
                                               boolean shiftOlderCursor)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        PageImportResult result = new PageImportResult();
        for (int index = 0; index < page.messages.size(); index++) {
            ZohoApi.MailMessage reference = page.messages.get(index);
            int offset = (reference.position < 0 ? index : reference.position);
            long remotePosition = (long) pageStart + offset;
            if (stopAtOlderCursor && folder.zoho_page_start != null &&
                    folder.zoho_page_start > 1 && remotePosition >= folder.zoho_page_start) {
                result.olderLimitReached = true;
                break;
            }
            boolean isBoundary = (!TextUtils.isEmpty(boundaryId) && boundaryId.equals(reference.id));
            if (!scan.markSeen(reference.id)) {
                if (isBoundary) {
                    result.boundaryReached = true;
                    break;
                }
                continue;
            }
            String deleteTrace = ZohoDeleteTraceRegistry.take(
                    account.id, folder.zoho_folder_id, reference.id);
            if (deleteTrace != null)
                ApiMutationLog.event("zoho", "readback_reconcile",
                        EntityOperation.ZOHO_PERMANENT_DELETE, "reappeared", deleteTrace);
            String traceId = ZohoApiDiagnostics.newTraceId();
            state.ensureRunning("Zoho REST original message fetch");
            EntityMessage cached = db.message().getMessageByZohoId(account.id, reference.id);
            stats.recordReference(cached != null);
            boolean refetchCachedBody = false;
            String cachedBody = null;
            String cacheRead = "not_applicable";
            long cacheFileBytes = -1;
            if (cached != null) {
                updateMessageThread(db, cached, getThreadKey(account.id, reference.id, reference.threadId));
                refetchCachedBody = shouldRefetchCachedBody(cached);
                if (cached.content && !refetchCachedBody) {
                    File cachedFile = cached.getFile(context);
                    cacheFileBytes = (cachedFile.isFile() ? cachedFile.length() : -1);
                    if (!cachedFile.isFile())
                        cacheRead = "missing";
                    else if (cacheFileBytes > MAX_CACHED_BODY_RECOVERY_BYTES)
                        cacheRead = "too_large";
                    else {
                        cacheRead = "attempted";
                        cachedBody = readCachedBodyForRecovery(context, cached);
                        cacheRead = (cachedBody == null ? "unreadable" : "read_ok");
                        refetchCachedBody = shouldRefetchCachedBody(cached, cachedBody);
                    }
                } else if (refetchCachedBody) {
                    File cachedFile = cached.getFile(context);
                    cacheFileBytes = (cachedFile.isFile() ? cachedFile.length() : -1);
                    cacheRead = "warning_triggered";
                }
            }
            String cacheDecision = (cached == null ? "fetch_original" :
                    (refetchCachedBody ? "refetch_body" :
                            (!cached.content ? "body_missing" : "reuse_cached_body")));
            ZohoApiDiagnostics.logTrace(context, traceId, "cache_lookup",
                    "cached=" + (cached != null) +
                            " db_content=" + (cached != null && cached.content) +
                            " cache_file_bytes=" + cacheFileBytes +
                            " cache_read=" + cacheRead +
                            " cached_body_chars=" + (cachedBody == null ? -1 : cachedBody.length()) +
                            " cached_has_xplain=" + ZohoContentNormalizer.hasXPlainWrapper(cachedBody) +
                            " cached_has_boundary=" + ZohoContentNormalizer.hasMultipartBoundary(cachedBody) +
                            " warning_fallback=" + (cached != null && hasPlainMultipartFallbackWarning(cached.warning)) +
                            " decision=" + cacheDecision);
            if (refetchCachedBody) {
                    cached.warning = removePlainMultipartFallbackWarnings(cached.warning);
                    db.message().setMessageContent(cached.id, false, cached.language,
                            cached.plain_only, cached.preview, cached.warning);
                    cached.content = false;
            }
            if (!shouldFetchOriginalMessage(cached)) {
                if (addFolderMembership(db, cached, folder.zoho_folder_id)) {
                    result.folderPositionsAdded++;
                    if (shiftOlderCursor)
                        advanceOlderPageStart(db, folder);
                }
                updateServerSeenState(db, cached, reference.seen);
                updateServerFlagState(db, cached, reference.flagged);
                if (shouldFetchCachedBodyOnSync(cached) &&
                        db.operation().getOperationCount(cached.folder, cached.id,
                                EntityOperation.BODY) == 0)
                    EntityOperation.queue(context, cached, EntityOperation.BODY);
                if (isBoundary) {
                    result.boundaryReached = true;
                    break;
                }
                continue;
            }

            String rawHeaders;
            String body;
            List<NativeApiContent.Attachment> remoteAttachments;
            try {
                // Zoho exposes readable body content separately from attachment metadata and bytes.
                rawHeaders = api.getMessageHeaders(folder.zoho_folder_id, reference.id);
                remoteAttachments = api.getMessageAttachments(folder.zoho_folder_id, reference.id, traceId);
                body = api.getMessageContent(folder.zoho_folder_id, reference.id, traceId);
                stats.recordMetadataGet(true);
            } catch (IOException ex) {
                stats.recordMetadataGet(false);
                // The list can race a server-side deletion; other API errors are not ignored.
                if (ex instanceof ZohoApi.ApiException && ((ZohoApi.ApiException) ex).status == 404) {
                    if (isBoundary)
                        result.boundaryReached = true;
                    if (result.boundaryReached)
                        break;
                    continue;
                }
                throw ex;
            } catch (JSONException ex) {
                stats.recordMetadataGet(false);
                throw ex;
            } catch (RuntimeException ex) {
                stats.recordMetadataGet(false);
                throw ex;
            }

            state.ensureRunning("Zoho REST message metadata completion");
            long bodyRequestSequence = api.getLastContentRequestSequence();
            String bodyInputKind = ZohoContentNormalizer.sourceKind(body);
            ZohoApiDiagnostics.logTrace(context, traceId, "body_input",
                    "request_seq=" + Math.max(0, bodyRequestSequence) +
                            " classification=" + bodyInputKind +
                            " chars=" + body.length() +
                            " utf8_bytes=" + body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length +
                            " has_boundary=" + ZohoContentNormalizer.hasMultipartBoundary(body) +
                            " image_display_cid_queries=" + ZohoContentNormalizer.countImageDisplayCidReferences(body) +
                            " cid_references=" + ZohoContentNormalizer.countCidReferences(body) +
                            " xplain_wrapper=" + ZohoContentNormalizer.hasXPlainWrapper(body));
            ZohoContentNormalizer.Body normalized;
            try {
                normalized = ZohoContentNormalizer.normalize(body);
            } catch (IOException ex) {
                ZohoApiDiagnostics.logTrace(context, traceId, "body_normalized",
                        "request_seq=" + Math.max(0, bodyRequestSequence) +
                                " outcome=failure error_class=" + ex.getClass().getSimpleName());
                throw ex;
            }
            int inlineResourceCount = 0;
            int inlineImageCount = 0;
            for (NativeApiContent.Attachment attachment : remoteAttachments)
                if (ZohoApi.INLINE_ATTACHMENT_TYPE.equals(attachment.providerType)) {
                    inlineResourceCount++;
                    if (attachment.contentType != null &&
                            attachment.contentType.regionMatches(true, 0, "image/", 0, 6))
                        inlineImageCount++;
                }
            ZohoApiDiagnostics.logTrace(context, traceId, "body_normalized",
                            "request_seq=" + Math.max(0, bodyRequestSequence) +
                            " outcome=success path=" + normalized.mimePath.replace('>', '/') +
                            " source_type=" + ZohoApiDiagnostics.safeContentType(normalized.contentType) +
                            " output_chars=" + normalized.content.length() +
                            " output_utf8_bytes=" + normalized.content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length +
                            " plain_parts=" + normalized.textPlainParts +
                            " html_parts=" + normalized.textHtmlParts +
                            " embedded_resources=" + normalized.inlineResources +
                            " embedded_images=" + normalized.inlineImages +
                            " output_cid_references=" + normalized.cidReferences +
                            " output_has_boundary=" + ZohoContentNormalizer.hasMultipartBoundary(normalized.content));
            MimeMessage mime = NativeApiContent.createZohoMessage(
                    Session.getInstance(MessageHelper.getSessionProperties(true), null),
                    rawHeaders, normalized.contentType, normalized.content, remoteAttachments);
            ZohoApiDiagnostics.logTrace(context, traceId, "synthetic_mime",
                    "request_seq=" + Math.max(0, bodyRequestSequence) +
                            " input_classification=" + bodyInputKind +
                            " root_type=" + ZohoApiDiagnostics.safeContentType(mime.getContentType()) +
                            " remote_attachment_parts=" + remoteAttachments.size() +
                            " inline_resources=" + inlineResourceCount +
                            " inline_images=" + inlineImageCount);
            NativeApiContent.logZohoMimeTree(context, traceId, mime);
            MessageHelper helper = new MessageHelper(mime, context, true, traceId);
            String msgid = helper.getMessageID();
            if (!TextUtils.isEmpty(msgid)) {
                boolean linked = false;
                EntityMessage old = NativeSentMessageMatcher.findUnique(
                        db.message().getMessagesByMsgId(account.id, msgid), account.id, folder,
                        msgid, (reference.sent > 0 ? reference.sent : reference.received));
                if (old != null) {
                    boolean alreadyInFolder = ZohoMailMutator.hasFolderId(
                            old.zoho_folder_ids, folder.zoho_folder_id);
                    String memberships = addFolderId(old.zoho_folder_ids, folder.zoho_folder_id);
                    if (db.message().setMessageZohoId(old.id, reference.id, memberships) > 0) {
                        updateMessageThread(db, old, getThreadKey(account.id, reference.id, reference.threadId));
                        if (!alreadyInFolder) {
                            result.folderPositionsAdded++;
                            if (shiftOlderCursor)
                                advanceOlderPageStart(db, folder);
                        }
                        updateServerSeenState(db, old, reference.seen);
                        updateServerFlagState(db, old, reference.flagged);
                        linked = true;
                    }
                }
                if (linked)
                    stats.recordLinkedExisting();
                if (linked) {
                    ZohoApiDiagnostics.logTrace(context, traceId, "message_linked",
                            "request_seq=" + Math.max(0, bodyRequestSequence) +
                                    " renderer_skipped=true existing_message_linked=true");
                    if (isBoundary) {
                        result.boundaryReached = true;
                        break;
                    }
                    continue;
                }
            }
            if (importMessage(context, db, account, folder, reference, mime, helper, true,
                    traceId, bodyRequestSequence, bodyInputKind, normalized.contentType,
                    remoteAttachments.size(), inlineResourceCount, inlineImageCount)) {
                stats.recordImported();
                result.imported++;
                result.folderPositionsAdded++;
                if (shiftOlderCursor)
                    advanceOlderPageStart(db, folder);
            }
            if (isBoundary) {
                result.boundaryReached = true;
                break;
            }
        }
        return result;
    }

    private static void finishPageLog(Context context, EntityAccount account, ZohoApi api,
                                      String pageType, ZohoPageStats stats) {
        if (stats.listAttempted && stats.listStatus < 0)
            stats.recordListStatus(api.getLastHttpStatus());
        logPage(context, account, pageType, stats);
    }

    private static void logPage(Context context, EntityAccount account, String pageType,
                                ZohoPageStats stats) {
        EntityLog.log(context, EntityLog.Type.Account, account.id, null, null,
                stats.toDiagnosticString(pageType));
    }

    private static void updateServerSeenState(DB db, EntityMessage message, boolean seen) {
        if (message.seen != seen)
            db.message().setMessageSeen(message.id, seen);
        if (message.ui_seen != seen)
            db.message().setMessageUiSeen(message.id, seen);
        message.seen = seen;
        message.ui_seen = seen;
    }

    static String getThreadKey(long accountId, String messageId, String threadId) {
        String providerThreadId = (threadId == null || threadId.length() == 0 ? messageId : threadId);
        return "zoho:" + accountId + ":" + providerThreadId;
    }

    private static void updateMessageThread(DB db, EntityMessage message, String thread) {
        if (message != null && !TextUtils.equals(message.thread, thread)) {
            db.message().setMessageThread(message.id, thread);
            message.thread = thread;
        }
    }

    private static void updateServerFlagState(DB db, EntityMessage message, boolean flagged) {
        if (message.flagged != flagged)
            db.message().setMessageFlagged(message.id, flagged);
        if (message.ui_flagged != flagged)
            db.message().setMessageUiFlagged(message.id, flagged, message.color);
        message.flagged = flagged;
        message.ui_flagged = flagged;
    }

    private static boolean addFolderMembership(DB db, EntityMessage message, String folderId) {
        String updated = addFolderId(message.zoho_folder_ids, folderId);
        if (!updated.equals(message.zoho_folder_ids)) {
            db.message().setMessageZohoFolderIds(message.id, updated);
            message.zoho_folder_ids = updated;
            return true;
        }
        return false;
    }

    private static void advanceOlderPageStart(DB db, EntityFolder folder) {
        if (folder.zoho_page_start != null && folder.zoho_page_start > 1) {
            folder.zoho_page_start = ZohoRecentPageScan.shiftOlderStart(folder.zoho_page_start, 1);
            db.folder().setFolderZohoPageStart(folder.id, folder.zoho_page_start);
        }
    }

    private static void setRecentPageStart(DB db, EntityFolder folder, Integer start) {
        folder.zoho_recent_page_start = start;
        db.folder().setFolderZohoRecentPageStart(folder.id, start);
    }

    private static void setRecentBoundary(DB db, EntityFolder folder, String messageId) {
        folder.zoho_recent_boundary_id = messageId;
        db.folder().setFolderZohoRecentBoundary(folder.id, messageId);
    }

    private static void setRecentScanHead(DB db, EntityFolder folder, String messageId) {
        folder.zoho_recent_scan_head_id = messageId;
        db.folder().setFolderZohoRecentScanHead(folder.id, messageId);
    }

    private static void finishHeadFolder(DB db, EntityFolder folder) {
        folder.last_sync = System.currentTimeMillis();
        db.folder().setFolderLastSync(folder.id, folder.last_sync);
        db.folder().setFolderError(folder.id, null);
        db.folder().setFolderState(folder.id, "connected");
    }

    private static String addFolderId(String current, String folderId) {
        if (TextUtils.isEmpty(folderId) || folderId.indexOf('|') >= 0)
            throw new IllegalArgumentException("Zoho folder ID cannot be represented safely");
        String token = "|" + folderId + "|";
        if (current == null || current.isEmpty())
            return token;
        if (current.contains(token))
            return current;
        return current + folderId + "|";
    }

    private static boolean importMessage(Context context, DB db, EntityAccount account,
                                         EntityFolder storage, ZohoApi.MailMessage remote,
                                         MimeMessage mime, MessageHelper helper,
                                         boolean contentFetched, String traceId,
                                         long bodyRequestSequence,
                                         String bodyInputKind, String bodyType,
                                         int remoteAttachmentCount, int inlineResourceCount,
                                         int inlineImageCount)
            throws IOException, MessagingException {
        MessageHelper.MessageParts parts;
        try {
            parts = helper.getMessageParts();
            parts.normalize();
        } catch (IOException | MessagingException | RuntimeException ex) {
            ZohoApiDiagnostics.logTrace(context, traceId, "helper_parts",
                    "request_seq=" + Math.max(0, bodyRequestSequence) +
                            " outcome=parse_failure error_class=" + ex.getClass().getSimpleName());
            throw ex;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean downloadPlain = prefs.getBoolean("download_plain", false);
        String rendererTraceId = (ZohoApiDiagnostics.isTraceEnabled(context) ? traceId : null);
        parts.logZohoDiagnostics(context, rendererTraceId, downloadPlain);
        String body;
        try {
            body = parts.getHtml(context, downloadPlain, null, rendererTraceId);
        } catch (IOException | MessagingException | RuntimeException ex) {
            ZohoApiDiagnostics.logTrace(context, traceId, "renderer_output",
                    "request_seq=" + Math.max(0, bodyRequestSequence) +
                            " outcome=render_failure error_class=" + ex.getClass().getSimpleName());
            throw ex;
        }
        String text = HtmlHelper.getFullText(context, body);
        int generatedWarningCount = parts.getDiagnosticWarningCount();
        int generatedFallbackCount = parts.getDiagnosticPlainMultipartFallbackCount();
        Integer rendererPlainOnly = parts.isPlainOnly();
        if (bodyRequestSequence >= 0) {
            ZohoApiDiagnostics.logTrace(context, traceId, "renderer_output",
                    "request_seq=" + Math.max(0, bodyRequestSequence) +
                            " body_chars=" + (body == null ? -1 : body.length()) +
                            " body_utf8_bytes=" + (body == null ? -1 :
                                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) +
                            " renderer_input_type=" + ZohoApiDiagnostics.safeContentType(bodyType) +
                            " renderer_is_html=" + (rendererPlainOnly != null && (rendererPlainOnly & 1) == 0) +
                            " xplain_wrapper=" + ZohoContentNormalizer.hasXPlainWrapper(body) +
                            " xplain_has_boundary=" + (ZohoContentNormalizer.hasXPlainWrapper(body) &&
                                    ZohoContentNormalizer.hasMultipartBoundary(body)) +
                            " text_plain_fallback_warnings=" + generatedFallbackCount +
                            " all_warnings=" + generatedWarningCount +
                            " image_display_cid_queries=" + ZohoContentNormalizer.countImageDisplayCidReferences(body) +
                            " cid_references=" + ZohoContentNormalizer.countCidReferences(body));
        }

        EntityMessage message = new EntityMessage();
        message.account = account.id;
        message.folder = storage.id;
        message.uid = null;
        message.uidl = null;
        message.zoho_id = remote.id;
        message.zoho_folder_ids = addFolderId(null, storage.zoho_folder_id);
        message.msgid = helper.getMessageID();
        message.hash = helper.getHash();
        message.references = TextUtils.join(" ", helper.getReferences());
        message.inreplyto = helper.getInReplyTo();
        message.deliveredto = helper.getDeliveredTo();
        message.thread = getThreadKey(account.id, remote.id, remote.threadId);
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
        message.total = (remote.size < 0 ? helper.getSize() : remote.size);
        if (message.size == null)
            message.size = message.total;
        message.attachments = parts.getAttachments().size();
        message.encrypt = parts.getEncryption();
        message.ui_encrypt = message.encrypt;
        message.received = normalizeTimestamp(remote.received, new Date().getTime());
        message.sent = normalizeTimestamp(remote.sent, helper.getSent());
        message.seen = remote.seen;
        message.answered = false;
        message.flagged = remote.flagged;
        message.deleted = false;
        message.ui_seen = message.seen;
        message.ui_answered = false;
        message.ui_flagged = message.flagged;
        message.ui_deleted = false;
        message.sender = MessageHelper.getSortKey(message.from);
        String previewText = (TextUtils.isEmpty(text) ? remote.summary : text);
        message.preview = HtmlHelper.getPreview(previewText);
        message.language = HtmlHelper.getLanguage(context, message.subject, previewText);
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
                    Log.w("Zoho message cleanup failed: " + file);
            throw ex;
        }
        if (contentFetched && bodyRequestSequence >= 0) {
            File bodyCache = message.getFile(context);
            ZohoApiDiagnostics.logTrace(context, traceId, "body_persisted",
                    "request_seq=" + Math.max(0, bodyRequestSequence) +
                            " cache_file_exists=" + bodyCache.isFile() +
                            " cache_file_bytes=" + (bodyCache.isFile() ? bodyCache.length() : -1) +
                            " database_content=" + message.content +
                            " persisted_body_chars=" + (body == null ? 0 : body.length()) +
                            " persisted_body_utf8_bytes=" + (body == null ? 0 :
                                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length) +
                            " warning_present=" + !TextUtils.isEmpty(message.warning) +
                            " plain_multipart_fallback_warning=" +
                                    hasPlainMultipartFallbackWarning(message.warning));
        }
        return true;
    }

    private static long normalizeTimestamp(long timestamp, long fallback) {
        if (timestamp <= 0)
            return fallback;
        // Zoho currently returns epoch milliseconds; tolerate seconds if an older DC does so.
        return (timestamp < 100000000000L ? timestamp * 1000L : timestamp);
    }

    private static long normalizeTimestamp(long timestamp, Long fallback) {
        return normalizeTimestamp(timestamp, fallback == null ? new Date().getTime() : fallback);
    }

}

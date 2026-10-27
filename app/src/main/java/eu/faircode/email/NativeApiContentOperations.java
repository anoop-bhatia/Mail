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

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.internet.MimeMessage;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Runs native BODY/ATTACHMENT requests and explicit RAW exports for Gmail, Zoho, and Graph REST accounts. */
final class NativeApiContentOperations {
    private static final long MAX_CACHE_BYTES = 50L * 1024L * 1024L;
    static final String EXPLICIT_RAW_EXPORT_ARGUMENT = "save_raw";

    private NativeApiContentOperations() {
    }

    interface RawMessageFetcher {
        void fetch(File temporary) throws IOException, JSONException;
    }

    interface FailureHandler {
        void setAttachmentError(long attachmentId, String error);

        void logFailure(long operationId, String error);

        void deleteOperation(long operationId);
    }

    private static final class BodyMetrics {
        final long sourceLength;
        final long renderedChars;
        final boolean cacheReused;

        BodyMetrics(long sourceLength, long renderedChars, boolean cacheReused) {
            this.sourceLength = sourceLength;
            this.renderedChars = renderedChars;
            this.cacheReused = cacheReused;
        }
    }

    static boolean isContentOperation(EntityOperation operation) {
        return (operation != null &&
                (EntityOperation.BODY.equals(operation.name) ||
                        EntityOperation.ATTACHMENT.equals(operation.name)));
    }

    static boolean isRawExportOperation(EntityOperation operation) {
        if (operation == null || !EntityOperation.RAW.equals(operation.name))
            return false;
        try {
            String value = (operation.args == null || operation.args.isEmpty() ? "[]" : operation.args);
            JSONArray arguments = new JSONArray(value);
            return (arguments.length() == 1 && EXPLICIT_RAW_EXPORT_ARGUMENT.equals(arguments.optString(0)));
        } catch (JSONException ex) {
            return false;
        }
    }

    static boolean supportsAccount(EntityAccount account) {
        return (account != null &&
                (account.isGmailApi() || account.isZohoApi() || account.isGraphApi()));
    }

    static boolean shouldQueueBodyOnOpen(EntityAccount account, EntityFolder folder,
                                         EntityMessage message) {
        return "eligible".equals(bodyOpenDecision(account, folder, message, false));
    }

    static String bodyOpenDecision(EntityAccount account, EntityFolder folder,
                                   EntityMessage message, boolean operationPending) {
        if (!supportsAccount(account))
            return "unsupported_provider";
        if (folder == null)
            return "folder_missing";
        if (message == null)
            return "message_missing";
        if (message.content)
            return "already_complete";
        if (account.id == null || folder.id == null || message.id == null ||
                !account.id.equals(message.account) || !account.id.equals(folder.account) ||
                !folder.id.equals(message.folder))
            return "owner_mismatch";
        if (!hasProviderMessageId(account, message))
            return "provider_id_missing";
        return (operationPending ? "already_pending" : "eligible");
    }

    private static boolean hasText(String value) {
        return (value != null && !value.isEmpty());
    }

    static boolean queueBodyOnOpen(Context context, DB db, EntityAccount account,
                                   EntityFolder folder, EntityMessage message) {
        return queueBodyOnOpen(context, db, account, folder, message, null);
    }

    static boolean queueBodyOnOpen(Context context, DB db, EntityAccount account,
                                   EntityFolder folder, EntityMessage message, String requestedTrace) {
        String trace = (account != null && account.isGraphApi()
                ? ApiMutationLog.safeTraceId(requestedTrace) : null);
        String decision = bodyOpenDecision(account, folder, message, false);
        if (!"eligible".equals(decision)) {
            NativeApiDiagnostics.logBodyOpenDecision(context, providerFor(account), decision,
                    folder, message, hasProviderMessageId(account, message), false);
            if (trace != null)
                ApiMutationLog.event("graph", "body_fetch", "body", "rejected", trace);
            return false;
        }
        boolean pending = db.operation().getOperationCount(folder.id, message.id,
                EntityOperation.BODY) > 0;
        if (pending) {
            NativeApiDiagnostics.logBodyOpenDecision(context, providerFor(account),
                    "already_pending", folder, message,
                    hasProviderMessageId(account, message), true);
            if (trace != null)
                ApiMutationLog.event("graph", "body_fetch", "body", "already_pending", trace);
            return false;
        }
        if (trace == null)
            EntityOperation.queue(context, message, EntityOperation.BODY);
        else
            EntityOperation.queue(context, message, EntityOperation.BODY,
                    JSONObject.NULL, JSONObject.NULL, trace);
        NativeApiDiagnostics.logBodyOpenDecision(context, providerFor(account), "queued",
                folder, message, hasProviderMessageId(account, message), false);
        if (trace != null) {
            EntityOperation operation = db.operation().getOperation(message.id, EntityOperation.BODY);
            ApiMutationLog.event("graph", "queue_insert", "body",
                    (ApiMutationLog.operationHasTrace(operation, trace) ? "queued" : "not_queued"), trace);
        }
        return true;
    }

    private static boolean hasProviderMessageId(EntityAccount account, EntityMessage message) {
        return (account != null && message != null &&
                ((account.isGmailApi() && hasText(message.gmail_id)) ||
                        (account.isZohoApi() && hasText(message.zoho_id)) ||
                        (account.isGraphApi() && hasText(message.graph_id))));
    }

    static boolean shouldFetchRenderedInline(EntityAccount account, EntityAttachment attachment) {
        return (supportsAccount(account) && attachment != null && !attachment.available &&
                attachment.progress == null && attachment.provider_id != null);
    }

    static boolean shouldQueueAttachmentTap(EntityAttachment attachment) {
        return (attachment != null && attachment.progress == null && !attachment.available);
    }

    static boolean shouldWakeAfterBulkAttachmentQueue(EntityAccount account) {
        return (account != null && (account.isGmailApi() || account.isZohoApi()));
    }

    static boolean hasMissingLegacyImapUid(EntityAccount account, EntityMessage message) {
        return (account != null && message != null &&
                account.protocol == EntityAccount.TYPE_IMAP && !account.isNativeRestApi() &&
                message.uid == null);
    }

    static boolean isGraphApiCidImage(EntityMessage message, EntityAttachment attachment) {
        return (message != null && hasText(message.graph_id) && attachment != null &&
                hasText(attachment.cid) && hasText(attachment.provider_id) &&
                attachment.type != null && attachment.type.regionMatches(true, 0, "image/", 0, 6));
    }

    static boolean matchesGraphCidReference(EntityMessage message, EntityAttachment attachment,
                                            String source) {
        if (!isGraphApiCidImage(message, attachment) || source == null || !source.startsWith("cid:"))
            return false;
        String cid = source.substring(4);
        if (!cid.startsWith("<"))
            cid = "<" + cid;
        if (!cid.endsWith(">"))
            cid += ">";
        return cid.equals(attachment.cid);
    }

    static boolean hasUncachedGraphInlineImage(EntityMessage message,
                                               List<EntityAttachment> attachments, String source) {
        if (attachments == null)
            return false;
        for (EntityAttachment attachment : attachments)
            if (!attachment.available && matchesGraphCidReference(message, attachment, source))
                return true;
        return false;
    }

    static boolean shouldFetchGraphCidImagesOnOpen(EntityMessage message,
                                                    List<EntityAttachment> attachments,
                                                    String source, boolean showFull,
                                                    boolean inlineImages) {
        return (!showFull && inlineImages &&
                hasUncachedGraphInlineImage(message, attachments, source));
    }

    static boolean shouldWakeForGraphCidImageRequest(EntityMessage message,
                                                      EntityAttachment attachment) {
        return (isGraphApiCidImage(message, attachment) && !attachment.available &&
                attachment.progress == null);
    }

    static boolean usesZohoInlineImageEndpoint(EntityAttachment attachment) {
        if (attachment == null || !hasText(attachment.cid))
            return false;
        if (ZohoApi.INLINE_ATTACHMENT_TYPE.equals(attachment.provider_type))
            return true;
        if (ZohoApi.ORDINARY_ATTACHMENT_TYPE.equals(attachment.provider_type))
            return false;
        // Rows created before provider-specific attachment markers retain MIME inline metadata.
        return attachment.isInline();
    }

    static boolean isZohoApiCidImage(EntityMessage message, EntityAttachment attachment) {
        return (message != null && hasText(message.zoho_id) && attachment != null &&
                hasText(attachment.cid) && usesZohoInlineImageEndpoint(attachment) &&
                attachment.type != null && attachment.type.regionMatches(true, 0, "image/", 0, 6));
    }

    static boolean matchesZohoCidReference(EntityMessage message, EntityAttachment attachment,
                                            String source) {
        if (!isZohoApiCidImage(message, attachment) || source == null)
            return false;
        String cid = (source.startsWith("cid:")
                ? normalizeZohoContentId(source.substring(4))
                : getZohoImageDisplayCid(source));
        return (cid != null && cid.equals(normalizeZohoContentId(attachment.cid)));
    }

    private static String normalizeZohoContentId(String value) {
        if (value == null)
            return null;
        String cid = value.trim();
        boolean open = cid.startsWith("<");
        boolean close = cid.endsWith(">");
        if (open != close)
            return null;
        if (open)
            cid = cid.substring(1, cid.length() - 1).trim();
        return (cid.isEmpty() ? null : "<" + cid + ">");
    }

    /** Returns a canonical Content-ID only for Zoho's root-relative ImageDisplay route. */
    static String getZohoImageDisplayCid(String source) {
        if (source == null)
            return null;
        int query = source.indexOf('?');
        if (query < 0 || !"/mail/ImageDisplay".equals(source.substring(0, query)))
            return null;
        int fragment = source.indexOf('#', query + 1);
        String value = null;
        for (String parameter : source.substring(query + 1,
                fragment < 0 ? source.length() : fragment).split("&", -1)) {
            int equals = parameter.indexOf('=');
            String name = decodeZohoQueryComponent(equals < 0
                    ? parameter : parameter.substring(0, equals));
            if (!"cid".equals(name))
                continue;
            if (equals < 0)
                return null;
            String candidate = normalizeZohoContentId(
                    decodeZohoQueryComponent(parameter.substring(equals + 1)));
            if (candidate == null || (value != null && !value.equals(candidate)))
                return null;
            value = candidate;
        }
        return value;
    }

    private static String decodeZohoQueryComponent(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ex) {
            return null;
        }
    }

    static boolean hasZohoInlineImageReference(EntityMessage message,
                                                List<EntityAttachment> attachments,
                                                String source) {
        if (attachments == null)
            return false;
        for (EntityAttachment attachment : attachments)
            if (matchesZohoCidReference(message, attachment, source))
                return true;
        return false;
    }

    static boolean hasUncachedZohoInlineImage(EntityMessage message,
                                              List<EntityAttachment> attachments, String source) {
        if (attachments == null)
            return false;
        for (EntityAttachment attachment : attachments)
            if (!attachment.available && matchesZohoCidReference(message, attachment, source))
                return true;
        return false;
    }

    static boolean shouldFetchZohoCidImagesOnOpen(EntityMessage message,
                                                  List<EntityAttachment> attachments,
                                                  String source, boolean showFull,
                                                  boolean inlineImages, boolean showImages) {
        return (!showFull && inlineImages && showImages &&
                hasUncachedZohoInlineImage(message, attachments, source));
    }

    static boolean getInlineImagesPreference(EntityMessage message,
                                             boolean currentPreference, boolean adapterPreference) {
        return (message != null && hasText(message.zoho_id) ? currentPreference : adapterPreference);
    }

    static boolean shouldEmbedZohoInlineImages(EntityMessage message,
                                               boolean showImages, boolean inlineImages) {
        return (message != null && hasText(message.zoho_id) &&
                (showImages || inlineImages));
    }

    static String rewriteZohoImageDisplaySource(EntityMessage message, EntityAttachment attachment,
                                                 String source, String dataUri,
                                                 boolean showImages, boolean inlineImages) {
        return rewriteZohoImageDisplaySource(message, attachment, source, dataUri,
                showImages, inlineImages, false);
    }

    static String rewriteZohoImageDisplaySource(EntityMessage message, EntityAttachment attachment,
                                                String source, String dataUri,
                                                boolean showImages, boolean inlineImages,
                                                boolean compactTextView) {
        if (!shouldEmbedZohoInlineImages(message, showImages, inlineImages) ||
                attachment == null ||
                getZohoImageDisplayCid(source) == null ||
                !matchesZohoCidReference(message, attachment, source))
            return source;
        if (compactTextView) {
            String cid = getZohoImageDisplayCid(source);
            return "cid:" + cid.substring(1, cid.length() - 1);
        }
        if (!attachment.available || !hasText(dataUri))
            return source;
        return dataUri;
    }

    static boolean shouldWakeForZohoCidImageRequest(EntityMessage message,
                                                    EntityAttachment attachment) {
        return (isZohoApiCidImage(message, attachment) && !attachment.available &&
                attachment.progress == null);
    }

    static boolean isGmailApiCidImage(EntityMessage message, EntityAttachment attachment) {
        return (message != null && message.gmail_id != null && !message.gmail_id.isEmpty() &&
                attachment != null && attachment.cid != null && !attachment.cid.isEmpty() &&
                attachment.provider_id != null && !attachment.provider_id.isEmpty() &&
                attachment.type != null && attachment.type.regionMatches(true, 0, "image/", 0, 6) &&
                (GmailApiMessageParser.ATTACHMENT_ID.equals(attachment.provider_type) ||
                        NativeApiContent.GMAIL_INLINE_PART.equals(attachment.provider_type)));
    }

    static boolean shouldWakeForGmailCidImageRequest(EntityMessage message, EntityAttachment attachment) {
        return (isGmailApiCidImage(message, attachment) && !attachment.available &&
                attachment.progress == null);
    }

    static boolean isAvailableGmailCidImage(EntityMessage message, EntityAttachment attachment) {
        return (attachment != null && attachment.available &&
                isGmailApiCidImage(message, attachment));
    }

    static boolean hasUncachedGmailInlineImage(EntityMessage message,
                                               List<EntityAttachment> attachments,
                                               String source) {
        if (attachments == null || source == null || !source.startsWith("cid:"))
            return false;

        String cid = source.substring(4);
        if (!cid.startsWith("<"))
            cid = "<" + cid;
        if (!cid.endsWith(">"))
            cid += ">";

        for (EntityAttachment attachment : attachments)
            if (isGmailApiCidImage(message, attachment) && !attachment.available &&
                    cid.equals(attachment.cid))
                return true;
        return false;
    }

    static boolean hasPending(DB db, long accountId) {
        EntityAccount account = db.account().getAccount(accountId);
        if (!supportsAccount(account))
            return false;
        return hasPending(db.operation().getOperations(accountId));
    }

    static boolean hasPending(List<EntityOperation> operations) {
        if (operations == null)
            return false;
        for (EntityOperation operation : operations)
            if ((isContentOperation(operation) || isRawExportOperation(operation)) && operation.error == null)
                return true;
        return false;
    }

    static void process(Context context, DB db, EntityAccount account, Core.State state,
                        GmailApi gmail, ZohoApi zoho, GraphMailApi graph)
            throws OperationCanceledException {
        if (!supportsAccount(account) ||
                (gmail != null && !account.isGmailApi()) ||
                (zoho != null && !account.isZohoApi()) ||
                (graph != null && !account.isGraphApi()) ||
                (gmail == null && zoho == null && graph == null))
            return;
        for (EntityOperation operation : db.operation().getOperations(account.id)) {
            boolean rawExport = isRawExportOperation(operation);
            if ((!isContentOperation(operation) && !rawExport) || operation.error != null)
                continue;
            state.ensureRunning("Native REST content operation");
            db.operation().setOperationState(operation.id, "executing");
            db.operation().setOperationError(operation.id, null);
            EntityMessage message = (operation.message == null
                    ? null : db.message().getMessage(operation.message));
            EntityAttachment attachment = null;
            File temporary = null;
            boolean bodyOperation = EntityOperation.BODY.equals(operation.name);
            BodyMetrics bodyMetrics = null;
            boolean gmailCidAttachment = false;
            long gmailCidBytes = -1;
            boolean gmailCidCacheHit = false;
            String graphBodyTrace = (bodyOperation && account.isGraphApi()
                    ? ApiMutationLog.safeTraceId(ApiMutationLog.traceFromArgs(operation.args)) : null);
            ApiMutationLog.OperationContext previousTrace = null;
            if (graphBodyTrace != null) {
                previousTrace = ApiMutationLog.setCurrent("graph", "body", graphBodyTrace);
                ApiMutationLog.event("graph", "body_fetch", "body", "started", graphBodyTrace);
            }
            if (bodyOperation || EntityOperation.ATTACHMENT.equals(operation.name))
                NativeApiDiagnostics.logContentOperationEvent(context, providerFor(account),
                        (bodyOperation ? "body" : "attachment"), "started");
            try {
                if (message == null || message.account != account.id)
                    throw new IOException("Message no longer belongs to this native API account");
                if (rawExport) {
                    File destination = message.getRawFile(context);
                    boolean cached = isCompleteRawCache(message.raw, destination);
                    if (!cached)
                        db.message().setMessageRaw(message.id, false);
                    ensureRawFile(cached, destination, new RawMessageFetcher() {
                        @Override
                        public void fetch(File temporary) throws IOException, JSONException {
                            state.ensureRunning("Native REST complete MIME export");
                            if (message.gmail_id != null && gmail != null) {
                                writeRawBytes(temporary, gmail.getRawMessage(message.gmail_id));
                            } else if (message.zoho_id != null && zoho != null) {
                                writeRawBytes(temporary, zoho.getOriginalMessage(message.zoho_id));
                            } else if (message.graph_id != null && graph != null) {
                                graph.downloadOriginalMessage(message.graph_id, temporary);
                                syncFile(temporary);
                            } else
                                throw new IOException("Native message provider identity is unavailable for complete MIME export");
                        }
                    });
                    db.message().setMessageRaw(message.id, true);
                } else if (bodyOperation) {
                    bodyMetrics = fetchBody(context, db, message, operation.args,
                            gmail, zoho, graph, state);
                } else {
                    Long attachmentId = getAttachmentId(operation.args);
                    if (attachmentId == null)
                        throw new IOException("Attachment operation has no attachment ID");
                    attachment = db.attachment().getAttachment(attachmentId);
                    if (attachment == null || !message.id.equals(attachment.message))
                        throw new IOException("Attachment no longer belongs to this message");
                    gmailCidAttachment = (!attachment.available &&
                            isGmailApiCidImage(message, attachment));
                    if (!attachment.available) {
                        boolean graphRawAttachment = (message.graph_id != null && graph != null &&
                                GraphMailApi.isRawContentAttachmentType(attachment.provider_type));
                        if (gmailCidAttachment)
                            NativeApiDiagnostics.logGmailCidWorkerStarted(context);
                        File destination = attachment.getFile(context);
                        if (reuseOrRemoveInvalidAttachmentCache(destination, attachment.size,
                                graphRawAttachment)) {
                            db.attachment().setDownloaded(attachment.id, destination.length());
                            gmailCidBytes = destination.length();
                            gmailCidCacheHit = true;
                            NativeApiDiagnostics.logAttachmentComplete(context, providerFor(account),
                                    destination.length(), true);
                            db.operation().deleteOperation(operation.id);
                            if (gmailCidAttachment)
                                NativeApiDiagnostics.logGmailCidFetchComplete(context, true,
                                        gmailCidBytes, gmailCidCacheHit);
                            continue;
                        }
                        File parent = destination.getParentFile();
                        if (parent == null || (!parent.exists() && !parent.mkdirs()))
                            throw new IOException("Unable to create the attachment cache directory");
                        db.attachment().setProgress(attachment.id, 0);
                        temporary = File.createTempFile("native-attachment-", ".tmp", parent);
                        downloadSelectedAttachment(context, message, attachment,
                                gmail, zoho, graph, temporary, state);
                        verifyAttachmentLength(temporary, attachment.size, graphRawAttachment);
                        installAtomically(temporary, destination, false);
                        temporary = null;
                        db.attachment().setDownloaded(attachment.id, destination.length());
                        NativeApiDiagnostics.logAttachmentComplete(context, providerFor(account),
                                destination.length(), false);
                        if (gmailCidAttachment)
                            gmailCidBytes = destination.length();
                    }
                }
                db.operation().deleteOperation(operation.id);
                if (bodyOperation && bodyMetrics != null)
                    NativeApiDiagnostics.logBody(context, providerFor(account), true,
                            bodyMetrics.sourceLength, bodyMetrics.renderedChars,
                            bodyMetrics.cacheReused);
                if (graphBodyTrace != null)
                    ApiMutationLog.event("graph", "body_fetch", "body", "success", graphBodyTrace);
                if (gmailCidAttachment)
                    NativeApiDiagnostics.logGmailCidFetchComplete(context, true,
                            gmailCidBytes, gmailCidCacheHit);
            } catch (OperationCanceledException ex) {
                if (temporary != null && temporary.exists() && !temporary.delete())
                    Log.w("Native attachment temporary-file cleanup failed: " + temporary);
                if (attachment != null)
                    db.attachment().setProgress(attachment.id, null);
                if (bodyOperation || EntityOperation.ATTACHMENT.equals(operation.name))
                    NativeApiDiagnostics.logContentOperationFailure(context, providerFor(account),
                            (bodyOperation ? "body" : "attachment"), ex);
                if (bodyOperation)
                    NativeApiDiagnostics.logBody(context, providerFor(account), false,
                            -1, -1, false);
                if (gmailCidAttachment)
                    NativeApiDiagnostics.logGmailCidFetchComplete(context, false, -1, false);
                if (graphBodyTrace != null)
                    ApiMutationLog.failure("graph", "body_fetch", "body", "failure", ex, graphBodyTrace);
                throw ex;
            } catch (Throwable ex) {
                if (bodyOperation || EntityOperation.ATTACHMENT.equals(operation.name))
                    NativeApiDiagnostics.logContentOperationFailure(context, providerFor(account),
                            (bodyOperation ? "body" : "attachment"), ex);
                if (bodyOperation)
                    NativeApiDiagnostics.logBody(context, providerFor(account), false,
                            -1, -1, false);
                if (gmailCidAttachment)
                    NativeApiDiagnostics.logGmailCidFetchComplete(context, false, -1, false);
                if (graphBodyTrace != null)
                    ApiMutationLog.failure("graph", "body_fetch", "body", "failure", ex, graphBodyTrace);
                String error = Log.formatThrowable(ex, false);
                handleFailedFetch(temporary, (attachment == null ? null : attachment.id),
                        operation.id, error, new FailureHandler() {
                            @Override
                            public void setAttachmentError(long attachmentId, String value) {
                                db.attachment().setError(attachmentId, value);
                            }

                            @Override
                            public void logFailure(long operationId, String value) {
                                EntityLog.log(context, EntityLog.Type.General, message,
                                        "Native REST " + operation.name + " failed; user may retry: " + value);
                            }

                            @Override
                            public void deleteOperation(long operationId) {
                                db.operation().deleteOperation(operationId);
                            }
                        });
            } finally {
                if (previousTrace != null)
                    ApiMutationLog.restoreCurrent(previousTrace);
            }
        }
    }

    private static BodyMetrics fetchBody(Context context, DB db, EntityMessage message, String arguments,
                                         GmailApi gmail, ZohoApi zoho, GraphMailApi graph,
                                         Core.State state)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        JSONArray bodyArguments = new JSONArray(TextUtils.isEmpty(arguments) ? "[]" : arguments);
        String graphTrace = (graph != null && message.graph_id != null
                ? ApiMutationLog.safeTraceId(ApiMutationLog.traceFromArgs(arguments)) : null);
        boolean plainText = bodyArguments.optBoolean(0,
                preferences.getBoolean("download_plain", false));
        String charset = (bodyArguments.isNull(1) ? null : bodyArguments.optString(1, null));
        if (message.content && message.isPlainOnly() == plainText && charset == null) {
            if (message.zoho_id != null && zoho != null) {
                File cache = message.getFile(context);
                ZohoApiDiagnostics.logBodyCache(context, 0, "reused",
                        (cache.isFile() ? cache.length() : -1), true,
                        !TextUtils.isEmpty(message.warning));
                ZohoApiDiagnostics.logWarningSources(context, 0, "cache_reused",
                        0, 0, !TextUtils.isEmpty(message.warning));
            }
            return new BodyMetrics(-1, 0, true);
        }
        state.ensureRunning("Native REST message body request");
        MimeMessage mime;
        long sourceLength = -1;
        long zohoTraceSequence = -1;
        String zohoInputKind = "unknown";
        String zohoBodyType = null;
        String zohoBodyContent = null;
        int zohoInlineResourceCount = 0;
        int zohoInlineImageCount = 0;
        if (message.gmail_id != null && gmail != null) {
            GmailApi.ReceivedMessage received = gmail.getMessage(message.gmail_id, true);
            if (!message.gmail_id.equals(received.id))
                throw new IOException("Gmail body response returned an inconsistent message ID");
            NativeApiDiagnostics.logGmailMime(context, "body_open", received);
            if (!received.bodyIncluded)
                throw new IOException("Gmail body response was incomplete");
            mime = received.mimeMessage;
            sourceLength = mime.getSize();
        } else if (message.zoho_id != null && zoho != null) {
            String folderId = getZohoFolderId(db, message);
            String body = zoho.getMessageContent(folderId, message.zoho_id);
            sourceLength = body.length();
            zohoTraceSequence = zoho.getLastContentRequestSequence();
            zohoInputKind = ZohoContentNormalizer.sourceKind(body);
            ZohoApiDiagnostics.logBodyStructure(context, zohoTraceSequence, "normalizer_input",
                    ZohoContentNormalizer.hasMultipartBoundary(body),
                    ZohoContentNormalizer.countImageDisplayCidReferences(body),
                    ZohoContentNormalizer.countCidReferences(body));
            ZohoContentNormalizer.Body normalized;
            try {
                normalized = ZohoContentNormalizer.normalize(body);
            } catch (IOException ex) {
                ZohoApiDiagnostics.logNormalization(context, zohoTraceSequence, zohoInputKind,
                        sourceLength, null, 0, false, "unknown", 0, 0, 0, 0, 0);
                throw ex;
            }
            zohoBodyType = normalized.contentType;
            zohoBodyContent = normalized.content;
            zohoInlineResourceCount = normalized.inlineResources;
            zohoInlineImageCount = normalized.inlineImages;
            ZohoApiDiagnostics.logNormalization(context, zohoTraceSequence, zohoInputKind,
                    sourceLength, normalized.contentType, normalized.content.length(), true,
                    normalized.mimePath, normalized.textPlainParts, normalized.textHtmlParts,
                    normalized.inlineResources, normalized.inlineImages, normalized.cidReferences);
            ZohoApiDiagnostics.logBodyStructure(context, zohoTraceSequence, "normalizer_output",
                    ZohoContentNormalizer.hasMultipartBoundary(normalized.content),
                    ZohoContentNormalizer.countImageDisplayCidReferences(normalized.content),
                    normalized.cidReferences);
            mime = NativeApiContent.createMessage(
                    Session.getInstance(MessageHelper.getSessionProperties(true), null),
                    null, null, normalized.contentType, normalized.content,
                    new ArrayList<NativeApiContent.Attachment>());
        } else if (message.graph_id != null && graph != null) {
            GraphMailApi.MessageDetails details = graph.getMessage(message.graph_id, true);
            if (!message.graph_id.equals(details.id))
                throw new IOException("Microsoft Graph body response returned an inconsistent message ID");
            sourceLength = (details.body == null ? 0 : details.body.length());
            if (graphTrace != null)
                ApiMutationLog.graphBodyResult(graphTrace, details.body != null, sourceLength);
            String type = ("text".equalsIgnoreCase(details.bodyType)
                    ? "text/plain; charset=UTF-8" : "text/html; charset=UTF-8");
            mime = NativeApiContent.createMessage(
                    Session.getInstance(MessageHelper.getSessionProperties(true), null),
                    null, details.headers, type,
                    (details.body == null ? "" : details.body),
                    new ArrayList<NativeApiContent.Attachment>());
        } else
            throw new IOException("Native message provider identity is unavailable");

        state.ensureRunning("Native REST body response");
        if (zohoTraceSequence >= 0)
            ZohoApiDiagnostics.logExpansion(context, zohoTraceSequence, "prepared",
                    zohoInputKind, zohoBodyType, mime.getContentType(), 0,
                    zohoInlineResourceCount, zohoInlineImageCount, 0, -1);
        if (zohoTraceSequence >= 0)
            ZohoApiDiagnostics.logBodyStructure(context, zohoTraceSequence, "renderer_input",
                    ZohoContentNormalizer.hasMultipartBoundary(zohoBodyContent),
                    ZohoContentNormalizer.countImageDisplayCidReferences(zohoBodyContent),
                    ZohoContentNormalizer.countCidReferences(zohoBodyContent));
        MessageHelper helper = new MessageHelper(mime, context);
        MessageHelper.MessageParts parts;
        String body;
        try {
            parts = helper.getMessageParts();
            body = parts.getHtml(context, plainText, charset);
        } catch (IOException | MessagingException | RuntimeException ex) {
            if (zohoTraceSequence >= 0)
                ZohoApiDiagnostics.logExpansion(context, zohoTraceSequence, "parts_failed",
                        zohoInputKind, zohoBodyType, mime.getContentType(), 0,
                        zohoInlineResourceCount, zohoInlineImageCount, 0, -1);
            throw ex;
        }
        long renderedChars = (body == null ? 0 : body.length());
        String text = HtmlHelper.getFullText(context, body);
        String preview = HtmlHelper.getPreview(text);
        Integer rendererPlainOnly = parts.isPlainOnly();
        Integer plainOnly = rendererPlainOnly;
        if (plainText)
            plainOnly = 1 | (plainOnly == null ? 0 : plainOnly & 0x80);
        int generatedWarningCount = parts.getDiagnosticWarningCount();
        int generatedFallbackCount = parts.getDiagnosticPlainMultipartFallbackCount();
        boolean savedWarningPresent = !TextUtils.isEmpty(message.warning);
        if (zohoTraceSequence >= 0) {
            ZohoApiDiagnostics.logWarningSources(context, zohoTraceSequence, "rendered",
                    generatedWarningCount, generatedFallbackCount, savedWarningPresent);
            ZohoApiDiagnostics.logRenderer(context, zohoTraceSequence,
                    (zohoBodyType != null && zohoBodyType.regionMatches(true, 0, "text/html", 0, 9)),
                    (rendererPlainOnly != null && (rendererPlainOnly & 1) == 0),
                    ZohoContentNormalizer.hasXPlainWrapper(body), generatedFallbackCount);
            ZohoApiDiagnostics.logBodyStructure(context, zohoTraceSequence, "renderer_output",
                    ZohoContentNormalizer.hasMultipartBoundary(body),
                    ZohoContentNormalizer.countImageDisplayCidReferences(body),
                    ZohoContentNormalizer.countCidReferences(body));
        }
        String warning = parts.getWarnings(message.warning);
        if (zohoTraceSequence >= 0)
            ZohoApiDiagnostics.logExpansion(context, zohoTraceSequence, "body_rendered",
                    zohoInputKind, zohoBodyType, mime.getContentType(), 0,
                    zohoInlineResourceCount, zohoInlineImageCount,
                    (warning == null ? 0 : 1), renderedChars);

        File destination = message.getFile(context);
        File parent = destination.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs()))
            throw new IOException("Unable to create the message body cache directory");
        File temporary = File.createTempFile("native-body-", ".tmp", parent);
        try {
            writeUtf8(temporary, (body == null ? "" : body));
            installAtomically(temporary, destination);
            temporary = null;
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete())
                Log.w("Native message-body temporary-file cleanup failed: " + temporary);
        }
        db.message().setMessageContent(message.id, true,
                HtmlHelper.getLanguage(context, message.subject, text), plainOnly,
                preview, warning);
        if (zohoTraceSequence >= 0)
            ZohoApiDiagnostics.logBodyCache(context, zohoTraceSequence, "persisted",
                    (destination.isFile() ? destination.length() : -1), true,
                    !TextUtils.isEmpty(warning));
        EntityFolder classifierFolder = db.folder().getFolder(message.folder);
        boolean autoClassifySource = (classifierFolder != null && classifierFolder.auto_classify_source);
        boolean movePendingBefore = (db.operation().getOperationCount(
                message.folder, message.id, EntityOperation.MOVE) > 0);
        if (graphTrace != null)
            ApiMutationLog.event("graph", "classification", "classify", "started", graphTrace);
        MessageClassifier.classify(message, classifierFolder, true, context);
        boolean movePendingAfter = (db.operation().getOperationCount(
                message.folder, message.id, EntityOperation.MOVE) > 0);
        if (graphTrace != null)
            ApiMutationLog.graphClassification(graphTrace, autoClassifySource,
                    movePendingBefore, movePendingAfter);
        return new BodyMetrics(sourceLength, renderedChars, false);
    }

    static String providerFor(EntityAccount account) {
        if (account == null)
            return "unknown";
        if (account.isGmailApi())
            return "gmail";
        if (account.isZohoApi())
            return "zoho";
        if (account.isGraphApi())
            return "graph";
        return "unknown";
    }

    private static void downloadSelectedAttachment(Context context, EntityMessage message,
                                                   EntityAttachment attachment,
                                                   GmailApi gmail, ZohoApi zoho, GraphMailApi graph,
                                                   File temporary, Core.State state)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        state.ensureRunning("Native REST selected attachment request");
        if (message.gmail_id != null && gmail != null) {
            if (GmailApiMessageParser.ATTACHMENT_ID.equals(attachment.provider_type)) {
                byte[] bytes = gmail.getAttachmentBytes(message.gmail_id, attachment.provider_id);
                try (OutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
                    output.write(bytes);
                    output.flush();
                }
            } else if (NativeApiContent.GMAIL_INLINE_PART.equals(attachment.provider_type)) {
                GmailApi.ReceivedMessage received = gmail.getMessage(message.gmail_id, true);
                MessageHelper helper = new MessageHelper(received.mimeMessage, context);
                MessageHelper.MessageParts parts = helper.getMessageParts();
                boolean found = false;
                for (MessageHelper.AttachmentPart part : parts.getAttachmentParts()) {
                    String partId = NativeApiContent.getInternalHeader(part.part,
                            NativeApiContent.HEADER_GMAIL_PART_ID);
                    if (attachment.provider_id.equals(partId)) {
                        try (InputStream input = new BufferedInputStream(part.part.getInputStream())) {
                            copyToFile(input, temporary, MAX_CACHE_BYTES);
                        }
                        found = true;
                        break;
                    }
                }
                if (!found)
                    throw new IOException("Gmail inline MIME part is no longer present");
            } else
                throw new IOException("Gmail attachment has no retrievable attachmentId");
        } else if (message.zoho_id != null && zoho != null) {
            String folderId = (TextUtils.isEmpty(attachment.provider_folder)
                    ? getZohoFolderId(null, message) : attachment.provider_folder);
            if (usesZohoInlineImageEndpoint(attachment))
                zoho.downloadInlineImage(folderId, message.zoho_id, attachment.cid,
                        attachment.name, temporary, attachment);
            else
                zoho.downloadAttachment(folderId, message.zoho_id, attachment.provider_id, temporary);
        } else if (message.graph_id != null && graph != null) {
            String type = (attachment.provider_type == null ? "" : attachment.provider_type);
            if (type.toLowerCase(java.util.Locale.ROOT).contains("referenceattachment"))
                throw new IOException("Microsoft Graph reference attachments are cloud links and cannot be downloaded as local files");
            graph.downloadAttachment(message.graph_id, attachment.provider_id, temporary);
        } else
            throw new IOException("Native attachment provider identity is unavailable");
        state.ensureRunning("Native REST selected attachment completion");
    }

    static String getZohoFolderId(DB db, EntityMessage message) throws IOException {
        if (db != null) {
            EntityFolder folder = db.folder().getFolder(message.folder);
            if (folder != null && !TextUtils.isEmpty(folder.zoho_folder_id))
                return folder.zoho_folder_id;
        }
        if (!TextUtils.isEmpty(message.zoho_folder_ids)) {
            String[] ids = message.zoho_folder_ids.split("\\|");
            for (String id : ids)
                if (ZohoApi.isValidRemoteId(id))
                    return id;
        }
        throw new IOException("Zoho message no longer has a remote folder identity");
    }

    private static Long getAttachmentId(String args) throws JSONException {
        if (args == null)
            return null;
        JSONArray values = new JSONArray(args);
        return (values.length() > 0 && !values.isNull(0) ? values.getLong(0) : null);
    }

    static boolean isCompleteRawCache(Boolean available, File file) {
        return (Boolean.TRUE.equals(available) && file != null && file.isFile() &&
                file.length() > 0 && file.length() <= MAX_CACHE_BYTES);
    }

    static boolean ensureRawFile(boolean available, File destination, RawMessageFetcher fetcher)
            throws IOException, JSONException {
        if (isCompleteRawCache(available, destination))
            return false;
        if (destination == null)
            throw new IOException("Missing native raw-message cache destination");
        File parent = destination.getParentFile();
        if (parent == null || (!parent.exists() && !parent.mkdirs()))
            throw new IOException("Unable to create the raw-message cache directory");
        if (destination.exists() && !destination.delete())
            throw new IOException("Unable to remove an incomplete raw-message cache file");
        File temporary = File.createTempFile("native-raw-", ".tmp", parent);
        try {
            fetcher.fetch(temporary);
            verifyRawMessage(temporary);
            syncFile(temporary);
            installAtomically(temporary, destination, false);
            temporary = null;
            return true;
        } finally {
            if (temporary != null && temporary.exists() && !temporary.delete())
                Log.w("Native raw-message temporary-file cleanup failed: " + temporary);
        }
    }

    private static void writeRawBytes(File destination, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0)
            throw new IOException("Native provider returned an empty original message");
        if (bytes.length > MAX_CACHE_BYTES)
            throw new IOException("Native original message exceeds the local size limit");
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
    }

    private static void verifyRawMessage(File file) throws IOException {
        if (file == null || !file.isFile() || file.length() == 0)
            throw new IOException("Native provider returned an incomplete original message");
        if (file.length() > MAX_CACHE_BYTES)
            throw new IOException("Native original message exceeds the local size limit");
    }

    private static void syncFile(File file) throws IOException {
        if (file == null || !file.isFile())
            throw new IOException("Native provider did not create a MIME file");
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.getFD().sync();
        }
    }

    static void handleFailedFetch(File temporary, Long attachmentId, long operationId,
                                  String error, FailureHandler handler) {
        if (temporary != null && temporary.exists() && !temporary.delete())
            Log.w("Native attachment temporary-file cleanup failed: " + temporary);
        if (attachmentId != null)
            handler.setAttachmentError(attachmentId, error);
        handler.logFailure(operationId, error);
        // A failed user request must not hold sync cursors or auto-retry in a loop.
        // The message/attachment remains unavailable and the UI can enqueue a fresh request.
        handler.deleteOperation(operationId);
    }

    static void verifyLength(File file, Long expected) throws IOException {
        verifyAttachmentFile(file);
        if (expected != null && expected >= 0 && file.length() != expected)
            throw new IOException("Native attachment response length did not match provider metadata");
    }

    static void verifyAttachmentLength(File file, Long expected, boolean graphRawAttachment)
            throws IOException {
        if (!graphRawAttachment) {
            verifyLength(file, expected);
            return;
        }
        verifyAttachmentFile(file);
        if (file.length() == 0)
            throw new IOException("Microsoft Graph returned an empty attachment response");
        // Graph's attachment size includes provider metadata for some fileAttachments; for
        // itemAttachments, $value is a MIME serialization. Neither size is an exact raw-byte limit.
    }

    private static void verifyAttachmentFile(File file) throws IOException {
        if (!file.isFile())
            throw new IOException("Native attachment response did not create a cache file");
        if (file.length() > MAX_CACHE_BYTES)
            throw new IOException("Native attachment exceeds the local cache size limit");
    }

    static boolean reuseOrRemoveInvalidAttachmentCache(File file, Long expected) throws IOException {
        return reuseOrRemoveInvalidAttachmentCache(file, expected, false);
    }

    static boolean reuseOrRemoveInvalidAttachmentCache(File file, Long expected,
                                                        boolean graphRawAttachment) throws IOException {
        if (file == null || !file.exists())
            return false;
        try {
            verifyAttachmentLength(file, expected, graphRawAttachment);
            return true;
        } catch (IOException ex) {
            // A stale/partial final file can survive older non-atomic cache writes. Leaving it
            // in place makes every later user-requested download fail before reaching the API.
            if (!file.delete())
                throw new IOException("Unable to remove an invalid native attachment cache", ex);
            return false;
        }
    }

    private static void installAtomically(File temporary, File destination) throws IOException {
        installAtomically(temporary, destination, true);
    }

    static void installAtomically(File temporary, File destination, boolean replace)
            throws IOException {
        if (temporary == null || !temporary.isFile())
            throw new IOException("Native content response is incomplete");
        if (!replace && destination.exists())
            throw new IOException("An existing native attachment cache file will not be overwritten");
        if (!temporary.renameTo(destination))
            throw new IOException("Unable to atomically install native content in the cache");
    }

    private static void writeUtf8(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    private static void copyToFile(InputStream input, File destination, long maximum)
            throws IOException {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            byte[] buffer = new byte[8192];
            long total = 0;
            for (int length = input.read(buffer); length != -1; length = input.read(buffer)) {
                total += length;
                if (total > maximum)
                    throw new IOException("Native content exceeds the local cache size limit");
                output.write(buffer, 0, length);
            }
            output.flush();
        }
        try (FileOutputStream sync = new FileOutputStream(destination, true)) {
            sync.getFD().sync();
        }
    }
}

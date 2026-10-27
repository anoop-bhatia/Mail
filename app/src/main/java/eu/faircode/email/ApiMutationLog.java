package eu.faircode.email;

import android.os.OperationCanceledException;

import org.json.JSONException;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Safe, provider-tagged diagnostics persisted only to the Protocol log when enabled. */
final class ApiMutationLog {
    interface ProtocolSink {
        void write(String event);
    }

    static final class OperationContext {
        final String provider;
        final String operation;
        final String traceId;

        OperationContext(String provider, String operation, String traceId) {
            this.provider = provider;
            this.operation = operation;
            this.traceId = traceId;
        }
    }

    private static final Set<String> PROVIDERS = new HashSet<>(Arrays.asList("gmail", "graph", "zoho"));
    private static final Set<String> STAGES = new HashSet<>(Arrays.asList(
            "picker_callback", "queue_insert", "durable_queue", "worker_start", "worker_validation",
            "http", "readback_reconcile", "local_commit", "refresh_summary", "operation_summary",
            "failure_summary", "action_gate", "message_open", "body_fetch", "classification",
            "navigation"));
    private static final Set<String> OPERATIONS = new HashSet<>(Arrays.asList(
            "seen", "flag", "move", "delete", "label", "keyword", "permanent_delete", "empty_trash",
            "refresh", "open", "body", "classify", "navigate"));
    private static final Set<String> TARGET_TYPES = new HashSet<>(Arrays.asList(
            "trash", "inbox", "label", "archive", "folder", "other"));
    private static final Set<String> RESULTS = new HashSet<>(Arrays.asList(
            "accepted", "queued", "stored", "started", "success", "failure", "refused",
            "not_queued", "not_inserted", "not_writable", "invalid_target", "copy_not_supported",
            "message_missing", "account_missing", "account_mismatch", "source_missing",
            "source_folder_missing", "destination_folder_missing", "source_view_missing",
            "source_view_mismatch", "not_sent", "rate_limited", "response_read_failed",
            "satisfied", "not_satisfied", "reconciled", "unavailable", "unknown",
            "confirmed", "rejected", "scheduled", "skipped", "observed", "already_executing",
            "already_pending",
            "eligible", "not_writable", "visible_enabled", "hidden", "opened", "reappeared", "body_missing",
            "body_complete", "auto_seen_enabled", "auto_seen_disabled", "move_queued", "no_move",
            "paused", "auto_closed", "navigated"));
    private static final Set<String> ERROR_CLASSES = new HashSet<>(Arrays.asList(
            "none", "api", "io", "json", "cancelled", "validation", "other"));
    private static final Pattern TRACE_ID = Pattern.compile(
            "(?i)[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}");
    private static final ThreadLocal<OperationContext> CURRENT = new ThreadLocal<>();

    private ApiMutationLog() {
    }

    static String provider(EntityAccount account) {
        if (account == null)
            return null;
        if (account.isGmailApi())
            return "gmail";
        if (account.isGraphApi())
            return "graph";
        if (account.isZohoApi())
            return "zoho";
        return null;
    }

    static String targetType(String folderType) {
        if (EntityFolder.TRASH.equals(folderType))
            return "trash";
        if (EntityFolder.INBOX.equals(folderType))
            return "inbox";
        if (EntityFolder.USER.equals(folderType))
            return "label";
        if (EntityFolder.ARCHIVE.equals(folderType))
            return "archive";
        return (folderType == null ? "other" : "folder");
    }

    static String errorClass(Throwable failure) {
        if (failure == null)
            return "none";
        if (failure instanceof GraphMailApi.ApiException || failure instanceof ZohoApi.ApiException ||
                failure instanceof GmailApi.ApiException)
            return "api";
        if (failure instanceof OperationCanceledException)
            return "cancelled";
        if (failure instanceof JSONException)
            return "json";
        if (failure instanceof IllegalArgumentException)
            return "validation";
        if (failure instanceof IOException)
            return "io";
        return "other";
    }

    static String formatEvent(String provider, String stage, String operation, String result) {
        return formatEvent(provider, stage, operation, result, null);
    }

    static String formatEvent(String provider, String stage, String operation, String result,
                              String traceId) {
        String safeProvider = safeProvider(provider);
        String event = providerLabel(safeProvider) + " mutation stage=" + safeStage(stage) +
                " provider=" + safeProvider + " operation=" + safeOperation(operation) +
                " result=" + safeResult(result);
        String safeTrace = safeTraceId(traceId);
        return (safeTrace == null ? event : event + " trace=" + safeTrace);
    }

    static String formatCallback(String provider, String operation, String targetType, String result) {
        return formatCallback(provider, operation, targetType, result, null);
    }

    static String formatCallback(String provider, String operation, String targetType, String result,
                                 String traceId) {
        String safeProvider = safeProvider(provider);
        String event = providerLabel(safeProvider) + " mutation stage=picker_callback provider=" + safeProvider +
                " operation=" + safeOperation(operation) + " target_type=" + safeTarget(targetType) +
                " result=" + safeResult(result);
        String safeTrace = safeTraceId(traceId);
        return (safeTrace == null ? event : event + " trace=" + safeTrace);
    }

    static String formatHttp(String provider, String operation, int status, String result, String errorClass) {
        return formatHttp(provider, operation, status, result, errorClass, null);
    }

    static String formatHttp(String provider, String operation, int status, String result, String errorClass,
                             String traceId) {
        String safeProvider = safeProvider(provider);
        int safeStatus = (status >= 100 && status <= 599 ? status : -1);
        String safeError = (ERROR_CLASSES.contains(errorClass) ? errorClass : "other");
        String event = providerLabel(safeProvider) + " mutation stage=http provider=" + safeProvider +
                " operation=" + safeOperation(operation) + " status=" + safeStatus +
                " result=" + safeResult(result) + " error_class=" + safeError;
        String safeTrace = safeTraceId(traceId);
        return (safeTrace == null ? event : event + " trace=" + safeTrace);
    }

    static String formatFailure(String provider, String stage, String operation,
                                String result, Throwable failure) {
        return formatFailure(provider, stage, operation, result, failure, null);
    }

    static String formatFailure(String provider, String stage, String operation,
                                String result, Throwable failure, String traceId) {
        return formatEvent(provider, stage, operation, result, traceId) +
                " error_class=" + errorClass(failure);
    }

    static void event(String provider, String stage, String operation, String result) {
        event(provider, stage, operation, result, null);
    }

    static void event(String provider, String stage, String operation, String result, String traceId) {
        write(formatEvent(provider, stage, operation, result, resolveTrace(provider, traceId)));
    }

    static void callback(String provider, String operation, String targetType, String result) {
        callback(provider, operation, targetType, result, null);
    }

    static void callback(String provider, String operation, String targetType, String result, String traceId) {
        write(formatCallback(provider, operation, targetType, result, resolveTrace(provider, traceId)));
    }

    static void http(String provider, String operation, int status, String result, String errorClass) {
        http(provider, operation, status, result, errorClass, null);
    }

    static void http(String provider, String operation, int status, String result, String errorClass,
                     String traceId) {
        write(formatHttp(provider, operation, status, result, errorClass, resolveTrace(provider, traceId)));
    }

    static void failure(String provider, String stage, String operation, String result, Throwable failure) {
        failure(provider, stage, operation, result, failure, null);
    }

    static void failure(String provider, String stage, String operation, String result,
                        Throwable failure, String traceId) {
        write(formatFailure(provider, stage, operation, result, failure,
                resolveTrace(provider, traceId)));
    }

    static void httpCurrent(int status, String result, String errorClass) {
        OperationContext context = CURRENT.get();
        if (context != null)
            http(context.provider, context.operation, status, result, errorClass, context.traceId);
    }

    static void zohoDeleteRequest(boolean expunge, boolean folderIdPresent, boolean messageIdPresent) {
        write(formatZohoDeleteRequest(expunge, folderIdPresent, messageIdPresent, currentTraceId()));
    }

    static String formatZohoDeleteRequest(boolean expunge, boolean folderIdPresent,
                                          boolean messageIdPresent, String traceId) {
        String operation = (expunge ? EntityOperation.ZOHO_PERMANENT_DELETE : EntityOperation.DELETE);
        return formatEvent("zoho", "http", operation, "started", traceId) +
                " expunge=" + expunge + " folder_id_present=" + folderIdPresent +
                " message_id_present=" + messageIdPresent;
    }

    static OperationContext setCurrent(String provider, String operation) {
        return setCurrent(provider, operation, null);
    }

    static OperationContext setCurrent(String provider, String operation, String traceId) {
        OperationContext previous = CURRENT.get();
        String safeProvider = safeProvider(provider);
        String safeOperation = safeOperation(operation);
        if ("other".equals(safeProvider) || "other".equals(safeOperation))
            CURRENT.remove();
        else
            CURRENT.set(new OperationContext(safeProvider, safeOperation, safeTraceId(traceId)));
        return previous;
    }

    static String newTraceId() {
        return UUID.randomUUID().toString();
    }

    static String safeTraceId(String traceId) {
        return (traceId != null && TRACE_ID.matcher(traceId).matches() ? traceId.toLowerCase() : null);
    }

    static String traceFromArgs(String arguments) {
        if (arguments == null || arguments.isEmpty())
            return null;
        try {
            org.json.JSONArray values = new org.json.JSONArray(arguments);
            for (int i = 0; i < values.length(); i++) {
                Object value = values.opt(i);
                if (value instanceof String) {
                    String trace = safeTraceId((String) value);
                    if (trace != null)
                        return trace;
                }
            }
        } catch (JSONException ignored) {
        }
        return null;
    }

    static boolean operationHasTrace(EntityOperation operation, String traceId) {
        String safeTrace = safeTraceId(traceId);
        return (operation != null && safeTrace != null && safeTrace.equals(traceFromArgs(operation.args)));
    }

    static String currentTraceId() {
        OperationContext context = CURRENT.get();
        return (context == null ? null : context.traceId);
    }

    static String formatZohoDeleteState(String stage, String result, String traceId,
                                        boolean folderReadOnly, boolean folderSelectable,
                                        boolean remoteFolderIdPresent, boolean messageIdPresent,
                                        boolean sourceMembershipPresent) {
        return formatEvent("zoho", stage, EntityOperation.ZOHO_PERMANENT_DELETE, result, traceId) +
                " folder_read_only=" + folderReadOnly + " folder_selectable=" + folderSelectable +
                " remote_folder_id_present=" + remoteFolderIdPresent +
                " message_id_present=" + messageIdPresent +
                " source_membership_present=" + sourceMembershipPresent;
    }

    static String formatZohoMenuGate(String traceId, boolean menuVisible, boolean menuEnabled,
                                     boolean folderReadOnly, boolean messageIdPresent,
                                     boolean trashOrSpam) {
        String result = (menuVisible && menuEnabled ? "visible_enabled" : "hidden");
        return formatEvent("zoho", "action_gate", EntityOperation.ZOHO_PERMANENT_DELETE,
                result, traceId) + " menu_visible=" + menuVisible + " menu_enabled=" + menuEnabled +
                " folder_read_only=" + folderReadOnly + " message_id_present=" + messageIdPresent +
                " trash_or_spam=" + trashOrSpam;
    }

    static String formatGraphOpen(String traceId, String folderType, boolean unread,
                                  boolean autoSeen, boolean bodyMissing, boolean autoClassifySource) {
        String safeFolder = safeFolderType(folderType);
        return formatEvent("graph", "message_open", "open", "opened", traceId) +
                " folder_type=" + safeFolder + " unread=" + unread + " auto_seen=" + autoSeen +
                " body_missing=" + bodyMissing + " auto_classify_source=" + autoClassifySource;
    }

    static void graphOpen(String traceId, String folderType, boolean unread,
                          boolean autoSeen, boolean bodyMissing, boolean autoClassifySource) {
        write(formatGraphOpen(traceId, folderType, unread, autoSeen, bodyMissing, autoClassifySource));
    }

    static String formatGraphClassification(String traceId, boolean autoClassifySource,
                                            boolean movePendingBefore, boolean movePendingAfter) {
        String result = (!autoClassifySource ? "skipped" :
                (!movePendingBefore && movePendingAfter ? "move_queued" :
                        (movePendingBefore ? "already_pending" : "no_move")));
        return formatEvent("graph", "classification", "classify", result, traceId) +
                " auto_classify_source=" + autoClassifySource +
                " move_pending_before=" + movePendingBefore +
                " move_pending_after=" + movePendingAfter;
    }

    static void graphClassification(String traceId, boolean autoClassifySource,
                                    boolean movePendingBefore, boolean movePendingAfter) {
        write(formatGraphClassification(traceId, autoClassifySource,
                movePendingBefore, movePendingAfter));
    }

    static String formatGraphBodyResult(String traceId, boolean bodyPresent, long bodyChars) {
        return formatEvent("graph", "body_fetch", "body", "success", traceId) +
                " body_present=" + bodyPresent + " body_chars=" + Math.max(0, bodyChars);
    }

    static void graphBodyResult(String traceId, boolean bodyPresent, long bodyChars) {
        write(formatGraphBodyResult(traceId, bodyPresent, bodyChars));
    }

    private static String safeFolderType(String folderType) {
        if (EntityFolder.INBOX.equals(folderType))
            return "inbox";
        if (EntityFolder.TRASH.equals(folderType))
            return "trash";
        if (EntityFolder.JUNK.equals(folderType))
            return "junk";
        if (EntityFolder.SENT.equals(folderType))
            return "sent";
        if (EntityFolder.DRAFTS.equals(folderType))
            return "drafts";
        if (EntityFolder.ARCHIVE.equals(folderType))
            return "archive";
        return "other";
    }

    private static String resolveTrace(String provider, String traceId) {
        String safeTrace = safeTraceId(traceId);
        if (safeTrace != null)
            return safeTrace;
        OperationContext context = CURRENT.get();
        String safeProvider = safeProvider(provider);
        return (context != null && context.provider.equals(safeProvider) ? context.traceId : null);
    }

    static void restoreCurrent(OperationContext previous) {
        if (previous == null)
            CURRENT.remove();
        else
            CURRENT.set(previous);
    }

    static void write(String event) {
        try {
            route(event, Log.isProtocolLoggingEnabled(), new ProtocolSink() {
                @Override
                public void write(String safeEvent) {
                    Log.persistProtocolEvent(safeEvent);
                }
            });
        } catch (Throwable ignored) {
            // Diagnostic logging must not change provider behavior.
        }
    }

    static void route(String event, boolean protocolEnabled, ProtocolSink sink) {
        if (protocolEnabled)
            sink.write(event);
    }

    private static String safeProvider(String provider) {
        return (PROVIDERS.contains(provider) ? provider : "other");
    }

    private static String providerLabel(String provider) {
        if ("gmail".equals(provider))
            return "Gmail";
        if ("graph".equals(provider))
            return "Microsoft Graph";
        if ("zoho".equals(provider))
            return "Zoho Mail";
        return "Native REST";
    }

    private static String safeStage(String stage) {
        return (STAGES.contains(stage) ? stage : "unknown");
    }

    private static String safeOperation(String operation) {
        if (EntityOperation.SEEN.equals(operation))
            return "seen";
        if (EntityOperation.FLAG.equals(operation))
            return "flag";
        if (EntityOperation.MOVE.equals(operation))
            return "move";
        if (EntityOperation.DELETE.equals(operation))
            return "delete";
        if (EntityOperation.LABEL.equals(operation))
            return "label";
        if (EntityOperation.KEYWORD.equals(operation))
            return "keyword";
        if (EntityOperation.GMAIL_PERMANENT_DELETE.equals(operation) ||
                EntityOperation.GRAPH_PERMANENT_DELETE.equals(operation) ||
                EntityOperation.GRAPH_DELETE_PERMANENT.equals(operation) ||
                EntityOperation.ZOHO_PERMANENT_DELETE.equals(operation) ||
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT.equals(operation))
            return "permanent_delete";
        if (EntityOperation.ZOHO_PURGE_SPAM.equals(operation))
            return "empty_spam";
        if (EntityOperation.GRAPH_PURGE_TRASH.equals(operation) ||
                EntityOperation.ZOHO_PURGE_TRASH.equals(operation) || EntityOperation.EXPUNGE.equals(operation))
            return "empty_trash";
        if ("open".equals(operation) || "body".equals(operation) ||
                "classify".equals(operation) || "navigate".equals(operation))
            return operation;
        if ("refresh".equals(operation))
            return "refresh";
        return "other";
    }

    private static String safeTarget(String target) {
        return (TARGET_TYPES.contains(target) ? target : "other");
    }

    private static String safeResult(String result) {
        return (RESULTS.contains(result) ? result : "other");
    }
}

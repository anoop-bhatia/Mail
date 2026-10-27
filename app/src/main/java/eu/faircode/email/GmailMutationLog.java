package eu.faircode.email;

import org.json.JSONArray;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Privacy-safe, low-cardinality stage events for Gmail REST message mutations. */
final class GmailMutationLog {
    static final int ATTEMPT_TOKEN_INDEX = 6;

    enum QueueResult {
        QUEUED("queued"),
        NOT_WRITABLE("not_writable"),
        NOT_INSERTED("not_inserted");

        final String reason;

        QueueResult(String reason) {
            this.reason = reason;
        }
    }

    private static final Set<String> STAGES = new HashSet<>(Arrays.asList(
            "picker_callback", "queue_insert", "durable_queue", "worker_start", "http",
            "readback_reconcile", "local_commit", "worker_validation", "refresh_summary",
            "operation_summary", "failure_summary"));
    private static final Set<String> OPERATIONS = new HashSet<>(Arrays.asList(
            EntityOperation.SEEN, EntityOperation.FLAG, EntityOperation.LABEL,
            EntityOperation.KEYWORD, EntityOperation.MOVE, EntityOperation.DELETE,
            EntityOperation.GMAIL_PERMANENT_DELETE, EntityOperation.EXPUNGE,
            "modify", "trash", "untrash", "delete", "get_labels", "refresh"));
    private static final Set<String> TARGET_TYPES = new HashSet<>(Arrays.asList(
            "trash", "inbox", "label", "archive", "other"));
    private static final Set<String> OUTCOMES = new HashSet<>(Arrays.asList(
            "accepted", "queued", "started", "stored", "success", "failure",
            "response_read_failed", "labels_present", "satisfied", "not_satisfied",
            "missing", "unavailable", "confirmed", "source_missing",
            "invalid_target", "not_queued", "message_missing", "account_missing",
            "account_not_gmail", "source_folder_missing", "destination_folder_missing",
            "destination_account_missing", "source_view_missing", "source_view_mismatch",
            "copy_not_supported", "source_account_mismatch", "target_account_mismatch",
            "not_writable", "not_inserted", "callback_exception", "unknown", "rate_limited",
            "scheduled", "skipped", "rejected"));
    private static final ThreadLocal<String> CURRENT_ATTEMPT = new ThreadLocal<>();

    private GmailMutationLog() {
    }

    private static void write(String message) {
        ApiMutationLog.write(message);
    }

    static void route(String message, boolean protocolEnabled, ApiMutationLog.ProtocolSink sink) {
        ApiMutationLog.route(message, protocolEnabled, sink);
    }

    static String newAttemptToken() {
        return "gm-" + UUID.randomUUID().toString().replace("-", "");
    }

    static String safeAttemptToken(String token) {
        if (token == null || token.length() != 35 || !token.startsWith("gm-"))
            return null;
        for (int i = 3; i < token.length(); i++) {
            char c = token.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')))
                return null;
        }
        return token;
    }

    static String setCurrentAttemptToken(String token) {
        String previous = CURRENT_ATTEMPT.get();
        String safe = safeAttemptToken(token);
        if (safe == null)
            CURRENT_ATTEMPT.remove();
        else
            CURRENT_ATTEMPT.set(safe);
        return previous;
    }

    static String attemptToken(JSONArray args) {
        return (args == null ? null : safeAttemptToken(args.optString(ATTEMPT_TOKEN_INDEX, null)));
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
        return "other";
    }

    static String deleteTargetType(String operation) {
        return (EntityOperation.DELETE.equals(operation) ? "trash" : "other");
    }

    static String validationReason(GmailMailMutator.MoveCallbackValidation validation) {
        if (validation == GmailMailMutator.MoveCallbackValidation.READY)
            return "accepted";
        if (validation == GmailMailMutator.MoveCallbackValidation.SOURCE_MISSING)
            return "source_missing";
        return "invalid_target";
    }

    static String queueReason(QueueResult result) {
        return (result == null ? "not_inserted" : result.reason);
    }

    static String formatEvent(String stage, String operation, String outcome, String attemptToken) {
        String safeStage = (STAGES.contains(stage) ? stage : "unknown");
        String safeOperation = (OPERATIONS.contains(operation) ? operation : "unknown");
        String safeOutcome = (OUTCOMES.contains(outcome) ? outcome : "other");
        StringBuilder result = new StringBuilder("Gmail mutation stage=")
                .append(safeStage).append(" provider=gmail operation=").append(safeOperation)
                .append(" result=").append(safeOutcome);
        String safeAttempt = safeAttemptToken(attemptToken);
        if (safeAttempt != null)
            result.append(" attempt=").append(safeAttempt);
        return result.toString();
    }

    static String formatCallback(String operation, String targetType, String outcome,
                                 String attemptToken) {
        String safeOperation = (OPERATIONS.contains(operation) ? operation : "unknown");
        String safeTarget = (TARGET_TYPES.contains(targetType) ? targetType : "other");
        String safeOutcome = (OUTCOMES.contains(outcome) ? outcome : "other");
        StringBuilder result = new StringBuilder("Gmail mutation stage=picker_callback provider=gmail operation=")
                .append(safeOperation).append(" target_type=").append(safeTarget)
                .append(" result=").append(safeOutcome);
        String safeAttempt = safeAttemptToken(attemptToken);
        if (safeAttempt != null)
            result.append(" attempt=").append(safeAttempt);
        return result.toString();
    }

    static String formatHttp(String operation, int status, String outcome, String attemptToken) {
        String safeOperation = (OPERATIONS.contains(operation) ? operation : "unknown");
        String safeOutcome = (OUTCOMES.contains(outcome) ? outcome : "other");
        int safeStatus = (status >= 100 && status <= 599 ? status : -1);
        String errorClass = ("success".equals(safeOutcome) ? "none" :
                ("response_read_failed".equals(safeOutcome) ? "io" : "api"));
        StringBuilder result = new StringBuilder("Gmail mutation stage=http provider=gmail operation=")
                .append(safeOperation).append(" status=").append(safeStatus)
                .append(" result=").append(safeOutcome).append(" error_class=").append(errorClass);
        String safeAttempt = safeAttemptToken(attemptToken);
        if (safeAttempt != null)
            result.append(" attempt=").append(safeAttempt);
        return result.toString();
    }

    static void event(String stage, String operation, String outcome) {
        write(formatEvent(stage, operation, outcome, CURRENT_ATTEMPT.get()));
    }

    static void event(String stage, String operation, String outcome, String attemptToken) {
        write(formatEvent(stage, operation, outcome, attemptToken));
    }

    static void callback(String operation, String targetType, String outcome, String attemptToken) {
        write(formatCallback(operation, targetType, outcome, attemptToken));
    }

    static void http(String operation, int status, String outcome) {
        write(formatHttp(operation, status, outcome, CURRENT_ATTEMPT.get()));
    }
}

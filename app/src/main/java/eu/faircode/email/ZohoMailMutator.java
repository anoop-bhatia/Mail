package eu.faircode.email;

import android.content.Context;
import android.os.OperationCanceledException;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Executes explicit Zoho Mail REST writes on the account's existing serialized monitor. */
final class ZohoMailMutator {
    static final String OUTCOME_UNKNOWN_ERROR =
            "Zoho Mail REST mutation outcome unknown; automatic retry suppressed to avoid duplicates.";
    private static final String EXECUTING = "executing";

    private ZohoMailMutator() {
    }

    static int process(Context context, DB db, EntityAccount account, ZohoApi api, Core.State state)
            throws IOException, OperationCanceledException {
        if (account == null || !account.isZohoApi())
            throw new IllegalArgumentException("Zoho Mail API account required");

        int completed = 0;
        for (EntityOperation operation : db.operation().getOperations(account.id)) {
            EntityOperation current = db.operation().getOperation(operation.id);
            if (current == null || !ZohoOperationWakeup.isZohoWriteOperation(current.name))
                continue;
            String trace = ApiMutationLog.traceFromArgs(current.args);
            if (trace == null && isDeleteOperation(current.name))
                trace = ApiMutationLog.newTraceId();
            state.ensureRunning("Zoho Mail REST write");
            ApiMutationLog.event("zoho", "durable_queue", current.name, "observed", trace);
            ApiMutationLog.event("zoho", "worker_start", current.name, "started", trace);

            if (current.state != null) {
                markUnknown(db, current);
                ApiMutationLog.event("zoho", "readback_reconcile", current.name, "unavailable", trace);
                ApiMutationLog.failure("zoho", "failure_summary", current.name, "unknown", null, trace);
                continue;
            }
            if (current.error != null) {
                ApiMutationLog.event("zoho", "operation_summary", current.name, "unknown", trace);
                continue;
            }

            if (EntityOperation.ZOHO_PURGE_TRASH.equals(current.name) ||
                    EntityOperation.ZOHO_PURGE_SPAM.equals(current.name)) {
                EntityFolder folder = db.folder().getFolder(current.folder);
                if (!isEmptyFolderOperation(current.name, folder == null ? null : folder.type) ||
                        !canEmptyFolder(account, folder)) {
                    IllegalArgumentException refusal =
                            new IllegalArgumentException("invalid mapped Trash or Spam folder");
                    ApiMutationLog.failure("zoho", "worker_validation", current.name, "rejected", refusal);
                    ApiMutationLog.failure("zoho", "failure_summary", current.name, "failure", refusal);
                    rejectWithoutSending(db, current,
                            "Zoho empty-folder refused: only the mapped Trash/Bin or Spam/Junk folder can be emptied.");
                    continue;
                }
                db.operation().setOperationState(current.id, EXECUTING);
                ApiMutationLog.OperationContext previous = ApiMutationLog.setCurrent("zoho", current.name);
                try {
                    state.ensureRunning("Zoho Mail REST empty folder");
                    api.emptyFolder(folder.zoho_folder_id);
                    emptyFolderLocally(db, account, current, folder);
                    completed++;
                    ApiMutationLog.event("zoho", "local_commit", current.name, "success");
                    ApiMutationLog.event("zoho", "operation_summary", current.name, "success");
                } catch (Throwable ex) {
                    logFailure(current, ex);
                    handleFailure(db, current, null, ex);
                    if (ex instanceof OperationCanceledException)
                        throw (OperationCanceledException) ex;
                    if (ex instanceof IOException)
                        throw (IOException) ex;
                    throw new IOException("Unable to verify Zoho folder empty result", ex);
                } finally {
                    ApiMutationLog.restoreCurrent(previous);
                }
                continue;
            }

            EntityMessage message = (current.message == null ? null : db.message().getMessage(current.message));
            if (message == null) {
                ApiMutationLog.failure("zoho", "worker_validation", current.name, "message_missing", null, trace);
                ApiMutationLog.failure("zoho", "failure_summary", current.name, "failure", null, trace);
                rejectWithoutSending(db, current,
                        "Queued Zoho message is no longer available locally; no request was sent.");
                continue;
            }
            EntityFolder source = db.folder().getFolder(message.folder);
            if (isDeleteOperation(current.name))
                logDeleteState("worker_validation",
                        (isWritableMessage(account, source, message) ? "eligible" : "not_writable"),
                        trace, source, message);
            Work work;
            try {
                work = parseWork(db, account, current, message);
            } catch (Exception ex) {
                ApiMutationLog.failure("zoho", "worker_validation", current.name, "rejected", ex, trace);
                ApiMutationLog.failure("zoho", "failure_summary", current.name, "failure", ex, trace);
                rejectWithoutSending(db, current, "Invalid Zoho Mail REST operation; no request was sent.");
                restoreLocalState(db, current, message);
                continue;
            }

            db.operation().setOperationError(current.id, null);
            db.operation().setOperationState(current.id, EXECUTING);
            ApiMutationLog.OperationContext previous = ApiMutationLog.setCurrent("zoho", work.name, trace);
            try {
                state.ensureRunning("Zoho Mail REST message mutation");
                switch (work.name) {
                    case EntityOperation.SEEN:
                        api.setMessageRead(message.zoho_id, work.seen);
                        applySeen(db, current, message, work.seen);
                        break;
                    case EntityOperation.FLAG:
                        api.setMessageFlagged(work.source.zoho_folder_id, message.zoho_id, work.flagged);
                        applyFlag(db, current, message, work.flagged, work.color);
                        break;
                    case EntityOperation.MOVE:
                        api.moveMessage(work.source.zoho_folder_id, message.zoho_id,
                                work.target.zoho_folder_id);
                        moveLocally(db, current, message, work.source, work.target);
                        break;
                    case EntityOperation.DELETE:
                    case EntityOperation.ZOHO_DELETE_SPAM_PERMANENT:
                    case EntityOperation.ZOHO_PERMANENT_DELETE:
                        boolean permanent = isPermanentDelete(work.name, work.source.type);
                        api.deleteMessage(work.source.zoho_folder_id, message.zoho_id, permanent);
                        if (permanent) {
                            ZohoDeleteTraceRegistry.record(message.account, work.source.zoho_folder_id,
                                    message.zoho_id, trace);
                            deleteLocally(db, current, message);
                        } else
                            moveLocally(db, current, message, work.source, work.target);
                        break;
                    default:
                        throw new IllegalArgumentException("Unsupported Zoho Mail REST write");
                }
                completed++;
                ApiMutationLog.event("zoho", "local_commit", current.name, "success");
                ApiMutationLog.event("zoho", "operation_summary", current.name, "success");
            } catch (Throwable ex) {
                logFailure(current, ex);
                handleFailure(db, current, message, ex);
                if (ex instanceof OperationCanceledException)
                    throw (OperationCanceledException) ex;
                if (ex instanceof IOException)
                    throw (IOException) ex;
                throw new IOException("Unable to verify Zoho message mutation result", ex);
            } finally {
                ApiMutationLog.restoreCurrent(previous);
            }
        }
        return completed;
    }

    private static void logFailure(EntityOperation operation, Throwable failure) {
        ZohoMutationRecovery.FailureAction action = ZohoMutationRecovery.classify(failure);
        String result = (action == ZohoMutationRecovery.FailureAction.QUARANTINE_UNKNOWN ? "unknown" :
                (action == ZohoMutationRecovery.FailureAction.RETRY_AFTER_RATE_LIMIT
                        ? "rate_limited" : "failure"));
        if ("unknown".equals(result))
            ApiMutationLog.failure("zoho", "readback_reconcile", operation.name, "unavailable", failure);
        ApiMutationLog.failure("zoho", "failure_summary", operation.name, result, failure);
    }

    private static boolean isDeleteOperation(String name) {
        return (EntityOperation.DELETE.equals(name) ||
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT.equals(name) ||
                EntityOperation.ZOHO_PERMANENT_DELETE.equals(name));
    }

    private static void logDeleteState(String stage, String result, String trace,
                                       EntityFolder folder, EntityMessage message) {
        ApiMutationLog.write(ApiMutationLog.formatZohoDeleteState(stage, result, trace,
                folder != null && folder.read_only,
                folder != null && folder.selectable,
                folder != null && ZohoApi.isValidRemoteId(folder.zoho_folder_id),
                message != null && ZohoApi.isValidRemoteId(message.zoho_id),
                folder != null && ZohoMailMutator.hasFolderId(
                        message == null ? null : message.zoho_folder_ids, folder.zoho_folder_id)));
    }

    static boolean canEmptyFolder(EntityAccount account, EntityFolder folder) {
        return (account != null && account.isZohoApi() && folder != null &&
                Objects.equals(account.id, folder.account) &&
                (EntityFolder.TRASH.equals(folder.type) ||
                        (EntityFolder.JUNK.equals(folder.type) &&
                                account.auth_type != null &&
                                account.auth_type == ServiceAuthenticator.AUTH_TYPE_ZOHO)) &&
                folder.selectable && !folder.read_only &&
                ZohoApi.isValidRemoteId(folder.zoho_folder_id));
    }

    static boolean canEmptyTrash(EntityAccount account, EntityFolder folder) {
        return (canEmptyFolder(account, folder) && EntityFolder.TRASH.equals(folder.type));
    }

    static boolean canEmptyFolder(TupleFolderEx folder) {
        return (folder != null && folder.account != null &&
                folder.accountAuthType != null &&
                folder.accountAuthType == ServiceAuthenticator.AUTH_TYPE_ZOHO &&
                (EntityFolder.TRASH.equals(folder.type) || EntityFolder.JUNK.equals(folder.type)) &&
                folder.selectable && !folder.read_only &&
                ZohoApi.isValidRemoteId(folder.zoho_folder_id));
    }

    static boolean isEmptyFolderOperation(String operation, String folderType) {
        return ((EntityOperation.ZOHO_PURGE_TRASH.equals(operation) &&
                EntityFolder.TRASH.equals(folderType)) ||
                (EntityOperation.ZOHO_PURGE_SPAM.equals(operation) &&
                        EntityFolder.JUNK.equals(folderType)));
    }

    static boolean isMessageWrite(String name) {
        return (EntityOperation.SEEN.equals(name) || EntityOperation.FLAG.equals(name) ||
                EntityOperation.MOVE.equals(name) || EntityOperation.DELETE.equals(name) ||
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT.equals(name) ||
                EntityOperation.ZOHO_PERMANENT_DELETE.equals(name));
    }

    static boolean isPermanentDelete(String sourceFolderType) {
        return EntityFolder.TRASH.equals(sourceFolderType);
    }

    static boolean isPermanentDelete(String operation, String sourceFolderType) {
        return (EntityOperation.ZOHO_PERMANENT_DELETE.equals(operation) ||
                (EntityOperation.DELETE.equals(operation) && isPermanentDelete(sourceFolderType)) ||
                (EntityOperation.ZOHO_DELETE_SPAM_PERMANENT.equals(operation) &&
                        EntityFolder.JUNK.equals(sourceFolderType)));
    }

    static String getIndividualDeleteOperation(EntityAccount account, EntityFolder source,
                                               EntityMessage message, boolean permanent) {
        // Preserve an explicit permanent-delete choice. Queue/worker validation below will
        // refuse an unwritable or stale source; silently falling back to DELETE would trash it.
        if (permanent && account != null && account.isZohoApi() && source != null) {
            if (EntityFolder.TRASH.equals(source.type))
                return EntityOperation.DELETE;
            if (EntityFolder.JUNK.equals(source.type))
                return EntityOperation.ZOHO_DELETE_SPAM_PERMANENT;
            return EntityOperation.ZOHO_PERMANENT_DELETE;
        }
        return EntityOperation.DELETE;
    }

    private static final class Work {
        final String name;
        EntityFolder source;
        EntityFolder target;
        boolean seen;
        boolean flagged;
        Integer color;

        Work(String name) {
            this.name = name;
        }
    }

    private static Work parseWork(DB db, EntityAccount account, EntityOperation operation,
                                  EntityMessage message) throws JSONException, IOException {
        Work work = new Work(operation.name);
        work.source = db.folder().getFolder(message.folder);
        if (!isMessageWrite(operation.name) || message.zoho_id == null ||
                !Objects.equals(message.account, account.id) || work.source == null ||
                !Objects.equals(work.source.account, account.id) || work.source.read_only ||
                !work.source.selectable || !ZohoApi.isValidRemoteId(work.source.zoho_folder_id) ||
                !ZohoApi.isValidRemoteId(message.zoho_id) ||
                !Objects.equals(operation.folder, work.source.id) ||
                !hasFolderId(message.zoho_folder_ids, work.source.zoho_folder_id))
            throw new IOException("Invalid Zoho source folder or remote message ID");

        JSONArray args = new JSONArray(operation.args);
        if (EntityOperation.SEEN.equals(operation.name))
            work.seen = args.getBoolean(0);
        else if (EntityOperation.FLAG.equals(operation.name)) {
            work.flagged = args.getBoolean(0);
            if (args.length() > 1 && !args.isNull(1))
                work.color = args.getInt(1);
        } else if (EntityOperation.MOVE.equals(operation.name)) {
            work.target = db.folder().getFolder(args.getLong(0));
            if (work.target == null || !Objects.equals(work.target.account, account.id) ||
                    work.target.read_only || !work.target.selectable ||
                    !ZohoApi.isValidRemoteId(work.target.zoho_folder_id) ||
                    Objects.equals(work.source.id, work.target.id))
                throw new IOException("Invalid Zoho move target");
        } else if (EntityOperation.DELETE.equals(operation.name) &&
                !EntityFolder.TRASH.equals(work.source.type)) {
            work.target = db.folder().getFolderByType(account.id, EntityFolder.TRASH);
            if (work.target == null || !Objects.equals(work.target.account, account.id) ||
                    work.target.read_only || !work.target.selectable ||
                    !ZohoApi.isValidRemoteId(work.target.zoho_folder_id))
                throw new IOException("Mapped Zoho Trash/Bin folder required for soft delete");
        } else if (EntityOperation.ZOHO_DELETE_SPAM_PERMANENT.equals(operation.name) &&
                !EntityFolder.JUNK.equals(work.source.type)) {
            throw new IOException("Zoho Spam permanent delete requires the mapped Spam/Junk folder");
        }
        return work;
    }

    private static void applySeen(DB db, EntityOperation operation, EntityMessage message, boolean seen) {
        db.beginTransaction();
        try {
            db.message().setMessageSeen(message.id, seen);
            db.message().setMessageUiSeen(message.id, seen);
            db.operation().deleteOperation(operation.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void applyFlag(DB db, EntityOperation operation, EntityMessage message,
                                  boolean flagged, Integer color) {
        db.beginTransaction();
        try {
            db.message().setMessageFlagged(message.id, flagged);
            db.message().setMessageUiFlagged(message.id, flagged, flagged ? color : null);
            db.operation().deleteOperation(operation.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void moveLocally(DB db, EntityOperation operation,
                                    EntityMessage message, EntityFolder source, EntityFolder target) {
        db.beginTransaction();
        try {
            message.folder = target.id;
            message.zoho_folder_ids = addFolderId(
                    removeFolderId(message.zoho_folder_ids, source.zoho_folder_id), target.zoho_folder_id);
            message.ui_hide = false;
            message.ui_deleted = false;
            message.ui_busy = null;
            db.message().updateMessage(message);
            db.operation().deleteOperation(operation.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void deleteLocally(DB db, EntityOperation operation, EntityMessage message) {
        db.beginTransaction();
        try {
            db.operation().deleteOperation(operation.id);
            db.message().deleteMessage(message.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void emptyFolderLocally(DB db, EntityAccount account,
                                           EntityOperation operation, EntityFolder folder) {
        db.beginTransaction();
        try {
            for (EntityMessage message : db.message().getZohoMessages(folder.id)) {
                String remaining = removeFolderId(message.zoho_folder_ids, folder.zoho_folder_id);
                EntityFolder retained = findMappedMembership(db, account, remaining, folder.id);
                if (retained == null)
                    db.message().deleteMessage(message.id);
                else {
                    message.folder = retained.id;
                    message.zoho_folder_ids = remaining;
                    message.ui_hide = false;
                    message.ui_deleted = false;
                    message.ui_busy = null;
                    db.message().updateMessage(message);
                }
            }
            db.operation().deleteOperation(operation.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static EntityFolder findMappedMembership(DB db, EntityAccount account,
                                                     String memberships, long excludedFolder) {
        for (String remoteId : folderIds(memberships)) {
            EntityFolder folder = db.folder().getFolderByZohoId(account.id, remoteId);
            if (folder != null && folder.id != excludedFolder)
                return folder;
        }
        return null;
    }

    private static void handleFailure(DB db, EntityOperation operation, EntityMessage message,
                                      Throwable failure) {
        ZohoMutationRecovery.FailureAction action = ZohoMutationRecovery.classify(failure);
        if (action == ZohoMutationRecovery.FailureAction.QUARANTINE_UNKNOWN) {
            markUnknown(db, operation);
            return;
        }
        db.operation().setOperationState(operation.id, null);
        if (action == ZohoMutationRecovery.FailureAction.RETRY_SAFE ||
                action == ZohoMutationRecovery.FailureAction.RETRY_AFTER_RATE_LIMIT)
            db.operation().setOperationError(operation.id, null);
        else
            db.operation().setOperationError(operation.id,
                    "Zoho Mail REST request was rejected; local state restored.");
        if (message != null)
            restoreLocalState(db, operation, message);
    }

    private static void markUnknown(DB db, EntityOperation operation) {
        db.operation().setOperationState(operation.id, null);
        db.operation().setOperationError(operation.id, OUTCOME_UNKNOWN_ERROR);
    }

    private static void rejectWithoutSending(DB db, EntityOperation operation, String error) {
        db.operation().setOperationState(operation.id, null);
        db.operation().setOperationError(operation.id, error);
    }

    private static void restoreLocalState(DB db, EntityOperation operation, EntityMessage message) {
        db.beginTransaction();
        try {
            if (EntityOperation.SEEN.equals(operation.name))
                db.message().setMessageUiSeen(message.id, message.seen);
            else if (EntityOperation.FLAG.equals(operation.name))
                db.message().setMessageUiFlagged(message.id, message.flagged,
                        message.flagged ? message.color : null);
            else if (EntityOperation.MOVE.equals(operation.name) || EntityOperation.DELETE.equals(operation.name)) {
                db.message().setMessageUiHide(message.id, false);
                db.message().setMessageUiBusy(message.id, null);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    static String addFolderId(String current, String folderId) {
        if (!ZohoApi.isValidRemoteId(folderId))
            throw new IllegalArgumentException("Invalid Zoho folder ID");
        List<String> ids = folderIds(current);
        if (!ids.contains(folderId))
            ids.add(folderId);
        StringBuilder result = new StringBuilder("|");
        for (String id : ids)
            result.append(id).append('|');
        return result.toString();
    }

    static String getSentFolderMembership(EntityFolder sentFolder) {
        return (sentFolder == null || !ZohoApi.isValidRemoteId(sentFolder.zoho_folder_id)
                ? null : addFolderId(null, sentFolder.zoho_folder_id));
    }

    private static String removeFolderId(String current, String folderId) {
        List<String> ids = folderIds(current);
        ids.remove(folderId);
        if (ids.isEmpty())
            return null;
        StringBuilder result = new StringBuilder("|");
        for (String id : ids)
            result.append(id).append('|');
        return result.toString();
    }

    private static List<String> folderIds(String current) {
        List<String> ids = new ArrayList<>();
        if (current == null)
            return ids;
        for (String id : current.split("\\|"))
            if (id.length() > 0 && !ids.contains(id))
                ids.add(id);
        return ids;
    }

    static boolean isValidZohoMoveTarget(EntityAccount sourceAccount, EntityAccount targetAccount,
                                         EntityFolder targetFolder, boolean copy) {
        return (sourceAccount != null && sourceAccount.isZohoApi() && !copy &&
                targetAccount != null && targetAccount.isZohoApi() &&
                Objects.equals(sourceAccount.id, targetAccount.id) &&
                targetFolder != null && targetFolder.account != null &&
                Objects.equals(targetFolder.account, targetAccount.id) &&
                targetFolder.selectable && !targetFolder.read_only &&
                ZohoApi.isValidRemoteId(targetFolder.zoho_folder_id));
    }

    static boolean isWritableMessage(EntityAccount account, EntityFolder folder, EntityMessage message) {
        return (account != null && account.isZohoApi() && folder != null && message != null &&
                folder.account != null && folder.account.equals(account.id) &&
                folder.selectable && !folder.read_only &&
                ZohoApi.isValidRemoteId(folder.zoho_folder_id) &&
                ZohoApi.isValidRemoteId(message.zoho_id) &&
                hasFolderId(message.zoho_folder_ids, folder.zoho_folder_id));
    }

    static boolean hasFolderId(String memberships, String folderId) {
        return (ZohoApi.isValidRemoteId(folderId) && folderIds(memberships).contains(folderId));
    }
}

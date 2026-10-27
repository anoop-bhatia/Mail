package eu.faircode.email;

import android.content.Context;
import android.os.OperationCanceledException;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Executes approved Microsoft Graph message writes and direct permanent deletion. */
final class GraphMailMutator {
    static final String OUTCOME_UNKNOWN_ERROR =
            "Microsoft Graph mutation outcome unknown; automatic retry suppressed.";
    private static final String EXECUTING = "executing";

    private GraphMailMutator() {
    }

    static int process(Context context, DB db, EntityAccount account, GraphMailApi api, Core.State state)
            throws IOException, OperationCanceledException {
        if (account == null || !account.isGraphApi())
            throw new IllegalArgumentException("Microsoft Graph account required");

        int completed = 0;
        List<EntityOperation> operations = db.operation().getOperations(account.id);
        for (EntityOperation operation : operations) {
            EntityOperation current = db.operation().getOperation(operation.id);
            if (current == null)
                continue;

            boolean candidate = GraphOperationWakeup.isGraphWriteOperation(current.name);
            if (!candidate)
                continue;
            String trace = ApiMutationLog.traceFromArgs(current.args);
            state.ensureRunning("Microsoft Graph message write");

            if (GraphMailPurger.isStandalonePermanentDelete(current)) {
                EntityMessage legacyMessage = (current.message == null ? null :
                        db.message().getMessage(current.message));
                EntityFolder legacyFolder = db.folder().getFolder(current.folder);
                if (!GraphMailPurger.canDowngradeStandalonePermanentDelete(
                        current, account, legacyFolder, legacyMessage)) {
                    ApiMutationLog.failure("graph", "worker_validation", current.name, "rejected",
                            new IllegalArgumentException("unsupported permanent delete"));
                    ApiMutationLog.failure("graph", "failure_summary", current.name, "failure",
                            new IllegalArgumentException("unsupported permanent delete"));
                    blockLegacyPermanentDelete(db, current, legacyMessage);
                    continue;
                }
                if (!downgradeLegacyPermanentDelete(db, current))
                    continue;
            }

            boolean purgeOperation = GraphMailPurger.isPurgeOperation(current.name);
            if (current.error != null && !(purgeOperation
                    ? GraphMailPurger.isUnknownOutcome(current.error)
                    : OUTCOME_UNKNOWN_ERROR.equals(current.error)))
                continue;

            ApiMutationLog.event("graph", "durable_queue", current.name, "observed", trace);
            ApiMutationLog.event("graph", "worker_start", current.name, "started", trace);

            if (purgeOperation) {
                try {
                    if (GraphMailPurger.process(context, db, account, api, state, current)) {
                        logHttp(api, current.name, null);
                        ApiMutationLog.event("graph", "local_commit", current.name, "success");
                        ApiMutationLog.event("graph", "operation_summary", current.name, "success");
                        ApiMutationLog.event("graph", "refresh_summary", current.name, "scheduled");
                        completed++;
                    } else {
                        ApiMutationLog.failure("graph", "failure_summary", current.name, "unknown", null);
                    }
                } catch (IOException ex) {
                    logHttp(api, current.name, ex);
                    ApiMutationLog.failure("graph", "failure_summary", current.name,
                            (ex instanceof GraphMailApi.ApiException &&
                                    ((GraphMailApi.ApiException) ex).isRateLimitFailure()
                                    ? "rate_limited" : "unknown"), ex);
                    throw ex;
                } catch (OperationCanceledException ex) {
                    ApiMutationLog.failure("graph", "failure_summary", current.name, "unknown", ex);
                    throw ex;
                }
                continue;
            }

            EntityMessage message = (current.message == null ? null : db.message().getMessage(current.message));
            if (message == null) {
                ApiMutationLog.failure("graph", "worker_validation", current.name, "message_missing", null);
                ApiMutationLog.failure("graph", "failure_summary", current.name, "failure", null);
                db.operation().deleteOperation(current.id);
                continue;
            }
            if (message.graph_id == null) {
                ApiMutationLog.failure("graph", "worker_validation", current.name, "rejected",
                        new IllegalArgumentException("missing remote id"));
                ApiMutationLog.failure("graph", "failure_summary", current.name, "failure",
                        new IllegalArgumentException("missing remote id"));
                continue; // Local drafts and non-Graph rows are never sent through Graph REST.
            }

            if (GraphMutationRecovery.requiresReconciliation(current, OUTCOME_UNKNOWN_ERROR)) {
                if (current.state != null)
                    db.operation().setOperationState(current.id, null);
                db.operation().setOperationError(current.id, OUTCOME_UNKNOWN_ERROR);
                ApiMutationLog.event("graph", "readback_reconcile", current.name, "started");
                try {
                    boolean reconciled = reconcile(context, db, account, api, current, message);
                    logHttp(api, current.name, null);
                    ApiMutationLog.event("graph", "readback_reconcile", current.name,
                            (reconciled ? "reconciled" : "unavailable"));
                    if (reconciled) {
                        completed++;
                        ApiMutationLog.event("graph", "local_commit", current.name, "success");
                        ApiMutationLog.event("graph", "operation_summary", current.name, "success");
                    } else
                        ApiMutationLog.failure("graph", "failure_summary", current.name, "unknown", null);
                } catch (IOException ex) {
                    logHttp(api, current.name, ex);
                    ApiMutationLog.failure("graph", "readback_reconcile", current.name, "unavailable", ex);
                    ApiMutationLog.failure("graph", "failure_summary", current.name, "unknown", ex);
                    throw ex;
                }
                continue;
            }

            Work work;
            try {
                work = parseWork(db, account, current, message);
            } catch (Exception ex) {
                ApiMutationLog.failure("graph", "worker_validation", current.name, "rejected", ex, trace);
                ApiMutationLog.failure("graph", "failure_summary", current.name, "failure", ex, trace);
                db.operation().setOperationState(current.id, null);
                db.operation().setOperationError(current.id, "Invalid Microsoft Graph operation arguments");
                restoreLocalState(db, current, message);
                continue;
            }

            markOptimistic(db, current, message, work);
            db.operation().setOperationError(current.id, null);
            db.operation().setOperationState(current.id, EXECUTING);
            try {
                switch (work.name) {
                    case EntityOperation.SEEN: {
                        ApiMutationLog.OperationContext previousTrace = ApiMutationLog.setCurrent(
                                "graph", work.name, trace);
                        try {
                            api.setMessageRead(message.graph_id, work.seen);
                        } finally {
                            ApiMutationLog.restoreCurrent(previousTrace);
                        }
                        applySeen(db, current, message, work.seen);
                        completed++;
                        break;
                    }
                    case EntityOperation.FLAG:
                        api.setMessageFlagged(message.graph_id, work.flagged);
                        applyFlag(db, current, message, work.flagged, work.color);
                        completed++;
                        break;
                    case EntityOperation.DELETE: {
                        EntityFolder source = db.folder().getFolder(message.folder);
                        if (getDeleteRoute(source.type) == DeleteRoute.DELETE_FROM_DELETED_ITEMS) {
                            api.deleteMessageFromFolder(source.graph_folder_id, message.graph_id);
                            deleteLocally(context, db, current, message);
                        } else {
                            GraphMailApi.MessageState moved = api.moveMessageToDeletedItems(message.graph_id);
                            moveLocally(context, db, account, current, message, work.target, moved);
                        }
                        completed++;
                        break;
                    }
                    case EntityOperation.GRAPH_DELETE_PERMANENT: {
                        EntityFolder source = db.folder().getFolder(message.folder);
                        GraphMailApi.MessageState remote = api.getUserMessageState(
                                account.user, message.graph_id);
                        if (source == null || remote == null ||
                                !Objects.equals(remote.id, message.graph_id) ||
                                !Objects.equals(remote.parentFolderId, source.graph_folder_id)) {
                            rejectPermanentDelete(db, current, message,
                                    "Microsoft Graph permanent delete refused: message is no longer in its source folder.");
                            EntityFolder actual = (remote == null || remote.parentFolderId == null ? null :
                                    db.folder().getFolderByGraphId(account.id, remote.parentFolderId));
                            refreshFolder(context, source);
                            refreshFolder(context, actual);
                            ApiMutationLog.failure("graph", "failure_summary", current.name,
                                    "source_mismatch", null);
                            continue;
                        }
                        api.permanentDeleteMessage(account.user, source.graph_folder_id, message.graph_id);
                        deleteLocally(context, db, current, message);
                        completed++;
                        break;
                    }
                    case EntityOperation.MOVE: {
                        ApiMutationLog.OperationContext previousTrace = ApiMutationLog.setCurrent(
                                "graph", work.name, trace);
                        GraphMailApi.MessageState moved;
                        try {
                            moved = api.moveMessage(message.graph_id, work.target.graph_folder_id);
                        } finally {
                            ApiMutationLog.restoreCurrent(previousTrace);
                        }
                        moveLocally(context, db, account, current, message, work.target, moved);
                        completed++;
                        break;
                    }
                    default:
                        throw new IllegalArgumentException("Unsupported Microsoft Graph write");
                }
                logHttp(api, current.name, null);
                ApiMutationLog.event("graph", "local_commit", current.name, "success", trace);
                ApiMutationLog.event("graph", "operation_summary", current.name, "success", trace);
            } catch (OperationCanceledException ex) {
                ApiMutationLog.failure("graph", "failure_summary", current.name, "failure", ex, trace);
                throw ex;
            } catch (GraphMailApi.ApiException ex) {
                logHttp(api, current.name, ex);
                if (ex.status == 429) {
                    // Graph explicitly rejected a throttled request. Keep it pending for the Retry-After delay.
                    db.operation().setOperationState(current.id, null);
                    db.operation().setOperationError(current.id, null);
                    ApiMutationLog.failure("graph", "failure_summary", current.name, "rate_limited", ex, trace);
                    throw ex;
                }
                if (EntityOperation.GRAPH_DELETE_PERMANENT.equals(current.name) && ex.status == 404) {
                    GraphMailApi.MessageState remote = null;
                    try {
                        remote = api.getUserMessageState(account.user, message.graph_id);
                    } catch (GraphMailApi.ApiException verify) {
                        if (verify.status != 404) {
                            markUnknown(db, current);
                            throw verify;
                        }
                        deleteLocally(context, db, current, message);
                        completed++;
                        ApiMutationLog.event("graph", "local_commit", current.name, "success");
                        ApiMutationLog.event("graph", "operation_summary", current.name, "success");
                        continue;
                    } catch (JSONException verify) {
                        markUnknown(db, current);
                        throw new IOException("Unable to verify Microsoft Graph permanent delete", verify);
                    } catch (IOException verify) {
                        markUnknown(db, current);
                        throw verify;
                    }
                    EntityFolder source = db.folder().getFolder(message.folder);
                    EntityFolder actual = (remote == null || remote.parentFolderId == null ? null :
                            db.folder().getFolderByGraphId(account.id, remote.parentFolderId));
                    rejectPermanentDelete(db, current, message,
                            "Microsoft Graph permanentDelete returned 404 but the message still exists; no local removal was applied.");
                    refreshFolder(context, source);
                    refreshFolder(context, actual);
                    ApiMutationLog.failure("graph", "failure_summary", current.name, "failure", ex);
                    continue;
                }
                if (EntityOperation.DELETE.equals(current.name) && ex.status == 404) {
                    EntityFolder source = db.folder().getFolder(message.folder);
                    if (source != null && getDeleteRoute(source.type) == DeleteRoute.MOVE_TO_DELETED_ITEMS)
                        refreshFolder(context, work.target);
                    deleteLocally(context, db, current, message);
                    completed++;
                    ApiMutationLog.event("graph", "local_commit", current.name, "success");
                    ApiMutationLog.event("graph", "operation_summary", current.name, "success");
                    continue;
                }
                if (ex.status >= 500) {
                    markUnknown(db, current);
                    ApiMutationLog.failure("graph", "failure_summary", current.name,
                            (ex.isRateLimitFailure() ? "rate_limited" : "unknown"), ex);
                    throw ex; // Includes 503 Retry-After; reconcile only after the backoff.
                }
                // An explicit 4xx response means this request was rejected, not applied.
                db.operation().setOperationState(current.id, null);
                db.operation().setOperationError(current.id, null);
                restoreLocalState(db, current, message);
                ApiMutationLog.failure("graph", "failure_summary", current.name, "failure", ex);
                throw ex;
            } catch (IOException ex) {
                logHttp(api, current.name, ex);
                markUnknown(db, current);
                ApiMutationLog.failure("graph", "failure_summary", current.name, "unknown", ex);
                throw ex;
            } catch (JSONException ex) {
                // JSON construction is local; this path cannot mean the server accepted a write.
                db.operation().setOperationState(current.id, null);
                db.operation().setOperationError(current.id, null);
                restoreLocalState(db, current, message);
                ApiMutationLog.failure("graph", "failure_summary", current.name, "failure", ex);
                throw new IOException("Unable to encode Microsoft Graph message write", ex);
            }
        }
        return completed;
    }

    enum DeleteRoute {
        MOVE_TO_DELETED_ITEMS,
        DELETE_FROM_DELETED_ITEMS
    }

    static DeleteRoute getDeleteRoute(String sourceFolderType) {
        return (EntityFolder.TRASH.equals(sourceFolderType)
                ? DeleteRoute.DELETE_FROM_DELETED_ITEMS
                : DeleteRoute.MOVE_TO_DELETED_ITEMS);
    }

    private static void logHttp(GraphMailApi api, String operation, Throwable failure) {
        int status = (failure instanceof GraphMailApi.ApiException
                ? ((GraphMailApi.ApiException) failure).status
                : (failure == null ? api.getLastHttpStatus() : -1));
        if (failure == null && status < 100)
            return;
        boolean success = (status >= 200 && status < 300);
        String result = (success ? "success" :
                (status == 429 || status == 503 ? "rate_limited" : "failure"));
        String error = (failure != null ? ApiMutationLog.errorClass(failure) :
                (success ? "none" : (status >= 100 && status <= 599 ? "api" : "none")));
        ApiMutationLog.http("graph", operation, status, result, error);
    }

    private static final class Work {
        final String name;
        boolean seen;
        boolean flagged;
        Integer color;
        EntityFolder target;

        Work(String name) {
            this.name = name;
        }
    }

    private static Work parseWork(DB db, EntityAccount account, EntityOperation operation,
                                  EntityMessage message) throws JSONException, IOException {
        JSONArray args = new JSONArray(operation.args);
        Work work = new Work(operation.name);
        EntityFolder source = db.folder().getFolder(message.folder);
        if (source == null || !Objects.equals(source.account, account.id) ||
                source.read_only || !source.selectable || source.graph_folder_id == null ||
                source.graph_folder_id.length() == 0 || !Objects.equals(message.account, account.id))
            throw new IOException("Invalid Microsoft Graph source folder");
        if (EntityOperation.SEEN.equals(operation.name)) {
            work.seen = args.getBoolean(0);
        } else if (EntityOperation.FLAG.equals(operation.name)) {
            work.flagged = args.getBoolean(0);
            if (args.length() > 1 && !args.isNull(1))
                work.color = args.getInt(1);
        } else if (EntityOperation.MOVE.equals(operation.name)) {
            long targetId = args.getLong(0);
            work.target = db.folder().getFolder(targetId);
            if (work.target == null || work.target.account == null ||
                    !Objects.equals(work.target.account, account.id) || !Objects.equals(source.account, account.id) ||
                    work.target.read_only || !work.target.selectable || work.target.graph_folder_id == null ||
                    source.graph_folder_id == null || Objects.equals(source.id, work.target.id))
                throw new IOException("Invalid Microsoft Graph move target");
        } else if (EntityOperation.DELETE.equals(operation.name) &&
                getDeleteRoute(source.type) == DeleteRoute.MOVE_TO_DELETED_ITEMS) {
            work.target = db.folder().getFolderByType(account.id, EntityFolder.TRASH);
            if (work.target == null || !Objects.equals(work.target.account, account.id) ||
                    work.target.read_only || !work.target.selectable ||
                    work.target.graph_folder_id == null || work.target.graph_folder_id.length() == 0)
                throw new IOException("Mapped Microsoft Graph Deleted Items folder required for Delete");
        }
        return work;
    }

    private static boolean downgradeLegacyPermanentDelete(DB db, EntityOperation operation) {
        db.beginTransaction();
        try {
            if (db.operation().getOperation(operation.id) == null) {
                db.setTransactionSuccessful();
                return false;
            }
            db.operation().setOperationName(operation.id, EntityOperation.DELETE);
            db.operation().setOperationArgs(operation.id, new JSONArray().toString());
            db.operation().setOperationState(operation.id, null);
            db.operation().setOperationError(operation.id, null);
            operation.name = EntityOperation.DELETE;
            operation.args = new JSONArray().toString();
            operation.state = null;
            operation.error = null;
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private static void blockLegacyPermanentDelete(DB db, EntityOperation operation,
                                                   EntityMessage message) {
        db.beginTransaction();
        try {
            if (db.operation().getOperation(operation.id) != null) {
                db.operation().setOperationState(operation.id, null);
                db.operation().setOperationError(operation.id,
                        "Microsoft Graph legacy permanent delete refused: no verified Empty Trash parent; " +
                                "operation was not sent.");
            }
            if (message != null) {
                db.message().setMessageUiHide(message.id, false);
                db.message().setMessageUiBusy(message.id, null);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void markOptimistic(DB db, EntityOperation operation, EntityMessage message, Work work) {
        if (EntityOperation.SEEN.equals(operation.name))
            db.message().setMessageUiSeen(message.id, work.seen);
        else if (EntityOperation.FLAG.equals(operation.name))
            db.message().setMessageUiFlagged(message.id, work.flagged, work.flagged ? work.color : null);
        else if (EntityOperation.MOVE.equals(operation.name) || EntityOperation.DELETE.equals(operation.name) ||
                EntityOperation.GRAPH_DELETE_PERMANENT.equals(operation.name))
            db.message().setMessageUiHide(message.id, true);
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

    private static void deleteLocally(Context context, DB db, EntityOperation operation,
                                      EntityMessage message) {
        EntityFolder source = db.folder().getFolder(message.folder);
        db.beginTransaction();
        try {
            db.operation().deleteOperation(operation.id);
            db.message().deleteMessage(message.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        refreshFolder(context, source);
    }

    private static void moveLocally(Context context, DB db, EntityAccount account,
                                   EntityOperation operation, EntityMessage message,
                                   EntityFolder requestedTarget, GraphMailApi.MessageState moved) {
        EntityFolder source = db.folder().getFolder(message.folder);
        EntityFolder target = requestedTarget;
        if (moved != null && moved.parentFolderId != null &&
                !moved.parentFolderId.equals(requestedTarget.graph_folder_id))
            target = db.folder().getFolderByGraphId(account.id, moved.parentFolderId);

        String newGraphId = (moved == null ? null : moved.id);
        if (target == null || newGraphId == null) {
            // The server accepted the move but did not provide a usable representation. Invalidate the stale row;
            // ordinary source/target folder deltas will repopulate it. Never repeat the non-idempotent POST.
            db.beginTransaction();
            try {
                db.operation().deleteOperation(operation.id);
                db.message().deleteMessage(message.id);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            refreshFolder(context, source);
            refreshFolder(context, requestedTarget);
            return;
        }

        EntityMessage duplicate = db.message().getMessageByGraphId(account.id, newGraphId);
        if (duplicate != null && !Objects.equals(duplicate.id, message.id)) {
            db.beginTransaction();
            try {
                db.operation().deleteOperation(operation.id);
                db.message().deleteMessage(message.id);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            refreshFolder(context, source);
            refreshFolder(context, target);
            return;
        }

        db.beginTransaction();
        try {
            message.graph_id = newGraphId;
            message.folder = target.id;
            message.ui_hide = false;
            message.ui_busy = null;
            if (moved.seen != null) {
                message.seen = moved.seen;
                message.ui_seen = moved.seen;
                db.message().setMessageSeen(message.id, moved.seen);
                db.message().setMessageUiSeen(message.id, moved.seen);
            }
            if (moved.flagged != null) {
                message.flagged = moved.flagged;
                message.ui_flagged = moved.flagged;
                if (!moved.flagged)
                    message.color = null;
                db.message().setMessageFlagged(message.id, moved.flagged);
                db.message().setMessageUiFlagged(message.id, moved.flagged, message.color);
            }
            db.message().updateMessage(message);
            db.operation().deleteOperation(operation.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        refreshFolder(context, source);
        refreshFolder(context, target);
    }

    private static boolean reconcile(Context context, DB db, EntityAccount account, GraphMailApi api,
                                     EntityOperation operation, EntityMessage message) throws IOException {
        GraphMailApi.MessageState remote;
        try {
            remote = (EntityOperation.GRAPH_DELETE_PERMANENT.equals(operation.name)
                    ? api.getUserMessageState(account.user, message.graph_id)
                    : api.getMessageState(message.graph_id));
        } catch (GraphMailApi.ApiException ex) {
            if (ex.status != 404)
                throw ex;
            if (EntityOperation.DELETE.equals(operation.name)) {
                EntityFolder source = db.folder().getFolder(message.folder);
                if (source != null && getDeleteRoute(source.type) == DeleteRoute.MOVE_TO_DELETED_ITEMS)
                    refreshFolder(context, db.folder().getFolderByType(account.id, EntityFolder.TRASH));
                deleteLocally(context, db, operation, message);
                return true;
            }
            if (EntityOperation.GRAPH_DELETE_PERMANENT.equals(operation.name)) {
                deleteLocally(context, db, operation, message);
                return true;
            }
            if (EntityOperation.SEEN.equals(operation.name) || EntityOperation.FLAG.equals(operation.name)) {
                deleteLocally(context, db, operation, message);
                return true;
            }
            if (GraphMutationRecovery.afterMessageLookupFailure(operation, ex.status) ==
                    GraphMutationRecovery.MissingMessageAction.REFRESH_SOURCE_AND_TARGET_DELTAS) {
                JSONArray args;
                try {
                    args = new JSONArray(operation.args);
                    EntityFolder target = db.folder().getFolder(args.getLong(0));
                    refreshFolder(context, db.folder().getFolder(message.folder));
                    refreshFolder(context, target);
                } catch (JSONException ignored) {
                    // Keep the unknown operation quarantined if its target cannot be resolved.
                }
            }
            return false;
        } catch (JSONException ex) {
            throw new IOException("Unable to read Microsoft Graph mutation state", ex);
        }

        if (EntityOperation.SEEN.equals(operation.name)) {
            try {
                boolean desired = new JSONArray(operation.args).getBoolean(0);
                if (remote.seen == null)
                    return false;
                if (remote.seen == desired)
                    applySeen(db, operation, message, desired);
                else {
                    restoreRemoteState(db, operation, message, remote);
                    db.operation().deleteOperation(operation.id);
                }
                return true;
            } catch (JSONException ex) {
                throw new IOException("Invalid Microsoft Graph read-state operation", ex);
            }
        }
        if (EntityOperation.FLAG.equals(operation.name)) {
            try {
                JSONArray args = new JSONArray(operation.args);
                boolean desired = args.getBoolean(0);
                Integer color = (args.length() > 1 && !args.isNull(1) ? args.getInt(1) : null);
                if (remote.flagged == null)
                    return false;
                if (remote.flagged == desired)
                    applyFlag(db, operation, message, desired, color);
                else {
                    restoreRemoteState(db, operation, message, remote);
                    db.operation().deleteOperation(operation.id);
                }
                return true;
            } catch (JSONException ex) {
                throw new IOException("Invalid Microsoft Graph flag operation", ex);
            }
        }
        if (EntityOperation.DELETE.equals(operation.name))
            return reconcileDelete(context, db, account, operation, message, remote);
        if (EntityOperation.GRAPH_DELETE_PERMANENT.equals(operation.name)) {
            EntityFolder source = db.folder().getFolder(message.folder);
            if (!Objects.equals(remote.id, message.graph_id)) {
                rejectPermanentDelete(db, operation, message,
                        "Microsoft Graph permanent-delete outcome is unknown; remote identity changed.");
                refreshFolder(context, source);
                return false;
            }
            if (source != null && Objects.equals(remote.parentFolderId, source.graph_folder_id)) {
                restoreRemoteState(db, operation, message, remote);
                restoreLocalState(db, operation, message);
                db.operation().setOperationState(operation.id, null);
                db.operation().setOperationError(operation.id, null);
                refreshFolder(context, source);
                return false; // Still in the source folder: retry only after a fresh readback.
            }
            EntityFolder actual = (remote.parentFolderId == null ? null :
                    db.folder().getFolderByGraphId(account.id, remote.parentFolderId));
            if (actual != null) {
                moveLocally(context, db, account, operation, message, actual, remote);
                return true;
            }
            rejectPermanentDelete(db, operation, message,
                    "Microsoft Graph permanent-delete outcome is unknown; the message moved to an unmapped folder.");
            refreshFolder(context, source);
            return false;
        }
        if (EntityOperation.MOVE.equals(operation.name)) {
            try {
                EntityFolder target = db.folder().getFolder(new JSONArray(operation.args).getLong(0));
                EntityFolder source = db.folder().getFolder(message.folder);
                if (remote.parentFolderId == null)
                    return false;
                if (target != null && remote.parentFolderId.equals(target.graph_folder_id)) {
                    moveLocally(context, db, account, operation, message, target, remote);
                    return true;
                }
                if (source != null && remote.parentFolderId.equals(source.graph_folder_id)) {
                    restoreRemoteState(db, operation, message, remote);
                    db.message().setMessageUiHide(message.id, false);
                    db.operation().deleteOperation(operation.id);
                    return true;
                }
                EntityFolder actual = db.folder().getFolderByGraphId(account.id, remote.parentFolderId);
                if (actual != null) {
                    moveLocally(context, db, account, operation, message, actual, remote);
                    return true;
                }
                return false;
            } catch (JSONException ex) {
                throw new IOException("Invalid Microsoft Graph move operation", ex);
            }
        }
        return false;
    }

    private static boolean reconcileDelete(Context context, DB db, EntityAccount account,
                                           EntityOperation operation, EntityMessage message,
                                           GraphMailApi.MessageState remote) {
        EntityFolder source = db.folder().getFolder(message.folder);
        if (source == null || remote.parentFolderId == null)
            return false;

        if (Objects.equals(source.graph_folder_id, remote.parentFolderId)) {
            restoreRemoteState(db, operation, message, remote);
            db.operation().deleteOperation(operation.id);
            return true;
        }

        EntityFolder actual = db.folder().getFolderByGraphId(account.id, remote.parentFolderId);
        if (actual == null)
            return false;
        moveLocally(context, db, account, operation, message, actual, remote);
        return true;
    }

    private static void restoreRemoteState(DB db, EntityOperation operation, EntityMessage message,
                                           GraphMailApi.MessageState remote) {
        if (remote.seen != null) {
            db.message().setMessageSeen(message.id, remote.seen);
            db.message().setMessageUiSeen(message.id, remote.seen);
        }
        if (remote.flagged != null) {
            Integer color = (remote.flagged ? message.color : null);
            db.message().setMessageFlagged(message.id, remote.flagged);
            db.message().setMessageUiFlagged(message.id, remote.flagged, color);
        }
        if (EntityOperation.MOVE.equals(operation.name) || EntityOperation.DELETE.equals(operation.name) ||
                EntityOperation.GRAPH_DELETE_PERMANENT.equals(operation.name)) {
            db.message().setMessageUiHide(message.id, false);
            db.message().setMessageUiBusy(message.id, null);
        }
    }

    private static void restoreLocalState(DB db, EntityOperation operation, EntityMessage message) {
        db.beginTransaction();
        try {
            if (EntityOperation.SEEN.equals(operation.name))
                db.message().setMessageUiSeen(message.id, message.seen);
            else if (EntityOperation.FLAG.equals(operation.name))
                db.message().setMessageUiFlagged(message.id, message.flagged, message.flagged ? message.color : null);
            else if (EntityOperation.MOVE.equals(operation.name) || EntityOperation.DELETE.equals(operation.name) ||
                    EntityOperation.GRAPH_DELETE_PERMANENT.equals(operation.name)) {
                db.message().setMessageUiHide(message.id, false);
                db.message().setMessageUiBusy(message.id, null);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void rejectPermanentDelete(DB db, EntityOperation operation,
                                              EntityMessage message, String error) {
        db.beginTransaction();
        try {
            if (db.operation().getOperation(operation.id) != null) {
                db.operation().setOperationState(operation.id, null);
                db.operation().setOperationError(operation.id, error);
            }
            db.message().setMessageUiHide(message.id, false);
            db.message().setMessageUiBusy(message.id, null);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void markUnknown(DB db, EntityOperation operation) {
        db.operation().setOperationState(operation.id, null);
        db.operation().setOperationError(operation.id, OUTCOME_UNKNOWN_ERROR);
    }

    private static void refreshFolder(Context context, EntityFolder folder) {
        if (folder != null && folder.graph_folder_id != null) {
            EntityOperation.sync(context, folder.id, true);
            ApiMutationLog.event("graph", "refresh_summary", "refresh", "scheduled");
        } else
            ApiMutationLog.event("graph", "refresh_summary", "refresh", "skipped");
    }
}

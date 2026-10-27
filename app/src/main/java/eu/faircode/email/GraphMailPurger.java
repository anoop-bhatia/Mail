package eu.faircode.email;

import android.content.Context;
import android.os.OperationCanceledException;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import javax.mail.MessagingException;

/** Recoverable, Deleted-Items-only Microsoft Graph permanent deletion workflow. */
final class GraphMailPurger {
    static final String OUTCOME_UNKNOWN_ERROR =
            "Microsoft Graph permanent-delete outcome unknown; remote state must be checked before retry.";
    private static final String EXECUTING = "executing";
    private static final String PHASE_ENUMERATING = "enumerating";
    private static final String PHASE_READY = "ready";
    private static final String PHASE_RECONCILE = "reconcile";
    private static final String PHASE_DELETE = "delete";

    private GraphMailPurger() {
    }

    static boolean isPurgeOperation(String name) {
        return (EntityOperation.GRAPH_PURGE_TRASH.equals(name) ||
                EntityOperation.GRAPH_PERMANENT_DELETE.equals(name));
    }

    static boolean isUnknownOutcome(String error) {
        return OUTCOME_UNKNOWN_ERROR.equals(error);
    }

    static boolean process(Context context, DB db, EntityAccount account, GraphMailApi api,
                          Core.State runState, EntityOperation operation)
            throws IOException, OperationCanceledException {
        if (EntityOperation.GRAPH_PURGE_TRASH.equals(operation.name))
            return processTrash(context, db, account, api, runState, operation);
        if (EntityOperation.GRAPH_PERMANENT_DELETE.equals(operation.name))
            return processMessage(context, db, account, api, runState, operation);
        throw new IllegalArgumentException("Unsupported Graph permanent-delete operation");
    }

    private static boolean processTrash(Context context, DB db, EntityAccount account,
                                        GraphMailApi api, Core.State runState,
                                        EntityOperation operation)
            throws IOException, OperationCanceledException {
        EntityFolder folder = db.folder().getFolder(operation.folder);
        if (!isTrashFolder(account, folder, operation.folder)) {
            setOperationError(db, operation, "Graph purge refused: source is not the mapped Deleted Items folder.");
            return false;
        }

        State work;
        try {
            work = loadState(operation, "folder", account, folder, null);
        } catch (IOException ex) {
            setOperationError(db, operation, "Graph purge refused: invalid persisted operation state.");
            return false;
        }
        if (!validWorkIdentity(work, account, folder)) {
            setOperationError(db, operation, "Graph purge refused: configured mailbox or folder mapping changed.");
            return false;
        }
        if (!PHASE_RECONCILE.equals(work.phase) && !work.folderVerified) {
            GraphMailApi.MailFolder remote;
            try {
                remote = getUserFolder(api, work.userPrincipalName, work.folderId);
            } catch (GraphMailApi.ApiException ex) {
                if (isRetryableReadFailure(ex))
                    throw ex;
                setOperationError(db, operation, formatRejected("Deleted Items verification", ex));
                return false;
            } catch (IOException ex) {
                throw ex;
            }
            if (!isDeletedItemsFolder(work.folderId, remote)) {
                setOperationError(db, operation, "Graph purge refused: remote folder is not Deleted Items.");
                return false;
            }
            work.folderVerified = true;
            if (!persistState(db, operation, work, null, null))
                return false;
        }

        int rescanWaves = 0;
        while (true) {
            runState.ensureRunning("Microsoft Graph Empty Recycle Bin");
            if (db.operation().getOperation(operation.id) == null)
                return false;
            if (PHASE_ENUMERATING.equals(work.phase)) {
                Set<String> queued = getQueuedChildIds(db, account, operation.id);
                while (PHASE_ENUMERATING.equals(work.phase)) {
                    runState.ensureRunning("Microsoft Graph Deleted Items enumeration");
                    String requestedLink = work.nextLink;
                    GraphMailApi.Page page = getPurgePage(api, work, requestedLink);
                    if (!advanceEnumeration(db, account, folder, operation, work, page,
                            requestedLink, queued))
                        return false;
                }
            }

            if (PHASE_READY.equals(work.phase)) {
                List<EntityOperation> children = getChildOperations(db, account, operation.id);
                for (EntityOperation child : children) {
                    runState.ensureRunning("Microsoft Graph permanent-delete batch");
                    if (child.error != null && !isUnknownOutcome(child.error))
                        return false;
                    processMessage(context, db, account, api, runState, child);
                    EntityOperation remaining = db.operation().getOperation(child.id);
                    if (remaining != null && remaining.error != null && !isUnknownOutcome(remaining.error))
                        return false;
                }
                if (!getChildOperations(db, account, operation.id).isEmpty())
                    return false;

                runState.ensureRunning("Microsoft Graph Deleted Items verification scan");
                GraphMailApi.Page remaining = listInitialPage(api, work);
                if (remaining.messages.isEmpty() && remaining.nextLink == null) {
                    work.phase = PHASE_RECONCILE;
                    work.nextLink = null;
                    persistState(db, operation, work, null, null);
                    continue;
                }

                work.phase = (remaining.nextLink == null ? PHASE_READY : PHASE_ENUMERATING);
                work.nextLink = remaining.nextLink;
                work.visitedLinks.clear();
                Set<String> queued = new HashSet<>();
                if (!advanceEnumeration(db, account, folder, operation, work,
                        remaining, null, queued))
                    return false;
                rescanWaves++;
                if (rescanWaves >= 3)
                    return false;
                continue;
            }

            if (PHASE_RECONCILE.equals(work.phase)) {
                runState.ensureRunning("Microsoft Graph Deleted Items delta reconciliation");
                try {
                    GraphMailReceiver.syncFolderDelta(context, db, account, api, runState, folder);
                } catch (JSONException | MessagingException ex) {
                    throw new IOException("Microsoft Graph Deleted Items delta reconciliation failed", ex);
                }
                EntityOperation current = db.operation().getOperation(operation.id);
                if (current == null)
                    return true;
                db.beginTransaction();
                try {
                    db.operation().deleteOperation(operation.id);
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
                return true;
            }

            if (!PHASE_ENUMERATING.equals(work.phase) && !PHASE_READY.equals(work.phase)) {
                setOperationError(db, operation, "Graph purge paused: unsupported persisted operation phase.");
                return false;
            }
        }
    }

    private static boolean processMessage(Context context, DB db, EntityAccount account,
                                         GraphMailApi api, Core.State runState,
                                         EntityOperation operation)
            throws IOException, OperationCanceledException {
        EntityFolder folder = db.folder().getFolder(operation.folder);
        EntityMessage local = (operation.message == null ? null : db.message().getMessage(operation.message));
        State work;
        try {
            work = loadState(operation, "message", account, folder, local);
        } catch (IOException ex) {
            setMessageError(db, operation, local,
                    "Graph permanent delete refused: invalid persisted operation state.");
            return false;
        }
        if (!isTrashFolder(account, folder, operation.folder) || !validWorkIdentity(work, account, folder)) {
            setMessageError(db, operation, local,
                    "Graph permanent delete refused: source is not the mapped Deleted Items folder.");
            return false;
        }
        if (work.parentOperationId <= 0) {
            setMessageError(db, operation, local,
                    "Graph permanent delete refused: an Empty Trash parent operation is required.");
            return false;
        }
        if (isEmpty(work.messageId)) {
            setMessageError(db, operation, local, "Graph permanent delete refused: missing remote message ID.");
            return false;
        }
        if (local != null && (!Objects.equals(local.account, account.id) ||
                !Objects.equals(local.graph_id, work.messageId) || !Objects.equals(local.folder, folder.id))) {
            if (work.parentOperationId > 0)
                deleteOperationWithoutMessage(db, operation);
            else
                setMessageError(db, operation, local,
                        "Graph permanent delete skipped: the local message is no longer in Deleted Items.");
            return false;
        }
        if (operation.message != null && local == null && work.parentOperationId == 0) {
            setMessageError(db, operation, null,
                    "Graph permanent delete paused: the queued local message is no longer available.");
            return false;
        }

        if (work.parentOperationId > 0) {
            EntityOperation parent = db.operation().getOperation(work.parentOperationId);
            if (!isValidPurgeChildRelationship(account, folder, operation, parent)) {
                setMessageError(db, operation, local,
                        "Graph permanent delete refused: a matching Empty Trash parent operation is required.");
                return false;
            }
            if (parent.error != null || !parentIsReady(db, account, parent, folder)) {
                persistState(db, operation, work, null, null);
                return false;
            }
        } else if (PHASE_RECONCILE.equals(work.phase)) {
            return reconcileSingle(context, db, account, api, runState, operation, work, folder);
        }

        if (work.parentOperationId == 0 && !work.folderVerified) {
            GraphMailApi.MailFolder remote;
            try {
                remote = getUserFolder(api, work.userPrincipalName, work.folderId);
            } catch (GraphMailApi.ApiException ex) {
                if (isRetryableReadFailure(ex))
                    throw ex;
                setMessageError(db, operation, local, formatRejected("Deleted Items verification", ex));
                return false;
            }
            if (!isDeletedItemsFolder(work.folderId, remote)) {
                setMessageError(db, operation, local,
                        "Graph permanent delete refused: remote folder is not Deleted Items.");
                return false;
            }
            work.folderVerified = true;
            if (!persistState(db, operation, work, null, null))
                return false;
        }

        runState.ensureRunning("Microsoft Graph Deleted Items message validation");
        GraphMailApi.MessageState remote;
        try {
            remote = getUserMessageState(api, work.userPrincipalName, work.messageId);
        } catch (GraphMailApi.ApiException ex) {
            if (ex.status == 404)
                return confirmedMessageDeletion(context, db, account, api, runState,
                        operation, work, folder);
            if (isRetryableReadFailure(ex))
                throw ex;
            setMessageError(db, operation, local, formatRejected("message validation", ex));
            return false;
        }

        if (!Objects.equals(remote.id, work.messageId)) {
            setMessageError(db, operation, local,
                    "Graph permanent delete refused: remote message identity did not match the queued ID.");
            return false;
        }
        if (!Objects.equals(remote.parentFolderId, work.folderId)) {
            if (work.parentOperationId > 0) {
                deleteOperationWithoutMessage(db, operation);
                EntityFolder actual = db.folder().getFolderByGraphId(account.id, remote.parentFolderId);
                scheduleFolderSync(context, folder);
                scheduleFolderSync(context, actual);
            } else {
                setMessageError(db, operation, local,
                        "Graph permanent delete skipped: the message is no longer in Deleted Items.");
                EntityFolder actual = db.folder().getFolderByGraphId(account.id, remote.parentFolderId);
                scheduleFolderSync(context, folder);
                scheduleFolderSync(context, actual);
            }
            return false;
        }

        runState.ensureRunning("Microsoft Graph permanent delete");
        if (!persistState(db, operation, work, EXECUTING, null))
            return false;
        if (work.parentOperationId > 0) {
            EntityOperation parent = db.operation().getOperation(work.parentOperationId);
            if (!isValidPurgeChildRelationship(account, folder, operation, parent)) {
                setMessageError(db, operation, local,
                        "Graph permanent delete refused: a matching Empty Trash parent operation is required.");
                return false;
            }
            if (parent.error != null || !parentIsReady(db, account, parent, folder)) {
                persistState(db, operation, work, null, null);
                return false;
            }
        }
        try {
            api.permanentDeleteMessage(work.userPrincipalName, work.folderId, work.messageId);
        } catch (GraphMailApi.ApiException ex) {
            if (ex.status == 404)
                return verifyNotFoundAfterPermanentDelete(context, db, account, api, runState,
                        operation, work, folder, local);
            if (ex.status == 429) {
                persistState(db, operation, work, null, null);
                throw ex;
            }
            if (ex.status == 408 || ex.status >= 500) {
                persistState(db, operation, work, null, OUTCOME_UNKNOWN_ERROR);
                throw ex;
            }
            setMessageError(db, operation, local, formatRejected("permanentDelete", ex));
            return false;
        } catch (IOException ex) {
            persistState(db, operation, work, null, OUTCOME_UNKNOWN_ERROR);
            throw ex;
        } catch (JSONException ex) {
            persistState(db, operation, work, null, null);
            throw new IOException("Unable to encode Microsoft Graph permanent delete", ex);
        }

        return confirmedMessageDeletion(context, db, account, api, runState,
                operation, work, folder);
    }

    private static boolean confirmedMessageDeletion(Context context, DB db, EntityAccount account,
                                                    GraphMailApi api, Core.State runState,
                                                    EntityOperation operation, State work,
                                                    EntityFolder folder)
            throws IOException, OperationCanceledException {
        if (work.parentOperationId > 0) {
            deleteMessageAndOperation(db, account, operation, work.messageId);
            return true;
        }
        work.phase = PHASE_RECONCILE;
        persistState(db, operation, work, null, null);
        return reconcileSingle(context, db, account, api, runState, operation, work, folder);
    }

    private static boolean verifyNotFoundAfterPermanentDelete(Context context, DB db,
                                                              EntityAccount account, GraphMailApi api,
                                                              Core.State runState,
                                                              EntityOperation operation, State work,
                                                              EntityFolder folder, EntityMessage local)
            throws IOException, OperationCanceledException {
        GraphMailApi.MessageState after;
        try {
            after = getUserMessageState(api, work.userPrincipalName, work.messageId);
        } catch (GraphMailApi.ApiException ex) {
            if (ex.status == 404)
                return confirmedMessageDeletion(context, db, account, api, runState,
                        operation, work, folder);
            if (isRetryableReadFailure(ex)) {
                persistState(db, operation, work, null, OUTCOME_UNKNOWN_ERROR);
                throw ex;
            }
            setMessageError(db, operation, local,
                    "Graph permanentDelete returned HTTP 404; follow-up verification was rejected (HTTP " +
                            ex.status + ").");
            return false;
        } catch (IOException ex) {
            persistState(db, operation, work, null, OUTCOME_UNKNOWN_ERROR);
            throw ex;
        }

        if (!Objects.equals(after.id, work.messageId)) {
            setMessageError(db, operation, local,
                    "Graph permanentDelete returned HTTP 404, but the follow-up message identity did not match.");
            return false;
        }
        if (!Objects.equals(after.parentFolderId, work.folderId)) {
            if (work.parentOperationId > 0) {
                deleteOperationWithoutMessage(db, operation);
                EntityFolder actual = db.folder().getFolderByGraphId(account.id, after.parentFolderId);
                scheduleFolderSync(context, folder);
                scheduleFolderSync(context, actual);
            } else {
                setMessageError(db, operation, local,
                        "Graph permanentDelete returned HTTP 404, but the message is no longer in Deleted Items.");
                EntityFolder actual = db.folder().getFolderByGraphId(account.id, after.parentFolderId);
                scheduleFolderSync(context, folder);
                scheduleFolderSync(context, actual);
            }
            return false;
        }

        setMessageError(db, operation, local,
                "Graph permanentDelete returned HTTP 404, but the message remains in Deleted Items; " +
                        "no local rows were removed.");
        return false;
    }

    private static boolean reconcileSingle(Context context, DB db, EntityAccount account,
                                           GraphMailApi api, Core.State runState,
                                           EntityOperation operation, State work,
                                           EntityFolder folder)
            throws IOException, OperationCanceledException {
        runState.ensureRunning("Microsoft Graph Trash delta reconciliation");
        try {
            GraphMailReceiver.syncFolderDelta(context, db, account, api, runState, folder);
        } catch (JSONException | MessagingException ex) {
            throw new IOException("Microsoft Graph Trash delta reconciliation failed", ex);
        }
        EntityOperation current = db.operation().getOperation(operation.id);
        if (current == null)
            return true;
        deleteMessageAndOperation(db, account, current, work.messageId);
        return true;
    }

    private static boolean advanceEnumeration(DB db, EntityAccount account, EntityFolder folder,
                                              EntityOperation operation, State work,
                                              GraphMailApi.Page page, String requestedLink,
                                              Set<String> queued)
            throws IOException {
        validateContinuation(work, requestedLink, page.nextLink);
        db.beginTransaction();
        try {
            if (db.operation().getOperation(operation.id) == null) {
                db.setTransactionSuccessful();
                return false;
            }
            for (GraphMailApi.MailMessage reference : page.messages) {
                if (isEmpty(reference.id) || !queued.add(reference.id))
                    continue;
                EntityMessage cached = db.message().getMessageByGraphId(account.id, reference.id);
                EntityOperation child = new EntityOperation();
                child.account = account.id;
                child.folder = folder.id;
                child.message = (cached != null && Objects.equals(cached.folder, folder.id)
                        ? cached.id : null);
                child.name = EntityOperation.GRAPH_PERMANENT_DELETE;
                child.args = new JSONArray()
                        .put(work.folderId)
                        .put(operation.id)
                        .put(reference.id)
                        .toString();
                child.created = System.currentTimeMillis();
                child.id = db.operation().insertOperation(child);
            }
            work.phase = (page.nextLink == null ? PHASE_READY : PHASE_ENUMERATING);
            work.nextLink = page.nextLink;
            if (requestedLink != null)
                work.visitedLinks.add(requestedLink);
            work.args = encodeState(work);
            db.operation().setOperationArgs(operation.id, work.args);
            db.operation().setOperationState(operation.id, null);
            db.operation().setOperationError(operation.id, null);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private static String encodeState(State work) throws IOException {
        try {
            return new JSONArray().put(work.toJson()).toString();
        } catch (JSONException ex) {
            throw new IOException("Unable to encode Microsoft Graph purge state", ex);
        }
    }

    private static void validateContinuation(State work, String requestedLink, String nextLink)
            throws IOException {
        if (requestedLink != null && work.visitedLinks.contains(requestedLink))
            throw new IOException("Microsoft Graph repeated a Deleted Items continuation link");
        if (nextLink != null && (nextLink.equals(requestedLink) || work.visitedLinks.contains(nextLink)))
            throw new IOException("Microsoft Graph returned a repeated Deleted Items continuation link");
    }

    private static GraphMailApi.Page getPurgePage(GraphMailApi api, State work, String link)
            throws IOException {
        if (link == null)
            return listInitialPage(api, work);
        try {
            return api.listUserFolderMessages(link, true);
        } catch (JSONException ex) {
            throw new IOException("Unable to parse Microsoft Graph Deleted Items page", ex);
        }
    }

    private static GraphMailApi.Page listInitialPage(GraphMailApi api, State work) throws IOException {
        try {
            return api.listUserFolderMessages(work.userPrincipalName, work.folderId);
        } catch (JSONException ex) {
            throw new IOException("Unable to parse Microsoft Graph Deleted Items page", ex);
        }
    }

    private static List<EntityOperation> getChildOperations(DB db, EntityAccount account, long parentId) {
        List<EntityOperation> result = new ArrayList<>();
        for (EntityOperation candidate : db.operation().getOperations(
                account.id, EntityOperation.GRAPH_PERMANENT_DELETE))
            if (getParentOperationId(candidate) == parentId)
                result.add(candidate);
        Collections.sort(result, (left, right) -> Long.compare(
                left.id == null ? 0 : left.id, right.id == null ? 0 : right.id));
        return result;
    }

    private static Set<String> getQueuedChildIds(DB db, EntityAccount account, long parentId) {
        Set<String> result = new HashSet<>();
        for (EntityOperation candidate : getChildOperations(db, account, parentId)) {
            String messageId = getQueuedMessageId(candidate);
            if (!isEmpty(messageId))
                result.add(messageId);
        }
        return result;
    }

    static boolean isStandalonePermanentDelete(EntityOperation operation) {
        return (operation != null && EntityOperation.GRAPH_PERMANENT_DELETE.equals(operation.name) &&
                getParentOperationId(operation) == 0);
    }

    static boolean canDowngradeStandalonePermanentDelete(EntityOperation operation,
                                                         EntityAccount account,
                                                         EntityFolder folder,
                                                         EntityMessage message) {
        if (!isStandalonePermanentDelete(operation) || account == null || !account.isGraphApi() ||
                folder == null || message == null || account.user == null ||
                !Objects.equals(operation.account, account.id) ||
                !Objects.equals(operation.folder, folder.id) ||
                !Objects.equals(operation.message, message.id) ||
                !Objects.equals(folder.account, account.id) || !EntityFolder.TRASH.equals(folder.type) ||
                isEmpty(folder.graph_folder_id) || !Objects.equals(message.account, account.id) ||
                !Objects.equals(message.folder, folder.id) || isEmpty(message.graph_id))
            return false;

        try {
            JSONArray args = new JSONArray(operation.args);
            if (args.length() == 1 && args.optJSONObject(0) != null) {
                JSONObject state = args.getJSONObject(0);
                return ("message".equals(state.optString("kind")) &&
                        state.optLong("parentOperationId", 0) == 0 &&
                        Objects.equals(state.optString("userPrincipalName", null), account.user.trim()) &&
                        Objects.equals(state.optString("folderId", null), folder.graph_folder_id) &&
                        Objects.equals(state.optString("messageId", null), message.graph_id));
            }
            return (args.length() == 1 &&
                    Objects.equals(args.optString(0, null), folder.graph_folder_id));
        } catch (JSONException ex) {
            return false;
        }
    }

    static long getParentOperationId(EntityOperation operation) {
        try {
            JSONArray args = new JSONArray(operation.args);
            JSONObject state = (args.length() == 1 ? args.optJSONObject(0) : null);
            if (state != null)
                return state.optLong("parentOperationId", 0);
            return (args.length() > 1 && !args.isNull(1) ? args.optLong(1, 0) : 0);
        } catch (JSONException ex) {
            return 0;
        }
    }

    static boolean isValidPurgeChildRelationship(EntityAccount account, EntityFolder folder,
                                                 EntityOperation child, EntityOperation parent) {
        return (account != null && folder != null && child != null && parent != null &&
                EntityOperation.GRAPH_PERMANENT_DELETE.equals(child.name) &&
                EntityOperation.GRAPH_PURGE_TRASH.equals(parent.name) &&
                Objects.equals(child.account, account.id) && Objects.equals(parent.account, account.id) &&
                Objects.equals(child.folder, folder.id) && Objects.equals(parent.folder, folder.id) &&
                parent.id != null && getParentOperationId(child) == parent.id &&
                isTrashFolder(account, folder, child.folder));
    }

    private static String getQueuedMessageId(EntityOperation operation) {
        try {
            JSONArray args = new JSONArray(operation.args);
            JSONObject state = (args.length() == 1 ? args.optJSONObject(0) : null);
            if (state != null)
                return state.optString("messageId", null);
            return (args.length() > 2 ? args.optString(2, null) : null);
        } catch (JSONException ex) {
            return null;
        }
    }

    private static boolean parentIsReady(DB db, EntityAccount account,
                                         EntityOperation parent, EntityFolder folder) {
        if (parent == null || !EntityOperation.GRAPH_PURGE_TRASH.equals(parent.name) ||
                !Objects.equals(parent.account, account.id) || !Objects.equals(parent.folder, folder.id))
            return false;
        try {
            State state = loadState(parent, "folder", account, folder, null);
            return (PHASE_READY.equals(state.phase) && state.folderVerified &&
                    Objects.equals(state.folderId, folder.graph_folder_id));
        } catch (IOException ex) {
            return false;
        }
    }

    private static boolean isTrashFolder(EntityAccount account, EntityFolder folder, Long operationFolder) {
        return (account != null && account.isGraphApi() && folder != null &&
                Objects.equals(folder.account, account.id) && Objects.equals(folder.id, operationFolder) &&
                EntityFolder.TRASH.equals(folder.type) && !Boolean.TRUE.equals(folder.read_only) &&
                !Boolean.FALSE.equals(folder.selectable) && !isEmpty(folder.graph_folder_id));
    }

    static boolean isDeletedItemsFolder(String expectedFolderId, GraphMailApi.MailFolder remote) {
        return (!isEmpty(expectedFolderId) && remote != null &&
                Objects.equals(expectedFolderId, remote.id) &&
                EntityFolder.TRASH.equals(GraphFolderDefaults.getFolderType(remote.name)));
    }

    private static boolean validWorkIdentity(State work, EntityAccount account, EntityFolder folder) {
        return (work != null && folder != null && !isEmpty(work.userPrincipalName) &&
                account != null && account.user != null &&
                account.user.trim().equals(work.userPrincipalName) &&
                !isEmpty(work.folderId) && Objects.equals(folder.graph_folder_id, work.folderId));
    }

    private static State loadState(EntityOperation operation, String kind,
                                   EntityAccount account, EntityFolder folder,
                                   EntityMessage message) throws IOException {
        try {
            JSONArray args = new JSONArray(operation.args);
            if (args.length() == 1 && args.optJSONObject(0) != null) {
                State state = State.fromJson(args.getJSONObject(0));
                if (!kind.equals(state.kind))
                    throw new IOException("Unexpected Graph purge state kind");
                if (!isValidPhase(kind, state.phase))
                    throw new IOException("Unsupported Graph purge state phase");
                return state;
            }
            if ("folder".equals(kind) && args.length() == 0)
                return State.newFolder(account.user, folder.graph_folder_id);
            if ("message".equals(kind)) {
                String folderId = args.optString(0, null);
                long parentId = (args.length() > 1 && !args.isNull(1) ? args.optLong(1, 0) : 0);
                String messageId = (args.length() > 2 ? args.optString(2, null) :
                        (message == null ? null : message.graph_id));
                return State.newMessage(account.user, folderId, parentId, messageId);
            }
            throw new IOException("Invalid Graph purge arguments");
        } catch (JSONException ex) {
            throw new IOException("Invalid Graph purge arguments", ex);
        }
    }

    private static boolean isValidPhase(String kind, String phase) {
        if ("folder".equals(kind))
            return (PHASE_ENUMERATING.equals(phase) || PHASE_READY.equals(phase) ||
                    PHASE_RECONCILE.equals(phase));
        if ("message".equals(kind))
            return (PHASE_DELETE.equals(phase) || PHASE_RECONCILE.equals(phase));
        return false;
    }

    private static boolean isRetryableReadFailure(GraphMailApi.ApiException ex) {
        return (ex.status == 408 || ex.status == 429 || ex.status == 503 || ex.status >= 500);
    }

    private static String formatRejected(String action, GraphMailApi.ApiException ex) {
        return "Microsoft Graph " + action + " rejected (HTTP " + ex.status +
                (isEmpty(ex.errorCode) ? "" : ", code " + ex.errorCode) + ").";
    }

    private static boolean persistState(DB db, EntityOperation operation, State work,
                                        String state, String error) throws IOException {
        work.args = encodeState(work);
        db.beginTransaction();
        try {
            if (db.operation().getOperation(operation.id) == null) {
                db.setTransactionSuccessful();
                return false;
            }
            db.operation().setOperationArgs(operation.id, work.args);
            db.operation().setOperationState(operation.id, state);
            db.operation().setOperationError(operation.id, error);
            db.setTransactionSuccessful();
            return true;
        } finally {
            db.endTransaction();
        }
    }

    private static void setOperationError(DB db, EntityOperation operation, String error) {
        db.beginTransaction();
        try {
            if (db.operation().getOperation(operation.id) != null) {
                db.operation().setOperationState(operation.id, null);
                db.operation().setOperationError(operation.id, error);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void setMessageError(DB db, EntityOperation operation,
                                        EntityMessage message, String error) {
        db.beginTransaction();
        try {
            if (db.operation().getOperation(operation.id) != null) {
                db.operation().setOperationState(operation.id, null);
                db.operation().setOperationError(operation.id, error);
            }
            if (message != null && message.id != null) {
                db.message().setMessageUiHide(message.id, false);
                db.message().setMessageUiBusy(message.id, null);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void deleteOperationWithoutMessage(DB db, EntityOperation operation) {
        db.beginTransaction();
        try {
            db.operation().deleteOperation(operation.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void deleteMessageAndOperation(DB db, EntityAccount account,
                                                  EntityOperation operation, String graphId) {
        db.beginTransaction();
        try {
            EntityMessage cached = db.message().getMessageByGraphId(account.id, graphId);
            if (cached != null)
                db.message().deleteMessage(cached.id);
            db.operation().deleteOperation(operation.id);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private static void scheduleFolderSync(Context context, EntityFolder folder) {
        if (folder != null && !isEmpty(folder.graph_folder_id))
            EntityOperation.sync(context, folder.id, true);
    }

    private static GraphMailApi.MailFolder getUserFolder(GraphMailApi api, String userPrincipalName,
                                                         String folderId) throws IOException {
        try {
            return api.getUserMailFolder(userPrincipalName, folderId);
        } catch (JSONException ex) {
            throw new IOException("Unable to parse Microsoft Graph folder identity", ex);
        }
    }

    private static GraphMailApi.MessageState getUserMessageState(GraphMailApi api,
                                                                 String userPrincipalName,
                                                                 String messageId) throws IOException {
        try {
            return api.getUserMessageState(userPrincipalName, messageId);
        } catch (JSONException ex) {
            throw new IOException("Unable to parse Microsoft Graph message state", ex);
        }
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }

    private static final class State {
        int version = 1;
        String kind;
        String phase;
        String userPrincipalName;
        String folderId;
        String messageId;
        long parentOperationId;
        String nextLink;
        boolean folderVerified;
        List<String> visitedLinks = new ArrayList<>();
        String args;

        static State newFolder(String userPrincipalName, String folderId) {
            State state = new State();
            state.kind = "folder";
            state.phase = PHASE_ENUMERATING;
            state.userPrincipalName = (userPrincipalName == null ? null : userPrincipalName.trim());
            state.folderId = folderId;
            state.args = "[]";
            return state;
        }

        static State newMessage(String userPrincipalName, String folderId,
                                long parentOperationId, String messageId) {
            State state = new State();
            state.kind = "message";
            state.phase = PHASE_DELETE;
            state.userPrincipalName = (userPrincipalName == null ? null : userPrincipalName.trim());
            state.folderId = folderId;
            state.parentOperationId = parentOperationId;
            state.messageId = messageId;
            state.args = "[]";
            return state;
        }

        JSONObject toJson() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("version", version);
            json.put("kind", kind);
            json.put("phase", phase);
            json.put("userPrincipalName", userPrincipalName);
            json.put("folderId", folderId);
            if (messageId != null)
                json.put("messageId", messageId);
            if (parentOperationId > 0)
                json.put("parentOperationId", parentOperationId);
            if (nextLink != null)
                json.put("nextLink", nextLink);
            json.put("folderVerified", folderVerified);
            JSONArray visited = new JSONArray();
            for (String link : visitedLinks)
                visited.put(link);
            json.put("visitedLinks", visited);
            return json;
        }

        static State fromJson(JSONObject json) throws IOException {
            if (json.optInt("version", -1) != 1)
                throw new IOException("Unsupported Graph purge state version");
            State state = new State();
            state.version = json.optInt("version");
            state.kind = json.optString("kind", null);
            state.phase = json.optString("phase", null);
            state.userPrincipalName = json.optString("userPrincipalName", null);
            state.folderId = json.optString("folderId", null);
            state.messageId = json.optString("messageId", null);
            state.parentOperationId = json.optLong("parentOperationId", 0);
            state.nextLink = json.optString("nextLink", null);
            state.folderVerified = json.optBoolean("folderVerified", false);
            JSONArray visited = json.optJSONArray("visitedLinks");
            if (visited != null)
                for (int i = 0; i < visited.length(); i++) {
                    String link = visited.optString(i, null);
                    if (link != null)
                        state.visitedLinks.add(link);
                }
            state.args = new JSONArray().put(json).toString();
            return state;
        }
    }
}

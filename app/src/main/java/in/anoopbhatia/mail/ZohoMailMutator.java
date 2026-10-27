package in.anoopbhatia.mail;

import android.content.Context;
import android.os.OperationCanceledException;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
                String remoteFolderId;
                ApiMutationLog.OperationContext refreshPrevious =
                        ApiMutationLog.setCurrent("zoho", current.name);
                try {
                    state.ensureRunning("Zoho Mail REST folder-list refresh");
                    remoteFolderId = refreshFolderMapping(db, account, folder, current.name, api);
                } catch (IOException ex) {
                    ApiMutationLog.failure("zoho", "folder_refresh", current.name,
                            "no_mutation", ex, trace);
                    throw ex;
                } finally {
                    ApiMutationLog.restoreCurrent(refreshPrevious);
                }
                if (remoteFolderId == null) {
                    IOException refusal = new IOException(
                            "Zoho server/local system-folder mapping is missing or ambiguous");
                    ApiMutationLog.failure("zoho", "folder_refresh", current.name,
                            "no_unique_mapping", refusal, trace);
                    rejectWithoutSending(db, current,
                            "Zoho system folder could not be identified unambiguously; no empty-folder request was sent.");
                    continue;
                }
                db.operation().setOperationState(current.id, EXECUTING);
                ApiMutationLog.OperationContext previous = ApiMutationLog.setCurrent("zoho", current.name);
                try {
                    if (EntityOperation.ZOHO_PURGE_TRASH.equals(current.name))
                        purgeTrashMessages(db, account, current, folder, remoteFolderId, api, state);
                    else {
                        state.ensureRunning("Zoho Mail REST empty folder");
                        api.emptyFolder(remoteFolderId);
                    }
                    emptyFolderLocally(db, account, current, folder, remoteFolderId);
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
                if (EntityOperation.SEEN.equals(work.name) || EntityOperation.FLAG.equals(work.name))
                    reconcileMembership(context, db, account.id, message, api,
                            work.source.zoho_folder_id);
                completed++;
                ApiMutationLog.event("zoho", "local_commit", current.name, "success");
                ApiMutationLog.event("zoho", "operation_summary", current.name, "success");
            } catch (Throwable ex) {
                logFailure(current, ex);
                if (ex instanceof ZohoApi.ApiException &&
                        ((ZohoApi.ApiException) ex).isMessageAbsentFromFolder() &&
                        handleStaleMessage(context, db, current, message, work, api)) {
                    ApiMutationLog.event("zoho", "stale_identity", current.name,
                            "retired_and_refreshed", trace);
                    completed++;
                    continue;
                }
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

    private static boolean handleStaleMessage(Context context, DB db,
                                              EntityOperation operation,
                                              EntityMessage message, Work work, ZohoApi api) {
        if (message == null || work == null ||
                !(EntityOperation.SEEN.equals(operation.name) ||
                        EntityOperation.FLAG.equals(operation.name) ||
                        EntityOperation.MOVE.equals(operation.name)))
            return false;
        // Do not retry an identity rejected by Zoho. Another client may have moved or removed
        // the message; the next head sync will resolve the current folder membership.
        db.operation().deleteOperation(operation.id);
        restoreLocalState(db, operation, message);
        if (EntityOperation.SEEN.equals(operation.name) || EntityOperation.FLAG.equals(operation.name))
            reconcileMembership(context, db, message.account, message, api, work.source.zoho_folder_id);
        refreshFolder(context, work.source);
        refreshFolder(context, work.target);
        return true;
    }

    private static void refreshFolder(Context context, EntityFolder folder) {
        if (folder != null && ZohoApi.isValidRemoteId(folder.zoho_folder_id)) {
            EntityOperation.sync(context, folder.id, true);
            ApiMutationLog.event("zoho", "refresh_summary", "refresh", "scheduled");
        }
    }

    /** Query only known local folders for this message; never scan a mailbox page. */
    private static void reconcileMembership(Context context, DB db, long accountId, EntityMessage message,
                                            ZohoApi api, String sourceFolderId) {
        List<String> confirmed;
        try {
            confirmed = findMembership(api, message.zoho_id, sourceFolderId,
                    db.folder().getFolders(accountId, false, false), message.zoho_folder_ids);
        } catch (IOException | JSONException ex) {
            // The write has already succeeded. Failed verification must not replay it,
            // nor remove memberships on the strength of incomplete readback.
            ApiMutationLog.event("zoho", "membership_readback", "refresh", "unverified");
            return;
        }
        if (confirmed == null)
            return;
        StringBuilder memberships = new StringBuilder("|");
        for (String folderId : confirmed)
            memberships.append(folderId).append('|');
        String updated = memberships.toString();
        EntityFolder primary = db.folder().getFolderByZohoId(accountId, confirmed.get(0));
        db.runInTransaction(() -> {
            db.message().setMessageZohoFolderIds(message.id, updated);
            if (primary != null)
                db.message().setMessageFolder(message.id, primary.id);
        });
        message.zoho_folder_ids = updated;
        if (primary != null)
            message.folder = primary.id;
        refreshFolder(context, primary);
    }

    static List<String> findMembership(ZohoApi api, String messageId, String sourceFolderId,
                                        List<EntityFolder> folders, String current)
            throws IOException, JSONException {
        if (ZohoApi.isValidRemoteId(sourceFolderId)) {
            try {
                api.getMessageMetadata(sourceFolderId, messageId);
                return null;
            } catch (ZohoApi.MembershipChangedException ex) {
                List<String> confirmed = new ArrayList<>();
                confirmed.add(ex.metadata.folderId);
                for (String old : folderIds(current))
                    if (!sourceFolderId.equals(old) && !confirmed.contains(old))
                        confirmed.add(old);
                return confirmed;
            } catch (ZohoApi.ApiException ex) {
                if (!ex.isMessageAbsentFromFolder())
                    throw ex;
            }
        }
        List<String> confirmed = new ArrayList<>();
        Set<String> queried = new HashSet<>();
        if (ZohoApi.isValidRemoteId(sourceFolderId))
            queried.add(sourceFolderId);
        for (EntityFolder folder : folders) {
            if (!ZohoApi.isValidRemoteId(folder.zoho_folder_id))
                continue;
            if (!queried.add(folder.zoho_folder_id))
                continue;
            try {
                ZohoApi.MessageMetadata metadata =
                        api.getMessageMetadata(folder.zoho_folder_id, messageId);
                if (!confirmed.contains(metadata.folderId))
                    confirmed.add(metadata.folderId);
            } catch (ZohoApi.ApiException ex) {
                if (!ex.isMessageAbsentFromFolder())
                    throw ex;
            }
        }
        if (confirmed.isEmpty())
            return null;
        for (String old : folderIds(current))
            if (!queried.contains(old) && !confirmed.contains(old))
                confirmed.add(old);
        return confirmed;
    }

    /** Re-read the first page after each batch because deleting messages shifts later offsets. */
    private static void purgeTrashMessages(DB db, EntityAccount account, EntityOperation operation,
                                           EntityFolder folder, String remoteFolderId, ZohoApi api,
                                           Core.State state)
            throws IOException, JSONException, OperationCanceledException {
        Set<String> deleted = new HashSet<>();
        while (true) {
            state.ensureRunning("Zoho Mail REST Trash listing");
            ZohoApi.Page page = api.listMessages(remoteFolderId, 1, ZohoApi.MAX_PAGE_SIZE);
            if (page.resultItemCount != page.messages.size())
                throw new IOException("Zoho Trash listing contained messages that could not be identified safely");
            if (page.messages.isEmpty()) {
                EntityFolder currentFolder = db.folder().getFolder(folder.id);
                if (!isCurrentTrashMapping(db, account, operation, currentFolder, remoteFolderId))
                    throw new IOException("Zoho Trash mapping changed during empty operation");
                return;
            }

            Set<String> pageIds = new HashSet<>();
            List<String> batch = new ArrayList<>();
            for (ZohoApi.MailMessage message : page.messages) {
                if (!ZohoApi.isValidRemoteId(message.id))
                    throw new IOException("Zoho Trash listing contained an invalid message ID");
                if (!pageIds.add(message.id))
                    continue;
                if (deleted.contains(message.id))
                    throw new IOException("Zoho Trash listing repeated a message after confirmed deletion");
                batch.add(message.id);
            }
            if (batch.isEmpty())
                throw new IOException("Zoho Trash listing made no safe deletion progress");

            for (String messageId : batch) {
                state.ensureRunning("Zoho Mail REST Trash deletion");
                EntityFolder currentFolder = db.folder().getFolder(folder.id);
                if (!isCurrentTrashMapping(db, account, operation, currentFolder, remoteFolderId))
                    throw new IOException("Zoho Trash mapping changed during empty operation");

                // A successful response confirms only this message; unknown outcomes are
                // quarantined by handleFailure and are never silently replayed.
                api.deleteMessage(remoteFolderId, messageId, true);
                removeTrashMessageLocally(db, account, currentFolder, remoteFolderId, messageId);
                deleted.add(messageId);
            }
        }
    }

    private static boolean isCurrentTrashMapping(DB db, EntityAccount account,
                                                 EntityOperation operation, EntityFolder folder,
                                                 String remoteFolderId) {
        return (folder != null && Objects.equals(operation.folder, folder.id) &&
                canEmptyTrash(account, folder) &&
                Objects.equals(folder.zoho_folder_id, remoteFolderId) &&
                hasUniqueLocalFolderMapping(db, account, folder, EntityFolder.TRASH,
                        remoteFolderId, remoteFolderId));
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

    private static String refreshFolderMapping(DB db, EntityAccount account, EntityFolder folder,
                                               String operation, ZohoApi api) throws IOException {
        String expectedType = (EntityOperation.ZOHO_PURGE_TRASH.equals(operation)
                ? EntityFolder.TRASH : EntityFolder.JUNK);
        List<ZohoApi.Folder> remoteFolders;
        try {
            remoteFolders = api.listFolders();
        } catch (JSONException ex) {
            throw new IOException("Unable to parse the Zoho server folder list", ex);
        }

        ZohoApi.Folder remote = ZohoFolderDefaults.findUniqueSystemFolder(remoteFolders, expectedType);
        if (remote == null)
            return null;

        String previousRemoteId = folder.zoho_folder_id;
        if (!Objects.equals(previousRemoteId, remote.id))
            for (ZohoApi.Folder candidate : remoteFolders)
                if (Objects.equals(candidate.id, previousRemoteId))
                    return null;

        if (!hasUniqueLocalFolderMapping(db, account, folder, expectedType,
                previousRemoteId, remote.id))
            return null;

        if (!Objects.equals(previousRemoteId, remote.id)) {
            if (!updateLocalFolderMapping(db, account, folder, expectedType,
                    previousRemoteId, remote.id))
                return null;
            folder.zoho_folder_id = remote.id;
        }
        return remote.id;
    }

    private static boolean hasUniqueLocalFolderMapping(DB db, EntityAccount account,
                                                       EntityFolder folder, String expectedType,
                                                       String previousRemoteId, String remoteFolderId) {
        int systemFolders = 0;
        for (EntityFolder candidate : db.folder().getFolders(account.id, false, false)) {
            if (expectedType.equals(candidate.type)) {
                systemFolders++;
                if (!Objects.equals(candidate.id, folder.id) ||
                        !candidate.selectable || candidate.read_only)
                    return false;
            }
            if (!Objects.equals(candidate.id, folder.id) &&
                    (Objects.equals(candidate.zoho_folder_id, previousRemoteId) ||
                            Objects.equals(candidate.zoho_folder_id, remoteFolderId)))
                return false;
        }
        return systemFolders == 1;
    }

    private static boolean updateLocalFolderMapping(DB db, EntityAccount account,
                                                    EntityFolder folder, String expectedType,
                                                    String previousRemoteId, String remoteFolderId) {
        return db.completeInTransaction(() -> {
            EntityFolder current = db.folder().getFolder(folder.id);
            if (!canEmptyFolder(account, current) || !expectedType.equals(current.type) ||
                    !Objects.equals(current.zoho_folder_id, previousRemoteId) ||
                    !hasUniqueLocalFolderMapping(db, account, current, expectedType,
                            previousRemoteId, remoteFolderId))
                return TransactionResult.rollback(false);

            for (EntityMessage message : db.message().getZohoMessagesInFolderOrMembership(
                    account.id, current.id, previousRemoteId)) {
                String memberships = replaceFolderId(message.zoho_folder_ids,
                        previousRemoteId, remoteFolderId);
                if (!Objects.equals(memberships, message.zoho_folder_ids)) {
                    message.zoho_folder_ids = memberships;
                    db.message().updateMessage(message);
                }
            }

            current.zoho_folder_id = remoteFolderId;
            if (db.folder().updateFolder(current) != 1)
                return TransactionResult.rollback(false);
            return TransactionResult.commit(true);
        });
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

    static boolean isTerminalFailedEmptyFolderOperation(EntityOperation operation,
                                                        long folder, String name) {
        return (operation != null &&
                (EntityOperation.ZOHO_PURGE_TRASH.equals(name) ||
                        EntityOperation.ZOHO_PURGE_SPAM.equals(name)) &&
                Objects.equals(operation.folder, folder) && Objects.equals(operation.name, name) &&
                operation.state == null && operation.error != null);
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
        db.runInTransaction(() -> {
            db.message().setMessageSeen(message.id, seen);
            db.message().setMessageUiSeen(message.id, seen);
            db.operation().deleteOperation(operation.id);
        });
    }

    private static void applyFlag(DB db, EntityOperation operation, EntityMessage message,
                                  boolean flagged, Integer color) {
        db.runInTransaction(() -> {
            db.message().setMessageFlagged(message.id, flagged);
            db.message().setMessageUiFlagged(message.id, flagged, flagged ? color : null);
            db.operation().deleteOperation(operation.id);
        });
    }

    private static void moveLocally(DB db, EntityOperation operation,
                                    EntityMessage message, EntityFolder source, EntityFolder target) {
        db.runInTransaction(() -> {
            message.folder = target.id;
            message.zoho_folder_ids = addFolderId(
                    removeFolderId(message.zoho_folder_ids, source.zoho_folder_id), target.zoho_folder_id);
            message.ui_hide = false;
            message.ui_deleted = false;
            message.ui_busy = null;
            db.message().updateMessage(message);
            db.operation().deleteOperation(operation.id);
        });
    }

    private static void deleteLocally(DB db, EntityOperation operation, EntityMessage message) {
        db.runInTransaction(() -> {
            db.operation().deleteOperation(operation.id);
            db.message().deleteMessage(message.id);
        });
    }

    private static void removeTrashMessageLocally(DB db, EntityAccount account, EntityFolder folder,
                                                  String remoteFolderId, String messageId) {
        db.runInTransaction(() -> {
            removeFolderMembershipLocally(db, account, folder, remoteFolderId, messageId);
        });
    }

    private static void emptyFolderLocally(DB db, EntityAccount account,
                                           EntityOperation operation, EntityFolder folder,
                                           String remoteFolderId) {
        db.runInTransaction(() -> {
            removeFolderMembershipLocally(db, account, folder, remoteFolderId, null);
            db.operation().deleteOperation(operation.id);
        });
    }

    private static void removeFolderMembershipLocally(DB db, EntityAccount account,
                                                       EntityFolder folder, String remoteFolderId,
                                                       String onlyMessageId) {
        for (EntityMessage message : db.message().getZohoMessagesInFolderOrMembership(
                account.id, folder.id, remoteFolderId)) {
            if (onlyMessageId != null && !Objects.equals(onlyMessageId, message.zoho_id))
                continue;
            String remaining = removeFolderId(message.zoho_folder_ids, remoteFolderId);
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
        db.runInTransaction(() -> {
            if (EntityOperation.SEEN.equals(operation.name))
                db.message().setMessageUiSeen(message.id, message.seen);
            else if (EntityOperation.FLAG.equals(operation.name))
                db.message().setMessageUiFlagged(message.id, message.flagged,
                        message.flagged ? message.color : null);
            else if (EntityOperation.MOVE.equals(operation.name) || EntityOperation.DELETE.equals(operation.name)) {
                db.message().setMessageUiHide(message.id, false);
                db.message().setMessageUiBusy(message.id, null);
            }
        });
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

    static String replaceFolderId(String current, String previousFolderId, String remoteFolderId) {
        if (Objects.equals(previousFolderId, remoteFolderId))
            return current;
        if (!ZohoApi.isValidRemoteId(previousFolderId) || !ZohoApi.isValidRemoteId(remoteFolderId))
            throw new IllegalArgumentException("Invalid Zoho folder ID mapping");

        List<String> ids = folderIds(current);
        boolean replaced = ids.remove(previousFolderId);
        if (!replaced)
            return current;
        if (!ids.contains(remoteFolderId))
            ids.add(remoteFolderId);

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

package in.anoopbhatia.mail;

import android.content.Context;

import java.io.IOException;
import java.util.List;

/** Explicit-refresh reconciliation of cached native API memberships, independent of mailbox pages. */
final class NativeFolderState {
    static final String OPERATION = "native_folder_state";
    static final int BATCH_SIZE = 10;

    private NativeFolderState() {
    }

    static void queue(DB db, EntityAccount account, EntityFolder folder) {
        if (db == null || account == null || folder == null || account.id == null ||
                folder.id == null || !account.id.equals(folder.account) ||
                !(account.isGraphApi() || account.isZohoApi()))
            return;
        db.runInTransaction(() -> {
            // Repeated refreshes share the durable scan already in progress.
            if (db.operation().getOperationsByFolder(folder.id, OPERATION).isEmpty()) {
                EntityOperation operation = new EntityOperation();
                operation.account = account.id;
                operation.folder = folder.id;
                operation.message = null;
                operation.name = OPERATION;
                operation.args = "0";
                operation.created = System.currentTimeMillis();
                db.operation().insertOperation(operation);
            }
        });
    }

    static boolean hasPending(DB db, long accountId) {
        return db != null && !db.operation().getOperations(accountId, OPERATION).isEmpty();
    }

    static boolean hasPendingForFolder(DB db, long folderId) {
        return db.operation().getOperationCount(folderId, OPERATION) > 0;
    }

    private static void complete(DB db, EntityOperation operation) {
        db.operation().deleteOperation(operation.id);
        if (operation.folder != null && !hasPendingForFolder(db, operation.folder) &&
                db.operation().getOperationCount(operation.folder, EntityOperation.SYNC) == 0)
            db.folder().setFolderSyncState(operation.folder, null);
    }

    static boolean process(Context context, DB db, EntityAccount account, Core.State state,
                        GraphMailApi graph, ZohoApi zoho)
            throws IOException, org.json.JSONException, android.os.OperationCanceledException {
        if (db == null || account == null || account.id == null ||
                !(account.isGraphApi() || account.isZohoApi()))
            return false;
        boolean morePending = false;
        for (EntityOperation operation : db.operation().getOperations(account.id, OPERATION)) {
            if (operation.folder == null)
                complete(db, operation);
            else {
                long afterId = cursor(operation.args);
                List<EntityMessage> messages = db.message().getNativeFolderStateMessages(
                        account.id, operation.folder, afterId, BATCH_SIZE);
                if (messages.isEmpty()) {
                    complete(db, operation);
                    continue;
                }
                boolean blocked = false;
                long lastId = afterId;
                for (EntityMessage message : messages) {
                    state.ensureRunning("Native folder membership check");
                    List<EntityOperation> pending = db.operation().getOperationsForMessage(message.id);
                    // Zoho reader checks already own a durable read-only retry. They must
                    // not pin this scan's cursor. All other pending work still blocks it.
                    if (hasBlockingOperations(account.isZohoApi(), pending)) {
                        blocked = true;
                        break;
                    }
                    if (account.isZohoApi() && !pending.isEmpty()) {
                        lastId = message.id;
                        continue;
                    }
                    if (account.isGraphApi()) {
                        GraphMailReceiver.reconcileCachedMembership(context, db, graph, state,
                                db.folder().getFolder(operation.folder), message);
                    } else if (!ZohoMessageSync.tryReadback(
                            () -> ZohoMessageSync.refresh(context, db, message, zoho))) {
                        // Save the retry before advancing: ambiguity is not absence, and
                        // one bad response must not starve later cached memberships/pages.
                        NativeReaderState.deferZohoRefresh(db, message, operation.folder);
                        Log.i("Zoho folder check deferred: unverified message metadata");
                    }
                    lastId = message.id;
                }
                if (!blocked && messages.size() < BATCH_SIZE)
                    complete(db, operation);
                else {
                    db.operation().setOperationArgs(operation.id, Long.toString(lastId));
                    morePending |= !blocked;
                    if (blocked)
                        Log.i("Native folder refresh deferred account=" + account.id +
                                " folder=" + operation.folder + " pending message work");
                }
            }
        }
        return morePending;
    }

    static boolean hasBlockingOperations(boolean zoho, List<EntityOperation> operations) {
        for (EntityOperation operation : operations)
            if (!zoho || !NativeReaderState.OPERATION.equals(operation.name))
                return true;
        return false;
    }

    static long cursor(String args) {
        try {
            return Math.max(0L, Long.parseLong(args));
        } catch (RuntimeException ex) {
            return 0L;
        }
    }
}

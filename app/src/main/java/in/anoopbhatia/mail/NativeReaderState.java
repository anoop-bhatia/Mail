package in.anoopbhatia.mail;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Read-only reader-exit checks, serialized with native account mutations and imports.
 *
 * <p>Graph and Zoho write endpoints do not return the message's resulting state, so a message the
 * user actually opened is read back once on exit: that reconciles a read/flag/move made by another
 * client and confirms the app's own write. Gmail returns the label list from its mutations and its
 * history feed covers other clients, so a Gmail exit queues the folder refresh only.</p>
 */
final class NativeReaderState {
    static final String OPERATION = "native_reader_state";

    private NativeReaderState() {
    }

    static boolean supports(EntityAccount account, EntityMessage message) {
        return account != null && message != null && account.id != null &&
                account.id.equals(message.account) &&
                ((account.isGraphApi() && message.graph_id != null && !message.graph_id.isEmpty()) ||
                        (account.isZohoApi() && ZohoApi.isValidRemoteId(message.zoho_id)));
    }

    static boolean hasPending(DB db, long accountId) {
        return !db.operation().getOperations(accountId, OPERATION).isEmpty();
    }

    static void queue(Context context, long[] ids, long sourceFolder) {
        DB db = DB.getInstance(context);
        Set<Long> accounts = new HashSet<>();
        Set<Long> refreshOnly = new HashSet<>();
        db.runInTransaction(() -> {
            for (long id : ids) {
                EntityMessage message = db.message().getMessage(id);
                EntityAccount account = (message == null ? null : db.account().getAccount(message.account));
                if (account == null || message.account == null || !account.id.equals(message.account))
                    continue;
                if (!supports(account, message)) {
                    // Gmail history already reports every flag and membership change of the
                    // mailbox, so a Gmail reader exit costs the folder refresh only, exactly what
                    // an IMAP reader exit costs.
                    if (account.isGmailApi())
                        refreshOnly.add(account.id);
                    continue;
                }
                accounts.add(account.id);
                // Each exit has its own request: a check already in flight must not
                // swallow a newer exit or its different original folder.
                EntityOperation operation = new EntityOperation();
                operation.account = account.id;
                operation.folder = message.folder;
                operation.message = message.id;
                operation.name = OPERATION;
                operation.args = new JSONArray().put(sourceFolder).toString();
                operation.created = System.currentTimeMillis();
                db.operation().insertOperation(operation);
            }
        });
        for (long account : refreshOnly) {
            // The Gmail account worker refreshes inbox/history when woken. A unified inbox may
            // pass a synthetic source ID (for example -1), which has no folder row to sync.
            accounts.add(account);
            EntityFolder folder = db.folder().getFolder(sourceFolder);
            if (folder != null && folder.account == account)
                EntityOperation.sync(context, sourceFolder, true);
        }
        // Wake only after the durable request is visible. Existing worker backoff/offline rules apply.
        for (long account : accounts)
            ServiceSynchronize.eval(context, account, "native-reader-exit");
    }

    static long sourceFolder(String args, long fallback) {
        try {
            return new JSONArray(args).optLong(0, fallback);
        } catch (JSONException | NullPointerException ex) {
            return fallback;
        }
    }

    // Transfer an ambiguous scan item to durable read-only retry before advancing its cursor.
    // No wake here: the account worker is already running and must not busy-loop on ambiguity.
    static void deferZohoRefresh(DB db, EntityMessage message, long sourceFolder) {
        db.runInTransaction(() -> {
            if (db.operation().getOperation(message.id, OPERATION) != null)
                return;
            EntityOperation operation = new EntityOperation();
            operation.account = message.account;
            operation.folder = message.folder;
            operation.message = message.id;
            operation.name = OPERATION;
            operation.args = new JSONArray().put(sourceFolder).toString();
            operation.created = System.currentTimeMillis();
            db.operation().insertOperation(operation);
        });
    }

    static void process(Context context, DB db, EntityAccount account, Core.State state,
                        GraphMailApi graph, ZohoApi zoho) throws IOException, JSONException {
        for (EntityOperation operation : db.operation().getOperations(account.id, OPERATION)) {
            state.ensureRunning("Native reader state check");
            EntityMessage message = (operation.message == null ? null :
                    db.message().getMessage(operation.message));
            if (supports(account, message)) {
                if (account.isGraphApi()) {
                    GraphMailApi.MessageState remote = GraphMessageSync.resolve(graph, message);
                    GraphMessageSync.ensureDestination(db, account, graph, remote);
                    GraphMessageSync.apply(context, db, message, remote);
                    if (remote == null)
                        GraphMessageSync.refresh(context, db.folder().getFolder(message.folder));
                } else if (!ZohoMessageSync.tryReadback(
                        () -> ZohoMessageSync.refresh(context, db, message, zoho))) {
                    // Keep this request, but do not starve independent checks or folder refresh.
                    Log.i("Zoho reader check deferred: unverified message metadata");
                    continue;
                }
                // Refresh the list the reader came from too, including a move made in this app.
                long source = sourceFolder(operation.args, message.folder);
                EntityFolder folder = db.folder().getFolder(source);
                if (folder != null && account.id.equals(folder.account))
                    EntityOperation.sync(context, source, true);
            }
            // An ambiguous or failed/429 readback remains durable for retry.
            db.operation().deleteOperation(operation.id);
        }
    }
}

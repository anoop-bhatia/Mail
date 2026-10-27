package in.anoopbhatia.mail;

import android.content.Context;

import java.util.List;

/** Implements the debug Repair operation without rebuilding user-owned folder settings. */
final class RepairHelper {
    private RepairHelper() {
    }

    /** A timed service join may give up; never reset cursors while a worker can still write. */
    static void afterWorkersStopped(List<java.util.function.BooleanSupplier> workers, Runnable repair) {
        for (java.util.function.BooleanSupplier worker : workers)
            if (worker.getAsBoolean())
                throw new IllegalStateException("Repair could not stop synchronization; please retry after it finishes");
        repair.run();
    }

    static void repair(Context context) {
        DB db = DB.getInstance(context);
        db.runInTransaction(() -> {
            List<EntityAccount> accounts = db.account().getAccounts();
            if (accounts != null)
                for (EntityAccount account : accounts) {
                    List<EntityFolder> folders = db.folder().getFolders(account.id, false, false);
                    if (folders == null)
                        continue;

                    if (account.isNativeRestApi()) {
                        resetNativeState(db, account, folders);
                    } else if (account.protocol == EntityAccount.TYPE_IMAP) {
                        repairLegacyFolders(context, db, account, folders);
                    }
                }
        });
    }

    private static void repairLegacyFolders(Context context, DB db, EntityAccount account,
                                            List<EntityFolder> folders) {
        EntityFolder inbox = db.folder().getFolderByType(account.id, EntityFolder.INBOX);
        for (EntityFolder folder : folders) {
            if (inbox == null && "inbox".equalsIgnoreCase(folder.name))
                folder.type = EntityFolder.INBOX;

            if (!EntityFolder.USER.equals(folder.type) &&
                    !EntityFolder.SYSTEM.equals(folder.type)) {
                EntityLog.log(context, "Repairing " + account.name + ":" + folder.type);
                // Keep the historical IMAP repair semantics intact.
                folder.setProperties();
                folder.setSpecials(account);
                db.folder().updateFolder(folder);
            }
        }
    }

    private static void resetNativeState(DB db, EntityAccount account, List<EntityFolder> folders) {
        if (account.isGmailApi())
            db.account().setGmailSyncState(account.id, null, null, null, null, null);
        resetNativeState(account, folders);
        for (EntityFolder folder : folders)
            db.folder().updateFolder(folder);
    }

    static void resetNativeState(EntityAccount account, List<EntityFolder> folders) {
        if (account.isGmailApi()) {
            // History state is mailbox-wide. Folder label IDs are provider identity and must survive.
            account.gmail_history_id = null;
            account.gmail_full_sync_history_id = null;
            account.gmail_full_sync_page_token = null;
            account.gmail_history_page_token = null;
            account.gmail_history_pending = null;
            for (EntityFolder folder : folders)
                folder.gmail_page_token = null;
        } else if (account.isGraphApi()) {
            // Delta/page links are opaque cursors, unlike graph_folder_id (stable provider identity).
            for (EntityFolder folder : folders) {
                folder.graph_page_link = null;
                folder.graph_delta_link = null;
            }
        } else if (account.isZohoApi()) {
            // Restart both the bounded recent scan and the older-page walk.
            for (EntityFolder folder : folders) {
                folder.zoho_page_start = 1;
                folder.zoho_recent_page_start = null;
                folder.zoho_recent_scan_head_id = null;
                folder.zoho_recent_boundary_id = null;
            }
        }
    }
}

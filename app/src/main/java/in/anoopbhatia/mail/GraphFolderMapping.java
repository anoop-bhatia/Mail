package in.anoopbhatia.mail;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Repairs folder identities omitted by older backups before the worker consumes any work. */
final class GraphFolderMapping {
    interface Store {
        List<EntityFolder> folders();
        void transaction(Runnable work);
        boolean map(long folderId, String remoteId);
    }

    interface FolderSource {
        List<GraphMailApi.MailFolder> list() throws IOException, JSONException;
    }

    private GraphFolderMapping() {
    }

    static void ensureMappings(DB db, EntityAccount account, GraphMailApi api, Core.State state)
            throws IOException, JSONException {
        ensureMappings(new Store() {
            @Override
            public List<EntityFolder> folders() {
                return db.folder().getFolders(account.id, false, false);
            }

            @Override
            public void transaction(Runnable work) {
                db.runInTransaction(work);
            }

            @Override
            public boolean map(long folderId, String remoteId) {
                return db.folder().setMissingGraphFolderId(account.id, folderId, remoteId) == 1;
            }
        }, () -> {
            state.ensureRunning("Microsoft Graph folder mapping recovery");
            return api.listFolders();
        });
    }

    static void ensureMappings(Store store, FolderSource source) throws IOException, JSONException {
        boolean missing = false;
        for (EntityFolder folder : store.folders())
            if (folder.selectable && isEmpty(folder.graph_folder_id)) {
                missing = true;
                break;
            }
        if (!missing)
            return;

        // Finish discovery before writing anything. Network/authentication failures use the
        // worker's normal retry path and must not consume queued sync or mutation work.
        List<GraphMailApi.MailFolder> remote = source.list();
        store.transaction(() -> {
            // Re-read after network I/O so concurrent settings/mapping changes are preserved.
            List<EntityFolder> folders = store.folders();
            for (EntityFolder folder : folders) {
                if (!folder.selectable || !isEmpty(folder.graph_folder_id))
                    continue;
                GraphMailApi.MailFolder match = findMatch(folder, folders, remote);
                if (match == null)
                    continue;
                if (store.map(folder.id, match.id))
                    folder.graph_folder_id = match.id;
            }
        });
    }

    private static GraphMailApi.MailFolder findMatch(EntityFolder folder,
                                                      List<EntityFolder> folders,
                                                      List<GraphMailApi.MailFolder> remote) {
        // Backup folder names are not paths. Never guess between same-named folders,
        // or attach two local rows to the same server folder.
        int localNames = 0;
        for (EntityFolder local : folders)
            if (Objects.equals(local.name, folder.name))
                localNames++;
        GraphMailApi.MailFolder match = null;
        int remoteNames = 0;
        for (GraphMailApi.MailFolder candidate : remote)
            if (Objects.equals(candidate.name, folder.name)) {
                match = candidate;
                remoteNames++;
            }
        if (localNames != 1 || remoteNames > 1)
            return null;
        if (remoteNames == 0) {
            // System-folder display names can change language between export and restore.
            // Only use a unique system type; user-folder names must match exactly.
            if (EntityFolder.USER.equals(folder.type))
                return null;
            int localTypes = 0;
            for (EntityFolder local : folders)
                if (Objects.equals(local.type, folder.type))
                    localTypes++;
            if (localTypes != 1)
                return null;
            for (GraphMailApi.MailFolder candidate : remote)
                if (Objects.equals(folder.type, GraphFolderDefaults.getFolderType(candidate.name))) {
                    if (match != null)
                        return null;
                    match = candidate;
                }
        }
        if (match == null || isEmpty(match.id))
            return null;
        for (EntityFolder local : folders)
            if (Objects.equals(local.graph_folder_id, match.id))
                return null;
        return match;
    }

    private static boolean isEmpty(String value) {
        return value == null || value.length() == 0;
    }
}

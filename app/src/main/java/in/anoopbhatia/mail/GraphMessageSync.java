package in.anoopbhatia.mail;

import android.content.Context;

import org.json.JSONException;

import java.io.IOException;
import java.util.Objects;

/** Reconciles mailbox-wide Graph identity/location without replacing an open message row. */
final class GraphMessageSync {
    private GraphMessageSync() {
    }

    static GraphMailApi.MessageState resolve(GraphMailApi api, EntityMessage message)
            throws IOException, JSONException {
        try {
            return api.getMessageState(message.graph_id);
        } catch (GraphMailApi.ApiException ex) {
            if (ex.status != 404)
                throw ex;
            // RFC Message-ID is not unique when copies exist. The API only returns an
            // unambiguous match; never mutate whichever copy happens to be returned first.
            return api.findMessageStateByInternetMessageId(message.msgid);
        }
    }

    static void apply(Context context, DB db, EntityMessage message,
                      GraphMailApi.MessageState remote) {
        if (remote == null || remote.id == null)
            return;
        EntityFolder source = db.folder().getFolder(message.folder);
        EntityFolder actual = (remote.parentFolderId == null ? null :
                db.folder().getFolderByGraphId(message.account, remote.parentFolderId));
        EntityMessage duplicate = db.message().getMessageByGraphId(message.account, remote.id);
        final boolean relocationCandidate = (actual != null &&
                (duplicate == null || Objects.equals(duplicate.id, message.id)));
        boolean canRelocate = db.callInTransaction(() -> {
            boolean relocate = relocationCandidate;
            // Recheck after network I/O, under the same transaction as the write.
            relocate = relocate &&
                    !GraphFolderMembership.hasPendingLocationChange(db.operation(), message.id);
            if (relocate) {
                // Targeted updates are essential: a body download or user action may have
                // changed other columns since this worker read the message.
                db.message().setMessageGraphId(message.id, remote.id);
                db.message().setMessageFolder(message.id, actual.id);
            }
            if (remote.seen != null) {
                db.message().setMessageSeen(message.id, remote.seen);
                if (db.operation().getOperation(message.id, EntityOperation.SEEN) == null)
                    db.message().setMessageUiSeen(message.id, remote.seen);
            }
            if (remote.flagged != null) {
                db.message().setMessageFlagged(message.id, remote.flagged);
                if (db.operation().getOperation(message.id, EntityOperation.FLAG) == null)
                    db.message().setMessageUiFlagged(message.id, remote.flagged,
                            remote.flagged ? message.color : null);
            }
            return relocate ? Boolean.TRUE : Boolean.FALSE;
        });
        if (canRelocate) {
            message.graph_id = remote.id;
            message.folder = actual.id;
        }
        if (actual == null || source == null || !Objects.equals(source.id, actual.id) || !canRelocate) {
            refresh(context, source);
            refresh(context, actual);
        }
    }

    static void ensureDestination(DB db, EntityAccount account, GraphMailApi api,
                                                GraphMailApi.MessageState remote)
            throws IOException, JSONException {
        if (remote == null || remote.parentFolderId == null ||
                db.folder().getFolderByGraphId(account.id, remote.parentFolderId) != null)
            return;
        // Another client can move into a folder created since account setup. Discover
        // that exact destination, rather than keeping the row in its now-wrong source.
        for (GraphMailApi.MailFolder folder : api.listFolders())
            if (remote.parentFolderId.equals(folder.id)) {
                db.runInTransaction(() -> {
                    if (db.folder().getFolderByGraphId(account.id, folder.id) == null) {
                        EntityFolder created = new EntityFolder(folder.name,
                                GraphFolderDefaults.getFolderType(folder.name));
                        created.account = account.id;
                        created.graph_folder_id = folder.id;
                        EntityFolder parent = (folder.parentId == null ? null :
                                db.folder().getFolderByGraphId(account.id, folder.parentId));
                        created.parent = (parent == null ? null : parent.id);
                        created.selectable = true;
                        NativeApiFolderDefaults.applyCreationDefaults(created, false, true);
                        created.setSpecials(account);
                        db.folder().insertFolder(created);
                    }
                });
                return;
            }
        throw new IOException("Microsoft Graph reader destination is not mapped; retry readback");
    }

    static void refresh(Context context, EntityFolder folder) {
        if (folder != null && folder.graph_folder_id != null)
            EntityOperation.sync(context, folder.id, true);
    }
}

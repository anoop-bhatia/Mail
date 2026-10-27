package in.anoopbhatia.mail;

import android.content.Context;
import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Targeted reader readback for Zoho; never replaces or deletes the cached body. */
final class ZohoMessageSync {
    private ZohoMessageSync() {
    }

    interface ReadbackAction {
        void run() throws IOException, JSONException;
    }

    // Only semantic ambiguity is message-local. Authentication, throttling, transport,
    // cancellation and parse failures must still reach the account worker's normal policy.
    // A false result requires the caller to retain/queue durable read-only work.
    static boolean tryReadback(ReadbackAction action) throws IOException, JSONException {
        try {
            action.run();
            return true;
        } catch (ZohoApi.UnverifiedMetadataException ex) {
            return false;
        }
    }

    static final class Readback {
        final String memberships;
        final List<ZohoApi.MessageMetadata> found;
        final boolean sourceMissing;

        Readback(String memberships, List<ZohoApi.MessageMetadata> found, boolean sourceMissing) {
            this.memberships = memberships;
            this.found = found;
            this.sourceMissing = sourceMissing;
        }
    }

    static Readback resolve(ZohoApi api, String messageId, String sourceId,
                            String current, List<EntityFolder> folders) throws IOException, JSONException {
        // Legacy rows can have null memberships and fall back to message.folder in list queries.
        String memberships = (current == null || current.isEmpty() ? "|" + sourceId + "|" : current);
        List<ZohoApi.MessageMetadata> found = new ArrayList<>();
        try {
            ZohoApi.MessageMetadata metadata = api.getMessageMetadata(sourceId, messageId);
            found.add(metadata);
            return new Readback(add(memberships, metadata.folderId), found, false);
        } catch (ZohoApi.MembershipChangedException ex) {
            // The source GET positively identifies this message in another folder.
            // Remove only the disproved source; preserve unqueried memberships.
            found.add(ex.metadata);
            return new Readback(add(remove(memberships, sourceId), ex.metadata.folderId),
                    found, true);
        } catch (ZohoApi.ApiException ex) {
            if (!ex.isMessageAbsentFromFolder())
                throw ex;
        }
        memberships = remove(memberships, sourceId);
        Set<String> queried = new HashSet<>();
        queried.add(sourceId);
        // Probe only mapped folders. Any partial/transport/rate-limit failure aborts the
        // readback before a DB write; absence is never guessed from a page or failed GET.
        // Zoho reports a stale folder/message pair as 400 rather than 404, so both statuses
        // count as absence from that folder; a message moved to Trash is found by this scan.
        for (EntityFolder folder : folders) {
            String folderId = folder.zoho_folder_id;
            if (!ZohoApi.isValidRemoteId(folderId) || !queried.add(folderId))
                continue;
            try {
                ZohoApi.MessageMetadata metadata = api.getMessageMetadata(folderId, messageId);
                found.add(metadata);
                memberships = add(memberships, metadata.folderId);
            } catch (ZohoApi.ApiException ex) {
                if (!ex.isMessageAbsentFromFolder())
                    throw ex;
                memberships = remove(memberships, folderId);
            }
        }
        return new Readback(memberships, found, true);
    }

    /**
     * @return true when the cached row was retired because no local folder holds the message
     * any more
     */
    static boolean refresh(Context context, DB db, EntityMessage message, ZohoApi api)
            throws IOException, JSONException {
        EntityFolder source = db.folder().getFolder(message.folder);
        if (source == null || !ZohoApi.isValidRemoteId(source.zoho_folder_id))
            return false;
        Readback readback = resolve(api, message.zoho_id, source.zoho_folder_id,
                message.zoho_folder_ids, db.folder().getFolders(message.account, false, false));
        // The source and every folder in the client's local list answered "not here". Keep the
        // cached row if its stored membership still names an unmapped folder: that remote copy
        // was not queried, so its body may still be fetchable.
        boolean locationQueued = hasPendingLocationChange(db.operation(), message.id);
        if (readback.sourceMissing && readback.found.isEmpty() &&
                hasNoRemainingMemberships(readback.memberships) && !locationQueued)
            return NativeAbsentMessage.purge(context, db, message, "not in any local Zoho folder");
        // A unique destination is safe to use as the primary row. Multiple positive
        // memberships remain visible without arbitrarily selecting one copy's flags.
        ZohoApi.MessageMetadata metadata = (readback.found.size() == 1 ? readback.found.get(0) : null);
        EntityFolder actual = (metadata == null ? null :
                db.folder().getFolderByZohoId(message.account, metadata.folderId));
        db.runInTransaction(() -> {
            // A move/delete may have been queued while the GET was in flight.
            // Preserve its optimistic location/membership until the write worker resolves it.
            if (!hasPendingLocationChange(db.operation(), message.id)) {
                db.message().setMessageZohoFolderIds(message.id, readback.memberships);
                if (actual != null)
                    db.message().setMessageFolder(message.id, actual.id);
            }
            if (metadata != null && metadata.seen != null) {
                db.message().setMessageSeen(message.id, metadata.seen);
                if (db.operation().getOperation(message.id, EntityOperation.SEEN) == null)
                    db.message().setMessageUiSeen(message.id, metadata.seen);
            }
            if (metadata != null && metadata.flagged != null) {
                db.message().setMessageFlagged(message.id, metadata.flagged);
                if (db.operation().getOperation(message.id, EntityOperation.FLAG) == null)
                    db.message().setMessageUiFlagged(message.id, metadata.flagged,
                            metadata.flagged ? message.color : null);
            }
        });
        if (readback.sourceMissing) {
            EntityOperation.sync(context, source.id, true);
            if (actual != null && !actual.id.equals(source.id))
                EntityOperation.sync(context, actual.id, true);
        }
        return false;
    }

    static boolean hasPendingLocationChange(DaoOperation operations, long messageId) {
        return operations.getOperation(messageId, EntityOperation.MOVE) != null ||
                operations.getOperation(messageId, EntityOperation.DELETE) != null ||
                operations.getOperation(messageId, EntityOperation.ZOHO_DELETE_SPAM_PERMANENT) != null ||
                operations.getOperation(messageId, EntityOperation.ZOHO_PERMANENT_DELETE) != null;
    }

    static boolean hasNoRemainingMemberships(String memberships) {
        return "|".equals(memberships);
    }

    private static String add(String current, String id) {
        String token = "|" + id + "|";
        return current.contains(token) ? current : current + id + "|";
    }

    private static String remove(String current, String id) {
        return current.replace("|" + id + "|", "|");
    }
}

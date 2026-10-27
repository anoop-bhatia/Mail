package in.anoopbhatia.mail;

import org.json.JSONException;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/** Resolves Graph message membership without replacing the cached message row. */
final class GraphFolderMembership {
    private GraphFolderMembership() {
    }

    /**
     * Resolve the folder reported by a mailbox-wide Graph message read.
     *
     * <p>The source folder is only a fallback: a mailbox-wide GET can return a message
     * whose current parent differs from the folder being synchronized.</p>
     */
    static EntityFolder resolveFolder(DaoFolder folders, long accountId,
                                      EntityFolder listed, GraphMailApi.MessageState state) {
        if (state == null || state.parentFolderId == null)
            return listed;
        if (listed != null && Objects.equals(state.parentFolderId, listed.graph_folder_id))
            return listed;
        return folders.getFolderByGraphId(accountId, state.parentFolderId);
    }

    /** Resolve a cached message by its current Graph ID, with the existing stable-ID fallback. */
    static GraphMailApi.MessageState resolveRemoval(GraphMailApi api, EntityMessage cached)
            throws IOException, JSONException {
        return GraphMessageSync.resolve(api, cached);
    }

    /** Relocate only the folder column, preserving the cached message body and other data. */
    static boolean relocate(DaoMessage messages, EntityMessage cached, EntityFolder actual) {
        if (messages == null || cached == null || cached.id == null || actual == null || actual.id == null)
            return false;
        int updated = messages.setMessageFolder(cached.id, actual.id);
        if (updated > 0)
            cached.folder = actual.id;
        return updated > 0;
    }

    /** Return whether a cached native Graph row belongs in a different known folder. */
    static boolean isRelocation(EntityMessage cached, EntityFolder actual) {
        return cached != null && cached.graph_id != null && cached.folder != null &&
                actual != null && actual.id != null && !Objects.equals(cached.folder, actual.id);
    }

    static boolean hasPendingLocationChange(DaoOperation operations, long messageId) {
        return operations.getOperation(messageId, EntityOperation.MOVE) != null ||
                operations.getOperation(messageId, EntityOperation.DELETE) != null ||
                operations.getOperation(messageId, EntityOperation.GRAPH_DELETE_PERMANENT) != null ||
                operations.getOperation(messageId, EntityOperation.GRAPH_PERMANENT_DELETE) != null;
    }

    /** Keep queued work first, then the most recently opened row, then the original row. */
    static EntityMessage chooseSurvivor(EntityMessage original, EntityMessage duplicate,
                                        boolean originalPending, boolean duplicatePending) {
        if (originalPending && duplicatePending)
            return null;
        if (originalPending)
            return original;
        if (duplicatePending)
            return duplicate;
        long originalTouch = (original.last_touched == null ? 0 : original.last_touched);
        long duplicateTouch = (duplicate.last_touched == null ? 0 : duplicate.last_touched);
        return duplicateTouch > originalTouch ? duplicate : original;
    }

    /** Select exactly one same-account native Graph row that could have moved to a new ID. */
    static EntityMessage findUniqueMovedCandidate(List<EntityMessage> candidates,
                                                   long accountId, String incomingGraphId) {
        if (candidates == null || incomingGraphId == null || incomingGraphId.isEmpty())
            return null;
        EntityMessage candidate = null;
        for (EntityMessage message : candidates) {
            if (message == null || message.account == null || message.account != accountId ||
                    message.graph_id == null || message.graph_id.isEmpty() ||
                    message.gmail_id != null || message.zoho_id != null ||
                    incomingGraphId.equals(message.graph_id))
                continue;
            if (candidate != null)
                return null;
            candidate = message;
        }
        return candidate;
    }
}

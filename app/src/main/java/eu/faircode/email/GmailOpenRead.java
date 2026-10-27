package eu.faircode.email;
import org.json.JSONArray;
import org.json.JSONException;
import java.util.List;
/** Read-on-open policy for Gmail API messages, separate from display-only local state. */
final class GmailOpenRead {
    private GmailOpenRead() {
    }
    static boolean shouldQueue(boolean gmailApiAccount, boolean accountAutoSeen,
                               boolean readTriggerReached, String gmailMessageId,
                               boolean serverUnread, List<EntityOperation> operations) {
        return (gmailApiAccount && accountAutoSeen && readTriggerReached &&
                gmailMessageId != null && !gmailMessageId.trim().isEmpty() &&
                serverUnread && !hasQueuedRead(operations));
    }
    static boolean hasQueuedRead(List<EntityOperation> operations) {
        if (operations == null)
            return false;
        for (EntityOperation operation : operations) {
            if (operation == null || !EntityOperation.SEEN.equals(operation.name))
                continue;
            try {
                JSONArray args = new JSONArray(operation.args);
                if (args.optBoolean(0, false))
                    return true;
            } catch (JSONException ignored) {
                // An invalid operation is not proof that a read was queued.
            }
        }
        return false;
    }
}

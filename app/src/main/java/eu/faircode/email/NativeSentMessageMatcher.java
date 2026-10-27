package eu.faircode.email;

import java.util.List;
import java.util.Objects;

/** Matches a native REST Sent item to its optimistic local copy without broad fuzzy merging. */
final class NativeSentMessageMatcher {
    static final long MAX_SENT_TIME_DIFFERENCE = 15L * 60L * 1000L;

    private NativeSentMessageMatcher() {
    }

    static EntityMessage findUnique(List<EntityMessage> candidates, long accountId,
                                   EntityFolder storage, String internetMessageId,
                                   long remoteSent) {
        if (candidates == null || storage == null ||
                !EntityFolder.SENT.equals(storage.type) ||
                !Objects.equals(storage.account, accountId) ||
                internetMessageId == null || internetMessageId.trim().isEmpty() ||
                remoteSent <= 0)
            return null;

        EntityMessage match = null;
        for (EntityMessage candidate : candidates) {
            if (candidate == null || !Objects.equals(candidate.account, accountId) ||
                    !Objects.equals(candidate.folder, storage.id) ||
                    candidate.gmail_id != null || candidate.zoho_id != null || candidate.graph_id != null ||
                    !internetMessageId.equals(candidate.msgid) || candidate.sent == null || candidate.sent <= 0)
                continue;
            long difference = (candidate.sent >= remoteSent
                    ? candidate.sent - remoteSent : remoteSent - candidate.sent);
            if (difference > MAX_SENT_TIME_DIFFERENCE)
                continue;
            if (match != null)
                return null;
            match = candidate;
        }
        return match;
    }
}

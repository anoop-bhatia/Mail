package eu.faircode.email;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Durable offset-scan helpers for Zoho's newest-first, mutable pagination. */
final class ZohoRecentPageScan {
    static final int OFFSET_OVERLAP = 1;

    private final Set<String> seen = new HashSet<>();

    ZohoRecentPageScan() {
    }

    boolean markSeen(String messageId) {
        return (messageId != null && seen.add(messageId));
    }

    List<ZohoApi.MailMessage> unique(List<ZohoApi.MailMessage> messages) {
        List<ZohoApi.MailMessage> result = new ArrayList<>();
        if (messages != null)
            for (ZohoApi.MailMessage message : messages)
                if (message != null && markSeen(message.id))
                    result.add(message);
        return result;
    }

    static int nextStart(int start, int resultItemCount) {
        if (start < 1 || resultItemCount <= 0)
            return 0;
        int advance = Math.max(1, resultItemCount - OFFSET_OVERLAP);
        long next = (long) start + advance;
        return (next > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) next);
    }

    // Negative checkpoints mean that page must be replayed after interruption;
    // positive checkpoints point at the next page that has not completed.
    static int inFlight(int start) {
        if (start < 1)
            throw new IllegalArgumentException("Zoho scan page must be positive");
        return -start;
    }

    static int resumeStart(Integer checkpoint) {
        if (checkpoint == null || checkpoint == 0)
            return 0;
        return (checkpoint < 0 ? -checkpoint : checkpoint);
    }

    static boolean isInFlight(Integer checkpoint) {
        return (checkpoint != null && checkpoint < 0);
    }

    static boolean sameHead(String previousHeadId, String currentHeadId) {
        return (previousHeadId == null ? currentHeadId == null : previousHeadId.equals(currentHeadId));
    }

    static Integer shiftCheckpoint(Integer checkpoint, int addedBeforeCursor) {
        if (checkpoint == null || checkpoint == 0 || addedBeforeCursor <= 0)
            return checkpoint;
        long shifted = (long) resumeStart(checkpoint) + addedBeforeCursor;
        int value = (shifted > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) shifted);
        return (checkpoint < 0 ? -value : value);
    }

    static int shiftOlderStart(int olderStart, int addedToFolder) {
        if (olderStart <= 1 || addedToFolder <= 0)
            return olderStart;
        long shifted = (long) olderStart + addedToFolder;
        return (shifted > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) shifted);
    }

    static boolean pageCanContinue(int resultItemCount, boolean boundaryReached,
                                   boolean olderLimitReached) {
        return (resultItemCount >= ZohoApi.PAGE_SIZE && !boundaryReached && !olderLimitReached);
    }

    static String lastMessageId(ZohoApi.Page page) {
        if (page == null || page.messages == null || page.messages.isEmpty())
            return null;
        return page.messages.get(page.messages.size() - 1).id;
    }
}

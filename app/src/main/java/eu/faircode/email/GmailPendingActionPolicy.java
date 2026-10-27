package eu.faircode.email;

/** UI policy for native Gmail REST writes that are queued but not yet confirmed. */
final class GmailPendingActionPolicy {
    static final long PENDING_UNTIL_RESOLVED = Long.MAX_VALUE;

    private GmailPendingActionPolicy() {
    }

    static boolean shouldMarkPending(GmailMutationLog.QueueResult result) {
        return (result == GmailMutationLog.QueueResult.QUEUED);
    }

    static boolean isPending(boolean hasGmailId, Long uiBusy, boolean groupedPending) {
        return (groupedPending || (hasGmailId && uiBusy != null));
    }

    static boolean shouldReturnToList(boolean openThread, boolean gmailQueued,
                                      boolean otherProviderQueued) {
        return (openThread && gmailQueued && !otherProviderQueued);
    }
}

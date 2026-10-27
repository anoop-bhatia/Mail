package eu.faircode.email;

/** Rate-limit retry policy for the Gmail REST receiver. */
final class GmailRateLimitBackoff {
    static final int USER_QUOTA_UNITS_PER_MINUTE = 6000;
    static final int MESSAGES_GET_QUOTA_UNITS = 20;
    static final int MESSAGES_LIST_QUOTA_UNITS = 5;
    static final int MESSAGES_SEND_QUOTA_UNITS = 100;
    static final int MESSAGES_MODIFY_QUOTA_UNITS = 5;
    static final int MESSAGES_DELETE_QUOTA_UNITS = 10;
    static final int LABELS_LIST_QUOTA_UNITS = 1;
    static final int HISTORY_LIST_QUOTA_UNITS = 2;
    static final int PROFILE_GET_QUOTA_UNITS = 1;
    static final int MAX_MESSAGES_PER_PASS = 10;

    static final long MIN_BACKOFF_MS = 60 * 1000L;
    static final long MAX_BACKOFF_MS = 60 * 60 * 1000L;
    static final long MAX_JITTER_MS = 15 * 1000L;

    private GmailRateLimitBackoff() {
    }

    /** Worst-case full-sync page cost: labels.list + messages.list + one messages.get per ID. */
    static long fullSyncPageQuotaUnits(int pageSize) {
        if (pageSize < 0)
            throw new IllegalArgumentException("Negative Gmail page size");
        return LABELS_LIST_QUOTA_UNITS + MESSAGES_LIST_QUOTA_UNITS +
                ((long) pageSize * MESSAGES_GET_QUOTA_UNITS);
    }

    static long nextAllowedRequestAt(long nowMs, long quotaBackoffUntil,
                                     long nextPollAt, boolean folderRequested) {
        long next = Math.max(nowMs, quotaBackoffUntil);
        if (!folderRequested)
            next = Math.max(next, nextPollAt);
        return next;
    }

    /**
     * Apply truncated exponential backoff only after a real rate-limit response.
     * A server Retry-After is a lower bound; fallback waits include caller-supplied jitter.
     */
    static RetryPlan forRateLimit(int consecutiveFailures, long retryAfterMs,
                                  long jitterMs, long nowMs) {
        int shift = Math.max(0, Math.min(6, consecutiveFailures));
        long fallback = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS << shift);
        long base = Math.max(fallback, Math.max(0, retryAfterMs));
        long jitter = Math.max(0, Math.min(MAX_JITTER_MS, jitterMs));
        long delay = (retryAfterMs > MAX_BACKOFF_MS
                ? saturatedAdd(base, jitter)
                : Math.min(MAX_BACKOFF_MS, saturatedAdd(base, jitter)));
        return new RetryPlan(delay, saturatedAdd(nowMs, delay));
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right)
            return Long.MAX_VALUE;
        return left + right;
    }

    static final class RetryPlan {
        final long delayMs;
        final long nextAttemptAt;
        final boolean keepAccountConnected = true;
        final boolean preserveSyncCursor = true;

        RetryPlan(long delayMs, long nextAttemptAt) {
            this.delayMs = delayMs;
            this.nextAttemptAt = nextAttemptAt;
        }
    }
}

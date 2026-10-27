package in.anoopbhatia.mail;

/**
 * Durable cursor state for bounded Gmail REST synchronization.
 * A non-null fullSyncHistoryId means a full scan is in progress; historyId is
 * committed only after the final full-sync page or final history page succeeds.
 * The otherwise-unused pending-history field stores an internal recovery marker
 * while a full scan reconciles messages after an expired history cursor.
 */
final class GmailSyncState {
    enum Stage { FULL, HISTORY }
    private static final String EXPIRED_HISTORY_PROFILE_MARKER = "!gmail-expired-history-profile-v1!";
    private static final String EXPIRED_HISTORY_RECONCILE_PREFIX = "!gmail-expired-history-reconcile-v1:";

    final String historyId;
    final String fullSyncHistoryId;
    final String fullSyncPageToken;
    final String historyPageToken;
    final String historyPendingJson;

    GmailSyncState(String historyId, String fullSyncHistoryId,
                   String fullSyncPageToken, String historyPageToken,
                   String historyPendingJson) {
        this.historyId = emptyToNull(historyId);
        this.fullSyncHistoryId = emptyToNull(fullSyncHistoryId);
        this.fullSyncPageToken = emptyToNull(fullSyncPageToken);
        this.historyPageToken = emptyToNull(historyPageToken);
        this.historyPendingJson = emptyToNull(historyPendingJson);
    }

    static GmailSyncState empty() {
        return new GmailSyncState(null, null, null, null, null);
    }

    static GmailSyncState from(EntityAccount account) {
        return new GmailSyncState(account.gmail_history_id,
                account.gmail_full_sync_history_id,
                account.gmail_full_sync_page_token,
                account.gmail_history_page_token,
                account.gmail_history_pending);
    }

    Stage getStage() {
        return (fullSyncHistoryId != null || historyId == null ? Stage.FULL : Stage.HISTORY);
    }

    boolean needsProfile() {
        return (fullSyncHistoryId == null && historyId == null);
    }

    boolean isExpiredHistoryRecoveryPending() {
        return (needsProfile() && EXPIRED_HISTORY_PROFILE_MARKER.equals(historyPendingJson));
    }

    boolean isExpiredHistoryReconciliation() {
        return (isFullSync() && historyPendingJson != null &&
                historyPendingJson.startsWith(EXPIRED_HISTORY_RECONCILE_PREFIX));
    }

    long getExpiredHistoryLastMessageId() {
        if (!isExpiredHistoryReconciliation())
            throw new IllegalStateException("Gmail expired-history reconciliation is not active");
        try {
            long id = Long.parseLong(historyPendingJson.substring(EXPIRED_HISTORY_RECONCILE_PREFIX.length()));
            if (id < 0)
                throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid Gmail expired-history reconciliation cursor", ex);
        }
    }

    boolean isFullSync() {
        return (fullSyncHistoryId != null);
    }

    boolean hasMoreWork() {
        return (isFullSync() || isExpiredHistoryRecoveryPending() ||
                historyPageToken != null || historyPendingJson != null);
    }

    GmailSyncState beginFullSync(String baselineHistoryId) {
        requireValue(baselineHistoryId, "Missing Gmail history ID for full sync");
        return new GmailSyncState(null, baselineHistoryId, null, null, null);
    }

    GmailSyncState afterFullPage(String nextPageToken) {
        if (!isFullSync())
            throw new IllegalStateException("Gmail full sync is not active");
        if (isExpiredHistoryReconciliation())
            throw new IllegalStateException("Gmail expired-history reconciliation is not complete");
        if (!isEmpty(nextPageToken))
            return new GmailSyncState(null, fullSyncHistoryId, nextPageToken, null, null);
        return new GmailSyncState(fullSyncHistoryId, null, null, null, null);
    }

    GmailSyncState restartFullPage() {
        if (!isFullSync())
            throw new IllegalStateException("Gmail full sync is not active");
        return new GmailSyncState(null, fullSyncHistoryId, null, null, null);
    }

    GmailSyncState beginExpiredHistoryRecovery(String baselineHistoryId) {
        if (!isExpiredHistoryRecoveryPending())
            throw new IllegalStateException("Gmail expired-history recovery is not pending");
        requireValue(baselineHistoryId, "Missing Gmail history ID for expired-history recovery");
        return new GmailSyncState(null, baselineHistoryId, null, null,
                EXPIRED_HISTORY_RECONCILE_PREFIX + "0");
    }

    GmailSyncState afterExpiredHistoryMessage(long messageId) {
        if (!isExpiredHistoryReconciliation() || messageId <= getExpiredHistoryLastMessageId())
            throw new IllegalStateException("Invalid Gmail expired-history reconciliation progress");
        return new GmailSyncState(null, fullSyncHistoryId, null, null,
                EXPIRED_HISTORY_RECONCILE_PREFIX + messageId);
    }

    GmailSyncState completeExpiredHistoryRecovery() {
        if (!isExpiredHistoryReconciliation())
            throw new IllegalStateException("Gmail expired-history reconciliation is not active");
        return new GmailSyncState(null, fullSyncHistoryId, null, null, null);
    }

    GmailSyncState afterHistoryPage(String nextPageToken, String currentHistoryId) {
        if (historyId == null || isFullSync())
            throw new IllegalStateException("Gmail incremental sync is not active");
        if (!isEmpty(nextPageToken))
            return new GmailSyncState(historyId, null, null, nextPageToken, null);
        requireValue(currentHistoryId, "Gmail history response did not contain a history ID");
        return new GmailSyncState(currentHistoryId, null, null, null, null);
    }

    GmailSyncState withHistoryPending(String pendingJson) {
        if (historyId == null || isFullSync())
            throw new IllegalStateException("Gmail incremental sync is not active");
        requireValue(pendingJson, "Missing pending Gmail history changes");
        return new GmailSyncState(historyId, null, null, historyPageToken, pendingJson);
    }

    static GmailSyncState expiredHistory() {
        return new GmailSyncState(null, null, null, null, EXPIRED_HISTORY_PROFILE_MARKER);
    }

    private static void requireValue(String value, String message) {
        if (isEmpty(value))
            throw new IllegalArgumentException(message);
    }

    private static boolean isEmpty(String value) {
        return (value == null || value.trim().isEmpty());
    }

    private static String emptyToNull(String value) {
        return (isEmpty(value) ? null : value);
    }
}

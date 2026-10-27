package eu.faircode.email;

/**
 * Durable cursor state for bounded Gmail REST synchronization.
 * A non-null fullSyncHistoryId means a full scan is in progress; historyId is
 * committed only after the final full-sync page or final history page succeeds.
 */
final class GmailSyncState {
    enum Stage { FULL, HISTORY }

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

    boolean isFullSync() {
        return (fullSyncHistoryId != null);
    }

    boolean hasMoreWork() {
        return (isFullSync() || historyPageToken != null || historyPendingJson != null);
    }

    GmailSyncState beginFullSync(String baselineHistoryId) {
        requireValue(baselineHistoryId, "Missing Gmail history ID for full sync");
        return new GmailSyncState(null, baselineHistoryId, null, null, null);
    }

    GmailSyncState afterFullPage(String nextPageToken) {
        if (!isFullSync())
            throw new IllegalStateException("Gmail full sync is not active");
        if (!isEmpty(nextPageToken))
            return new GmailSyncState(null, fullSyncHistoryId, nextPageToken, null, null);
        return new GmailSyncState(fullSyncHistoryId, null, null, null, null);
    }

    GmailSyncState restartFullPage() {
        if (!isFullSync())
            throw new IllegalStateException("Gmail full sync is not active");
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
        return empty();
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

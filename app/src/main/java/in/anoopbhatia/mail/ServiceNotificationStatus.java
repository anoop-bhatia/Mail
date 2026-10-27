package in.anoopbhatia.mail;

/**
 * Content of the persistent monitoring notification.
 *
 * The notification speaks for all accounts at once, so what it says has to come from the network
 * and the account list, never from what an individual account worker happens to report. While the
 * device has no suitable connection every enabled account is waiting for one, whether it is an
 * IMAP or POP3 monitor that was stopped, a hibernated Graph/Zoho monitor, a Gmail API monitor, or a
 * monitor that still holds a stale "connected" state.
 */
final class ServiceNotificationStatus {
    static final class Status {
        final boolean awaiting;
        final int accounts;
        final int operations;

        private Status(boolean awaiting, int accounts, int operations) {
            this.awaiting = awaiting;
            this.accounts = accounts;
            this.operations = operations;
        }
    }

    private ServiceNotificationStatus() {
    }

    /**
     * @param suitable   last known network suitability, null while it is still unknown
     * @param monitored  accounts that are connected or backing off
     * @param enabled    accounts that should run
     * @param operations pending operations of the synchronizing accounts
     */
    static Status resolve(Boolean suitable, int monitored, int enabled, int operations) {
        // An unknown state is not a working connection, so it announces the same thing.
        if (suitable == null || !suitable)
            return new Status(true, Math.max(enabled, 0), Math.max(operations, 0));
        return new Status(false, Math.max(monitored, 0), Math.max(operations, 0));
    }
}

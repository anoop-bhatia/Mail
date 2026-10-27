package eu.faircode.email;

/** Account-level new-mail polling interval semantics, expressed in minutes. */
final class AccountPollInterval {
    private static final long MIN_DELAY_MS = 60_000L;
    private static final long MINUTES_TO_MILLIS = 60_000L;

    private AccountPollInterval() {
    }

    static int parseMinutes(String value) {
        return (value == null || value.length() == 0
                ? EntityAccount.DEFAULT_POLL_INTERVAL
                : Integer.parseInt(value));
    }

    static int normalizeMinutes(int value) {
        return Math.max(1, value);
    }

    static long getDelayMillis(int minutes) {
        return Math.max(MIN_DELAY_MS, normalizeMinutes(minutes) * MINUTES_TO_MILLIS);
    }
}

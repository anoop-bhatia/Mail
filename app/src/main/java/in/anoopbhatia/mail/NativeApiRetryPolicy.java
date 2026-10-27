package in.anoopbhatia.mail;

import java.util.concurrent.ThreadLocalRandom;

/** Retry timing for native REST monitors, never for IMAP/POP or routine polling. */
final class NativeApiRetryPolicy {
    private NativeApiRetryPolicy() {
    }

    static long transportDelayMs(int failures) {
        return transportDelayMs(failures, ThreadLocalRandom.current().nextLong(15_001L));
    }

    static long transportDelayMs(int failures, long jitterMs) {
        // The first retry is exactly 20 seconds, including DNS failures. Further failures
        // use short exponential backoff with positive jitter, capped at five minutes.
        if (failures <= 1)
            return 20_000L;
        int shift = Math.min(failures - 1, 4);
        return Math.min(300_000L, (20_000L << shift) + boundedJitter(jitterMs));
    }

    static long failureDelayMs(int failures) {
        return failureDelayMs(failures, ThreadLocalRandom.current().nextLong(15_001L));
    }

    static long failureDelayMs(int failures, long jitterMs) {
        // Keep a conservative minute for provider/application errors and missing throttle
        // headers, but never seed a retry from a user's (possibly hours-long) poll interval.
        int shift = (failures <= 1 ? 0 : Math.min(failures - 1, 6));
        return Math.min(3_600_000L, (60_000L << shift) + boundedJitter(jitterMs));
    }

    private static long boundedJitter(long jitterMs) {
        return Math.max(0L, Math.min(15_000L, jitterMs));
    }
}

package eu.faircode.email;

import android.os.OperationCanceledException;

/** Conservative retry decisions for Zoho REST writes. */
final class ZohoMutationRecovery {
    enum FailureAction {
        RETRY_SAFE,
        RETRY_AFTER_RATE_LIMIT,
        RESTORE_REJECTED,
        QUARANTINE_UNKNOWN
    }

    private ZohoMutationRecovery() {
    }

    static FailureAction classify(Throwable failure) {
        if (failure instanceof OperationCanceledException)
            return FailureAction.RETRY_SAFE;
        if (failure instanceof ZohoApi.ApiException) {
            ZohoApi.ApiException api = (ZohoApi.ApiException) failure;
            if (api.isRateLimitFailure())
                return FailureAction.RETRY_AFTER_RATE_LIMIT;
            if (api.status >= 500)
                return FailureAction.QUARANTINE_UNKNOWN;
            return FailureAction.RESTORE_REJECTED;
        }
        // Transport failures and malformed success responses may follow an accepted write.
        return FailureAction.QUARANTINE_UNKNOWN;
    }

    static long rateLimitDelayMs(long retryAfterMs, long fallbackDelayMs) {
        return (retryAfterMs > 0 ? Math.max(1000L, retryAfterMs) : Math.max(1000L, fallbackDelayMs));
    }

    static long backoffUntil(long now, long delayMs) {
        return (Long.MAX_VALUE - now < delayMs ? Long.MAX_VALUE : now + delayMs);
    }

    static boolean isQuarantined(EntityOperation operation) {
        return (operation != null && ZohoMailMutator.OUTCOME_UNKNOWN_ERROR.equals(operation.error));
    }
}

package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZohoMutationRecoveryTest {
    @Test
    public void explicitRejectionCanRestoreLocalStateAndThrottleIsRetriedAfterBackoff() {
        assertEquals(ZohoMutationRecovery.FailureAction.RESTORE_REJECTED,
                ZohoMutationRecovery.classify(new ZohoApi.ApiException(400, 0)));
        assertEquals(ZohoMutationRecovery.FailureAction.RESTORE_REJECTED,
                ZohoMutationRecovery.classify(new ZohoApi.ApiException(403, 0)));
        assertEquals(ZohoMutationRecovery.FailureAction.RETRY_AFTER_RATE_LIMIT,
                ZohoMutationRecovery.classify(new ZohoApi.ApiException(429, 5000)));
        assertEquals(ZohoMutationRecovery.FailureAction.RETRY_AFTER_RATE_LIMIT,
                ZohoMutationRecovery.classify(new ZohoApi.ApiException(403, 5000)));
    }

    @Test
    public void transportAndServerFailuresQuarantineToPreventDuplicateWrites() {
        assertEquals(ZohoMutationRecovery.FailureAction.QUARANTINE_UNKNOWN,
                ZohoMutationRecovery.classify(new java.io.IOException("connection lost")));
        assertEquals(ZohoMutationRecovery.FailureAction.QUARANTINE_UNKNOWN,
                ZohoMutationRecovery.classify(new ZohoApi.ApiException(500, 0)));
        assertEquals(ZohoMutationRecovery.FailureAction.RETRY_SAFE,
                ZohoMutationRecovery.classify(new android.os.OperationCanceledException()));

        EntityOperation operation = new EntityOperation();
        assertFalse(ZohoMutationRecovery.isQuarantined(operation));
        operation.error = ZohoMailMutator.OUTCOME_UNKNOWN_ERROR;
        assertTrue(ZohoMutationRecovery.isQuarantined(operation));
    }

    @Test
    public void retryAfterIsHonoredAndDeadlineSaturatesSafely() {
        assertEquals(5000L, ZohoMutationRecovery.rateLimitDelayMs(5000L, 120000L));
        assertEquals(120000L, ZohoMutationRecovery.rateLimitDelayMs(0, 120000L));
        assertEquals(1000L, ZohoMutationRecovery.rateLimitDelayMs(1L, 0));
        assertEquals(Long.MAX_VALUE,
                ZohoMutationRecovery.backoffUntil(Long.MAX_VALUE - 10, 100));
        assertEquals(1025L, ZohoMutationRecovery.backoffUntil(1000L, 25L));
    }
}

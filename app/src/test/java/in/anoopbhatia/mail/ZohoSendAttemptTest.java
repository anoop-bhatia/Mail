package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ZohoSendAttemptTest {
    @Test
    public void ambiguousZohoSendIsRemovedFromRetryQueueAndHeldForReview() {
        EntityOperation zoho = sendOperation(31L, 41L);
        EntityOperation nextProvider = sendOperation(32L, 42L);
        List<EntityOperation> queue = new ArrayList<>(Arrays.asList(zoho, nextProvider));
        ZohoApi.SendOutcomeUnknownException unknown =
                new ZohoApi.SendOutcomeUnknownException(-1, new IOException("response lost"));

        assertTrue(ZohoSendAttempt.continueQueueAfterFailure(queue, zoho, unknown));
        zoho.error = EntityOperation.ZOHO_SEND_OUTCOME_UNKNOWN_ERROR;

        assertEquals(1, queue.size());
        assertSame(nextProvider, queue.get(0));
        assertTrue(EntityOperation.isSendOutcomeUnknown(zoho));
        assertFalse(EntityOperation.shouldRecoverSendOnSync(zoho));
    }

    @Test
    public void explicitZohoRejectionRemainsEligibleForNormalRetry() {
        EntityOperation zoho = sendOperation(31L, 41L);
        List<EntityOperation> queue = new ArrayList<>(Arrays.asList(zoho));

        assertFalse(ZohoSendAttempt.continueQueueAfterFailure(queue, zoho,
                new ZohoApi.ApiException(429, 1_000L)));
        assertEquals(1, queue.size());
    }

    private static EntityOperation sendOperation(long id, long account) {
        EntityOperation operation = new EntityOperation();
        operation.id = id;
        operation.account = account;
        operation.folder = 1L;
        operation.name = EntityOperation.SEND;
        return operation;
    }
}

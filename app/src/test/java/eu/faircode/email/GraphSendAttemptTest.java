package eu.faircode.email;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class GraphSendAttemptTest {
    @Test
    public void uploadTimeoutIsAmbiguousAndMustNotBeRetried() throws Exception {
        GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
        progress.setPhase(GraphSendAttempt.Phase.UPLOADING);
        try {
            GraphSendAttempt.execute(() -> {
                Thread.sleep(5_000L);
                return null;
            }, 25L, progress);
            fail("Expected a bounded Graph request timeout");
        } catch (GraphSendAttempt.SendTimeoutException ex) {
            assertTrue(ex.outcomeUnknown);
            assertTrue(GraphSendAttempt.isOutcomeUnknown(ex));
            assertEquals(GraphSendAttempt.Phase.UPLOADING, ex.phase);
        }
    }

    @Test
    public void responseWaitTimeoutIsAmbiguousAndMustNotBeRetried() throws Exception {
        GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
        progress.setPhase(GraphSendAttempt.Phase.WAITING_RESPONSE);
        try {
            GraphSendAttempt.execute(() -> {
                Thread.sleep(5_000L);
                return null;
            }, 25L, progress);
            fail("Expected a bounded Graph request timeout");
        } catch (GraphSendAttempt.SendTimeoutException ex) {
            assertTrue(ex.outcomeUnknown);
            assertEquals(GraphSendAttempt.Phase.WAITING_RESPONSE, ex.phase);
        }
    }

    @Test
    public void timeoutBeforeUploadRemainsRetryable() throws Exception {
        GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
        try {
            GraphSendAttempt.execute(() -> {
                Thread.sleep(5_000L);
                return null;
            }, 25L, progress);
            fail("Expected a bounded Graph request timeout");
        } catch (GraphSendAttempt.SendTimeoutException ex) {
            assertFalse(ex.outcomeUnknown);
            assertFalse(GraphSendAttempt.isOutcomeUnknown(ex));
            assertEquals(GraphSendAttempt.Phase.CONNECTING, ex.phase);
            assertTrue(progress.isCancelled());
        }
    }

    @Test
    public void draftPreparationAndAttachmentUploadTimeoutsAreNotSendOutcomeUnknown() {
        GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
        progress.setPhase(GraphSendAttempt.Phase.PREPARING_DRAFT);
        assertFalse(progress.isOutcomeUnknown());
        progress.setPhase(GraphSendAttempt.Phase.UPLOADING_ATTACHMENTS);
        assertFalse(progress.isOutcomeUnknown());
    }

    @Test
    public void transportFailureAfterUploadWithoutHttpResponseIsAmbiguous() throws Exception {
        GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
        progress.setPhase(GraphSendAttempt.Phase.WAITING_RESPONSE);
        try {
            GraphSendAttempt.execute(() -> {
                throw new IOException("socket timeout");
            }, 1_000L, progress);
            fail("Expected an ambiguous transport failure");
        } catch (GraphSendAttempt.SendTimeoutException ex) {
            assertTrue(ex.outcomeUnknown);
            assertEquals(GraphSendAttempt.Phase.WAITING_RESPONSE, ex.phase);
        }
    }

    @Test
    public void explicitHttpFailureRemainsRetryable() throws Exception {
        GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
        try {
            GraphSendAttempt.execute(() -> {
                progress.setPhase(GraphSendAttempt.Phase.WAITING_RESPONSE);
                progress.setStatus(429);
                throw new IOException("Microsoft Graph HTTP 429");
            }, 1_000L, progress);
            fail("Expected the explicit HTTP failure");
        } catch (IOException ex) {
            assertEquals("Microsoft Graph HTTP 429", ex.getMessage());
            assertFalse(GraphSendAttempt.isOutcomeUnknown(ex));
        }
    }

    @Test
    public void acceptedResponseCountsAsSuccessEvenIfWorkerCleanupRunsLong() throws Exception {
        GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
        progress.setStatus(202);
        progress.setPhase(GraphSendAttempt.Phase.ACCEPTED);
        GraphSendAttempt.execute(() -> {
            Thread.sleep(5_000L);
            return null;
        }, 25L, progress);
        assertTrue(progress.isAccepted());
    }

    @Test
    public void acceptedResponseWinsOverLaterWorkerCleanupFailure() throws Exception {
        GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
        progress.setStatus(202);
        progress.setPhase(GraphSendAttempt.Phase.ACCEPTED);

        GraphSendAttempt.execute(() -> {
            throw new IOException("local cleanup failure");
        }, 1_000L, progress);
        assertTrue(progress.isAccepted());
    }

    @Test
    public void holdingUnknownGraphSendLetsNextProviderOperationProceed() {
        EntityOperation graph = sendOperation(12L, 20L);
        EntityOperation zoho = sendOperation(13L, 21L);
        List<EntityOperation> queue = new ArrayList<>(Arrays.asList(graph, zoho));
        GraphSendAttempt.SendTimeoutException unknown = new GraphSendAttempt.SendTimeoutException(
                GraphSendAttempt.Phase.WAITING_RESPONSE, -1, true, 25L, "timed out");

        assertTrue(GraphSendAttempt.continueQueueAfterFailure(queue, graph, unknown));
        graph.error = EntityOperation.SEND_OUTCOME_UNKNOWN_ERROR;

        assertEquals(1, queue.size());
        assertSame(zoho, queue.get(0));
        assertTrue(EntityOperation.isSendOutcomeUnknown(graph));
        assertFalse(EntityOperation.isSendOutcomeUnknown(zoho));
    }

    @Test
    public void nonAmbiguousFailuresDoNotSkipTheQueueOperation() {
        EntityOperation graph = sendOperation(12L, 20L);
        List<EntityOperation> queue = new ArrayList<>(Arrays.asList(graph));

        assertFalse(GraphSendAttempt.continueQueueAfterFailure(queue, graph,
                new IOException("Microsoft Graph HTTP 500")));
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

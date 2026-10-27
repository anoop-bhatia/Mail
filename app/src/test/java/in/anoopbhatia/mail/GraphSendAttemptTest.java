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
    public void directAndDraftSendFiveHundredsAreUnknownAndNeverReplayed() throws Exception {
        for (String requestName : Arrays.asList("direct send", "draft send")) {
            GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
            int[] requestCount = {0};
            GraphSendAttempt.SendOutcomeUnknownException unknown;
            try {
                GraphSendAttempt.execute(() -> {
                    requestCount[0]++;
                    progress.setPhase(GraphSendAttempt.Phase.WAITING_RESPONSE);
                    GraphSendAttempt.requireSendAccepted(500, requestName, progress);
                    return null;
                }, 1_000L, progress);
                fail("A post-submission 5xx must be outcome-unknown");
                return;
            } catch (GraphSendAttempt.SendOutcomeUnknownException ex) {
                unknown = ex;
            }

            EntityOperation operation = sendOperation(15L, 25L);
            List<EntityOperation> queue = new ArrayList<>(Arrays.asList(operation));
            assertTrue(GraphSendAttempt.isOutcomeUnknown(unknown));
            assertEquals(500, unknown.status);
            assertEquals(1, requestCount[0]);
            assertTrue(GraphSendAttempt.continueQueueAfterFailure(queue, operation, unknown));
            operation.error = EntityOperation.SEND_OUTCOME_UNKNOWN_ERROR;
            assertTrue(EntityOperation.isSendOutcomeUnknown(operation));
            assertTrue(queue.isEmpty());
        }
    }

    @Test
    public void noStatusAndUnexpectedSuccessStatusesForGraphSendAreNeverReplayed() throws Exception {
        for (String requestName : Arrays.asList("direct send", "draft send"))
            for (int status : Arrays.asList(-1, 200, 204)) {
                GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
                int[] requestCount = {0};
                GraphSendAttempt.SendOutcomeUnknownException unknown;
                try {
                    GraphSendAttempt.execute(() -> {
                        requestCount[0]++;
                        progress.setPhase(GraphSendAttempt.Phase.WAITING_RESPONSE);
                        GraphSendAttempt.requireSendAccepted(status, requestName, progress);
                        return null;
                    }, 1_000L, progress);
                    fail("A no-status or unrecognized 2xx send response must be outcome-unknown");
                    return;
                } catch (GraphSendAttempt.SendOutcomeUnknownException ex) {
                    unknown = ex;
                }

                EntityOperation operation = sendOperation(17L, 27L);
                List<EntityOperation> queue = new ArrayList<>(Arrays.asList(operation));
                assertTrue(GraphSendAttempt.isOutcomeUnknown(unknown));
                assertEquals(status, unknown.status);
                assertEquals(1, requestCount[0]);
                assertTrue(GraphSendAttempt.continueQueueAfterFailure(queue, operation, unknown));
                operation.error = EntityOperation.SEND_OUTCOME_UNKNOWN_ERROR;
                assertTrue(EntityOperation.isSendOutcomeUnknown(operation));
                assertTrue(queue.isEmpty());
            }
    }

    @Test
    public void noStatusAndUnexpectedSuccessStatusesForDraftCreateAreNeverReplayed() throws Exception {
        for (int status : Arrays.asList(-1, 200, 204)) {
            GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
            int[] requestCount = {0};
            GraphSendAttempt.SendOutcomeUnknownException unknown;
            try {
                GraphSendAttempt.execute(() -> {
                    requestCount[0]++;
                    progress.setPhase(GraphSendAttempt.Phase.PREPARING_DRAFT);
                    progress.markOutcomeUnknown(); // The MIME body was transmitted before the response.
                    GraphSendAttempt.classifyDraftCreateFailure(status, progress);
                    throw new IOException("Microsoft Graph draft HTTP " + status);
                }, 1_000L, progress);
                fail("A no-status or unrecognized 2xx draft response must be outcome-unknown");
                return;
            } catch (GraphSendAttempt.SendOutcomeUnknownException ex) {
                unknown = ex;
            }

            EntityOperation operation = sendOperation(18L, 28L);
            List<EntityOperation> queue = new ArrayList<>(Arrays.asList(operation));
            assertEquals(GraphSendAttempt.Phase.PREPARING_DRAFT, unknown.phase);
            assertEquals(status, unknown.status);
            assertTrue(GraphSendAttempt.continueQueueAfterFailure(queue, operation, unknown));
            operation.error = EntityOperation.GRAPH_DRAFT_OUTCOME_UNKNOWN_ERROR;
            assertTrue(EntityOperation.isSendOutcomeUnknown(operation));
            assertEquals(1, requestCount[0]);
            assertTrue(queue.isEmpty());
        }
    }

    @Test
    public void lostGraphDraftCreateResponseIsHeldWithoutBlindRecreation() throws Exception {
        GraphSendAttempt.Progress progress = new GraphSendAttempt.Progress();
        progress.setPhase(GraphSendAttempt.Phase.PREPARING_DRAFT);
        int[] requestCount = {0};
        try {
            GraphSendAttempt.execute(() -> {
                requestCount[0]++;
                progress.markOutcomeUnknown(); // The request body was transmitted, but no draft ID arrived.
                throw new IOException("response lost");
            }, 1_000L, progress);
            fail("A lost draft-create response must be outcome-unknown");
        } catch (GraphSendAttempt.SendOutcomeUnknownException ex) {
            assertEquals(GraphSendAttempt.Phase.PREPARING_DRAFT, ex.phase);
            assertTrue(GraphSendAttempt.isOutcomeUnknown(ex));
            EntityOperation operation = sendOperation(16L, 26L);
            List<EntityOperation> queue = new ArrayList<>(Arrays.asList(operation));
            assertTrue(GraphSendAttempt.continueQueueAfterFailure(queue, operation, ex));
            operation.error = EntityOperation.GRAPH_DRAFT_OUTCOME_UNKNOWN_ERROR;
            assertTrue(EntityOperation.isSendOutcomeUnknown(operation));
            assertTrue(queue.isEmpty());
        }
        assertEquals(1, requestCount[0]);
    }

    @Test
    public void explicitUnauthorizedResponseRefreshesAndRetriesExactlyOnce() throws Exception {
        int[] requests = {0};
        int[] refreshes = {0};
        GraphSendAttempt.TokenProvider tokens = new GraphSendAttempt.TokenProvider() {
            private String token = "old-token";

            @Override
            public String getAccessToken() {
                return token;
            }

            @Override
            public String refresh(String rejectedToken) {
                assertEquals("old-token", rejectedToken);
                refreshes[0]++;
                token = "new-token";
                return token;
            }
        };

        String result = GraphSendAttempt.executeAuthenticated(tokens, token -> {
            requests[0]++;
            if ("old-token".equals(token))
                throw new GraphSendAttempt.UnauthorizedResponseException();
            return "accepted";
        });

        assertEquals("accepted", result);
        assertEquals(2, requests[0]);
        assertEquals(1, refreshes[0]);
    }

    @Test
    public void repeatedUnauthorizedResponseDoesNotCauseASecondRefreshRetry() throws Exception {
        int[] requests = {0};
        int[] refreshes = {0};
        GraphSendAttempt.TokenProvider tokens = new GraphSendAttempt.TokenProvider() {
            @Override
            public String getAccessToken() {
                return "old-token";
            }

            @Override
            public String refresh(String rejectedToken) {
                refreshes[0]++;
                return "new-token";
            }
        };

        try {
            GraphSendAttempt.executeAuthenticated(tokens, token -> {
                requests[0]++;
                throw new GraphSendAttempt.UnauthorizedResponseException();
            });
            fail("A second 401 must not be retried");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("after one token refresh"));
        }

        assertEquals(2, requests[0]);
        assertEquals(1, refreshes[0]);
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

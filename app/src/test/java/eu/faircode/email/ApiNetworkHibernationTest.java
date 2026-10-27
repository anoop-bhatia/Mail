package eu.faircode.email;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApiNetworkHibernationTest {
    @Test
    public void nativeRestMonitorHibernatesWhenNetworkBecomesUnsuitable() {
        assertEquals(ApiNetworkHibernation.Transition.HIBERNATE,
                ApiNetworkHibernation.getTransition(true, true, true, false));
        assertEquals(ApiNetworkHibernation.Transition.NONE,
                ApiNetworkHibernation.getTransition(false, true, true, false));
        assertEquals(ApiNetworkHibernation.Transition.NONE,
                ApiNetworkHibernation.getTransition(true, false, true, false));
    }

    @Test
    public void nativeRestMonitorResumesWhenSuitableNetworkReturns() {
        assertEquals(ApiNetworkHibernation.Transition.RESUME,
                ApiNetworkHibernation.getTransition(true, true, false, true));
        assertEquals(ApiNetworkHibernation.Transition.NONE,
                ApiNetworkHibernation.getTransition(true, true, true, true));
    }

    @Test
    public void onlyTransportFailuresWhileOfflineAreTreatedAsHibernation() {
        assertTrue(ApiNetworkHibernation.shouldHibernateNetworkFailure(
                false, new UnknownHostException("offline")));
        assertTrue(ApiNetworkHibernation.shouldHibernateNetworkFailure(
                false, new SocketTimeoutException("offline timeout")));
        assertFalse(ApiNetworkHibernation.shouldHibernateNetworkFailure(
                true, new UnknownHostException("temporary DNS failure")));
        assertFalse(ApiNetworkHibernation.shouldHibernateNetworkFailure(
                false, new IOException("invalid local response")));
    }

    @Test
    public void transportFailuresRetryWhenNetworkStillLooksSuitable() {
        assertEquals(ApiNetworkHibernation.FailureAction.RETRY,
                ApiNetworkHibernation.getTransportFailureAction(
                        true, new SocketTimeoutException("route is not usable yet")));
        assertEquals(ApiNetworkHibernation.FailureAction.HIBERNATE,
                ApiNetworkHibernation.getTransportFailureAction(
                        false, new UnknownHostException("offline")));
        assertEquals(ApiNetworkHibernation.FailureAction.NONE,
                ApiNetworkHibernation.getTransportFailureAction(
                        true, new IOException("provider rejected request")));
    }

    @Test
    public void recognizesDirectAndWrappedTransportExceptionsButNotApiResponses() {
        assertTrue(ApiNetworkHibernation.isTransportFailure(new UnknownHostException("DNS")));
        assertTrue(ApiNetworkHibernation.isTransportFailure(new ConnectException("refused")));
        assertTrue(ApiNetworkHibernation.isTransportFailure(new NoRouteToHostException("route")));
        assertTrue(ApiNetworkHibernation.isTransportFailure(new SocketException("reset")));
        assertTrue(ApiNetworkHibernation.isTransportFailure(new SocketTimeoutException("timeout")));
        assertTrue(ApiNetworkHibernation.isTransportFailure(new EOFException("closed")));
        assertTrue(ApiNetworkHibernation.isTransportFailure(
                new IOException("wrapped", new SocketTimeoutException("timeout"))));
        assertFalse(ApiNetworkHibernation.isTransportFailure(
                new GmailApi.ApiException(401, "unauthorized")));
        assertFalse(ApiNetworkHibernation.isTransportFailure(
                new GraphMailApi.ApiException(503, 30_000L, null)));
        assertFalse(ApiNetworkHibernation.isTransportFailure(
                new ZohoApi.ApiException(429, 30_000L)));
    }

    @Test
    public void retryableProviderStatusesRemainDistinctFromPermanentErrors() {
        assertTrue(ApiNetworkHibernation.isRetryableApiFailure(
                new GmailApi.ApiException(429, "rate limited", 30_000L, true)));
        assertFalse(ApiNetworkHibernation.isRetryableApiFailure(
                new GmailApi.ApiException(401, "unauthorized")));
        assertTrue(ApiNetworkHibernation.isRetryableApiFailure(
                new GraphMailApi.ApiException(503, 30_000L, null)));
        assertFalse(ApiNetworkHibernation.isRetryableApiFailure(
                new GraphMailApi.ApiException(401, 0L, "InvalidAuthenticationToken")));
        assertTrue(ApiNetworkHibernation.isRetryableApiFailure(
                new ZohoApi.ApiException(429, 30_000L)));
        assertTrue(ApiNetworkHibernation.isRetryableApiFailure(
                new ZohoApi.ApiException(403, 30_000L)));
        assertFalse(ApiNetworkHibernation.isRetryableApiFailure(
                new ZohoApi.ApiException(403, 0L)));
        assertFalse(ApiNetworkHibernation.isRetryableApiFailure(
                new ZohoApi.ApiException(400, 0L)));
    }

    @Test
    public void reconnectWakeIsNativeOnlyAndBackoffIsBounded() {
        assertTrue(ApiNetworkHibernation.shouldWakeOnNetworkChange(
                true, true, true, true, true));
        assertFalse(ApiNetworkHibernation.shouldWakeOnNetworkChange(
                false, true, true, true, true));
        assertFalse(ApiNetworkHibernation.shouldWakeOnNetworkChange(
                true, false, true, true, true));
        assertFalse(ApiNetworkHibernation.shouldWakeOnNetworkChange(
                true, true, false, true, true)); // Offline-to-online already restarts the monitor.
        assertFalse(ApiNetworkHibernation.shouldWakeOnNetworkChange(
                true, true, true, true, false));

        assertEquals(60_000L, ApiNetworkHibernation.getTransportRetryDelayMs(1));
        assertEquals(120_000L, ApiNetworkHibernation.getTransportRetryDelayMs(2));
        assertEquals(240_000L, ApiNetworkHibernation.getTransportRetryDelayMs(3));
        assertEquals(15 * 60_000L, ApiNetworkHibernation.getTransportRetryDelayMs(20));
    }

    @Test
    public void reconnectWakeDoesNotMakeUnknownWritesOrSendsReplayable() {
        assertTrue(ApiNetworkHibernation.shouldWakeOnNetworkChange(
                true, true, true, true, true));

        EntityOperation graphSend = new EntityOperation();
        graphSend.name = EntityOperation.SEND;
        graphSend.error = EntityOperation.SEND_OUTCOME_UNKNOWN_ERROR;
        assertTrue(EntityOperation.isSendOutcomeUnknown(graphSend));
        assertFalse(EntityOperation.shouldRecoverSendOnSync(graphSend));

        EntityOperation gmailSend = new EntityOperation();
        gmailSend.name = EntityOperation.SEND;
        gmailSend.error = EntityOperation.GMAIL_SEND_OUTCOME_UNKNOWN_ERROR;
        assertTrue(EntityOperation.isSendOutcomeUnknown(gmailSend));
        assertFalse(EntityOperation.shouldRecoverSendOnSync(gmailSend));

        EntityOperation graphMutation = new EntityOperation();
        graphMutation.name = EntityOperation.MOVE;
        graphMutation.error = GraphMailMutator.OUTCOME_UNKNOWN_ERROR;
        assertTrue(GraphMutationRecovery.requiresReconciliation(
                graphMutation, GraphMailMutator.OUTCOME_UNKNOWN_ERROR));

        EntityOperation zohoMutation = new EntityOperation();
        zohoMutation.error = ZohoMailMutator.OUTCOME_UNKNOWN_ERROR;
        assertTrue(ZohoMutationRecovery.isQuarantined(zohoMutation));
    }

    @Test
    public void activeNetworkCancellationRemainsLegacyTransportOnly() {
        assertFalse(ApiNetworkHibernation.shouldUseLegacyNetworkChangeCancellation(true));
        assertTrue(ApiNetworkHibernation.shouldUseLegacyNetworkChangeCancellation(false));
    }

    @Test
    public void httpRateLimitResponsesRemainOnTheirRetryAfterPath() {
        assertFalse(ApiNetworkHibernation.shouldHibernateNetworkFailure(
                false, new GraphMailApi.ApiException(429, 30_000L, null)));
        assertFalse(ApiNetworkHibernation.shouldHibernateNetworkFailure(
                false, new GraphMailApi.ApiException(503, 30_000L, null)));
        assertFalse(ApiNetworkHibernation.shouldHibernateNetworkFailure(
                false, new GmailApi.ApiException(429, "rate limited", 30_000L, true)));
        assertFalse(ApiNetworkHibernation.shouldHibernateNetworkFailure(
                false, new ZohoApi.ApiException(429, 30_000L)));
    }
}

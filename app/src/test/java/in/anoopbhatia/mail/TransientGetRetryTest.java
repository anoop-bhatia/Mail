package in.anoopbhatia.mail;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransientGetRetryTest {
    @Test
    public void socketLossOnAnIdempotentRequestIsReplayedExactlyOnce() {
        for (IOException failure : new IOException[]{
                new SocketException("reset"),
                new SocketTimeoutException("timeout"),
                new ConnectException("refused"),
                new UnknownHostException("dns"),
                new EOFException("closed"),
                new IOException("wrapped", new SocketException("reset"))}) {
            assertTrue(failure.toString(), TransientGetRetry.shouldReplay("GET", 0, failure));
            assertFalse(failure.toString(), TransientGetRetry.shouldReplay("GET", 1, failure));
        }
        assertTrue(TransientGetRetry.shouldReplay("HEAD", 0, new SocketException("reset")));
    }

    @Test
    public void writesAreNeverReplayedAndProviderResponsesAreNotTransportFailures() {
        assertFalse(TransientGetRetry.shouldReplay("POST", 0, new SocketException("reset")));
        assertFalse(TransientGetRetry.shouldReplay("DELETE", 0, new SocketException("reset")));
        assertFalse(TransientGetRetry.shouldReplay("PATCH", 0, new SocketTimeoutException("timeout")));
        assertFalse(TransientGetRetry.shouldReplay("GET", 0,
                new GraphMailApi.ApiException(503, -1, null)));
        assertFalse(TransientGetRetry.shouldReplay("GET", 0,
                new GmailApi.ApiException(500, "failed")));
        assertFalse(TransientGetRetry.shouldReplay("GET", 0,
                new ZohoApi.ApiException(503, -1)));
        assertFalse(TransientGetRetry.shouldReplay("GET", 0,
                new IOException("Microsoft Graph response length did not match")));
        assertFalse(TransientGetRetry.shouldReplay("GET", 0, null));
    }

    @Test
    public void theReplayPauseIsShortAndAbortsWhenTheWorkerIsInterrupted() throws Exception {
        long started = System.currentTimeMillis();
        assertTrue(TransientGetRetry.pause(0));
        long elapsed = System.currentTimeMillis() - started;
        assertTrue("Replay delay must stay inside one pass: " + elapsed,
                elapsed >= TransientGetRetry.FIRST_DELAY_MS - 50 && elapsed < 5_000L);

        Thread.currentThread().interrupt();
        try {
            assertFalse(TransientGetRetry.pause(0));
            assertTrue("The interrupt must be restored for the caller",
                    Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }
}

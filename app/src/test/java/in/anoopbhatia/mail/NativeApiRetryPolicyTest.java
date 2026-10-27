package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NativeApiRetryPolicyTest {
    @Test
    public void transportBoundariesAreDeterministicAndJitterIsClamped() {
        assertEquals(20_000L, NativeApiRetryPolicy.transportDelayMs(-Integer.MAX_VALUE, 15_000L));
        assertEquals(20_000L, NativeApiRetryPolicy.transportDelayMs(1, -1L));
        assertEquals(20_000L, NativeApiRetryPolicy.transportDelayMs(1, Long.MAX_VALUE));
        assertEquals(55_000L, NativeApiRetryPolicy.transportDelayMs(2, 15_000L));
        assertEquals(95_000L, NativeApiRetryPolicy.transportDelayMs(3, 15_000L));
        assertEquals(300_000L, NativeApiRetryPolicy.transportDelayMs(Integer.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(NativeApiRetryPolicy.transportDelayMs(Integer.MAX_VALUE, -Long.MAX_VALUE) > 0);
    }

    @Test
    public void failureBackoffIsIndependentAndCapsWithoutIntegerOverflow() {
        assertEquals(60_000L, NativeApiRetryPolicy.failureDelayMs(-Integer.MAX_VALUE, 0L));
        assertEquals(60_000L, NativeApiRetryPolicy.failureDelayMs(1, 0L));
        assertEquals(75_000L, NativeApiRetryPolicy.failureDelayMs(1, 15_000L));
        assertEquals(135_000L, NativeApiRetryPolicy.failureDelayMs(2, 15_000L));
        assertEquals(255_000L, NativeApiRetryPolicy.failureDelayMs(3, 15_000L));
        assertEquals(3_600_000L, NativeApiRetryPolicy.failureDelayMs(7, 15_000L));
        assertEquals(3_600_000L, NativeApiRetryPolicy.failureDelayMs(8, 15_000L));
        assertEquals(3_600_000L, NativeApiRetryPolicy.failureDelayMs(11, 15_000L));
        assertEquals(3_600_000L, NativeApiRetryPolicy.failureDelayMs(Integer.MAX_VALUE, Long.MAX_VALUE));
        assertTrue(NativeApiRetryPolicy.failureDelayMs(Integer.MAX_VALUE, -Long.MAX_VALUE) > 0);
        assertTrue(NativeApiRetryPolicy.failureDelayMs(2, 0L) >
                NativeApiRetryPolicy.transportDelayMs(2, 0L));
    }

    @Test
    public void randomizedHelpersStayWithinDocumentedProductionBounds() {
        for (int failures : new int[]{-1, 0, 1, 2, 3, 7, 20, Integer.MAX_VALUE}) {
            long transport = NativeApiRetryPolicy.transportDelayMs(failures);
            assertTrue(transport >= 20_000L && transport <= 300_000L);
            long failure = NativeApiRetryPolicy.failureDelayMs(failures);
            assertTrue(failure >= 60_000L && failure <= 3_600_000L);
        }
    }

    @Test
    public void nativeMonitorsUseIndependentFailureCountersAndPreservePollingBranches() throws Exception {
        String source = source("ServiceSynchronize");
        String gmail = section(source, "private void monitorGmailAccount(",
                "private static int countRequestedGmailFolderSync(");
        String graph = section(source, "private void monitorGraphAccount(",
                "private int syncRequestedGraphFolders(");
        String zoho = section(source, "private void monitorZohoAccount(",
                "private ZohoReceiver.SyncResult syncRequestedZohoFolder(");
        for (String monitor : new String[]{gmail, graph, zoho}) {
            assertTrue(monitor.contains("int failureRetries = 0"));
            assertTrue(monitor.contains("failureRetries = 0"));
            assertTrue(monitor.contains("NativeApiRetryPolicy.failureDelayMs(++failureRetries)"));
            assertTrue(monitor.contains("ApiNetworkHibernation.getTransportRetryDelayMs(transportFailures)"));
        }
        assertTrue(gmail.contains("int quotaFailures = 0"));
        assertTrue(gmail.contains("quotaFailures = 0"));
        assertTrue(gmail.contains("GmailRateLimitBackoff.forRateLimit"));
        for (String monitor : new String[]{graph, zoho}) {
            assertTrue(monitor.contains("int rateLimitFailures = 0"));
            assertTrue(monitor.contains("rateLimitFailures = 0"));
            assertTrue(monitor.contains("NativeApiRetryPolicy.failureDelayMs(++rateLimitFailures)"));
            assertTrue(monitor.contains("state.acquireRateLimitWait(waitDelay)"));
            assertTrue(monitor.contains("state.acquire(waitDelay, false)"));
            assertTrue(monitor.contains("delay = pollDelay"));
            assertTrue(monitor.contains("rateLimitUntil"));
        }
        assertTrue(graph.contains("RetryAfter.isPresent(retryAfter) ? Math.max(1000L, retryAfter)"));
        assertTrue(graph.contains("ApiNetworkHibernation.isRetryableApiFailure(ex)"));
        assertTrue(zoho.contains("ZohoMutationRecovery.rateLimitDelayMs"));
        assertTrue(zoho.contains("ApiNetworkHibernation.isRetryableApiFailure(ex)"));

        int imapStart = source.indexOf("final PowerManager pm = Helper.getSystemService(this, PowerManager.class);",
                source.indexOf("private void monitorAccount("));
        assertTrue(imapStart >= 0);
        assertFalse(source.substring(imapStart).contains("NativeApiRetryPolicy"));
    }

    @Test
    public void startupSerializationAppliesOnlyToLegacyConnections() throws Exception {
        String startup = section(source("ServiceSynchronize"),
                "private void start(final TupleAccountNetworkState", "executorService.submit(");
        assertTrue(startup.contains("if (start_delay > 0 && !accountNetworkState.accountState.isNativeRestApi())"));
        assertTrue(startup.contains("sem.acquire()"));
        assertTrue(startup.contains("start_delay * 1000L"));
        assertFalse(startup.contains("gmailFolderSyncNow"));
    }

    private static String section(String source, String start, String end) {
        int first = source.indexOf(start);
        int last = source.indexOf(end, first + start.length());
        assertTrue("Missing section " + start, first >= 0 && last > first);
        return source.substring(first, last);
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}

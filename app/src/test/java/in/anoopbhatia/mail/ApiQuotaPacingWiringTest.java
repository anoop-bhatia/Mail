package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Graph and Zoho must plan their passes against their own request budget, the way Gmail plans
 * against its quota units, and a pacing wait must look like a healthy account rather than a
 * connection attempt that lasts minutes.
 */
public class ApiQuotaPacingWiringTest {
    @Test
    public void graphAndZohoPlanPassesAgainstTheirOwnRequestBudget() throws Exception {
        String service = source("ServiceSynchronize");
        for (String provider : new String[]{"GRAPH", "ZOHO"}) {
            String monitor = monitor(service, provider);
            assertTrue(provider, monitor.contains("ApiQuotaWindow." + provider + "_WINDOW_MS"));
            assertTrue(provider, monitor.contains("ApiQuotaWindow." + provider + "_REQUESTS_PER_WINDOW"));
            assertTrue(provider, monitor.contains("ApiQuotaWindow." + provider + "_PASS_BASE_REQUESTS"));
            assertTrue(provider, monitor.contains("quotaWindow.beginPass("));
            assertTrue(provider, monitor.contains("api.setRequestListener("));
            assertTrue(provider, monitor.contains("quotaWindow.finishPass(quotaPlan.reservationId"));
        }
    }

    @Test
    public void aPacingWaitDelaysThePassWithoutLookingLikeAConnection() throws Exception {
        String service = source("ServiceSynchronize");
        for (String provider : new String[]{"GRAPH", "ZOHO"}) {
            String monitor = monitor(service, provider);
            int pacing = monitor.indexOf("quotaPlan.allowedAt > quotaNow");
            assertTrue(provider, pacing >= 0);
            int acquire = monitor.indexOf("state.acquire(quotaPlan.allowedAt", pacing);
            assertTrue(provider, acquire > pacing);
            int connected = monitor.indexOf("setAccountState(account.id, \"connected\")", pacing);
            assertTrue(provider, connected > pacing && connected < acquire);
            assertFalse(provider + " must not report pacing as a connection attempt",
                    monitor.substring(pacing, acquire).contains("setAccountState(account.id, \"connecting\")"));
            // The wait must be interruptible like every other backoff in the monitor.
            assertTrue(provider, monitor.substring(acquire, monitor.indexOf("continue;", acquire))
                    .contains("if (!state.isRunning())"));
        }
    }

    @Test
    public void gmailKeepsItsUnitsBasedQuotaWindow() throws Exception {
        String service = source("ServiceSynchronize");
        assertTrue(service.contains("GmailQuotaWindow quotaWindow = getGmailQuotaWindow("));
        assertTrue(service.contains("quotaWindow.recordRequest(requestAt, units)"));
    }

    private static String monitor(String source, String provider) {
        String name = "private void monitor" +
                (provider.equals("GRAPH") ? "Graph" : "Zoho") + "Account(";
        int start = source.indexOf(name);
        assertTrue("Missing " + name, start >= 0);
        int end = source.indexOf("\n    private ", start + 1);
        assertTrue("Unterminated " + name, end > start);
        return source.substring(start, end);
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}

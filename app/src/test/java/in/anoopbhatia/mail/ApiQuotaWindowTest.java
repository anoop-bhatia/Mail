package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ApiQuotaWindowTest {
    private static final long WINDOW = 60_000L;
    private static final int MAX = 10;

    @Test
    public void graphBudgetMatchesTheDocumentedPerMailboxLimit() {
        assertEquals(10 * 60 * 1000L, ApiQuotaWindow.GRAPH_WINDOW_MS);
        assertEquals(10_000, ApiQuotaWindow.GRAPH_REQUESTS_PER_WINDOW);
        // A pass must not reserve the whole window, or pacing would block its own work.
        assertTrue(ApiQuotaWindow.GRAPH_PASS_BASE_REQUESTS < ApiQuotaWindow.GRAPH_REQUESTS_PER_WINDOW);
        assertTrue(ApiQuotaWindow.ZOHO_PASS_BASE_REQUESTS < ApiQuotaWindow.ZOHO_REQUESTS_PER_WINDOW);
    }

    @Test
    public void aPassIsAdmittedAndItsReservationIsReleasedAtTheEnd() {
        ApiQuotaWindow window = new ApiQuotaWindow(null, WINDOW, MAX);
        long now = 1_000_000L;
        ApiQuotaWindow.PassPlan plan = window.beginPass(now, 4, 0, 0);
        assertTrue(plan.isReserved());
        assertEquals(now, plan.allowedAt);
        assertEquals(4, window.usedRequests());

        window.recordRequest(now + 5);
        window.recordRequest(now + 6);
        window.finishPass(plan.reservationId, now + 7);

        // Only the two real requests remain; the unused part of the reservation is returned.
        assertEquals(2, window.usedRequests());
    }

    @Test
    public void aReservationLeftBehindByADeadWorkerDoesNotSpendTheReloadedWindow() {
        long now = 1_000_000L;
        ApiQuotaWindow live = new ApiQuotaWindow(null, WINDOW, MAX);
        ApiQuotaWindow.PassPlan plan = live.beginPass(now, 4, 0, 0);
        assertTrue(plan.isReserved());
        assertEquals(4, live.usedRequests());

        // The worker is killed before finishPass, so the reservation is what gets persisted.
        ApiQuotaWindow reloaded = new ApiQuotaWindow(live.serialize(now), WINDOW, MAX);
        assertEquals("a reservation cannot outlive the pass that made it", 0, reloaded.usedRequests());
        assertTrue(reloaded.beginPass(now, 4, 0, 0).isReserved());
    }

    @Test
    public void aFullWindowDelaysTheNextPassUntilTheOldestChargeExpires() {
        ApiQuotaWindow window = new ApiQuotaWindow(null, WINDOW, MAX);
        long now = 1_000_000L;
        window.recordRequest(now + 5);
        window.recordRequest(now + 6);
        window.recordRequest(now + 10, 8);
        assertEquals(MAX, window.usedRequests());

        ApiQuotaWindow.PassPlan blocked = window.beginPass(now + 20, 1, 0, 0);
        assertFalse(blocked.isReserved());
        assertEquals(now + 5 + WINDOW, blocked.allowedAt);

        // As soon as the oldest charge ages out the window admits work again: the two newer
        // charges (1 + 8) remain, plus the new one-request reservation.
        ApiQuotaWindow.PassPlan admitted = window.beginPass(now + 5 + WINDOW, 1, 0, 0);
        assertTrue(admitted.isReserved());
        assertEquals(10, window.usedRequests());
        window.finishPass(admitted.reservationId, now + 5 + WINDOW);
        assertEquals(9, window.usedRequests());
    }

    @Test
    public void rateLimitBackoffIsNeverShortenedByQuotaPacing() {
        ApiQuotaWindow window = new ApiQuotaWindow(null, WINDOW, MAX);
        long now = 1_000_000L;
        ApiQuotaWindow.PassPlan plan = window.beginPass(now, 1, now + 45_000, 0);
        assertFalse(plan.isReserved());
        assertEquals(now + 45_000, plan.allowedAt);

        assertEquals(now + 30_000,
                window.beginPass(now, 1, 0, now + 30_000).allowedAt);
    }

    @Test
    public void aReserveLargerThanTheWindowCanNeverBeAdmitted() {
        ApiQuotaWindow window = new ApiQuotaWindow(null, WINDOW, MAX);
        ApiQuotaWindow.PassPlan plan = window.beginPass(1_000_000L, MAX + 1, 0, 0);
        assertFalse(plan.isReserved());
        assertEquals(Long.MAX_VALUE, plan.allowedAt);
    }

    @Test
    public void chargesSurviveAPreferenceRoundTripAndMalformedEntriesAreDiscarded() {
        ApiQuotaWindow window = new ApiQuotaWindow(null, WINDOW, MAX);
        long now = 1_000_000L;
        window.recordRequest(now, 3);
        window.recordRequest(now + 1, 2);
        String serialized = window.serialize(now + 2);

        ApiQuotaWindow restored = new ApiQuotaWindow(serialized, WINDOW, MAX);
        assertEquals(5, restored.usedRequests());
        assertEquals(serialized, restored.serialize(now + 2));

        ApiQuotaWindow malformed = new ApiQuotaWindow("nonsense;1,x,;2,3,;3,-4,", WINDOW, MAX);
        assertEquals(3, malformed.usedRequests());
    }

    @Test
    public void eachProviderAndAccountGetsItsOwnStableKey() {
        String key = ApiQuotaWindow.key("graph", "User@Example.com", 1L);
        assertEquals(key, ApiQuotaWindow.key("graph", " user@example.com ", 2L));
        assertFalse(key.equals(ApiQuotaWindow.key("zoho", "user@example.com", 1L)));
        assertFalse(key.equals(ApiQuotaWindow.key("graph", "other@example.com", 1L)));
        assertEquals(ApiQuotaWindow.key("graph", null, 42L),
                ApiQuotaWindow.key("graph", "  ", 42L));
    }

    @Test
    public void aDifferentBudgetDoesNotShareTheOtherProvidersWindow() {
        ApiQuotaWindow graph = new ApiQuotaWindow(null,
                ApiQuotaWindow.GRAPH_WINDOW_MS, ApiQuotaWindow.GRAPH_REQUESTS_PER_WINDOW);
        ApiQuotaWindow zoho = new ApiQuotaWindow(null,
                ApiQuotaWindow.ZOHO_WINDOW_MS, ApiQuotaWindow.ZOHO_REQUESTS_PER_WINDOW);
        long now = 1_000_000L;
        graph.recordRequest(now, ApiQuotaWindow.GRAPH_REQUESTS_PER_WINDOW);
        assertFalse(graph.beginPass(now + 1, 1, 0, 0).isReserved());
        assertTrue(zoho.beginPass(now + 1,
                ApiQuotaWindow.ZOHO_PASS_BASE_REQUESTS, 0, 0).isReserved());
    }
}

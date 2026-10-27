package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailRateLimitBackoffTest {
    @Test
    public void tenMessagePageCostIsBoundedAndOldHundredMessagePagesExhaustedQuota() {
        assertEquals(10, GmailRateLimitBackoff.MAX_MESSAGES_PER_PASS);
        assertEquals(206L, GmailRateLimitBackoff.fullSyncPageQuotaUnits(10));
        assertEquals(2_006L, GmailRateLimitBackoff.fullSyncPageQuotaUnits(100));
        assertEquals(24_072L,
                GmailRateLimitBackoff.fullSyncPageQuotaUnits(100) * 60_000L / 5_000L);
    }

    @Test
    public void tenMessageFolderPageCostsAtMost205UnitsAndLabelsAddsOne() {
        assertEquals(10, GmailReceiver.PAGE_SIZE);
        assertEquals(205, GmailRateLimitBackoff.MESSAGES_LIST_QUOTA_UNITS +
                GmailReceiver.PAGE_SIZE * GmailRateLimitBackoff.MESSAGES_GET_QUOTA_UNITS);
        assertEquals(206, GmailRateLimitBackoff.LABELS_LIST_QUOTA_UNITS +
                GmailRateLimitBackoff.MESSAGES_LIST_QUOTA_UNITS +
                GmailReceiver.PAGE_SIZE * GmailRateLimitBackoff.MESSAGES_GET_QUOTA_UNITS);
        assertEquals(210, GmailQuotaWindow.FOLDER_PAGE_RESERVE_UNITS);
    }

    @Test
    public void rollingQuotaWindowAllowsRapidSmallPagesWithoutAnArtificialMinuteFloor() {
        long now = 1_800_000_000_000L;
        GmailQuotaWindow window = new GmailQuotaWindow(null);
        int baseReserve = GmailQuotaWindow.baseReserveUnits(true, false, false);
        GmailQuotaWindow.PassPlan first = window.beginPass(now, 1, 0, 0, baseReserve);
        assertEquals(now, first.allowedAt);
        assertEquals(1, first.folderPageLimit);
        assertEquals(230, first.reservedUnits);
        window.recordRequest(now + 10, 205);
        window.finishPass(first.reservationId, now + 20);

        GmailQuotaWindow.PassPlan second = window.beginPass(now + 20, 1, 0, 0, baseReserve);
        assertEquals("A 205-unit page does not impose a fixed 60-second delay",
                now + 20, second.allowedAt);
        assertEquals(1, second.folderPageLimit);
    }

    @Test
    public void rollingPlannerBypassesPollDeadlineButHonorsQuotaBackoffForQueuedFolderWork() {
        long now = 1_800_000_000_000L;
        GmailQuotaWindow window = new GmailQuotaWindow(null);

        int baseReserve = GmailQuotaWindow.baseReserveUnits(true, false, false);
        GmailQuotaWindow.PassPlan immediate = window.beginPass(
                now, 1, 0, now + 15 * 60 * 1000L, baseReserve);
        assertEquals(now, immediate.allowedAt);
        assertTrue(immediate.isReserved());
        window.finishPass(immediate.reservationId, now);

        long retryAt = now + 75_000L;
        GmailQuotaWindow.PassPlan backedOff = window.beginPass(
                now, 1, retryAt, now + 15 * 60 * 1000L, baseReserve);
        assertEquals(retryAt, backedOff.allowedAt);
        assertFalse(backedOff.isReserved());
    }

    @Test
    public void coldBootstrapWaitsOnlyForItsActualReservedHeadroom() {
        long now = 1_800_000_000_000L;
        GmailQuotaWindow window = new GmailQuotaWindow(null);
        window.recordRequest(now, 5_600);

        int bootstrapReserve = GmailQuotaWindow.baseReserveUnits(true, true, false);
        GmailQuotaWindow.PassPlan blocked = window.beginPass(now, 1, 0, 0, bootstrapReserve);
        assertEquals(now + GmailQuotaWindow.WINDOW_MS, blocked.allowedAt);
        assertFalse(blocked.isReserved());

        GmailQuotaWindow.PassPlan ready = window.beginPass(
                now + GmailQuotaWindow.WINDOW_MS, 1, 0, 0, bootstrapReserve);
        assertEquals(now + GmailQuotaWindow.WINDOW_MS, ready.allowedAt);
        assertTrue(ready.isReserved());
    }

    @Test
    public void folderBatchLimitUsesTheFullDocumentedRollingQuota() {
        long now = 1_800_000_000_000L;
        GmailQuotaWindow window = new GmailQuotaWindow(null);
        assertEquals(6_000, GmailQuotaWindow.MAX_SYNC_UNITS_PER_WINDOW);
        GmailQuotaWindow.PassPlan batch = window.beginPass(now, 28, 0, 0);
        assertEquals(27, batch.folderPageLimit);
        assertEquals(5_899, batch.reservedUnits);
        assertTrue(batch.reservedUnits <= GmailQuotaWindow.MAX_SYNC_UNITS_PER_WINDOW);
        window.finishPass(batch.reservationId, now);

        window.recordRequest(now + 1, 205);
        GmailQuotaWindow.PassPlan next = window.beginPass(now + 2, 28, 0, 0);
        assertEquals("Rolling headroom permits another bounded batch immediately", now + 2, next.allowedAt);
        assertEquals(26, next.folderPageLimit);
    }

    @Test
    public void settledFolderPageDoesNotWaitForColdBootstrapReserve() {
        long now = 1_800_000_000_000L;
        assertEquals(20, GmailQuotaWindow.baseReserveUnits(true, false, false));
        assertEquals(21, GmailQuotaWindow.baseReserveUnits(true, false, true));
        assertEquals(229, GmailQuotaWindow.baseReserveUnits(true, true, false));

        GmailQuotaWindow window = new GmailQuotaWindow(null);
        window.recordRequest(now, 5_600);
        GmailQuotaWindow.PassPlan settled = window.beginPass(now, 1, 0, 0,
                GmailQuotaWindow.baseReserveUnits(true, false, false));
        assertEquals("A settled 205-unit page plus possible 401 replay fits the remaining 400 units",
                now, settled.allowedAt);
        assertEquals(1, settled.folderPageLimit);
        assertEquals(230, settled.reservedUnits);

        GmailQuotaWindow.PassPlan settledBatch = new GmailQuotaWindow(null).beginPass(
                now, 28, 0, 0, GmailQuotaWindow.baseReserveUnits(true, false, false));
        assertEquals(28, settledBatch.folderPageLimit);
        assertEquals(5_900, settledBatch.reservedUnits);

        GmailQuotaWindow.PassPlan labelsDue = new GmailQuotaWindow(null).beginPass(
                now, 1, 0, 0, GmailQuotaWindow.baseReserveUnits(true, false, true));
        assertEquals(231, labelsDue.reservedUnits);
    }

    @Test
    public void rollingQuotaChargesSurviveSerializationAndExpireAtWindowBoundary() {
        long now = 1_800_000_000_000L;
        GmailQuotaWindow window = new GmailQuotaWindow(null);
        window.recordRequest(now, 5_600);
        GmailQuotaWindow restored = new GmailQuotaWindow(window.serialize(now));
        assertEquals(now + GmailQuotaWindow.WINDOW_MS,
                restored.beginPass(now, 1, 0, 0).allowedAt);
        assertEquals(now + GmailQuotaWindow.WINDOW_MS,
                restored.beginPass(now + GmailQuotaWindow.WINDOW_MS, 1, 0, 0).allowedAt);
    }

    @Test
    public void anInterruptedBatchReservationRemainsCountedUntilItsWindowExpires() {
        long now = 1_800_000_000_000L;
        GmailQuotaWindow original = new GmailQuotaWindow(null);
        GmailQuotaWindow.PassPlan batch = original.beginPass(now, 28, 0, 0);
        GmailQuotaWindow restored = new GmailQuotaWindow(original.serialize(now));

        GmailQuotaWindow.PassPlan blocked = restored.beginPass(now, 0, 0, 0);
        assertEquals(now + GmailQuotaWindow.WINDOW_MS, blocked.allowedAt);
        assertFalse(blocked.isReserved());
        assertTrue(restored.beginPass(now + GmailQuotaWindow.WINDOW_MS, 0, 0, 0).isReserved());
    }

    @Test
    public void gmailRequestEndpointCostsMatchOfficialPerMethodQuota() {
        assertEquals(1, GmailApi.quotaUnitsForRequest("GET", GmailApi.LABELS_ENDPOINT));
        assertEquals(1, GmailApi.quotaUnitsForRequest("GET", GmailApi.PROFILE_ENDPOINT));
        assertEquals(2, GmailApi.quotaUnitsForRequest("GET", GmailApi.HISTORY_ENDPOINT));
        assertEquals(5, GmailApi.quotaUnitsForRequest("GET", GmailApi.MESSAGES_ENDPOINT + "?maxResults=10"));
        assertEquals(20, GmailApi.quotaUnitsForRequest("GET", GmailApi.MESSAGES_ENDPOINT + "/id?format=raw"));
        assertEquals(100, GmailApi.quotaUnitsForRequest("POST", GmailApi.SEND_ENDPOINT));
        assertEquals(GmailRateLimitBackoff.MESSAGES_MODIFY_QUOTA_UNITS,
                GmailApi.quotaUnitsForRequest("POST", GmailApi.MESSAGES_ENDPOINT + "/id/modify"));
        assertEquals(GmailRateLimitBackoff.MESSAGES_MODIFY_QUOTA_UNITS,
                GmailApi.quotaUnitsForRequest("POST", GmailApi.MESSAGES_ENDPOINT + "/id/trash"));
        assertEquals(GmailRateLimitBackoff.MESSAGES_MODIFY_QUOTA_UNITS,
                GmailApi.quotaUnitsForRequest("POST", GmailApi.MESSAGES_ENDPOINT + "/id/untrash"));
        assertEquals(GmailRateLimitBackoff.MESSAGES_DELETE_QUOTA_UNITS,
                GmailApi.quotaUnitsForRequest("DELETE", GmailApi.MESSAGES_ENDPOINT + "/id"));
        assertEquals(0, GmailApi.quotaUnitsForRequest("GET", "https://example.com/unknown"));
    }

    @Test
    public void quotaFailureKeepsConnectedAccountAndCursorAndHonorsRetryAfter() {
        long now = 1_800_000_000_000L;
        GmailApi.ApiException quota = new GmailApi.ApiException(
                403, "PERMISSION_DENIED quota.metric Total Query Cost", 75_000L, true);

        assertTrue(quota.isRateLimitFailure());
        GmailRateLimitBackoff.RetryPlan plan = GmailRateLimitBackoff.forRateLimit(
                0, quota.retryAfterMs, 0, now);

        assertTrue(plan.keepAccountConnected);
        assertTrue(plan.preserveSyncCursor);
        assertEquals(75_000L, plan.delayMs);
        assertEquals(now + 75_000L, plan.nextAttemptAt);
    }

    @Test
    public void explicitFolderRequestMayBypassPollDeadlineButNeverQuotaBackoff() {
        long now = 20_000L;
        assertEquals(1_200_000L, GmailRateLimitBackoff.nextAllowedRequestAt(
                now, 1_200_000L, 900_000L, true));
        assertEquals(900_000L, GmailRateLimitBackoff.nextAllowedRequestAt(
                now, 0, 900_000L, false));
        assertEquals(now, GmailRateLimitBackoff.nextAllowedRequestAt(
                now, 0, 0, false));
    }

    @Test
    public void queuedAttachmentStillWaitsForQuotaRetryAfterAndAuthenticationRetryDeadline() {
        long now = 1_800_000_000_000L;
        int reserve = GmailQuotaWindow.PASS_BASE_RESERVE_UNITS;
        GmailQuotaWindow window = new GmailQuotaWindow(null);

        long retryAfter = now + 75_000L;
        GmailQuotaWindow.PassPlan rateLimited = window.beginPass(
                now, 0, true, retryAfter, 0, now + 15 * 60 * 1000L, reserve);
        assertEquals("Retry-After remains a hard gate for attachment requests",
                retryAfter, rateLimited.allowedAt);
        assertFalse(rateLimited.isReserved());

        long authRetryAt = now + 60_000L;
        GmailQuotaWindow.PassPlan authorizationBlocked = window.beginPass(
                now, 0, true, 0, authRetryAt, now + 15 * 60 * 1000L, reserve);
        assertEquals("A failed token refresh cannot create an immediate retry loop",
                authRetryAt, authorizationBlocked.allowedAt);
        assertFalse(authorizationBlocked.isReserved());
    }

    @Test
    public void missingRetryAfterUsesExponentialBackoffWithJitterAndOneHourCap() {
        long now = 1_800_000_000_000L;
        GmailRateLimitBackoff.RetryPlan first =
                GmailRateLimitBackoff.forRateLimit(0, -1, 5_000L, now);
        GmailRateLimitBackoff.RetryPlan second =
                GmailRateLimitBackoff.forRateLimit(1, -1, 10_000L, now);
        GmailRateLimitBackoff.RetryPlan capped =
                GmailRateLimitBackoff.forRateLimit(100, -1, 15_000L, now);

        assertEquals(65_000L, first.delayMs);
        assertEquals(130_000L, second.delayMs);
        assertEquals(GmailRateLimitBackoff.MAX_BACKOFF_MS, capped.delayMs);
    }

    @Test
    public void retryAfterLongerThanFallbackCapIsStillHonored() {
        GmailRateLimitBackoff.RetryPlan plan = GmailRateLimitBackoff.forRateLimit(
                0, 2 * 60 * 60 * 1000L, 1_000L, 1_800_000_000_000L);
        assertEquals(2 * 60 * 60 * 1000L + 1_000L, plan.delayMs);
    }
}

package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The three REST providers answer the same HTTP status with the same meaning. A throttle (429, or
 * a provider-specific quota body) keeps cursors and the connected state; a 5xx is a service
 * failure and takes the ordinary failure path everywhere. Graph used to fold 503 into its
 * throttle class, so an outage looked healthy there and broken on Gmail/Zoho.
 */
public class ApiFailureClassificationTest {
    @Test
    public void throttlesAndServiceFailuresAreClassifiedIdenticallyPerProvider() {
        int[] statuses = {429, 500, 502, 503, 504, 401, 404};
        for (int status : statuses) {
            boolean throttle = (status == 429);
            String label = "HTTP " + status;
            assertTrue(label + " Graph", throttle ==
                    new GraphMailApi.ApiException(status, 30_000L, null).isRateLimitFailure());
            assertTrue(label + " Gmail", throttle == GmailApi.isRateLimitFailure(status, null));
            assertTrue(label + " Gmail error body", throttle == GmailApi.isRateLimitFailure(status,
                    "{\"error\":{\"code\":" + status + ",\"message\":\"failed\"}}"));
            assertTrue(label + " Zoho", throttle ==
                    new ZohoApi.ApiException(status, 30_000L).isRateLimitFailure());
        }
    }

    @Test
    public void providerSpecificQuotaSignalsStayRateLimits() {
        // Gmail reports per-user quota exhaustion as 403 with a reason in the body.
        assertTrue(GmailApi.isRateLimitFailure(403,
                "{\"error\":{\"errors\":[{\"reason\":\"rateLimitExceeded\"}]}}"));
        assertTrue(GmailApi.isRateLimitFailure(403,
                "{\"error\":{\"errors\":[{\"reason\":\"userRateLimitExceeded\"}]}}"));
        assertFalse(GmailApi.isRateLimitFailure(403,
                "{\"error\":{\"errors\":[{\"reason\":\"insufficientPermissions\"}]}}"));
        // Zoho signals throttling with 403 plus a Retry-After header.
        assertTrue(new ZohoApi.ApiException(403, 30_000L).isRateLimitFailure());
        assertTrue(new ZohoApi.ApiException(403, 0L).isRateLimitFailure());
        assertFalse(new ZohoApi.ApiException(403, RetryAfter.UNAVAILABLE).isRateLimitFailure());
        // A Graph 403 is a permission decision, never a throttle.
        assertFalse(new GraphMailApi.ApiException(403, 30_000L, "ErrorAccessDenied")
                .isRateLimitFailure());
    }
}

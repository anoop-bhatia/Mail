package in.anoopbhatia.mail;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Rolling per-account request budget for the request-metered REST providers.
 *
 * <p>Gmail charges documented quota units per endpoint ({@link GmailQuotaWindow}); Graph and Zoho
 * instead publish a request count, so this window counts requests. Its purpose is the same one the
 * Gmail window serves: a pass that would not fit the current window is delayed until room exists,
 * instead of being sent and stalling on a 429 for a minute or more.</p>
 */
final class ApiQuotaWindow {
    /**
     * Microsoft Graph, Outlook mail: 10,000 API requests per 10 minutes per app+mailbox
     * (https://learn.microsoft.com/graph/throttling-limits). Charging below the service limit
     * would throttle the app ourselves for no reason.
     */
    static final long GRAPH_WINDOW_MS = 10 * 60 * 1000L;
    static final int GRAPH_REQUESTS_PER_WINDOW = 10_000;
    /** Folder mapping, Inbox delta, requested folders, mutations and one 401 replay. */
    static final int GRAPH_PASS_BASE_REQUESTS = 40;

    /**
     * Zoho Mail documents 429 responses but no numeric Mail API limit, so this is a self-imposed
     * guard against bursts rather than an asserted service quota: it keeps one pass from hammering
     * the endpoint, and a server Retry-After still governs when a 429 does arrive.
     */
    static final long ZOHO_WINDOW_MS = 60 * 1000L;
    static final int ZOHO_REQUESTS_PER_WINDOW = 120;
    static final int ZOHO_PASS_BASE_REQUESTS = 25;

    /** Persist charges at most this often; the window is rebuilt from preferences after a restart. */
    static final long PERSIST_INTERVAL_MS = 5_000L;

    private final long windowMs;
    private final int maxRequests;
    private final List<Charge> charges = new ArrayList<>();

    /** Stable preference key, so an account's charges survive a service restart. */
    static String key(String provider, String identity, long fallbackId) {
        String normalized = (identity == null || identity.trim().isEmpty()
                ? Long.toString(fallbackId)
                : identity.trim().toLowerCase(Locale.ROOT));
        return "api_rest_quota_v1." + provider + "." + UUID.nameUUIDFromBytes(
                normalized.getBytes(StandardCharsets.UTF_8));
    }

    ApiQuotaWindow(String serialized, long windowMs, int maxRequests) {
        if (windowMs <= 0 || maxRequests <= 0)
            throw new IllegalArgumentException("Invalid API quota window");
        this.windowMs = windowMs;
        this.maxRequests = maxRequests;
        if (serialized == null || serialized.isEmpty())
            return;
        for (String record : serialized.split(";")) {
            String[] fields = record.split(",", -1);
            if (fields.length != 3)
                continue;
            try {
                long at = Long.parseLong(fields[0]);
                int requests = Integer.parseInt(fields[1]);
                // A reservation only exists while the pass that made it is running in this
                // process, and finishPass removes it. A persisted one means the worker died
                // mid-pass, so keeping it would spend budget on work that is no longer running.
                if (!fields[2].isEmpty())
                    continue;
                if (at >= 0 && requests > 0)
                    charges.add(new Charge(at, requests, null));
            } catch (NumberFormatException ignored) {
                // Discard a malformed preference entry and keep valid recent charges.
            }
        }
    }

    /**
     * Plan and reserve atomically so simultaneous accounts for one provider stay inside the
     * window. A reserved pass holds {@code baseRequests} until it finishes, so two passes cannot
     * both spend the same remaining budget.
     */
    synchronized PassPlan beginPass(long nowMs, int baseRequests,
                                    long rateLimitUntil, long retryBackoffUntil) {
        if (baseRequests < 0)
            throw new IllegalArgumentException("Negative API pass request reserve");
        prune(nowMs);
        int reserve = Math.max(1, baseRequests);
        long allowedAt = Math.max(nowMs, Math.max(rateLimitUntil, retryBackoffUntil));
        allowedAt = Math.max(allowedAt, nextAvailableAt(nowMs, reserve));
        if (allowedAt > nowMs)
            return new PassPlan(allowedAt, null);
        String reservationId = UUID.randomUUID().toString();
        charges.add(new Charge(nowMs, reserve, reservationId));
        return new PassPlan(nowMs, reservationId);
    }

    /** Record one actual provider request attempt, including failed responses and 401 replays. */
    synchronized void recordRequest(long atMs) {
        recordRequest(atMs, 1);
    }

    synchronized void recordRequest(long atMs, int requests) {
        if (requests <= 0)
            return;
        prune(atMs);
        charges.add(new Charge(atMs, requests, null));
    }

    synchronized void finishPass(String reservationId, long nowMs) {
        if (reservationId == null)
            return;
        for (Iterator<Charge> iterator = charges.iterator(); iterator.hasNext(); )
            if (reservationId.equals(iterator.next().reservationId)) {
                iterator.remove();
                break;
            }
        prune(nowMs);
    }

    synchronized String serialize(long nowMs) {
        prune(nowMs);
        StringBuilder result = new StringBuilder();
        for (Charge charge : charges) {
            if (result.length() > 0)
                result.append(';');
            result.append(charge.atMs).append(',').append(charge.requests).append(',');
            if (charge.reservationId != null)
                result.append(charge.reservationId);
        }
        return result.toString();
    }

    synchronized int usedRequests() {
        long total = 0;
        for (Charge charge : charges)
            total += charge.requests;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private long nextAvailableAt(long nowMs, int requestedRequests) {
        if (requestedRequests > maxRequests)
            return Long.MAX_VALUE;
        long remaining = usedRequests();
        if (remaining + requestedRequests <= maxRequests)
            return nowMs;

        List<Charge> ordered = new ArrayList<>(charges);
        Collections.sort(ordered, new Comparator<Charge>() {
            @Override
            public int compare(Charge left, Charge right) {
                return Long.compare(left.atMs, right.atMs);
            }
        });
        for (Charge charge : ordered) {
            remaining -= charge.requests;
            if (remaining + requestedRequests <= maxRequests)
                return Math.max(nowMs, saturatedAdd(charge.atMs, windowMs));
        }
        return (ordered.isEmpty() ? nowMs :
                Math.max(nowMs, saturatedAdd(ordered.get(ordered.size() - 1).atMs, windowMs)));
    }

    private void prune(long nowMs) {
        for (Iterator<Charge> iterator = charges.iterator(); iterator.hasNext(); ) {
            Charge charge = iterator.next();
            if (nowMs >= saturatedAdd(charge.atMs, windowMs))
                iterator.remove();
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right)
            return Long.MAX_VALUE;
        return left + right;
    }

    private static final class Charge {
        final long atMs;
        final int requests;
        final String reservationId;

        Charge(long atMs, int requests, String reservationId) {
            this.atMs = atMs;
            this.requests = requests;
            this.reservationId = reservationId;
        }
    }

    static final class PassPlan {
        final long allowedAt;
        final String reservationId;

        PassPlan(long allowedAt, String reservationId) {
            this.allowedAt = allowedAt;
            this.reservationId = reservationId;
        }

        boolean isReserved() {
            return reservationId != null;
        }
    }
}

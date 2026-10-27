package eu.faircode.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Rolling per-user Gmail REST quota accounting for native synchronization. */
final class GmailQuotaWindow {
    static final long WINDOW_MS = 60 * 1000L;
    // Use the documented per-user limit; actual request charges, not a fixed fraction, gate work.
    static final int MAX_SYNC_UNITS_PER_WINDOW =
            GmailRateLimitBackoff.USER_QUOTA_UNITS_PER_MINUTE;
    // Full page, labels, profile, expired-history probe, and one bounded 401 replay.
    static final int PASS_BASE_RESERVE_UNITS = 229;
    // Two list attempts cover a stale page token; ten message GETs cover one folder page.
    static final int FOLDER_PAGE_RESERVE_UNITS = 210;

    static int baseReserveUnits(boolean folderSyncRequested, boolean coldBootstrap,
                                boolean refreshLabels) {
        if (!folderSyncRequested || coldBootstrap)
            return PASS_BASE_RESERVE_UNITS;
        // Established folder work defers history; allow one possible GET 401 replay
        // and charge the label refresh only when the 15-minute cache is due.
        return GmailRateLimitBackoff.MESSAGES_GET_QUOTA_UNITS +
                (refreshLabels ? GmailRateLimitBackoff.LABELS_LIST_QUOTA_UNITS : 0);
    }

    private final List<Charge> charges = new ArrayList<>();

    GmailQuotaWindow(String serialized) {
        if (serialized == null || serialized.isEmpty())
            return;
        for (String record : serialized.split(";")) {
            String[] fields = record.split(",", -1);
            if (fields.length != 3)
                continue;
            try {
                long at = Long.parseLong(fields[0]);
                int units = Integer.parseInt(fields[1]);
                if (at >= 0 && units > 0)
                    charges.add(new Charge(at, units,
                            (fields[2].isEmpty() ? null : fields[2])));
            } catch (NumberFormatException ignored) {
                // Discard a malformed preference entry and keep valid recent charges.
            }
        }
    }

    /** Plan and reserve atomically so simultaneous Gmail accounts for one user share the cap. */
    synchronized PassPlan beginPass(long nowMs, int pendingFolderRequests,
                                    long quotaBackoffUntil, long nextPollAt) {
        return beginPass(nowMs, pendingFolderRequests, false, quotaBackoffUntil,
                0, nextPollAt, PASS_BASE_RESERVE_UNITS);
    }

    synchronized PassPlan beginPass(long nowMs, int pendingFolderRequests,
                                    long quotaBackoffUntil, long nextPollAt,
                                    int baseReserveUnits) {
        return beginPass(nowMs, pendingFolderRequests, false, quotaBackoffUntil,
                0, nextPollAt, baseReserveUnits);
    }

    synchronized PassPlan beginPass(long nowMs, int pendingFolderRequests,
                                    boolean pendingPriorityWork, long quotaBackoffUntil,
                                    long nextPollAt, int baseReserveUnits) {
        return beginPass(nowMs, pendingFolderRequests, pendingPriorityWork,
                quotaBackoffUntil, 0, nextPollAt, baseReserveUnits);
    }

    synchronized PassPlan beginPass(long nowMs, int pendingFolderRequests,
                                    boolean pendingPriorityWork, long quotaBackoffUntil,
                                    long retryBackoffUntil, long nextPollAt,
                                    int baseReserveUnits) {
        if (baseReserveUnits < 0)
            throw new IllegalArgumentException("Negative Gmail pass quota reserve");
        prune(nowMs);
        int pending = Math.max(0, pendingFolderRequests);
        int minimumUnits = baseReserveUnits +
                (pending > 0 ? FOLDER_PAGE_RESERVE_UNITS : 0);
        long allowedAt = Math.max(nowMs, Math.max(quotaBackoffUntil, retryBackoffUntil));
        if (pending == 0 && !pendingPriorityWork)
            allowedAt = Math.max(allowedAt, nextPollAt);
        allowedAt = Math.max(allowedAt, nextAvailableAt(nowMs, minimumUnits));
        if (allowedAt > nowMs)
            return new PassPlan(allowedAt, 0, 0, null);

        int available = MAX_SYNC_UNITS_PER_WINDOW - usedUnits();
        int pageLimit = (pending == 0 ? 0 : Math.min(pending,
                Math.max(0, (available - baseReserveUnits) /
                        FOLDER_PAGE_RESERVE_UNITS)));
        int reservedUnits = baseReserveUnits +
                pageLimit * FOLDER_PAGE_RESERVE_UNITS;
        String reservationId = UUID.randomUUID().toString();
        charges.add(new Charge(nowMs, reservedUnits, reservationId));
        return new PassPlan(nowMs, pageLimit, reservedUnits, reservationId);
    }

    /** Record one actual Gmail REST request attempt, including failed responses and 401 replays. */
    synchronized void recordRequest(long atMs, int quotaUnits) {
        if (quotaUnits <= 0)
            return;
        prune(atMs);
        charges.add(new Charge(atMs, quotaUnits, null));
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
            result.append(charge.atMs).append(',').append(charge.units).append(',');
            if (charge.reservationId != null)
                result.append(charge.reservationId);
        }
        return result.toString();
    }

    private long nextAvailableAt(long nowMs, int requestedUnits) {
        if (requestedUnits > MAX_SYNC_UNITS_PER_WINDOW)
            return Long.MAX_VALUE;
        long remaining = usedUnits();
        if (remaining + requestedUnits <= MAX_SYNC_UNITS_PER_WINDOW)
            return nowMs;

        List<Charge> ordered = new ArrayList<>(charges);
        Collections.sort(ordered, new Comparator<Charge>() {
            @Override
            public int compare(Charge left, Charge right) {
                return Long.compare(left.atMs, right.atMs);
            }
        });
        for (Charge charge : ordered) {
            remaining -= charge.units;
            if (remaining + requestedUnits <= MAX_SYNC_UNITS_PER_WINDOW)
                return Math.max(nowMs, saturatedAdd(charge.atMs, WINDOW_MS));
        }
        return (ordered.isEmpty() ? nowMs :
                Math.max(nowMs, saturatedAdd(ordered.get(ordered.size() - 1).atMs, WINDOW_MS)));
    }

    private int usedUnits() {
        long total = 0;
        for (Charge charge : charges)
            total += charge.units;
        return (int) Math.min(Integer.MAX_VALUE, total);
    }

    private void prune(long nowMs) {
        for (Iterator<Charge> iterator = charges.iterator(); iterator.hasNext(); ) {
            Charge charge = iterator.next();
            if (nowMs >= saturatedAdd(charge.atMs, WINDOW_MS))
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
        final int units;
        final String reservationId;

        Charge(long atMs, int units, String reservationId) {
            this.atMs = atMs;
            this.units = units;
            this.reservationId = reservationId;
        }
    }

    static final class PassPlan {
        final long allowedAt;
        final int folderPageLimit;
        final int reservedUnits;
        final String reservationId;

        PassPlan(long allowedAt, int folderPageLimit, int reservedUnits, String reservationId) {
            this.allowedAt = allowedAt;
            this.folderPageLimit = folderPageLimit;
            this.reservedUnits = reservedUnits;
            this.reservationId = reservationId;
        }

        boolean isReserved() {
            return reservationId != null;
        }
    }
}

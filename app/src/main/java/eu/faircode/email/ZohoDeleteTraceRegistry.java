package eu.faircode.email;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Correlates a successful delete with a later folder-page reappearance without logging provider IDs. */
final class ZohoDeleteTraceRegistry {
    private static final long MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L;
    private static final int MAX_ENTRIES = 256;
    private static final LinkedHashMap<String, Entry> PENDING = new LinkedHashMap<>();

    private static final class Entry {
        final String traceId;
        final long created;

        Entry(String traceId, long created) {
            this.traceId = traceId;
            this.created = created;
        }
    }

    private ZohoDeleteTraceRegistry() {
    }

    static synchronized void record(long accountId, String folderId, String messageId, String traceId) {
        String trace = ApiMutationLog.safeTraceId(traceId);
        String key = key(accountId, folderId, messageId);
        if (trace == null || key == null)
            return;
        prune(System.currentTimeMillis());
        PENDING.put(key, new Entry(trace, System.currentTimeMillis()));
        while (PENDING.size() > MAX_ENTRIES) {
            Iterator<Map.Entry<String, Entry>> iterator = PENDING.entrySet().iterator();
            if (!iterator.hasNext())
                break;
            iterator.next();
            iterator.remove();
        }
    }

    static synchronized String take(long accountId, String folderId, String messageId) {
        String key = key(accountId, folderId, messageId);
        if (key == null)
            return null;
        prune(System.currentTimeMillis());
        Entry entry = PENDING.remove(key);
        return (entry == null ? null : entry.traceId);
    }

    private static String key(long accountId, String folderId, String messageId) {
        if (accountId <= 0 || !ZohoApi.isValidRemoteId(folderId) ||
                !ZohoApi.isValidRemoteId(messageId))
            return null;
        // This private in-memory key is never rendered in diagnostics.
        return accountId + "\u0000" + folderId + "\u0000" + messageId;
    }

    private static void prune(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = PENDING.entrySet().iterator();
        while (iterator.hasNext())
            if (now - iterator.next().getValue().created > MAX_AGE_MILLIS)
                iterator.remove();
    }
}

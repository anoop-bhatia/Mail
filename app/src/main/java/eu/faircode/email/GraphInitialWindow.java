package eu.faircode.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Keeps only the newest unique Graph references while bootstrap pages establish a delta cursor. */
final class GraphInitialWindow {
    private final int limit;
    private final Map<String, GraphMailApi.MailMessage> messages = new HashMap<>();

    GraphInitialWindow(int limit) {
        if (limit < 0)
            throw new IllegalArgumentException("Negative Graph initial window size");
        this.limit = limit;
    }

    void addAll(List<GraphMailApi.MailMessage> references) {
        if (references == null)
            return;
        for (GraphMailApi.MailMessage reference : references) {
            if (reference == null || reference.id == null || reference.id.length() == 0)
                continue;
            if (reference.removed)
                messages.remove(reference.id);
            else
                messages.put(reference.id, reference);
        }
        trim();
    }

    List<GraphMailApi.MailMessage> newestFirst() {
        List<GraphMailApi.MailMessage> result = new ArrayList<>(messages.values());
        Collections.sort(result, NEWEST_FIRST);
        return result;
    }

    private void trim() {
        if (messages.size() <= limit)
            return;
        List<GraphMailApi.MailMessage> sorted = newestFirst();
        messages.clear();
        for (int i = 0; i < limit; i++) {
            GraphMailApi.MailMessage message = sorted.get(i);
            messages.put(message.id, message);
        }
    }

    private static final Comparator<GraphMailApi.MailMessage> NEWEST_FIRST =
            new Comparator<GraphMailApi.MailMessage>() {
                @Override
                public int compare(GraphMailApi.MailMessage left, GraphMailApi.MailMessage right) {
                    int received = Long.compare(right.received, left.received);
                    if (received != 0)
                        return received;
                    int sent = Long.compare(right.sent, left.sent);
                    if (sent != 0)
                        return sent;
                    return left.id.compareTo(right.id);
                }
            };
}

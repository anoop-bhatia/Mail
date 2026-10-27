package eu.faircode.email;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Collect Gmail writes inside a Room transaction and dispatch their durable log/wake after commit. */
final class GmailMutationWakeups {
    interface Callback {
        void onDurable(long accountId, String operation);

        default void onDurable(long accountId, String operation, String attemptToken) {
            onDurable(accountId, operation);
        }

        void onWake(long accountId);
    }

    private static final class Queued {
        final String operation;
        final String attemptToken;

        Queued(String operation, String attemptToken) {
            this.operation = operation;
            this.attemptToken = attemptToken;
        }
    }

    private final Map<Long, List<Queued>> queued = new LinkedHashMap<>();

    void queued(long accountId, String operation) {
        queued(accountId, operation, null);
    }

    void queued(long accountId, String operation, String attemptToken) {
        if (accountId <= 0 || operation == null)
            return;
        List<Queued> operations = queued.get(accountId);
        if (operations == null) {
            operations = new ArrayList<>();
            queued.put(accountId, operations);
        }
        operations.add(new Queued(operation, GmailMutationLog.safeAttemptToken(attemptToken)));
    }

    boolean isEmpty() {
        return queued.isEmpty();
    }

    /** Call only after the transaction containing all recorded operation rows has ended successfully. */
    void dispatchAfterCommit(Callback callback) {
        if (callback == null || queued.isEmpty())
            return;
        for (Map.Entry<Long, List<Queued>> entry : queued.entrySet())
            for (Queued item : entry.getValue())
                callback.onDurable(entry.getKey(), item.operation, item.attemptToken);
        for (Long accountId : queued.keySet())
            callback.onWake(accountId);
        queued.clear();
    }
}

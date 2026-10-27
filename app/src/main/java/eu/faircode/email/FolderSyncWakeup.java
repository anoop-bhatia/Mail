package eu.faircode.email;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Executor;

/** Dispatches read-only folder-request checks away from service callbacks. */
final class FolderSyncWakeup {
    private FolderSyncWakeup() {
    }

    interface Listener {
        boolean hasRequestedFolderSync(long accountId) throws Exception;

        void wake(long accountId);

        void onError(long accountId, Throwable ex);
    }

    static void enqueue(Executor executor, Collection<Long> accountIds, Listener listener) {
        // Snapshot the in-memory account IDs now; database work is exclusively in the task.
        final List<Long> ids = new ArrayList<>(accountIds);
        if (ids.isEmpty())
            return;

        executor.execute(new Runnable() {
            @Override
            public void run() {
                for (Long id : ids)
                    try {
                        if (listener.hasRequestedFolderSync(id))
                            listener.wake(id);
                    } catch (Throwable ex) {
                        listener.onError(id, ex);
                    }
            }
        });
    }
}

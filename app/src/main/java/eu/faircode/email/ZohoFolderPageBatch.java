package eu.faircode.email;

import android.os.OperationCanceledException;
import org.json.JSONException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.mail.MessagingException;

/** Selects and drains one bounded, duplicate-free batch of requested Zoho folder pages. */
final class ZohoFolderPageBatch {
    // Gmail's documented quota planner can admit 27–28 pages per minute. Zoho has no
    // equivalent published budget here, so cap user-requested REST page calls at ten/pass.
    // Exhausted pages make no request and are drained outside that quota cap.
    static final int MAX_PAGES_PER_PASS = 10;

    // Resume promptly, but retain an interruptible positive yield between bounded passes.
    static final long CONTINUATION_YIELD_MS = 1_000L;

    static long successfulPassWaitMs(boolean morePending, long pollDelayMs) {
        return (morePending ? CONTINUATION_YIELD_MS : pollDelayMs);
    }

    private ZohoFolderPageBatch() {
    }

    static final class Request {
        final long operationId;
        final long folderId;
        final boolean requiresApiCall;

        Request(long operationId, long folderId) {
            this(operationId, folderId, true);
        }

        Request(long operationId, long folderId, boolean requiresApiCall) {
            this.operationId = operationId;
            this.folderId = folderId;
            this.requiresApiCall = requiresApiCall;
        }
    }

    static final class Selection {
        final List<Request> requests = new ArrayList<>();
        final List<Request> duplicates = new ArrayList<>();
    }

    interface Processor {
        /** Returns false when processing should stop; the current request stays queued. */
        boolean process(Request request) throws IOException, JSONException, MessagingException,
                OperationCanceledException;
    }

    static Selection select(List<Request> pending, int pageLimit) {
        Selection selection = new Selection();
        if (pending == null || pageLimit <= 0)
            return selection;

        Set<Long> selectedFolders = new HashSet<>();
        int remotePages = 0;
        for (Request request : pending) {
            if (request == null)
                continue;
            if (selectedFolders.contains(request.folderId)) {
                selection.duplicates.add(request);
                continue;
            }
            if (request.requiresApiCall && remotePages >= pageLimit)
                continue;
            selectedFolders.add(request.folderId);
            selection.requests.add(request);
            if (request.requiresApiCall)
                remotePages++;
        }
        return selection;
    }

    static int drain(List<Request> requests, Processor processor)
            throws IOException, JSONException, MessagingException, OperationCanceledException {
        int processed = 0;
        if (requests == null)
            return processed;
        for (Request request : requests) {
            if (!processor.process(request))
                break;
            processed++;
        }
        return processed;
    }
}

package in.anoopbhatia.mail;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/**
 * Bounded in-client replay for idempotent requests.
 *
 * <p>A GET that loses its socket has no side effect to reason about, so replaying it inside the
 * pass is cheaper than failing the pass and waiting out the pass-level transport backoff. Only
 * the transport itself is retried: a provider response, even a failed one, is never replayed here,
 * and mutations, deletes and sends are excluded because their unknown outcome has to reach the
 * recovery path that reconciles with the server instead.</p>
 */
final class TransientGetRetry {
    static final int MAX_REPLAYS = 1;
    static final long FIRST_DELAY_MS = 250L;

    private TransientGetRetry() {
    }

    static boolean shouldReplay(String method, int replays, Throwable error) {
        if (replays >= MAX_REPLAYS)
            return false;
        if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))
            return false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            // A provider response proves the transport worked; only socket-level loss is replayable.
            if (cause instanceof GmailApi.ApiException ||
                    cause instanceof GraphMailApi.ApiException ||
                    cause instanceof ZohoApi.ApiException)
                return false;
            if (cause instanceof SocketTimeoutException ||
                    cause instanceof ConnectException ||
                    cause instanceof NoRouteToHostException ||
                    cause instanceof UnknownHostException ||
                    cause instanceof EOFException ||
                    cause instanceof SocketException)
                return true;
        }
        return false;
    }

    /** Returns false when the worker was interrupted, so the caller rethrows the original error. */
    static boolean pause(int replays) {
        try {
            Thread.sleep(FIRST_DELAY_MS << Math.max(0, Math.min(2, replays)));
            return true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

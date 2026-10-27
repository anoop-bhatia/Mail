package eu.faircode.email;

import org.json.JSONException;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Timeout policy for Zoho REST attachment uploads only. */
final class ZohoUploadTimeouts {
    static final int CONNECT_TIMEOUT_MILLIS = 20_000;
    static final int READ_TIMEOUT_MILLIS = 60_000;
    static final long MIN_UPLINK_BYTES_PER_SECOND = 64L * 1024L;
    static final long BASE_UPLOAD_MILLIS = 90_000L;
    static final long MAX_OVERALL_UPLOAD_MILLIS = 10L * 60L * 1000L;
    private static final long MONITOR_POLL_MILLIS = 250L;

    interface Diagnostic {
        void onUpdate(String stage, long elapsedMillis, long bytesSent);
    }

    interface Progress {
        void stage(String stage);

        void bytesSent(long totalBytes);
    }

    interface Operation<T> {
        T run(Progress progress) throws IOException, JSONException;
    }

    interface AbortAction {
        void abort();
    }

    private static final class Completion<T> {
        final T value;
        final long completedAtNanos;

        Completion(T value) {
            this.value = value;
            this.completedAtNanos = System.nanoTime();
        }
    }

    private static final class State implements Progress {
        private final long startedAtNanos;
        private final Diagnostic diagnostic;
        private volatile long lastProgressNanos;
        private volatile long bytesSent;
        private volatile String stage = "starting";

        State(long startedAtNanos, Diagnostic diagnostic) {
            this.startedAtNanos = startedAtNanos;
            this.lastProgressNanos = startedAtNanos;
            this.diagnostic = diagnostic;
            notifyDiagnostic();
        }

        @Override
        public void stage(String value) {
            stage = value;
            lastProgressNanos = System.nanoTime();
            notifyDiagnostic();
        }

        @Override
        public void bytesSent(long value) {
            bytesSent = value;
            lastProgressNanos = System.nanoTime();
            notifyDiagnostic();
        }

        long idleNanos(long nowNanos) {
            return Math.max(0L, nowNanos - lastProgressNanos);
        }

        void notifyDiagnostic() {
            if (diagnostic != null) {
                long elapsed = TimeUnit.NANOSECONDS.toMillis(
                        Math.max(0L, System.nanoTime() - startedAtNanos));
                try {
                    diagnostic.onUpdate(stage, elapsed, bytesSent);
                } catch (RuntimeException ignored) {
                    // A diagnostic failure must never break an attachment upload.
                }
            }
        }
    }

    private ZohoUploadTimeouts() {
    }

    /**
     * Computes a transfer allowance using a 90-second setup/response floor and a
     * conservative 64 KiB/s minimum uplink assumption, with a firm ten-minute cap.
     */
    static long budgetMillis(long byteCount) {
        long bytes = Math.max(0L, byteCount);
        long transferMillis;
        if (bytes == 0L)
            transferMillis = 0L;
        else if (bytes > Long.MAX_VALUE / 1000L)
            transferMillis = Long.MAX_VALUE;
        else {
            long numerator = bytes * 1000L;
            transferMillis = numerator / MIN_UPLINK_BYTES_PER_SECOND;
            if (numerator % MIN_UPLINK_BYTES_PER_SECOND != 0L)
                transferMillis++;
        }
        if (transferMillis >= MAX_OVERALL_UPLOAD_MILLIS - BASE_UPLOAD_MILLIS)
            return MAX_OVERALL_UPLOAD_MILLIS;
        return BASE_UPLOAD_MILLIS + transferMillis;
    }

    /**
     * Runs one upload away from its caller so a blocked stream cannot hold the send
     * path past the overall budget. On cancellation or timeout, disconnects the
     * active transport via {@code abortAction} and interrupts the upload worker.
     */
    static <T> T execute(Operation<T> operation, AbortAction abortAction,
                         long overallTimeoutMillis, long noProgressTimeoutMillis,
                         Diagnostic diagnostic) throws IOException, JSONException {
        if (overallTimeoutMillis <= 0L || noProgressTimeoutMillis <= 0L)
            throw new IllegalArgumentException("Upload timeouts must be positive");

        final long startNanos = System.nanoTime();
        final long overallNanos = TimeUnit.MILLISECONDS.toNanos(overallTimeoutMillis);
        final long noProgressNanos = TimeUnit.MILLISECONDS.toNanos(noProgressTimeoutMillis);
        final State state = new State(startNanos, diagnostic);
        final FutureTask<Completion<T>> task = new FutureTask<>(
                () -> new Completion<>(operation.run(state)));
        Thread worker = new Thread(task, "Zoho attachment upload");
        worker.setDaemon(true);
        worker.start();

        while (true) {
            if (task.isDone())
                return getResult(task, state, startNanos, overallNanos, noProgressNanos, abortAction);

            long now = System.nanoTime();
            long elapsed = Math.max(0L, now - startNanos);
            long remainingOverall = overallNanos - elapsed;
            long remainingNoProgress = noProgressNanos - state.idleNanos(now);
            if (remainingOverall <= 0L || remainingNoProgress <= 0L) {
                if (task.isDone())
                    return getResult(task, state, startNanos, overallNanos,
                            noProgressNanos, abortAction);
                boolean overall = (remainingOverall <= 0L);
                timeout(state, task, abortAction, overall);
            }

            long waitNanos = Math.min(remainingOverall, remainingNoProgress);
            waitNanos = Math.min(waitNanos, TimeUnit.MILLISECONDS.toNanos(MONITOR_POLL_MILLIS));
            try {
                Completion<T> completion = task.get(Math.max(1L, waitNanos), TimeUnit.NANOSECONDS);
                return finish(completion, state, startNanos, overallNanos,
                        noProgressNanos, task, abortAction);
            } catch (TimeoutException ignored) {
                // Re-evaluate both the no-progress interval and the absolute deadline.
            } catch (InterruptedException ex) {
                state.stage("cancelled");
                abort(abortAction);
                task.cancel(true);
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException(
                        "Zoho attachment upload was cancelled");
                interrupted.initCause(ex);
                throw interrupted;
            } catch (ExecutionException ex) {
                return rethrow(ex.getCause());
            } catch (CancellationException ex) {
                throw new IOException("Zoho attachment upload was cancelled", ex);
            }
        }
    }

    private static <T> T getResult(FutureTask<Completion<T>> task, State state,
                                   long startNanos, long overallNanos, long noProgressNanos,
                                   AbortAction abortAction) throws IOException, JSONException {
        try {
            return finish(task.get(), state, startNanos, overallNanos,
                    noProgressNanos, task, abortAction);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            InterruptedIOException interrupted = new InterruptedIOException(
                    "Zoho attachment upload was cancelled");
            interrupted.initCause(ex);
            throw interrupted;
        } catch (ExecutionException ex) {
            return rethrow(ex.getCause());
        } catch (CancellationException ex) {
            throw new IOException("Zoho attachment upload was cancelled", ex);
        }
    }

    private static <T> T finish(Completion<T> completion, State state, long startNanos,
                                long overallNanos, long noProgressNanos,
                                FutureTask<?> task, AbortAction abortAction)
            throws SocketTimeoutException {
        long elapsedNanos = Math.max(0L, completion.completedAtNanos - startNanos);
        long idleNanos = state.idleNanos(completion.completedAtNanos);
        if (elapsedNanos >= overallNanos)
            timeout(state, task, abortAction, true);
        if (idleNanos >= noProgressNanos)
            timeout(state, task, abortAction, false);
        state.stage("complete");
        return completion.value;
    }

    private static void timeout(State state, FutureTask<?> task,
                                AbortAction abortAction, boolean overall)
            throws SocketTimeoutException {
        state.stage(overall ? "timeout_overall" : "timeout_no_progress");
        abort(abortAction);
        task.cancel(true);
        String message = (overall
                ? "Zoho attachment upload exceeded its overall deadline"
                : "Zoho attachment upload made no progress");
        throw new SocketTimeoutException(message);
    }

    private static void abort(AbortAction action) {
        try {
            action.abort();
        } catch (RuntimeException ignored) {
            // Cancellation must still interrupt the worker if disconnect fails.
        }
    }

    private static <T> T rethrow(Throwable cause) throws IOException, JSONException {
        if (cause instanceof IOException)
            throw (IOException) cause;
        if (cause instanceof JSONException)
            throw (JSONException) cause;
        if (cause instanceof RuntimeException)
            throw (RuntimeException) cause;
        if (cause instanceof Error)
            throw (Error) cause;
        throw new IOException("Zoho attachment upload failed", cause);
    }
}

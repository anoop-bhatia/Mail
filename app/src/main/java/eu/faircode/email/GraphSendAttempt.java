package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import org.json.JSONException;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.mail.MessagingException;
import javax.net.ssl.HttpsURLConnection;

/** Bounds a Graph send request without occupying the shared send-queue worker indefinitely. */
final class GraphSendAttempt {
    enum Phase {
        CONNECTING,
        PREPARING_DRAFT,
        UPLOADING_ATTACHMENTS,
        UPLOADING,
        WAITING_RESPONSE,
        ACCEPTED
    }

    static final class Progress {
        private volatile Phase phase = Phase.CONNECTING;
        private volatile int status = -1;
        private volatile HttpsURLConnection connection;
        private volatile boolean cancelled;

        void setConnection(HttpsURLConnection connection) {
            this.connection = connection;
        }

        void setPhase(Phase phase) {
            this.phase = phase;
        }

        void setStatus(int status) {
            this.status = status;
        }

        Phase getPhase() {
            return phase;
        }

        int getStatus() {
            return status;
        }

        boolean isAccepted() {
            return phase == Phase.ACCEPTED;
        }

        boolean isCancelled() {
            return cancelled;
        }

        boolean isOutcomeUnknown() {
            return (phase == Phase.UPLOADING || phase == Phase.WAITING_RESPONSE);
        }

        void cancel() {
            cancelled = true;
            disconnect();
        }

        void disconnect() {
            HttpsURLConnection current = connection;
            if (current != null)
                current.disconnect();
        }
    }

    static final class SendTimeoutException extends IOException {
        final Phase phase;
        final int status;
        final boolean outcomeUnknown;
        final long elapsedMillis;

        SendTimeoutException(Phase phase, int status, boolean outcomeUnknown,
                             long elapsedMillis, String reason) {
            super("Microsoft Graph send " + reason + " stage=" + phase.name().toLowerCase(Locale.ROOT) +
                    (status < 0 ? "" : " status=" + status));
            this.phase = phase;
            this.status = status;
            this.outcomeUnknown = outcomeUnknown;
            this.elapsedMillis = elapsedMillis;
        }
    }

    private GraphSendAttempt() {
    }

    static boolean isOutcomeUnknown(Throwable ex) {
        return (ex instanceof SendTimeoutException &&
                ((SendTimeoutException) ex).outcomeUnknown);
    }

    static <T> boolean continueQueueAfterFailure(java.util.List<T> operations, T operation, Throwable ex) {
        if (!isOutcomeUnknown(ex))
            return false;
        for (int index = 0; index < operations.size(); index++)
            if (operations.get(index) == operation) {
                operations.remove(index);
                break;
            }
        return true;
    }

    static void execute(Callable<Void> request, long timeoutMillis, Progress progress)
            throws IOException, JSONException, MessagingException {
        long started = System.currentTimeMillis();
        FutureTask<Void> task = new FutureTask<>(request);
        Thread worker = new Thread(task, "FairEmail_graph_send");
        worker.setDaemon(true);
        worker.start();

        try {
            task.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            cancel(task, progress);
            if (progress.isAccepted())
                return;
            throw new SendTimeoutException(progress.getPhase(), progress.getStatus(),
                    progress.isOutcomeUnknown(), System.currentTimeMillis() - started,
                    "timed out limit=" + timeoutMillis + "ms elapsed=" +
                            (System.currentTimeMillis() - started) + "ms");
        } catch (InterruptedException ex) {
            cancel(task, progress);
            if (progress.isAccepted()) {
                Thread.currentThread().interrupt();
                return;
            }
            Thread.currentThread().interrupt();
            throw new SendTimeoutException(progress.getPhase(), progress.getStatus(),
                    progress.isOutcomeUnknown(), System.currentTimeMillis() - started,
                    "interrupted elapsed=" + (System.currentTimeMillis() - started) + "ms");
        } catch (ExecutionException ex) {
            if (progress.isAccepted())
                return;

            Throwable cause = ex.getCause();
            if (progress.isOutcomeUnknown() && progress.getStatus() < 0)
                throw new SendTimeoutException(progress.getPhase(), progress.getStatus(), true,
                        System.currentTimeMillis() - started, "failed without an HTTP response");
            if (cause instanceof IOException)
                throw (IOException) cause;
            if (cause instanceof JSONException)
                throw (JSONException) cause;
            if (cause instanceof MessagingException)
                throw (MessagingException) cause;
            if (cause instanceof RuntimeException)
                throw (RuntimeException) cause;
            if (cause instanceof Error)
                throw (Error) cause;
            throw new IOException("Microsoft Graph send failed", cause);
        }
    }

    private static void cancel(FutureTask<Void> task, Progress progress) {
        progress.cancel();
        task.cancel(true);
    }
}

package eu.faircode.email;

import android.content.Context;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

/** Shared offline handling for native REST account monitors. */
final class ApiNetworkHibernation {
    enum Transition {
        NONE,
        HIBERNATE,
        RESUME
    }

    enum FailureAction {
        NONE,
        HIBERNATE,
        RETRY
    }

    private ApiNetworkHibernation() {
    }

    /**
     * The service scheduler already stops a monitor when canRun changes to false and starts a
     * fresh monitor when it changes back to true. Keep that lifecycle for native REST accounts,
     * while naming the network-only transition so it can be tested and logged explicitly.
     */
    static Transition getTransition(boolean nativeRestApi, boolean shouldRun,
                                     boolean previousCanRun, boolean currentCanRun) {
        if (!nativeRestApi || !shouldRun || previousCanRun == currentCanRun)
            return Transition.NONE;
        return (currentCanRun ? Transition.RESUME : Transition.HIBERNATE);
    }

    static boolean shouldWakeOnNetworkChange(boolean nativeRestApi, boolean shouldRun,
                                              boolean wasSuitable, boolean isSuitable,
                                              boolean activeNetworkChanged) {
        return (nativeRestApi && shouldRun && wasSuitable && isSuitable && activeNetworkChanged);
    }

    static boolean shouldUseLegacyNetworkChangeCancellation(boolean nativeRestApi) {
        return !nativeRestApi;
    }

    static long getTransportRetryDelayMs(int consecutiveFailures) {
        int exponent = Math.min(Math.max(0, consecutiveFailures - 1), 4);
        return Math.min(60_000L << exponent, 15 * 60_000L);
    }

    static boolean isNetworkSuitable(Core.State state) {
        ConnectionHelper.NetworkState network = (state == null ? null : state.getNetworkState());
        return (network != null && network.isSuitable());
    }

    private static boolean isNetworkSuitable(Context context, Core.State state) {
        if (!isNetworkSuitable(state))
            return false;
        ConnectionHelper.NetworkState current = ConnectionHelper.getNetworkState(context);
        return (current != null && current.isSuitable());
    }

    static boolean shouldHibernateNetworkFailure(boolean networkSuitable, Throwable error) {
        return (getTransportFailureAction(networkSuitable, error) == FailureAction.HIBERNATE);
    }

    static FailureAction getTransportFailureAction(boolean networkSuitable, Throwable error) {
        if (!isTransportFailure(error))
            return FailureAction.NONE;
        return (networkSuitable ? FailureAction.RETRY : FailureAction.HIBERNATE);
    }

    static boolean isRetryableApiFailure(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof GraphMailApi.ApiException)
                return ((GraphMailApi.ApiException) cause).isRateLimitFailure();
            if (cause instanceof GmailApi.ApiException)
                return ((GmailApi.ApiException) cause).isRateLimitFailure();
            if (cause instanceof ZohoApi.ApiException)
                return ((ZohoApi.ApiException) cause).isRateLimitFailure();
        }
        return false;
    }

    static boolean isTransportFailure(Throwable error) {
        boolean transportFailure = false;
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            // These represent an HTTP/application response, not loss of transport. In particular,
            // callers must retain their existing 429/503 Retry-After handling.
            if (cause instanceof GraphMailApi.ApiException ||
                    cause instanceof GmailApi.ApiException ||
                    cause instanceof ZohoApi.ApiException)
                return false;

            if (cause instanceof UnknownHostException ||
                    cause instanceof ConnectException ||
                    cause instanceof NoRouteToHostException ||
                    cause instanceof SocketException ||
                    cause instanceof SocketTimeoutException ||
                    cause instanceof EOFException)
                transportFailure = true;
        }
        return transportFailure;
    }

    /** Return true before a REST pass when the Android connectivity observer reports no suitable network. */
    static boolean parkIfOffline(Context context, DB db, EntityAccount account, Core.State state) {
        if (isNetworkSuitable(context, state))
            return false;

        clearWorkerState(db, account, false);
        logHibernating(context);
        return true;
    }

    /** Suppress transport-only account errors when the current network is unsuitable. */
    static boolean hibernateOnTransportFailure(Context context, DB db,
                                               EntityAccount account, Core.State state,
                                               Throwable error) {
        boolean networkSuitable = isNetworkSuitable(context, state);
        if (!shouldHibernateNetworkFailure(networkSuitable, error))
            return false;

        clearWorkerState(db, account, true);
        logHibernating(context);
        return true;
    }

    /** Clear visible state for an already classified transient native REST failure only. */
    static void clearTransientWorkerState(DB db, EntityAccount account) {
        clearWorkerState(db, account, true);
    }

    private static void clearWorkerState(DB db, EntityAccount account, boolean clearErrors) {
        db.account().setAccountState(account.id, null);
        if (clearErrors)
            db.account().setAccountError(account.id, null);

        for (EntityFolder folder : db.folder().getFolders(account.id, false, false)) {
            boolean connecting = "connecting".equals(folder.state);
            db.folder().setFolderState(folder.id, null);
            if (clearErrors && connecting)
                db.folder().setFolderError(folder.id, null);
        }

        EntityFolder inbox = db.folder().getFolderByType(account.id, EntityFolder.INBOX);
        if (inbox != null) {
            db.folder().setFolderState(inbox.id, null);
            if (clearErrors)
                db.folder().setFolderError(inbox.id, null);
        }
    }

    private static void logHibernating(Context context) {
        EntityLog.log(context, EntityLog.Type.Network,
                "Native REST monitor hibernating while network is unsuitable; durable operations retained");
    }
}

package eu.faircode.email;

/** Event-driven wakeups for queued native Microsoft Graph write operations. */
final class GraphOperationWakeup {
    private GraphOperationWakeup() {
    }

    static boolean isGraphWriteOperation(String name) {
        return EntityOperation.isGraphApiWriteOperation(name);
    }

    static boolean shouldWake(boolean graphAccount, int previousCount, int currentCount) {
        return (graphAccount && currentCount > previousCount);
    }

    static boolean wake(Core.State state) {
        return (state != null && state.releaseUnlessBackingOff());
    }
}

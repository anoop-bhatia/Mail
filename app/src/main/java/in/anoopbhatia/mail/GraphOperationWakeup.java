package in.anoopbhatia.mail;

import java.util.List;

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

    static boolean hasPendingWrite(List<EntityOperation> operations) {
        if (operations != null)
            for (EntityOperation operation : operations)
                if (operation != null && operation.error == null && operation.state == null &&
                        isGraphWriteOperation(operation.name))
                    return true;
        return false;
    }

    static boolean wake(Core.State state) {
        return (state != null && state.releaseUnlessBackingOff());
    }
}

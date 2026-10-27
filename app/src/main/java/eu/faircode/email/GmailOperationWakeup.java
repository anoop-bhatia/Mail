package eu.faircode.email;

import java.util.List;

/** Event-driven scheduling helpers for native Gmail REST writes. */
final class GmailOperationWakeup {
    private GmailOperationWakeup() {
    }

    static boolean isPendingWrite(EntityOperation operation) {
        return (operation != null && operation.error == null &&
                EntityOperation.isGmailApiWriteOperation(operation.name));
    }

    static boolean hasPendingWrite(List<EntityOperation> operations) {
        if (operations == null)
            return false;
        for (EntityOperation operation : operations)
            if (isPendingWrite(operation))
                return true;
        return false;
    }

    static boolean hasWork(boolean hasRequestedFolderSync, boolean hasPendingWrite) {
        return hasWork(hasRequestedFolderSync, hasPendingWrite, false);
    }

    static boolean hasWork(boolean hasRequestedFolderSync, boolean hasPendingWrite,
                           boolean hasPendingContentOperation) {
        return (hasRequestedFolderSync || hasPendingWrite || hasPendingContentOperation);
    }

    static boolean shouldWake(boolean gmailApiAccount, boolean hasRequestedFolderSync,
                              boolean hasPendingWrite) {
        return shouldWake(gmailApiAccount, hasRequestedFolderSync, hasPendingWrite, false);
    }

    static boolean shouldWake(boolean gmailApiAccount, boolean hasRequestedFolderSync,
                              boolean hasPendingWrite, boolean hasPendingContentOperation) {
        return (gmailApiAccount && hasWork(hasRequestedFolderSync,
                hasPendingWrite, hasPendingContentOperation));
    }

    static boolean wake(Core.State state) {
        return (state != null && state.release());
    }
}

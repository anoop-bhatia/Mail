package eu.faircode.email;

/** Event-driven wakeups for queued native Zoho Mail REST writes. */
final class ZohoOperationWakeup {
    private ZohoOperationWakeup() {
    }

    static boolean isZohoWriteOperation(String name) {
        return (EntityOperation.SEEN.equals(name) || EntityOperation.FLAG.equals(name) ||
                EntityOperation.MOVE.equals(name) || EntityOperation.DELETE.equals(name) ||
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT.equals(name) ||
                EntityOperation.ZOHO_PERMANENT_DELETE.equals(name) ||
                EntityOperation.ZOHO_PURGE_TRASH.equals(name) ||
                EntityOperation.ZOHO_PURGE_SPAM.equals(name));
    }

    static boolean shouldWake(boolean zohoAccount, int previousCount, int currentCount) {
        return (zohoAccount && currentCount > previousCount);
    }

    static boolean shouldWakeForRequestedFolderSync(boolean zohoAccount, boolean hasRequestedSync) {
        return (zohoAccount && hasRequestedSync);
    }

    static boolean wake(Core.State state) {
        return (state != null && state.releaseUnlessBackingOff());
    }

    static String mapFolderOperation(boolean zohoAccount, String folderType, String name) {
        if (!zohoAccount)
            return name;
        if (EntityOperation.EXPUNGE.equals(name))
            return (EntityFolder.TRASH.equals(folderType) ? EntityOperation.ZOHO_PURGE_TRASH : null);
        if (EntityOperation.PURGE.equals(name)) {
            if (EntityFolder.TRASH.equals(folderType))
                return EntityOperation.ZOHO_PURGE_TRASH;
            if (EntityFolder.JUNK.equals(folderType))
                return EntityOperation.ZOHO_PURGE_SPAM;
            return null;
        }
        return name;
    }
}

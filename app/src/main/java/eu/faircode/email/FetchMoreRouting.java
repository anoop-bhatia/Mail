package eu.faircode.email;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Routes Fetch more actions without changing legacy date-period behavior. */
final class FetchMoreRouting {
    static final int NATIVE_PAGE_SIZE = 10;

    static final class Target {
        final long folderId;
        final boolean nativeRest;
        final boolean hasOlderMessages;

        private Target(long folderId, boolean nativeRest, boolean hasOlderMessages) {
            this.folderId = folderId;
            this.nativeRest = nativeRest;
            this.hasOlderMessages = hasOlderMessages;
        }

        static Target create(EntityFolder folder, EntityAccount account) {
            boolean nativeRest = (account != null && account.isNativeRestApi());
            boolean hasOlderMessages = (nativeRest && NativeApiSyncRequest.hasOlderMessages(folder, account));
            return new Target(folder.id, nativeRest, hasOlderMessages);
        }

        static Target create(long folderId, boolean nativeRest, boolean hasOlderMessages) {
            return new Target(folderId, nativeRest, hasOlderMessages);
        }
    }

    interface PageRequester {
        void requestOlderPage(long folderId, int pageSize);
    }

    static final class Result {
        final List<Long> legacyFolderIds;
        final int nativePagesRequested;

        private Result(List<Long> legacyFolderIds, int nativePagesRequested) {
            this.legacyFolderIds = Collections.unmodifiableList(legacyFolderIds);
            this.nativePagesRequested = nativePagesRequested;
        }
    }

    private FetchMoreRouting() {
    }

    static Result dispatch(List<Target> targets, PageRequester requester) {
        ArrayList<Long> legacyFolderIds = new ArrayList<>();
        int nativePagesRequested = 0;
        if (targets != null)
            for (Target target : targets) {
                if (target == null)
                    continue;
                if (!target.nativeRest) {
                    legacyFolderIds.add(target.folderId);
                    continue;
                }
                if (target.hasOlderMessages) {
                    requester.requestOlderPage(target.folderId, NATIVE_PAGE_SIZE);
                    nativePagesRequested++;
                }
            }
        return new Result(legacyFolderIds, nativePagesRequested);
    }

    /** Preserve the existing dialog's month-to-day policy for legacy folders. */
    static Integer getKeepDays(int months) {
        if (months == 0)
            return Integer.MAX_VALUE;
        if (months > 0)
            return months * 30;
        return null;
    }
}

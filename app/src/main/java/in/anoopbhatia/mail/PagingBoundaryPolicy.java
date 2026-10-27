package in.anoopbhatia.mail;

import java.util.Objects;

/** Keeps snapshot scans/selection from requesting remote pages; only UI access can do so. */
final class PagingBoundaryPolicy {
    private int accessed;
    private boolean dispatched;
    private int dispatchedSize;
    private Long dispatchedLast;

    void reset() {
        accessed = 0;
        dispatched = false;
    }

    void access(int position) {
        accessed = position;
    }

    boolean shouldDispatch(int size, int placeholdersAfter, Long lastId, int prefetchDistance) {
        if (placeholdersAfter != 0 || (size > 0 && accessed < size - 1 - prefetchDistance))
            return false;
        if (dispatched && dispatchedSize == size && Objects.equals(dispatchedLast, lastId))
            return false;
        dispatched = true;
        dispatchedSize = size;
        dispatchedLast = lastId;
        return true;
    }
}

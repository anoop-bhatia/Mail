package in.anoopbhatia.mail;

import androidx.paging.PagingConfig;

/** Keep full positional lists and bounded caches, including far scrollbar/navigation jumps. */
final class MessagePagingConfig {
    private static final int LOCAL_PAGE_SIZE = 50;
    private static final int REMOTE_PREFETCH_DISTANCE = 10;
    private static final int THREAD_PAGE_SIZE = 100;
    private static final int MAX_CACHED_ITEMS = LOCAL_PAGE_SIZE * 50;

    static PagingConfig unified() {
        return create(LOCAL_PAGE_SIZE, LOCAL_PAGE_SIZE, LOCAL_PAGE_SIZE * 3, MAX_CACHED_ITEMS);
    }

    static PagingConfig folder() {
        return create(LOCAL_PAGE_SIZE, REMOTE_PREFETCH_DISTANCE, LOCAL_PAGE_SIZE, MAX_CACHED_ITEMS);
    }

    static PagingConfig thread() {
        return create(THREAD_PAGE_SIZE, THREAD_PAGE_SIZE, THREAD_PAGE_SIZE * 3,
                PagingConfig.MAX_SIZE_UNBOUNDED);
    }

    static PagingConfig search() {
        return create(LOCAL_PAGE_SIZE, REMOTE_PREFETCH_DISTANCE, LOCAL_PAGE_SIZE * 3, MAX_CACHED_ITEMS);
    }

    private static PagingConfig create(int pageSize, int prefetchDistance, int initialLoadSize, int maxSize) {
        // Paging 2's positional lists could seek directly to any placeholder. Room's
        // PagingSource supports jumps; enable them instead of sequentially loading
        // thousands of pages after scrolling or navigating to a distant message.
        return new PagingConfig(pageSize, prefetchDistance, true, initialLoadSize, maxSize, pageSize * 3);
    }
}

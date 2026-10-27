package in.anoopbhatia.mail;

import androidx.paging.PagingConfig;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PagingConfigTest {
    @Test
    public void unifiedKeepsOriginalPageAndCacheSizes() {
        assertConfig(MessagePagingConfig.unified(), 50, 50, 150, 2500);
    }

    @Test
    public void folderKeepsShortInitialLoadAndTenItemBoundaryPrefetch() {
        assertConfig(MessagePagingConfig.folder(), 50, 10, 50, 2500);
    }

    @Test
    public void searchKeepsOriginalInitialWindowAndBoundaryPrefetch() {
        assertConfig(MessagePagingConfig.search(), 50, 10, 150, 2500);
    }

    @Test
    public void threadKeepsUnboundedCacheAndOriginalWindow() {
        assertConfig(MessagePagingConfig.thread(), 100, 100, 300, PagingConfig.MAX_SIZE_UNBOUNDED);
    }

    private static void assertConfig(PagingConfig config, int pageSize, int prefetch,
                                     int initial, int maxSize) {
        assertEquals(pageSize, config.pageSize);
        assertEquals(prefetch, config.prefetchDistance);
        assertEquals(initial, config.initialLoadSize);
        assertEquals(maxSize, config.maxSize);
        assertTrue("Unloaded rows retain their positions and the full mailbox count", config.enablePlaceholders);
        assertTrue("Far navigation must reload around the target, not append every intervening page",
                config.jumpThreshold > 0);
    }
}

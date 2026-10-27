package eu.faircode.email;

import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZohoFolderPageBatchTest {
    @Test
    public void pendingBatchUsesPositiveShortYieldAndNormalPollIsUnchanged() {
        long pollDelayMs = 15 * 60 * 1000L;
        long continuationDelayMs = ZohoFolderPageBatch.successfulPassWaitMs(true, pollDelayMs);
        assertEquals(1_000L, continuationDelayMs);
        assertTrue(continuationDelayMs > 0L);
        assertTrue(continuationDelayMs < 60_000L);
        assertEquals(pollDelayMs,
                ZohoFolderPageBatch.successfulPassWaitMs(false, pollDelayMs));
    }

    @Test
    public void tenQueuedFoldersDrainInOnePassAndDuplicateRowsDoNotRepeatFolderWork() throws Exception {
        List<ZohoFolderPageBatch.Request> queued = new ArrayList<>();
        queued.add(new ZohoFolderPageBatch.Request(1, 100));
        queued.add(new ZohoFolderPageBatch.Request(2, 100));
        for (int i = 0; i < 9; i++)
            queued.add(new ZohoFolderPageBatch.Request(3 + i, 101 + i));
        ZohoFolderPageBatch.Selection selection = ZohoFolderPageBatch.select(
                queued, ZohoFolderPageBatch.MAX_PAGES_PER_PASS);

        assertEquals(ZohoFolderPageBatch.MAX_PAGES_PER_PASS, selection.requests.size());
        assertEquals(1, selection.duplicates.size());
        List<Long> processed = new ArrayList<>();
        int drained = ZohoFolderPageBatch.drain(selection.requests, request -> {
            processed.add(request.folderId);
            return true;
        });
        assertEquals(10, drained);
        assertEquals(10, processed.size());
        assertEquals(10, new java.util.HashSet<>(processed).size());
    }

    @Test
    public void selectionLeavesUniqueWorkBeyondConservativeCapForContinuation() {
        List<ZohoFolderPageBatch.Request> queued = new ArrayList<>();
        for (int i = 0; i < ZohoFolderPageBatch.MAX_PAGES_PER_PASS + 2; i++)
            queued.add(new ZohoFolderPageBatch.Request(i + 1, i + 10));
        ZohoFolderPageBatch.Selection selection = ZohoFolderPageBatch.select(
                queued, ZohoFolderPageBatch.MAX_PAGES_PER_PASS);
        assertEquals(10, selection.requests.size());
        assertEquals(0, selection.duplicates.size());
        assertEquals(12, queued.size());
    }

    @Test
    public void exhaustedRowsDoNotConsumeRemotePageCap() {
        List<ZohoFolderPageBatch.Request> queued = new ArrayList<>();
        for (int i = 0; i < ZohoFolderPageBatch.MAX_PAGES_PER_PASS; i++)
            queued.add(new ZohoFolderPageBatch.Request(i + 1, i + 10));
        queued.add(new ZohoFolderPageBatch.Request(11, 30, false));
        queued.add(new ZohoFolderPageBatch.Request(12, 31));

        ZohoFolderPageBatch.Selection selection = ZohoFolderPageBatch.select(
                queued, ZohoFolderPageBatch.MAX_PAGES_PER_PASS);

        assertEquals(11, selection.requests.size());
        assertFalse(selection.requests.get(10).requiresApiCall);
        for (ZohoFolderPageBatch.Request request : selection.requests)
            assertTrue(request.operationId != 12);
    }

    @Test
    public void exhaustedPageSentinelDoesNotNeedRemoteRequest() {
        EntityFolder exhausted = new EntityFolder();
        exhausted.zoho_page_start = ZohoReceiver.PAGE_START_EXHAUSTED;
        assertTrue(ZohoReceiver.isFolderPageExhausted(exhausted));

        EntityFolder active = new EntityFolder();
        active.zoho_page_start = 1;
        assertFalse(ZohoReceiver.isFolderPageExhausted(active));

        EntityFolder fresh = new EntityFolder();
        assertFalse(ZohoReceiver.isFolderPageExhausted(fresh));
    }

    @Test
    public void explicitZohoRefreshIsWakeEligibleWithoutChangingFolderPreference() {
        EntityFolder folder = new EntityFolder("Projects", EntityFolder.USER);
        folder.synchronize = false;
        folder.sync_state = "requested";
        assertTrue(ZohoReceiver.isRequestedFolderSync(folder));
        assertFalse("A one-off refresh must not enable ongoing synchronization", folder.synchronize);

        folder.zoho_page_start = ZohoReceiver.PAGE_START_EXHAUSTED;
        assertTrue("Exhausted-page cleanup still needs the queued request", ZohoReceiver.isRequestedFolderSync(folder));
        folder.sync_state = null;
        assertFalse(ZohoReceiver.isRequestedFolderSync(folder));
        assertFalse(folder.synchronize);
    }

    @Test
    public void failedPageStopsDrainSoItsDurableOperationCanRetry() throws Exception {
        List<ZohoFolderPageBatch.Request> requests = Arrays.asList(
                new ZohoFolderPageBatch.Request(1, 10),
                new ZohoFolderPageBatch.Request(2, 11),
                new ZohoFolderPageBatch.Request(3, 12));
        List<Long> attempted = new ArrayList<>();
        int drained = ZohoFolderPageBatch.drain(requests, request -> {
            attempted.add(request.operationId);
            return request.operationId != 2;
        });
        assertEquals(1, drained);
        assertEquals(Arrays.asList(1L, 2L), attempted);
        assertFalse(attempted.contains(3L));
    }

    @Test
    public void emptyBatchDoesNotCallProcessor() throws Exception {
        assertEquals(0, ZohoFolderPageBatch.drain(Collections.<ZohoFolderPageBatch.Request>emptyList(),
                request -> { throw new AssertionError("unexpected page"); }));
        assertTrue(ZohoFolderPageBatch.select(null, 10).requests.isEmpty());
    }
}

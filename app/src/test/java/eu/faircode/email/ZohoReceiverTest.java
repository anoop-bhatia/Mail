package eu.faircode.email;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZohoReceiverTest {
    @Test
    public void routineFetchUsesOnlyTheNewestBoundedWindow() {
        assertEquals(10, ZohoReceiver.PAGE_SIZE);
        assertEquals(1, ZohoReceiver.ROUTINE_HEAD_START);
        assertTrue(ZohoReceiver.shouldSyncAutomatically(
                new EntityFolder("Sent", EntityFolder.SENT)));

        EntityFolder disabled = new EntityFolder("Archive", EntityFolder.ARCHIVE);
        disabled.synchronize = false;
        assertFalse(ZohoReceiver.shouldSyncAutomatically(disabled));

        EntityFolder noContent = new EntityFolder(EntityFolder.JUNK, EntityFolder.JUNK);
        noContent.synchronize = true;
        noContent.download = false;
        assertTrue("Native REST bodies are imported for sync-enabled folders with download=false",
                ZohoReceiver.shouldSyncAutomatically(noContent));
        noContent.synchronize = false;
        assertFalse("An unselected folder remains excluded",
                ZohoReceiver.shouldSyncAutomatically(noContent));
    }

    @Test
    public void initialWindowSeedsOlderCursorAndExplicitPagesAdvanceWithoutRoutineHeadMovingIt() {
        ZohoApi.Page full = page(10, 10);
        ZohoApi.Page partial = page(9, 10);

        assertEquals(11, ZohoReceiver.initialPageNextStart(1, full));
        assertEquals("A missing manual-page offset starts at one",
                11, ZohoReceiver.initialPageNextStart(null, full));
        assertEquals(10, ZohoReceiver.initialPageNextStart(1, partial));
        assertEquals("Routine newest-page checks do not move the saved older-page cursor",
                11, ZohoReceiver.initialPageNextStart(11, full));
        assertEquals("Explicit load-more advances to the next older page",
                21, ZohoReceiver.nextPageStart(11, full));
        assertEquals("Short server pages advance by returned rows; only an empty page exhausts",
                30, ZohoReceiver.nextPageStart(21, partial));
        List<ZohoApi.MailMessage> filtered = Collections.singletonList(
                new ZohoApi.MailMessage("one-valid-id", false, false, 0, 0, -1));
        assertEquals("Offset cursors use raw API rows, not messages surviving ID validation",
                11, ZohoReceiver.nextPageStart(1,
                        new ZohoApi.Page(filtered, 1, 10, 10, 200)));
        assertEquals(0, ZohoReceiver.nextPageStart(30, page(0, 10)));
    }

    @Test
    public void manualPageEligibilityDoesNotDependOnAutomaticFolderSync() {
        EntityFolder folder = new EntityFolder("Projects", EntityFolder.USER);
        folder.selectable = true;
        folder.zoho_folder_id = "zoho-projects";
        folder.synchronize = false;

        assertTrue(ZohoReceiver.canFetchMoreManually(
                folder.selectable, folder.zoho_folder_id, folder.zoho_page_start));
        assertFalse("Manual eligibility does not enable routine synchronization",
                ZohoReceiver.shouldSyncAutomatically(folder));
        folder.zoho_page_start = ZohoReceiver.PAGE_START_EXHAUSTED;
        assertFalse(ZohoReceiver.canFetchMoreManually(
                folder.selectable, folder.zoho_folder_id, folder.zoho_page_start));
    }

    @Test
    public void successiveOlderRequestsReachPartialAndEmptyExhaustionWithoutAnActionCap() {
        EntityFolder folder = new EntityFolder("Archive", EntityFolder.ARCHIVE);
        folder.zoho_page_start = 1;
        int remoteRequests = 0;

        while (!ZohoReceiver.isFolderPageExhausted(folder)) {
            ZohoApi.Page page;
            if (remoteRequests < 12)
                page = page(10, 10);
            else if (remoteRequests == 12)
                page = page(3, 10);
            else
                page = page(0, 10);
            folder.zoho_page_start = ZohoReceiver.nextPageStart(folder.zoho_page_start, page);
            remoteRequests++;
        }

        assertEquals("Twelve full pages plus one partial and one empty page", 14, remoteRequests);
        assertEquals(Integer.valueOf(0), folder.zoho_page_start);
        for (int retry = 0; retry < 3; retry++)
            assertTrue("A repeated Fetch more action remains exhausted",
                    ZohoReceiver.isFolderPageExhausted(folder));
        assertEquals("No additional remote page is scheduled after exhaustion", 14, remoteRequests);
    }

    @Test
    public void incrementalHeadChecksDoNotDownloadCachedOriginalMessagesAgain() {
        assertTrue(ZohoReceiver.shouldFetchOriginalMessage(null));
        assertFalse(ZohoReceiver.shouldFetchOriginalMessage(new EntityMessage()));
    }

    @Test
    public void syncQueuesBodyForCachedZohoRowsOnlyWhenContentIsMissing() {
        EntityMessage cached = new EntityMessage();
        cached.content = false;
        assertTrue(ZohoReceiver.shouldFetchCachedBodyOnSync(cached));
        cached.content = true;
        assertFalse(ZohoReceiver.shouldFetchCachedBodyOnSync(cached));
        assertFalse(ZohoReceiver.shouldFetchCachedBodyOnSync(null));
    }

    @Test
    public void cachedBodyWithSyntheticMultipartFallbackIsRefetchedAndOnlyThatWarningIsRemoved() {
        String fallback = "Expected text/plain; charset=UTF-8 got multipart " +
                "[text/html] [image/png] [image/jpeg] result=false";
        EntityMessage cached = new EntityMessage();
        cached.content = true;
        cached.warning = fallback;
        assertTrue(ZohoReceiver.shouldRefetchCachedBody(cached));
        assertEquals(null, ZohoReceiver.removePlainMultipartFallbackWarnings(fallback));

        cached.warning = "Saved unrelated warning, " + fallback + ", another saved warning";
        assertTrue(ZohoReceiver.shouldRefetchCachedBody(cached));
        assertEquals("Saved unrelated warning, another saved warning",
                ZohoReceiver.removePlainMultipartFallbackWarnings(cached.warning));

        cached.warning = "Expected text/plain got multipart result=true";
        assertFalse(ZohoReceiver.shouldRefetchCachedBody(cached));
        cached.warning = null;
        assertFalse(ZohoReceiver.shouldRefetchCachedBody(cached));
        cached.content = false;
        cached.warning = fallback;
        assertFalse(ZohoReceiver.shouldRefetchCachedBody(cached));
    }

    @Test
    public void escapedZohoMultipartCacheBodyIsRefetchedWithoutAWarning() {
        String boundary = Long.toHexString(System.nanoTime());
        EntityMessage cached = new EntityMessage();
        cached.content = true;
        String cachedContent = "<div x-plain=\"true\">--" + boundary +
                "\r\n\r\nsynthetic plain fixture\r\n--" + boundary +
                "\r\nContent-Type: text/html; charset=UTF-8\r\n\r\nsynthetic html fixture\r\n--" +
                boundary + "--</div>";

        assertTrue(ZohoReceiver.shouldRefetchCachedBody(cached, cachedContent));
        assertFalse(ZohoReceiver.shouldRefetchCachedBody(cached,
                "<div x-plain=\"true\">ordinary fixture text</div>"));
        assertFalse(ZohoReceiver.shouldRefetchCachedBody(null, cachedContent));
    }

    private static ZohoApi.Page page(int count, int limit) {
        List<ZohoApi.MailMessage> messages = new ArrayList<>();
        for (int i = 0; i < count; i++)
            messages.add(new ZohoApi.MailMessage("id-" + i, false, false, 0, 0, -1));
        return new ZohoApi.Page(messages, 1, limit, count, 200);
    }
}

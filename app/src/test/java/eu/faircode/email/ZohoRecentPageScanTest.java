package eu.faircode.email;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZohoRecentPageScanTest {
    @Test
    public void continuationOverlapsThePriorPageAndAdvancesByRawRows() {
        assertEquals(10, ZohoRecentPageScan.nextStart(1, 10));
        assertEquals(19, ZohoRecentPageScan.nextStart(10, 10));
        assertEquals(0, ZohoRecentPageScan.nextStart(19, 0));
    }

    @Test
    public void inFlightCheckpointReplaysAndNewHeadRowsShiftPendingOffset() {
        Integer checkpoint = ZohoRecentPageScan.inFlight(19);
        assertEquals(Integer.valueOf(-19), checkpoint);
        assertTrue(ZohoRecentPageScan.isInFlight(checkpoint));
        assertEquals(19, ZohoRecentPageScan.resumeStart(checkpoint));
        assertEquals(Integer.valueOf(-22), ZohoRecentPageScan.shiftCheckpoint(checkpoint, 3));
        assertEquals(Integer.valueOf(22), ZohoRecentPageScan.shiftCheckpoint(19, 3));
        assertFalse(ZohoRecentPageScan.isInFlight(null));
    }

    @Test
    public void changedHeadInvalidatesTheSavedOffsetWatermark() {
        assertTrue(ZohoRecentPageScan.sameHead("head-10", "head-10"));
        assertFalse(ZohoRecentPageScan.sameHead("head-10", "new-head-10"));
        assertTrue(ZohoRecentPageScan.sameHead(null, null));
        assertFalse(ZohoRecentPageScan.sameHead(null, "head-10"));
    }

    @Test
    public void olderFetchMoreCursorRemainsIndependentFromRecentScanOffsets() {
        assertEquals(11, ZohoRecentPageScan.shiftOlderStart(11, 0));
        assertEquals(14, ZohoRecentPageScan.shiftOlderStart(11, 3));
        assertEquals(1, ZohoRecentPageScan.shiftOlderStart(1, 10));
        assertEquals(0, ZohoRecentPageScan.shiftOlderStart(0, 10));
    }

    @Test
    public void moreThanTenPagesOfNewMessagesAreTraversedToTheExistingBoundary() {
        List<ZohoApi.MailMessage> remote = new ArrayList<>();
        for (int i = 1; i <= 127; i++)
            remote.add(new ZohoApi.MailMessage("new-" + i, false, false, 0, 0, -1));
        remote.add(new ZohoApi.MailMessage("old-boundary", false, false, 0, 0, -1));
        remote.add(new ZohoApi.MailMessage("older-history", false, false, 0, 0, -1));

        ZohoRecentPageScan scan = new ZohoRecentPageScan();
        int start = 1;
        int imported = 0;
        int pages = 0;
        boolean boundaryReached = false;
        while (start > 0 && !boundaryReached) {
            int first = start - 1;
            int last = Math.min(remote.size(), first + ZohoApi.PAGE_SIZE);
            List<ZohoApi.MailMessage> page = remote.subList(first, last);
            for (ZohoApi.MailMessage message : page) {
                if (!scan.markSeen(message.id))
                    continue;
                imported++;
                if ("old-boundary".equals(message.id)) {
                    boundaryReached = true;
                    break;
                }
            }
            pages++;
            if (boundaryReached || !ZohoRecentPageScan.pageCanContinue(
                    page.size(), boundaryReached, false))
                break;
            start = ZohoRecentPageScan.nextStart(start, page.size());
        }

        assertTrue(boundaryReached);
        assertEquals(128, imported);
        assertTrue("Newer-mail polling is not capped at ten pages", pages > 10);
        assertFalse(scan.markSeen("old-boundary"));
        assertTrue(scan.markSeen("older-history"));
    }

    @Test
    public void exhaustedOlderCursorWithoutAnyCachedBoundaryStopsAfterHeadWindow() {
        EntityFolder exhausted = new EntityFolder("Archive", EntityFolder.ARCHIVE);
        exhausted.zoho_page_start = 0;
        EntityFolder available = new EntityFolder("Archive", EntityFolder.ARCHIVE);
        available.zoho_page_start = 11;

        assertTrue(ZohoReceiver.shouldStopAtNewestWindow(exhausted, false));
        assertFalse(ZohoReceiver.shouldStopAtNewestWindow(exhausted, true));
        assertFalse(ZohoReceiver.shouldStopAtNewestWindow(available, false));
    }

    @Test
    public void overlapDeduplicationKeepsOnlyFirstOccurrenceAndStopsAtSavedBoundary() {
        ZohoRecentPageScan scan = new ZohoRecentPageScan();
        List<ZohoApi.MailMessage> first = page("a", "b", "c");
        List<ZohoApi.MailMessage> second = page("c", "d", "e");
        assertEquals(3, scan.unique(first).size());
        List<ZohoApi.MailMessage> unique = scan.unique(second);
        assertEquals(2, unique.size());
        assertEquals("d", unique.get(0).id);
        assertTrue(ZohoRecentPageScan.pageCanContinue(10, false, false));
        assertFalse(ZohoRecentPageScan.pageCanContinue(10, true, false));
        assertFalse(ZohoRecentPageScan.pageCanContinue(10, false, true));
        assertFalse(ZohoRecentPageScan.pageCanContinue(9, false, false));
    }

    private static List<ZohoApi.MailMessage> page(String... ids) {
        List<ZohoApi.MailMessage> messages = new ArrayList<>();
        for (String id : ids)
            messages.add(new ZohoApi.MailMessage(id, false, false, 0, 0, -1));
        return messages;
    }
}

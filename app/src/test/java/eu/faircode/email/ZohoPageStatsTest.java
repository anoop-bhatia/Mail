package eu.faircode.email;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZohoPageStatsTest {
    @Test
    public void successfulEmptyListPageIsDistinguishableFromSkippedPage() {
        ZohoPageStats stats = new ZohoPageStats();
        stats.markListAttempted();
        stats.recordListResult(200, 0);
        assertTrue(stats.listAttempted);
        assertEquals(200, stats.listStatus);
        assertEquals(0, stats.listItems);
        assertTrue(stats.toDiagnosticString("folder").contains("listStatus=200"));
        assertTrue(stats.toDiagnosticString("folder").contains("listItems=0"));

        ZohoPageStats skipped = new ZohoPageStats();
        assertFalse(skipped.listAttempted);
        assertTrue(skipped.toDiagnosticString("folder").contains("listStatus=not-attempted"));
    }

    @Test
    public void resultAndImportCountersTrackKnownNewGetsAndCommittedImportsSeparately() {
        ZohoPageStats stats = new ZohoPageStats();
        stats.markListAttempted();
        stats.recordListResult(200, 3);
        stats.recordReference(true);
        stats.recordReference(false);
        stats.recordReference(false);
        stats.recordMetadataGet(true);
        stats.recordMetadataGet(false);
        stats.recordLinkedExisting();
        stats.recordImported();

        assertEquals(3, stats.listItems);
        assertEquals(1, stats.knownReferences);
        assertEquals(2, stats.newReferences);
        assertEquals(1, stats.metadataGetSuccesses);
        assertEquals(1, stats.metadataGetFailures);
        assertEquals(1, stats.linkedExisting);
        assertEquals(1, stats.imported);
        String diagnostic = stats.toDiagnosticString("folder");
        assertTrue(diagnostic.contains("refsKnown=1"));
        assertTrue(diagnostic.contains("refsNew=2"));
        assertTrue(diagnostic.contains("metadataGetSuccess=1"));
        assertTrue(diagnostic.contains("metadataGetFailure=1"));
        assertFalse(diagnostic.contains("originalGet"));
        assertTrue(diagnostic.contains("linkedExisting=1"));
        assertTrue(diagnostic.contains("imported=1"));
        assertFalse(diagnostic.contains("opaque"));
    }

    @Test
    public void failedListRecordsHttpStatusWithoutInventingItems() {
        ZohoPageStats stats = new ZohoPageStats();
        stats.markListAttempted();
        stats.recordListStatus(429);
        assertEquals(429, stats.listStatus);
        assertEquals(0, stats.listItems);
        assertTrue(stats.toDiagnosticString("folder").contains("listStatus=429"));
    }
}

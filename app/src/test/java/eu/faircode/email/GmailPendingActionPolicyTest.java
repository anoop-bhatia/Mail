package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailPendingActionPolicyTest {
    @Test
    public void pendingMarkerRequiresAQueuedGmailOperation() {
        assertTrue(GmailPendingActionPolicy.shouldMarkPending(GmailMutationLog.QueueResult.QUEUED));
        assertFalse(GmailPendingActionPolicy.shouldMarkPending(GmailMutationLog.QueueResult.NOT_WRITABLE));
        assertFalse(GmailPendingActionPolicy.shouldMarkPending(GmailMutationLog.QueueResult.NOT_INSERTED));
    }

    @Test
    public void pendingUiIsLimitedToQueuedGmailMessagesAndTheirGroupedRows() {
        assertTrue(GmailPendingActionPolicy.isPending(true, 123L, false));
        assertFalse(GmailPendingActionPolicy.isPending(true, null, false));
        assertFalse("A non-Gmail busy marker must not alter IMAP/POP rows",
                GmailPendingActionPolicy.isPending(false, Long.MAX_VALUE, false));
        assertTrue("A grouped folder row is pending when any Gmail member is pending",
                GmailPendingActionPolicy.isPending(false, null, true));
        assertEquals(Long.MAX_VALUE, GmailPendingActionPolicy.PENDING_UNTIL_RESOLVED);
    }

    @Test
    public void openThreadReturnsOnlyAfterGmailQueueAcceptanceAndNotForOtherProviders() {
        assertTrue(GmailPendingActionPolicy.shouldReturnToList(true, true, false));
        assertFalse(GmailPendingActionPolicy.shouldReturnToList(true, false, false));
        assertFalse(GmailPendingActionPolicy.shouldReturnToList(false, true, false));
        assertFalse(GmailPendingActionPolicy.shouldReturnToList(true, true, true));
    }
}

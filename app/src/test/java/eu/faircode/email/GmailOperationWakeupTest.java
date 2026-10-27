package eu.faircode.email;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailOperationWakeupTest {
    @Test
    public void pendingGmailWritesWakeAndBypassOnlyTheOrdinaryPollDeadline() {
        EntityOperation seen = operation(EntityOperation.SEEN, null);
        EntityOperation errored = operation(EntityOperation.FLAG, "previous error");
        EntityOperation sync = operation(EntityOperation.SYNC, null);
        EntityOperation permanentDelete = operation(EntityOperation.GMAIL_PERMANENT_DELETE, null);

        assertTrue(GmailOperationWakeup.isPendingWrite(seen));
        assertTrue(GmailOperationWakeup.isPendingWrite(permanentDelete));
        assertFalse(GmailOperationWakeup.isPendingWrite(errored));
        assertFalse(GmailOperationWakeup.isPendingWrite(sync));
        assertTrue(GmailOperationWakeup.hasPendingWrite(Arrays.asList(errored, seen, sync, permanentDelete)));
        assertFalse(GmailOperationWakeup.hasPendingWrite(Collections.singletonList(errored)));
        assertTrue(GmailOperationWakeup.shouldWake(true, false, true));
        assertFalse(GmailOperationWakeup.shouldWake(false, false, true));
        assertFalse(GmailOperationWakeup.shouldWake(true, false, false));
    }

    @Test
    public void queuedGmailMutationBypassesPollButNotQuotaBackoffOrQuotaHeadroom() {
        long now = 1_800_000_000_000L;
        int reserve = GmailQuotaWindow.PASS_BASE_RESERVE_UNITS;
        GmailQuotaWindow window = new GmailQuotaWindow(null);

        GmailQuotaWindow.PassPlan immediate = window.beginPass(now, 0, true,
                0, now + 60_000L, reserve);
        assertEquals(now, immediate.allowedAt);
        assertEquals(0, immediate.folderPageLimit);
        assertTrue(immediate.isReserved());
        window.finishPass(immediate.reservationId, now);

        long retryAt = now + 75_000L;
        GmailQuotaWindow.PassPlan backedOff = window.beginPass(now, 0, true,
                retryAt, now + 60_000L, reserve);
        assertEquals(retryAt, backedOff.allowedAt);
        assertFalse(backedOff.isReserved());

        window.recordRequest(now, GmailQuotaWindow.MAX_SYNC_UNITS_PER_WINDOW);
        GmailQuotaWindow.PassPlan full = window.beginPass(now, 0, true,
                0, now + 60_000L, reserve);
        assertEquals(now + GmailQuotaWindow.WINDOW_MS, full.allowedAt);
        assertFalse(full.isReserved());
    }

    @Test
    public void queueOnlyGmailAttachmentWakesAndBypassesFutureRoutinePoll() {
        long now = 1_800_000_000_000L;
        EntityOperation attachment = operation(EntityOperation.ATTACHMENT, null);
        boolean pendingContent = NativeApiContentOperations.hasPending(
                Collections.singletonList(attachment));

        assertTrue(pendingContent);
        assertTrue(GmailOperationWakeup.hasWork(false, false, pendingContent));
        assertTrue(GmailOperationWakeup.shouldWake(true, false, false, pendingContent));
        assertFalse(GmailOperationWakeup.shouldWake(false, false, false, pendingContent));

        GmailQuotaWindow window = new GmailQuotaWindow(null);
        GmailQuotaWindow.PassPlan immediate = window.beginPass(
                now, 0, pendingContent, 0, 0, now + 15 * 60 * 1000L,
                GmailQuotaWindow.PASS_BASE_RESERVE_UNITS);
        assertEquals("No folder request or write is needed for an on-demand attachment",
                now, immediate.allowedAt);
        assertEquals(0, immediate.folderPageLimit);
        assertTrue(immediate.isReserved());
    }

    @Test
    public void gmailClassificationDoesNotChangeZohoGraphOrLegacyImapRouting() {
        assertTrue(GmailOperationWakeup.isPendingWrite(operation(EntityOperation.SEEN, null)));
        assertFalse(GmailOperationWakeup.isPendingWrite(operation(EntityOperation.ANSWERED, null)));
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.SEEN));
        assertTrue(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.SEEN));
        assertTrue(GmailOperationWakeup.isPendingWrite(operation(EntityOperation.GMAIL_PERMANENT_DELETE, null)));
        assertFalse(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.GMAIL_PERMANENT_DELETE));
        assertFalse(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.GMAIL_PERMANENT_DELETE));

        EntityAccount imap = new EntityAccount();
        imap.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertTrue(EntityOperation.usesLegacyImapSyncSideEffects(imap));
        assertFalse(EntityOperation.shouldCreateLocalMoveCopy(gmailAccount()));
        assertTrue(EntityOperation.shouldCreateLocalMoveCopy(imap));
        assertTrue(EntityOperation.shouldCreateLocalMoveCopy(graphAccount()));
        assertTrue(EntityOperation.shouldCreateLocalMoveCopy(zohoAccount()));
        assertFalse(GmailOperationWakeup.shouldWake(false, false, true));
    }

    @Test
    public void explicitRefreshAndNewUserMutationWakeDespiteAnOlderErroredRow() {
        EntityOperation failed = operation(EntityOperation.MOVE, "previous REST error");
        EntityOperation retry = operation(EntityOperation.MOVE, null);

        assertFalse(GmailOperationWakeup.hasPendingWrite(Collections.singletonList(failed)));
        assertTrue(GmailOperationWakeup.shouldWake(true, true, false));
        assertTrue(GmailOperationWakeup.hasPendingWrite(Arrays.asList(failed, retry)));
        assertTrue(GmailOperationWakeup.shouldWake(true, false,
                GmailOperationWakeup.hasPendingWrite(Arrays.asList(failed, retry))));
        assertFalse(GmailOperationWakeup.shouldWake(false, true, false));
    }

    private static EntityAccount gmailAccount() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GMAIL;
        account.protocol = EntityAccount.TYPE_IMAP;
        return account;
    }

    private static EntityAccount graphAccount() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        return account;
    }

    private static EntityAccount zohoAccount() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        return account;
    }

    private static EntityOperation operation(String name, String error) {
        EntityOperation operation = new EntityOperation();
        operation.name = name;
        operation.error = error;
        return operation;
    }
}

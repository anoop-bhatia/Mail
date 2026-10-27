package eu.faircode.email;

import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ZohoOperationWakeupTest {
    @Test
    public void classifiesOnlySupportedZohoWorkerWrites() {
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.SEEN));
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.FLAG));
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.MOVE));
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.DELETE));
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT));
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(
                EntityOperation.ZOHO_PERMANENT_DELETE));
        assertTrue(EntityOperation.isZohoApiMutation(EntityOperation.ZOHO_DELETE_SPAM_PERMANENT));
        assertTrue(EntityOperation.isZohoApiMutation(EntityOperation.ZOHO_PERMANENT_DELETE));
        assertTrue(EntityOperation.isZohoApiMessageWrite(EntityOperation.ZOHO_DELETE_SPAM_PERMANENT));
        assertTrue(EntityOperation.isZohoApiMessageWrite(EntityOperation.ZOHO_PERMANENT_DELETE));
        assertTrue(EntityOperation.ZOHO_API_WRITE_NAMES_SQL.contains(
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT));
        assertTrue(EntityOperation.ZOHO_API_WRITE_NAMES_SQL.contains(
                EntityOperation.ZOHO_PERMANENT_DELETE));
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.ZOHO_PURGE_TRASH));
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.ZOHO_PURGE_SPAM));
        assertFalse(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.COPY));
        assertFalse(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.EXPUNGE));
        assertFalse(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.PURGE));
        assertFalse(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.SYNC));
    }

    @Test
    public void onlyDeleteFromTrashMapsToPermanentMessageDeletion() {
        assertTrue(ZohoMailMutator.isPermanentDelete(EntityFolder.TRASH));
        assertTrue(ZohoMailMutator.isPermanentDelete(
                EntityOperation.DELETE, EntityFolder.TRASH));
        assertFalse(ZohoMailMutator.isPermanentDelete(EntityFolder.INBOX));
        assertFalse(ZohoMailMutator.isPermanentDelete(EntityFolder.ARCHIVE));
    }

    @Test
    public void explicitZohoPermanentDeleteUsesDirectOperationOutsideTrashAndSpam() {
        EntityAccount zoho = new EntityAccount();
        zoho.id = 42L;
        zoho.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        zoho.protocol = EntityAccount.TYPE_IMAP;

        EntityFolder trash = zohoFolder(zoho.id, EntityFolder.TRASH, "100");
        EntityFolder spam = zohoFolder(zoho.id, EntityFolder.JUNK, "200");
        EntityFolder inbox = zohoFolder(zoho.id, EntityFolder.INBOX, "300");
        EntityFolder archive = zohoFolder(zoho.id, EntityFolder.ARCHIVE, "400");
        EntityFolder sent = zohoFolder(zoho.id, EntityFolder.SENT, "500");
        EntityMessage message = new EntityMessage();
        message.account = zoho.id;
        message.zoho_id = "900";

        message.zoho_folder_ids = "|100|";
        boolean trashPermanent = MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                true, trash.type);
        assertTrue(trashPermanent);
        assertEquals(EntityOperation.DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, trash, message, trashPermanent));
        assertTrue(ZohoMailMutator.isPermanentDelete(EntityOperation.DELETE, trash.type));

        message.zoho_folder_ids = "|200|";
        boolean spamPermanent = MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                true, spam.type);
        assertTrue(spamPermanent);
        assertEquals(EntityOperation.ZOHO_DELETE_SPAM_PERMANENT,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, spam, message, spamPermanent));
        assertTrue(ZohoMailMutator.isPermanentDelete(
                EntityOperation.ZOHO_DELETE_SPAM_PERMANENT, spam.type));
        assertFalse(ZohoMailMutator.isPermanentDelete(EntityOperation.DELETE, spam.type));
        assertEquals(EntityOperation.DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, spam, message, false));

        message.zoho_folder_ids = "|300|";
        boolean inboxPermanent = MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                true, inbox.type);
        assertFalse(inboxPermanent);
        assertEquals(EntityOperation.DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, inbox, message, inboxPermanent));
        assertFalse(ZohoMailMutator.isPermanentDelete(EntityOperation.DELETE, inbox.type));
        assertEquals(EntityOperation.ZOHO_PERMANENT_DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, inbox, message, true));
        assertTrue(ZohoMailMutator.isPermanentDelete(
                EntityOperation.ZOHO_PERMANENT_DELETE, inbox.type));

        message.zoho_folder_ids = "|400|";
        assertEquals(EntityOperation.DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, archive, message,
                        MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                                true, archive.type)));
        assertFalse(ZohoMailMutator.isPermanentDelete(EntityOperation.DELETE, archive.type));
        assertEquals(EntityOperation.ZOHO_PERMANENT_DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, archive, message, true));

        message.zoho_folder_ids = "|500|";
        assertEquals(EntityOperation.DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, sent, message,
                        MessageSelectionCapabilities.isZohoPermanentIndividualDelete(
                                true, sent.type)));
        assertFalse(ZohoMailMutator.isPermanentDelete(EntityOperation.DELETE, sent.type));
        assertEquals(EntityOperation.ZOHO_PERMANENT_DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, sent, message, true));

        EntityAccount graph = new EntityAccount();
        graph.id = 43L;
        graph.auth_type = ServiceAuthenticator.AUTH_TYPE_GRAPH_API;
        assertEquals(EntityOperation.DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(graph, spam, message, true));
        assertFalse(ZohoMailMutator.isPermanentDelete(EntityOperation.DELETE, spam.type));

        EntityAccount imap = new EntityAccount();
        imap.id = 44L;
        imap.protocol = EntityAccount.TYPE_IMAP;
        assertEquals(EntityOperation.DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(imap, spam, message, true));
    }

    @Test
    public void explicitZohoPermanentDeleteDoesNotDowngradeAnUnwritableSource() {
        EntityAccount zoho = new EntityAccount();
        zoho.id = 42L;
        zoho.auth_type = ServiceAuthenticator.AUTH_TYPE_ZOHO;
        zoho.protocol = EntityAccount.TYPE_IMAP;

        EntityFolder inbox = zohoFolder(zoho.id, EntityFolder.INBOX, "300");
        EntityMessage message = new EntityMessage();
        message.account = zoho.id;
        message.zoho_id = "900";
        message.zoho_folder_ids = "|999|";

        assertFalse(ZohoMailMutator.isWritableMessage(zoho, inbox, message));
        assertEquals(EntityOperation.ZOHO_PERMANENT_DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, inbox, message, true));

        inbox.read_only = true;
        message.zoho_folder_ids = "|300|";
        assertFalse(ZohoMailMutator.isWritableMessage(zoho, inbox, message));
        assertEquals(EntityOperation.ZOHO_PERMANENT_DELETE,
                ZohoMailMutator.getIndividualDeleteOperation(zoho, inbox, message, true));
    }

    private static EntityFolder zohoFolder(long accountId, String type, String remoteId) {
        EntityFolder folder = new EntityFolder(type, type);
        folder.account = accountId;
        folder.zoho_folder_id = remoteId;
        folder.selectable = true;
        return folder;
    }

    @Test
    public void onlyAnIncreaseForAZohoAccountRequestsAWake() {
        assertTrue(ZohoOperationWakeup.shouldWake(true, 0, 1));
        assertFalse(ZohoOperationWakeup.shouldWake(false, 0, 1));
        assertFalse(ZohoOperationWakeup.shouldWake(true, 1, 1));
        assertFalse(ZohoOperationWakeup.shouldWake(true, 2, 1));
    }

    @Test
    public void explicitRequestedFolderRefreshWakesOnlyTheZohoAccountWorker() {
        assertTrue(ZohoOperationWakeup.shouldWakeForRequestedFolderSync(true, true));
        assertFalse(ZohoOperationWakeup.shouldWakeForRequestedFolderSync(false, true));
        assertFalse(ZohoOperationWakeup.shouldWakeForRequestedFolderSync(true, false));
    }

    @Test
    public void folderOperationsMapTrashToTrashAndSpamToItsOwnZohoEmptyOperation() {
        assertEquals(EntityOperation.ZOHO_PURGE_TRASH,
                ZohoOperationWakeup.mapFolderOperation(true, EntityFolder.TRASH, EntityOperation.EXPUNGE));
        assertEquals(EntityOperation.ZOHO_PURGE_TRASH,
                ZohoOperationWakeup.mapFolderOperation(true, EntityFolder.TRASH, EntityOperation.PURGE));
        assertEquals(EntityOperation.ZOHO_PURGE_SPAM,
                ZohoOperationWakeup.mapFolderOperation(true, EntityFolder.JUNK, EntityOperation.PURGE));
        assertEquals(null,
                ZohoOperationWakeup.mapFolderOperation(true, EntityFolder.INBOX, EntityOperation.EXPUNGE));
        assertEquals(null,
                ZohoOperationWakeup.mapFolderOperation(true, EntityFolder.JUNK, EntityOperation.EXPUNGE));
        assertEquals(null,
                ZohoOperationWakeup.mapFolderOperation(true, EntityFolder.INBOX, EntityOperation.PURGE));
        assertEquals(EntityOperation.EXPUNGE,
                ZohoOperationWakeup.mapFolderOperation(false, EntityFolder.INBOX, EntityOperation.EXPUNGE));
        assertEquals(EntityOperation.PURGE,
                ZohoOperationWakeup.mapFolderOperation(false, EntityFolder.JUNK, EntityOperation.PURGE));
        assertEquals(EntityOperation.SYNC,
                ZohoOperationWakeup.mapFolderOperation(true, EntityFolder.TRASH, EntityOperation.SYNC));
    }

    @Test
    public void emptyOperationNamesCannotBeReplayedAgainstTheOtherZohoSystemFolder() {
        assertTrue(ZohoMailMutator.isEmptyFolderOperation(
                EntityOperation.ZOHO_PURGE_TRASH, EntityFolder.TRASH));
        assertTrue(ZohoMailMutator.isEmptyFolderOperation(
                EntityOperation.ZOHO_PURGE_SPAM, EntityFolder.JUNK));
        assertFalse(ZohoMailMutator.isEmptyFolderOperation(
                EntityOperation.ZOHO_PURGE_TRASH, EntityFolder.JUNK));
        assertFalse(ZohoMailMutator.isEmptyFolderOperation(
                EntityOperation.ZOHO_PURGE_SPAM, EntityFolder.TRASH));
        assertTrue(EntityOperation.ZOHO_API_WRITE_NAMES_SQL.contains(EntityOperation.ZOHO_PURGE_SPAM));
    }

    @Test
    public void queuedWriteWakesSleepingMonitorWithoutInterruptingIt() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch woken = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        Core.State monitor = new Core.State(null);
        monitor.runnable(new Runnable() {
            @Override
            public void run() {
                waiting.countDown();
                try {
                    monitor.acquire(TimeUnit.SECONDS.toMillis(30), false);
                    woken.countDown();
                } catch (InterruptedException ex) {
                    interrupted.set(true);
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }
        }, "test.zoho-write-monitor");
        monitor.start();
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            assertTrue(ZohoOperationWakeup.wake(monitor));
            assertTrue(woken.await(2, TimeUnit.SECONDS));
            assertFalse(interrupted.get());
        } finally {
            monitor.stop();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    public void queuedWriteDoesNotReleaseRetryAfterWait() throws Exception {
        CountDownLatch waiting = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Core.State monitor = new Core.State(null);
        monitor.runnable(new Runnable() {
            @Override
            public void run() {
                waiting.countDown();
                try {
                    monitor.acquireRateLimitWait(TimeUnit.SECONDS.toMillis(30));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    finished.countDown();
                }
            }
        }, "test.zoho-write-rate-limit");
        monitor.start();
        try {
            assertTrue(waiting.await(2, TimeUnit.SECONDS));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!monitor.isBackingOff() && System.nanoTime() < deadline)
                Thread.sleep(1);
            assertTrue("Monitor should be in Retry-After wait", monitor.isBackingOff());
            assertFalse(ZohoOperationWakeup.wake(monitor));
            assertTrue(monitor.isBackingOff());
        } finally {
            monitor.stop();
            assertTrue(finished.await(2, TimeUnit.SECONDS));
        }
    }
}

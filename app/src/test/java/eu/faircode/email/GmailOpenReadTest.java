package eu.faircode.email;
import org.json.JSONArray;
import org.junit.Test;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
public class GmailOpenReadTest {
    @Test
    public void openingUnreadGmailApiMessageQueuesReadWhenItsTriggerIsReached() {
        assertTrue(GmailOpenRead.shouldQueue(true, true, true, "gmail-message-id",
                true, Collections.<EntityOperation>emptyList()));
        GmailMailMutator.Plan plan = GmailMailMutator.createSeenPlan(true);
        assertTrue(plan.add.isEmpty());
        assertEquals(Collections.singletonList("UNREAD"), plan.remove);
    }
    @Test
    public void alreadyReadGmailApiMessageDoesNotQueueAnotherRead() {
        assertFalse(GmailOpenRead.shouldQueue(true, true, true, "gmail-message-id",
                false, Collections.<EntityOperation>emptyList()));
    }
    @Test
    public void accountSettingAndExistingDelayTriggerRemainAuthoritative() {
        assertFalse(GmailOpenRead.shouldQueue(true, false, true, "gmail-message-id",
                true, Collections.<EntityOperation>emptyList()));
        // The initial open callback passes false while the configured seen delay is pending.
        assertFalse(GmailOpenRead.shouldQueue(true, true, false, "gmail-message-id",
                true, Collections.<EntityOperation>emptyList()));
        assertFalse(GmailOpenRead.shouldQueue(true, true, true, "  ",
                true, Collections.<EntityOperation>emptyList()));
    }
    @Test
    public void repeatedOpenCallbacksDoNotQueueAnotherSeenTrueOperation() {
        EntityOperation read = seenOperation(true);
        assertTrue(GmailOpenRead.hasQueuedRead(Collections.singletonList(read)));
        assertFalse(GmailOpenRead.shouldQueue(true, true, true, "gmail-message-id",
                true, Arrays.asList(read)));
        EntityOperation unread = seenOperation(false);
        assertFalse(GmailOpenRead.hasQueuedRead(Collections.singletonList(unread)));
        assertTrue(GmailOpenRead.shouldQueue(true, true, true, "gmail-message-id",
                true, Arrays.asList(unread)));
    }
    @Test
    public void newlyQueuedReadWakesOnlyItsGmailWorkerAfterCommit() {
        assertTrue(GmailOpenRead.shouldQueue(true, true, true, "gmail-message-id",
                true, Collections.<EntityOperation>emptyList()));
        GmailMutationWakeups wakeups = new GmailMutationWakeups();
        wakeups.queued(73L, EntityOperation.SEEN);
        List<String> events = new ArrayList<>();
        wakeups.dispatchAfterCommit(new GmailMutationWakeups.Callback() {
            @Override
            public void onDurable(long accountId, String operation) {
                events.add("durable:" + accountId + ":" + operation);
            }
            @Override
            public void onWake(long accountId) {
                events.add("wake:" + accountId);
            }
        });
        assertEquals(Arrays.asList("durable:73:seen", "wake:73"), events);
    }
    @Test
    public void openReadPolicyDoesNotCaptureZohoGraphOrLegacyMessages() {
        assertFalse(GmailOpenRead.shouldQueue(false, true, true, "zoho-id",
                true, Collections.<EntityOperation>emptyList()));
        assertFalse(GmailOpenRead.shouldQueue(false, true, true, "graph-id",
                true, Collections.<EntityOperation>emptyList()));
        assertFalse(GmailOpenRead.shouldQueue(false, true, true, null,
                true, Collections.<EntityOperation>emptyList()));
        assertTrue(ZohoOperationWakeup.isZohoWriteOperation(EntityOperation.SEEN));
        assertTrue(GraphOperationWakeup.isGraphWriteOperation(EntityOperation.SEEN));
        assertTrue(EntityOperation.usesLegacyImapSyncSideEffects(legacyImapAccount()));
    }
    private static EntityOperation seenOperation(boolean seen) {
        EntityOperation operation = new EntityOperation();
        operation.name = EntityOperation.SEEN;
        operation.args = new JSONArray().put(seen).toString();
        return operation;
    }
    private static EntityAccount legacyImapAccount() {
        EntityAccount account = new EntityAccount();
        account.auth_type = ServiceAuthenticator.AUTH_TYPE_PASSWORD;
        account.protocol = EntityAccount.TYPE_IMAP;
        return account;
    }
}

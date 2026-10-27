package eu.faircode.email;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GmailMutationWakeupsTest {
    @Test
    public void durableEventsAndSingleSerializedWakeAreDispatchedOnlyAfterCommit() {
        GmailMutationWakeups wakeups = new GmailMutationWakeups();
        List<String> events = new ArrayList<>();

        wakeups.queued(42L, EntityOperation.DELETE);
        wakeups.queued(42L, EntityOperation.MOVE);
        wakeups.queued(7L, EntityOperation.DELETE);
        assertFalse(wakeups.isEmpty());
        assertTrue("Recording a transaction-local insert must not wake the worker", events.isEmpty());

        // This is the production boundary: FragmentMessages calls dispatchAfterCommit only
        // after Room's endTransaction returns successfully.
        events.add("commit");
        wakeups.dispatchAfterCommit(new GmailMutationWakeups.Callback() {
            @Override
            public void onDurable(long accountId, String operation) {
                events.add("durable:" + accountId + ":" + operation);
            }

            @Override
            public void onWake(long accountId) {
                assertTrue("The worker must observe the committed operation rows",
                        events.contains("commit"));
                events.add("wake:" + accountId);
            }
        });

        assertEquals(Arrays.asList(
                "commit",
                "durable:42:delete",
                "durable:42:move",
                "durable:7:delete",
                "wake:42",
                "wake:7"), events);
        assertTrue(wakeups.isEmpty());
    }

    @Test
    public void attemptTokenStaysWithItsDurableEventAndIsDispatchedOnlyAfterCommit() {
        GmailMutationWakeups wakeups = new GmailMutationWakeups();
        List<String> events = new ArrayList<>();
        String attempt = "gm-0123456789abcdef0123456789abcdef";
        wakeups.queued(9L, EntityOperation.MOVE, attempt);
        events.add("transaction");
        wakeups.dispatchAfterCommit(new GmailMutationWakeups.Callback() {
            @Override
            public void onDurable(long accountId, String operation) {
                throw new AssertionError("The trace-aware callback should be used");
            }

            @Override
            public void onDurable(long accountId, String operation, String attemptToken) {
                assertEquals("transaction", events.get(0));
                events.add("durable:" + operation + ":" + attemptToken);
            }

            @Override
            public void onWake(long accountId) {
                assertTrue(events.get(1).contains(attempt));
                events.add("wake");
            }
        });

        assertEquals(Arrays.asList(
                "transaction", "durable:move:" + attempt, "wake"), events);
        assertTrue(wakeups.isEmpty());
    }
}

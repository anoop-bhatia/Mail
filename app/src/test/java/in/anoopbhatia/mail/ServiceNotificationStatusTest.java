package in.anoopbhatia.mail;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ServiceNotificationStatusTest {
    @Test
    public void offlineAnnouncesEveryEnabledAccount() {
        ServiceNotificationStatus.Status status =
                ServiceNotificationStatus.resolve(false, 0, 3, 0);

        assertTrue(status.awaiting);
        assertEquals(3, status.accounts);
    }

    @Test
    public void offlineIgnoresStaleWorkerStates() {
        // A monitor left "connected", one stopped for lack of a network and a hibernated REST
        // monitor all mean the same thing, so the announced account count does not change.
        assertEquals(
                ServiceNotificationStatus.resolve(false, 0, 2, 0).accounts,
                ServiceNotificationStatus.resolve(false, 2, 2, 0).accounts);
        assertTrue(ServiceNotificationStatus.resolve(false, 2, 2, 0).awaiting);
    }

    @Test
    public void unknownNetworkStateAnnouncesAwaitingConnection() {
        assertTrue(ServiceNotificationStatus.resolve(null, 1, 1, 0).awaiting);
    }

    @Test
    public void onlineAnnouncesMonitoredAccountsAndOperations() {
        ServiceNotificationStatus.Status status =
                ServiceNotificationStatus.resolve(true, 2, 5, 3);

        assertFalse(status.awaiting);
        assertEquals(2, status.accounts);
        assertEquals(3, status.operations);
    }

    @Test
    public void offlineKeepsPendingOperationsBehindTheWaitingState() {
        ServiceNotificationStatus.Status status =
                ServiceNotificationStatus.resolve(false, 1, 4, 7);

        assertTrue(status.awaiting);
        assertEquals(4, status.accounts);
        assertEquals(7, status.operations);
    }
}

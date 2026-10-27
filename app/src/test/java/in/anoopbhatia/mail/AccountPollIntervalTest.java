package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AccountPollIntervalTest {
    @Test
    public void newAccountsDefaultToThePollIntervalConstant() {
        EntityAccount account = new EntityAccount();
        assertEquals(EntityAccount.DEFAULT_POLL_INTERVAL, account.poll_interval.intValue());
        assertEquals(15, account.poll_interval.intValue());
    }

    @Test
    public void blankAndEditedIntervalsRemainInMinutes() {
        assertEquals(EntityAccount.DEFAULT_POLL_INTERVAL,
                AccountPollInterval.parseMinutes(null));
        assertEquals(EntityAccount.DEFAULT_POLL_INTERVAL,
                AccountPollInterval.parseMinutes(""));
        assertEquals(37, AccountPollInterval.parseMinutes("37"));
        assertEquals(37, AccountPollInterval.normalizeMinutes(
                AccountPollInterval.parseMinutes("37")));
    }

    @Test
    public void persistedAndScheduledIntervalsKeepTheExistingOneMinuteFloor() {
        assertEquals(1, AccountPollInterval.normalizeMinutes(0));
        assertEquals(1, AccountPollInterval.normalizeMinutes(-5));
        assertEquals(60_000L, AccountPollInterval.getDelayMillis(0));
        assertEquals(37 * 60_000L, AccountPollInterval.getDelayMillis(37));
    }

    @Test
    public void fifteenMinuteSettingIsNotReplacedByTheOneMinuteMinimum() {
        assertEquals(15, AccountPollInterval.parseMinutes("15"));
        assertEquals(15 * 60_000L, AccountPollInterval.getDelayMillis(15));
        EntityAccount first = new EntityAccount();
        EntityAccount second = new EntityAccount();
        first.poll_interval = 15;
        second.poll_interval = 37;
        assertEquals(15 * 60_000L, AccountPollInterval.getDelayMillis(first.poll_interval));
        assertEquals(37 * 60_000L, AccountPollInterval.getDelayMillis(second.poll_interval));
    }

    @Test
    public void allApiMonitorsReadTheirOwnSavedIntervalAndSettingChangesRestartThem() throws Exception {
        String service = source("ServiceSynchronize");
        for (String provider : new String[]{"Gmail", "Graph", "Zoho"}) {
            int start = service.indexOf("private void monitor" + provider + "Account(");
            int end = service.indexOf("\n    private ", start + 1);
            String monitor = service.substring(start, end);
            assertTrue(provider, monitor.contains("AccountPollInterval.getDelayMillis(account.poll_interval)"));
        }
        String settings = source("FragmentAccount");
        assertTrue(settings.contains("AccountPollInterval.parseMinutes(interval)"));
        // The save happens on the in-transaction copy of the account, not the fragment field.
        assertTrue(settings.contains(
                "transactionAccount.poll_interval = AccountPollInterval.normalizeMinutes(poll_interval)"));
        assertTrue(settings.contains("Long.toString(account.poll_interval)"));
        assertTrue(source("TupleAccountState").contains("this.poll_interval.equals(other.poll_interval)"));
        assertTrue(service.contains("!prev.accountState.equals(current.accountState)"));
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}

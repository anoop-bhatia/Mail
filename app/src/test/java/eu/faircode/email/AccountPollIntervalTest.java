package eu.faircode.email;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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
}

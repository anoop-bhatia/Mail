package eu.faircode.email;

import org.junit.Test;

import javax.mail.Address;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SupportReportRecipientTest {
    @Test
    public void onlyMainDrawerSupportLongPressUsesRequestedRecipient() throws Exception {
        Address support = SupportReportRecipient.forMainDrawerLongPress(true);
        assertEquals("anoopbhatia@bot.cue.im", support.toString());
        assertNull(SupportReportRecipient.forMainDrawerLongPress(false));
    }
}

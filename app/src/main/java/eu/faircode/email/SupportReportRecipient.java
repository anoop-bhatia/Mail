package eu.faircode.email;

import javax.mail.Address;
import javax.mail.internet.InternetAddress;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

final class SupportReportRecipient {
    static final String ARG_SUPPORT_LONG_PRESS = "support_long_press";
    private static final String EMAIL = "anoopbhatia@bot.cue.im";

    private SupportReportRecipient() {
    }

    static Address forMainDrawerLongPress(boolean supportLongPress) throws UnsupportedEncodingException {
        if (!supportLongPress)
            return null;
        return new InternetAddress(EMAIL, null, StandardCharsets.UTF_8.name());
    }
}

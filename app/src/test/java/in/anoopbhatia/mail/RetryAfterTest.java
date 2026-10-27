package in.anoopbhatia.mail;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * One parser for all three providers: -1 means "no usable Retry-After", otherwise a saturated
 * delay in milliseconds, with a date the server already named clamped to zero.
 */
public class RetryAfterTest {
    @Test
    public void delaySecondsAreMillisecondsAndSaturateInsteadOfOverflowing() {
        assertEquals(75_000L, RetryAfter.parseMillis("75", 1_000L));
        assertEquals(75_000L, RetryAfter.parseMillis("  75  ", 1_000L));
        assertEquals(0L, RetryAfter.parseMillis("0", 1_000L));
        assertEquals(Long.MAX_VALUE, RetryAfter.parseMillis("999999999999999999999999999999", 1_000L));
        assertEquals(Long.MAX_VALUE, RetryAfter.parseMillis(
                Long.toString(Long.MAX_VALUE / 1000L + 1), 1_000L));
    }

    @Test
    public void allThreeHttpDateFormsAreAcceptedAndPastDatesClampToZero() throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        format.setLenient(false);
        long now = format.parse("Fri, 15 Jan 2027 08:00:00 GMT").getTime();

        assertEquals(60_000L, RetryAfter.parseMillis("Fri, 15 Jan 2027 08:01:00 GMT", now));
        assertEquals(60_000L, RetryAfter.parseMillis("Friday, 15-Jan-27 08:01:00 GMT", now));
        assertEquals(60_000L, RetryAfter.parseMillis("Fri Jan 15 08:01:00 2027", now));
        assertEquals(60_000L, RetryAfter.parseMillis("Thu Jan  7 08:01:00 2027",
                format.parse("Thu, 07 Jan 2027 08:00:00 GMT").getTime()));
        assertEquals(0L, RetryAfter.parseMillis("Thu, 14 Jan 2027 08:00:00 GMT", now));
        assertEquals(0L, RetryAfter.parseMillis("Sunday, 06-Nov-94 08:49:37 GMT", now));
        assertEquals(0L, RetryAfter.parseMillis(format.format(new Date(now)), now));
    }

    @Test
    public void absentBlankMalformedAndNegativeHeadersAreTheSameUnavailableValue() {
        for (String value : new String[]{null, "", "   ", "not-a-date", "-5", "1.5",
                "Wed, 32 Jan 2027 08:01:00 GMT", "Fri, 15 Jan 2027 08:01:00 CET"}) {
            assertEquals("Retry-After " + value, RetryAfter.UNAVAILABLE,
                    RetryAfter.parseMillis(value, 1_000L));
        }
        assertFalse(RetryAfter.isPresent(RetryAfter.UNAVAILABLE));
        assertTrue(RetryAfter.isPresent(0L));
        assertTrue(RetryAfter.isPresent(1L));
    }

    @Test
    public void everyProviderParsesThroughTheSharedImplementation() throws Exception {
        for (String name : new String[]{"GmailApi", "GraphMailApi", "ZohoApi"}) {
            String source = source(name);
            assertTrue(name + " must use the shared Retry-After parser",
                    source.contains("RetryAfter.parseMillis("));
            assertFalse(name + " must not keep a provider-private Retry-After parser",
                    source.contains("parseRetryAfter("));
        }
    }

    private static String source(String name) throws Exception {
        Path root = Path.of("src/main/java/in/anoopbhatia/mail");
        if (!Files.exists(root))
            root = Path.of("app/src/main/java/in/anoopbhatia/mail");
        return Files.readString(root.resolve(name + ".java"));
    }
}

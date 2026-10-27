package javax.mail.internet;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.text.ParsePosition;
import java.util.Date;

import org.junit.Test;

public class MailDateFormatMonthTest {
    private static final String[] MONTHS = {
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    };

    private static final long[] MONTH_MILLIS = {
            1577836800000L, 1580515200000L, 1583020800000L,
            1585699200000L, 1588291200000L, 1590969600000L,
            1593561600000L, 1596240000000L, 1598918400000L,
            1601510400000L, 1604188800000L, 1606780800000L
    };

    @Test
    public void strictAcceptsTitlecaseMonthNamesAndRejectsOtherAsciiCases() {
        MailDateFormat format = new MailDateFormat();
        format.setLenient(false);

        for (int month = 0; month < MONTHS.length; month++) {
            String titlecase = dateWithMonth(MONTHS[month]);
            ParsePosition success = new ParsePosition(0);
            assertNotNull(MONTHS[month], format.parse(titlecase, success));
            assertEquals(titlecase.length(), success.getIndex());

            for (int mask = 0; mask < 8; mask++) {
                String variant = dateWithMonth(asciiCaseVariant(MONTHS[month], mask));
                if (mask == 0) {
                    continue;
                }
                ParsePosition failure = new ParsePosition(0);
                assertNull(variant, format.parse(variant, failure));
                assertEquals(2, failure.getErrorIndex());
                assertEquals(0, failure.getIndex());
            }
        }
    }

    @Test
    public void lenientAcceptsEveryAsciiCaseCombinationForEveryMonth() {
        MailDateFormat format = new MailDateFormat();
        for (String month : MONTHS) {
            for (int mask = 0; mask < 8; mask++) {
                String text = dateWithMonth(asciiCaseVariant(month, mask));
                ParsePosition position = new ParsePosition(0);
                assertNotNull(text, format.parse(text, position));
                assertEquals(text.length(), position.getIndex());
                assertEquals(-1, position.getErrorIndex());
            }
        }
    }

    @Test
    public void strictAndLenientRejectUnicodeLookalikeInitials() {
        for (boolean lenient : new boolean[] {false, true}) {
            MailDateFormat format = new MailDateFormat();
            format.setLenient(lenient);
            String text = dateWithMonth("Ｊan"); // Fullwidth U+FF2A, not ASCII J.
            ParsePosition position = new ParsePosition(0);
            assertNull(lenient ? "lenient" : "strict", format.parse(text, position));
            assertEquals(2, position.getErrorIndex());
            assertEquals(0, position.getIndex());
        }
    }

    @Test
    public void truncatedAndSharedMonthPrefixesFailAtMonthStart() {
        MailDateFormat format = new MailDateFormat();
        for (boolean lenient : new boolean[] {false, true}) {
            format.setLenient(lenient);
            for (String prefix : new String[] {"Ju", "Jux", "Ma", "Max", "A"}) {
                String text = dateWithMonth(prefix);
                ParsePosition position = new ParsePosition(0);
                assertNull(prefix, format.parse(text, position));
                assertEquals(2, position.getErrorIndex());
                assertEquals(0, position.getIndex());
            }
        }
    }

    @Test
    public void nonzeroStartRollsBackAndReportsAbsoluteMonthErrorOffset() {
        MailDateFormat format = new MailDateFormat();
        format.setLenient(false);
        String prefix = "xx";
        String text = prefix + dateWithMonth("Jux");
        ParsePosition position = new ParsePosition(prefix.length());

        assertNull(format.parse(text, position));
        assertEquals(prefix.length(), position.getIndex());
        assertEquals(prefix.length() + 2, position.getErrorIndex());
    }

    @Test
    public void successfulParseReturnsExpectedMillisAndLeavesTrailingText() {
        MailDateFormat format = new MailDateFormat();
        format.setLenient(false);
        String suffix = " trailing text";

        for (int month = 0; month < MONTHS.length; month++) {
            String text = dateWithMonth(MONTHS[month]) + suffix;
            ParsePosition position = new ParsePosition(0);
            Date parsed = format.parse(text, position);
            assertNotNull(MONTHS[month], parsed);
            assertEquals(MONTH_MILLIS[month], parsed.getTime());
            assertEquals(text.length() - suffix.length(), position.getIndex());
            assertEquals(-1, position.getErrorIndex());
        }
    }

    @Test
    public void invalidMonthDoesNotConsumeInputWhenDateHasNonzeroStart() {
        MailDateFormat format = new MailDateFormat();
        format.setLenient(true);
        String text = "lead 1 A 2020 00:00 +0000";
        ParsePosition position = new ParsePosition(5);

        assertNull(format.parse(text, position));
        assertEquals(5, position.getIndex());
        assertEquals(7, position.getErrorIndex());
    }

    private static String dateWithMonth(String month) {
        return "1 " + month + " 2020 00:00 +0000";
    }

    private static String asciiCaseVariant(String titlecase, int mask) {
        char[] chars = titlecase.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            if ((mask & (1 << i)) != 0) {
                chars[i] = i == 0
                        ? (char) (chars[i] - 'A' + 'a')
                        : (char) (chars[i] - 'a' + 'A');
            }
        }
        return new String(chars);
    }
}

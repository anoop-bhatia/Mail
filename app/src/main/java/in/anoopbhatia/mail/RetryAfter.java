package in.anoopbhatia.mail;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one Retry-After parser used by every native REST provider.
 *
 * <p>Accepts the RFC 9110 forms: delay-seconds, and an HTTP-date in IMF-fixdate, RFC 850 or
 * asctime form. The result is a non-negative delay in milliseconds, saturated instead of
 * overflowing, and clamped to zero when the named moment has already passed. A header that is
 * absent, blank or malformed returns {@link #UNAVAILABLE}: one value for "no usable delay", so no
 * caller has to guess whether 0 meant "absent" or "retry now", and no provider silently treats a
 * broken header as a real backoff instruction.</p>
 */
final class RetryAfter {
    /** No usable Retry-After header: absent, blank or malformed. */
    static final long UNAVAILABLE = -1L;

    private static final Pattern IMF_FIXDATE = Pattern.compile(
            "^(Mon|Tue|Wed|Thu|Fri|Sat|Sun), [0-9]{2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) " +
                    "[0-9]{4} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT$");
    private static final Pattern RFC850_DATE = Pattern.compile(
            "^(Monday|Tuesday|Wednesday|Thursday|Friday|Saturday|Sunday), ([0-9]{2})-" +
                    "(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)-([0-9]{2}) " +
                    "([0-9]{2}:[0-9]{2}:[0-9]{2}) GMT$");
    private static final Pattern ASCTIME_DATE = Pattern.compile(
            "^(Mon|Tue|Wed|Thu|Fri|Sat|Sun) (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec) " +
                    "(?:([0-9]{2})| ([0-9])) ([0-9]{2}:[0-9]{2}:[0-9]{2}) ([0-9]{4})$");

    private RetryAfter() {
    }

    /** True when the header was present and usable, including an explicit "retry now" of 0. */
    static boolean isPresent(long millis) {
        return millis >= 0;
    }

    static long parseMillis(String value, long nowMs) {
        if (value == null || value.trim().isEmpty())
            return UNAVAILABLE;
        String trimmed = value.trim();
        if (trimmed.matches("[0-9]+")) {
            long seconds = 0;
            for (int i = 0; i < trimmed.length(); i++) {
                int digit = trimmed.charAt(i) - '0';
                if (seconds > (Long.MAX_VALUE - digit) / 10L)
                    return Long.MAX_VALUE;
                seconds = seconds * 10L + digit;
            }
            return (seconds > Long.MAX_VALUE / 1000L ? Long.MAX_VALUE : seconds * 1000L);
        }
        Date date = parseHttpDate(trimmed, nowMs);
        if (date == null)
            return UNAVAILABLE;
        return Math.max(0, date.getTime() - nowMs);
    }

    private static Date parseHttpDate(String value, long nowMs) {
        if (IMF_FIXDATE.matcher(value).matches())
            return parseHttpDate(value, "EEE, dd MMM yyyy HH:mm:ss 'GMT'");

        Matcher rfc850 = RFC850_DATE.matcher(value);
        if (rfc850.matches()) {
            Calendar current = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.US);
            current.setTimeInMillis(nowMs);
            int year = (current.get(Calendar.YEAR) / 100) * 100 +
                    Integer.parseInt(rfc850.group(4));
            Calendar cutoff = Calendar.getInstance(TimeZone.getTimeZone("GMT"), Locale.US);
            cutoff.setTimeInMillis(nowMs);
            cutoff.add(Calendar.YEAR, 50);
            Date candidate = parseHttpDate(rfc850CalendarDate(rfc850, year),
                    "dd-MMM-yyyy HH:mm:ss 'GMT'");
            if (year > cutoff.get(Calendar.YEAR) ||
                    (candidate != null && candidate.getTime() > cutoff.getTimeInMillis()))
                year -= 100;
            return parseHttpDate(rfc850Date(rfc850, year), "EEEE, dd-MMM-yyyy HH:mm:ss 'GMT'");
        }

        Matcher asctime = ASCTIME_DATE.matcher(value);
        if (asctime.matches()) {
            String day = (asctime.group(3) == null ? asctime.group(4) : asctime.group(3));
            String normalized = asctime.group(1) + " " + asctime.group(2) + " " + day + " " +
                    asctime.group(5) + " " + asctime.group(6);
            return parseHttpDate(normalized, "EEE MMM d HH:mm:ss yyyy");
        }
        return null;
    }

    private static String rfc850Date(Matcher matcher, int year) {
        return matcher.group(1) + ", " + matcher.group(2) + "-" + matcher.group(3) + "-" + year +
                " " + matcher.group(5) + " GMT";
    }

    private static String rfc850CalendarDate(Matcher matcher, int year) {
        return matcher.group(2) + "-" + matcher.group(3) + "-" + year +
                " " + matcher.group(5) + " GMT";
    }

    private static Date parseHttpDate(String value, String pattern) {
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("GMT"));
        format.setLenient(false);
        ParsePosition position = new ParsePosition(0);
        Date date = format.parse(value, position);
        return (date != null && position.getIndex() == value.length() ? date : null);
    }
}

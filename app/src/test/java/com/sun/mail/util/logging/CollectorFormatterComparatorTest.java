package com.sun.mail.util.logging;

import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.junit.Test;

/** Regression tests for CollectorFormatter comparator initialization and use. */
public class CollectorFormatterComparatorTest {
    @Test
    public void defaultComparatorIsSeveritySingleton() throws Exception {
        CollectorFormatter formatter = new IsolatedCollectorFormatter();
        Field field = CollectorFormatter.class.getDeclaredField("comparator");
        field.setAccessible(true);
        assertSame(SeverityComparator.getInstance(), field.get(formatter));
    }

    @Test
    public void defaultComparatorChoosesMoreSevereRecord() {
        CollectorFormatter formatter = new IsolatedCollectorFormatter();
        LogRecord info = record(Level.INFO, 1L, 10L);
        LogRecord warning = record(Level.WARNING, 2L, 20L);
        assertSame(warning, formatter.apply(info, warning));
        assertSame(warning, formatter.apply(warning, info));
    }

    @Test
    public void defaultComparatorKeepsFirstOnAnExactTie() {
        CollectorFormatter formatter = new IsolatedCollectorFormatter();
        LogRecord first = record(Level.INFO, 7L, 42L);
        LogRecord second = record(Level.INFO, 7L, 42L);
        assertSame(first, formatter.apply(first, second));
    }

    @Test
    public void nullComparatorAlwaysChoosesIncomingRecord() {
        CollectorFormatter formatter = new CollectorFormatter("{1}", null, null);
        LogRecord first = record(Level.WARNING, 1L, 1L);
        LogRecord second = record(Level.INFO, 2L, 2L);
        assertSame(second, formatter.apply(first, second));
    }

    @Test
    public void configuredAndReversedComparatorsControlSelection() {
        Comparator<LogRecord> bySequence = new Comparator<LogRecord>() {
            @Override
            public int compare(LogRecord first, LogRecord second) {
                return Long.compare(first.getSequenceNumber(),
                        second.getSequenceNumber());
            }
        };
        LogRecord low = record(Level.INFO, 1L, 1L);
        LogRecord high = record(Level.INFO, 2L, 2L);
        CollectorFormatter configured = new CollectorFormatter("{1}", null,
                bySequence);
        CollectorFormatter reversed = new CollectorFormatter("{1}", null,
                bySequence.reversed());
        assertSame(high, configured.apply(low, high));
        assertSame(low, reversed.apply(low, high));
    }

    @Test
    public void comparatorFailureIsPropagated() {
        final IllegalStateException expected = new IllegalStateException("test");
        Comparator<LogRecord> failing = new Comparator<LogRecord>() {
            @Override
            public int compare(LogRecord first, LogRecord second) {
                throw expected;
            }
        };
        CollectorFormatter formatter = new CollectorFormatter("{1}", null,
                failing);
        try {
            formatter.apply(record(Level.INFO, 1L, 1L),
                    record(Level.INFO, 2L, 2L));
            fail("expected comparator failure");
        } catch (IllegalStateException actual) {
            assertSame(expected, actual);
        }
    }

    private static LogRecord record(Level level, long sequence, long millis) {
        return new FixedRecord(level, sequence, millis);
    }

    private static final class IsolatedCollectorFormatter
            extends CollectorFormatter {
    }

    private static final class FixedRecord extends LogRecord {
        private final long fixedMillis;

        private FixedRecord(Level level, long sequence, long millis) {
            super(level, "message");
            setSequenceNumber(sequence);
            fixedMillis = millis;
        }

        @Override
        public long getMillis() {
            return fixedMillis;
        }
    }
}

package com.mobby.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DurationFormatTest {

    private static long millis(long days, long hours, long minutes, long seconds) {
        return (((days * 24L + hours) * 60L + minutes) * 60L + seconds) * 1000L;
    }

    @Test
    void zeroIsReportedInSeconds() {
        assertEquals("0 seconds", DurationFormat.format(0L));
    }

    @Test
    void formatsDaysHoursMinutes() {
        assertEquals("2 days, 5 hours, 32 minutes",
                DurationFormat.format(millis(2, 5, 32, 45)));
    }

    @Test
    void formatsHoursAndMinutes() {
        assertEquals("7 hours, 12 minutes", DurationFormat.format(millis(0, 7, 12, 9)));
    }

    @Test
    void formatsSingleDay() {
        assertEquals("1 day, 3 hours", DurationFormat.format(millis(1, 3, 0, 0)));
    }

    @Test
    void formatsSecondsWhenLessThanAMinute() {
        assertEquals("45 seconds", DurationFormat.format(45_000L));
    }

    @Test
    void negativeDurationIsClampedToZero() {
        assertEquals("0 seconds", DurationFormat.format(-5_000L));
    }
}

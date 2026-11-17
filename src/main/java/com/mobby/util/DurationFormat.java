package com.mobby.util;

/**
 * Formats millisecond durations as human readable strings such as
 * {@code "2 days, 5 hours, 32 minutes"}.
 */
public final class DurationFormat {

    private DurationFormat() {
    }

    public static String format(long millis) {
        long totalSeconds = Math.max(0L, millis) / 1000L;
        long days = totalSeconds / 86_400L;
        long hours = (totalSeconds % 86_400L) / 3_600L;
        long minutes = (totalSeconds % 3_600L) / 60L;
        long seconds = totalSeconds % 60L;

        StringBuilder builder = new StringBuilder();
        if (days > 0) {
            appendPart(builder, days, "day", "days");
        }
        if (hours > 0) {
            appendPart(builder, hours, "hour", "hours");
        }
        if (minutes > 0) {
            appendPart(builder, minutes, "minute", "minutes");
        }
        if (builder.length() == 0) {
            appendPart(builder, seconds, "second", "seconds");
        }
        return builder.toString();
    }

    private static void appendPart(StringBuilder builder, long value, String singular, String plural) {
        if (builder.length() > 0) {
            builder.append(", ");
        }
        builder.append(value).append(' ').append(value == 1L ? singular : plural);
    }
}

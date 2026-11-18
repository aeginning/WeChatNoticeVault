package com.aeginning.noticevault;

/** Pure time-window calculation shared by the paginated message filters. */
public final class RecordTimeFilter {
    private static final long DAY_MILLIS = 24L * 60L * 60L * 1000L;

    private RecordTimeFilter() { }

    /**
     * Returns the inclusive lower timestamp for the supported date presets.
     * Zero means all time. Supported finite presets are 1, 7 and 30 days.
     */
    public static long sinceTimestamp(int days, long nowMillis) {
        if (nowMillis < 0L) throw new IllegalArgumentException("Current timestamp cannot be negative");
        if (days == 0) return 0L;
        if (days != 1 && days != 7 && days != 30) {
            throw new IllegalArgumentException("Unsupported date filter");
        }
        long window = days * DAY_MILLIS;
        return nowMillis >= window ? nowMillis - window : 0L;
    }
}

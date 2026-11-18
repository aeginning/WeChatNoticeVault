package com.aeginning.noticevault;

/** Pure filter helpers. Package names remain in encrypted record payloads, not SQLite indexes. */
public final class RecordFilter {
    private static final long MILLIS_PER_DAY = 24L * 60L * 60L * 1000L;

    private RecordFilter() { }

    /** Returns 0 for all-time, otherwise a rolling lower timestamp bound. */
    public static long startTimestamp(int lookbackDays, long now) {
        if (lookbackDays != 0 && lookbackDays != 1 && lookbackDays != 7 && lookbackDays != 30) {
            throw new IllegalArgumentException("Unsupported date filter");
        }
        if (lookbackDays == 0) return 0L;
        return Math.max(0L, now - lookbackDays * MILLIS_PER_DAY);
    }

    /** Empty filters match every record; non-empty filters require an exact package match. */
    public static boolean matchesPackage(String recordPackage, String packageFilter) {
        return packageFilter == null || packageFilter.isEmpty()
                || packageFilter.equals(recordPackage);
    }
}

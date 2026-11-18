package com.aeginning.noticevault;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordTimeFilterTest {
    @Test
    public void allTimeUsesZeroLowerBound() {
        assertEquals(0L, RecordTimeFilter.sinceTimestamp(0, 1_800_000_000_000L));
    }

    @Test
    public void oneDayUses24HourWindow() {
        long now = 1_800_000_000_000L;
        assertEquals(now - 24L * 60L * 60L * 1000L, RecordTimeFilter.sinceTimestamp(1, now));
    }

    @Test
    public void sevenAndThirtyDayWindowsAreExact() {
        long now = 1_800_000_000_000L;
        assertEquals(now - 7L * 24L * 60L * 60L * 1000L, RecordTimeFilter.sinceTimestamp(7, now));
        assertEquals(now - 30L * 24L * 60L * 60L * 1000L, RecordTimeFilter.sinceTimestamp(30, now));
    }

    @Test
    public void lowerBoundNeverBecomesNegative() {
        assertEquals(0L, RecordTimeFilter.sinceTimestamp(30, 100L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedWindow() {
        RecordTimeFilter.sinceTimestamp(14, 1_800_000_000_000L);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNegativeCurrentTimestamp() {
        RecordTimeFilter.sinceTimestamp(7, -1L);
    }
}

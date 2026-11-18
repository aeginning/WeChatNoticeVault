package com.aeginning.noticevault;

import org.junit.Test;
import static org.junit.Assert.*;

public class RecordFilterTest {
    @Test
    public void allTimeHasNoLowerBound() {
        assertEquals(0L, RecordFilter.startTimestamp(0, 123456L));
    }

    @Test
    public void rollingDateFiltersUseExpectedDayWindows() {
        long now = 2_000_000_000_000L;
        long day = 24L * 60L * 60L * 1000L;
        assertEquals(now - day, RecordFilter.startTimestamp(1, now));
        assertEquals(now - 7L * day, RecordFilter.startTimestamp(7, now));
        assertEquals(now - 30L * day, RecordFilter.startTimestamp(30, now));
    }

    @Test
    public void lowerBoundNeverBecomesNegative() {
        assertEquals(0L, RecordFilter.startTimestamp(30, 100L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnsupportedDateWindow() {
        RecordFilter.startTimestamp(14, 1000L);
    }

    @Test
    public void emptyPackageFilterMatchesEveryRecord() {
        assertTrue(RecordFilter.matchesPackage("com.tencent.mm", ""));
        assertTrue(RecordFilter.matchesPackage("other.app", null));
    }

    @Test
    public void packageFilterRequiresExactMatch() {
        assertTrue(RecordFilter.matchesPackage("com.tencent.mm", "com.tencent.mm"));
        assertFalse(RecordFilter.matchesPackage("com.tencent.mm", "other.app"));
        assertFalse(RecordFilter.matchesPackage(null, "other.app"));
    }
}

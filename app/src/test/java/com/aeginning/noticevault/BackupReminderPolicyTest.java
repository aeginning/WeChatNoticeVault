package com.aeginning.noticevault;

import org.junit.Test;
import static org.junit.Assert.*;

public class BackupReminderPolicyTest {
    private static final long DAY = 24L * 60L * 60L * 1000L;

    @Test public void firstBackupIsDueImmediatelyWhenReminderEnabled() {
        assertTrue(BackupReminderPolicy.isDue(7, 0L, 10_000L));
    }
    @Test public void reminderIsNotDueBeforeInterval() {
        assertFalse(BackupReminderPolicy.isDue(7, 1_000L, 1_000L + 7L * DAY - 1L));
    }
    @Test public void reminderIsDueAtExactIntervalBoundary() {
        assertTrue(BackupReminderPolicy.isDue(7, 1_000L, 1_000L + 7L * DAY));
    }
    @Test public void futureBackupTimestampDoesNotTriggerReminder() {
        assertFalse(BackupReminderPolicy.isDue(7, 20_000L, 10_000L));
    }
    @Test public void invalidOrDisabledIntervalsNeverTrigger() {
        assertFalse(BackupReminderPolicy.isDue(0, 0L, 1_000L));
        assertFalse(BackupReminderPolicy.isDue(366, 0L, 1_000L));
        assertFalse(BackupReminderPolicy.isDue(-1, 0L, 1_000L));
    }
}

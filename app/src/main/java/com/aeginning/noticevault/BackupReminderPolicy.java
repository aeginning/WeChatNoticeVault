package com.aeginning.noticevault;

/** Pure time policy for in-app backup reminders. */
public final class BackupReminderPolicy {
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private BackupReminderPolicy() { }

    public static boolean isDue(int intervalDays, long lastSuccessfulBackup, long now) {
        if (intervalDays < 1 || intervalDays > 365 || now <= 0L) return false;
        if (lastSuccessfulBackup <= 0L) return true;
        if (lastSuccessfulBackup > now) return false;
        return now - lastSuccessfulBackup >= intervalDays * DAY_MS;
    }
}

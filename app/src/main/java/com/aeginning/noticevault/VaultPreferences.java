package com.aeginning.noticevault;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Non-sensitive user preferences for notification filtering and local retention. */
public final class VaultPreferences {
    public static final String WECHAT_PACKAGE = "com.tencent.mm";
    private static final String PREFS = "notice_vault_settings";
    private static final String KEY_ALLOWED_PACKAGES = "allowed_notification_packages";
    private static final String KEY_RETENTION_DAYS = "retention_days";
    private static final String KEY_APP_LOCK = "device_credential_lock";
    private static final String KEY_LAST_CLEANUP = "last_retention_cleanup";
    private static final String KEY_DROPPED_NOTIFICATIONS = "dropped_notification_count";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_LAST_BACKUP = "last_successful_backup";
    private static final String KEY_BACKUP_REMINDER = "last_backup_reminder";
    public static final String THEME_SYSTEM = "system";
    public static final String THEME_LIGHT = "light";
    public static final String THEME_DARK = "dark";
    private static final long CLEANUP_INTERVAL_MS = 12L * 60L * 60L * 1000L;

    private VaultPreferences() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** The first-run default remains WeChat-only, preserving the app's original behaviour. */
    public static Set<String> getAllowedPackages(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.contains(KEY_ALLOWED_PACKAGES)) {
            return new HashSet<>(Collections.singleton(WECHAT_PACKAGE));
        }
        Set<String> stored = p.getStringSet(KEY_ALLOWED_PACKAGES, Collections.<String>emptySet());
        return stored == null ? new HashSet<String>() : new HashSet<>(stored);
    }

    public static boolean isPackageAllowed(Context context, String packageName) {
        return packageName != null && getAllowedPackages(context).contains(packageName);
    }

    public static void setAllowedPackages(Context context, Set<String> packages) {
        Set<String> safe = new HashSet<>();
        if (packages != null) {
            for (String name : packages) {
                if (name != null && !name.trim().isEmpty()
                        && name.length() <= 255
                        && !context.getPackageName().equals(name)) {
                    safe.add(name);
                }
            }
        }
        prefs(context).edit().putStringSet(KEY_ALLOWED_PACKAGES, safe).apply();
    }

    /** 0 disables automatic deletion; supported values are 7, 30 and 90 days. */
    public static int getRetentionDays(Context context) {
        int days = prefs(context).getInt(KEY_RETENTION_DAYS, 0);
        return days == 7 || days == 30 || days == 90 ? days : 0;
    }

    public static void setRetentionDays(Context context, int days) {
        if (days != 0 && days != 7 && days != 30 && days != 90) {
            throw new IllegalArgumentException("Unsupported retention period");
        }
        prefs(context).edit().putInt(KEY_RETENTION_DAYS, days).putLong(KEY_LAST_CLEANUP, 0L).apply();
    }

    public static boolean isAppLockEnabled(Context context) {
        return prefs(context).getBoolean(KEY_APP_LOCK, false);
    }

    public static void setAppLockEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_APP_LOCK, enabled).apply();
    }

    /** Count notifications rejected because the bounded persistence queue was full. */
    public static synchronized void recordDroppedNotification(Context context) {
        SharedPreferences p = prefs(context);
        long current = p.getLong(KEY_DROPPED_NOTIFICATIONS, 0L);
        long next = current == Long.MAX_VALUE ? Long.MAX_VALUE : current + 1L;
        p.edit().putLong(KEY_DROPPED_NOTIFICATIONS, next).apply();
    }

    public static long getDroppedNotificationCount(Context context) {
        return Math.max(0L, prefs(context).getLong(KEY_DROPPED_NOTIFICATIONS, 0L));
    }

    public static String getThemeMode(Context context) {
        String mode = prefs(context).getString(KEY_THEME_MODE, THEME_SYSTEM);
        if (THEME_LIGHT.equals(mode) || THEME_DARK.equals(mode)) return mode;
        return THEME_SYSTEM;
    }

    public static void setThemeMode(Context context, String mode) {
        if (!THEME_SYSTEM.equals(mode) && !THEME_LIGHT.equals(mode) && !THEME_DARK.equals(mode)) {
            throw new IllegalArgumentException("Unsupported theme mode");
        }
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply();
    }

    public static long getLastBackupTime(Context context) {
        return Math.max(0L, prefs(context).getLong(KEY_LAST_BACKUP, 0L));
    }

    public static void setLastBackupTime(Context context, long timestamp) {
        prefs(context).edit().putLong(KEY_LAST_BACKUP, Math.max(0L, timestamp)).apply();
    }

    /** Show a reminder only when records exist, at least seven days have elapsed, and reminders are not nagging daily. */
    public static boolean shouldShowBackupReminder(Context context, long now, boolean hasRecords) {
        if (!hasRecords) return false;
        long anchor = getLastBackupTime(context);
        if (anchor <= 0L) {
            try {
                anchor = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).firstInstallTime;
            } catch (Exception ignored) {
                return false;
            }
        }
        if (now < anchor || now - anchor < 7L * 24L * 60L * 60L * 1000L) return false;
        long lastReminder = prefs(context).getLong(KEY_BACKUP_REMINDER, 0L);
        return lastReminder <= 0L || now < lastReminder
                || now - lastReminder >= 3L * 24L * 60L * 60L * 1000L;
    }

    public static void markBackupReminderShown(Context context, long now) {
        prefs(context).edit().putLong(KEY_BACKUP_REMINDER, now).apply();
    }
    /** Run cleanup at most twice per day, and only when the user explicitly selected a period. */
    public static synchronized int maybeCleanupExpired(Context context, VaultDb db, long now)
            throws Exception {
        int days = getRetentionDays(context);
        if (days <= 0) return 0;
        SharedPreferences p = prefs(context);
        long last = p.getLong(KEY_LAST_CLEANUP, 0L);
        if (last > 0L && now - last < CLEANUP_INTERVAL_MS) return 0;
        long cutoff = now - days * 24L * 60L * 60L * 1000L;
        int deleted = db.deleteOlderThan(cutoff);
        p.edit().putLong(KEY_LAST_CLEANUP, now).apply();
        return deleted;
    }
}
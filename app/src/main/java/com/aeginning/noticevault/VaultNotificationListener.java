package com.aeginning.noticevault;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Captures only content exposed by Android's notification API, never private app storage. */
public class VaultNotificationListener extends NotificationListenerService {
    private static final String TAG = "NoticeVault";
    private static final int MAX_NOTIFICATION_TEXT = 16384;

    private volatile boolean stopping;
    private VaultDb database;
    private volatile Set<String> allowedPackageCache;
    private volatile long allowedPackageCacheAt;

    // Bound queued notification objects to avoid unbounded memory growth during bursts.
    private final ThreadPoolExecutor ioExecutor = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(256),
            runnable -> {
                Thread thread = new Thread(runnable, "NoticeVault-DB");
                thread.setPriority(Thread.NORM_PRIORITY - 1);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()) {
        @Override
        protected void terminated() {
            if (database != null) {
                database.close();
                database = null;
            }
            super.terminated();
        }
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (stopping || sbn == null) return;
        try {
            String packageName = sbn.getPackageName();
            if (!isPackageAllowed(packageName)) return;

            Notification notification = sbn.getNotification();
            if (notification == null || notification.extras == null) return;

            Bundle extras = notification.extras;
            String title = bounded(extras.getCharSequence(Notification.EXTRA_TITLE), 1024);
            String conversation = bounded(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE), 1024);
            if (conversation.isEmpty()) conversation = title;
            String body = extractBody(extras);
            if (body.trim().isEmpty() && title.trim().isEmpty()) return;

            boolean revoke = RevokeMatcher.isRevokeNotice(title, body);
            VaultRecord record = new VaultRecord(
                    sbn.getPostTime(), revoke ? VaultRecord.REVOKE : VaultRecord.MESSAGE,
                    conversation, title, body, packageName);

            // Queue rejection and malformed notification bundles are isolated to this callback.
            // Never block the system notification thread by doing database work inline.
            ioExecutor.execute(() -> persist(record));
        } catch (RuntimeException error) {
            if (!stopping) Log.w(TAG, "Unable to process notification payload", error);
        }
    }

    private boolean isPackageAllowed(String packageName) {
        long now = SystemClock.elapsedRealtime();
        Set<String> cached = allowedPackageCache;
        if (cached == null || now - allowedPackageCacheAt >= 5000L) {
            synchronized (this) {
                if (allowedPackageCache == null || now - allowedPackageCacheAt >= 5000L) {
                    allowedPackageCache = VaultPreferences.getAllowedPackages(getApplicationContext());
                    allowedPackageCacheAt = now;
                }
                cached = allowedPackageCache;
            }
        }
        return packageName != null && cached != null && cached.contains(packageName);
    }

    private void persist(VaultRecord record) {
        // Tasks already accepted by the executor must finish even after service shutdown begins.
        try {
            if (database == null) database = new VaultDb(getApplicationContext());
            VaultPreferences.maybeCleanupExpired(getApplicationContext(), database, System.currentTimeMillis());
            long id = database.saveWithRevokeLink(record);
            Log.i(TAG, id > 0L ? "Notification saved" : "Duplicate notification ignored");
        } catch (Exception error) {
            Log.e(TAG, "Unable to persist notification record", error);
        }
    }

    private String extractBody(Bundle extras) {
        String big = bounded(extras.getCharSequence(Notification.EXTRA_BIG_TEXT), MAX_NOTIFICATION_TEXT);
        if (!big.isEmpty()) return big;
        String text = bounded(extras.getCharSequence(Notification.EXTRA_TEXT), MAX_NOTIFICATION_TEXT);
        if (!text.isEmpty()) return text;

        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null && lines.length > 0) {
            StringBuilder builder = new StringBuilder();
            for (CharSequence line : lines) {
                if (line == null) continue;
                int remaining = MAX_NOTIFICATION_TEXT - builder.length();
                if (remaining <= 0) break;
                if (builder.length() > 0) {
                    builder.append('\n');
                    remaining--;
                }
                if (remaining > 0) builder.append(line, 0, Math.min(line.length(), remaining));
            }
            if (builder.length() > 0) return builder.toString();
        }

        Parcelable[] messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            List<Notification.MessagingStyle.Message> parsed =
                    Notification.MessagingStyle.Message.getMessagesFromBundleArray(messages);
            if (parsed != null) {
                StringBuilder builder = new StringBuilder();
                for (Notification.MessagingStyle.Message message : parsed) {
                    if (message == null || message.getText() == null) continue;
                    CharSequence messageText = message.getText();
                    int remaining = MAX_NOTIFICATION_TEXT - builder.length();
                    if (remaining <= 0) break;
                    if (builder.length() > 0) {
                        builder.append('\n');
                        remaining--;
                    }
                    if (remaining > 0) {
                        builder.append(messageText, 0, Math.min(messageText.length(), remaining));
                    }
                }
                if (builder.length() > 0) return builder.toString();
            }
        }
        return "";
    }

    /** Truncate the CharSequence before converting it to String to bound transient allocations. */
    private static String bounded(CharSequence value, int maxLength) {
        if (value == null || maxLength <= 0) return "";
        int length = Math.min(value.length(), maxLength);
        return length == 0 ? "" : value.subSequence(0, length).toString();
    }

    @Override
    public void onDestroy() {
        stopping = true;
        // Graceful shutdown drains accepted notifications; terminated() closes the DB on its worker.
        ioExecutor.shutdown();
        super.onDestroy();
    }
}

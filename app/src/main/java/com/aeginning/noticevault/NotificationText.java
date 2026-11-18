package com.aeginning.noticevault;

import java.util.List;

/** Bounded joining helper for Android MessagingStyle notification payloads. */
public final class NotificationText {
    private NotificationText() { }

    public static String joinMessages(List<String> messages, int maxLength) {
        if (messages == null || messages.isEmpty() || maxLength <= 0) return "";
        StringBuilder result = new StringBuilder(Math.min(maxLength, 1024));
        for (String message : messages) {
            if (message == null || message.trim().isEmpty()) continue;
            String value = message.trim();
            int separatorLength = result.length() == 0 ? 0 : 1;
            int remaining = maxLength - result.length() - separatorLength;
            if (remaining <= 0) break;
            if (separatorLength == 1) result.append('\n');
            result.append(value, 0, Math.min(value.length(), remaining));
            if (result.length() >= maxLength) break;
        }
        return result.toString();
    }
}

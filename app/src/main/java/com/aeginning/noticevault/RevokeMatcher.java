package com.aeginning.noticevault;

/** Conservative notification-text heuristics; a match is a candidate, not proof of a revoke. */
public final class RevokeMatcher {
    public static final long DEFAULT_WINDOW_MS = 5L * 60L * 1000L;
    private RevokeMatcher() { }

    public static boolean isRevokeNotice(String title, String text) {
        return matchesRevokePhrase(normalize(title)) || matchesRevokePhrase(normalize(text));
    }

    private static boolean matchesRevokePhrase(String value) {
        if (value.isEmpty()) return false;
        String phrase = stripTerminalPunctuation(value).toLowerCase(java.util.Locale.ROOT);
        switch (phrase) {
            case "撤回了一条消息":
            case "撤回一条消息":
            case "你撤回了一条消息":
            case "您撤回了一条消息":
            case "对方撤回了一条消息":
            case "收回了一条消息":
            case "you recalled a message":
            case "recalled a message":
            case "you unsent a message":
            case "unsent a message":
            case "message was recalled":
                return true;
            default:
                return false;
        }
    }

    private static String normalize(String value) {
        if (value == null || value.trim().isEmpty()) return "";
        StringBuilder clean = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '\u200B' || ch == '\uFEFF' || ch == '\u2060') continue;
            clean.append(ch);
        }
        return clean.toString().trim().replaceAll("\\s+", " ");
    }

    private static String stripTerminalPunctuation(String value) {
        int end = value.length();
        while (end > 0) {
            char ch = value.charAt(end - 1);
            if (ch == '。' || ch == '！' || ch == '!' || ch == '.') end--;
            else break;
        }
        return value.substring(0, end).trim();
    }

    public static boolean sameConversation(String left, String right) {
        return left != null && right != null && !left.trim().isEmpty()
                && left.trim().equalsIgnoreCase(right.trim());
    }

    public static boolean isCandidate(VaultRecord message, VaultRecord revoke) {
        if (message == null || revoke == null
                || !VaultRecord.MESSAGE.equals(message.type)
                || !VaultRecord.REVOKE.equals(revoke.type)
                || message.packageName == null || message.packageName.trim().isEmpty()
                || revoke.packageName == null
                || !message.packageName.equals(revoke.packageName)
                || !sameConversation(message.conversation, revoke.conversation)) return false;
        long age = revoke.timestamp - message.timestamp;
        return age >= 0L && age <= DEFAULT_WINDOW_MS;
    }
}

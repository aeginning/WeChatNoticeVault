package com.aeginning.noticevault;

import java.util.Locale;

/** Heuristic only: platform wording varies; a match is displayed as a candidate, never asserted as fact. */
public final class RevokeMatcher {
    private RevokeMatcher() {}
    public static boolean isRevokeNotice(String title, String text) {
        String s=((title==null?"":title)+" "+(text==null?"":text)).toLowerCase(Locale.ROOT);
        return s.contains("撤回了一条消息") || s.contains("撤回一条消息") || s.contains("recalled a message") || s.contains("message was recalled");
    }
    public static boolean sameConversation(String a,String b) {
        return a!=null && b!=null && !a.trim().isEmpty() && a.trim().equalsIgnoreCase(b.trim());
    }
}

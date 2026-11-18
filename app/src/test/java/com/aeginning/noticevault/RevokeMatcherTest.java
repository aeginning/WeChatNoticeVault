package com.aeginning.noticevault;

import org.junit.Test;
import static org.junit.Assert.*;

public class RevokeMatcherTest {
    @Test public void recognizesCommonChineseRevokeNotices() {
        assertTrue(RevokeMatcher.isRevokeNotice("微信", "你撤回了一条消息"));
        assertTrue(RevokeMatcher.isRevokeNotice("群聊", "对方撤回了一条消息。"));
        assertTrue(RevokeMatcher.isRevokeNotice("", "撤回一条消息"));
        assertTrue(RevokeMatcher.isRevokeNotice("", "您撤回了一条消息！"));
    }
    @Test public void recognizesEnglishRevokeNoticesCaseInsensitively() {
        assertTrue(RevokeMatcher.isRevokeNotice("Chat", "You recalled a message"));
        assertTrue(RevokeMatcher.isRevokeNotice("Chat", "UNSENT A MESSAGE"));
        assertTrue(RevokeMatcher.isRevokeNotice("Chat", "Message was recalled."));
    }
    @Test public void ignoresOrdinaryAndQuotedConversationText() {
        assertFalse(RevokeMatcher.isRevokeNotice("Alice", "今晚吃饭吗"));
        assertFalse(RevokeMatcher.isRevokeNotice("聊天记录", "我看到你撤回了一条消息"));
        assertFalse(RevokeMatcher.isRevokeNotice("帮助", "recalled a message 是撤回提示的英文"));
    }
    @Test public void conversationMustMatch() {
        assertTrue(RevokeMatcher.sameConversation("群聊A", "群聊a"));
        assertFalse(RevokeMatcher.sameConversation("群聊A", "群聊B"));
        assertFalse(RevokeMatcher.sameConversation("", ""));
    }
    @Test public void candidateMustMatchSourceConversationAndTimeWindow() {
        VaultRecord message = record(1_000_000L, VaultRecord.MESSAGE, "群聊A", "com.tencent.mm");
        VaultRecord revoke = record(1_120_000L, VaultRecord.REVOKE, "群聊a", "com.tencent.mm");
        assertTrue(RevokeMatcher.isCandidate(message, revoke));
    }
    @Test public void candidateRejectsWrongPackageConversationOrType() {
        VaultRecord revoke = record(1_120_000L, VaultRecord.REVOKE, "群聊A", "com.tencent.mm");
        assertFalse(RevokeMatcher.isCandidate(record(1_000_000L, VaultRecord.MESSAGE, "群聊A", "other.app"), revoke));
        assertFalse(RevokeMatcher.isCandidate(record(1_000_000L, VaultRecord.MESSAGE, "群聊B", "com.tencent.mm"), revoke));
        assertFalse(RevokeMatcher.isCandidate(record(1_000_000L, VaultRecord.REVOKE, "群聊A", "com.tencent.mm"), revoke));
    }
    @Test public void candidateRejectsFutureOrOlderThanFiveMinutes() {
        VaultRecord revoke = record(1_000_000L, VaultRecord.REVOKE, "群聊A", "com.tencent.mm");
        assertFalse(RevokeMatcher.isCandidate(record(699_999L, VaultRecord.MESSAGE, "群聊A", "com.tencent.mm"), revoke));
        assertFalse(RevokeMatcher.isCandidate(record(1_000_001L, VaultRecord.MESSAGE, "群聊A", "com.tencent.mm"), revoke));
    }
    private static VaultRecord record(long ts, String type, String conversation, String pkg) {
        return new VaultRecord(ts, type, conversation, "title", "body", pkg);
    }
}

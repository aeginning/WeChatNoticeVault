package com.aeginning.noticevault;
import org.junit.Test;
import static org.junit.Assert.*;
public class RevokeMatcherTest {
 @Test public void recognizesChineseRevokeNotice(){assertTrue(RevokeMatcher.isRevokeNotice("文件传输助手","你撤回了一条消息"));}
 @Test public void recognizesEnglishRevokeNotice(){assertTrue(RevokeMatcher.isRevokeNotice("Chat","Alice recalled a message"));}
 @Test public void doesNotTreatOrdinaryMessageAsRevoke(){assertFalse(RevokeMatcher.isRevokeNotice("Alice","今晚吃饭吗"));}
 @Test public void conversationMustMatch(){assertTrue(RevokeMatcher.sameConversation("群聊A","群聊a"));assertFalse(RevokeMatcher.sameConversation("群聊A","群聊B"));}
}

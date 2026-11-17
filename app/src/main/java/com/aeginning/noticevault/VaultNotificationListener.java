package com.aeginning.noticevault;

import android.app.Notification;
import android.app.RemoteInput;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

/** Captures only content exposed by Android's notification API. It cannot read WeChat's private storage. */
public class VaultNotificationListener extends NotificationListenerService {
    private static final String TAG="NoticeVault";
    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if(sbn==null || !"com.tencent.mm".equals(sbn.getPackageName())) return;
        Notification n=sbn.getNotification(); if(n==null||n.extras==null) return;
        Bundle e=n.extras; String title=cs(e.getCharSequence(Notification.EXTRA_TITLE));
        String conversation=cs(e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)); if(conversation.isEmpty()) conversation=title;
        String body=extractBody(e);
        if(body.trim().isEmpty() && title.trim().isEmpty()) return;
        boolean revoke=RevokeMatcher.isRevokeNotice(title,body);
        VaultRecord r=new VaultRecord(sbn.getPostTime(),revoke?VaultRecord.REVOKE:VaultRecord.MESSAGE,conversation,title,body,sbn.getPackageName());
        try { new VaultDb(getApplicationContext()).saveWithRevokeLink(r); Log.i(TAG,"Stored notification record type="+r.type); }
        catch(Exception ex){ Log.e(TAG,"Unable to persist notification",ex); }
    }
    private String extractBody(Bundle e) {
        String big=cs(e.getCharSequence(Notification.EXTRA_BIG_TEXT)); if(!big.isEmpty()) return big;
        String text=cs(e.getCharSequence(Notification.EXTRA_TEXT)); if(!text.isEmpty()) return text;
        CharSequence[] lines=e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if(lines!=null && lines.length>0){ StringBuilder b=new StringBuilder(); for(CharSequence line:lines){if(b.length()>0)b.append('\n');b.append(line);} return b.toString(); }
        Parcelable[] messages=e.getParcelableArray(Notification.EXTRA_MESSAGES);
        if(messages!=null){StringBuilder b=new StringBuilder(); for(Parcelable p:messages) if(p instanceof Notification.MessagingStyle.Message){ CharSequence t=((Notification.MessagingStyle.Message)p).getText(); if(t!=null){if(b.length()>0)b.append('\n');b.append(t);} } if(b.length()>0)return b.toString();}
        return "";
    }
    private static String cs(CharSequence c){return c==null?"":c.toString();}
}

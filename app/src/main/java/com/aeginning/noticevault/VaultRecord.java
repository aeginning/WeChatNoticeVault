package com.aeginning.noticevault;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class VaultRecord {
    public static final String MESSAGE = "message", REVOKE = "revoke";
    public long id, timestamp;
    public String type, conversation, title, text, packageName;
    public JSONArray candidates = new JSONArray();
    public VaultRecord(long timestamp, String type, String conversation, String title, String text, String packageName) {
        this.timestamp=timestamp; this.type=type; this.conversation=conversation; this.title=title; this.text=text; this.packageName=packageName;
    }
    public JSONObject toJson() throws JSONException {
        JSONObject o=new JSONObject(); o.put("id",id); o.put("timestamp",timestamp); o.put("type",type); o.put("conversation",conversation);
        o.put("title",title); o.put("text",text); o.put("package",packageName); o.put("candidates",candidates); return o;
    }
    public static VaultRecord fromJson(JSONObject o) throws JSONException {
        VaultRecord r=new VaultRecord(o.getLong("timestamp"),o.getString("type"),o.optString("conversation"),o.optString("title"),o.optString("text"),o.optString("package"));
        r.id=o.optLong("id",-1); r.candidates=o.optJSONArray("candidates")==null?new JSONArray():o.getJSONArray("candidates"); return r;
    }
}

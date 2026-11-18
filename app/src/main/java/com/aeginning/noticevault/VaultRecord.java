package com.aeginning.noticevault;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class VaultRecord {
    public static final String MESSAGE = "message";
    public static final String REVOKE = "revoke";

    public long id;
    public long timestamp;
    public String type;
    public String conversation;
    public String title;
    public String text;
    public String packageName;
    public boolean favorite;
    public JSONArray candidates = new JSONArray();

    public VaultRecord(long timestamp, String type, String conversation, String title,
                       String text, String packageName) {
        this.timestamp = timestamp;
        this.type = type;
        this.conversation = conversation == null ? "" : conversation;
        this.title = title == null ? "" : title;
        this.text = text == null ? "" : text;
        this.packageName = packageName == null ? "" : packageName;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject object = new JSONObject();
        object.put("id", id);
        object.put("timestamp", timestamp);
        object.put("type", type);
        object.put("conversation", conversation);
        object.put("title", title);
        object.put("text", text);
        object.put("package", packageName);
        object.put("favorite", favorite);
        object.put("candidates", candidates == null ? new JSONArray() : candidates);
        return object;
    }

    public static VaultRecord fromJson(JSONObject object) throws JSONException {
        if (object == null) throw new IllegalArgumentException("记录对象为空");
        VaultRecord record = new VaultRecord(
                object.getLong("timestamp"), object.getString("type"),
                object.optString("conversation", ""), object.optString("title", ""),
                object.optString("text", ""), object.optString("package", ""));
        record.id = object.optLong("id", -1L);
        record.favorite = object.optBoolean("favorite", false);
        JSONArray candidateArray = object.optJSONArray("candidates");
        record.candidates = candidateArray == null ? new JSONArray() : candidateArray;
        return record;
    }
}

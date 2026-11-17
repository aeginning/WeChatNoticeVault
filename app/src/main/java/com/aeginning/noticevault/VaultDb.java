package com.aeginning.noticevault;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** SQLite stores only timestamp/type indexes in cleartext; all user-visible record fields are AES-GCM encrypted. */
public class VaultDb extends SQLiteOpenHelper {
    private static final String DB="noticevault.db"; private static final int VERSION=1;
    public VaultDb(Context c){super(c,DB,null,VERSION); setWriteAheadLoggingEnabled(true);}
    @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE records (_id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, type TEXT NOT NULL, payload BLOB NOT NULL)"); db.execSQL("CREATE INDEX idx_records_ts ON records(ts DESC)");}
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV) { }
    public synchronized long insert(VaultRecord r) throws Exception {
        byte[] encrypted=VaultCrypto.encrypt(r.toJson().toString().getBytes(StandardCharsets.UTF_8));
        ContentValues cv=new ContentValues(); cv.put("ts",r.timestamp); cv.put("type",r.type); cv.put("payload",encrypted);
        return getWritableDatabase().insertOrThrow("records",null,cv);
    }
    public synchronized List<VaultRecord> all() throws Exception { return query(null); }
    public synchronized List<VaultRecord> search(String q) throws Exception {
        List<VaultRecord> out=new ArrayList<>(); String needle=q==null?"":q.toLowerCase(java.util.Locale.ROOT);
        for(VaultRecord r:all()) if((r.title+" "+r.text+" "+r.conversation).toLowerCase(java.util.Locale.ROOT).contains(needle)) out.add(r);
        return out;
    }
    private List<VaultRecord> query(String ignored) throws Exception {
        List<VaultRecord> out=new ArrayList<>(); Cursor c=getReadableDatabase().query("records",new String[]{"_id","payload"},null,null,null,null,"ts DESC","10000");
        try { while(c.moveToNext()){ byte[] p=c.getBlob(1); JSONObject o=new JSONObject(new String(VaultCrypto.decrypt(p),StandardCharsets.UTF_8)); VaultRecord r=VaultRecord.fromJson(o); r.id=c.getLong(0); out.add(r); } } finally { c.close(); }
        return out;
    }
    public synchronized long saveWithRevokeLink(VaultRecord r) throws Exception {
        if(VaultRecord.REVOKE.equals(r.type)) {
            long min=r.timestamp-5*60*1000L;
            for(VaultRecord old:query(null)) if(VaultRecord.MESSAGE.equals(old.type) && old.timestamp<=r.timestamp && old.timestamp>=min && RevokeMatcher.sameConversation(old.conversation,r.conversation)) r.candidates.put(old.id);
        }
        return insert(r);
    }
    public synchronized void importRecords(JSONArray records) throws Exception {
        SQLiteDatabase db=getWritableDatabase(); db.beginTransaction();
        try {
            java.util.Map<Long,Long> remap=new java.util.HashMap<>(); java.util.List<VaultRecord> imported=new java.util.ArrayList<>();
            for(int i=0;i<records.length();i++){ VaultRecord r=VaultRecord.fromJson(records.getJSONObject(i)); long old=r.id; r.id=-1; long fresh=insert(r); if(old>0) remap.put(old,fresh); r.id=fresh; imported.add(r); }
            for(VaultRecord r:imported){ JSONArray mapped=new JSONArray(); for(int j=0;j<r.candidates.length();j++){ long old=r.candidates.optLong(j,-1); Long fresh=remap.get(old); if(fresh!=null)mapped.put(fresh); } r.candidates=mapped; byte[] encrypted=VaultCrypto.encrypt(r.toJson().toString().getBytes(StandardCharsets.UTF_8)); ContentValues cv=new ContentValues(); cv.put("payload",encrypted); db.update("records",cv,"_id=?",new String[]{String.valueOf(r.id)}); }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized JSONArray exportRecords() throws Exception { JSONArray a=new JSONArray(); for(VaultRecord r:all()) a.put(r.toJson()); return a; }
    public synchronized void clearAll(){getWritableDatabase().delete("records",null,null);}
}

package com.aeginning.noticevault;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * User-visible message fields are AES-GCM encrypted with an Android Keystore key.
 * Only timestamp and message type remain as plaintext query indexes.
 */
public class VaultDb extends SQLiteOpenHelper {
    private static final String DB = "noticevault.db";
    private static final int VERSION = 1;
    private static final int MAX_RECORDS = 10000;

    public VaultDb(Context context) {
        super(context, DB, null, VERSION);
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "ts INTEGER NOT NULL, type TEXT NOT NULL, payload BLOB NOT NULL)");
        db.execSQL("CREATE INDEX idx_records_ts ON records(ts DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 1 schema is intentionally retained to avoid destructive upgrades.
    }

    public synchronized long insert(VaultRecord record) throws Exception {
        validateRecord(record);
        byte[] plain = record.toJson().toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        try {
            encrypted = VaultCrypto.encrypt(plain);
            ContentValues values = new ContentValues();
            values.put("ts", record.timestamp);
            values.put("type", record.type);
            values.put("payload", encrypted);
            return getWritableDatabase().insertOrThrow("records", null, values);
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
            if (encrypted != null) java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    public synchronized List<VaultRecord> all() throws Exception {
        return queryAll();
    }

    public synchronized List<VaultRecord> search(String query) throws Exception {
        List<VaultRecord> result = new ArrayList<>();
        String needle = query == null ? "" : query.toLowerCase(Locale.ROOT);
        for (VaultRecord record : queryAll()) {
            String haystack = (record.title + " " + record.text + " " + record.conversation)
                    .toLowerCase(Locale.ROOT);
            if (needle.isEmpty() || haystack.contains(needle)) result.add(record);
        }
        return result;
    }

    private List<VaultRecord> queryAll() throws Exception {
        List<VaultRecord> records = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("records", new String[]{"_id", "payload"},
                null, null, null, null, "ts DESC", String.valueOf(MAX_RECORDS));
        try {
            while (cursor.moveToNext()) {
                VaultRecord record = decode(cursor.getBlob(1));
                record.id = cursor.getLong(0);
                records.add(record);
            }
        } finally {
            cursor.close();
        }
        return records;
    }

    private VaultRecord decode(byte[] packed) throws Exception {
        byte[] plain = VaultCrypto.decrypt(packed);
        try {
            JSONObject object = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            return VaultRecord.fromJson(object);
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
        }
    }

    public synchronized long count() {
        return DatabaseUtils.longForQuery(getReadableDatabase(), "SELECT COUNT(*) FROM records", null);
    }

    public synchronized long countSince(long timestamp) {
        return DatabaseUtils.longForQuery(getReadableDatabase(),
                "SELECT COUNT(*) FROM records WHERE ts >= ?", new String[]{String.valueOf(timestamp)});
    }

    public synchronized int deleteOlderThan(long cutoff) {
        return getWritableDatabase().delete("records", "ts < ?", new String[]{String.valueOf(cutoff)});
    }

    public synchronized long saveWithRevokeLink(VaultRecord record) throws Exception {
        validateRecord(record);
        SQLiteDatabase db = getWritableDatabase();
        if (containsDuplicate(record, db)) return -1L;

        if (VaultRecord.REVOKE.equals(record.type)) {
            long minTimestamp = record.timestamp - 5L * 60L * 1000L;
            Cursor cursor = db.query("records", new String[]{"_id", "payload"},
                    "type=? AND ts BETWEEN ? AND ?",
                    new String[]{VaultRecord.MESSAGE, String.valueOf(minTimestamp), String.valueOf(record.timestamp)},
                    null, null, "ts DESC", String.valueOf(MAX_RECORDS));
            try {
                while (cursor.moveToNext()) {
                    VaultRecord previous = decode(cursor.getBlob(1));
                    previous.id = cursor.getLong(0);
                    if (RevokeMatcher.sameConversation(previous.conversation, record.conversation)) {
                        if (record.candidates.length() < 1000) record.candidates.put(previous.id);
                        else break;
                    }
                }
            } finally {
                cursor.close();
            }
        }
        return insert(record);
    }

    private boolean containsDuplicate(VaultRecord record, SQLiteDatabase db) throws Exception {
        Cursor cursor = db.query("records", new String[]{"payload"},
                "ts=? AND type=?",
                new String[]{String.valueOf(record.timestamp), record.type},
                null, null, "_id DESC", "32");
        try {
            while (cursor.moveToNext()) {
                VaultRecord old = decode(cursor.getBlob(0));
                if (sameMessage(old, record)) return true;
            }
        } finally {
            cursor.close();
        }
        return false;
    }

    private static boolean sameMessage(VaultRecord left, VaultRecord right) {
        return left.timestamp == right.timestamp
                && safe(left.type).equals(safe(right.type))
                && safe(left.packageName).equals(safe(right.packageName))
                && safe(left.conversation).equals(safe(right.conversation))
                && safe(left.title).equals(safe(right.title))
                && safe(left.text).equals(safe(right.text));
    }

    /** Imports atomically, remaps revoke references, and skips records already present. */
    public synchronized int[] importRecords(JSONArray records) throws Exception {
        if (records == null || records.length() > MAX_RECORDS) {
            throw new IllegalArgumentException("备份记录数量无效");
        }
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        int insertedCount = 0;
        int skippedCount = 0;
        try {
            long startingCount = count();
            Map<String, Long> known = new HashMap<>();
            for (VaultRecord existing : queryAll()) known.put(fingerprint(existing), existing.id);

            Map<Long, Long> remap = new HashMap<>();
            List<VaultRecord> imported = new ArrayList<>();
            Set<Long> importedIds = new HashSet<>();

            for (int i = 0; i < records.length(); i++) {
                VaultRecord record = VaultRecord.fromJson(records.getJSONObject(i));
                validateRecord(record);
                long oldId = record.id;
                Long existingId = known.get(fingerprint(record));
                long destinationId;
                if (existingId != null) {
                    destinationId = existingId;
                    skippedCount++;
                } else {
                    if (startingCount + insertedCount >= MAX_RECORDS) {
                        throw new IllegalArgumentException("恢复后记录总数不能超过 10,000 条，请先设置清理期限或清理本机旧记录");
                    }
                    destinationId = insert(record);
                    record.id = destinationId;
                    imported.add(record);
                    importedIds.add(destinationId);
                    known.put(fingerprint(record), destinationId);
                    insertedCount++;
                }
                if (oldId > 0L) remap.put(oldId, destinationId);
            }

            for (VaultRecord record : imported) {
                JSONArray mapped = new JSONArray();
                for (int i = 0; i < record.candidates.length(); i++) {
                    long oldCandidate = record.candidates.optLong(i, -1L);
                    Long newCandidate = remap.get(oldCandidate);
                    if (newCandidate != null) mapped.put(newCandidate);
                }
                record.candidates = mapped;
                if (importedIds.contains(record.id)) updatePayload(db, record);
            }
            db.setTransactionSuccessful();
            return new int[]{insertedCount, skippedCount};
        } finally {
            db.endTransaction();
        }
    }

    private void updatePayload(SQLiteDatabase db, VaultRecord record) throws Exception {
        byte[] plain = record.toJson().toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        try {
            encrypted = VaultCrypto.encrypt(plain);
            ContentValues values = new ContentValues();
            values.put("payload", encrypted);
            db.update("records", values, "_id=?", new String[]{String.valueOf(record.id)});
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
            if (encrypted != null) java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    public synchronized JSONArray exportRecords() throws Exception {
        if (count() > MAX_RECORDS) {
            throw new IllegalStateException("本机记录超过 10,000 条，请先设置保留期限或清理数据后再导出");
        }
        JSONArray array = new JSONArray();
        for (VaultRecord record : queryAll()) array.put(record.toJson());
        return array;
    }

    public synchronized void clearAll() {
        getWritableDatabase().delete("records", null, null);
    }

    private static String fingerprint(VaultRecord record) throws Exception {
        String value = record.timestamp + "\u0000" + safe(record.type) + "\u0000"
                + safe(record.packageName) + "\u0000" + safe(record.conversation) + "\u0000"
                + safe(record.title) + "\u0000" + safe(record.text);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte item : digest) result.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return result.toString();
    }

    private static void validateRecord(VaultRecord record) {
        if (record == null || record.timestamp <= 0L) throw new IllegalArgumentException("记录时间无效");
        if (!VaultRecord.MESSAGE.equals(record.type) && !VaultRecord.REVOKE.equals(record.type)) {
            throw new IllegalArgumentException("记录类型无效");
        }
        checkLength(record.packageName, 255, "应用包名");
        checkLength(record.conversation, 1024, "会话名称");
        checkLength(record.title, 1024, "通知标题");
        checkLength(record.text, 16384, "通知内容");
        if (record.candidates == null || record.candidates.length() > 1000) {
            throw new IllegalArgumentException("撤回关联数据无效");
        }
    }

    private static void checkLength(String value, int max, String field) {
        if (value != null && value.length() > max) throw new IllegalArgumentException(field + "过长");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}

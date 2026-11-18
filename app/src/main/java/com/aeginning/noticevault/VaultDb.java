package com.aeginning.noticevault;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * User-visible message fields are AES-GCM encrypted with an Android Keystore key.
 * Only timestamp and message type remain as plaintext query indexes.
 */
public class VaultDb extends SQLiteOpenHelper {
    private static final String DB = "noticevault.db";
    private static final int VERSION = 3;
    private static final int MAX_RECORDS = 10000;
    private final Context appContext;

    public VaultDb(Context context) {
        super(context.getApplicationContext(), DB, null, VERSION);
        appContext = context.getApplicationContext();
        setWriteAheadLoggingEnabled(true);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE records (_id INTEGER PRIMARY KEY AUTOINCREMENT, "
                + "ts INTEGER NOT NULL, type TEXT NOT NULL, favorite INTEGER NOT NULL DEFAULT 0, payload BLOB NOT NULL)");
        db.execSQL("CREATE INDEX idx_records_ts ON records(ts DESC)");
        db.execSQL("CREATE INDEX idx_records_type_ts ON records(type, ts DESC, _id DESC)");
        db.execSQL("CREATE INDEX idx_records_favorite_ts ON records(favorite, ts DESC, _id DESC)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Version 2 adds a non-destructive composite index; existing rows and ciphertext are untouched.
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_type_ts ON records(type, ts DESC, _id DESC)");
        }
        if (oldVersion < 3) {
            // Existing rows and encrypted payloads are untouched; legacy rows are not favorites.
            db.execSQL("ALTER TABLE records ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_records_favorite_ts ON records(favorite, ts DESC, _id DESC)");
        }
    }

    public synchronized long insert(VaultRecord record) throws Exception {
        validateRecord(record);
        byte[] plain = record.toJson().toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = null;
        try {
            SQLiteDatabase db = getWritableDatabase();
            boolean allowKeyCreation;
            try (Cursor existing = db.rawQuery("SELECT 1 FROM records LIMIT 1", null)) {
                allowKeyCreation = !existing.moveToFirst();
            }
            // A missing Keystore alias may be recreated only for a truly empty vault.
            encrypted = VaultCrypto.encrypt(plain, allowKeyCreation);
            ContentValues values = new ContentValues();
            values.put("ts", record.timestamp);
            values.put("type", record.type);
            values.put("favorite", record.favorite ? 1 : 0);
            values.put("payload", encrypted);
            return db.insertOrThrow("records", null, values);
        } finally {
            java.util.Arrays.fill(plain, (byte) 0);
            if (encrypted != null) java.util.Arrays.fill(encrypted, (byte) 0);
        }
    }

    public synchronized List<VaultRecord> all() throws Exception {
        return queryAll();
    }

    /**
     * Reads only a bounded page into memory. A query scans encrypted rows newest-first and
     * stops as soon as it finds enough matches; unlike the legacy search(), it never retains
     * all 10,000 decoded records in a List.
     */
    public synchronized List<VaultRecord> searchPage(String query, String typeFilter,
                                                      long beforeTimestamp, long beforeId,
                                                      int limit) throws Exception {
        return searchPage(query, typeFilter, 0L, "", false, beforeTimestamp, beforeId, limit);
    }

    public synchronized List<VaultRecord> searchPage(String query, String typeFilter,
                                                      long sinceTimestamp, String packageFilter,
                                                      boolean favoriteOnly, long beforeTimestamp,
                                                      long beforeId, int limit) throws Exception {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String needle = query == null ? "" : query.trim();
        if (needle.length() > 128) needle = needle.substring(0, 128);

        StringBuilder selection = new StringBuilder();
        List<String> args = new ArrayList<>();
        if (VaultRecord.MESSAGE.equals(typeFilter) || VaultRecord.REVOKE.equals(typeFilter)) {
            selection.append("type=?");
            args.add(typeFilter);
        }
        if (sinceTimestamp > 0L) {
            if (selection.length() > 0) selection.append(" AND ");
            selection.append("ts >= ?");
            args.add(String.valueOf(sinceTimestamp));
        }
        if (favoriteOnly) {
            if (selection.length() > 0) selection.append(" AND ");
            selection.append("favorite=1");
        }
        if (beforeTimestamp > 0L) {
            if (selection.length() > 0) selection.append(" AND ");
            selection.append("(ts < ? OR (ts = ? AND _id < ?))");
            args.add(String.valueOf(beforeTimestamp));
            args.add(String.valueOf(beforeTimestamp));
            args.add(String.valueOf(beforeId));
        }

        List<VaultRecord> result = new ArrayList<>(safeLimit);
        Cursor cursor = getReadableDatabase().query(
                "records", new String[]{"_id", "payload", "favorite"},
                selection.length() == 0 ? null : selection.toString(),
                args.isEmpty() ? null : args.toArray(new String[0]),
                null, null, "ts DESC, _id DESC", null);
        try {
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Search cancelled");
                VaultRecord record = decode(cursor.getBlob(1));
                record.id = cursor.getLong(0);
                record.favorite = cursor.getInt(2) != 0;
                if (packageFilter != null && !packageFilter.isEmpty()
                        && !packageFilter.equals(record.packageName)) continue;
                if (!needle.isEmpty()
                        && !containsIgnoreCase(record.title, needle)
                        && !containsIgnoreCase(record.conversation, needle)
                        && !containsIgnoreCase(record.text, needle)) continue;
                result.add(record);
                if (result.size() >= safeLimit) break;
            }
        } finally {
            cursor.close();
        }
        return result;
    }

    /** Legacy full search retained for internal compatibility; new UI must use searchPage(). */
    public synchronized List<VaultRecord> search(String query) throws Exception {
        List<VaultRecord> result = new ArrayList<>();
        String needle = query == null ? "" : query.trim();
        for (VaultRecord record : queryAll()) {
            if (needle.isEmpty()
                    || containsIgnoreCase(record.title, needle)
                    || containsIgnoreCase(record.conversation, needle)
                    || containsIgnoreCase(record.text, needle)) result.add(record);
        }
        return result;
    }

    private List<VaultRecord> queryAll() throws Exception {
        List<VaultRecord> records = new ArrayList<>();
        Cursor cursor = getReadableDatabase().query("records", new String[]{"_id", "payload", "favorite"},
                null, null, null, null, "ts DESC", String.valueOf(MAX_RECORDS));
        try {
            while (cursor.moveToNext()) {
                VaultRecord record = decode(cursor.getBlob(1));
                record.id = cursor.getLong(0);
                record.favorite = cursor.getInt(2) != 0;
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

    /** Read-only SQLite structural check. It does not decrypt payloads or modify records. */
    public synchronized String verifyIntegrity() {
        try (Cursor cursor = getReadableDatabase().rawQuery("PRAGMA quick_check(1)", null)) {
            return cursor.moveToFirst() ? cursor.getString(0) : "no_result";
        }
    }

    /** Database, WAL and shared-memory bytes currently present on disk. */
    public synchronized long storageBytes() {
        java.io.File databaseFile = appContext.getDatabasePath(DB);
        java.io.File walFile = new java.io.File(databaseFile.getPath() + "-wal");
        java.io.File shmFile = new java.io.File(databaseFile.getPath() + "-shm");
        return databaseFile.length() + walFile.length() + shmFile.length();
    }

    public synchronized int setFavorite(long id, boolean favorite) {
        if (id <= 0L) return 0;
        ContentValues values = new ContentValues();
        values.put("favorite", favorite ? 1 : 0);
        return getWritableDatabase().update("records", values, "_id=?",
                new String[]{String.valueOf(id)});
    }

    /** Update favorite metadata for a bounded set of IDs without decrypting message bodies. */
    public synchronized int setFavorites(long[] ids, boolean favorite) {
        if (ids == null || ids.length == 0) return 0;
        StringBuilder selection = new StringBuilder("_id IN (");
        String[] args = new String[ids.length];
        int count = 0;
        for (long id : ids) {
            if (id <= 0L) continue;
            if (count > 0) selection.append(',');
            selection.append('?');
            args[count++] = String.valueOf(id);
        }
        if (count == 0) return 0;
        selection.append(')');
        if (count != args.length) args = java.util.Arrays.copyOf(args, count);
        ContentValues values = new ContentValues();
        values.put("favorite", favorite ? 1 : 0);
        return getWritableDatabase().update("records", values, selection.toString(), args);
    }

    /** Delete only explicitly selected IDs; empty or invalid input is a no-op. */
    public synchronized int deleteRecords(long[] ids) {
        if (ids == null || ids.length == 0) return 0;
        StringBuilder selection = new StringBuilder("_id IN (");
        String[] args = new String[ids.length];
        int count = 0;
        for (long id : ids) {
            if (id <= 0L) continue;
            if (count > 0) selection.append(',');
            selection.append('?');
            args[count++] = String.valueOf(id);
        }
        if (count == 0) return 0;
        selection.append(')');
        if (count != args.length) args = java.util.Arrays.copyOf(args, count);
        return getWritableDatabase().delete("records", selection.toString(), args);
    }

    public synchronized long countFavorites() {
        return DatabaseUtils.longForQuery(getReadableDatabase(),
                "SELECT COUNT(*) FROM records WHERE favorite=1", null);
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
        db.beginTransaction();
        try {
            // Check, candidate lookup and insert must be atomic against the activity's import writer.
            if (containsDuplicate(record, db)) {
                db.setTransactionSuccessful();
                return -1L;
            }

            if (VaultRecord.REVOKE.equals(record.type)) {
                long minTimestamp = record.timestamp - 5L * 60L * 1000L;
                Cursor cursor = db.query("records", new String[]{"_id", "payload"},
                        "type=? AND ts BETWEEN ? AND ?",
                        new String[]{VaultRecord.MESSAGE, String.valueOf(minTimestamp), String.valueOf(record.timestamp)},
                        null, null, "ts DESC, _id DESC", String.valueOf(MAX_RECORDS));
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

            long insertedId = insert(record);
            db.setTransactionSuccessful();
            return insertedId;
        } finally {
            db.endTransaction();
        }
    }

    private boolean containsDuplicate(VaultRecord record, SQLiteDatabase db) throws Exception {
        return findDuplicateId(record, db) > 0L;
    }

    private long findDuplicateId(VaultRecord record, SQLiteDatabase db) throws Exception {
        Cursor cursor = db.query("records", new String[]{"_id", "payload"},
                "ts=? AND type=?",
                new String[]{String.valueOf(record.timestamp), record.type},
                null, null, "_id DESC", "64");
        try {
            while (cursor.moveToNext()) {
                VaultRecord old = decode(cursor.getBlob(1));
                if (sameMessage(old, record)) return cursor.getLong(0);
            }
        } finally {
            cursor.close();
        }
        return -1L;
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
            Map<Long, Long> remap = new java.util.HashMap<>();
            List<VaultRecord> imported = new ArrayList<>();

            for (int i = 0; i < records.length(); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Import cancelled");
                VaultRecord record = VaultRecord.fromJson(records.getJSONObject(i));
                validateRecord(record);
                long oldId = record.id;
                long existingId = findDuplicateId(record, db);
                long destinationId;
                if (existingId > 0L) {
                    destinationId = existingId;
                    skippedCount++;
                } else {
                    if (startingCount + insertedCount >= MAX_RECORDS) {
                        throw new IllegalArgumentException("恢复后记录总数不能超过 10,000 条，请先设置清理期限或清理本机旧记录");
                    }
                    destinationId = insert(record);
                    record.id = destinationId;
                    imported.add(record);
                    insertedCount++;
                }
                if (oldId > 0L) remap.put(oldId, destinationId);
            }

            // Keep only newly imported records in memory; map old IDs after all IDs are known.
            for (VaultRecord record : imported) {
                JSONArray mapped = new JSONArray();
                for (int i = 0; i < record.candidates.length(); i++) {
                    long oldCandidate = record.candidates.optLong(i, -1L);
                    Long freshId = remap.get(oldCandidate);
                    if (freshId != null) mapped.put(freshId);
                }
                record.candidates = mapped;
                updatePayload(db, record);
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
            encrypted = VaultCrypto.encrypt(plain, false);
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
        Cursor cursor = getReadableDatabase().query("records", new String[]{"_id", "payload", "favorite"},
                null, null, null, null, "ts DESC, _id DESC", String.valueOf(MAX_RECORDS));
        try {
            while (cursor.moveToNext()) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Export cancelled");
                VaultRecord record = decode(cursor.getBlob(1));
                record.id = cursor.getLong(0);
                record.favorite = cursor.getInt(2) != 0;
                array.put(record.toJson());
            }
        } finally {
            cursor.close();
        }
        return array;
    }

    public synchronized void clearAll() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("records", null, null);
        // Release unused pages and WAL space after an explicit user-requested full clear.
        try (Cursor ignored = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            // Consume and close the pragma result.
        } catch (RuntimeException ignored) { }
        try {
            db.execSQL("VACUUM");
        } catch (RuntimeException ignored) {
            // Another active connection may temporarily prevent compaction; deletion is still complete.
        }
    }

    /** Explicit storage maintenance; called from a background thread only. */
    public synchronized long[] compactStorage() {
        long beforeBytes = appContext.getDatabasePath(DB).length();
        SQLiteDatabase db = getWritableDatabase();
        try (Cursor checkpoint = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            while (checkpoint.moveToNext()) {
                // Drain checkpoint status before running VACUUM.
            }
        }
        db.execSQL("VACUUM");
        long afterBytes = appContext.getDatabasePath(DB).length();
        return new long[]{beforeBytes, afterBytes};
    }

    private static boolean containsIgnoreCase(String value, String query) {
        if (value == null || query == null || query.isEmpty()) return true;
        int lastStart = value.length() - query.length();
        for (int i = 0; i <= lastStart; i++) {
            if (value.regionMatches(true, i, query, 0, query.length())) return true;
        }
        return false;
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

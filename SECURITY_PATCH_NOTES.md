# 2.1.4 memory, storage and responsiveness improvements

## Changes

- Search now reads bounded pages of 30 records and decrypts only enough cursor rows to fill one page plus a look-ahead item.
- Keyset pagination uses timestamp plus row ID, avoiding large offsets and stable-order gaps when timestamps collide.
- Search uses a dedicated single-thread executor. A newer query cancels/interupts the previous cursor scan and cannot block backup/import I/O.
- Notification ingestion now uses a single worker with a bounded 256-item queue and a named lower-priority thread. The queue drains accepted records during normal service shutdown.
- Reused the medium Typeface instance instead of allocating it for every bold TextView.
- Added one-tap filtering for all, ordinary messages and revoke notices.
- New record payloads are GZIP-compressed only when the compressed bytes are smaller, then encrypted with the existing AES-GCM Keystore key. A high-bit compression flag maintains backward compatibility with older uncompressed encrypted rows.
- Decompression is bounded to 64 KiB to prevent memory exhaustion from malformed/corrupt stored payloads.
- Added explicit background database compaction (WAL checkpoint + VACUUM) and best-effort compaction after explicit clear-all.
- Shizuku status is now visibly marked optional; missing Shizuku no longer looks like a missing core notification-listener permission.
- Version 2.1.4 / versionCode 214.

## Compatibility and data safety

- No SQLite schema migration and no change to the Keystore alias or AES-GCM key.
- Old encrypted payload header values (nonce length 12) are still decoded as uncompressed records.
- New records use the high-bit flag in the first payload byte to denote GZIP-compressed plaintext before AES-GCM encryption.
- Existing records are not batch-rewritten at startup; they remain readable and will retain their old storage size until naturally replaced or exported/imported through the supported backup flow.
- Automatic retention remains opt-in and disabled by default.
- Search result presentation is now paginated; record data is not deleted when a page is unloaded.

## Validation boundary

JUnit tests cover the pure-Java compression helper and its decompression limit. GitHub Actions must confirm compilation, all unit tests and Debug APK packaging. Real-device measurements are still required to quantify memory use, database file shrinkage, notification throughput, and touch responsiveness.


# 2.1.5 application identity and callback bug fixes

- Set the Android launcher/app/activity label to the Chinese product name 微信防撤回; use the same name for the notification listener service and home-screen title.
- Catch malformed notification extras and queue rejection at the notification callback boundary so a single malformed Bundle cannot throw an uncaught runtime exception into Android's notification dispatch thread. Database persistence remains off the callback thread.
- Remove the unused POST_NOTIFICATIONS permission declaration. The app reads notifications through the user-granted Notification Listener access and does not post notifications itself.
- Keep applicationId, SQLite database name/schema, Android Keystore alias and backup envelope unchanged for upgrade compatibility.
- Launcher icon resource remains pending confirmation of the exact standalone image asset in the user's Library; no unrelated screenshot or image was substituted.

- Make notification duplicate detection, revoke-candidate lookup and row insertion one SQLite transaction, so they cannot race against the separate backup-import writer.


# 2.1.6 launcher icon

- Added an Android adaptive launcher icon using the latest Library image supplied by the user (`mmexport1794946980382.jpg`, sunflower field photograph), cropped/resized as the foreground artwork with a blue background layer.
- Configured both `android:icon` and `android:roundIcon` to the adaptive icon resource.
- Incremented versionName to 2.1.6 and versionCode to 216. No package ID, database schema, or Keystore alias changes.


# 2.1.8 data safety and stability hardening

- Stop automatic Android Keystore alias regeneration during record decryption. The alias is created for initial writes only when the vault database has no rows.
- Add a read-only SQLite quick integrity check and database/WAL/SHM size reporting; no automatic repair or destructive recovery is attempted.
- Add a non-destructive composite index migration and visible notification queue overflow counts.
- Expand backup encryption and compression regression tests.

# 2.1.9 favorites, filters, themes and optional release signing

- Add SQLite schema v3 with a favorite bit and favorite/time index. Existing rows and AES-GCM payload bytes are not rewritten; older rows default to not-favorite.
- Keep favorite status in a dedicated metadata column rather than re-encrypting message content. Backup JSON carries favorite state and restores it when importing; merge imports add favorites but do not remove an existing local favorite.
- Add transactional chunked batch favorite/delete operations in groups of 900 IDs to stay below common SQLite bind-parameter limits. Bulk delete is limited to explicitly selected record IDs.
- Add date presets (all, 24 hours, 7 days, 30 days), app-source filtering and favorites-only filtering while retaining keyset pagination. App package names remain inside encrypted payloads and are checked after decryption rather than exposed as plaintext indexes.
- Add system/light/dark appearance modes and a backup reminder after seven days without a successful backup, limited to one prompt every three days.
- Add optional GitHub Actions Release signing using user-provided Repository Secrets. No keystore or password is stored in the repository. Without all four secrets the release build is skipped and Debug build/test remains the default.
- Bump versionName to 2.1.9 and versionCode to 219. Application ID, existing Keystore alias and encrypted payload format remain unchanged.
- Device validation remains required for SQLite v3 migration, favorites/batch operations, night mode, reminder timing and actual signing-key compatibility.

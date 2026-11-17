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

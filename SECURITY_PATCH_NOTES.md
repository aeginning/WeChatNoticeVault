# 2.1.3 stability, privacy and recovery improvements

## Changes

- Notification listener now filters by a persisted package allowlist (first-run default: WeChat only).
- Notification callbacks enqueue persistence on a single background executor; the service reuses one `VaultDb` helper and queues its close during shutdown.
- Duplicate notifications are checked against a narrow timestamp/type window before insertion.
- Revoke matching queries only messages within the preceding five minutes instead of decrypting the entire local record set.
- Retention cleanup is opt-in (off by default), supports 7/30/90 days, and runs at most twice daily when the app or listener next accesses storage.
- Added optional Android device-credential app lock and `FLAG_SECURE` to hide app content in recent-task screenshots.
- Added message detail view, copy action, total/today counters, and clear notification-capture limitations in the UI.
- Backup plaintext now uses a versioned `NoticeVaultBackup` envelope. Import remains compatible with the previous direct JSON-array format.
- Import remains transactional, validates record types/field sizes, remaps revoke references, and skips already-present duplicate records.
- Export fails explicitly when the device contains more than 10,000 records instead of silently exporting only the newest 10,000.
- Hardened encrypted-record parsing and added backup tamper-detection regression coverage.

## Compatibility

- SQLite schema version remains 1; no destructive schema migration is required.
- Existing encrypted database rows retain the same AES-GCM `VaultCrypto` format and Keystore alias.
- Existing `.nvbk` files with the `NVBK1` encrypted container and a JSON array plaintext payload remain importable.
- App version is 2.1.3 (`versionCode 213`).

## Validation boundary

Source formatting, XML/YAML parsing, and standalone backup-crypto tests are checked locally. A full Android Gradle build, Android Keystore integration, notification lifecycle, device credential prompt, package visibility, and real-device battery behavior must still be confirmed by GitHub Actions and an Android 13 device test. No unverified result should be treated as passed.

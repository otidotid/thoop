# THOOP — complete cloud recovery v2 (Patch 08)

## Device migration procedure

1. On the old phone, open Google Drive settings, choose **Encrypted full backup now**, and enter a strong recovery passphrase. Keep that passphrase in a password manager **outside your phone**.
2. Wait until THOOP reports that the uploaded encrypted backup was **downloaded, authenticated, and verified**. Never rely on an in-progress upload as your only backup.
3. On the new phone, install the same THOOP Android app and sign in to the same Google account. Authorize Google Drive again.
4. Choose **Restore from Google Drive**, select the **Encrypted** full backup, enter its passphrase, preview it, and confirm restore. Fully close and reopen THOOP afterward.
5. Verify the workout/sleep/HR history, daily metrics, food entries, and profile/display preferences before retiring the old phone.

## Backed up

- Entire main Room/SQLite health database, including imported WHOOP, Zepp, Garmin, activity and historical records that are present in that database.
- Entire separate Room/SQLite food intake database, including all saved entries.
- `settings.json` portable *whitelisted* profile and display settings already supported by THOOP.
- Manually created **full** Google Drive backups are passphrase-encrypted using authenticated `THOOPENC-v1` and verified by read-back from Drive.

## Not backed up / limitations

- Device-specific Bluetooth pairing, Google authentication, API credentials and external service access must be set up again on a new phone.
- Some app-private media and diagnostic files (e.g. profile photos, temporary images, raw debug dumps) and non-whitelisted preferences are **not** part of the current `.noopbak` contract. They must not be described as restored.
- Existing legacy `.noopbak` backups remain readable but did not contain the food database. The UI now labels them **Legacy health-only backup** when previewed.
- Scheduled automatic backups still run on the preexisting, **unencrypted** path, although the new full backup format includes the food DB. Keep automatic backup off if you require *only* encrypted cloud copies.
- This is backup and restore, not conflict-resolving two-way sync between two simultaneously active phones.
- Changing the app's Google OAuth identity, package identifier, or signing setup during later rebranding may affect access to the previous app-specific Google Drive folder. Verify migration *before* changing those identities.
- No local data retention cleanup or automatic data deletion is part of Patch 08.

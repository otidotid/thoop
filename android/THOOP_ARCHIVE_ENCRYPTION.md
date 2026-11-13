# THOOP archive encryption v1 — manual monthly archives

This is a THOOP fork-specific, optional Google Drive history feature. The user chooses to sign
in and manually press **Create & Verify Encrypted Archives**. The new `.noopenc` encrypted bytes
are uploaded to the user's private Google Drive app-data folder, downloaded, authenticated,
decrypted locally, and the archive database is checked for schema/row-count consistency.
**No local historical records are deleted or moved.** The background workers and backup
scheduler are unchanged; no passphrase is stored in preferences, logs, or Drive metadata.

`ArchiveEncryption.encrypt` and `decrypt` create separate files (without overwriting inputs).
A user-held recovery passphrase with at least 12 characters is required. The app asks the user
to confirm it before archive creation and re-enter it to preview an encrypted monthly archive.
Keep the recovery passphrase in a secure password manager **outside the app**. Using different
passphrases on different months is possible: users must retain each one. Losing a passphrase
means encrypted archives cannot be recovered. Neither a device-only key nor Google Sign-In
can recover that passphrase.

**Existing `.nooparc` archives remain unencrypted and readable as legacy archives.** They are
labeled as such in the browser; the app does not retroactively encrypt or replace them. The
separate `.noopbak` full-backup, scheduled folder-backup, and automated Google Drive backup
paths also remain **unencrypted**. The food-intake Room database is not yet included in the
monthly archives or full backups. Therefore do not treat cloud coverage or Tier 2 offload as
complete. The current monthly archiving calendar still uses its original 90-day threshold.

The fork-specific optional cloud read-back described here expands on upstream's offline-first
scope. It is explicitly user-triggered; online history is read-only, no network connection is
required for ordinary daily tracking, and future automated retention requires independent opt-in
and restore verification.

## Version 1 file format (`.noopenc`)

- Header: 8 ASCII bytes `THOOPENC`, 1 version byte (`01`), 4-byte big-endian PBKDF2 iteration
  count, 16-byte random salt, and 8-byte random nonce prefix.
- Derivation: PBKDF2-HMAC-SHA256, 256-bit AES key; default 600,000 iterations (minimum 200,000,
  maximum 1,200,000 for recovery). A new salt and nonce prefix are generated for each file.
- Data: at most 65,536 plaintext bytes per AES-256-GCM frame, with a 128-bit authentication tag.
  Each frame starts with 4-byte big-endian ciphertext length and a 1-byte frame flag.
- Nonce: the header's 8-byte prefix plus a 4-byte big-endian frame counter (starts at zero).
  AAD: entire header plus the 4-byte frame counter, 1-byte flag and 4-byte ciphertext length.
- The final frame has flag `01` and authenticates an empty plaintext. It is required even for
  empty files; truncation, reordering, corruption and trailing data are rejected.
- Decrypt writes plaintext into a temporary file alongside the chosen output path and renames
  it only after *all* authentication checks succeed; failure deletes the temporary file.
- The decoder rejects unsupported versions and out-of-range work factors. It does not accept
  old plaintext `.nooparc`/`.noopbak` files as encrypted inputs.

## Still required before enabling Tier 2 retention

1. Encrypt **all** export paths, especially `.noopbak` and the separate food database, and
   provide portable full restore on another phone. Existing legacy/plaintext copies must not
   be counted as encrypted or adequate for safe deletion.
2. Add cloud discovery/recovery for encrypted archives on a brand-new device without depending
   on the old device's local archive manifest.
3. Verify contents per-table and record identity, not just total row count/schema, and test a
   full restore on a clean installation before any local cleanup.
4. Guarantee interrupted archive operations do not retain plaintext in app-private scratch
   storage; current code deletes the cache on success/failure, but process death may leave
   incomplete files until the next archive operation.
5. Build snapshots, online detail reading for every supported data module, and explicit
   archive-to-retention mapping. No background process or retention deletion is enabled here.

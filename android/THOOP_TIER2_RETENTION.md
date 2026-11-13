# THOOP Android — Rolling hybrid history (automatic encrypted archives)

> **Never delete or offload source data.** All original records stay in the two local databases.
> Google Drive history is an additional encrypted copy, not the only place data exists.

## Calendar-day rolling window

| Age of record on this phone | Presentation tier | Data retention |
|---|---|---|
| Today–14 days ago | Full local detail | Original remains local |
| 15–21 days ago (exactly 7 days) | Compact daily snapshots (refreshed daily by WorkManager and on opening Backup & History) | Original remains local |
| 22+ days ago | Eligible for verified encrypted Google Drive rolling archive and online day preview | Original remains local |

The window advances by **one day every calendar day**. Example on November 13, 2026:
October 23–29 are the seven snapshot days; October 22 and earlier are cloud-eligible.
On November 14, October 23 becomes cloud-eligible. Each month archive can contain only
records up to the eligible cutoff date. Incomplete months are re-uploaded on the next
automatic daily WorkManager run when another day becomes eligible. Existing encrypted
files are never deleted; the local manifest updates only after a new archive is
uploaded, downloaded, decrypted and verified. After explicit one-time enrollment,
a working copy of the passphrase is encrypted with a non-exportable Android Keystore key
in the device's no-backup storage. The original passphrase is still required for recovery.

## Already wired in this patch

- `HybridHistoryPolicy.kt` centralizes the rolling day boundaries and cutoff tests.
- The existing local compact-snapshot builder refreshes when the Backup/History
  account screen opens, even without internet, and its summaries display without Drive authorization.
- `GoogleDriveArchiveManager` can now prepare verified encrypted *partial months* through
  day 22, and refresh those months when another day becomes eligible.
- The Backup/History card can preview exactly one eligible day via verified Drive archive,
  as well as open an entire archived month.
- All sources, app databases, full encrypted backups and existing scheduled backups are unchanged.

## Important remaining limitations

1. **Automatic encrypted Drive archives require one-time setup** after Google Sign-In and
   Drive authorization. Enter the EXISTING recovery passphrase once to securely enroll this
   phone. WorkManager performs daily catch-up, retries temporary failures, and reports
   pending/failed state. Android may delay jobs (Doze, offline, permissions); it does NOT
   guarantee a midnight upload. Background Drive authorization can require foreground
   consent renewal; status explicitly tells the user to reopen THOOP. No recurring sync button.
   Local snapshot refresh runs on a separate offline-capable daily schedule.
2. The current month-history archive contains the **main health database**. The separate food
   database is covered by the manual encrypted *full recovery backup*, but not this month archive.
3. This patch adds day-level browsing to **Backup & History**, not automatic cloud hydration
   inside *every* training, labs, journal or nutrition screen. Those other screens continue to
   read their complete local data (which is preserved).
4. Google Drive archive manifests remain stored locally. Cloud rediscovery on a newly
   initialized device is not included; the encrypted full recovery backup remains the
   supported disaster-recovery mechanism.
5. Repeated automatic partial-month refreshes create additional immutable encrypted Drive
   objects. Existing objects are not deleted. Expect additional Drive storage use.
6. A device-bound encryption key means unattended archives only run on this configured device.
   Keep your recovery passphrase outside THOOP. Google Sign-Out clears the local encrypted
   passphrase copy and cancels automatic cloud jobs; a new setup is then required.

This patch does not touch BLE, the health calculation algorithms, scheduled background
services, Google Drive full backup/restore behavior, or the v13.1.0 release tag.

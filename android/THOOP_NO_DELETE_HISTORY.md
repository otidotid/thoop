# THOOP fork: cloud history with full local copy preserved

Policy: **Never delete health, food, training, journal, or sensor database records as a result of the Tier 2 controls.** This change does not introduce any database DELETE statement, retention worker, sync side-effect, or scheduled cleanup. Existing independent pruning already in upstream repositories remains unchanged.

## Manual archive migration

In Google Drive > Browse Archived History, a `Legacy unencrypted` entry now offers **Encrypt a copy**. One selected month's existing `.nooparc` is downloaded and verified, encrypted with a user-chosen recovery passphrase, uploaded as a new `.noopenc`, downloaded and authenticated, extracted and checked before the local archive manifest is pointed to the new encrypted copy. The original Google Drive object is **not deleted** and its ID is stored in the manifest as `legacyDriveFileId`. Original on-device health records remain in place. Partial uploads can leave an unused encrypted object in Drive, but they cannot replace the manifest unless verification completes.

## Compact snapshots, not retention deletion

A separate manual **Refresh compact snapshots (days 15–21)** control generates a tiny app-private JSON file from the **current** local health and food databases. It counts dated rows for workouts, sleep, mood/metricSeries, journal, labs, coach, lifts and other supported event tables; dailyMetric contributes sleep/HRV/recovery/steps and food contributes calories by source. Source IDs remain separate. Large raw sensor rows are **not copied** into the compact JSON, but stay in their original Room tables. No background task is added. The JSON may be regenerated; it is not itself an independent recovery backup.

## Historical cloud read-back

Selecting a monthly archive downloads it, checks byte size and SHA-256, decrypts if necessary, validates schema and total row count, and now shows bounded read-only examples of daily metrics, workout sessions, sleep sessions, and metricSeries points. The table count list still covers the archived tables. This is a History Browser read path, **not** a replacement for every app module's regular history screen. Existing archives do not contain the separate `thoop_food_intake.db` database. Full `.noopbak` backups are also separate and remain unencrypted in this version. A clean-device cloud archive index and full history restore still require separate engineering before depending on cloud data as the only copy.

## Important storage expectation

Because all original records remain, this does not reduce phone database size. `RetentionPlan.Band.ONLINE_ONLY` is treated as a *future UI retrieval hint* rather than an authorization to purge data. Only disposable cache copies created for manual archive operations are removed. The existing WHOOP raw waveform newest-N cap and any pre-existing source-specific cleanup logic are **not changed by this patch**.

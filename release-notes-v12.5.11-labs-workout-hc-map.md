# THOOP v12.5.11 Labs, Workout Import & GPS Route

## What's new

- Added full editing for custom Labs markers, including title, value, unit, date, reference range, note, and test condition.
- Preserved marker history when a custom marker is renamed.
- Added fasting, non-fasting, post-meal, and unknown sample conditions.
- Added conservative non-fasting glucose interpretation for THOOP Age.
- A 90 mg/dL non-fasting glucose reading remains neutral and is not evaluatedsting thresholds.
- Prevented glycaemic double-counting when fasting glucose or HbA1c is available.
- Improved custom marker names, category spacing, and Labs card layout.
- Hidden flat sparklines when readings contain no real numerical change.
- Replaced Health Connect import choices with Workout Only and Workout + Activity.
- Workout Only imports exercise sessions, distance, and GPS route when available.
- Workout + Activity additionally imports supported steps and calorie activity.
- Added an emerald GPS route card with start and finish markers to Workout Detail.
- Improved Health Connect Share Back retry messaging.

## Safety

- Home and Today layout were not changed.
- Dock implementation, order, customization, and Settings controls were not changed.
- Today heart-rate chart was not changed.
- Google Drive backup, restore, archive, and history were not changed.
- Health Connect remains restricted by the existing import allowlist.
- Health Connect imports are not written back as a synchronization loop.
- Existing Labs history and raw workout route data remain preserved.

## Validation

- CustomLabAgePolicyTest: PASS
- HealthConnectWorkoutScopeTest: PASS
- HealthConnectRouteImportTest: PASS
- LabMarkerV42MigrationTest: PASS
- compileFullDebugKotlin: PASS
- assembleFullDebug: PASS
- APK update installation: PASS
- Labs full-edit smoke test: PASS
- THOOP AI Coach glucose-context smoke test: PASS
- Health Connect mode smoke test: PASS
- Labs visual cleanup smoke test: PASS
- Home and dock regression smoke test: PASS

Release commit: 9cd95393

# THOOP v12.5.12 Workout HR & Calories Reconciliation

## What's new

- Reconciles Health Connect workouts with raw THOOP heart-rate data from the exact workout interval.
- Calculates workout average HR and peak HR from the THOOP device.
- Calculates workout calories with the local THOOP bout-calorie engine.
- Calculates workout Effort with the local THOOP strain engine.
- Rejects impossible external workout calories such as 956 kcal for a two-minute session.
- Reprocesses older Health Connect workouts when the Workouts list is loaded.
- Displays Pending instead of synthetic HR or calories when THOOP HR has not finished syncing.
- Keeps Health Connect authoritative only for sport, timestamps, duration, distance, elevation, and GPS route.
- Keeps manual edits as the highest-priority workout values.

## Calories safety

- Daily Total Calories remains authoritative and unchanged.
- Workout calories are an interval breakdown only.
- Workout calories are not added again to daily Active or Total Calories.
- Health Connect calories are not used as final workout calories.
- TotalCaloriesEngine was not changed.

## Included improvements

- Full custom Labs marker editing.
- Non-fasting glucose support in THOOP Age.
- Workout Only and Workout + Activity Health Connect modes.
- GPS route import and route visualization.
- Labs card spacing and sparkline cleanup.

## Scope protection

- Home and Today layout were not changed.
- Dock implementation, order, customization, and Settings controls were not changed.
- Today heart-rate chart was not changed.
- Google Drive backup, restore, archive, and history were not changed.

## Validation

- HealthConnectWorkoutReconcilerTest: PASS
- DayCaloriesTest: PASS
- TotalCaloriesEngineTest: PASS
- HealthConnectWorkoutScopeTest: PASS
- CustomLabAgePolicyTest: PASS
- HealthConnectRouteImportTest: PASS
- LabMarkerV42MigrationTest: PASS
- compileFullDebugKotlin: PASS
- assembleFullDebug: PASS
- APK update installation: PASS

Release commit: d75268d3

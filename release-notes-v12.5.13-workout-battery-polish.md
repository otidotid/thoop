# THOOP v12.5.13 Workout & Battery Polish

## Workout reconciliation

- Reconciles Health Connect workouts with raw THOOP heart-rate data from the exact workout.
- Calculates workout average HR and peak HR from THOOP data.
- Calculates workout calories with the local THOOP bout-calorie engine.
- Calculates workout Effort through the local strain engine when the session is eligible.
- Replaces invalid external calories, including impossible values from short sessions.
- Displays Pending instead of synthetic HR or calories while THOOP HR is still syncing.
- Reprocesses existing Health Connect workouts when the Workouts list loads.
- Keeps Health Connect authoritative only for session identity, timestamps, sport, duration, distance, elevation, and GPS route.

## Total Calories safety

- Daily Total Calories remains authoritative and unchanged.
- Workout calories are an interval breakdown only.
- Workout calories are not added again to daily Active or Total Calories.
- TotalCaloriesEngine was not changed.
- Manual workout edits remain the highest-priority values.

## Labs polish

- Added consistent spacing between Body marker cards.
- Added matching spacing between the Custom heading and custom marker cards.
- New custom markers automatically inherit the same spacing.
- No Labs data or THOOP Age algorithm was changed by this visual polish.

## Battery polish

- Battery ring is green at 80–100%.
- Battery ring is amber at 26–79%.
- Battery ring is red at 0–25%.
- Added Power Saving activation thresholds at 15%, 30%, 45%, 60%, 75%, and 90%.
- Legacy threshold values migrate to the nearest supported option.
- BLE battery decoding, percentage, voltage, polling, charging state, and charging animation were not changed.

## Scope protection

- Home layout was not changed.
- Dock implementation, order, customization, and Settings controls were not changed.
- Today heart-rate chart was not changed.
- Google Drive backup, restore, archive, and history were not changed.
- Health algorithms outside workout reconciliation were not changed.

## Validation

- HealthConnectWorkoutReconcilerTest: PASS
- DayCaloriesTest: PASS
- TotalCaloriesEngineTest: PASS
- HealthConnectWorkoutScopeTest: PASS
- PowerSavingThresholdTest: PASS
- compileFullDebugKotlin: PASS
- assembleFullDebug: PASS
- BottomBarTabsTest restored unchanged after targeted validation
- Power Saving UI smoke test: PASS

Release commit: 345d4fb5

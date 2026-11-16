#!/usr/bin/env bash
# Only runs in an isolated GitHub Actions Android emulator. No release or production changes.
set -uo pipefail
adb shell settings put secure show_ime_with_hard_keyboard 1
cd android || exit 1
bash ./gradlew connectedFullDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.noop.ui.CoachImeGeometryTest
result=$?
cd .. || exit 1
adb logcat -d -s CoachIME:I > coach-ime-measurements.txt || true
adb pull /sdcard/Android/data/com.otidotid.thoop.debug/files/coach-ime-open.png coach-ime-open.png || true
exit "$result"

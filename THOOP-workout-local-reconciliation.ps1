$ErrorActionPreference = "Stop"
$root = "C:\Projects\thoop-recovery"
Set-Location $root
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-Utf8([string]$relative) {
    [IO.File]::ReadAllText((Join-Path $root $relative), [Text.Encoding]::UTF8)
}
function Write-Utf8([string]$relative, [string]$content) {
    $path = Join-Path $root $relative
    New-Item -ItemType Directory -Force (Split-Path $path) | Out-Null
    [IO.File]::WriteAllText($path, $content, $utf8)
}
function Replace-Once([string]$text, [string]$old, [string]$new, [string]$label) {
    $count = ([regex]::Matches($text, [regex]::Escape($old))).Count
    if ($count -ne 1) { throw "$label anchor count was $count, expected 1" }
    $text.Replace($old, $new)
}

$vm = "android\app\src\main\java\com\noop\ui\AppViewModel.kt"
$screen = "android\app\src\main\java\com\noop\ui\WorkoutsScreen.kt"
$engine = "android\app\src\main\java\com\noop\analytics\HealthConnectWorkoutReconciler.kt"
$test = "android\app\src\test\java\com\noop\analytics\HealthConnectWorkoutReconcilerTest.kt"

if (Test-Path (Join-Path $root $engine)) { throw "Reconciler already exists; stop to avoid double application" }

@'
package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import kotlin.math.roundToInt

/**
 * Reconciles an imported Health Connect workout against the THOOP strap HR trace.
 *
 * Health Connect remains authoritative only for session identity, time, sport, distance and route.
 * HR, workout calories and Effort are local THOOP values. With fewer than two strap samples the
 * session stays pending; no external or synthetic metric is shown.
 *
 * Workout calories are an interval breakdown only. They are never added to DailyMetric active
 * calories, whose whole-day raw-HR pipeline already includes the same interval exactly once.
 */
object HealthConnectWorkoutReconciler {
    const val sourceId = "health-connect"

    fun reconcile(
        row: WorkoutRow,
        samples: List<HrSample>,
        profile: UserProfile,
        maxHr: Double,
        restingHr: Double?,
        effortMethod: StrainScorer.Method,
        sex: String,
    ): WorkoutRow {
        if (!row.source.equals(sourceId, ignoreCase = true)) return row

        val window = samples
            .asSequence()
            .filter { it.ts in row.startTs..row.endTs && it.bpm > 0 }
            .sortedBy { it.ts }
            .toList()

        if (window.size < 2) {
            return row.copy(avgHr = null, maxHr = null, energyKcal = null, strain = null)
        }

        val kcal = Calories.estimateBoutCalories(
            hr = window,
            profile = profile,
            hrmax = maxHr,
            restingHR = restingHr,
        ).first.takeIf { it.isFinite() && it > 0.0 }

        val effort = StrainScorer.strain(
            samples = window,
            maxHR = maxHr,
            restingHR = restingHr ?: StrainScorer.defaultRestingHR,
            method = effortMethod,
            sex = sex,
        )

        return row.copy(
            avgHr = window.map { it.bpm }.average().roundToInt(),
            maxHr = window.maxOf { it.bpm },
            energyKcal = kcal,
            strain = effort,
        )
    }
}
'@ | ForEach-Object { Write-Utf8 $engine $_ }

@'
package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthConnectWorkoutReconcilerTest {
    private val profile = UserProfile(weightKg = 72.0, heightCm = 174.0, age = 40.0, sex = "male")
    private fun row(source: String = "health-connect") = WorkoutRow(
        deviceId = "health-connect", startTs = 100, endTs = 220, sport = "Running", source = source,
        durationS = 120.0, energyKcal = 956.0, avgHr = 118, maxHr = 130, strain = 10.0,
    )

    @Test fun healthConnectMetricsAreReplacedByLocalStrapMetrics() {
        val samples = (100L..220L).map { HrSample("my-whoop", it, if (it < 160) 130 else 150) }
        val out = HealthConnectWorkoutReconciler.reconcile(
            row(), samples, profile, 185.0, 55.0, StrainScorer.Method.EDWARDS, "male")
        assertEquals(140, out.avgHr)
        assertEquals(150, out.maxHr)
        requireNotNull(out.energyKcal)
        require(out.energyKcal!! < 956.0)
        requireNotNull(out.strain)
    }

    @Test fun missingStrapHrBecomesPendingInsteadOfKeepingExternalOrEstimatedValues() {
        val out = HealthConnectWorkoutReconciler.reconcile(
            row(), emptyList(), profile, 185.0, 55.0, StrainScorer.Method.EDWARDS, "male")
        assertNull(out.avgHr)
        assertNull(out.maxHr)
        assertNull(out.energyKcal)
        assertNull(out.strain)
    }

    @Test fun nonHealthConnectWorkoutIsUntouched() {
        val original = row(source = "manual")
        val out = HealthConnectWorkoutReconciler.reconcile(
            original, emptyList(), profile, 185.0, 55.0, StrainScorer.Method.EDWARDS, "male")
        assertEquals(original, out)
    }
}
'@ | ForEach-Object { Write-Utf8 $test $_ }

# AppViewModel: import the pure reconciler.
$text = Read-Utf8 $vm
$text = Replace-Once $text `
    "import com.noop.analytics.IntelligencePersistence" `
    "import com.noop.analytics.IntelligencePersistence`nimport com.noop.analytics.HealthConnectWorkoutReconciler" `
    "AppViewModel import"

$old = @'
            val filled = repository.fillWorkoutHrFromStrap(
                (whoop + apple + detected + activityFiles),
                strapDeviceId = deviceId,
                strainMaxHR = profileStore.hrMax.toDouble(),
                strainSex = profileStore.sex,
                effortMethod = NoopPrefs.effortMethod(appContext),
            )
'@
$new = $old + @'
            // Health Connect contributes the session boundary, sport, distance and route only.
            // Re-resolve HR, calories and Effort from the THOOP strap window every time workouts load.
            // This self-heals old imports (including impossible external kcal) after HR offload/sync.
            val locallyReconciled = filled.map { row ->
                if (!row.source.equals(HealthConnectWorkoutReconciler.sourceId, ignoreCase = true)) {
                    row
                } else {
                    val ids = WhoopRepository.workoutHrDeviceIds(row.source, row.deviceId, deviceId)
                    val samples = runCatching {
                        repository.hrSamplesFor(ids, row.startTs, row.endTs, limit = 8_000)
                    }.getOrDefault(emptyList())
                    HealthConnectWorkoutReconciler.reconcile(
                        row = row,
                        samples = samples,
                        profile = currentProfile(),
                        maxHr = profileStore.hrMax.toDouble(),
                        restingHr = _today.value?.restingHr?.toDouble(),
                        effortMethod = NoopPrefs.effortMethod(appContext),
                        sex = profileStore.sex,
                    )
                }
            }
'@
$text = Replace-Once $text $old $new "AppViewModel reconciliation block"
$text = Replace-Once $text `
    "val filteredRows = WorkoutEditing.filterDismissed(filled + lifting, markers)" `
    "val filteredRows = WorkoutEditing.filterDismissed(locallyReconciled + lifting, markers)" `
    "AppViewModel reconciled list"
Write-Utf8 $vm $text

# Workouts list: never synthesize Health Connect HR/kcal. Show Pending until strap HR is available.
$text = Read-Utf8 $screen
$old = @'
          Cell(
              if (row.avgHr != null) resolvedAvgHr.toString() else "~$resolvedAvgHr",
              Modifier.weight(1.1f),
              color = Palette.metricRose,
          )
          Cell(
              if (row.energyKcal != null) grouped(resolvedCalories) else "~${grouped(resolvedCalories)}",
              Modifier.weight(1f),
              color = Palette.metricAmber,
          )
'@
$new = @'
          val healthConnectPending = row.source.equals("health-connect", ignoreCase = true)
          Cell(
              when {
                  row.avgHr != null -> resolvedAvgHr.toString()
                  healthConnectPending -> "Pending"
                  else -> "~$resolvedAvgHr"
              },
              Modifier.weight(1.1f),
              color = Palette.metricRose,
          )
          Cell(
              when {
                  row.energyKcal != null -> grouped(resolvedCalories)
                  healthConnectPending -> "Pending"
                  else -> "~${grouped(resolvedCalories)}"
              },
              Modifier.weight(1f),
              color = Palette.metricAmber,
          )
'@
$text = Replace-Once $text $old $new "Workouts pending display"
Write-Utf8 $screen $text

# Scope protection. This patch must not alter daily calories or unrelated product surfaces.
$protected = git diff --name-only | Where-Object {
    $_ -match "AppRoot.kt|TodayScreen.kt|SettingsScreen.kt|BottomBarTabsTest.kt|HeartRate|TotalCaloriesEngine.kt"
}
if ($protected) { throw "Protected file changed: $($protected -join ', ')" }

git diff --check -- $vm $screen $engine $test
if ($LASTEXITCODE -ne 0) { throw "git diff --check failed" }

Write-Host "Workout local reconciliation patch applied." -ForegroundColor Green
Write-Host "Total Calories daily pipeline was not changed; workout calories remain an interval breakdown only." -ForegroundColor Cyan
git diff --stat -- $vm $screen $engine $test

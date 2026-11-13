$ErrorActionPreference = "Stop"
$root = "C:\Projects\thoop-recovery"
Set-Location $root
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-Normalized([string]$relative) {
    $p = Join-Path $root $relative
    if (-not (Test-Path $p)) { throw "Missing source: $relative" }
    ([IO.File]::ReadAllText($p, [Text.Encoding]::UTF8)).Replace("`r`n", "`n")
}
function Write-Utf8([string]$relative, [string]$content) {
    [IO.File]::WriteAllText((Join-Path $root $relative), $content, $utf8)
}

$vm = "android\app\src\main\java\com\noop\ui\AppViewModel.kt"
$screen = "android\app\src\main\java\com\noop\ui\WorkoutsScreen.kt"
$engine = "android\app\src\main\java\com\noop\analytics\HealthConnectWorkoutReconciler.kt"
$test = "android\app\src\test\java\com\noop\analytics\HealthConnectWorkoutReconcilerTest.kt"

if (-not (Test-Path (Join-Path $root $engine))) { throw "Reconciler file from v1 was not created" }
if (-not (Test-Path (Join-Path $root $test))) { throw "Reconciler test from v1 was not created" }

# Patch AppViewModel using bounded regex instead of an exact multiline anchor.
$text = Read-Normalized $vm
if (-not $text.Contains("val locallyReconciled = filled.map")) {
    $pattern = '(?ms)(\s+val filled = repository\.fillWorkoutHrFromStrap\(.*?\n\s+\))'
    $matches = [regex]::Matches($text, $pattern)
    if ($matches.Count -ne 1) { throw "fillWorkoutHrFromStrap block count was $($matches.Count), expected 1" }

    $addition = @'

            // Health Connect contributes session identity, time, sport, distance and route only.
            // Re-resolve HR, calories and Effort from the THOOP strap window every time workouts load.
            // This self-heals old imports, including impossible external kcal, after HR offload/sync.
            val locallyReconciled = filled.map { row ->
                if (!row.source.equals(
                        com.noop.analytics.HealthConnectWorkoutReconciler.sourceId,
                        ignoreCase = true,
                    )
                ) {
                    row
                } else {
                    val ids = WhoopRepository.workoutHrDeviceIds(row.source, row.deviceId, deviceId)
                    val samples = runCatching {
                        repository.hrSamplesFor(ids, row.startTs, row.endTs, limit = 8_000)
                    }.getOrDefault(emptyList())
                    com.noop.analytics.HealthConnectWorkoutReconciler.reconcile(
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
    $text = [regex]::Replace($text, $pattern, ('$1' + $addition), 1)
}

$oldFilter = 'WorkoutEditing.filterDismissed(filled + lifting, markers)'
$newFilter = 'WorkoutEditing.filterDismissed(locallyReconciled + lifting, markers)'
if ($text.Contains($oldFilter)) {
    $text = $text.Replace($oldFilter, $newFilter)
} elseif (-not $text.Contains($newFilter)) {
    throw "Workout filtered-list anchor not found"
}
Write-Utf8 $vm $text

# Health Connect rows must never show synthetic fallback HR/kcal.
$text = Read-Normalized $screen
if (-not $text.Contains("val healthConnectPending = row.source.equals")) {
    $pattern = '(?ms)\s+Cell\(\n\s+if \(row\.avgHr != null\) resolvedAvgHr\.toString\(\) else "~\$resolvedAvgHr",\n\s+Modifier\.weight\(1\.1f\),\n\s+color = Palette\.metricRose,\n\s+\)\n\s+Cell\(\n\s+if \(row\.energyKcal != null\) grouped\(resolvedCalories\) else "~\$\{grouped\(resolvedCalories\)\}",\n\s+Modifier\.weight\(1f\),\n\s+color = Palette\.metricAmber,\n\s+\)'
    $matches = [regex]::Matches($text, $pattern)
    if ($matches.Count -ne 1) { throw "Workout HR/kcal display block count was $($matches.Count), expected 1" }
    $replacement = @'
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
    $text = [regex]::Replace($text, $pattern, $replacement.TrimEnd(), 1)
}
Write-Utf8 $screen $text

$protected = git diff --name-only | Where-Object {
    $_ -match "AppRoot.kt|TodayScreen.kt|SettingsScreen.kt|BottomBarTabsTest.kt|HeartRate|TotalCaloriesEngine.kt"
}
if ($protected) { throw "Protected file changed: $($protected -join ', ')" }

git diff --check -- $vm $screen $engine $test
if ($LASTEXITCODE -ne 0) { throw "git diff --check failed" }

Write-Host "Workout local reconciliation v2 applied." -ForegroundColor Green
Write-Host "Daily Total Calories pipeline remains unchanged." -ForegroundColor Cyan
git diff --stat -- $vm $screen $engine $test

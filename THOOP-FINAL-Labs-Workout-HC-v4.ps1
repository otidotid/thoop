$ErrorActionPreference = "Stop"
$root = "C:\Projects\thoop-recovery"
Set-Location $root
$utf8 = New-Object System.Text.UTF8Encoding($false)
function Read-Utf8File([string]$p){
  $content=[IO.File]::ReadAllText((Join-Path $root $p),[Text.Encoding]::UTF8)
  $content.Replace("`r`n","`n")
}
function Write-Utf8File([string]$p,[string]$s){[IO.File]::WriteAllText((Join-Path $root $p),$s,$utf8)}
function One([string]$s,[string]$a,[string]$b,[string]$n){$c=([regex]::Matches($s,[regex]::Escape($a))).Count;if($c-ne 1){throw "$n anchor $c"};$s.Replace($a,$b)}

# Guard: validated Labs work must already exist; obsolete final v1 must not have been applied.
if(-not(Test-Path "android\app\src\main\java\com\noop\ui\CustomMarkerEditScreen.kt")){throw "Validated Labs editor missing"}
if(Select-String -Path "android\app\src\main\java\com\noop\ingest\HealthConnectImporter.kt" -SimpleMatch "Legacy enum/storage keys are retained" -Quiet){throw "Old final patch appears applied. Stop."}

$imp="android\app\src\main\java\com\noop\ingest\HealthConnectImporter.kt";$s=Read-Utf8File $imp
$old=@'
        ACTIVITY_ONLY(
            "activity-only",
            setOf(
                StepsRecord::class,
                TotalCaloriesBurnedRecord::class,
                ActiveCaloriesBurnedRecord::class,
            ),
        ),
        ALL(
            "all",
            setOf(
                StepsRecord::class,
                TotalCaloriesBurnedRecord::class,
                ActiveCaloriesBurnedRecord::class,
                ExerciseSessionRecord::class,
                DistanceRecord::class,
            ),
        ),
'@
$new=@'
        // Storage keys remain unchanged for backward compatibility.
        // ACTIVITY_ONLY now means Workout Only; ALL means Workout + Activity.
        ACTIVITY_ONLY(
            "activity-only",
            setOf(ExerciseSessionRecord::class, DistanceRecord::class),
        ),
        ALL(
            "all",
            setOf(
                ExerciseSessionRecord::class,
                DistanceRecord::class,
                StepsRecord::class,
                TotalCaloriesBurnedRecord::class,
                ActiveCaloriesBurnedRecord::class,
            ),
        ),
'@
$pattern='(?ms)^        ACTIVITY_ONLY\(.*?^        \),\n        ALL\(.*?^        \),'
$matches=[regex]::Matches($s,$pattern)
if($matches.Count -ne 1){throw "HC modes regex count $($matches.Count)"}
$s=[regex]::Replace($s,$pattern,$new.TrimEnd("`r","`n"),1)
Write-Utf8File $imp $s

$sel="android\app\src\main\java\com\noop\ui\HealthConnectCategorySelector.kt";$s=Read-Utf8File $sel
$s=$s.Replace('label = { if (it == ImportCategory.ACTIVITY_ONLY) "Activity only" else "Activity + workouts" }','label = { if (it == ImportCategory.ACTIVITY_ONLY) "Workout Only" else "Workout + Activity" }');Write-Utf8File $sel $s

$data="android\app\src\main\java\com\noop\ui\DataSourcesScreen.kt";$s=Read-Utf8File $data
$s=One $s 'subtitle = "Manual import only. Activity only imports steps and calories; All also imports " +
                "exercise sessions and their distance. Sleep, HR, HRV, SpO₂, VO₂ max and body " +
                "composition are never read.",' 'subtitle = "Manual import only. Workout Only imports exercise sessions, distance and GPS route when available. " +
                "Workout + Activity also imports supported steps and calorie activity. Sleep, HR, HRV, SpO₂, " +
                "VO₂ max and body composition are never read.",' "HC copy"
$s=$s.Replace('uiString(R.string.l10n_data_sources_screen_sharing_paused_health_connect_permission_was_e8950315),','"Last share was paused. Tap to re-check write access and retry.",');Write-Utf8File $data $s

$edit="android\app\src\main\java\com\noop\ui\CustomMarkerEditScreen.kt";$s=Read-Utf8File $edit
$s=One $s 'val evidenceId = if (condition == "non_fasting" || condition == "post_meal") "glycemic_non_fasting" else null' 'val isGlucose = name.contains("glucose", ignoreCase = true)
                val evidenceId = if (isGlucose && (condition == "non_fasting" || condition == "post_meal")) "glycemic_non_fasting" else null' "glucose guard";Write-Utf8File $edit $s

# Repository-backed historical THOOP Age. Use the Labs source constant and suppress custom delta
# whenever stronger glycaemic evidence (fasting glucose or HbA1c) exists on/before the snapshot.
$eng="android\app\src\main\java\com\noop\analytics\IntelligenceEngine.kt";$s=Read-Utf8File $eng
$start=$s.IndexOf('    suspend fun fitnessAgeRows(repo: WhoopRepository,');if($start-lt 0){throw "fitnessAgeRows(repo) missing"}
$a='        val labBodyFat = latestLab("body_fat")';$p=$s.IndexOf($a,$start);if($p-lt 0){throw "Labs anchor missing"}
$block=@'

        val hasPrimaryGlycemic = latestLab("fasting_glucose") != null || latestLab("hba1c") != null
        val customGlycemic = if (hasPrimaryGlycemic) null else
            repo.labMarkersByCategory(WhoopDao.LAB_BOOK_SOURCE_ID, "other")
                .asSequence()
                .filter { it.day <= satKey && it.evidenceProfileId == "glycemic_non_fasting" && it.value != null }
                .maxByOrNull { it.takenAt }
        val customGlycemicDelta = customGlycemic?.let { reading ->
            val condition = when (reading.sampleCondition) {
                "non_fasting" -> CustomLabAgePolicy.SampleCondition.NON_FASTING
                "post_meal" -> CustomLabAgePolicy.SampleCondition.POST_MEAL
                else -> CustomLabAgePolicy.SampleCondition.UNKNOWN
            }
            CustomLabAgePolicy.nonFastingGlucose(
                CustomLabAgePolicy.Reading(reading.value!!, reading.unit, java.time.LocalDate.parse(reading.day), condition, reading.hoursSinceMeal),
                java.time.LocalDate.parse(satKey),
            ).takeIf { it.eligible }?.ageDeltaYears
        } ?: 0.0
'@
$s=$s.Insert($p+$a.Length,$block)
$row='        val rows = mutableListOf(MetricSeriesRow(deviceId = computedId, day = satKey, key = "fitness_age", value = res.fitnessAge))';$rp=$s.IndexOf($row,$p);if($rp-lt 0){throw "fitness row missing"}
$s=$s.Remove($rp,$row.Length).Insert($rp,'        val rows = mutableListOf(MetricSeriesRow(deviceId = computedId, day = satKey, key = "fitness_age", value = res.fitnessAge + customGlycemicDelta))')
Write-Utf8File $eng $s

# Premium route visual in Workout Detail: clipped dark card, emerald polyline, start/finish markers.
$work="android\app\src\main\java\com\noop\ui\WorkoutsScreen.kt";$s=Read-Utf8File $work
$s=One $s 'import com.noop.analytics.WorkoutSport' 'import com.noop.analytics.WorkoutSport
import com.noop.analytics.RouteMath' "RouteMath import"
$a='        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())'
$p=$s.IndexOf($a,$s.IndexOf('internal fun WorkoutDetailSheet'));if($p-lt 0){throw "Workout detail column missing"}
$close=$s.IndexOf('{',$p)+1
$insert=@'

            row.routePolyline?.takeIf { it.isNotBlank() }?.let { encoded ->
                val route = remember(encoded) { runCatching { RouteMath.decode(encoded) }.getOrDefault(emptyList()) }
                if (route.size >= 2) {
                    NoopCard {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("Route", style = NoopType.headline, color = Palette.textPrimary)
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(210.dp)
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(Palette.surfaceRaised)
                                    .semantics { contentDescription = "Workout GPS route map" },
                            ) {
                                val pts = RouteMath.normalizeToBox(route, size.width, size.height, 20f)
                                if (pts.size >= 2) {
                                    val path = androidx.compose.ui.graphics.Path().apply {
                                        moveTo(pts.first().first, pts.first().second)
                                        pts.drop(1).forEach { lineTo(it.first, it.second) }
                                    }
                                    drawPath(path, Palette.accent, style = Stroke(width = 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                                    drawCircle(Palette.textPrimary, 9f, androidx.compose.ui.geometry.Offset(pts.first().first, pts.first().second))
                                    drawCircle(Palette.accent, 11f, androidx.compose.ui.geometry.Offset(pts.last().first, pts.last().second))
                                }
                            }
                        }
                    }
                }
            }
'@
$s=$s.Insert($close,$insert);Write-Utf8File $work $s

# Tests: migration pair, HC scopes, and historical policy.
$mt="android\app\src\test\java\com\noop\data\LabMarkerV42MigrationTest.kt"
@'
package com.noop.data
import org.junit.Assert.assertEquals
import org.junit.Test
class LabMarkerV42MigrationTest {
 @Test fun migrationPairIs41To42(){ assertEquals(41, WhoopDatabase.MIGRATION_41_42.startVersion); assertEquals(42, WhoopDatabase.MIGRATION_41_42.endVersion) }
}
'@|%{Write-Utf8File $mt $_}
$ht="android\app\src\test\java\com\noop\ingest\HealthConnectWorkoutScopeTest.kt"
@'
package com.noop.ingest
import androidx.health.connect.client.records.*
import org.junit.Assert.*
import org.junit.Test
class HealthConnectWorkoutScopeTest {
 @Test fun workoutOnly(){val t=HealthConnectImporter.ImportCategory.ACTIVITY_ONLY.recordTypes;assertTrue(ExerciseSessionRecord::class in t);assertTrue(DistanceRecord::class in t);assertFalse(StepsRecord::class in t)}
 @Test fun workoutPlusActivity(){val t=HealthConnectImporter.ImportCategory.ALL.recordTypes;assertTrue(ExerciseSessionRecord::class in t);assertTrue(StepsRecord::class in t)}
}
'@|%{Write-Utf8File $ht $_}

if(-not(Select-String -Path $imp -SimpleMatch "ExerciseRouteResult.Data" -Quiet)){throw "Route import missing"}
$bad=git diff --name-only|?{$_ -match "AppRoot.kt|TodayScreen.kt|SettingsScreen.kt|HeartRate"};if($bad){throw "Protected UI changed: $bad"}
git diff --check;if($LASTEXITCODE-ne 0){throw "diff check failed"}
Write-Host "FINAL v2 applied." -ForegroundColor Green
Write-Host "Home, permanent dock, Today HR, Settings, backup and archive were not changed." -ForegroundColor Cyan

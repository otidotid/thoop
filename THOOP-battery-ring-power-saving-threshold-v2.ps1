$ErrorActionPreference = "Stop"
$root = "C:\Projects\thoop-recovery"
Set-Location $root
$utf8 = New-Object System.Text.UTF8Encoding($false)

function Read-Utf8([string]$relative) {
    $path = Join-Path $root $relative
    if (-not (Test-Path $path)) { throw "Missing source: $relative" }
    ([IO.File]::ReadAllText($path, [Text.Encoding]::UTF8)).Replace("`r`n", "`n")
}
function Write-Utf8([string]$relative, [string]$content) {
    [IO.File]::WriteAllText((Join-Path $root $relative), $content, $utf8)
}
function Replace-Once([string]$text, [string]$old, [string]$new, [string]$label) {
    $count = ([regex]::Matches($text, [regex]::Escape($old))).Count
    if ($count -ne 1) { throw "$label anchor count was $count, expected 1" }
    $text.Replace($old, $new)
}

$today = "android\app\src\main\java\com\noop\ui\TodayScreen.kt"
$power = "android\app\src\main\java\com\noop\ui\PowerSavingScreen.kt"
$test = "android\app\src\test\java\com\noop\ui\PowerSavingThresholdTest.kt"

# 1. Home battery ring: <=25 red, 26-79 amber, >=80 green.
$text = Read-Utf8 $today
$old = @'
            val ringColor = when {
                pct < 15 -> Palette.statusCritical
                pct < 35 -> Palette.statusWarning
                else -> Palette.chargeColor
            }
'@
$new = @'
            val ringColor = when {
                pct <= 25.0 -> Palette.statusCritical
                pct < 80.0 -> Palette.statusWarning
                else -> Palette.chargeColor
            }
'@
$text = Replace-Once $text $old $new "Home battery ring thresholds"
Write-Utf8 $today $text

# 2. Power Saving selector: six exact 15% steps from 15 to 90.
$text = Read-Utf8 $power
$text = Replace-Once $text `
    "import androidx.compose.runtime.Composable`n" `
    "import androidx.compose.runtime.Composable`nimport androidx.compose.runtime.LaunchedEffect`n" `
    "LaunchedEffect import"

$text = Replace-Once $text `
    "    var powerSavingBatteryPct by remember { mutableStateOf(NoopPrefs.powerSavingBatteryPct(context)) }" `
    "    val storedPowerSavingBatteryPct = remember { NoopPrefs.powerSavingBatteryPct(context) }`n    var powerSavingBatteryPct by remember { mutableStateOf(normalizePowerSavingThreshold(storedPowerSavingBatteryPct)) }`n`n    LaunchedEffect(Unit) {`n        if (storedPowerSavingBatteryPct != powerSavingBatteryPct) {`n            vm.setPowerSavingBatteryPct(powerSavingBatteryPct)`n        }`n    }" `
    "Power Saving threshold state"

$oldSlider = @'
                Slider(
                    value = powerSavingBatteryPct.toFloat(),
                    // 1035% snapping to 5% steps (10/15/20/25/30/35). steps = the 4 stops BETWEEN ends.
            // 35 buys one more step of strap life than the old 30 ceiling: the levers engage ~5%
            // earlier in the discharge, at the cost of a slightly longer stretch of quieter syncing.
                    onValueChange = { powerSavingBatteryPct = it.roundToInt() },
                    onValueChangeFinished = { vm.setPowerSavingBatteryPct(powerSavingBatteryPct) },
                    valueRange = 10f..35f,
                    steps = 4,
'@
# Source contains an en dash in the comment. Use regex bounded by Slider and colors instead.
$pattern = '(?ms)                Slider\(\r?\n                    value = powerSavingBatteryPct\.toFloat\(\),.*?                    steps = 4,\r?\n'
$matches = [regex]::Matches($text, $pattern)
if ($matches.Count -ne 1) { throw "Power Saving slider block count was $($matches.Count), expected 1" }
$newSlider = @'
                Slider(
                    value = powerSavingBatteryPct.toFloat(),
                    // Six exact activation thresholds: 15, 30, 45, 60, 75 and 90 percent.
                    onValueChange = { powerSavingBatteryPct = normalizePowerSavingThreshold(it.roundToInt()) },
                    onValueChangeFinished = { vm.setPowerSavingBatteryPct(powerSavingBatteryPct) },
                    valueRange = 15f..90f,
                    steps = 4,
'@
$text = [regex]::Replace($text, $pattern, $newSlider, 1)

$helper = @'

internal val powerSavingThresholds: List<Int> = listOf(15, 30, 45, 60, 75, 90)

internal fun normalizePowerSavingThreshold(value: Int): Int =
    powerSavingThresholds.minByOrNull { kotlin.math.abs(it - value) } ?: 30
'@
if (-not $text.Contains("internal val powerSavingThresholds")) {
    $text = $text.TrimEnd() + $helper + "`n"
}
Write-Utf8 $power $text

@'
package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PowerSavingThresholdTest {
    @Test fun exposesSixFifteenPercentSteps() {
        assertEquals(listOf(15, 30, 45, 60, 75, 90), powerSavingThresholds)
    }

    @Test fun migratesLegacyValuesToNearestSupportedThreshold() {
        assertEquals(15, normalizePowerSavingThreshold(10))
        assertEquals(15, normalizePowerSavingThreshold(20))
        assertEquals(30, normalizePowerSavingThreshold(25))
        assertEquals(30, normalizePowerSavingThreshold(35))
    }

    @Test fun preservesSupportedThresholds() {
        powerSavingThresholds.forEach { value ->
            assertEquals(value, normalizePowerSavingThreshold(value))
        }
    }
}
'@ | ForEach-Object { Write-Utf8 $test $_ }

# Explicit scope guard. LabBook spacing is allowed to remain pending, but this patch cannot touch it.
$protected = git diff --name-only | Where-Object {
    $_ -match "AppRoot.kt|SettingsScreen.kt|BottomBarTabsTest.kt|HeartRate|BatteryEstimator|DevicesScreen.kt"
}
if ($protected) { throw "Protected file changed: $($protected -join ', ')" }

git diff --check -- $today $power $test
if ($LASTEXITCODE -ne 0) { throw "git diff --check failed" }

Write-Host "Battery ring and Power Saving threshold patch v2 applied." -ForegroundColor Green
Write-Host "BLE battery reading, charging state/animation, Home layout and health algorithms were not changed." -ForegroundColor Cyan
git diff --stat -- $today $power $test

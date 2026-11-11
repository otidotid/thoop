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
package com.noop.ui

import org.junit.Assert.*
import org.junit.Test

class DashboardRingProgressTest {
    @Test fun oxygenUsesPhysiologicalDisplayRange() {
        assertEquals(13.0 / 15.0, DashboardRingProgress.bloodOxygen(98.0)!!, 1e-12)
        assertEquals(1.0, DashboardRingProgress.bloodOxygen(100.0)!!, 0.0)
        assertNull(DashboardRingProgress.bloodOxygen(null))
    }
    @Test fun skinUsesDeviationAndNeutralFallback() {
        assertEquals(1.0, DashboardRingProgress.skinTemperature(0.0, true)!!, 0.0)
        assertEquals(0.75, DashboardRingProgress.skinTemperature(0.5, true)!!, 0.0)
        assertEquals(0.75, DashboardRingProgress.skinTemperature(null, true)!!, 0.0)
        assertNull(DashboardRingProgress.skinTemperature(null, false))
    }
    @Test fun caloriesUseManualTargetAndClampAtGoal() {
        assertEquals(0.0, DashboardRingProgress.totalCalories(3026.0, null)!!, 0.0)
        assertEquals(3026.0 / 3500.0, DashboardRingProgress.totalCalories(3026.0, 3500)!!, 1e-12)
        assertEquals(1.0, DashboardRingProgress.totalCalories(3800.0, 3500)!!, 0.0)
    }
}

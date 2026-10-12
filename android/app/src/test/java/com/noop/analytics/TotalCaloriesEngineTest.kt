package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TotalCaloriesEngineTest {
    @Test fun restingUsesMifflinAndAccruesByLocalMinute() {
        assertEquals(1612.5, TotalCaloriesEngine.dailyRestingKcal(72.0, 174.0, 40, "male"), 0.01)
        val noon = TotalCaloriesEngine.breakdown(0.0, 72.0, 174.0, 40, "male", 720, false)
        assertEquals(806.25, noon.restingKcal, 0.01)
    }

    @Test fun totalAddsExistingActiveExactlyOnce() {
        val out = TotalCaloriesEngine.breakdown(500.0, 72.0, 174.0, 40, "male", 720, false)
        assertEquals(500.0, out.activeKcal, 0.0)
        assertEquals(1306.25, out.totalKcal, 0.01)
        assertFalse(out.estimated)
    }

    @Test fun missingActiveBecomesApproximateZeroNotNoData() {
        val out = TotalCaloriesEngine.breakdown(null, 72.0, 174.0, 40, "male", 0, true)
        assertEquals(0, TotalCaloriesEngine.rounded(out.totalKcal))
        assertTrue(out.estimated)
    }

    @Test fun elapsedMinutesAreClampedToCalendarDay() {
        val full = TotalCaloriesEngine.dailyRestingKcal(72.0, 174.0, 40, "male")
        val out = TotalCaloriesEngine.breakdown(0.0, 72.0, 174.0, 40, "male", 9_999, false)
        assertEquals(full, out.restingKcal, 0.01)
    }
}

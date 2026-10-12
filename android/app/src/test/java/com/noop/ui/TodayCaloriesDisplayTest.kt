package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayCaloriesDisplayTest {
    @Test fun freshValueWinsAndIsNotEstimated() {
        val out = todayCaloriesDisplay(1286.0, 1250.0, isToday = true)!!
        assertEquals(1286.0, out.value, 0.0)
        assertFalse(out.estimated)
    }

    @Test fun refreshRetainsTodaysLastValueAndMarksApproximate() {
        val out = todayCaloriesDisplay(null, 1250.0, isToday = true)!!
        assertEquals(1250.0, out.value, 0.0)
        assertTrue(out.estimated)
    }

    @Test fun coldStartTodayUsesApproximateZeroInsteadOfNoData() {
        val out = todayCaloriesDisplay(null, null, isToday = true)!!
        assertEquals(0.0, out.value, 0.0)
        assertTrue(out.estimated)
    }

    @Test fun pastDayNeverBorrowsAnotherValue() {
        assertNull(todayCaloriesDisplay(null, 1250.0, isToday = false))
    }
}

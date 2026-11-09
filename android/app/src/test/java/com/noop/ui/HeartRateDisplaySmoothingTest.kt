package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateDisplaySmoothingTest {
    @Test fun appliesOneLightWeightedPass() {
        val result = lightlySmoothHeartRate(
            values = listOf(50.0, 54.0, 90.0, 58.0, 60.0),
            segmentIds = listOf("0", "0", "0", "0", "0"),
        )
        assertEquals(listOf(50.0, 62.0, 73.0, 66.5, 60.0), result)
    }

    @Test fun neverSmoothsAcrossMissingDataGap() {
        val result = lightlySmoothHeartRate(
            values = listOf(50.0, 54.0, 90.0, 58.0, 60.0),
            segmentIds = listOf("0", "0", "1", "1", "1"),
        )
        assertEquals(listOf(50.0, 54.0, 90.0, 66.5, 60.0), result)
    }

    @Test fun preservesShortSeriesExactly() {
        val values = listOf(50.0, 80.0)
        assertEquals(values, lightlySmoothHeartRate(values, listOf("0", "0")))
    }
}

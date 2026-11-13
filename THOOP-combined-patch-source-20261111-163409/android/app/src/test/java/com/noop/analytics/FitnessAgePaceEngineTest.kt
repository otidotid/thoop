package com.noop.analytics

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class FitnessAgePaceEngineTest {
    private fun weekly(vararg ages: Double): List<FitnessAgePaceEngine.Point> =
        ages.mapIndexed { index, age ->
            FitnessAgePaceEngine.Point(LocalDate.of(2026, 1, 3).plusWeeks(index.toLong()).toString(), age)
        }

    @Test fun fewerThanFourPointsCalibrates() {
        val result = FitnessAgePaceEngine.calculate(weekly(30.0, 30.1, 30.2))
        assertNull(result.pace)
        assertEquals(1, result.weeksNeeded)
        assertEquals(FitnessAgePaceEngine.Confidence.CALIBRATING, result.confidence)
    }

    @Test fun oneCalendarYearPerYearIsApproximatelyOneX() {
        val points = listOf(
            FitnessAgePaceEngine.Point("2025-01-04", 30.0),
            FitnessAgePaceEngine.Point("2025-05-03", 30.326),
            FitnessAgePaceEngine.Point("2025-09-06", 30.668),
            FitnessAgePaceEngine.Point("2026-01-03", 31.0),
        )
        assertEquals(1.0, FitnessAgePaceEngine.calculate(points).pace!!, 0.02)
    }

    @Test fun flatFitnessAgeIsZeroX() {
        assertEquals(0.0, FitnessAgePaceEngine.calculate(weekly(30.0, 30.0, 30.0, 30.0)).pace!!, 0.0)
    }

    @Test fun improvingFitnessAgeCanBeNegative() {
        val result = FitnessAgePaceEngine.calculate(weekly(30.0, 29.9, 29.8, 29.7))
        assertTrue(result.pace!! < 0.0)
        assertEquals("IMPROVING", FitnessAgePaceEngine.label(result))
    }

    @Test fun oneOutlierDoesNotDominateMedianPairwiseSlope() {
        val points = listOf(
            FitnessAgePaceEngine.Point("2026-01-03", 30.0),
            FitnessAgePaceEngine.Point("2026-01-10", 30.0),
            FitnessAgePaceEngine.Point("2026-01-17", 45.0),
            FitnessAgePaceEngine.Point("2026-01-24", 30.0),
            FitnessAgePaceEngine.Point("2026-01-31", 30.0),
        )
        assertEquals(0.0, FitnessAgePaceEngine.calculate(points).pace!!, 0.0)
    }

    @Test fun duplicateDatesAndNonFiniteValuesAreHandled() {
        val points = listOf(
            FitnessAgePaceEngine.Point("2026-01-03", 30.0),
            FitnessAgePaceEngine.Point("2026-01-03", 30.1),
            FitnessAgePaceEngine.Point("2026-01-10", Double.NaN),
            FitnessAgePaceEngine.Point("2026-01-17", 30.1),
            FitnessAgePaceEngine.Point("2026-01-24", 30.1),
            FitnessAgePaceEngine.Point("2026-01-31", 30.1),
        )
        val result = FitnessAgePaceEngine.calculate(points)
        assertTrue(result.ready)
        assertEquals(4, result.sampleWeeks)
    }

    @Test fun onlyLatestTwelvePointsDriveConfidence() {
        val result = FitnessAgePaceEngine.calculate(weekly(*DoubleArray(13) { 30.0 }))
        assertEquals(12, result.sampleWeeks)
        assertEquals(FitnessAgePaceEngine.Confidence.HIGH, result.confidence)
    }
}

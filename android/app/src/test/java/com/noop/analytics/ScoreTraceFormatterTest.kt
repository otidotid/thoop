package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoreTraceFormatterTest {
    private val data = DailyMetric(
        deviceId = "my-whoop-noop",
        day = "2026-11-14",
        restingHr = 57,
        avgHrv = 52.5,
        totalSleepMin = 430.0,
        respRateBpm = 14.5,
        skinTempDevC = 0.3,
        strain = 48.0,
        exerciseCount = 1,
    )

    @Test fun recoverySnapshotUsesMeasuredValuesAndFixedFormulaId() {
        val s = ScoreTraceFormatter.forDailyScore("recovery", data)!!
        assertEquals(RecoveryScorer.ALGORITHM_VERSION, s.version)
        assertTrue(s.measurements.contains("RMSSD: 52.5 ms"))
        assertTrue(s.measurements.contains("Resting HR: 57 bpm"))
        assertTrue(s.measurements.contains("Sleep: 430.0 min"))
        assertTrue(s.measurements.contains("Daily input summary (partial)"))
    }

    @Test fun missingReadingsStayMissingInsteadOfBecomingZero() {
        val blank = DailyMetric(deviceId="my-whoop-noop", day="2026-11-14", recovery=60.0)
        val result = ScoreTraceFormatter.forDailyScore("recovery", blank)
        assertNull(result) // no observed drivers -> cannot claim a trace
    }

    @Test fun strainNeverInventsRawHeartRateFromDailyScore() {
        val s = ScoreTraceFormatter.forDailyScore("strain", data)!!
        assertEquals(StrainScorer.ALGORITHM_VERSION, s.version)
        assertTrue(s.measurements.contains("raw samples not listed"))
        assertFalse(s.measurements.contains("bpm"))
    }

    @Test fun unsupportedMetricIsNotFalselyVersioned() {
        assertNull(ScoreTraceFormatter.forDailyScore("hba1c", data))
        assertNull(ScoreTraceFormatter.forDailyScore("vo2max_est", data))
        assertNull(ScoreTraceFormatter.forDailyScore("recovery", null))
    }
}

package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HealthConnectWorkoutReconcilerTest {
    private val profile = UserProfile(weightKg = 72.0, heightCm = 174.0, age = 40.0, sex = "male")
    private fun row(source: String = "health-connect") = WorkoutRow(
        deviceId = "health-connect", startTs = 100, endTs = 220, sport = "Running", source = source,
        durationS = 120.0, energyKcal = 956.0, avgHr = 118, maxHr = 130, strain = 10.0,
    )

    @Test fun healthConnectMetricsAreReplacedByLocalStrapMetrics() {
        val samples = (100L..220L).map { HrSample("my-whoop", it, if (it < 160) 130 else 150) }
        val out = HealthConnectWorkoutReconciler.reconcile(
            row(), samples, profile, 185.0, 55.0, StrainScorer.Method.EDWARDS, "male")
        assertEquals(140, out.avgHr)
        assertEquals(150, out.maxHr)
        requireNotNull(out.energyKcal)
        require(out.energyKcal!! < 956.0)
        // A very short workout may not be eligible for Effort, but must never crash.
    }

    @Test fun missingStrapHrBecomesPendingInsteadOfKeepingExternalOrEstimatedValues() {
        val out = HealthConnectWorkoutReconciler.reconcile(
            row(), emptyList(), profile, 185.0, 55.0, StrainScorer.Method.EDWARDS, "male")
        assertNull(out.avgHr)
        assertNull(out.maxHr)
        assertNull(out.energyKcal)
        assertNull(out.strain)
    }

    @Test fun nonHealthConnectWorkoutIsUntouched() {
        val original = row(source = "manual")
        val out = HealthConnectWorkoutReconciler.reconcile(
            original, emptyList(), profile, 185.0, 55.0, StrainScorer.Method.EDWARDS, "male")
        assertEquals(original, out)
    }
}
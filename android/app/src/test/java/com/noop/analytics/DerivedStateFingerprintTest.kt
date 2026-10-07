package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.SleepSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DerivedStateFingerprintTest {
    private fun day(recovery: Double? = 55.0) = DailyMetric("my-whoop-noop", "2026-10-07", recovery = recovery, strain = 4.2)
    private fun sleep(end: Long = 2_000L) = SleepSession("my-whoop-noop", 1_000L, end, userEdited = true)

    @Test fun unchangedOutputDoesNotRequestPassTwo() {
        val a = DerivedStateFingerprints.of(listOf(day()), listOf(sleep()))
        assertFalse(DerivedStateFingerprints.changed(a, a))
    }
    @Test fun recoveryChangeRequestsPassTwo() {
        assertTrue(DerivedStateFingerprints.changed(
            DerivedStateFingerprints.of(listOf(day(55.0)), listOf(sleep())),
            DerivedStateFingerprints.of(listOf(day(61.0)), listOf(sleep())),
        ))
    }
    @Test fun sleepWindowChangeRequestsPassTwo() {
        assertTrue(DerivedStateFingerprints.changed(
            DerivedStateFingerprints.of(listOf(day()), listOf(sleep(2_000L))),
            DerivedStateFingerprints.of(listOf(day()), listOf(sleep(2_300L))),
        ))
    }
}

package com.noop.ui

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ResolvedReadinessSnapshotTest {
    private fun day(key: String, recovery: Double? = null) = DailyMetric(
        deviceId = "my-whoop-noop",
        day = key,
        recovery = recovery,
    )

    @Test
    fun `display day is used when no carried score exists`() {
        val display = day("2026-11-03", recovery = 71.0)

        val snapshot = ResolvedReadinessSnapshot.resolve(
            baselines = null,
            displayDay = display,
        )

        assertSame(display, snapshot.readDay)
        assertEquals("2026-11-03", snapshot.day)
        assertEquals(71.0, snapshot.recovery!!, 0.0)
    }

    @Test
    fun `carried day drives headline and explanation together`() {
        val display = day("2026-11-03")
        val carried = day("2026-11-02", recovery = 68.0)

        val snapshot = ResolvedReadinessSnapshot.resolve(
            baselines = null,
            displayDay = display,
            carriedDay = carried,
        )

        assertSame(carried, snapshot.readDay)
        assertEquals("2026-11-02", snapshot.day)
        assertEquals(68.0, snapshot.recovery!!, 0.0)
    }

    @Test
    fun `empty input remains empty without inventing a score`() {
        val snapshot = ResolvedReadinessSnapshot.resolve(
            baselines = null,
            displayDay = null,
        )

        assertNull(snapshot.readDay)
        assertNull(snapshot.recovery)
        assertNull(snapshot.confidence)
        assertEquals(emptyList<Any>(), snapshot.drivers)
    }
}

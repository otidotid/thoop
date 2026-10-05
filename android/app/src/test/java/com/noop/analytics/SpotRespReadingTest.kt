package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class SpotRespReadingTest {
    @Test fun `estimates resting respiration from RSA modulated RR`() {
        val targetRpm = 15.0
        val rr = mutableListOf<Int>()
        var t = 0.0
        while (t < 60.0) {
            val interval = 900.0 + 55.0 * sin(2.0 * PI * (targetRpm / 60.0) * t)
            rr += interval.toInt()
            t += interval / 1000.0
        }
        val result = SpotRespReading.compute(rr)
        assertTrue(result is SpotRespReading.Outcome.Reading)
        result as SpotRespReading.Outcome.Reading
        assertEquals(targetRpm, result.rpm, 1.0)
    }

    @Test fun `does not publish a number when capture is short`() {
        assertTrue(SpotRespReading.compute(List(25) { 900 }) is SpotRespReading.Outcome.Incomplete)
    }

    @Test fun `does not clamp flat pattern into valid range`() {
        assertTrue(SpotRespReading.compute(List(70) { 900 }) is SpotRespReading.Outcome.Incomplete)
    }

    @Test fun `rejects capture with more than thirty five percent noise`() {
        val result = SpotRespReading.compute(List(40) { if (it % 2 == 0) 900 else 250 })
        assertEquals(SpotRespReading.Outcome.Incomplete(SpotRespReading.Reason.TOO_MUCH_NOISE), result)
    }
}

package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.sqrt

class GenieMaxHrvCoreGoldenTest {
    private val goldenVector =
        listOf(800.0, 810.0, 790.0, 830.0, 805.0)

    @Test
    fun sharedNnSetPinsAllMetrics() {
        val metrics = requireNotNull(
            GenieMaxHrvCore.metrics(goldenVector)
        )

        assertEquals(
            sqrt(681.25),
            metrics.rmssd,
            1e-12,
        )
        assertEquals(
            sqrt(176.0),
            metrics.sdnn,
            1e-12,
        )
        assertEquals(
            807.0,
            metrics.meanNN,
            0.0,
        )
        assertEquals(
            0.0,
            metrics.pnn50,
            0.0,
        )
        assertEquals(
            metrics.rmssd / sqrt(2.0),
            metrics.sd1,
            1e-12,
        )
        assertEquals(
            sqrt(
                maxOf(
                    0.0,
                    2.0 * metrics.sdnn * metrics.sdnn -
                        metrics.sd1 * metrics.sd1,
                ),
            ),
            metrics.sd2,
            1e-12,
        )
    }

    @Test
    fun rejectsThinOrNonFiniteInput() {
        assertNull(
            GenieMaxHrvCore.metrics(
                listOf(800.0, 810.0),
            ),
        )
        assertNull(
            GenieMaxHrvCore.metrics(
                listOf(800.0, Double.NaN, 810.0),
            ),
        )
        assertNull(
            GenieMaxHrvCore.metrics(
                listOf(
                    800.0,
                    Double.POSITIVE_INFINITY,
                    810.0,
                ),
            ),
        )
    }

    @Test
    fun analyzerUsesPopulationSdnnButPreservesRmssd() {
        val nn = List(4) { goldenVector }.flatten()

        val result = HrvAnalyzer.analyzeRaw(nn)
        val core = requireNotNull(
            GenieMaxHrvCore.metrics(nn),
        )

        assertEquals(
            core.rmssd,
            requireNotNull(result.rmssd),
            1e-12,
        )
        assertEquals(
            core.sdnn,
            requireNotNull(result.sdnn),
            1e-12,
        )
    }
}

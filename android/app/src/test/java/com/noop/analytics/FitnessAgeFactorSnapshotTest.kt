package com.noop.analytics

import com.noop.data.MetricSeriesRow
import com.noop.data.ScoreInputProvenanceRow
import org.junit.Assert.*
import org.junit.Test

class FitnessAgeFactorSnapshotTest {
    private val source = "strap-noop"
    private val day = "2026-11-14"
    private val factor = MetricSeriesRow(source, day, "fitness_factor_metabolic_delta", 3.0)
    private val scoreTrace = ScoreInputProvenanceRow(source, day, "fitness_age", source,
        MetabolicFitnessAge.ALGORITHM_VERSION, "captured context; request=" + "a".repeat(64) + ";")
    private val factorTrace = scoreTrace.copy(key = factor.key)

    private fun visible(score: ScoreInputProvenanceRow? = scoreTrace, row: MetricSeriesRow? = factor,
        trace: ScoreInputProvenanceRow? = factorTrace) =
        currentFitnessAgeMetabolicFactor(source, day, score, row, trace)

    @Test fun localAgeCannotDisplayTheStaleAiFactorAtTheSamePoint() {
        val localAge = scoreTrace.copy(algorithmVersion = FitnessAgeEngine.ALGORITHM_VERSION,
            observedInputs = "Current local calculation; no eligible body factor")
        assertNull(visible(localAge))
        // The filter only chooses what is current; it leaves the old audit row unchanged.
        assertEquals(3.0, factor.value, 0.0)
        assertEquals(MetabolicFitnessAge.ALGORITHM_VERSION, factorTrace.algorithmVersion)
    }

    @Test fun currentAiFactorRequiresExactPointAndSameCapturedRequestEvidence() {
        assertEquals(factor, visible())
        assertNull(visible(row = factor.copy(day = "2026-11-07")))
        assertNull(visible(row = factor.copy(deviceId = "other-noop")))
        assertNull(visible(trace = factorTrace.copy(day = "2026-11-07")))
        assertNull(visible(trace = factorTrace.copy(sourceId = "other-noop")))
        assertNull(visible(trace = factorTrace.copy(observedInputs = "different request")))
        assertNull(visible(trace = null))
        assertNull(visible(score = null))
        assertNull(visible(score = scoreTrace.copy(observedInputs = null)))
    }

    @Test fun aiAgeCannotDisplayAnEarlierLocalFactor() {
        assertNull(visible(trace = factorTrace.copy(algorithmVersion = FitnessAgeEngine.ALGORITHM_VERSION)))
    }

    @Test fun localAndLegacyFactorsKeepTheirExistingBehavior() {
        assertEquals(factor, visible(score = null, trace = null))
        assertEquals(factor, visible(
            score = scoreTrace.copy(algorithmVersion = FitnessAgeEngine.ALGORITHM_VERSION),
            trace = factorTrace.copy(algorithmVersion = FitnessAgeEngine.ALGORITHM_VERSION)))
        assertNull(visible(row = null))
        assertNull(visible(row = factor.copy(key = "fitness_factor_bmi")))
    }
}

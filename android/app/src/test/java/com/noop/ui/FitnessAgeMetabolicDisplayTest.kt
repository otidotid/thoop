package com.noop.ui

import com.noop.data.ScoreInputProvenanceRow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FitnessAgeMetabolicDisplayTest {
    private val trace = ScoreInputProvenanceRow(
        deviceId = "strap-noop", day = "2026-11-14", key = "fitness_age", sourceId = "strap-noop",
        algorithmVersion = "fitness-age-ai-metabolic-v1",
    )

    @Test fun aiOriginRequiresTheActualDisplayedPoint() {
        assertTrue(hasAiMetabolicProvenance(trace, "strap-noop", "2026-11-14"))
        assertFalse(hasAiMetabolicProvenance(trace, "other-strap-noop", "2026-11-14"))
        assertFalse(hasAiMetabolicProvenance(trace, "strap-noop", "2026-11-07"))
        assertFalse(hasAiMetabolicProvenance(trace.copy(key = "vo2max_est"), "strap-noop", "2026-11-14"))
    }

    @Test fun localLegacyAndMissingProvenanceDoNotClaimAiOrigin() {
        assertFalse(hasAiMetabolicProvenance(null, "strap-noop", "2026-11-14"))
        assertFalse(hasAiMetabolicProvenance(trace.copy(algorithmVersion = null), "strap-noop", "2026-11-14"))
        assertFalse(hasAiMetabolicProvenance(
            trace.copy(algorithmVersion = "fitness-age-nes-waist-metabolic-v1"), "strap-noop", "2026-11-14",
        ))
    }
}

package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protects all existing data on the v42 -> v43 additive provenance upgrade. */
class ScoreProvenanceV43MigrationTest {
    @Test fun theNewMigrationIsRegisteredInTheContinuousChain() {
        assertEquals(42, WhoopDatabase.MIGRATION_42_43.startVersion)
        assertEquals(43, WhoopDatabase.MIGRATION_42_43.endVersion)
        assertTrue(WhoopDatabase.ALL_MIGRATIONS.contains(WhoopDatabase.MIGRATION_42_43))
        assertEquals(43, WhoopDatabase.SCHEMA_VERSION)
    }

    @Test fun legacyProvenanceDefaultsToUnknownVersionNotAnInventedModel() {
        val legacy = ScoreInputProvenanceRow("whoop-noop", "2026-11-14", "recovery", "my-whoop")
        assertEquals(null, legacy.algorithmVersion)
        assertEquals(null, legacy.observedInputs)
        assertEquals("my-whoop", legacy.sourceId)
    }

    @Test fun newVersionedProvenanceDoesNotAlterTheOriginalSourceIdContract() {
        val point = ScoreInputProvenanceRow(
            "my-whoop-noop", "2026-11-14", "vo2max_est", "nes",
            "fitness-age-nes-waist-metabolic-v1", "RHR=58 bpm, waist=91 cm",
        )
        assertEquals("nes", point.sourceId)
        assertEquals("fitness-age-nes-waist-metabolic-v1", point.algorithmVersion)
    }
}

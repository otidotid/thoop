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

    @Test fun migrationOnlyAddsNullableFieldsAndNeverDeletesStoredRecords() {
        val sql = mutableListOf<String>()
        val db = java.lang.reflect.Proxy.newProxyInstance(
            androidx.sqlite.db.SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(androidx.sqlite.db.SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL" && args != null && args.isNotEmpty()) {
                sql += args[0] as String
            }
            null
        } as androidx.sqlite.db.SupportSQLiteDatabase
        WhoopDatabase.MIGRATION_42_43.migrate(db)
        assertEquals(
            listOf(
                "ALTER TABLE `scoreInputProvenance` ADD COLUMN `algorithmVersion` TEXT",
                "ALTER TABLE `scoreInputProvenance` ADD COLUMN `observedInputs` TEXT",
            ),
            sql,
        )
        assertTrue(sql.none { it.contains("DROP", ignoreCase = true) ||
            it.contains("DELETE", ignoreCase = true) })
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

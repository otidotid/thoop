package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class RetentionPlanTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test fun `day boundaries are inclusive and no data changes at midnight by accident`() {
        val table = RetentionPlan.requireTable(RetentionPlan.Database.HEALTH, "workout")
        fun band(daysOld: Long) = RetentionPlan.bandFor(table, today.minusDays(daysOld), today)
        assertEquals(RetentionPlan.Band.FULL_LOCAL, band(0))
        assertEquals(RetentionPlan.Band.FULL_LOCAL, band(14))
        assertEquals(RetentionPlan.Band.SNAPSHOT_LOCAL, band(15))
        assertEquals(RetentionPlan.Band.SNAPSHOT_LOCAL, band(21))
        assertEquals(RetentionPlan.Band.ONLINE_ONLY, band(22))
        assertEquals(RetentionPlan.Band.FULL_LOCAL,
            RetentionPlan.bandFor(table, today.plusDays(1), today))
    }

    @Test fun `tombstones and reusable settings are never age deleted`() {
        for (name in listOf("dismissedWorkout", "dismissedSleep", "dayOwnership", "liftProgram")) {
            val table = RetentionPlan.requireTable(RetentionPlan.Database.HEALTH, name)
            assertEquals(RetentionPlan.Band.KEEP_LOCAL,
                RetentionPlan.bandFor(table, today.minusYears(10), today))
        }
    }

    @Test fun `food is classified in its own database and lift sets use their parent session`() {
        val food = RetentionPlan.requireTable(RetentionPlan.Database.FOOD, "foodIntake")
        assertEquals("localDay", food.dateColumn)
        assertEquals(RetentionPlan.DateFormat.DAY_TEXT, food.dateFormat)
        val set = RetentionPlan.requireTable(RetentionPlan.Database.HEALTH, "liftSet")
        assertEquals("sessionId", set.dateColumn)
        assertEquals(RetentionPlan.DateFormat.PARENT_SESSION, set.dateFormat)
    }

    @Test fun `new or missing tables cause a hard failure rather than silent omission`() {
        val health = RetentionPlan.tables.filter { it.database == RetentionPlan.Database.HEALTH }
            .map { it.name }.toSet()
        RetentionPlan.requireExactCoverage(RetentionPlan.Database.HEALTH, health)
        for (changed in listOf(health + "unknownNewTable", health - "workout")) {
            val failed = runCatching {
                RetentionPlan.requireExactCoverage(RetentionPlan.Database.HEALTH, changed)
            }.isFailure
            assertTrue("A mismatched schema must be rejected", failed)
        }
    }

    @Test fun `inventory exactly matches all current Room entity declarations`() {
        // Read Kotlin source because @Database's annotation is not runtime-retained.
        var root = File(System.getProperty("user.dir") ?: ".").canonicalFile
        var sourceDir: File? = null
        repeat(5) {
            val candidate = File(root, "android/app/src/main/java/com/noop/data")
            if (candidate.isDirectory && sourceDir == null) sourceDir = candidate
            root = root.parentFile ?: root
        }
        val dir = sourceDir ?: error("Cannot locate Room entity source; do not skip inventory verification")
        val entityPattern = Regex("""@Entity\s*\([\s\S]*?\btableName\s*=\s*"([^"]+)"""")
        fun declarations(file: String): Set<String> = entityPattern.findAll(File(dir, file).readText())
            .map { it.groupValues[1] }.toSet()
        val health = declarations("Entities.kt") + declarations("PairedDevice.kt") +
            declarations("LiftEntities.kt")
        val food = declarations("FoodIntake.kt")
        assertEquals(34, health.size)
        assertEquals(setOf("foodIntake"), food)
        RetentionPlan.requireExactCoverage(RetentionPlan.Database.HEALTH, health)
        RetentionPlan.requireExactCoverage(RetentionPlan.Database.FOOD, food)
    }
}

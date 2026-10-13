package com.noop.analytics

import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeeklyHistoricalBackfillTest {
    private val profile = UserProfile(weightKg = 70.0, heightCm = 175.0, age = 40.0, sex = "male", waistCm = 85.0)
    private fun day(day: String, index: Int) = DailyMetric(
        deviceId = "my-whoop-noop", day = day, totalSleepMin = 420.0 + index,
        restingHr = 55 + index % 3, avgHrv = 45.0 + index, strain = 45.0,
        steps = 8_000 + index * 100,
    )
    private fun history(): List<DailyMetric> {
        val start = java.time.LocalDate.parse("2026-09-20")
        return (0L..20L).map { day(start.plusDays(it).toString(), it.toInt()) }
    }

    @Test fun buildsEarlierSaturdaysButLeavesCurrentSaturdayToNormalPath() {
        val rows = IntelligenceEngine.weeklyHistoricalRows(history(), profile, "my-whoop-noop", "2026-10-13", emptySet())
        val days = rows.map { it.day }.toSet()
        assertTrue("2026-09-26" in days)
        assertTrue("2026-10-03" in days)
        assertFalse("2026-10-10" in days)
        assertTrue(rows.any { it.key == "vo2max_est" })
        assertTrue(rows.any { it.key == "vitality" })
    }

    @Test fun neverReturnsAnExistingValidatedPoint() {
        val existing = setOf(
            "2026-10-03" to "fitness_age", "2026-10-03" to "vo2max_est",
            "2026-10-03" to "vitality", "2026-10-03" to "body_age",
        )
        val rows = IntelligenceEngine.weeklyHistoricalRows(history(), profile, "my-whoop-noop", "2026-10-13", existing)
        assertFalse(rows.any { it.day == "2026-10-03" })
        assertTrue(rows.any { it.day == "2026-09-26" })
    }

    @Test fun isIdempotentWhenAllGeneratedRowsAlreadyExist() {
        val first = IntelligenceEngine.weeklyHistoricalRows(history(), profile, "my-whoop-noop", "2026-10-13", emptySet())
        val second = IntelligenceEngine.weeklyHistoricalRows(
            history(), profile, "my-whoop-noop", "2026-10-13", first.mapTo(HashSet()) { it.day to it.key },
        )
        assertEquals(emptyList<com.noop.data.MetricSeriesRow>(), second)
    }

    @Test fun insufficientWeeksDoNotFabricateScores() {
        val rows = IntelligenceEngine.weeklyHistoricalRows(
            listOf(DailyMetric("my-whoop-noop", "2026-10-03")), profile,
            "my-whoop-noop", "2026-10-13", emptySet(),
        )
        assertTrue(rows.isEmpty())
    }
}

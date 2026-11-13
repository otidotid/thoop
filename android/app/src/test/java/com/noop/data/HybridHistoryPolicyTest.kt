package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class HybridHistoryPolicyTest {
    private val today = LocalDate.of(2026, 11, 13)

    @Test fun exactlySevenDaysAndDayEightMovesToCloud() {
        val days = HybridHistoryPolicy.snapshotDays(today)
        assertEquals(LocalDate.of(2026, 10, 23), days.start)
        assertEquals(LocalDate.of(2026, 10, 29), days.endInclusive)
        assertEquals(HybridHistoryPolicy.Tier.FULL_LOCAL,
            HybridHistoryPolicy.tier(today.minusDays(14), today))
        assertEquals(HybridHistoryPolicy.Tier.COMPACT_LOCAL,
            HybridHistoryPolicy.tier(today.minusDays(15), today))
        assertEquals(HybridHistoryPolicy.Tier.COMPACT_LOCAL,
            HybridHistoryPolicy.tier(today.minusDays(21), today))
        assertEquals(HybridHistoryPolicy.Tier.ENCRYPTED_CLOUD_ELIGIBLE,
            HybridHistoryPolicy.tier(today.minusDays(22), today))
    }

    @Test fun rollingWindowAdvancesOneDayEveryNewDay() {
        val tomorrow = today.plusDays(1)
        assertEquals(today.minusDays(21).plusDays(1),
            HybridHistoryPolicy.snapshotDays(tomorrow).start)
        assertEquals(today.minusDays(22).plusDays(1),
            HybridHistoryPolicy.archiveCutoff(tomorrow))
        val day = today.minusDays(21)
        assertEquals(HybridHistoryPolicy.Tier.COMPACT_LOCAL,
            HybridHistoryPolicy.tier(day, today))
        assertEquals(HybridHistoryPolicy.Tier.ENCRYPTED_CLOUD_ELIGIBLE,
            HybridHistoryPolicy.tier(day, tomorrow))
    }

    @Test fun cutoffMayFallInsideIncompleteCurrentMonth() {
        val testDay = LocalDate.of(2026, 11, 13)
        val cutoff = LocalDate.of(2026, 10, 22)
        assertEquals(cutoff, HybridHistoryPolicy.archiveCutoff(testDay))
        assertEquals(YearMonth.of(2026, 10), HybridHistoryPolicy.lastArchiveMonth(testDay))
        assertEquals(cutoff, HybridHistoryPolicy.coveredThrough(YearMonth.of(2026, 10), testDay))
        assertEquals(LocalDate.of(2026, 9, 30),
            HybridHistoryPolicy.coveredThrough(YearMonth.of(2026, 9), testDay))
        assertTrue(HybridHistoryPolicy.isCoveredThrough("2026-10-22", cutoff))
        assertFalse(HybridHistoryPolicy.isCoveredThrough("2026-10-21", cutoff))
        assertTrue(HybridHistoryPolicy.isCoveredThrough(null, cutoff)) // legacy whole-month
    }

    @Test fun futureDaysNeverBecomeCloudHistory() {
        assertEquals(HybridHistoryPolicy.Tier.FUTURE,
            HybridHistoryPolicy.tier(today.plusDays(1), today))
    }
}

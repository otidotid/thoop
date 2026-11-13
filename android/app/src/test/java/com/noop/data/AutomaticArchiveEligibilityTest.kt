package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class AutomaticArchiveEligibilityTest {
    @Test fun dailyCutoffAdvancesExactlyOneDay() {
        val today = LocalDate.of(2026, 11, 13)
        val tomorrow = today.plusDays(1)
        assertEquals(LocalDate.of(2026, 10, 22), HybridHistoryPolicy.archiveCutoff(today))
        assertEquals(LocalDate.of(2026, 10, 23), HybridHistoryPolicy.archiveCutoff(tomorrow))
        assertEquals(YearMonth.of(2026, 10), HybridHistoryPolicy.lastArchiveMonth(today))
        assertEquals(YearMonth.of(2026, 10), HybridHistoryPolicy.lastArchiveMonth(tomorrow))
    }

    @Test fun partialMonthNeedsARefreshOnTheNextDay() {
        val today = LocalDate.of(2026, 11, 13)
        val verifiedThrough = HybridHistoryPolicy.archiveCutoff(today)
        assertTrue(HybridHistoryPolicy.isCoveredThrough(
            verifiedThrough.toString(), HybridHistoryPolicy.archiveCutoff(today)))
        assertFalse(HybridHistoryPolicy.isCoveredThrough(
            verifiedThrough.toString(), HybridHistoryPolicy.archiveCutoff(today.plusDays(1))))
    }

    @Test fun snapshotsAlwaysContainExactlySevenDays() {
        for (offset in 0..40) {
            val date = LocalDate.of(2026, 11, 13).plusDays(offset.toLong())
            val window = HybridHistoryPolicy.snapshotDays(date)
            assertEquals(6L, java.time.temporal.ChronoUnit.DAYS.between(
                window.start, window.endInclusive))
            assertEquals(HybridHistoryPolicy.Tier.ENCRYPTED_CLOUD_ELIGIBLE,
                HybridHistoryPolicy.tier(date.minusDays(22), date))
        }
    }
}

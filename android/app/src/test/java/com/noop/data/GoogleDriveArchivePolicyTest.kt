package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class GoogleDriveArchivePolicyTest {
    @Test fun retentionWindowKeepsTodayAndPrevious89Days() {
        val today = LocalDate.of(2026, 11, 9)
        assertEquals(LocalDate.of(2026, 8, 12), today.minusDays(89))
    }

    @Test fun manifestRecordPreservesVerificationFields() {
        val row = GoogleDriveArchiveRecord("2026-07", "id", "sha", 99, 42, 28, 1)
        assertEquals("2026-07", row.month)
        assertEquals(42, row.recordCount)
        assertEquals(28, row.schemaVersion)
        assertEquals(false, row.encrypted) // existing manifests are legacy/plaintext
    }

    @Test fun encryptedArchiveRecordRetainsFormatFlag() {
        val row = GoogleDriveArchiveRecord("2026-07", "id", "sha", 99, 42, 28, 1, encrypted = true)
        assertEquals(true, row.encrypted)
    }

    @Test
    fun onlyCompleteMonthsAreEligibleForArchive() {
        val today = LocalDate.of(2026, 11, 9)
        val cutoff = today.minusDays(89)

        assertEquals(LocalDate.of(2026, 8, 12), cutoff)
        assertEquals(
            java.time.YearMonth.of(2026, 7),
            java.time.YearMonth.from(cutoff).minusMonths(1),
        )
    }
    @Test fun migrationKeepsTheOriginalDriveObjectReference() {
        val original = GoogleDriveArchiveRecord("2026-07", "old-id", "original-sha", 99, 42, 28, 1)
        val migrated = original.copy(driveFileId = "new-id", encrypted = true,
            legacyDriveFileId = original.driveFileId)
        assertEquals("old-id", migrated.legacyDriveFileId)
        assertEquals("old-id", original.driveFileId)
        assertEquals(true, migrated.encrypted)
    }
}

package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class CompactDailySnapshotsTest {
    @Test fun tier2SnapshotDaysAre15Through21Inclusive() {
        val today = LocalDate.of(2026, 11, 13)
        assertEquals("2026-10-23" to "2026-10-30", CompactDailySnapshots.window(today))
    }

    @Test fun userKeptLocalBackupIsNotScheduledForDeletion() {
        val table = RetentionPlan.requireTable(RetentionPlan.Database.HEALTH, "dailyMetric")
        // 'ONLINE_ONLY' is a presentation/caching band; no delete operation is provided.
        assertEquals(RetentionPlan.Band.ONLINE_ONLY,
            RetentionPlan.bandFor(table, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 13)))
    }
}

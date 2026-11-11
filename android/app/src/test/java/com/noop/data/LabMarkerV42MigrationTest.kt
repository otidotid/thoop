package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LabMarkerV42MigrationTest {
    @Test fun migrationIsRegisteredFor41To42() {
        assertEquals(41, WhoopDatabase.MIGRATION_41_42.startVersion)
        assertEquals(42, WhoopDatabase.MIGRATION_41_42.endVersion)
    }
}

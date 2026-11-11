package com.noop.ingest

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.StepsRecord
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectWorkoutScopeTest {
    @Test fun workoutOnlyImportsExerciseAndRouteInputs() {
        val types = HealthConnectImporter.ImportCategory.ACTIVITY_ONLY.recordTypes
        assertTrue(ExerciseSessionRecord::class in types)
        assertTrue(DistanceRecord::class in types)
        assertFalse(StepsRecord::class in types)
        assertFalse(ActiveCaloriesBurnedRecord::class in types)
    }

    @Test fun workoutPlusActivityAddsSupportedDailyActivity() {
        val types = HealthConnectImporter.ImportCategory.ALL.recordTypes
        assertTrue(ExerciseSessionRecord::class in types)
        assertTrue(DistanceRecord::class in types)
        assertTrue(StepsRecord::class in types)
        assertTrue(ActiveCaloriesBurnedRecord::class in types)
    }
}

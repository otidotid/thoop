package com.noop.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class BathWorkoutTypesTest {
    @Test fun bathTypesAreAvailableInAddWorkoutCatalog() {
        assertTrue("Cold Bath" in WorkoutEditing.relabelSports)
        assertTrue("Warm Bath" in WorkoutEditing.relabelSports)
    }
}

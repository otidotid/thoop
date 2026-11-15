package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTabVisibilityTest {
    @Test fun legacySelectionCannotHideThePermanentThoopTabs() {
        val visible = visibleBarDestinationsFor(listOf(Destination.Coach, Destination.Sleep), true)
        assertEquals(listOf(Destination.Today, Destination.Trends, Destination.Workouts, Destination.Coach), visible)
    }

    @Test fun legacyCoachToggleCannotRemoveThePermanentCoachDestination() {
        val visible = visibleBarDestinationsFor(
            listOf(Destination.Health, Destination.Coach, Destination.Sleep),
            false,
        )
        assertTrue(Destination.Coach in visible)
        assertEquals(listOf(Destination.Today, Destination.Trends, Destination.Workouts, Destination.Coach), visible)
        assertFalse(Destination.Sleep in visible)
        assertEquals(visible.size, visible.distinct().size)
    }
}

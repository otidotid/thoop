package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTabVisibilityTest {
    @Test fun coachEnabledKeepsSelectedCoach() {
        val visible = visibleBarDestinationsFor(listOf(Destination.Coach, Destination.Sleep), true)
        assertEquals(listOf(Destination.Coach, Destination.Sleep), visible)
    }

    @Test fun coachDisabledDropsOnlyCoach() {
        val visible = visibleBarDestinationsFor(
            listOf(Destination.Health, Destination.Coach, Destination.Sleep),
            false,
        )
        assertFalse(Destination.Coach in visible)
        assertEquals(listOf(Destination.Health, Destination.Sleep), visible)
        assertTrue(Destination.Sleep in visible)
    }
}

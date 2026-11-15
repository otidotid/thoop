package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoachTabVisibilityTest {
    @Test fun coachAppearsOnlyWhenPinnedAndKeepsItsSelectedPosition() {
        val visible = visibleBarDestinationsFor(listOf(Destination.Coach, Destination.Sleep), true)
        assertEquals(listOf(Destination.Today, Destination.Coach, Destination.Sleep), visible)
        val withoutCoach = visibleBarDestinationsFor(listOf(Destination.Health, Destination.Sleep), true)
        assertEquals(listOf(Destination.Today, Destination.Health, Destination.Sleep), withoutCoach)
        assertFalse(Destination.Coach in withoutCoach)
    }

    @Test fun coachActivityTogglePreservesTheCustomNavigationSelection() {
        val visible = visibleBarDestinationsFor(
            listOf(Destination.Health, Destination.Coach, Destination.Sleep),
            false,
        )
        assertTrue(Destination.Coach in visible)
        assertEquals(listOf(Destination.Today, Destination.Health, Destination.Coach, Destination.Sleep), visible)
        assertEquals(visible, visibleBarDestinationsFor(listOf(Destination.Health, Destination.Coach, Destination.Sleep), true))
        assertEquals(visible, barTabsFor(listOf(Destination.Health, Destination.Coach, Destination.Sleep)).map { it.dest })
        assertEquals(visible.size, visible.distinct().size)
    }
}

package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarTabsTest {
    @Test fun todayIsAlwaysFirstAndOnlyOptionalSlotsFollow() {
        val tabs = barTabsFor(listOf(Destination.Coach, Destination.Health, Destination.Sleep))
        assertEquals(Destination.Today, tabs.first().dest)
        assertEquals(listOf(Destination.Coach, Destination.Health, Destination.Sleep), tabs.drop(1).map { it.dest })
        assertEquals(4, tabs.size)
    }

    @Test fun duplicateSelectionsCannotCreateDuplicateSlots() {
        val tabs = barTabsFor(listOf(Destination.Health, Destination.Health, Destination.Sleep))
        assertEquals(tabs.map { it.dest }.distinct(), tabs.map { it.dest })
    }

    @Test fun pinnedModulesAreRemovedFromMoreButUnpinnedModulesRemain() {
        val groups = visibleMoreGroups(listOf(Destination.Coach, Destination.Sleep, Destination.Health))
        val modules = groups.flatMap { it.items }
        assertFalse(Destination.Coach in modules)
        assertFalse(Destination.Sleep in modules)
        assertFalse(Destination.Health in modules)
        assertTrue(Destination.Trends in modules)
        assertTrue(Destination.Workouts in modules)
        assertTrue(Destination.Settings in modules)
    }

    @Test fun todayAndMoreAreStructuralNotConfigurable() {
        assertFalse(Destination.Today in configurableBottomBarDestinations)
        assertFalse(Destination.More in configurableBottomBarDestinations)
    }
}

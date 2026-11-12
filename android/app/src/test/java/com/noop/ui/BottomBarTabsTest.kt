package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarTabsTest {
    @Test fun permanentBottomBarUsesFixedDestinationOrder() {
        val tabs = barTabsFor()
        assertEquals(
            listOf(
                Destination.Today,
                Destination.Trends,
                Destination.Workouts,
                Destination.Coach,
            ),
            tabs.map { it.dest },
        )
    }

    @Test fun permanentBottomBarContainsNoDuplicateDestinations() {
        val tabs = barTabsFor()
        assertEquals(tabs.map { it.dest }.distinct(), tabs.map { it.dest })
    }

    @Test fun pinnedModulesAreRemovedFromMoreButUnpinnedModulesRemain() {
        val groups = visibleMoreGroups(
            listOf(Destination.Coach, Destination.Sleep, Destination.Health)
        )
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

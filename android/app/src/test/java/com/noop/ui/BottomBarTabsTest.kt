package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarTabsTest {
    @Test fun unsetDockUsesTheExistingPreviewFiveDefaultsInOrder() {
        val tabs = barTabsFor(BottomBarModuleStore.decode(null))
        assertEquals(
            listOf(
                Destination.Today,
                Destination.Workouts,
                Destination.Trends,
                Destination.Sleep,
            ),
            tabs.map { it.dest },
        )
    }

    @Test fun customDockKeepsPinOrderAndCannotDuplicateStructuralDestinations() {
        val tabs = barTabsFor(listOf(Destination.Today, Destination.Coach, Destination.Health,
            Destination.Coach, Destination.More, Destination.Live, Destination.Sleep))
        assertEquals(listOf(Destination.Today, Destination.Coach, Destination.Health, Destination.Live), tabs.map { it.dest })
        assertEquals(tabs.map { it.dest }.distinct(), tabs.map { it.dest })
    }

    @Test fun anExplicitlyEmptyDockHasOnlyTodayBeforeTheFixedMoreSlot() {
        assertEquals(listOf(Destination.Today), barTabsFor(BottomBarModuleStore.decode("")).map { it.dest })
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

    @Test fun unpinningRestoresTheDestinationToMoreWithoutLosingOtherDestinations() {
        val pinned = listOf(Destination.Coach, Destination.Sleep, Destination.Health)
        val unpinned = BottomBarModuleStore.withPinned(pinned, Destination.Sleep, false)!!
        val before = visibleMoreGroups(pinned).flatMap { it.items }
        val after = visibleMoreGroups(unpinned).flatMap { it.items }
        assertEquals(before.toSet() + Destination.Sleep, after.toSet())
        assertEquals(after.size, after.distinct().size)
        assertFalse(Destination.Coach in after)
        assertFalse(Destination.Health in after)
        assertEquals(drawerGroups.flatMap { it.items }.toSet(), visibleMoreGroups(emptyList()).flatMap { it.items }.toSet())
    }
}

package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomBarModuleStoreTest {
    @Test fun unsetUsesSafeThreeModuleDefault() {
        assertEquals(listOf(Destination.Workouts, Destination.Trends, Destination.Sleep), BottomBarModuleStore.decode(null))
    }

    @Test fun routesRoundTripInOrder() {
        val input = listOf(Destination.Coach, Destination.Health, Destination.Live)
        assertEquals(input, BottomBarModuleStore.decode(BottomBarModuleStore.encode(input)))
    }

    @Test fun invalidDuplicateAndExtraRoutesAreSanitised() {
        val decoded = BottomBarModuleStore.decode("today,health,health,live,coach,sleep,unknown")
        assertEquals(listOf(Destination.Health, Destination.Live, Destination.Coach), decoded)
        assertTrue(decoded.size <= BottomBarModuleStore.MAX_PINNED)
    }

    @Test fun removingEveryPinSurvivesEncodingAndTheNextLoad() {
        var pins = BottomBarModuleStore.defaults
        for (destination in BottomBarModuleStore.defaults) {
            pins = BottomBarModuleStore.withPinned(pins, destination, false)!!
        }
        val stored = BottomBarModuleStore.encode(pins)
        assertEquals("", stored)
        assertEquals(emptyList<Destination>(), BottomBarModuleStore.decode(stored))
        assertEquals(BottomBarModuleStore.defaults, BottomBarModuleStore.decode(null))
        assertEquals(BottomBarModuleStore.defaults, BottomBarModuleStore.decode("unknown,today,more"))
    }

    @Test fun addingAFourthPinIsRejectedWithoutReplacingOrReorderingSavedPins() {
        val original = listOf(Destination.Coach, Destination.Health, Destination.Live)
        assertNull(BottomBarModuleStore.withPinned(original, Destination.LabBook, true))
        assertEquals(original, BottomBarModuleStore.withPinned(original, Destination.Health, true))
        assertNull(BottomBarModuleStore.withPinned(original, Destination.Today, true))
        assertNull(BottomBarModuleStore.withPinned(original, Destination.More, true))
        val removed = BottomBarModuleStore.withPinned(original, Destination.Health, false)!!
        val added = BottomBarModuleStore.withPinned(removed, Destination.LabBook, true)!!
        assertEquals(listOf(Destination.Coach, Destination.Live, Destination.LabBook), added)
        assertEquals(added, BottomBarModuleStore.decode(BottomBarModuleStore.encode(added)))
        assertEquals(listOf(Destination.Coach, Destination.Health, Destination.Live), original)
    }

    @Test fun reorderClampsAtTheEndsAndPersistsTheChosenOrder() {
        val original = listOf(Destination.Workouts, Destination.Trends, Destination.Sleep)
        val moved = BottomBarModuleStore.moved(original, Destination.Sleep, -1)
        assertEquals(listOf(Destination.Workouts, Destination.Sleep, Destination.Trends), moved)
        assertEquals(moved, BottomBarModuleStore.decode(BottomBarModuleStore.encode(moved)))
        assertEquals(original, BottomBarModuleStore.moved(original, Destination.Workouts, Int.MIN_VALUE))
        assertEquals(original, BottomBarModuleStore.moved(original, Destination.Sleep, Int.MAX_VALUE))
        assertEquals(original, BottomBarModuleStore.moved(original, Destination.Coach, 1))
        assertEquals(emptyList<Destination>(), BottomBarModuleStore.moved(emptyList(), Destination.Sleep, 1))
        assertEquals(listOf(Destination.Workouts, Destination.Trends, Destination.Sleep), original)
    }
}

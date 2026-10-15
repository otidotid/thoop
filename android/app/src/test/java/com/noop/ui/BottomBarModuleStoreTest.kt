package com.noop.ui

import org.junit.Assert.assertEquals
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
}

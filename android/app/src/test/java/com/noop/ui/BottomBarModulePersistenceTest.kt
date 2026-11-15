package com.noop.ui

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BottomBarModulePersistenceTest {
    @After fun resetSharedProcessState() {
        val context = RuntimeEnvironment.getApplication()
        BottomBarModuleStore.reset(context)
        NoopPrefs.of(context).edit().remove(BottomBarModuleStore.KEY).commit()
    }

    @Test fun explicitZeroPinsSurviveTheActualPreferenceWriteAndReload() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = NoopPrefs.of(context)
        prefs.edit().remove(BottomBarModuleStore.KEY).commit()
        BottomBarModuleStore.load(context)
        assertEquals(BottomBarModuleStore.defaults, BottomBarModuleStore.selected)
        for (destination in BottomBarModuleStore.defaults) {
            assertTrue(BottomBarModuleStore.setPinned(context, destination, false))
        }
        assertEquals("", prefs.getString(BottomBarModuleStore.KEY, null))

        // This calls the same loader as app startup; the former decoder restored defaults here.
        BottomBarModuleStore.load(context)
        assertEquals(emptyList<Destination>(), BottomBarModuleStore.selected)
        assertEquals(listOf(Destination.Today), barTabsFor().map { it.dest })

        assertTrue(BottomBarModuleStore.setPinned(context, Destination.Health, true))
        BottomBarModuleStore.load(context)
        assertEquals(listOf(Destination.Health), BottomBarModuleStore.selected)
        assertEquals(listOf(Destination.Today, Destination.Health), barTabsFor().map { it.dest })
    }
}

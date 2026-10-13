package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenieMaxSleepArchitectureTest {
    @Test fun removesIsolatedStageFlecksInTwoPasses() {
        val input = listOf("light", "light", "rem", "light", "deep", "light", "light")
        val output = GenieMaxSleepArchitecture.smooth(input)
        assertEquals(listOf("light", "light", "light", "light", "light", "light", "light"), output)
        assertTrue(GenieMaxSleepArchitecture.invariantPreserving(input, output))
    }

    @Test fun preservesRealRunsAndSequenceLength() {
        val input = listOf("wake", "light", "light", "deep", "deep", "rem", "rem", "wake")
        val output = GenieMaxSleepArchitecture.smooth(input)
        assertEquals(input, output)
        assertTrue(GenieMaxSleepArchitecture.invariantPreserving(input, output))
    }

    @Test fun invalidVocabularyFailsClosedWithoutRewriting() {
        val input = listOf("light", "unknown", "light")
        assertEquals(input, GenieMaxSleepArchitecture.smooth(input))
    }
}

package com.noop.analytics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepsSourcePriorityTest {
    @Test fun zeppWinsWithoutCrossSourceAddition() {
        val result = StepsSourcePriority.resolve(listOf(
            DailyStepSource("com.google.android.apps.fitness", 8_900),
            DailyStepSource("com.huami.watch.hmwatchmanager", 8_200),
            DailyStepSource("other.counter", 9_700),
        ))
        assertEquals(8_200L, result?.steps)
        assertEquals(StepSourceClass.ZEPP, result?.sourceClass)
    }

    @Test fun phoneWinsWhenZeppIsAbsent() {
        val result = StepsSourcePriority.resolve(listOf(
            DailyStepSource("com.google.android.apps.fitness", 7_900),
            DailyStepSource("other.counter", 9_100),
        ))
        assertEquals(7_900L, result?.steps)
        assertEquals(StepSourceClass.PHONE, result?.sourceClass)
    }

    @Test fun otherRealCounterIsLastImportedFallback() {
        assertEquals(6_400L, StepsSourcePriority.resolve(
            listOf(DailyStepSource("other.counter", 6_400)),
        )?.steps)
    }

    @Test fun noValidRealCounterAllowsWhoopEstimateFallback() {
        assertNull(StepsSourcePriority.resolve(
            listOf(DailyStepSource("com.zepp", 0)),
        ))
    }
}

package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HrEventVisualsTest {
    @Test fun temperatureAndRecoveryContextsAreClassified() {
        assertEquals(HrEventKind.COLD, classifyHrEvent("Cold Bath"))
        assertEquals(HrEventKind.COLD, classifyHrEvent("Ice Bath"))
        assertEquals(HrEventKind.HEAT, classifyHrEvent("Warm Bath"))
        assertEquals(HrEventKind.HEAT, classifyHrEvent("Sauna"))
        assertEquals(HrEventKind.MIND_BODY, classifyHrEvent("Breathing"))
    }

    @Test fun commonSportsHaveSpecificVisualFamilies() {
        assertEquals(HrEventKind.WALK, classifyHrEvent("Walking"))
        assertEquals(HrEventKind.RUN, classifyHrEvent("Trail Running"))
        assertEquals(HrEventKind.CYCLE, classifyHrEvent("Indoor Cycling"))
        assertEquals(HrEventKind.SWIM, classifyHrEvent("Swimming"))
        assertEquals(HrEventKind.STRENGTH, classifyHrEvent("Strength Training"))
        assertEquals(HrEventKind.COMBAT, classifyHrEvent("Muay Thai"))
        assertEquals(HrEventKind.RACKET, classifyHrEvent("Badminton"))
        assertEquals(HrEventKind.TEAM, classifyHrEvent("Futsal"))
    }

    @Test fun unknownActivityKeepsSafeFallback() {
        assertEquals(HrEventKind.OTHER, classifyHrEvent("Future Sport"))
        assertEquals(HrEventKind.OTHER, classifyHrEvent(""))
    }
}

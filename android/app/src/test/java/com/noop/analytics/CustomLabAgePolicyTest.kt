package com.noop.analytics

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomLabAgePolicyTest {
    private val day = LocalDate.of(2026, 11, 11)

    @Test fun nonFasting90MgDlIsNeutral() {
        val r = CustomLabAgePolicy.nonFastingGlucose(
            CustomLabAgePolicy.Reading(90.0, "mg/dL", day, CustomLabAgePolicy.SampleCondition.NON_FASTING), day)
        assertTrue(r.eligible)
        assertEquals(0.0, r.ageDeltaYears, 0.0)
    }

    @Test fun mmolConversionIsEquivalent() {
        val a = CustomLabAgePolicy.nonFastingGlucose(
            CustomLabAgePolicy.Reading(180.0, "mg/dL", day, CustomLabAgePolicy.SampleCondition.NON_FASTING, 3.0), day)
        val b = CustomLabAgePolicy.nonFastingGlucose(
            CustomLabAgePolicy.Reading(180.0 / 18.0182, "mmol/L", day, CustomLabAgePolicy.SampleCondition.NON_FASTING, 3.0), day)
        assertEquals(a.ageDeltaYears, b.ageDeltaYears, 1e-9)
    }

    @Test fun fastingIsNeverMisclassifiedAsNonFasting() {
        val r = CustomLabAgePolicy.nonFastingGlucose(
            CustomLabAgePolicy.Reading(126.0, "mg/dL", day, CustomLabAgePolicy.SampleCondition.FASTING), day)
        assertFalse(r.eligible)
    }

    @Test fun staleReadingIsExcluded() {
        val r = CustomLabAgePolicy.nonFastingGlucose(
            CustomLabAgePolicy.Reading(210.0, "mg/dL", day.minusDays(731), CustomLabAgePolicy.SampleCondition.NON_FASTING), day)
        assertFalse(r.eligible)
    }
}

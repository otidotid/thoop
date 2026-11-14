package com.noop.analytics

import com.noop.data.LabMarkerRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabCalculationContractTest {
    private fun lab(
        key: String,
        value: Double = 90.0,
        unit: String = "cm",
        day: String = "2026-11-12",
        name: String? = null,
        profile: String? = null,
        condition: String? = null,
    ) = LabMarkerRow(
        id = "row-" + key,
        deviceId = "my-whoop",
        markerKey = key,
        category = if (key.startsWith("custom_")) "other" else "bodyMeasurement",
        day = day,
        takenAt = 1_000_000L,
        value = value,
        unit = unit,
        source = "manual",
        displayName = name,
        evidenceProfileId = profile,
        sampleCondition = condition,
    )

    @Test fun noneOfTheOutputsCanBeWrittenByAi() {
        assertTrue(LabCalculationContract.outputs.size >= 10)
        assertTrue(LabCalculationContract.outputs.all { !it.aiMayWriteScore && it.algorithmOwner == "THOOP" })
    }

    @Test fun unknownCustomMarkerIsExplanationOnlyEvenWhenNamedLikeKnownBiomarker() {
        val marker = lab("custom_apo_b", value = 112.0, unit = "mg/dL", name = "Apolipoprotein B")
        val result = LabCalculationContract.assess(marker, "2026-11-14")
        assertEquals(LabCalculationContract.Relationship.EVIDENCE_ONLY, result.relationship)
        assertTrue(result.scoreKeys.isEmpty())
    }

    @Test fun builtInWaistIsLinkedToExactlyItsExistingMathPaths() {
        val assessment = LabCalculationContract.assess(lab("waist", 95.0), "2026-11-14")
        assertEquals(LabCalculationContract.Relationship.EXISTING_INPUT, assessment.relationship)
        assertTrue("vo2max_est" in assessment.scoreKeys)
        assertTrue("fitness_age" in assessment.scoreKeys)
        assertFalse("total_calories" in assessment.scoreKeys)
        assertFalse("vitality" in assessment.scoreKeys)
    }

    @Test fun onlyExistingExplicitGlycaemicProfileCanReachAge() {
        val approved = lab(
            "custom_random_glucose", value = 175.0, unit = "mg/dL",
            profile = "glycemic_non_fasting", condition = "post_meal"
        )
        assertEquals(
            setOf("fitness_age"),
            LabCalculationContract.assess(approved, "2026-11-14").scoreKeys,
        )
        val unknown = approved.copy(evidenceProfileId = null)
        assertTrue(LabCalculationContract.assess(unknown, "2026-11-14").scoreKeys.isEmpty())
        val fasting = approved.copy(sampleCondition = "fasting")
        assertTrue(LabCalculationContract.assess(fasting, "2026-11-14").scoreKeys.isEmpty())
    }

    @Test fun laterLabNeverLeaksIntoEarlierHistoricalScore() {
        assertEquals(
            LabCalculationContract.Relationship.INVALID_INPUT,
            LabCalculationContract.assess(lab("waist", 95.0, day = "2026-11-15"), "2026-11-14").relationship,
        )
        assertNull(LabCalculationContract.resolveLatest(listOf(lab("waist", 95.0, day = "2026-11-15")), "waist", "2026-11-14"))
    }

    @Test fun imperialWaistAndWeightAreConvertedWithoutChangingRawRows() {
        val waist = lab("waist", 36.0, "in")
        val resolvedWaist = LabCalculationContract.resolveLatest(listOf(waist), "waist", "2026-11-14")!!
        assertEquals(91.44, resolvedWaist.normalizedValue, 1e-6)
        assertEquals(36.0, waist.value!!, 0.0)
        assertEquals("in_to_cm", resolvedWaist.normalization)
        val resolvedWeight = LabCalculationContract.resolveLatest(
            listOf(lab("weight", 200.0, "lbs")), "weight", "2026-11-14"
        )!!
        assertEquals(90.718474, resolvedWeight.normalizedValue, 1e-6)
    }

    @Test fun unsupportedUnitNeverBecomesAHiddenScoreInput() {
        val marker = lab("waist", 36.0, "bananas")
        assertNull(LabCalculationContract.resolveLatest(listOf(marker), "waist", "2026-11-14"))
        assertEquals(
            LabCalculationContract.Relationship.INVALID_INPUT,
            LabCalculationContract.assess(marker, "2026-11-14").relationship,
        )
    }

    @Test fun newerInvalidReadingDoesNotEraseOlderValidReading() {
        val old = lab("waist", 90.0, "cm", day = "2026-11-11")
        val invalid = lab("waist", 360.0, "cm", day = "2026-11-12").copy(id = "invalid")
        val result = LabCalculationContract.resolveLatest(listOf(old, invalid), "waist", "2026-11-14")!!
        assertEquals(old.id, result.rowId)
        assertEquals(90.0, result.normalizedValue, 0.0)
    }

    @Test fun missingDateOrNonfiniteValueIsNotScored() {
        assertNull(LabCalculationContract.resolveLatest(listOf(lab("waist", Double.NaN)), "waist", "2026-11-14"))
        assertNull(LabCalculationContract.resolveLatest(listOf(lab("waist", 90.0, day = "invalid")), "waist", "2026-11-14"))
        assertTrue(LabCalculationContract.assess(lab("waist", 95.0, day = "invalid"), "2026-11-14").scoreKeys.isEmpty())
    }

    @Test fun outputInventoryDoesNotClaimLabDrivenCaloriesOrRecoveryToday() {
        val byKey = LabCalculationContract.outputs.associateBy { it.key }
        assertTrue(byKey.getValue("total_calories").existingLabInputs.isEmpty())
        assertTrue(byKey.getValue("recovery").existingLabInputs.isEmpty())
        assertTrue(byKey.getValue("vitality").existingLabInputs.isEmpty())
    }

    @Test fun exactBodyTiesSelectSameRowRegardlessOfInputOrderAndKeepSourceEvidence() {
        val a = lab("waist", 36.0, "in").copy(id = "a", source = "csv", takenAt = 12345L)
        val b = a.copy(id = "b", value = 37.0)
        val forward = LabCalculationContract.resolveLatest(listOf(a, b), "waist", "2026-11-14")!!
        val reverse = LabCalculationContract.resolveLatest(listOf(b, a), "waist", "2026-11-14")!!
        assertEquals(forward, reverse)
        assertEquals("a", forward.rowId)
        assertEquals("my-whoop", forward.deviceId)
        assertEquals("csv", forward.source)
        assertEquals(12345L, forward.takenAt)
        assertEquals("in", forward.originalUnit)
        assertEquals("cm", forward.canonicalUnit)
        assertEquals(91.44, forward.normalizedValue, 1e-9)
    }

    private fun glucose(day: String = "2026-11-12") = lab(
        "custom_random_glucose", 180.0, "mg/dL", day,
        profile = "glycemic_non_fasting", condition = "post_meal",
    )

    @Test fun invalidNewestCustomReadingsCannotCrashOrHideOlderEligibleEvidence() {
        val valid = glucose().copy(id = "valid")
        val malformed = valid.copy(id = "bad-date", day = "0000-bad", takenAt = Long.MAX_VALUE)
        val badUnit = valid.copy(id = "bad-unit", day = "2026-11-13", unit = "cm", takenAt = Long.MAX_VALUE)
        val badCondition = valid.copy(id = "fasting", day = "2026-11-14", sampleCondition = "fasting")
        val badValue = valid.copy(id = "nan", day = "2026-11-14", value = Double.NaN)
        val badMeal = valid.copy(id = "meal", day = "2026-11-14", hoursSinceMeal = Double.NaN)
        val rows = listOf(valid, malformed, badUnit, badCondition, badValue, badMeal)
        val result = LabCalculationContract.resolveCustomGlycemic(rows, "2026-11-14")!!
        assertEquals(valid, result.row)
        assertEquals(0.06666666666666667, result.result.ageDeltaYears, 1e-12)
        assertEquals(result, LabCalculationContract.resolveCustomGlycemic(rows.reversed(), "2026-11-14"))
        assertEquals(180.0, valid.value!!, 0.0)
    }

    @Test fun customPolicyRequiresExplicitOriginalCustomProfileAndPastEligibleSample() {
        val valid = glucose()
        val invalid = listOf(
            valid.copy(evidenceProfileId = null),
            valid.copy(markerKey = "fasting_glucose"),
            valid.copy(day = "2026-11-15"),
            valid.copy(day = "2020-01-01"),
            valid.copy(value = null),
        )
        assertNull(LabCalculationContract.resolveCustomGlycemic(invalid, "2026-11-14"))
        assertNull(LabCalculationContract.resolveCustomGlycemic(listOf(valid), "invalid"))
    }

    @Test fun customResolverUsesSampleDayThenTimestampThenStableRowId() {
        val oldest = glucose("2026-11-11").copy(id = "old", takenAt = Long.MAX_VALUE)
        val low = glucose().copy(id = "a", takenAt = 2L, value = 180.0)
        val high = low.copy(id = "b", value = 200.0)
        val early = low.copy(id = "early", takenAt = 1L)
        assertEquals("a", LabCalculationContract.resolveCustomGlycemic(listOf(high, oldest, early, low), "2026-11-14")!!.row.id)
    }
}


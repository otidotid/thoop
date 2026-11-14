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
}

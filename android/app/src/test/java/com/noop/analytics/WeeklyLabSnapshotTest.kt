package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.LabMarkerRow
import com.noop.data.MetricSeriesRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression coverage at the actual calculation/output boundary; no AI or repository writes. */
class WeeklyLabSnapshotTest {
    private val day = "2026-11-14"
    private val computedId = "strap-noop"
    private val profile = UserProfile(age = 40.0, sex = "male")
    private val week = (8..14).map { n -> DailyMetric(
        "strap", "2026-11-" + n.toString().padStart(2, '0'),
        restingHr = 65, strain = 60.0, totalSleepMin = 450.0, avgHrv = 33.0, steps = 7000,
    ) }

    private fun lab(key: String, value: Double, unit: String) = LabMarkerRow(
        id = "row-" + key, deviceId = "my-whoop", markerKey = key, category = "bodyMeasurement",
        day = "2026-11-12", takenAt = 12345L, value = value, unit = unit, source = "csv",
    )

    private fun capturedBody() = listOf(
        lab("weight", 81.0, "kg"), lab("height", 180.0, "cm"),
        lab("waist", 90.0, "cm"), lab("body_fat", 20.0, "%"),
    ).associate { row -> row.markerKey to LabCalculationContract.resolveLatest(listOf(row), row.markerKey, day)!! }

    @Test fun noLabsKeepsExistingFitnessAgeAndUthFallbackValues() {
        val labs = IntelligenceEngine.WeeklyLabInputs()
        val rows = IntelligenceEngine.fitnessAgeRows(labs, week, profile, computedId, day)
        val values = rows.associate { it.key to it.value }
        assertEquals(36.182432432432435, values.getValue("fitness_age"), 1e-12)
        assertEquals(42.36923076923077, values.getValue("vo2max_est"), 1e-12)
        assertEquals(setOf("fitness_age", "vo2max_est"), values.keys)
        val provenance = IntelligenceEngine.weeklyScoreProvenance(labs, rows, computedId, week, profile)
        assertEquals("uth", provenance.single { it.key == "vo2max_est" }.sourceId)
        assertEquals("vo2max-uth-hr-ratio-v1", provenance.single { it.key == "vo2max_est" }.algorithmVersion)
    }

    @Test fun capturedBodyAndCustomPolicyDriveExistingMathAndExactEvidenceTogether() {
        val customRow = lab("custom_glucose", 180.0, "mg/dL").copy(
            category = "other", evidenceProfileId = "glycemic_non_fasting",
            sampleCondition = "post_meal", hoursSinceMeal = 2.0,
        )
        val custom = LabCalculationContract.resolveCustomGlycemic(listOf(customRow), day)!!
        val labs = IntelligenceEngine.WeeklyLabInputs(capturedBody(), customGlycemic = custom)
        val rows = IntelligenceEngine.fitnessAgeRows(labs, week, profile, computedId, day)
        val values = rows.associate { it.key to it.value }
        assertEquals(37.062612612612616, values.getValue("fitness_age"), 1e-12)
        assertEquals(47.405, values.getValue("vo2max_est"), 1e-12)
        assertEquals(25.0, values.getValue("fitness_factor_bmi"), 1e-12)
        assertEquals(0.5, values.getValue("fitness_factor_whtr"), 1e-12)
        val provenance = IntelligenceEngine.weeklyScoreProvenance(labs, rows, computedId, week, profile)
        val age = provenance.single { it.key == "fitness_age" }
        assertEquals(FitnessAgeEngine.ALGORITHM_VERSION, age.algorithmVersion)
        val evidence = age.observedInputs!!
        for (key in listOf("weight", "height", "waist", "body_fat")) assertTrue(evidence.contains("row=row-" + key))
        assertTrue(evidence.contains("raw=81.0 kg"))
        assertTrue(evidence.contains("source=csv"))
        assertTrue(evidence.contains("takenAt=12345"))
        assertTrue(evidence.contains("device=my-whoop"))
        assertTrue(evidence.contains("row=row-custom_glucose"))
        assertTrue(evidence.contains(CustomLabAgePolicy.EVIDENCE_VERSION))
        assertTrue(evidence.contains(LabCalculationContract.VERSION))
        assertEquals("nes", provenance.single { it.key == "vo2max_est" }.sourceId)
        // A subsequent original-row edit produces a new snapshot, without changing an already captured result.
        val edited = customRow.copy(value = 200.0)
        val laterLabs = labs.copy(customGlycemic = LabCalculationContract.resolveCustomGlycemic(listOf(edited), day))
        val laterRows = IntelligenceEngine.fitnessAgeRows(laterLabs, week, profile, computedId, day)
        assertEquals(values.getValue("vo2max_est"), laterRows.single { it.key == "vo2max_est" }.value, 0.0)
        assertTrue(laterRows.single { it.key == "fitness_age" }.value > values.getValue("fitness_age"))
        assertEquals(180.0, labs.customGlycemic!!.row.value!!, 0.0)
        assertEquals(rows, IntelligenceEngine.fitnessAgeRows(labs, week, profile, computedId, day))
    }

    @Test fun primaryPresenceSuppressorIsDisclosedWithoutInventingUnitsOrNumericInputs() {
        val primary = MetricSeriesRow("lab-book", "2026-11-10", "hba1c", 5.5)
        val labs = IntelligenceEngine.WeeklyLabInputs(capturedBody(), listOf(primary))
        val rows = IntelligenceEngine.fitnessAgeRows(labs, week, profile, computedId, day)
        assertEquals(36.94594594594595, rows.single { it.key == "fitness_age" }.value, 1e-12)
        val evidence = IntelligenceEngine.weeklyScoreProvenance(labs, rows, computedId, week, profile)
            .single { it.key == "fitness_age" }.observedInputs!!
        assertTrue(evidence.contains("presence-only projected markers"))
        assertTrue(evidence.contains("hba1c day=2026-11-10 projectionSource=lab-book"))
        assertTrue(evidence.contains("raw units not available"))
    }

    @Test fun labsCannotEnterVitalityBodyAgeThroughWeeklyIntegration() {
        val rows = IntelligenceEngine.vitalityRows(week, profile, computedId, day)
        assertEquals(52.43454788150012, rows.single { it.key == "vitality" }.value, 1e-12)
        assertEquals(39.02618084739995, rows.single { it.key == "body_age" }.value, 1e-12)
        val provenance = IntelligenceEngine.weeklyScoreProvenance(
            IntelligenceEngine.WeeklyLabInputs(capturedBody()), rows, computedId, week, profile,
        )
        assertTrue(provenance.all { it.algorithmVersion == VitalityEngine.ALGORITHM_VERSION })
        assertTrue(provenance.all { it.observedInputs!!.contains("VO2max is not currently included") })
        assertFalse(provenance.any { it.observedInputs!!.contains("row=row-waist") })
    }
}

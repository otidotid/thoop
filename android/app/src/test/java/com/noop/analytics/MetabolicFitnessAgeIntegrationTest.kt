package com.noop.analytics

import com.noop.ai.LabInterpretation
import com.noop.ai.MetabolicEstimate
import com.noop.data.DailyMetric
import com.noop.data.LabMarkerRow
import com.noop.data.MetricSeriesRow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** The actual wire validators and weekly calculation boundary; persistence is tested separately. */
class MetabolicFitnessAgeIntegrationTest {
    private val day = "2026-11-14"
    private val computedId = "strap-noop"
    private val profile = UserProfile(age = 40.0, sex = "male")
    private val week = (8..14).map { n -> DailyMetric(
        "strap", "2026-11-" + n.toString().padStart(2, '0'), restingHr = 65,
        strain = 60.0, totalSleepMin = 450.0, avgHrv = 33.0, steps = 7000,
    ) }

    private fun original(key: String, value: Double, unit: String) = LabMarkerRow(
        id = "row-" + key, deviceId = "my-whoop", markerKey = key, category = "bodyMeasurement",
        day = "2026-11-12", takenAt = 12345L, value = value, unit = unit, source = "csv",
    )

    private fun originals(elevatedBody: Boolean = true): List<LabMarkerRow> = listOf(
        original("weight", if (elevatedBody) 99.0 else 81.0, "kg"),
        original("height", 180.0, "cm"),
        original("waist", if (elevatedBody) 104.0 else 90.0, "cm"),
        original("body_fat", if (elevatedBody) 31.0 else 20.0, "%"),
        original("custom_glucose", 180.0, "mg/dL").copy(
            category = "other", evidenceProfileId = "glycemic_non_fasting",
            sampleCondition = "post_meal", hoursSinceMeal = 2.0,
        ),
    )

    private fun findings(rows: List<LabMarkerRow>): LabInterpretation.Result {
        val request = requireNotNull(LabInterpretation.prepare(rows, day, consent = true))
        val observations = JSONObject(request.json).getJSONArray("observations")
        val wireFindings = JSONArray()
        for (i in 0 until observations.length()) {
            val observation = observations.getJSONObject(i)
            wireFindings.put(JSONObject()
                .put("recordRef", observation.getString("recordRef"))
                .put("fingerprint", observation.getString("fingerprint"))
                .put("identity", "IDENTIFIED").put("confidence", "HIGH")
                .put("domains", JSONArray(listOf("metabolic")))
                .put("interpretation", "Interpret the dated original unit and sample condition together.")
                .put("evidence", JSONArray())
                .put("limitations", JSONArray(listOf("Experimental wellness context, not a clinical age conversion."))))
        }
        val wire = JSONObject().put("schemaVersion", LabInterpretation.VERSION)
            .put("requestId", request.requestId).put("findings", wireFindings).toString()
        return LabInterpretation.validate(wire, request, rows).also { result ->
            assertEquals(rows.size, result.findings.size)
            assertTrue(result.findings.all { it.availability == LabInterpretation.Availability.AVAILABLE &&
                it.failure == LabInterpretation.Failure.NONE })
        }
    }

    private fun request(rows: List<LabMarkerRow>) = requireNotNull(MetabolicEstimate.prepare(
        rows, findings(rows), day, profile.age, profile.sex, consent = true,
    ))

    private fun wire(request: MetabolicEstimate.Request, delta: Double): JSONObject {
        val observations = JSONObject(request.json).getJSONArray("observations")
        val refs = (0 until observations.length()).map { observations.getJSONObject(it).getString("recordRef") }
        return JSONObject().put("schemaVersion", MetabolicEstimate.VERSION).put("requestId", request.requestId)
            .put("metabolicAgeDeltaYears", delta).put("confidence", "HIGH").put("recordRefs", JSONArray(refs))
            .put("reasoning", "Experimental holistic estimate for this unchanged source snapshot.")
            .put("limitations", JSONArray(listOf("Model confidence is not clinical validation.")))
    }

    private fun accepted(rows: List<LabMarkerRow>, delta: Double): MetabolicEstimate.Accepted {
        val request = request(rows)
        return requireNotNull(MetabolicEstimate.validate(wire(request, delta).toString(), request, rows))
    }

    private fun labs(rows: List<LabMarkerRow>, estimate: MetabolicEstimate.Accepted? = null) =
        IntelligenceEngine.WeeklyLabInputs(
            body = listOf("weight", "height", "waist", "body_fat").mapNotNull { key ->
                LabCalculationContract.resolveLatest(rows, key, day)?.let { key to it }
            }.toMap(),
            customGlycemic = LabCalculationContract.resolveCustomGlycemic(rows, day),
            metabolicEstimate = estimate,
        )

    private fun score(labs: IntelligenceEngine.WeeklyLabInputs) =
        IntelligenceEngine.fitnessAgeRows(labs, week, profile, computedId, day)

    private fun List<MetricSeriesRow>.value(key: String) = single { it.key == key }.value

    @Test fun validatedWireReplacesBothLocalPenaltiesWithExactlyOneFixedBlend() {
        val rows = originals()
        val snapshot = rows.map { it.copy() }
        val estimate = accepted(rows, 3.0)
        val localLabs = labs(rows)
        val local = score(localLabs)
        val aiLabs = labs(rows, estimate)
        val ai = score(aiLabs)

        // This golden is independent of the implementation helper: fixed 80% fitness + 20% (40 + 3).
        assertEquals(37.54594594594595, ai.value("fitness_age"), 1e-12)
        assertEquals(3.0, ai.value("fitness_factor_metabolic_delta"), 0.0)
        assertTrue(localLabs.customGlycemic!!.result.ageDeltaYears > 0.0)
        assertTrue(local.value("fitness_factor_metabolic_delta") > 0.0)
        // Neither the existing body penalty nor the custom glucose delta may be added on top.
        assertEquals(ai, score(aiLabs.copy(customGlycemic = null)))
        assertNotEquals(local.value("fitness_age"), ai.value("fitness_age"), 1e-12)
        for (key in listOf("vo2max_est", "fitness_factor_bmi", "fitness_factor_whtr", "fitness_factor_body_fat"))
            assertEquals(key, local.value(key), ai.value(key), 0.0)
        assertEquals(99.0 / (1.8 * 1.8), ai.value("fitness_factor_bmi"), 1e-12)
        assertEquals(104.0 / 180.0, ai.value("fitness_factor_whtr"), 1e-12)
        assertEquals(31.0, ai.value("fitness_factor_body_fat"), 0.0)
        assertEquals(snapshot, rows)
        assertEquals(snapshot.sortedBy { it.markerKey }, estimate.originals.sortedBy { it.markerKey })
    }

    @Test fun everyOutputTupleHasSourceBoundProvenanceAndOnlyTheAiAffectedKeysChangeVersion() {
        val originals = originals()
        val estimate = accepted(originals, 3.0)
        val labs = labs(originals, estimate)
        val rows = score(labs)
        val provenance = IntelligenceEngine.weeklyScoreProvenance(labs, rows, computedId, week, profile)
        assertEquals(rows.map { Triple(it.deviceId, it.day, it.key) }.toSet(),
            provenance.map { Triple(it.deviceId, it.day, it.key) }.toSet())
        assertEquals(rows.size, provenance.size)
        assertTrue(rows.all { it.deviceId == computedId && it.day == day })
        for (trace in provenance) {
            val aiKey = trace.key in setOf("fitness_age", "fitness_factor_metabolic_delta")
            assertEquals(trace.key, if (aiKey) MetabolicFitnessAge.ALGORITHM_VERSION else FitnessAgeEngine.ALGORITHM_VERSION,
                trace.algorithmVersion)
            assertEquals(if (trace.key == "vo2max_est") "nes" else computedId, trace.sourceId)
        }
        val evidence = provenance.single { it.key == "fitness_age" }.observedInputs!!
        for (text in listOf("delta=3.0 years", "fixed blend=20%", "request=" + estimate.requestId,
            "model=" + MetabolicEstimate.MODEL, "prompt=" + MetabolicEstimate.PROMPT_VERSION,
            "schema=" + MetabolicEstimate.VERSION, "snapshotDay=" + day,
            "replaces local body metabolic component and custom glycaemic addition", "not clinical validation"))
            assertTrue(text, evidence.contains(text))
        for (original in originals) {
            assertTrue(evidence.contains("AI source row=" + original.id))
            assertTrue(evidence.contains("marker=" + original.markerKey))
            assertTrue(evidence.contains("fingerprint=" + LabInterpretation.fingerprint(original)))
            assertTrue(evidence.contains("raw=" + original.value + " " + original.unit))
            assertTrue(evidence.contains("source=" + original.source))
        }
        assertFalse(evidence.contains("Custom glycaemic policy: " + CustomLabAgePolicy.EVIDENCE_VERSION))
    }

    @Test fun acceptedScalarBoundsMoveOnlyFitnessAgeAndMetabolicDeltaAtTheFixedTwentyPercentRate() {
        val originals = originals()
        val minusTwo = score(labs(originals, accepted(originals, -2.0)))
        val zero = score(labs(originals, accepted(originals, 0.0)))
        val four = score(labs(originals, accepted(originals, 4.0)))
        assertEquals(0.8, four.value("fitness_age") - zero.value("fitness_age"), 1e-12)
        assertEquals(-0.4, minusTwo.value("fitness_age") - zero.value("fitness_age"), 1e-12)
        assertEquals(-2.0, minusTwo.value("fitness_factor_metabolic_delta"), 0.0)
        assertEquals(0.0, zero.value("fitness_factor_metabolic_delta"), 0.0)
        assertEquals(4.0, four.value("fitness_factor_metabolic_delta"), 0.0)
        assertEquals(zero.filterNot { it.key in setOf("fitness_age", "fitness_factor_metabolic_delta") },
            four.filterNot { it.key in setOf("fitness_age", "fitness_factor_metabolic_delta") })
        assertEquals(zero.filterNot { it.key in setOf("fitness_age", "fitness_factor_metabolic_delta") },
            minusTwo.filterNot { it.key in setOf("fitness_age", "fitness_factor_metabolic_delta") })
    }

    @Test fun rejectedAiOrMissingConsentFallsBackToTheExactExistingCalculation() {
        val originals = originals()
        val request = request(originals)
        val localLabs = labs(originals)
        val expected = score(localLabs)
        val expectedTrace = IntelligenceEngine.weeklyScoreProvenance(localLabs, expected, computedId, week, profile)
        val invalidWires = listOf(
            wire(request, -2.001), wire(request, 4.001), wire(request, 3.0).put("fitnessAge", 20.0),
            wire(request, 3.0).put("coefficients", JSONObject().put("metabolic", 1.0)),
            wire(request, 3.0).put("confidence", "LOW"),
        )
        for (wire in invalidWires) {
            val rejected = MetabolicEstimate.validate(wire.toString(), request, originals)
            assertNull(rejected)
            val fallbackLabs = labs(originals, rejected)
            val fallback = score(fallbackLabs)
            assertEquals(expected, fallback)
            assertEquals(expectedTrace,
                IntelligenceEngine.weeklyScoreProvenance(fallbackLabs, fallback, computedId, week, profile))
        }
        assertNull(MetabolicEstimate.prepare(originals, findings(originals), day, profile.age, profile.sex, consent = false))
        assertNull(MetabolicEstimate.prepare(originals, LabInterpretation.Result(findings = emptyList()),
            day, profile.age, profile.sex, consent = true))
        assertEquals(expected, score(localLabs))
    }

    @Test fun cacheRestoreKeepsExactOutputsAndChangedSourceOrProfileCannotReuseIt() {
        val originals = originals()
        val estimate = accepted(originals, 3.0)
        val stored = MetabolicEstimate.toJson(estimate)
        val restored = requireNotNull(MetabolicEstimate.restore(stored, originals, profile.age, profile.sex, day))
        val captured = labs(originals, estimate)
        val restoredLabs = labs(originals, restored)
        val expected = score(captured)
        assertEquals(expected, score(restoredLabs))
        assertEquals(IntelligenceEngine.weeklyScoreProvenance(captured, expected, computedId, week, profile),
            IntelligenceEngine.weeklyScoreProvenance(restoredLabs, score(restoredLabs), computedId, week, profile))

        val edited = originals.map { if (it.markerKey == "custom_glucose") it.copy(value = 200.0) else it }
        val rejected = MetabolicEstimate.validate(estimate.responseJson, estimate.request, edited)
        assertNull(rejected)
        assertNull(MetabolicEstimate.restore(stored, edited, profile.age, profile.sex, day))
        assertNull(MetabolicEstimate.restore(stored, originals, 41.0, profile.sex, day))
        assertNull(MetabolicEstimate.restore(stored, originals, profile.age, "female", day))
        assertNull(MetabolicEstimate.restore(stored, originals, profile.age, profile.sex, "2026-12-15"))
        assertEquals(score(labs(edited)), score(labs(edited, rejected)))
        assertEquals(expected, score(captured))
    }

    @Test fun optionalAiPreservesNoAiGoldensAndCannotBypassWearReadiness() {
        val empty = IntelligenceEngine.WeeklyLabInputs()
        val noLabs = score(empty)
        assertEquals(36.182432432432435, noLabs.value("fitness_age"), 1e-12)
        assertEquals(42.36923076923077, noLabs.value("vo2max_est"), 1e-12)
        assertEquals(setOf("fitness_age", "vo2max_est"), noLabs.map { it.key }.toSet())
        val localLabs = labs(originals(elevatedBody = false))
        val local = score(localLabs)
        assertEquals(37.062612612612616, local.value("fitness_age"), 1e-12)
        assertEquals(47.405, local.value("vo2max_est"), 1e-12)
        assertTrue(IntelligenceEngine.weeklyScoreProvenance(localLabs, local, computedId, week, profile)
            .all { it.algorithmVersion == FitnessAgeEngine.ALGORITHM_VERSION })

        val originals = originals()
        val aiLabs = labs(originals, accepted(originals, 3.0))
        val insufficientWeek = week.takeLast(3)
        val unavailable = IntelligenceEngine.fitnessAgeRows(aiLabs, insufficientWeek, profile, computedId, day)
        assertTrue(unavailable.isEmpty())
        assertTrue(IntelligenceEngine.weeklyScoreProvenance(aiLabs, unavailable, computedId, insufficientWeek, profile).isEmpty())
    }
}

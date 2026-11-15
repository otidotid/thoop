package com.noop.ai

import com.noop.analytics.LabCalculationContract
import com.noop.data.LabMarkerRow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MetabolicEstimateTest {
    private val day = "2026-11-14"
    private fun row(id: String = "private-row", key: String = "custom_glucose", date: String = "2026-11-12") = LabMarkerRow(
        id = id, deviceId = "private-device", markerKey = key, category = "other", day = date,
        takenAt = 1794480123L, value = 175.123456789, unit = "mg/dL", source = "manual", note = "reported note",
        referenceText = "own report range", displayName = "Custom marker", sampleCondition = "post_meal", hoursSinceMeal = 2.0,
    )
    private fun findings(rows: List<LabMarkerRow>) = LabInterpretation.Result(findings = rows.map { r ->
        LabInterpretation.Finding(r.id, LabInterpretation.fingerprint(r), LabInterpretation.Availability.AVAILABLE,
            LabInterpretation.Identity.IDENTIFIED, LabInterpretation.Confidence.MODERATE, setOf("metabolic"),
            "Interpret with the original units and sample condition.", emptyList(), listOf("Experimental interpretation; not a diagnosis."),
            LabCalculationContract.assess(r, day), LabInterpretation.Failure.NONE)
    })
    private fun prepare(rows: List<LabMarkerRow> = listOf(row())) =
        MetabolicEstimate.prepare(rows, findings(rows), day, 40.0, "male", true)!!
    private fun refs(request: MetabolicEstimate.Request): List<String> {
        val observations = JSONObject(request.json).getJSONArray("observations")
        return (0 until observations.length()).map { observations.getJSONObject(it).getString("recordRef") }
    }
    private fun response(request: MetabolicEstimate.Request, delta: Any = 1.25): JSONObject = JSONObject()
        .put("schemaVersion", MetabolicEstimate.VERSION).put("requestId", request.requestId)
        .put("metabolicAgeDeltaYears", delta).put("confidence", "HIGH").put("recordRefs", JSONArray(refs(request)))
        .put("reasoning", "Experimental wellness estimate from the dated source snapshot.")
        .put("limitations", JSONArray(listOf("Not a clinically validated age conversion.")))

    @Test fun noExportWithoutExplicitConsentAndValidProfile() {
        val rows = listOf(row())
        assertNull(MetabolicEstimate.prepare(rows, findings(rows), day, 40.0, "male", false))
        for (age in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, 120.1))
            assertNull(MetabolicEstimate.prepare(rows, findings(rows), day, age, "male", true))
        assertNull(MetabolicEstimate.prepare(rows, findings(rows), day, 40.0, "", true))
        assertNull(MetabolicEstimate.prepare(rows, findings(rows), "2026-02-30", 40.0, "male", true))
    }

    @Test fun preservesExactOriginalEvidenceAndHidesPersistentIdentifiers() {
        val original = row()
        val request = prepare(listOf(original))
        val exported = JSONObject(request.json).getJSONArray("observations").getJSONObject(0)
        assertEquals(original.value!!, exported.getDouble("value"), 0.0)
        assertEquals("mg/dL", exported.getString("unit"))
        assertEquals("manual", exported.getString("source"))
        assertEquals(original.note, exported.getString("note"))
        assertEquals(original.referenceText, exported.getString("referenceText"))
        assertFalse(request.json.contains("private-row"))
        assertFalse(request.json.contains("private-device"))
        assertEquals(listOf(original), request.originals)
    }

    @Test fun supportsMoreThan32MarkersInOneHolisticSnapshot() {
        val rows = (0 until 40).map { row("source-$it", "custom_marker_$it") }
        val request = prepare(rows)
        assertEquals(40, request.originals.size)
        assertEquals(40, JSONObject(request.json).getJSONArray("observations").length())
        assertNotNull(MetabolicEstimate.validate(response(request).toString(), request, rows))
    }

    @Test fun latestSelectionIsAsOfStableAndUsesAllMarkers() {
        val old = row("old", date = "2026-11-10")
        val chosen = row("a", date = "2026-11-12")
        val tied = chosen.copy(id = "b", value = 180.0)
        val future = row("future", date = "2026-11-15")
        val qualitative = row("qualitative", "custom_screen").copy(value = null, valueText = "negative", unit = "qualitative")
        val rows = listOf(future, tied, old, qualitative, chosen)
        val first = prepare(rows)
        val reversed = prepare(rows.reversed())
        assertEquals(listOf("a", "qualitative"), first.originals.map { it.id })
        assertEquals(first.requestId, reversed.requestId)
        assertEquals(first.json, reversed.json)
    }

    @Test fun malformedSourceDatesCannotBeSilentlyDroppedFromTheCompleteSnapshot() {
        val rows = listOf(row(), row("malformed", "custom_other", "invalid"))
        assertNull(MetabolicEstimate.prepare(rows, findings(rows), day, 40.0, "male", true))
        val request = prepare()
        val accepted = MetabolicEstimate.validate(response(request).toString(), request, request.originals)!!
        assertNull(MetabolicEstimate.restore(MetabolicEstimate.toJson(accepted), rows, 40.0, "male", day))
    }

    @Test fun invalidNewestMeasurementCannotSilentlyReuseOlderEvidence() {
        val rows = listOf(row("old", date = "2026-11-10"), row("invalid").copy(value = Double.NaN))
        assertNull(MetabolicEstimate.prepare(rows, findings(rows), day, 40.0, "male", true))
        assertNull(MetabolicEstimate.prepare(listOf(row().copy(hoursSinceMeal = -1.0)), findings(listOf(row())), day, 40.0, "male", true))
    }

    @Test fun everySelectedObservationNeedsOneAvailableUnchangedInterpretation() {
        val original = row()
        val good = findings(listOf(original))
        assertNull(MetabolicEstimate.prepare(listOf(original), LabInterpretation.Result(findings = emptyList()), day, 40.0, "male", true))
        for (finding in listOf(good.findings.single().copy(availability = LabInterpretation.Availability.UNAVAILABLE),
            good.findings.single().copy(fingerprint = "changed"), good.findings.single().copy(failure = LabInterpretation.Failure.SOURCE_CHANGED))) {
            assertNull(MetabolicEstimate.prepare(listOf(original), good.copy(findings = listOf(finding)), day, 40.0, "male", true))
        }
        assertNull(MetabolicEstimate.prepare(listOf(original), good.copy(findings = good.findings + good.findings), day, 40.0, "male", true))
    }

    @Test fun onlyFiniteBoundedHighConfidenceScalarIsAccepted() {
        val request = prepare()
        for (delta in listOf(-2.0, -0.001, 0.0, 1.25, 4.0)) {
            val accepted = MetabolicEstimate.validate(response(request, delta).toString(), request, request.originals)!!
            assertEquals(delta, accepted.deltaYears, 0.0)
            assertEquals(MetabolicEstimate.MODEL, accepted.model)
            assertEquals(MetabolicEstimate.PROMPT_VERSION, accepted.promptVersion)
        }
        for (invalid in listOf(-2.001, 4.001, "1.25", JSONObject.NULL))
            assertNull(MetabolicEstimate.validate(response(request, invalid).toString(), request, request.originals))
        for (confidence in listOf("LOW", "MODERATE", "high"))
            assertNull(MetabolicEstimate.validate(response(request).put("confidence", confidence).toString(), request, request.originals))
        assertNull(MetabolicEstimate.validate(response(request).toString().replace("1.25", "1e999"), request, request.originals))
    }

    @Test fun finalScoresWeightsCoefficientsAndUnknownFieldsAreRejected() {
        val request = prepare()
        for (key in listOf("fitnessAge", "metabolicAge", "score", "coefficients", "weight", "unit", "value"))
            assertNull(key, MetabolicEstimate.validate(response(request).put(key, 100).toString(), request, request.originals))
        assertNull(MetabolicEstimate.validate(response(request).toString() + " trailing text", request, request.originals))
        assertNull(MetabolicEstimate.validate("[]", request, request.originals))
    }

    @Test fun allReferencesMustOccurExactlyOnceWithMatchingRequestId() {
        val request = prepare(listOf(row("a", "custom_a"), row("b", "custom_b")))
        val refs = refs(request)
        for (bad in listOf(emptyList(), listOf(refs[0]), listOf(refs[0], refs[0]), listOf(refs[0], "f".repeat(64))))
            assertNull(MetabolicEstimate.validate(response(request).put("recordRefs", JSONArray(bad)).toString(), request, request.originals))
        assertNull(MetabolicEstimate.validate(response(request).put("requestId", "a".repeat(64)).toString(), request, request.originals))
    }

    @Test fun malformedReasoningOrLimitationsNeverAcquireNumericalAuthority() {
        val request = prepare()
        for (reason in listOf("", " ", "x".repeat(2001)))
            assertNull(MetabolicEstimate.validate(response(request).put("reasoning", reason).toString(), request, request.originals))
        for (limits in listOf(emptyList(), listOf(""), List(9) { "limit" }))
            assertNull(MetabolicEstimate.validate(response(request).put("limitations", JSONArray(limits)).toString(), request, request.originals))
    }

    @Test fun editsDeletedRecordsAndNewLatestSourcesInvalidateAnInFlightReply() {
        val request = prepare()
        val wire = response(request).toString()
        assertNull(MetabolicEstimate.validate(wire, request, emptyList()))
        for (edited in listOf(row().copy(value = 176.0), row().copy(unit = "mmol/L"), row().copy(source = "csv"), row().copy(note = "edited")))
            assertNull(MetabolicEstimate.validate(wire, request, listOf(edited)))
        assertNull(MetabolicEstimate.validate(wire, request, request.originals + row("new-marker", "custom_new")))
        assertNull(MetabolicEstimate.validate(wire, request, request.originals + row("newer").copy(takenAt = 1794480124)))
    }

    @Test fun durableRestoreRequiresOriginalRequestProfileAndSourceHashes() {
        val request = prepare()
        val accepted = MetabolicEstimate.validate(response(request).toString(), request, request.originals)!!
        val stored = MetabolicEstimate.toJson(accepted)
        val restored = MetabolicEstimate.restore(stored, request.originals, 40.0, "male", day)!!
        assertEquals(accepted.deltaYears, restored.deltaYears, 0.0)
        assertEquals(accepted.requestId, restored.requestId)
        assertEquals(request.originals, restored.originals)
        assertEquals(stored, MetabolicEstimate.toJson(restored))
        assertNull(MetabolicEstimate.restore(stored, listOf(row().copy(value = 180.0)), 40.0, "male", day))
        assertNull(MetabolicEstimate.restore(stored, request.originals, 41.0, "male", day))
        assertNull(MetabolicEstimate.restore(stored, request.originals, 40.0, "female", day))
        for (key in listOf("model", "promptVersion", "version"))
            assertNull(MetabolicEstimate.restore(JSONObject(stored).put(key, "changed").toString(), request.originals, 40.0, "male", day))
        val tampered = JSONObject(stored)
        tampered.getJSONObject("request").getJSONArray("observations").getJSONObject(0).put("value", 999.0)
        assertNull(MetabolicEstimate.restore(tampered.toString(), request.originals, 40.0, "male", day))
    }

    @Test fun negativeBoundarySurvivesDurableRestoreWithoutClippingOrChangingOriginals() {
        val request = prepare()
        val originals = request.originals.map { it.copy() }
        val accepted = MetabolicEstimate.validate(response(request, -2.0).toString(), request, originals)!!
        val stored = MetabolicEstimate.toJson(accepted)
        val restored = MetabolicEstimate.restore(stored, originals, 40.0, "male", day)!!
        assertEquals(-2.0, restored.deltaYears, 0.0)
        assertEquals(stored, MetabolicEstimate.toJson(restored))
        assertEquals(originals, restored.originals)
        assertEquals(originals, request.originals)
    }

    @Test fun durableRestoreExpiresAndRejectsNewSourcesBeforeTheScoreDay() {
        val request = prepare()
        val accepted = MetabolicEstimate.validate(response(request).toString(), request, request.originals)!!
        val stored = MetabolicEstimate.toJson(accepted)
        assertNotNull(MetabolicEstimate.restore(stored, request.originals, 40.0, "male", "2026-12-14"))
        assertNull(MetabolicEstimate.restore(stored, request.originals, 40.0, "male", "2026-12-15"))
        assertNull(MetabolicEstimate.restore(stored, request.originals, 40.0, "male", "2026-11-13"))
        val newer = row("later", date = "2026-11-15")
        assertNull(MetabolicEstimate.restore(stored, request.originals + newer, 40.0, "male", "2026-11-21"))
    }

    @Test fun payloadByteBoundIsExplicitAndNeverSilentlyDropsMarkers() {
        val large = row().copy(note = "\u03bb".repeat(60_000))
        assertNull(MetabolicEstimate.prepare(listOf(large), findings(listOf(large)), day, 40.0, "male", true))
        val duplicate = listOf(row(), row())
        assertNull(MetabolicEstimate.prepare(duplicate, findings(duplicate), day, 40.0, "male", true))
    }
}

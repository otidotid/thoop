package com.noop.ai

import com.noop.analytics.LabCalculationContract
import com.noop.data.LabMarkerRow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LabInterpretationTest {
    private val day = "2026-11-14"
    private fun row(id: String = "private-row") = LabMarkerRow(
        id = id, deviceId = "private-device", markerKey = "custom_magnesium", category = "other",
        day = "2026-11-12", takenAt = 1_794_480_123L, value = 0.87654321, unit = "mmol/L",
        source = "manual", referenceText = "own report range", note = "literal \"notes\"\nline",
        displayName = "Magnesium", sampleCondition = "unknown",
    )
    private fun request(rows: List<LabMarkerRow> = listOf(row())) =
        LabInterpretation.prepare(rows, day, true)!!

    private fun finding(req: LabInterpretation.Request, index: Int = 0): JSONObject {
        val original = JSONObject(req.json).getJSONArray("observations").getJSONObject(index)
        return JSONObject().put("recordRef", original.getString("recordRef"))
            .put("fingerprint", original.getString("fingerprint"))
            .put("identity", "IDENTIFIED").put("confidence", "LOW")
            .put("domains", JSONArray(listOf("metabolic")))
            .put("interpretation", "Interpret with the report and clinical context.")
            .put("evidence", JSONArray(listOf("No external source was verified.")))
            .put("limitations", JSONArray(listOf("A single observation is insufficient.")))
    }
    private fun response(req: LabInterpretation.Request, vararg items: JSONObject) =
        JSONObject().put("schemaVersion", LabInterpretation.VERSION).put("requestId", req.requestId)
            .put("findings", JSONArray(items.toList()))

    @Test fun requiresConsentBeforePreparingAnyExport() {
        assertNull(LabInterpretation.prepare(listOf(row()), day, false))
        assertNull(LabInterpretation.prepare(listOf(row()), "invalid", true))
        assertNull(LabInterpretation.prepare(emptyList(), day, true))
    }

    @Test fun optionalPayloadFallsBackWithoutChangingOrdinaryChatAtServerLimit() {
        val req = request()
        val small = JSONObject().put("question", "Explain my labs")
        assertTrue(LabInterpretation.attachIfFits(small, req))
        assertTrue(small.getBoolean("labConsent"))
        val large = JSONObject().put("healthContext", "x".repeat(130_500))
        val before = large.toString()
        assertFalse(LabInterpretation.attachIfFits(large, req))
        assertEquals(before, large.toString())
        assertFalse(large.has("labRequest"))
        // UTF-8 byte length, not character count, governs the server body limit.
        val unicode = JSONObject().put("healthContext", "\u03bb".repeat(65_250))
        assertFalse(LabInterpretation.attachIfFits(unicode, req))
    }

    @Test fun exactUnitsSourceAndPrecisionArePreservedWithoutPersistentIdentifiers() {
        val req = request()
        val exported = JSONObject(req.json).getJSONArray("observations").getJSONObject(0)
        assertEquals(0.87654321, exported.getDouble("value"), 0.0)
        assertEquals("mmol/L", exported.getString("unit"))
        assertEquals("manual", exported.getString("source"))
        assertEquals(row().note, exported.getString("note"))
        assertEquals(row().referenceText, exported.getString("referenceText"))
        assertFalse(req.json.contains("private-row"))
        assertFalse(req.json.contains("private-device"))
        assertEquals(64, exported.getString("recordRef").length)
    }

    @Test fun arbitraryCustomAndQualitativeResultsAreSupportedAsEvidenceOnly() {
        val original = row().copy(value = null, valueText = "negative", unit = "qualitative")
        val req = request(listOf(original))
        val exported = JSONObject(req.json).getJSONArray("observations").getJSONObject(0)
        assertTrue(exported.isNull("value"))
        assertEquals("negative", exported.getString("valueText"))
        val result = LabInterpretation.validate(response(req, finding(req)).toString(), req, listOf(original))
        val accepted = result.findings.single()
        assertEquals(LabInterpretation.Availability.AVAILABLE, accepted.availability)
        assertEquals(LabCalculationContract.Relationship.EVIDENCE_ONLY, accepted.calculation.relationship)
        assertTrue(accepted.calculation.scoreKeys.isEmpty())
        assertEquals(listOf(original), req.originals)
    }

    @Test fun maliciousNumericalOrFormulaFieldsRejectTheWholeResponse() {
        val req = request()
        for (key in listOf("score", "value", "unit", "coefficients", "weights", "scoreKeys")) {
            val bad = finding(req).put(key, 99.0)
            val result = LabInterpretation.validate(response(req, bad).toString(), req, req.originals)
            assertEquals(key, LabInterpretation.Failure.INVALID_RESPONSE, result.findings.single().failure)
        }
        val root = response(req, finding(req)).put("fitnessAge", 21.0)
        assertEquals(LabInterpretation.Failure.INVALID_RESPONSE,
            LabInterpretation.validate(root.toString(), req, req.originals).findings.single().failure)
    }

    @Test fun modelDomainsNeverAuthorizeScoreLinks() {
        val req = request()
        val claim = finding(req).put("domains", JSONArray(listOf("recovery", "cardiorespiratory")))
        val accepted = LabInterpretation.validate(response(req, claim).toString(), req, req.originals).findings.single()
        assertTrue(accepted.calculation.scoreKeys.isEmpty())
        val waist = row().copy(markerKey = "waist", value = 36.0, unit = "in")
        val waistReq = request(listOf(waist))
        val acceptedWaist = LabInterpretation.validate(response(waistReq, finding(waistReq)).toString(),
            waistReq, listOf(waist)).findings.single()
        assertTrue("vo2max_est" in acceptedWaist.calculation.scoreKeys)
        assertFalse("recovery" in acceptedWaist.calculation.scoreKeys)
    }

    @Test fun staleEditedDeletedOrAmbiguousOriginalIsUnavailable() {
        val req = request()
        val wire = response(req, finding(req)).toString()
        val changed = listOf(row().copy(value = 2.0), row().copy(unit = "mg/dL"),
            row().copy(source = "csv"), row().copy(note = "edited"), row().copy(day = "2026-11-13"),
            row().copy(deviceId = "other-device"))
        changed.forEach { edited ->
            assertEquals(LabInterpretation.Failure.SOURCE_CHANGED,
                LabInterpretation.validate(wire, req, listOf(edited)).findings.single().failure)
        }
        assertEquals(LabInterpretation.Failure.SOURCE_CHANGED,
            LabInterpretation.validate(wire, req, emptyList()).findings.single().failure)
        assertEquals(LabInterpretation.Failure.SOURCE_CHANGED,
            LabInterpretation.validate(wire, req, listOf(row(), row())).findings.single().failure)
    }

    @Test fun wrongFingerprintUnknownRecordOrDuplicateFindingIsRejected() {
        val req = request()
        val bads = listOf(
            response(req, finding(req).put("fingerprint", "stale")),
            response(req, finding(req).put("recordRef", "unknown")),
            response(req, finding(req), finding(req)),
            response(req, finding(req)).put("requestId", "different-request"),
            response(req, finding(req)).put("schemaVersion", "future-schema"),
        )
        bads.forEach {
            assertEquals(LabInterpretation.Failure.INVALID_RESPONSE,
                LabInterpretation.validate(it.toString(), req, req.originals).findings.single().failure)
        }
    }

    @Test fun missingFindingsAndNetworkFailureAreExplicitAndLocalRecordsRemainAvailable() {
        val req = request(listOf(row("one"), row("two")))
        val partial = LabInterpretation.validate(response(req, finding(req)).toString(), req, req.originals)
        assertEquals(LabInterpretation.Availability.AVAILABLE, partial.findings.first().availability)
        assertEquals(LabInterpretation.Failure.MISSING, partial.findings.last().failure)
        val absent = LabInterpretation.validate(null, req, req.originals)
        assertTrue(absent.findings.all { it.failure == LabInterpretation.Failure.UNAVAILABLE })
        assertEquals(2, req.originals.size)
    }

    @Test fun enumTypesAndNarrativeBoundsAreValidated() {
        val req = request()
        val bads = listOf(
            finding(req).put("confidence", 0.99), finding(req).put("identity", "DIAGNOSED"),
            finding(req).put("domains", JSONArray(listOf("fitness_age"))),
            finding(req).put("interpretation", "x".repeat(2_001)),
            finding(req).put("evidence", JSONArray(listOf(JSONObject().put("weight", 1)))),
        )
        bads.forEach {
            assertEquals(LabInterpretation.Failure.INVALID_RESPONSE,
                LabInterpretation.validate(response(req, it).toString(), req, req.originals).findings.single().failure)
        }
    }

    @Test fun malformedOrTrailingOutputNeverReachesAcceptedFindings() {
        val req = request()
        val bads = listOf("not JSON", "[]", response(req, finding(req)).toString() + " extra",
            "x".repeat(60_001), "```json\n" + response(req, finding(req)) + "\n```")
        bads.forEach {
            assertEquals(LabInterpretation.Failure.INVALID_RESPONSE,
                LabInterpretation.validate(it, req, req.originals).findings.single().failure)
        }
    }

    @Test fun nonFiniteStoredNumbersAreDisclosedAsInvalidWithoutInventingMeasurements() {
        val original = row().copy(value = Double.NaN, hoursSinceMeal = Double.POSITIVE_INFINITY)
        val req = request(listOf(original))
        val item = JSONObject(req.json).getJSONArray("observations").getJSONObject(0)
        assertTrue(item.isNull("value"))
        assertEquals("NaN", item.getString("invalidNumericText"))
        assertTrue(item.isNull("hoursSinceMeal"))
        assertEquals("Infinity", item.getString("invalidHoursSinceMealText"))
    }

    @Test fun deterministicFingerprintsIncludeAllSourceMetadataAndNullDiffersFromEmpty() {
        val original = row()
        assertEquals(LabInterpretation.fingerprint(original), LabInterpretation.fingerprint(original.copy()))
        assertNotEquals(LabInterpretation.fingerprint(original), LabInterpretation.fingerprint(original.copy(unit = "mg/dL")))
        assertNotEquals(LabInterpretation.fingerprint(original.copy(note = null)),
            LabInterpretation.fingerprint(original.copy(note = "")))
        assertEquals(request(listOf(row("two"), row("one"))).json,
            request(listOf(row("one"), row("two"))).json)
    }

    @Test fun batchLimitIsExplicitAndNeverSilentlyDropsRecords() {
        assertNull(LabInterpretation.prepare((0..LabInterpretation.MAX_ROWS).map { row(it.toString()) }, day, true))
        assertNull(LabInterpretation.prepare(listOf(row(), row()), day, true))
        assertNull(LabInterpretation.prepare(listOf(row().copy(note = "x".repeat(100_000))), day, true))
    }

    @Test fun renderedAppendixLabelsUnverifiedEvidenceAndExactOriginalSource() {
        val req = request()
        val result = LabInterpretation.validate(response(req, finding(req)).toString(), req, req.originals)
        val text = LabInterpretation.render(result, req)
        assertTrue(text.startsWith(LabInterpretation.LOCAL_APPENDIX_MARKER))
        assertTrue(text.contains("AI lab interpretation (unverified; no score changes)"))
        assertTrue(text.contains("0.87654321 mmol/L; source=manual"))
        assertTrue(text.contains("2026-11-12"))
        assertTrue(text.contains("AI evidence (unverified):"))
        assertTrue(text.contains("Existing THOOP pathway eligibility:"))
        assertFalse(text.contains("private-row"))
        assertFalse(text.contains("private-device"))
        val changed = LabInterpretation.validate(response(req, finding(req)).toString(), req, emptyList())
        val changedText = LabInterpretation.render(changed, req)
        assertTrue(changedText.contains("Original request snapshot (record changed or removed)"))
        assertFalse(changedText.contains("Existing THOOP pathway eligibility:"))
    }

    @Test fun emptyLimitationsAndMultiByteOversizeResponsesAreRejected() {
        val req = request()
        val empty = finding(req).put("limitations", JSONArray())
        assertEquals(LabInterpretation.Failure.INVALID_RESPONSE,
            LabInterpretation.validate(response(req, empty).toString(), req, req.originals).findings.single().failure)
        val huge = "\u4E00".repeat(30_000)
        assertEquals(LabInterpretation.Failure.INVALID_RESPONSE,
            LabInterpretation.validate(huge, req, req.originals).findings.single().failure)
    }

    @Test fun explicitLabQuestionsAndArbitraryCustomMarkerNamesRequestInterpretation() {
        for (question in listOf("Explain my LAB marks", "What do my blood test results mean?",
            "laboratory interpretation", "biomarker history", "Compare biomarkers", "review test results", "jelaskan hasil tes darah",
            "What about magnesium?", "my custom magnesium result")) {
            assertTrue(question, LabInterpretation.isLabQuestion(question, listOf(row())))
        }
        val custom = row().copy(markerKey = "custom_unlisted_marker", displayName = "Unlisted Marker")
        assertTrue(LabInterpretation.isLabQuestion("Explain Unlisted Marker", listOf(custom)))
        assertFalse(LabInterpretation.isLabQuestion("unlisted markers are interesting", listOf(custom)))
    }

    @Test fun ordinaryMealsWalksAndWeightTrainingDoNotRequestALabExport() {
        val weight = row().copy(markerKey = "weight", displayName = "Weight")
        for (question in listOf("Log a short walk", "Record food: chicken and rice", "plan weight training",
            "collaborate on my exercise plan", "magnesiums", "log breakfast")) {
            assertFalse(question, LabInterpretation.isLabQuestion(question, listOf(row(), weight)))
        }
        assertTrue(LabInterpretation.isLabQuestion("Explain my weight", listOf(weight)))
        assertFalse(LabInterpretation.isLabQuestion("walk", listOf(row().copy(note = "walk"))))
    }

    @Test fun localSnapshotIsRemovedBeforeConversationHistoryIsReexported() {
        val req = request()
        val appendix = LabInterpretation.render(LabInterpretation.unavailable(req), req)
        val history = "Original answer.\n\n" + appendix
        val stripped = LabInterpretation.stripLocalAppendix(history)
        assertEquals("Original answer.", stripped)
        assertFalse(stripped.contains("0.87654321"))
        assertFalse(stripped.contains("source=manual"))
        assertEquals("", LabInterpretation.stripLocalAppendix(appendix))
        val ordinary = "Ordinary answer with intentional trailing spaces.  \n"
        assertEquals(ordinary, LabInterpretation.stripLocalAppendix(ordinary))
    }
}

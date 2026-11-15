package com.noop.ai

import com.noop.data.LabMarkerRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LabInterpretationBatcherTest {
    private val day = "2026-11-14"
    private fun rows(n: Int) = (1..n).map { i -> LabMarkerRow(
        id = "row-%04d".format(i), deviceId = "my-whoop", markerKey = "custom_marker_${i % 5}",
        category = "other", day = "2026-11-12", takenAt = i.toLong(), value = i.toDouble(),
        unit = "mg/dL", source = "csv", displayName = "Custom ${i % 5}",
    ) }
    private fun response(request: LabInterpretation.Request): String {
        val observations = JSONObject(request.json).getJSONArray("observations")
        val findings = JSONArray()
        for (i in 0 until observations.length()) {
            val original = observations.getJSONObject(i)
            findings.put(JSONObject().put("recordRef", original.getString("recordRef"))
                .put("fingerprint", original.getString("fingerprint"))
                .put("identity", "IDENTIFIED").put("confidence", "MODERATE")
                .put("domains", JSONArray(listOf("other")))
                .put("interpretation", "Verify this dated original with the report.")
                .put("evidence", JSONArray()).put("limitations", JSONArray(listOf("Unverified interpretation."))))
        }
        return JSONObject().put("schemaVersion", LabInterpretation.VERSION)
            .put("requestId", request.requestId).put("findings", findings).toString()
    }

    @Test fun moreThanNinetySixHistoricalRowsAreAllRequestedAndReturned() = runBlocking {
        val originals = rows(101)
        val plan = LabInterpretationBatcher.plan(originals.reversed(), day)
        assertEquals(listOf(32, 32, 32, 5), plan.requests.map { it.originals.size })
        assertTrue(plan.localFindings.isEmpty())
        val sent = ArrayList<LabInterpretation.Request>()
        val result = LabInterpretationBatcher.run(plan, { true }, { originals }) { req ->
            sent += req; response(req)
        }
        assertEquals(4, sent.size)
        assertEquals(101, result.findings.size)
        assertEquals(originals.map { it.id }.toSet(), sent.flatMap { it.originals }.map { it.id }.toSet())
        assertTrue(result.findings.all { it.availability == LabInterpretation.Availability.AVAILABLE })
        assertEquals(originals, rows(101))
    }

    @Test fun byteBoundIncludesMultibyteTextAndDoesNotDropAnOversizedRow() = runBlocking {
        val ordinary = rows(40).map { it.copy(note = "\u4E00".repeat(2_500)) }
        val huge = rows(1).single().copy(id = "huge", note = "\u4E00".repeat(30_000))
        val originals = ordinary + huge
        val plan = LabInterpretationBatcher.plan(originals, day)
        assertTrue(plan.requests.size > 2)
        assertTrue(plan.requests.all { it.originals.size <= 32 && it.json.toByteArray(Charsets.UTF_8).size <= 60_000 })
        assertEquals(listOf("huge"), plan.localFindings.map { it.rowId })
        assertEquals(LabInterpretation.Failure.OVERSIZED, plan.localFindings.single().failure)
        val result = LabInterpretationBatcher.run(plan, { true }, { originals }, ::response)
        assertEquals(41, result.findings.size)
        assertEquals(40, result.findings.count { it.availability == LabInterpretation.Availability.AVAILABLE })
    }

    @Test fun oneUnavailableBatchDoesNotEraseTheOtherValidatedBatches() = runBlocking {
        val originals = rows(65)
        val plan = LabInterpretationBatcher.plan(originals, day)
        var calls = 0
        val result = LabInterpretationBatcher.run(plan, { true }, { originals }) { req ->
            calls++
            if (calls == 2) throw IOException("offline") else response(req)
        }
        assertEquals(3, calls)
        assertEquals(65, result.findings.size)
        assertEquals(33, result.findings.count { it.availability == LabInterpretation.Availability.AVAILABLE })
        assertEquals(32, result.findings.count { it.failure == LabInterpretation.Failure.UNAVAILABLE })
    }

    @Test fun revokingConsentStopsBeforeTheNextCallAndDiscardsAcceptedFindings() = runBlocking {
        val originals = rows(65)
        var granted = true
        var calls = 0
        val result = LabInterpretationBatcher.run(LabInterpretationBatcher.plan(originals, day),
            { granted }, { originals }) { req -> calls++; granted = false; response(req) }
        assertEquals(1, calls)
        assertTrue(result.findings.all { it.failure == LabInterpretation.Failure.CONSENT_REVOKED })
        var noConsentCalls = 0
        val denied = LabInterpretationBatcher.run(LabInterpretationBatcher.plan(originals, day),
            { false }, { originals }) { noConsentCalls++; response(it) }
        assertEquals(0, noConsentCalls)
        assertTrue(denied.findings.all { it.failure == LabInterpretation.Failure.CONSENT_REVOKED })
    }

    @Test fun editingAnAlreadyValidatedRecordInALaterBatchInvalidatesItAtCompletion() = runBlocking {
        val originals = rows(65)
        var fresh = originals
        var calls = 0
        val result = LabInterpretationBatcher.run(LabInterpretationBatcher.plan(originals, day),
            { true }, { fresh }) { req ->
            calls++
            if (calls == 3) fresh = fresh.map { if (it.id == "row-0001") it.copy(unit = "mmol/L") else it }
            response(req)
        }
        assertEquals(LabInterpretation.Failure.SOURCE_CHANGED, result.findings.single { it.rowId == "row-0001" }.failure)
        assertEquals(64, result.findings.count { it.availability == LabInterpretation.Availability.AVAILABLE })
    }

    @Test fun duplicateOriginalIdsAreUnavailableAndNeverExported() = runBlocking {
        val one = rows(1).single()
        val originals = rows(33) + one.copy(deviceId = "legacy", source = "manual")
        val plan = LabInterpretationBatcher.plan(originals, day)
        assertEquals(2, plan.localFindings.size)
        assertTrue(plan.localFindings.all { it.failure == LabInterpretation.Failure.AMBIGUOUS_SOURCE })
        assertFalse(plan.requests.flatMap { it.originals }.any { it.id == one.id })
        val result = LabInterpretationBatcher.run(plan, { true }, { originals }, ::response)
        assertEquals(34, result.findings.size)
        assertEquals(2, result.findings.count { it.failure == LabInterpretation.Failure.AMBIGUOUS_SOURCE })
    }

    @Test fun cancellationIsRethrownAndCannotBecomeAnUnavailableModelResponse() {
        var calls = 0
        try {
            runBlocking {
                val originals = rows(65)
                LabInterpretationBatcher.run(LabInterpretationBatcher.plan(originals, day),
                    { true }, { originals }) { calls++; throw CancellationException("cancelled") }
            }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { assertEquals(1, calls) }
    }

    @Test fun invalidSnapshotHasAnExplicitFindingForEachRowAndNoOutboundRequest() = runBlocking {
        val originals = rows(40)
        val plan = LabInterpretationBatcher.plan(originals, "invalid")
        assertTrue(plan.requests.isEmpty())
        assertEquals(40, plan.localFindings.size)
        val result = LabInterpretationBatcher.run(plan, { true }, { originals }) { error("Must not export") }
        assertTrue(result.findings.all { it.failure == LabInterpretation.Failure.INVALID_REQUEST })
    }

    @Test fun pagedDisplayReportsWholeSnapshotAndEveryRecordCanBeDisplayed() = runBlocking {
        val originals = rows(101)
        val plan = LabInterpretationBatcher.plan(originals, day)
        val result = LabInterpretationBatcher.run(plan, { true }, { originals }, ::response)
        val first = LabInterpretation.renderPage(result, plan.originals, batchCount = plan.requests.size)
        assertTrue(first.contains("Snapshot: 101 observations; 101 available; 0 unavailable; 4 bounded request(s)."))
        assertTrue(first.contains("Page 1 of 7; showing 16 observation(s)."))
        assertTrue(first.contains("lab page N"))
        val allPages = (0..6).joinToString("\n") { LabInterpretation.renderPage(result, plan.originals, it) }
        originals.forEach { assertTrue(allPages.contains("${it.value} mg/dL; source=csv")) }
        assertEquals(1, LabInterpretation.requestedPage("show lab page 2"))
        assertEquals(6, LabInterpretation.requestedPage("halaman lab 7"))
        assertEquals(0, LabInterpretation.requestedPage("lab page 0"))
    }
}

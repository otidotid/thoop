package com.noop.ai

import com.noop.analytics.LabCalculationContract
import com.noop.data.LabMarkerRow
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class CloudCoachMetabolicTransportTest {
    private val json = "application/json".toMediaType()
    private val day = "2026-11-14"
    private fun request(count: Int = 1): MetabolicEstimate.Request {
        val originals = (1..count).map { i -> LabMarkerRow(
            id = "private-row-$i", deviceId = "private-device", markerKey = "custom_marker_$i", category = "other",
            day = "2026-11-12", takenAt = 1234L + i, value = 0.9 + i, unit = "mmol/L", source = "manual",
        ) }
        val findings = LabInterpretation.Result(findings = originals.map { row -> LabInterpretation.Finding(
            row.id, LabInterpretation.fingerprint(row), LabInterpretation.Availability.AVAILABLE,
            LabInterpretation.Identity.UNCERTAIN, LabInterpretation.Confidence.LOW, setOf("metabolic"),
            "An uncertain marker needs context.", emptyList(), listOf("Experimental evidence."),
            LabCalculationContract.assess(row, day), LabInterpretation.Failure.NONE,
        ) })
        return MetabolicEstimate.prepare(originals, findings, day, 40.0, "male", true)!!
    }
    private fun envelope(request: MetabolicEstimate.Request): JSONObject = JSONObject()
        .put("model", MetabolicEstimate.MODEL).put("promptVersion", MetabolicEstimate.PROMPT_VERSION)
        .put("metabolicEstimate", JSONObject().put("schemaVersion", "metabolic-estimate-v1")
            .put("requestId", request.requestId).put("metabolicAgeDeltaYears", 1.25).put("confidence", "HIGH")
            .put("recordRefs", JSONArray().apply {
                val rows = JSONObject(request.json).getJSONArray("observations")
                for (i in 0 until rows.length()) put(rows.getJSONObject(i).getString("recordRef"))
            }).put("reasoning", "Experimental wellness estimate, not clinical age.")
            .put("limitations", JSONArray(listOf("Unvalidated estimate."))))

    @Test fun oneIsolatedRequestIncludesAllLatestMarkersAndNoConversationData() = runBlocking {
        val prepared = request(40)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/v1/labs/metabolic", chain.request().url.encodedPath)
            val buffer = Buffer(); chain.request().body!!.writeTo(buffer)
            val payloadText = buffer.readUtf8()
            val payload = JSONObject(payloadText)
            assertEquals(setOf("labConsent", "metabolicConsent", "metabolicRequest"), payload.keys().asSequence().toSet())
            assertTrue(payload.getBoolean("labConsent")); assertTrue(payload.getBoolean("metabolicConsent"))
            assertEquals(40, payload.getJSONObject("metabolicRequest").getJSONArray("observations").length())
            assertFalse(payloadText.contains("private-device")); assertFalse(payloadText.contains("private-row"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(envelope(prepared).toString().toResponseBody(json)).build()
        }.build()
        val wire = CloudCoachClient(client, "https://example.test").metabolicEstimate(prepared) { true }
        assertEquals(prepared.requestId, JSONObject(wire!!).getString("requestId"))
    }

    @Test fun unexpectedServerModelPromptOrExtraEnvelopeFieldsAreUnavailable() = runBlocking {
        val prepared = request()
        for (change in listOf("model", "promptVersion", "weight")) {
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                val altered = envelope(prepared).put(change, "unexpected")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(altered.toString().toResponseBody(json)).build()
            }.build()
            assertNull(CloudCoachClient(client, "https://example.test").metabolicEstimate(prepared) { true })
        }
    }

    @Test fun consentIsRecheckedAtDispatchAndAfterTheResponse() = runBlocking {
        val prepared = request()
        val calls = AtomicInteger()
        var granted = true
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet(); granted = false
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(envelope(prepared).toString().toResponseBody(json)).build()
        }.build()
        assertNull(CloudCoachClient(client, "https://example.test").metabolicEstimate(prepared) { granted })
        assertEquals(1, calls.get())
        var checks = 0
        assertNull(CloudCoachClient(client, "https://example.test").metabolicEstimate(prepared) { ++checks == 1 })
        assertEquals(1, calls.get())
    }

    @Test fun unknownLengthOversizedBodyCannotAllocateAnUnboundedString() = runBlocking {
        val body = object : ResponseBody() {
            private val bytes = Buffer().write(ByteArray(128 * 1024 + 1) { 'x'.code.toByte() })
            override fun contentType() = json
            override fun contentLength() = -1L
            override fun source(): BufferedSource = bytes
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        assertNull(CloudCoachClient(client, "https://example.test").metabolicEstimate(request()) { true })
    }

    @Test fun coroutineCancellationCancelsTheActualMetabolicHttpCall() = runBlocking {
        val active = AtomicReference<Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            active.set(chain.call())
            while (!chain.call().isCanceled()) Thread.sleep(10)
            throw IOException("cancelled")
        }.build()
        val task = async { CloudCoachClient(client, "https://example.test").metabolicEstimate(request()) { true } }
        withTimeout(3_000) { while (active.get() == null) delay(10) }
        task.cancelAndJoin()
        assertTrue(active.get().isCanceled())
    }
}

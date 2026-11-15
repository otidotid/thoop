package com.noop.ai

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

class CloudCoachLabTransportTest {
    private val json = "application/json".toMediaType()
    private fun request() = LabInterpretation.prepare(listOf(LabMarkerRow(
        id = "private-row", deviceId = "private-device", markerKey = "custom_magnesium", category = "other",
        day = "2026-11-12", takenAt = 1234L, value = 0.9, unit = "mmol/L", source = "manual",
    )), "2026-11-14", true)!!

    @Test fun labOnlyPayloadUsesDedicatedRouteWithoutConversationIdentityOrHistory() = runBlocking {
        val original = request()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("/v1/labs/interpret", chain.request().url.encodedPath)
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val payloadText = buffer.readUtf8()
            val payload = JSONObject(payloadText)
            assertEquals(setOf("labConsent", "labRequest"), payload.keys().asSequence().toSet())
            assertTrue(payload.getBoolean("labConsent"))
            assertFalse(payloadText.contains("private-row"))
            assertFalse(payloadText.contains("private-device"))
            val findings = JSONObject().put("schemaVersion", LabInterpretation.VERSION)
                .put("requestId", original.requestId).put("findings", JSONArray())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(JSONObject().put("labInterpretation", findings).toString().toResponseBody(json)).build()
        }.build()
        val result = CloudCoachClient(client, "https://example.test").interpretLabs(original) { true }
        assertEquals(original.requestId, JSONObject(result!!).getString("requestId"))
    }

    @Test fun revokedGrantAtActualDispatchPreventsTheNetworkCall() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { calls.incrementAndGet(); error("No export allowed") }.build()
        val cloud = CloudCoachClient(client, "https://example.test")
        var consentChecks = 0
        assertNull(cloud.interpretLabs(request()) { ++consentChecks == 1 })
        assertEquals(0, calls.get())
        assertNull(cloud.interpretLabs(request()) { false })
        assertEquals(0, calls.get())
    }

    @Test fun unknownLengthOversizedResponseIsUnavailable() = runBlocking {
        val body = object : ResponseBody() {
            private val bytes = Buffer().write(ByteArray(128 * 1024 + 1) { 'x'.code.toByte() })
            override fun contentType() = json
            override fun contentLength() = -1L
            override fun source(): BufferedSource = bytes
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body).build()
        }.build()
        assertNull(CloudCoachClient(client, "https://example.test").interpretLabs(request()) { true })
    }

    @Test fun oldWorkerStopsRepeatedUnsupportedBatchRequests() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls.incrementAndGet()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("Not found")
                .body("{}".toResponseBody(json)).build()
        }.build()
        val cloud = CloudCoachClient(client, "https://example.test")
        repeat(4) { assertNull(cloud.interpretLabs(request()) { true }) }
        assertEquals(1, calls.get())
    }

    @Test fun cancellingTheCoroutineCancelsTheUnderlyingHttpCall() = runBlocking {
        val active = AtomicReference<Call>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            active.set(chain.call())
            while (!chain.call().isCanceled()) Thread.sleep(10)
            throw IOException("cancelled")
        }.build()
        val task = async { CloudCoachClient(client, "https://example.test").interpretLabs(request()) { true } }
        withTimeout(3_000) { while (active.get() == null) delay(10) }
        task.cancelAndJoin()
        assertTrue(active.get().isCanceled())
    }
}

package com.noop.ai

import android.content.Context
import com.noop.BuildConfig
import com.noop.data.SecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.io.IOException
import kotlin.coroutines.resume

/** Single transport for THOOP Cloud Coach. Memory and conversation summaries stay server-side. */
class CloudCoachClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = BuildConfig.CLOUD_COACH_BASE_URL.trim().trimEnd('/'),
) {
    @Volatile private var labServiceUnavailable = false
    @Volatile private var metabolicServiceUnavailable = false
    data class NutritionEstimate(
        val mealType: String,
        val foodName: String,
        val servingDescription: String,
        val servingGrams: Double?,
        val caloriesKcal: Double,
        val minimumCaloriesKcal: Double?,
        val maximumCaloriesKcal: Double?,
        val proteinGrams: Double?,
        val carbohydrateGrams: Double?,
        val fatGrams: Double?,
        val fiberGrams: Double?,
        val confidence: String?,
        val assumptions: List<String>,
        val sources: List<String>,
    )

    data class Reply(
        val answer: String,
        val conversationId: String,
        val intent: String = "chat",
        val nutrition: NutritionEstimate? = null,
        val references: List<String> = emptyList(),
        /** Untrusted transport payload; only LabInterpretation.validate may consume it. */
        val labInterpretation: String? = null,
    )

    val configured: Boolean get() = baseUrl.startsWith("https://")

    /** An isolated, cancellable lab request: no conversation identity, history or memory payload. */
    suspend fun interpretLabs(
        labRequest: LabInterpretation.Request,
        consent: () -> Boolean,
    ): String? = suspendCancellableCoroutine { continuation ->
        if (!configured || labServiceUnavailable || !consent()) {
            continuation.resume(null)
        } else {
            val payload = JSONObject().put("labConsent", true).put("labRequest", JSONObject(labRequest.json))
            val request = Request.Builder().url("$baseUrl/v1/labs/interpret")
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON)).build()
            val call = client.newCall(request)
            call.timeout().timeout(20, TimeUnit.SECONDS)
            continuation.invokeOnCancellation { call.cancel() }
            // Re-read at the actual dispatch boundary, after preparing the request body.
            if (!continuation.isActive || !consent()) {
                call.cancel()
                if (continuation.isActive) continuation.resume(null)
            } else call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resume(null)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (response.code in setOf(401, 403, 404, 429, 503)) labServiceUnavailable = true
                        val result = if (!response.isSuccessful) null else runCatching {
                            val body = response.body ?: return@runCatching null
                            if (body.contentLength() > 128 * 1024) return@runCatching null
                            val source = body.source()
                            // Bound streaming/unknown-length bodies before allocating a response String.
                            if (source.request(128 * 1024L + 1)) return@runCatching null
                            JSONObject(source.readUtf8()).optJSONObject("labInterpretation")?.toString()
                        }.getOrNull()
                        if (continuation.isActive) continuation.resume(result)
                    }
                }
            })
        }
    }

    /** One separately consented scalar estimate. Its envelope cannot override the expected model/prompt. */
    suspend fun metabolicEstimate(
        metabolicRequest: MetabolicEstimate.Request,
        consent: () -> Boolean,
    ): String? = suspendCancellableCoroutine { continuation ->
        fun permitted() = runCatching { consent() }.getOrDefault(false)
        if (!configured || metabolicServiceUnavailable || !permitted()) {
            continuation.resume(null)
        } else {
            val payload = JSONObject().put("labConsent", true).put("metabolicConsent", true)
                .put("metabolicRequest", JSONObject(metabolicRequest.json))
            // The snapshot has its own 120k-byte cap; include the complete transport envelope here.
            if (payload.toString().toByteArray(Charsets.UTF_8).size > 128 * 1024) {
                continuation.resume(null)
            } else {
                val request = Request.Builder().url("$baseUrl/v1/labs/metabolic")
                    .header("Accept", "application/json")
                    .post(payload.toString().toRequestBody(JSON)).build()
                val call = client.newCall(request)
                call.timeout().timeout(20, TimeUnit.SECONDS)
                continuation.invokeOnCancellation { call.cancel() }
                if (!continuation.isActive || !permitted()) {
                    call.cancel()
                    if (continuation.isActive) continuation.resume(null)
                } else call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) continuation.resume(null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use {
                            if (response.code in setOf(401, 403, 404, 429, 503)) metabolicServiceUnavailable = true
                            val wire = if (!response.isSuccessful) null else runCatching {
                                val body = response.body ?: return@runCatching null
                                if (body.contentLength() > 128 * 1024) return@runCatching null
                                val source = body.source()
                                if (source.request(128 * 1024L + 1)) return@runCatching null
                                val envelope = JSONObject(source.readUtf8())
                                if (envelope.keys().asSequence().toSet() != setOf("metabolicEstimate", "model", "promptVersion") ||
                                    envelope.get("model") != MetabolicEstimate.MODEL ||
                                    envelope.get("promptVersion") != MetabolicEstimate.PROMPT_VERSION) return@runCatching null
                                envelope.optJSONObject("metabolicEstimate")?.toString()
                            }.getOrNull()
                            if (continuation.isActive) continuation.resume(if (permitted()) wire else null)
                        }
                    }
                })
            }
        }
    }

    suspend fun ask(
        context: Context,
        conversationId: String?,
        question: String,
        recentMessages: List<ChatMsg>,
        healthContext: String,
        systemPrompt: String,
        labRequest: LabInterpretation.Request? = null,
    ): Reply = withContext(Dispatchers.IO) {
        check(configured) {
            "THOOP Cloud Coach belum dikonfigurasi. Isi THOOP_CLOUD_COACH_URL di android/local.properties lalu build ulang."
        }
        val id = conversationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("userId", CloudCoachIdentity.id(context))
            .put("conversationId", id)
            .put("question", question)
            .put("localHour", java.time.LocalTime.now().hour)
            .put("healthContext", healthContext)
            .put("systemPrompt", systemPrompt)
            .put("recentMessages", JSONArray().apply {
                recentMessages.takeLast(12).forEach { msg ->
                    put(JSONObject().put("role", msg.role)
                        .put("text", LabInterpretation.stripLocalAppendix(msg.text)))
                }
            })
        if (labRequest != null) {
            LabInterpretation.attachIfFits(payload, labRequest)
        }
        val request = Request.Builder()
            .url("$baseUrl/v1/coach/chat")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Cloud Coach gagal (${response.code})."
                error(message)
            }
            val json = JSONObject(text)
            val answer = json.optString("answer").trim()
            check(answer.isNotEmpty()) { "Cloud Coach mengembalikan jawaban kosong." }
            val nutrition = json.optJSONObject("nutrition")?.let { item ->
                NutritionEstimate(
                    mealType = normalizeMealType(
                        item.optString("mealType", json.optString("mealType", "other")),
                    ),
                    foodName = item.optString("foodName").trim(),
                    servingDescription = item.optString("servingDescription").trim(),
                    servingGrams = item.optDoubleOrNull("servingGrams"),
                    caloriesKcal = item.optDouble("caloriesKcal", Double.NaN),
                    minimumCaloriesKcal = item.optDoubleOrNull("minimumCaloriesKcal"),
                    maximumCaloriesKcal = item.optDoubleOrNull("maximumCaloriesKcal"),
                    proteinGrams = item.optDoubleOrNull("proteinGrams"),
                    carbohydrateGrams = item.optDoubleOrNull("carbohydrateGrams"),
                    fatGrams = item.optDoubleOrNull("fatGrams"),
                    fiberGrams = item.optDoubleOrNull("fiberGrams"),
                    confidence = item.optString("confidence").trim().takeIf { it.isNotEmpty() },
                    assumptions = item.optJSONArray("assumptions").toStringList(),
                    sources = item.optJSONArray("sources").toStringList(),
                ).takeIf { it.foodName.isNotBlank() && it.caloriesKcal.isFinite() && it.caloriesKcal >= 0.0 }
            }
            val intent = json.optString("intent", "chat")
                .takeIf { it in SUPPORTED_INTENTS }
                ?: "chat"
            val references = json.optJSONArray("references").toReferenceList()
            Reply(
                answer = answer,
                conversationId = json.optString("conversationId", id),
                intent = intent,
                nutrition = nutrition.takeIf { intent == "food_log" },
                references = references,
                labInterpretation = json.optJSONObject("labInterpretation")?.toString(),
            )
        }
    }

    private fun normalizeMealType(value: String): String {
        val normalized = value.trim().lowercase().replace('-', '_').replace(' ', '_')
        return normalized.takeIf { it in SUPPORTED_MEAL_TYPES } ?: "other"
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
            optString(index).trim().takeIf { it.isNotEmpty() }
        }

    private fun JSONArray?.toReferenceList(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
            val item = optJSONObject(index) ?: return@mapNotNull null
            val source = item.optString("source").trim()
            val title = item.optString("title").trim()
            val url = item.optString("url").trim()
            listOf(source, title, url).filter { it.isNotBlank() }.joinToString(" | ")
                .takeIf { it.isNotBlank() }
        }

    private companion object {
        val SUPPORTED_INTENTS = setOf("chat", "food_log", "food_preview", "nutrition_summary")
        val SUPPORTED_MEAL_TYPES = setOf(
            "breakfast", "brunch", "lunch", "snack", "afternoon_snack",
            "dinner", "evening_snack", "late_night_snack", "other",
        )
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}

private object CloudCoachIdentity {
    private const val PREF = "cloud_coach_identity"
    private const val KEY = "user_id"

    fun id(context: Context): String {
        val prefs = SecurePrefs.of(context.applicationContext, PREF)
        prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY, it).apply() }
    }
}


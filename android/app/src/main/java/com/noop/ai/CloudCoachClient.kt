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
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Single transport for THOOP Cloud Coach. Memory and conversation summaries stay server-side. */
class CloudCoachClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val baseUrl: String = BuildConfig.CLOUD_COACH_BASE_URL.trim().trimEnd('/'),
) {
    data class NutritionEstimate(
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
        val nutrition: NutritionEstimate? = null,
    )

    val configured: Boolean get() = baseUrl.startsWith("https://")

    suspend fun ask(
        context: Context,
        conversationId: String?,
        question: String,
        recentMessages: List<ChatMsg>,
        healthContext: String,
        systemPrompt: String,
    ): Reply = withContext(Dispatchers.IO) {
        check(configured) {
            "THOOP Cloud Coach belum dikonfigurasi. Isi THOOP_CLOUD_COACH_URL di android/local.properties lalu build ulang."
        }
        val id = conversationId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val payload = JSONObject()
            .put("userId", CloudCoachIdentity.id(context))
            .put("conversationId", id)
            .put("question", question)
            .put("healthContext", healthContext)
            .put("systemPrompt", systemPrompt)
            .put("recentMessages", JSONArray().apply {
                recentMessages.takeLast(12).forEach { msg ->
                    put(JSONObject().put("role", msg.role).put("text", msg.text))
                }
            })
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
            Reply(answer, json.optString("conversationId", id), nutrition)
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (has(key) && !isNull(key)) optDouble(key).takeIf { it.isFinite() } else null

    private fun JSONArray?.toStringList(): List<String> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { index ->
            optString(index).trim().takeIf { it.isNotEmpty() }
        }

    private companion object {
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

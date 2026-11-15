package com.noop.data

import android.content.Context
import android.util.AtomicFile
import com.noop.ai.MetabolicEstimate
import com.noop.analytics.LabMarkerCategory
import com.noop.analytics.UserProfile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Append-only local experiment records, separate from original labs and the Room schema. */
class MetabolicEstimateStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("metabolic_estimate_v1", Context.MODE_PRIVATE)
    private val directory = File(context.applicationContext.filesDir, "metabolic-estimates-v1")
    fun enabled(): Boolean = prefs.getBoolean("enabled", false)
    fun setEnabled(value: Boolean) = synchronized(lock) {
        check(prefs.edit().putBoolean("enabled", value).commit()) { "Could not save metabolic preference" }
    }

    suspend fun current(repo: WhoopRepository, sourceId: String, day: String, profile: UserProfile): MetabolicEstimate.Accepted? {
        if (!enabled()) return null
        val id = prefs.getString("active:$sourceId", null) ?: return null
        if (!id.matches(Regex("[a-f0-9]{64}"))) return null
        val file = recordFile(sourceId, id)
        val stored = runCatching {
            if (!file.isFile || file.length() > 1_000_000) return null
            JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() }).getString("estimate")
        }.getOrNull() ?: return null
        val rows = sourceRows(repo, sourceId)
        if (!enabled()) return null
        return MetabolicEstimate.restore(stored, rows, profile.age, profile.sex, day)
    }

    /** Save the accepted estimate and prior outputs before activating it. Repeated requests cannot rewrite history. */
    fun save(
        accepted: MetabolicEstimate.Accepted, sourceId: String,
        previous: List<MetricSeriesRow>, provenance: List<ScoreInputProvenanceRow>,
    ): Boolean = synchronized(lock) {
        if (!enabled()) return false
        val id = accepted.request.requestId
        require(id.matches(Regex("[a-f0-9]{64}")))
        if (!directory.exists() && !directory.mkdirs()) return false
        val file = recordFile(sourceId, id)
        val estimate = MetabolicEstimate.toJson(accepted)
        if (file.exists()) {
            // Reproducibility: an accepted answer for an identical request is immutable.
            val original = runCatching { JSONObject(file.readText()).getString("estimate") }.getOrNull()
            if (original != estimate) return false
        } else {
            val record = JSONObject().put("estimate", estimate)
                .put("previousOutputs", JSONArray(previous.map { row -> JSONObject()
                    .put("deviceId", row.deviceId).put("day", row.day).put("key", row.key).put("value", row.value) }))
                .put("previousProvenance", JSONArray(provenance.map { row -> JSONObject()
                    .put("deviceId", row.deviceId).put("day", row.day).put("key", row.key).put("sourceId", row.sourceId)
                    .put("algorithmVersion", row.algorithmVersion ?: JSONObject.NULL)
                    .put("observedInputs", row.observedInputs ?: JSONObject.NULL) }))
            val atomic = AtomicFile(file)
            val stream = atomic.startWrite()
            try {
                stream.write(record.toString().toByteArray(Charsets.UTF_8))
                atomic.finishWrite(stream)
            } catch (failure: Exception) {
                atomic.failWrite(stream)
                throw failure
            }
        }
        prefs.edit().putString("active:$sourceId", id).commit()
    }

    private fun recordFile(sourceId: String, requestId: String): File {
        val scope = MessageDigest.getInstance("SHA-256").digest(sourceId.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return File(directory, "$scope-$requestId.json")
    }

    companion object {
        private val lock = Any()
        suspend fun sourceRows(repo: WhoopRepository, sourceId: String): List<LabMarkerRow> =
            listOf("my-whoop", sourceId).distinct().flatMap { source ->
                LabMarkerCategory.entries.flatMap { category -> repo.labMarkersByCategory(source, category.raw) }
            }
    }
}

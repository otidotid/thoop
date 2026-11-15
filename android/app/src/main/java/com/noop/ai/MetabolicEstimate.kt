package com.noop.ai

import com.noop.data.LabMarkerRow
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Explicitly opted-in, experimental AI scalar. This is not a validated clinical age model.
 * Final equations/weights and all source records remain owned by THOOP. */
object MetabolicEstimate {
    const val VERSION = "metabolic-estimate-v1"
    const val MODEL = "@cf/openai/gpt-oss-120b"
    const val PROMPT_VERSION = "metabolic-prompt-v1"
    const val MAX_REQUEST_BYTES = 120_000
    const val MAX_AGE_DAYS = 30L
    private val rootKeys = setOf("schemaVersion", "requestId", "snapshotDay", "profile", "observations")
    private val responseKeys = setOf("schemaVersion", "requestId", "metabolicAgeDeltaYears", "confidence", "recordRefs", "reasoning", "limitations")
    private val interpretationKeys = setOf("identity", "confidence", "domains", "interpretation", "limitations")
    private val observationKeys = setOf("recordRef", "fingerprint", "markerKey", "day", "takenAt", "value", "valueText", "unit", "source",
        "displayName", "sampleCondition", "hoursSinceMeal", "referenceText", "note", "interpretation")
    private val storedKeys = setOf("version", "model", "promptVersion", "request", "response")
    private val domains = setOf("cardiorespiratory", "metabolic", "recovery", "nutrition", "other")

    data class Request internal constructor(
        val json: String,
        val requestId: String,
        val originals: List<LabMarkerRow>,
        val day: String,
        val profileAge: Double,
        val sex: String,
    ) { val snapshotDay: String get() = day }

    data class Accepted internal constructor(
        val deltaYears: Double,
        val reasoning: String,
        val limitations: List<String>,
        val request: Request,
        val responseJson: String,
        val model: String = MODEL,
        val promptVersion: String = PROMPT_VERSION,
    ) {
        val originals: List<LabMarkerRow> get() = request.originals
        val requestId: String get() = request.requestId
        val day: String get() = request.day
        val profileAge: Double get() = request.profileAge
        val sex: String get() = request.sex
    }

    /** Every selected source must have a validated available interpretation. No marker-count cap. */
    fun prepare(rows: List<LabMarkerRow>, findings: LabInterpretation.Result, day: String,
        age: Double, sex: String, consent: Boolean): Request? = runCatching {
        require(consent && findings.schemaVersion == LabInterpretation.VERSION)
        val selected = latest(rows, day)
        require(selected.isNotEmpty())
        val byId = findings.findings.groupBy { it.rowId }
        val interpretations = selected.associate { row ->
            val finding = byId[row.id]?.singleOrNull() ?: error("missing or duplicate interpretation")
            require(finding.availability == LabInterpretation.Availability.AVAILABLE && finding.failure == LabInterpretation.Failure.NONE)
            require(finding.fingerprint == LabInterpretation.fingerprint(row))
            require(finding.identity != null && finding.confidence != null && !finding.interpretation.isNullOrBlank())
            row.id to JSONObject().put("identity", finding.identity.name).put("confidence", finding.confidence.name)
                .put("domains", JSONArray(finding.domains.sorted())).put("interpretation", finding.interpretation)
                .put("limitations", JSONArray(finding.limitations))
        }
        buildRequest(selected, interpretations, day, age, sex)
    }.getOrNull()

    /** A HIGH-confidence scalar is accepted only for exactly the unchanged original-source snapshot. */
    fun validate(text: String?, request: Request, currentRows: List<LabMarkerRow>): Accepted? = runCatching {
        require(text != null && text.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES)
        val response = objectJson(text)
        require(keys(response) == responseKeys && string(response, "schemaVersion") == VERSION)
        require(string(response, "requestId") == request.requestId && string(response, "confidence") == "HIGH")
        val delta = number(response, "metabolicAgeDeltaYears")
        require(delta in -2.0..4.0)
        val refs = strings(response, "recordRefs", request.originals.size, 64)
        require(refs.size == request.originals.size && refs.toSet().size == refs.size)
        require(refs.toSet() == request.originals.map(::recordRef).toSet())
        val reason = string(response, "reasoning").also { require(it.isNotBlank() && it.length <= 2_000) }
        val limitations = strings(response, "limitations", 8, 1_000).also { require(it.isNotEmpty()) }
        require(sameSources(request.originals, latest(currentRows, request.day)))
        Accepted(delta, reason, limitations, request, canonical(response))
    }.getOrNull()

    /** Persist request+wire together; neither an isolated scalar nor a current-profile edit is sufficient. */
    fun toJson(accepted: Accepted): String = canonical(JSONObject().put("version", VERSION).put("model", accepted.model)
        .put("promptVersion", accepted.promptVersion).put("request", objectJson(accepted.request.json))
        .put("response", objectJson(accepted.responseJson)))

    /** Rebuild the request from fresh originals, then check its hash, exact evidence, profile and expiry. */
    fun restore(stored: String, currentRows: List<LabMarkerRow>, profileAge: Double, sex: String, scoreDay: String): Accepted? = runCatching {
        require(stored.toByteArray(Charsets.UTF_8).size <= 400_000)
        val root = objectJson(stored)
        require(keys(root) == storedKeys && string(root, "version") == VERSION)
        require(string(root, "model") == MODEL && string(root, "promptVersion") == PROMPT_VERSION)
        val saved = root.get("request") as? JSONObject ?: error("missing request")
        require(keys(saved) == rootKeys && string(saved, "schemaVersion") == VERSION)
        val day = string(saved, "snapshotDay")
        val ageDays = ChronoUnit.DAYS.between(date(day), date(scoreDay))
        require(ageDays in 0..MAX_AGE_DAYS)
        val profile = saved.get("profile") as? JSONObject ?: error("missing profile")
        require(keys(profile) == setOf("age", "sex") && number(profile, "age") == profileAge && string(profile, "sex") == sex)
        val selected = latest(currentRows, day)
        require(selected.isNotEmpty() && sameSources(selected, latest(currentRows, scoreDay)))
        val originals = selected.associateBy(::recordRef)
        val observations = saved.get("observations") as? JSONArray ?: error("missing observations")
        require(observations.length() == selected.size)
        val seen = HashSet<String>()
        val interpretations = buildMap {
            for (i in 0 until observations.length()) {
                val item = observations.get(i) as? JSONObject ?: error("invalid observation")
                require(keys(item) == observationKeys)
                val ref = string(item, "recordRef")
                require(seen.add(ref))
                val original = originals[ref] ?: error("changed source set")
                require(string(item, "fingerprint") == LabInterpretation.fingerprint(original))
                put(original.id, item.get("interpretation") as? JSONObject ?: error("missing interpretation"))
            }
        }
        val rebuilt = buildRequest(selected, interpretations, day, profileAge, sex)
        require(rebuilt.requestId == string(saved, "requestId") && canonical(saved) == canonical(objectJson(rebuilt.json)))
        val wire = root.get("response") as? JSONObject ?: error("missing response")
        validate(canonical(wire), rebuilt, currentRows) ?: error("invalid stored estimate")
    }.getOrNull()

    private fun buildRequest(rows: List<LabMarkerRow>, interpretations: Map<String, JSONObject>, day: String, age: Double, sex: String): Request {
        date(day)
        require(age.isFinite() && age > 0 && age <= 120 && sex.isNotBlank() && sex.length <= 40)
        require(rows.isNotEmpty())
        val observations = JSONArray()
        rows.forEach { row ->
            require(row.id.isNotBlank() && row.markerKey.isNotBlank() && row.markerKey.length <= 300)
            require(row.takenAt in 0..9_007_199_254_740_991L)
            require(row.value == null || row.value.isFinite())
            require(row.value != null || !row.valueText.isNullOrBlank())
            require(row.hoursSinceMeal == null || row.hoursSinceMeal.isFinite() && row.hoursSinceMeal >= 0)
            require(row.sampleCondition == null || row.sampleCondition.length <= 100)
            val finding = interpretations[row.id] ?: error("missing interpretation")
            require(keys(finding) == interpretationKeys)
            require(string(finding, "identity") in setOf("IDENTIFIED", "UNCERTAIN"))
            require(string(finding, "confidence") in setOf("LOW", "MODERATE", "HIGH"))
            require(strings(finding, "domains", 5, 32).all { it in domains })
            require(string(finding, "interpretation").let { it.isNotBlank() && it.length <= 2_000 })
            require(strings(finding, "limitations", 8, 1_000).isNotEmpty())
            observations.put(JSONObject().put("recordRef", recordRef(row)).put("fingerprint", LabInterpretation.fingerprint(row))
                .put("markerKey", row.markerKey).put("day", row.day).put("takenAt", row.takenAt)
                .put("value", row.value ?: JSONObject.NULL).put("valueText", row.valueText ?: JSONObject.NULL)
                .put("unit", row.unit).put("source", row.source).put("displayName", row.displayName ?: JSONObject.NULL)
                .put("sampleCondition", row.sampleCondition ?: JSONObject.NULL).put("hoursSinceMeal", row.hoursSinceMeal ?: JSONObject.NULL)
                .put("referenceText", row.referenceText ?: JSONObject.NULL).put("note", row.note ?: JSONObject.NULL)
                .put("interpretation", finding))
        }
        val payload = JSONObject().put("schemaVersion", VERSION).put("snapshotDay", day)
            .put("profile", JSONObject().put("age", age).put("sex", sex)).put("observations", observations)
        val requestId = hash(canonical(payload))
        payload.put("requestId", requestId)
        val json = canonical(payload)
        require(json.toByteArray(Charsets.UTF_8).size <= MAX_REQUEST_BYTES)
        return Request(json, requestId, rows.toList(), day, age, sex)
    }

    /** Latest per marker as-of the snapshot. Invalid newest values make the snapshot unavailable. */
    private fun latest(rows: List<LabMarkerRow>, day: String): List<LabMarkerRow> {
        val snapshot = date(day)
        require(rows.map { it.id }.distinct().size == rows.size)
        // A malformed date cannot establish whether a marker belongs to this snapshot. Reject the
        // whole estimate rather than quietly presenting an incomplete source set as complete.
        return rows.map { it to date(it.day) }.filter { !it.second.isAfter(snapshot) }.map { it.first }
            .groupBy { it.markerKey }.toSortedMap().values.map { group ->
                group.sortedWith(compareByDescending<LabMarkerRow> { it.day }.thenByDescending { it.takenAt }.thenBy { it.id }).first()
            }
    }

    private fun sameSources(a: List<LabMarkerRow>, b: List<LabMarkerRow>): Boolean =
        a.map(LabInterpretation::fingerprint).sorted() == b.map(LabInterpretation::fingerprint).sorted()

    private fun date(value: String): LocalDate {
        require(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value))
        return LocalDate.parse(value)
    }
    private fun recordRef(row: LabMarkerRow): String = hash("${row.id.toByteArray(Charsets.UTF_8).size}:${row.id}${row.deviceId.toByteArray(Charsets.UTF_8).size}:${row.deviceId}")
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun objectJson(text: String): JSONObject {
        val token = JSONTokener(text)
        val value = token.nextValue() as? JSONObject ?: error("object required")
        require(token.nextClean() == '\u0000')
        return value
    }
    private fun keys(value: JSONObject): Set<String> = value.keys().asSequence().toSet()
    private fun string(value: JSONObject, key: String): String = value.get(key) as? String ?: error("string required")
    private fun number(value: JSONObject, key: String): Double =
        (value.get(key) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: error("finite number required")
    private fun strings(value: JSONObject, key: String, count: Int, chars: Int): List<String> {
        val array = value.get(key) as? JSONArray ?: error("array required")
        require(array.length() <= count)
        return (0 until array.length()).map { index -> (array.get(index) as? String ?: error("string item required"))
            .also { require(it.isNotBlank() && it.length <= chars) } }
    }
    private fun canonical(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is JSONObject -> keys(value).sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        value is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        value is String -> JSONObject.quote(value)
        value is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
        value is Boolean -> value.toString()
        else -> error("unsupported JSON value")
    }
}

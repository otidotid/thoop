package com.noop.analytics

import com.noop.data.LabMarkerRow
import java.time.LocalDate
import java.util.Locale

/**
 * Stage 4/5: THOOP, not an LLM, owns all numeric health scores.
 * Inventory and validation boundary, NOT a new scoring formula.
 * Custom lab markers remain interpretable by AI but cannot automatically
 * change a score merely because AI recognized their names.
 */
object LabCalculationContract {
    const val VERSION = "lab-score-contract-v1"

    enum class Relationship { EXISTING_INPUT, EVIDENCE_ONLY, INVALID_INPUT }

    data class ScoreContract(
        val key: String,
        val existingLabInputs: Set<String>,
        val aiMayWriteScore: Boolean = false,
        val algorithmOwner: String = "THOOP",
        val version: String = VERSION,
    )

    // ONLY existing code paths; do not advertise future integrations as active.
    val outputs: List<ScoreContract> = listOf(
        ScoreContract("vo2max_est", setOf("waist")),
        ScoreContract("fitness_age", setOf("waist", "weight", "height", "body_fat", "custom:glycemic_non_fasting")),
        ScoreContract("fitness_factor_bmi", setOf("weight", "height")),
        ScoreContract("fitness_factor_whtr", setOf("waist", "height")),
        ScoreContract("fitness_factor_body_fat", setOf("body_fat")),
        ScoreContract("vitality", emptySet()),
        ScoreContract("body_age", emptySet()),
        ScoreContract("total_calories", emptySet()),
        ScoreContract("recovery", emptySet()),
        ScoreContract("strain", emptySet()),
    )

    data class Resolved(
        val rowId: String,
        val markerKey: String,
        val sourceDay: String,
        val originalValue: Double,
        val originalUnit: String,
        val normalizedValue: Double,
        val canonicalUnit: String,
        val normalization: String,
        val contractVersion: String = VERSION,
    )

    data class Assessment(
        val relationship: Relationship,
        val scoreKeys: Set<String>,
        val reason: String,
        val contractVersion: String = VERSION,
    )

    fun existingScoreLinks(markerKey: String): Set<String> =
        outputs.asSequence()
            .filter { markerKey in it.existingLabInputs }
            .map { it.key }
            .toSet()

    /** An AI response may NEVER supply a numeric input for this function. */
    fun assess(row: LabMarkerRow, snapshotDay: String): Assessment {
        val snapshot = runCatching { LocalDate.parse(snapshotDay) }.getOrNull()
            ?: return Assessment(Relationship.INVALID_INPUT, emptySet(), "Invalid score date")
        val sampleDay = runCatching { LocalDate.parse(row.day) }.getOrNull()
            ?: return Assessment(Relationship.INVALID_INPUT, emptySet(), "Invalid lab date")
        if (sampleDay.isAfter(snapshot)) {
            return Assessment(Relationship.INVALID_INPUT, emptySet(), "Future reading cannot affect an earlier score")
        }
        if (row.markerKey.startsWith("custom_")) {
            if (row.evidenceProfileId == "glycemic_non_fasting" &&
                (row.sampleCondition == "non_fasting" || row.sampleCondition == "post_meal") &&
                row.value != null
            ) {
                val condition = if (row.sampleCondition == "post_meal")
                    CustomLabAgePolicy.SampleCondition.POST_MEAL else CustomLabAgePolicy.SampleCondition.NON_FASTING
                val policy = CustomLabAgePolicy.nonFastingGlucose(
                    CustomLabAgePolicy.Reading(
                        row.value, row.unit, sampleDay, condition, row.hoursSinceMeal
                    ), snapshot
                )
                if (policy.eligible) {
                    return Assessment(Relationship.EXISTING_INPUT, setOf("fitness_age"),
                        "Approved existing glycaemic policy " + policy.evidenceVersion)
                }
            }
            return Assessment(Relationship.EVIDENCE_ONLY, emptySet(),
                "Custom lab marker: AI may interpret; no unapproved score changes")
        }
        val keys = existingScoreLinks(row.markerKey)
        if (keys.isEmpty()) {
            return Assessment(Relationship.EVIDENCE_ONLY, emptySet(),
                "No validated numeric THOOP score pathway for this marker")
        }
        return if (normalized(row.markerKey, row.value, row.unit) != null) {
            Assessment(Relationship.EXISTING_INPUT, keys, "Verified input for existing versioned formula")
        } else {
            Assessment(Relationship.INVALID_INPUT, emptySet(), "Invalid value or unsupported unit")
        }
    }

    /**
     * Latest valid reading as-of the score date. Never assume that a number
     * entered in inches or pounds is a number in cm or kg. Raw rows persist
     * unmodified and invalid/unsupported readings are never consumed.
     */
    fun resolveLatest(
        readings: List<LabMarkerRow>,
        markerKey: String,
        snapshotDay: String,
    ): Resolved? {
        val snapshot = runCatching { LocalDate.parse(snapshotDay) }.getOrNull() ?: return null
        return readings.asSequence()
            .filter { it.markerKey == markerKey }
            .filter { runCatching { LocalDate.parse(it.day) }.getOrNull()?.let { day -> !day.isAfter(snapshot) } == true }
            .sortedWith(compareByDescending<LabMarkerRow> { it.day }.thenByDescending { it.takenAt })
            .mapNotNull { row ->
                val original = row.value ?: return@mapNotNull null
                val resolved = normalized(markerKey, original, row.unit) ?: return@mapNotNull null
                Resolved(row.id, row.markerKey, row.day, original, row.unit,
                    resolved.first, resolved.second, resolved.third)
            }
            .firstOrNull()
    }

    private fun normalized(key: String, raw: Double?, unitRaw: String): Triple<Double, String, String>? {
        val value = raw?.takeIf { it.isFinite() && it > 0.0 } ?: return null
        val unit = unitRaw.lowercase(Locale.ROOT).replace(" ", "")
        val (out, canonical, conversion) = when (key) {
            "waist", "height" -> when (unit) {
                "cm" -> Triple(value, "cm", "identity")
                "mm" -> Triple(value / 10.0, "cm", "mm_to_cm")
                "m" -> Triple(value * 100.0, "cm", "m_to_cm")
                "in", "inch", "inches" -> Triple(value * 2.54, "cm", "in_to_cm")
                else -> return null
            }
            "weight" -> when (unit) {
                "kg" -> Triple(value, "kg", "identity")
                "g" -> Triple(value / 1000.0, "kg", "g_to_kg")
                "lb", "lbs", "pound", "pounds" -> Triple(value * 0.45359237, "kg", "lb_to_kg")
                else -> return null
            }
            "body_fat" -> when (unit) {
                "%" -> Triple(value, "%", "identity")
                else -> return null
            }
            else -> return null
        }
        val physicallyPlausible = when (key) {
            "waist" -> out in 30.0..300.0
            "height" -> out in 50.0..270.0
            "weight" -> out in 10.0..500.0
            "body_fat" -> out in 1.0..80.0
            else -> false
        }
        return if (physicallyPlausible) Triple(out, canonical, conversion) else null
    }
}

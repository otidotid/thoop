package com.noop.analytics

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.max

/** Existing, versioned product heuristic for custom glycaemic markers used by THOOP Age.
 * This is not a clinically validated conversion of glucose into years of age.
 * Raw readings are never changed. This policy only returns an age delta.
 */
object CustomLabAgePolicy {
    const val EVIDENCE_VERSION = "glycemic-custom-v1-2026"

    enum class SampleCondition { FASTING, NON_FASTING, POST_MEAL, UNKNOWN }

    data class Reading(
        val value: Double,
        val unit: String,
        val day: LocalDate,
        val condition: SampleCondition,
        val hoursSinceMeal: Double? = null,
        val referenceLow: Double? = null,
        val referenceHigh: Double? = null,
    )

    data class Result(
        val ageDeltaYears: Double,
        val confidence: Double,
        val eligible: Boolean,
        val reason: String,
        val evidenceVersion: String = EVIDENCE_VERSION,
    )

    fun nonFastingGlucose(reading: Reading, snapshotDay: LocalDate): Result {
        if (reading.condition != SampleCondition.NON_FASTING && reading.condition != SampleCondition.POST_MEAL) {
            return Result(0.0, 0.0, false, "Not a non-fasting glucose reading")
        }
        if (reading.hoursSinceMeal != null && (!reading.hoursSinceMeal.isFinite() || reading.hoursSinceMeal < 0.0)) {
            return Result(0.0, 0.0, false, "Invalid meal context")
        }
        val mgDl = when (reading.unit.trim().lowercase(Locale.ROOT)) {
            "mg/dl" -> reading.value
            "mmol/l" -> reading.value * 18.0182
            else -> return Result(0.0, 0.0, false, "Unsupported glucose unit")
        }
        if (!mgDl.isFinite() || mgDl <= 0.0 || reading.day.isAfter(snapshotDay)) {
            return Result(0.0, 0.0, false, "Invalid value or future reading")
        }

        val ageDays = max(0L, ChronoUnit.DAYS.between(reading.day, snapshotDay))
        val freshness = when {
            ageDays <= 90 -> 1.0
            ageDays <= 180 -> 0.85
            ageDays <= 365 -> 0.60
            ageDays <= 730 -> 0.30
            else -> 0.0
        }
        if (freshness == 0.0) return Result(0.0, 0.0, false, "Reading is too old")

        val contextConfidence = if (reading.hoursSinceMeal != null) 0.70 else 0.40
        val confidence = contextConfidence * freshness

        // Random/non-fasting glucose must never use fasting 100/126 mg/dL thresholds.
        // A single value below 140 mg/dL is neutral. 140-199 is a weak confirmation signal.
        // >=200 is a strong follow-up signal, not a diagnosis. Product caps prevent one custom
        // reading from moving THOOP Age disproportionately.
        val rawDelta = when {
            mgDl < 140.0 -> 0.0
            mgDl < 200.0 -> ((mgDl - 140.0) / 60.0) * 0.25
            else -> 0.25
        }
        return Result(
            ageDeltaYears = (rawDelta * confidence).coerceIn(0.0, 0.25),
            confidence = confidence,
            eligible = true,
            reason = if (mgDl < 140.0) "Neutral non-fasting glucose" else "Non-fasting glucose follow-up signal",
        )
    }
}


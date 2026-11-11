package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.WorkoutRow
import kotlin.math.roundToInt

/**
 * Reconciles an imported Health Connect workout against the THOOP strap HR trace.
 *
 * Health Connect remains authoritative only for session identity, time, sport, distance and route.
 * HR, workout calories and Effort are local THOOP values. With fewer than two strap samples the
 * session stays pending; no external or synthetic metric is shown.
 *
 * Workout calories are an interval breakdown only. They are never added to DailyMetric active
 * calories, whose whole-day raw-HR pipeline already includes the same interval exactly once.
 */
object HealthConnectWorkoutReconciler {
    const val sourceId = "health-connect"

    fun reconcile(
        row: WorkoutRow,
        samples: List<HrSample>,
        profile: UserProfile,
        maxHr: Double,
        restingHr: Double?,
        effortMethod: StrainScorer.Method,
        sex: String,
    ): WorkoutRow {
        if (!row.source.equals(sourceId, ignoreCase = true)) return row

        val window = samples
            .asSequence()
            .filter { it.ts in row.startTs..row.endTs && it.bpm > 0 }
            .sortedBy { it.ts }
            .toList()

        if (window.size < 2) {
            return row.copy(avgHr = null, maxHr = null, energyKcal = null, strain = null)
        }

        val kcal = runCatching {
            Calories.estimateBoutCalories(
                hrSamples = window,
                profile = profile,
                hrmax = maxHr,
                restingHR = restingHr,
            ).first
        }.getOrNull()?.takeIf { it.isFinite() && it > 0.0 }

        val effort = runCatching {
            StrainScorer.strain(
                hr = window,
                maxHR = maxHr,
                restingHR = restingHr ?: StrainScorer.defaultRestingHR,
                method = effortMethod,
                sex = sex,
            )
        }.getOrNull()

        return row.copy(
            avgHr = window.map { it.bpm }.average().roundToInt(),
            maxHr = window.maxOf { it.bpm },
            energyKcal = kcal,
            strain = effort,
        )
    }
}
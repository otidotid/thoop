package com.noop.analytics

import kotlin.math.roundToInt

/** Pure device-first Total Calories split for Today and its Calories detail. */
object TotalCaloriesEngine {
    const val MINUTES_PER_DAY = 1_440

    data class Breakdown(
        val totalKcal: Double,
        val restingKcal: Double,
        val activeKcal: Double,
        val estimated: Boolean,
    )

    fun dailyRestingKcal(weightKg: Double, heightCm: Double, age: Int, sex: String): Double {
        if (weightKg <= 0.0 || heightCm <= 0.0 || age <= 0) return 0.0
        val sexAdjustment = when (sex.trim().lowercase()) {
            "male" -> 5.0
            "female" -> -161.0
            else -> -78.0
        }
        return (10.0 * weightKg + 6.25 * heightCm - 5.0 * age + sexAdjustment)
            .coerceAtLeast(0.0)
    }

    fun breakdown(
        activeKcal: Double?,
        weightKg: Double,
        heightCm: Double,
        age: Int,
        sex: String,
        elapsedLocalMinutes: Int,
        estimated: Boolean,
    ): Breakdown {
        val active = activeKcal?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val resting = dailyRestingKcal(weightKg, heightCm, age, sex) *
            elapsedLocalMinutes.coerceIn(0, MINUTES_PER_DAY).toDouble() / MINUTES_PER_DAY
        return Breakdown(
            totalKcal = active + resting,
            restingKcal = resting,
            activeKcal = active,
            estimated = estimated || activeKcal == null,
        )
    }

    fun rounded(value: Double): Int = value.roundToInt().coerceAtLeast(0)
}

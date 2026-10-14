package com.noop.ui

import kotlin.math.abs

/** Pure display-only models for Today dashboard rings. */
object DashboardRingProgress {
    fun bloodOxygen(spo2: Double?): Double? = spo2?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { ((it - 85.0) / 15.0).coerceIn(0.0, 1.0) }

    fun skinTemperature(deviationC: Double?, hasReading: Boolean): Double? = when {
        !hasReading -> null
        deviationC == null || !deviationC.isFinite() -> 0.75
        else -> (1.0 - abs(deviationC) / 2.0).coerceIn(0.0, 1.0)
    }

    fun totalCalories(totalKcal: Double?, targetKcal: Int?): Double? {
        val value = totalKcal?.takeIf { it.isFinite() && it >= 0.0 } ?: return null
        val target = targetKcal?.takeIf { it in 500..10_000 } ?: return 0.0
        return (value / target).coerceIn(0.0, 1.0)
    }
}

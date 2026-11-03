package com.noop.ui

import com.noop.analytics.ChargeBaselines
import com.noop.analytics.ChargeDriver
import com.noop.analytics.ScoreConfidence
import com.noop.data.DailyMetric

/**
 * One immutable read model for every Charge/readiness surface.
 *
 * Headline, breakdown, confidence, Coach/export adapters must consume the same [readDay] and the same
 * resolved baselines. This prevents a carried headline from being explained with today's unscored row,
 * or a detail surface from resolving a different baseline while refresh is in flight.
 *
 * Presentation-only: it does not rescore, persist, or change any analytics formula.
 */
internal data class ResolvedReadinessSnapshot(
    val displayDay: DailyMetric?,
    val carriedDay: DailyMetric?,
    val readDay: DailyMetric?,
    val baselines: ChargeBaselines.Resolved?,
    val drivers: List<ChargeDriver>,
    val confidence: ScoreConfidence?,
) {
    val recovery: Double? get() = readDay?.recovery
    val day: String? get() = readDay?.day

    companion object {
        fun resolve(
            baselines: ChargeBaselines.Resolved?,
            displayDay: DailyMetric?,
            carriedDay: DailyMetric? = null,
        ): ResolvedReadinessSnapshot {
            val readDay = carriedDay ?: displayDay
            return ResolvedReadinessSnapshot(
                displayDay = displayDay,
                carriedDay = carriedDay,
                readDay = readDay,
                baselines = baselines,
                drivers = recoveryChargeDrivers(baselines, readDay),
                confidence = readDay?.let { chargeConfidenceTier(baselines, it) },
            )
        }
    }
}

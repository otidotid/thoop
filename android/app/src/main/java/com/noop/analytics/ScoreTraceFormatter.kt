package com.noop.analytics

import com.noop.data.DailyMetric

/**
 * Pure, non-AI snapshot of the measurements present in one scored DailyMetric.
 *
 * A daily aggregate is not the raw RR or HR waveform. We report only values
 * actually available at the time of score persistence; never invent absent
 * measurements, and never claim this is a fully reconstructed raw-sensor trace.
 */
object ScoreTraceFormatter {
    data class Snapshot(
        val version: String,
        val measurements: String,
    )

    fun forDailyScore(key: String, day: DailyMetric?): Snapshot? {
        if (day == null) return null
        val fields = ArrayList<String>()
        fun value(label: String, number: Number?, unit: String) {
            if (number == null) return
            val v = number.toDouble()
            if (!v.isFinite()) return
            fields += label + ": " + number.toString() + " " + unit
        }
        val version = when (key) {
            "recovery" -> {
                value("Nightly RMSSD", day.avgHrv, "ms")
                value("Resting HR", day.restingHr, "bpm")
                value("Sleep", day.totalSleepMin, "min")
                value("Respiratory rate", day.respRateBpm, "breaths/min")
                value("Skin temperature deviation", day.skinTempDevC, "C")
                RecoveryScorer.ALGORITHM_VERSION
            }
            "strain" -> {
                value("Exercise sessions", day.exerciseCount, "count")
                // Strain consumes raw HR time series, not a daily HR field.
                // The waveform is not reconstructed from this summary.
                fields += "Primary input: wearable HR time series (raw samples not listed here)"
                StrainScorer.ALGORITHM_VERSION
            }
            "sleep_performance", "rest" -> {
                value("Sleep", day.totalSleepMin, "min")
                value("Efficiency", day.efficiency, "fraction")
                value("Deep", day.deepMin, "min")
                value("REM", day.remMin, "min")
                // Actual Rest scoring is owned by the RestScorer path,
                // which is NOT the sleep-staging model.
                "rest-sleep-performance-v1"
            }
            "active_kcal" -> {
                value("Daily estimated active calories", day.activeKcalEst, "kcal")
                fields += "Primary input: HR time series; estimate, not direct calorimetry"
                "active-calories-keytel-v1"
            }
            else -> return null
        }
        if (fields.isEmpty()) return null
        return Snapshot(version, "Daily input summary (partial): " + fields.joinToString("; "))
    }
}

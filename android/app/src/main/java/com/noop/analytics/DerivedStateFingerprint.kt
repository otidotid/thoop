package com.noop.analytics

import com.noop.data.DailyMetric
import com.noop.data.SleepSession

/** Stable witness for output that can change after a post-sync scoring pass. */
data class DerivedStateFingerprint(
    val daily: Int,
    val sleep: Int,
)

object DerivedStateFingerprints {
    fun of(days: List<DailyMetric>, sleeps: List<SleepSession>): DerivedStateFingerprint {
        val dailyHash = days.sortedBy { it.day }.fold(1) { acc, d ->
            31 * acc + listOf(
                d.day, d.totalSleepMin, d.restingHr, d.avgHrv, d.recovery, d.strain,
                d.steps, d.activeKcalEst,
            ).hashCode()
        }
        val sleepHash = sleeps.sortedWith(compareBy<SleepSession> { it.startTs }.thenBy { it.endTs })
            .fold(1) { acc, s -> 31 * acc + listOf(s.deviceId, s.startTs, s.endTs, s.userEdited, s.stagesJSON).hashCode() }
        return DerivedStateFingerprint(dailyHash, sleepHash)
    }

    fun changed(before: DerivedStateFingerprint, after: DerivedStateFingerprint): Boolean = before != after
}

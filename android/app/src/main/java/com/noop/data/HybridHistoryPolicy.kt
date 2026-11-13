package com.noop.data

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** A moving window by age, never a storage-deletion policy. */
internal object HybridHistoryPolicy {
    const val LAST_FULL_LOCAL_DAY = 14
    const val FIRST_SNAPSHOT_DAY = 15
    const val LAST_SNAPSHOT_DAY = 21
    const val FIRST_CLOUD_ARCHIVE_DAY = 22

    enum class Tier { FULL_LOCAL, COMPACT_LOCAL, ENCRYPTED_CLOUD_ELIGIBLE, FUTURE }

    fun tier(recordDay: LocalDate, today: LocalDate): Tier {
        val age = ChronoUnit.DAYS.between(recordDay, today)
        return when {
            age < 0 -> Tier.FUTURE
            age <= LAST_FULL_LOCAL_DAY -> Tier.FULL_LOCAL
            age <= LAST_SNAPSHOT_DAY -> Tier.COMPACT_LOCAL
            else -> Tier.ENCRYPTED_CLOUD_ELIGIBLE
        }
    }

    fun snapshotDays(today: LocalDate): ClosedRange<LocalDate> =
        today.minusDays(LAST_SNAPSHOT_DAY.toLong())..today.minusDays(FIRST_SNAPSHOT_DAY.toLong())

    fun archiveCutoff(today: LocalDate): LocalDate =
        today.minusDays(FIRST_CLOUD_ARCHIVE_DAY.toLong())

    fun lastArchiveMonth(today: LocalDate): YearMonth = YearMonth.from(archiveCutoff(today))

    /** A monthly cloud archive may include only days <= the rolling cutoff. */
    fun coveredThrough(month: YearMonth, today: LocalDate): LocalDate =
        minOf(month.atEndOfMonth(), archiveCutoff(today))

    fun isCoveredThrough(current: String?, required: LocalDate): Boolean =
        current == null || runCatching { !LocalDate.parse(current).isBefore(required) }.getOrDefault(false)
}

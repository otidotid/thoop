package com.noop.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * THOOP fork: an INACTIVE inventory for a possible Tier 2 online-history feature.
 *
 * No DAO, worker, scheduler, backup, archive or UI calls this object. In particular this file
 * must NEVER be used to delete records until encrypted export, read-back and record-level
 * restoration have been implemented and verified for BOTH databases.
 *
 * This registry is explicit so an unknown table cannot silently disappear from an archive.
 */
internal object RetentionPlan {
    const val FULL_DETAIL_DAYS = 14
    const val SNAPSHOT_END_DAYS = 21

    enum class Database { HEALTH, FOOD }
    enum class Kind { RAW_STREAM, DAILY_METRIC, HISTORY, TOMBSTONE, OPERATIONAL }
    enum class DateFormat { DAY_TEXT, EPOCH_SECONDS, PARENT_SESSION, NONE }
    enum class Band { FULL_LOCAL, SNAPSHOT_LOCAL, ONLINE_ONLY, KEEP_LOCAL }

    data class Table(
        val database: Database,
        val name: String,
        val kind: Kind,
        val dateColumn: String? = null,
        val dateFormat: DateFormat = DateFormat.NONE,
    )

    private fun raw(name: String, column: String = "ts") =
        Table(Database.HEALTH, name, Kind.RAW_STREAM, column, DateFormat.EPOCH_SECONDS)
    private fun daily(name: String) =
        Table(Database.HEALTH, name, Kind.DAILY_METRIC, "day", DateFormat.DAY_TEXT)
    private fun history(name: String, column: String, format: DateFormat) =
        Table(Database.HEALTH, name, Kind.HISTORY, column, format)
    private fun local(name: String, kind: Kind) =
        Table(Database.HEALTH, name, kind)

    /** All 34 live Room v42 HEALTH tables and the separate FOOD table. */
    val tables: List<Table> = listOf(
        local("device", Kind.OPERATIONAL),
        raw("hrSample"),
        raw("ppgHrSample"),
        raw("rrInterval"),
        raw("event"),
        raw("battery"),
        raw("spo2Sample"),
        raw("skinTempSample"),
        raw("stepSample"),
        raw("sleepStateSample"),
        raw("respSample"),
        raw("gravitySample"),
        daily("dailyMetric"),
        history("sleepSession", "startTs", DateFormat.EPOCH_SECONDS),
        daily("metricSeries"),
        daily("scoreInputProvenance"),
        history("labMarker", "day", DateFormat.DAY_TEXT),
        history("journal", "day", DateFormat.DAY_TEXT),
        history("workout", "startTs", DateFormat.EPOCH_SECONDS),
        local("dismissedWorkout", Kind.TOMBSTONE),
        local("dismissedSleep", Kind.TOMBSTONE),
        daily("appleDaily"),
        raw("appleStepHour"),
        history("coachMessage", "createdAt", DateFormat.EPOCH_SECONDS),
        raw("ppgWaveformSample"),
        history("liveSession", "startTs", DateFormat.EPOCH_SECONDS),
        raw("v18AuxSample"),
        local("pairedDevice", Kind.OPERATIONAL),
        local("dayOwnership", Kind.OPERATIONAL),
        local("liftExercise", Kind.OPERATIONAL),
        local("liftProgram", Kind.OPERATIONAL),
        local("liftProgramItem", Kind.OPERATIONAL),
        history("liftSession", "startTs", DateFormat.EPOCH_SECONDS),
        // A liftSet's nullable startTs is not a reliable day key. Join its sessionId to
        // liftSession.id, and use liftSession.startTs; never offload orphaned sets.
        history("liftSet", "sessionId", DateFormat.PARENT_SESSION),
        Table(Database.FOOD, "foodIntake", Kind.HISTORY, "localDay", DateFormat.DAY_TEXT),
    )

    init {
        check(tables.map { it.database to it.name }.distinct().size == tables.size) {
            "Duplicate THOOP retention table declaration"
        }
        check(tables.all {
            (it.dateFormat == DateFormat.NONE) == (it.dateColumn == null) &&
                ((it.kind == Kind.OPERATIONAL || it.kind == Kind.TOMBSTONE) ==
                    (it.dateFormat == DateFormat.NONE))
        }) { "Invalid THOOP retention table date mapping" }
    }

    /** Fail closed if a Room migration adds/removes a table without updating this inventory. */
    fun requireExactCoverage(database: Database, currentTableNames: Set<String>) {
        val expected = tables.filter { it.database == database }.map { it.name }.toSet()
        check(currentTableNames == expected) {
            "THOOP retention inventory mismatch for $database; unknown=" +
                (currentTableNames - expected) + ", missing=" + (expected - currentTableNames)
        }
    }

    fun requireTable(database: Database, table: String): Table =
        tables.singleOrNull { it.database == database && it.name == table }
            ?: error("No THOOP retention policy for $database/$table")

    /** Calendar-day ages are inclusive: today = 0; day 14 = full; day 21 = snapshot. */
    fun bandFor(table: Table, recordDay: LocalDate, today: LocalDate): Band {
        if (table.kind == Kind.OPERATIONAL || table.kind == Kind.TOMBSTONE) return Band.KEEP_LOCAL
        val ageDays = ChronoUnit.DAYS.between(recordDay, today)
        return when {
            ageDays <= FULL_DETAIL_DAYS -> Band.FULL_LOCAL // Future-dated rows are never removed.
            ageDays <= SNAPSHOT_END_DAYS -> Band.SNAPSHOT_LOCAL
            else -> Band.ONLINE_ONLY
        }
    }
}

package com.noop.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Tier 2 read-only snapshot builder. Source databases are NEVER changed, archived or purged here.
 * Snapshots cover days 15..21 and contain counts plus dailyMetric + nutrition totals only.
 * The small JSON cache is app-private, user-triggered, and safe to regenerate.
 */
internal class CompactDailySnapshots(context: Context) {
    private val appContext = context.applicationContext
    private val output = File(appContext.filesDir, "tier2-compact-daily-snapshots.json")

    data class Summary(
        val day: String,
        val source: String,
        val counts: Map<String, Long>,
        val sleepMin: Double? = null,
        val avgHrv: Double? = null,
        val recovery: Double? = null,
        val steps: Long? = null,
        val foodCalories: Double? = null,
    ) {
        val recordCount: Long get() = counts.values.sum()
    }

    suspend fun refresh(today: LocalDate = LocalDate.now()): List<Summary> = withContext(Dispatchers.IO) {
        val (start, endExclusive) = window(today)
        val rows = linkedMapOf<Pair<String, String>, MutableSummary>()
        val health = WhoopDatabase.get(appContext).openHelper.readableDatabase
        for (table in SNAPSHOT_TABLES) {
            val policy = RetentionPlan.requireTable(RetentionPlan.Database.HEALTH, table)
            collectCounts(health, policy, start, endExclusive, rows)
        }
        health.query(
            "SELECT day,deviceId,totalSleepMin,avgHrv,recovery,steps FROM dailyMetric " +
                "WHERE day >= ? AND day < ? ORDER BY day,deviceId", arrayOf(start, endExclusive),
        ).use { c -> while (c.moveToNext()) {
            val row = rows.getOrPut(c.getString(0) to c.getString(1)) { MutableSummary() }
            row.sleepMin = if (c.isNull(2)) null else c.getDouble(2)
            row.avgHrv = if (c.isNull(3)) null else c.getDouble(3)
            row.recovery = if (c.isNull(4)) null else c.getDouble(4)
            row.steps = if (c.isNull(5)) null else c.getLong(5)
        } }
        val food = FoodIntakeDatabase.get(appContext).openHelper.readableDatabase
        val foodPolicy = RetentionPlan.requireTable(RetentionPlan.Database.FOOD, "foodIntake")
        collectCounts(food, foodPolicy, start, endExclusive, rows)
        food.query(
            "SELECT localDay,source,SUM(caloriesKcal) FROM foodIntake " +
                "WHERE localDay >= ? AND localDay < ? GROUP BY localDay,source",
            arrayOf(start, endExclusive),
        ).use { c -> while (c.moveToNext()) {
            val row = rows.getOrPut(c.getString(0) to "food:${c.getString(1)}") { MutableSummary() }
            row.foodCalories = c.getDouble(2)
        } }
        val snapshots = rows.map { (key, v) ->
            Summary(key.first, key.second, v.counts.toMap(), v.sleepMin, v.avgHrv,
                v.recovery, v.steps, v.foodCalories)
        }.sortedWith(compareByDescending<Summary> { it.day }.thenBy { it.source })
        val array = JSONArray()
        snapshots.forEach { s ->
            val counts = JSONObject()
            s.counts.forEach { (key, count) -> counts.put(key, count) }
            array.put(JSONObject().put("day", s.day).put("source", s.source)
                .put("counts", counts).put("sleepMin", s.sleepMin).put("avgHrv", s.avgHrv)
                .put("recovery", s.recovery).put("steps", s.steps)
                .put("foodCalories", s.foodCalories))
        }
        val tmp = File(output.parentFile, output.name + ".tmp")
        try {
            tmp.writeText(array.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(output)) {
                tmp.copyTo(output, overwrite = true)
                tmp.delete()
            }
        } finally { tmp.delete() }
        snapshots
    }

    private class MutableSummary(
        val counts: MutableMap<String, Long> = linkedMapOf(),
        var sleepMin: Double? = null,
        var avgHrv: Double? = null,
        var recovery: Double? = null,
        var steps: Long? = null,
        var foodCalories: Double? = null,
    )

    private fun collectCounts(
        db: SupportSQLiteDatabase,
        policy: RetentionPlan.Table,
        first: String,
        lastExclusive: String,
        rows: MutableMap<Pair<String, String>, MutableSummary>,
    ) {
        val table = quote(policy.name)
        val cols = buildSet {
            db.query("PRAGMA table_info($table)").use { c ->
                val idx = c.getColumnIndex("name")
                while (c.moveToNext()) add(c.getString(idx))
            }
        }
        val source = when {
            policy.database == RetentionPlan.Database.FOOD -> "'food:' || t.source"
            policy.name == "workout" && "deviceId" in cols && "source" in cols ->
                "t.deviceId || ':' || t.source"
            "deviceId" in cols -> "t.deviceId"
            else -> "'app'"
        }
        val from = if (policy.dateFormat == RetentionPlan.DateFormat.PARENT_SESSION) {
            "$table t JOIN liftSession s ON s.id = t.sessionId"
        } else "$table t"
        val day = when (policy.dateFormat) {
            RetentionPlan.DateFormat.DAY_TEXT -> "t.${quote(requireNotNull(policy.dateColumn))}"
            RetentionPlan.DateFormat.EPOCH_SECONDS ->
                "date(t.${quote(requireNotNull(policy.dateColumn))},'unixepoch','localtime')"
            RetentionPlan.DateFormat.PARENT_SESSION -> "date(s.startTs,'unixepoch','localtime')"
            RetentionPlan.DateFormat.NONE -> return
        }
        db.query(
            "SELECT $day, $source, COUNT(*) FROM $from " +
                "WHERE $day >= ? AND $day < ? GROUP BY $day, $source",
            arrayOf(first, lastExclusive),
        ).use { c -> while (c.moveToNext()) {
            val key = c.getString(0) to c.getString(1)
            rows.getOrPut(key) { MutableSummary() }.counts[policy.name] = c.getLong(2)
        } }
    }

    private fun quote(value: String) = "\"${value.replace("\"", "\"\"")}\""

    companion object {
        /** Start inclusive, end exclusive: ages 21, 20, ..., 15. */
        internal fun window(today: LocalDate): Pair<String, String> =
            today.minusDays(RetentionPlan.SNAPSHOT_END_DAYS.toLong()).toString() to
                today.minusDays(RetentionPlan.FULL_DETAIL_DAYS.toLong()).toString()

        // Compact historical module coverage. Raw streams are represented by dailyMetric aggregates;
        // their original rows remain untouched in the health database.
        private val SNAPSHOT_TABLES = listOf(
            "dailyMetric", "metricSeries", "scoreInputProvenance", "sleepSession",
            "workout", "journal", "labMarker", "appleDaily", "appleStepHour",
            "coachMessage", "liveSession", "liftSession", "liftSet",
        )
    }
}

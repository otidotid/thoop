package com.noop.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.StatFs
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class GoogleDriveArchiveRecord(
    val month: String,
    val driveFileId: String,
    val sha256: String,
    val sizeBytes: Long,
    val recordCount: Long,
    val schemaVersion: Int,
    val verifiedAtMs: Long,
)

internal class GoogleDriveArchiveManifest(context: Context) {
    private val file = File(context.applicationContext.filesDir, FILE_NAME)

    fun read(): List<GoogleDriveArchiveRecord> = runCatching {
        if (!file.isFile) return emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                add(GoogleDriveArchiveRecord(
                    month = item.getString("month"),
                    driveFileId = item.getString("driveFileId"),
                    sha256 = item.getString("sha256"),
                    sizeBytes = item.getLong("sizeBytes"),
                    recordCount = item.getLong("recordCount"),
                    schemaVersion = item.getInt("schemaVersion"),
                    verifiedAtMs = item.getLong("verifiedAtMs"),
                ))
            }
        }
    }.getOrDefault(emptyList())

    @Synchronized fun put(record: GoogleDriveArchiveRecord) {
        val array = JSONArray()
        (read().filterNot { it.month == record.month } + record).sortedBy { it.month }.forEach { row ->
            array.put(JSONObject()
                .put("month", row.month).put("driveFileId", row.driveFileId)
                .put("sha256", row.sha256).put("sizeBytes", row.sizeBytes)
                .put("recordCount", row.recordCount).put("schemaVersion", row.schemaVersion)
                .put("verifiedAtMs", row.verifiedAtMs))
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(array.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
    }
    companion object { private const val FILE_NAME = "google-drive-archive-manifest.json" }
}

/** Verified monthly cold storage. No local row is deleted before every required month is verified. */
internal class GoogleDriveArchiveManager(
    context: Context,
    private val driveClient: GoogleDriveBackupClient = GoogleDriveBackupClient(),
) {
    private val appContext = context.applicationContext
    private val manifest = GoogleDriveArchiveManifest(appContext)

    suspend fun createAndVerifyArchives(
        accessToken: String,
        ownerId: String,
        today: LocalDate = LocalDate.now(),
    ): List<GoogleDriveArchiveRecord> = withContext(Dispatchers.IO) {
        val firstRetainedDay = today.minusDays(89)
        val lastArchiveMonth = lastEligibleArchiveMonth(today)
        val existing = manifest.read().associateBy { it.month }.toMutableMap()
        val oldest = oldestLocalMonth() ?: return@withContext emptyList()
        val created = mutableListOf<GoogleDriveArchiveRecord>()
        var month = oldest
        while (!month.isAfter(lastArchiveMonth)) {
            if (existing[month.toString()] == null) {
                buildUploadVerify(month, accessToken, ownerId).also {
                    manifest.put(it); existing[it.month] = it; created += it
                }
            }
            month = month.plusMonths(1)
        }
        val required = generateSequence(oldest) { it.plusMonths(1) }
            .takeWhile { !it.isAfter(lastArchiveMonth) }.map(YearMonth::toString).toList()
        check(required.all(existing::containsKey)) {
            "THOOP archive verification stopped because one or more monthly archives are not verified."
        }
        // Deliberately archive-only. Local deletion is a separate future retention gate and may not
        // run until an archive has also been reopened through History Browser.
        created
    }

    suspend fun openMonthOnline(accessToken: String, month: YearMonth): SQLiteDatabase =
        withContext(Dispatchers.IO) {
            val record = manifest.read().firstOrNull { it.month == month.toString() }
                ?: error("Archived history requires Google Drive and is not available for $month.")
            val cache = File(appContext.cacheDir, "drive-archive-cache").apply { mkdirs() }
            val blob = File(cache, "${record.month}.nooparc")
            val dbFile = File(cache, "${record.month}.sqlite")
            if (!blob.isFile || driveClient.sha256(blob) != record.sha256) {
                blob.delete(); driveClient.download(accessToken, record.driveFileId, blob)
            }
            check(blob.length() == record.sizeBytes)
            check(driveClient.sha256(blob) == record.sha256)
            extractDatabase(blob, dbFile)
            val opened = SQLiteDatabase.openDatabase(dbFile.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            check(opened.version == record.schemaVersion)
            check(countRows(opened) == record.recordCount)
            cache.listFiles()?.filterNot { it.name.startsWith(record.month) }?.forEach(File::delete)
            opened
        }

    data class ArchivePreview(
        val record: GoogleDriveArchiveRecord,
        val tableCounts: Map<String, Long>,
        val totalRecords: Long,
    )

    suspend fun previewMonthOnline(accessToken: String, month: YearMonth): ArchivePreview =
        withContext(Dispatchers.IO) {
            val record = manifest.read().firstOrNull { it.month == month.toString() }
                ?: error("No verified archive is registered for $month.")
            val opened = openMonthOnline(accessToken, month)
            try {
                val counts = linkedMapOf<String, Long>()
                opened.rawQuery(
                    "SELECT name FROM sqlite_master WHERE type='table' " +
                        "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%' ORDER BY name",
                    null,
                ).use { tables ->
                    while (tables.moveToNext()) {
                        val table = tables.getString(0)
                        opened.rawQuery("SELECT COUNT(*) FROM ${q(table)}", null).use { count ->
                            if (count.moveToFirst()) counts[table] = count.getLong(0)
                        }
                    }
                }
                ArchivePreview(record, counts, counts.values.sum())
            } finally {
                opened.close()
                clearTemporaryCache(month)
            }
        }

    fun clearTemporaryCache(month: YearMonth? = null) {
        val cache = File(appContext.cacheDir, "drive-archive-cache")
        cache.listFiles()?.filter { file -> month == null || file.name.startsWith(month.toString()) }
            ?.forEach(File::delete)
    }

    fun archives(today: LocalDate = LocalDate.now()): List<GoogleDriveArchiveRecord> =
        manifest.read().filter { record ->
            runCatching {
                YearMonth.parse(record.month) <= lastEligibleArchiveMonth(today)
            }.getOrDefault(false)
        }

    internal fun lastEligibleArchiveMonth(today: LocalDate): YearMonth =
        YearMonth.from(today.minusDays(89)).minusMonths(1)
    private suspend fun buildUploadVerify(month: YearMonth, token: String, ownerId: String): GoogleDriveArchiveRecord {
        val work = File(appContext.cacheDir, "drive-archive-work").apply { mkdirs() }
        val dbCopy = File(work, "$month.sqlite")
        val archive = File(work, "$month.nooparc")
        val verify = File(work, "$month.verify.nooparc")
        try {
            checkpointAndCopy(dbCopy)
            filterToMonth(dbCopy, month)
            val snapshot = SQLiteDatabase.openDatabase(dbCopy.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            val schema = snapshot.version
            val count = countRows(snapshot)
            snapshot.close()
            zipDatabase(dbCopy, archive, month, count, schema)
            val checksum = driveClient.sha256(archive)
            val remote = driveClient.uploadArchive(token, archive, ownerId, month.toString(), count, schema)
            check(remote.sha256 == checksum) { "Google Drive archive metadata checksum mismatch." }
            driveClient.download(token, remote.id, verify)
            check(verify.length() == archive.length()) { "Google Drive archive size mismatch." }
            check(driveClient.sha256(verify) == checksum) { "Google Drive archive SHA-256 mismatch." }
            val verifyDb = File(work, "$month.verify.sqlite")
            extractDatabase(verify, verifyDb)
            val opened = SQLiteDatabase.openDatabase(verifyDb.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
            check(opened.version == schema) { "Google Drive archive schema mismatch." }
            check(countRows(opened) == count) { "Google Drive archive record count mismatch." }
            opened.close(); verifyDb.delete()
            return GoogleDriveArchiveRecord(month.toString(), remote.id, checksum, archive.length(), count, schema, System.currentTimeMillis())
        } finally { dbCopy.delete(); archive.delete(); verify.delete() }
    }

    private fun checkpointAndCopy(destination: File) {
        val room = WhoopDatabase.get(appContext)
        room.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").close()
        val source = appContext.getDatabasePath(WhoopDatabase.DB_NAME)
        check(source.isFile)
        check(StatFs(appContext.filesDir.absolutePath).availableBytes >= source.length() * 3L) {
            "Not enough storage to build and verify a THOOP monthly archive."
        }
        source.copyTo(destination, overwrite = true)
    }

    private fun filterToMonth(file: File, month: YearMonth) {
        val fromDay = month.atDay(1); val toDay = month.plusMonths(1).atDay(1)
        val fromTs = fromDay.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val toTs = toDay.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE)
        db.beginTransaction()
        try {
            temporalTables(db).forEach { item ->
                val args = when (item.encoding) {
                    TimeEncoding.DAY_TEXT -> arrayOf(fromDay.toString(), toDay.toString())
                    TimeEncoding.EPOCH_SECONDS -> arrayOf(fromTs, toTs)
                    TimeEncoding.EPOCH_MILLIS -> arrayOf(fromTs * 1000L, toTs * 1000L)
                }
                db.execSQL("DELETE FROM ${q(item.table)} WHERE ${q(item.column)} < ? OR ${q(item.column)} >= ?", args)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction(); db.close() }
    }


    private enum class TimeEncoding { DAY_TEXT, EPOCH_SECONDS, EPOCH_MILLIS }
    private data class Temporal(val table: String, val column: String, val encoding: TimeEncoding)

    private fun temporalTables(db: SupportSQLiteDatabase): List<Temporal> =
        discoverTemporalTables(
            tableNames = {
                buildList {
                    db.query(
                        "SELECT name FROM sqlite_master WHERE type='table' " +
                            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'",
                    ).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
            },
            columns = { table ->
                buildSet {
                    db.query("PRAGMA table_info(${q(table)})").use { info ->
                        val index = info.getColumnIndex("name")
                        while (info.moveToNext()) add(info.getString(index))
                    }
                }
            },
            scalarLong = { sql -> db.query(sql).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null } },
        )

    private fun temporalTables(db: SQLiteDatabase): List<Temporal> =
        discoverTemporalTables(
            tableNames = {
                buildList {
                    db.rawQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' " +
                            "AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'",
                        null,
                    ).use { cursor -> while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
            },
            columns = { table ->
                buildSet {
                    db.rawQuery("PRAGMA table_info(${q(table)})", null).use { info ->
                        val index = info.getColumnIndex("name")
                        while (info.moveToNext()) add(info.getString(index))
                    }
                }
            },
            scalarLong = { sql -> db.rawQuery(sql, null).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null } },
        )

    private fun discoverTemporalTables(
        tableNames: () -> List<String>,
        columns: (String) -> Set<String>,
        scalarLong: (String) -> Long?,
    ): List<Temporal> {
        val nowSeconds = Instant.now().epochSecond
        val minSeconds = LocalDate.of(VALID_MIN_YEAR, 1, 1)
            .atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
        return buildList {
            tableNames().forEach { table ->
                val names = columns(table)
                DAY_COLUMNS.firstOrNull(names::contains)?.let { dayColumn ->
                    add(Temporal(table, dayColumn, TimeEncoding.DAY_TEXT))
                    return@forEach
                }
                val timestamp = SECOND_COLUMNS.firstOrNull(names::contains) ?: return@forEach
                val min = scalarLong("SELECT MIN(${q(timestamp)}) FROM ${q(table)} WHERE ${q(timestamp)} > 0")
                    ?: return@forEach
                val encoding = when {
                    min in minSeconds..(nowSeconds + FUTURE_TOLERANCE_SECONDS) -> TimeEncoding.EPOCH_SECONDS
                    min in (minSeconds * 1000L)..((nowSeconds + FUTURE_TOLERANCE_SECONDS) * 1000L) -> TimeEncoding.EPOCH_MILLIS
                    else -> return@forEach
                }
                add(Temporal(table, timestamp, encoding))
            }
        }
    }

    private fun oldestLocalMonth(): YearMonth? {
        // Never call Context.openOrCreateDatabase() or close this handle. Room owns this database
        // lifecycle; closing an independently obtained handle can close Room's shared connection.
        val db = WhoopDatabase.get(appContext).openHelper.readableDatabase
        val now = LocalDate.now()
        return temporalTables(db).mapNotNull { item ->
            when (item.encoding) {
                TimeEncoding.DAY_TEXT -> db.query(
                    "SELECT MIN(${q(item.column)}) FROM ${q(item.table)} " +
                        "WHERE ${q(item.column)} >= '$VALID_MIN_YEAR-01-01' AND ${q(item.column)} <= '${now}'",
                ).use { c ->
                    if (!c.moveToFirst() || c.isNull(0)) null
                    else runCatching { YearMonth.from(LocalDate.parse(c.getString(0))) }.getOrNull()
                }
                TimeEncoding.EPOCH_SECONDS, TimeEncoding.EPOCH_MILLIS -> {
                    val divisor = if (item.encoding == TimeEncoding.EPOCH_MILLIS) 1000L else 1L
                    db.query("SELECT MIN(${q(item.column)}) FROM ${q(item.table)} WHERE ${q(item.column)} > 0").use { c ->
                        if (!c.moveToFirst() || c.isNull(0)) null else {
                            val seconds = c.getLong(0) / divisor
                            runCatching { YearMonth.from(Instant.ofEpochSecond(seconds).atZone(ZoneId.systemDefault())) }
                                .getOrNull()?.takeIf { it.year in VALID_MIN_YEAR..now.year }
                        }
                    }
                }
            }
        }.minOrNull()
    }

    private fun countRows(db: SQLiteDatabase): Long {
        var total = 0L
        db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'room_%'", null).use { tables ->
            while (tables.moveToNext()) db.rawQuery("SELECT COUNT(*) FROM ${q(tables.getString(0))}", null).use { c -> if (c.moveToFirst()) total += c.getLong(0) }
        }
        return total
    }

    private fun zipDatabase(db: File, archive: File, month: YearMonth, count: Long, schema: Int) {
        ZipOutputStream(FileOutputStream(archive).buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(DB_ENTRY)); FileInputStream(db).use { it.copyTo(zip) }; zip.closeEntry()
            zip.putNextEntry(ZipEntry(META_ENTRY))
            zip.write(JSONObject().put("month", month.toString()).put("recordCount", count).put("schemaVersion", schema).toString().toByteArray())
            zip.closeEntry()
        }
    }
    private fun extractDatabase(archive: File, destination: File) = ZipFile(archive).use { zip ->
        val entry = zip.getEntry(DB_ENTRY) ?: error("Archived history database is missing.")
        zip.getInputStream(entry).use { input -> FileOutputStream(destination).use(input::copyTo) }
    }
    private fun q(value: String) = "\"${value.replace("\"", "\"\"")}\""

    companion object {
        private const val DB_ENTRY = "noop-archive.sqlite"
        private const val META_ENTRY = "archive-manifest.json"
        private val DAY_COLUMNS = listOf("day", "localDay")
        private val SECOND_COLUMNS = listOf("ts", "startTs", "consumedAt", "createdAt", "updatedAt")
        private const val VALID_MIN_YEAR = 2020
        private const val FUTURE_TOLERANCE_SECONDS = 366L * 24L * 60L * 60L
    }
}

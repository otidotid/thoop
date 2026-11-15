package com.noop.ingest

import android.app.Application
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.room.Room
import com.noop.data.AppleDaily
import com.noop.data.DailyMetric
import com.noop.data.ImportSummary
import com.noop.data.LabMarkerRow
import com.noop.data.WhoopDatabase
import com.noop.data.WhoopRepository
import com.noop.data.WorkoutRow
import com.noop.ui.NoopPrefs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId

/** Only the provider is injected: real SDK records, production import path and real Room writes. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class HealthConnectImportPreservationTest {
    private lateinit var context: Context
    private lateinit var db: WhoopDatabase
    private lateinit var repo: WhoopRepository
    private val day = LocalDate.now().minusDays(1).toString()
    private val start = LocalDate.parse(day).atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant()
    private val offset = ZoneId.systemDefault().rules.getOffset(start)
    private val originalAggregate = AppleDaily("health-connect", day, steps = 9000, activeKcal = 600.0, basalKcal = 1600.0)
    private val originalDaily = DailyMetric("my-whoop", day, restingHr = 61, avgHrv = 45.0, recovery = 70.0)
    private val originalWorkout = WorkoutRow("manual-device", start.epochSecond, start.epochSecond + 3600,
        "Cycling", "manual", notes = "Original workout")
    private val originalLab = LabMarkerRow("source-lab", "my-whoop", "custom", "other", day,
        start.epochSecond, value = 1.23456789, unit = "mg/dL", source = "manual", note = "Original reported reading")

    @Before fun open() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(NoopPrefs.NAME, Context.MODE_PRIVATE).edit().clear()
            .putLong("hc.lastImportOkAt", 1234).putInt("hc.lastImportRows", 42)
            .putString("hc.lastImportThroughDay", "2000-01-01").commit()
        db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).allowMainThreadQueries().build()
        repo = WhoopRepository(db)
        db.whoopDao().upsertAppleDaily(listOf(originalAggregate))
        db.whoopDao().upsertDailyMetrics(listOf(originalDaily))
        db.whoopDao().upsertWorkouts(listOf(originalWorkout))
        db.whoopDao().upsertLabMarkers(listOf(originalLab))
    }

    @After fun close() { db.close() }

    private fun steps() = StepsRecord(startTime = start, startZoneOffset = offset,
        endTime = start.plusSeconds(3600), endZoneOffset = offset, count = 1000,
        metadata = Metadata(dataOrigin = DataOrigin("fixture.external.health")))

    private fun exercise(hoursAfterStart: Long = 2) = ExerciseSessionRecord(
        startTime = start.plusSeconds(hoursAfterStart * 3600), startZoneOffset = offset,
        endTime = start.plusSeconds((hoursAfterStart + 1) * 3600), endZoneOffset = offset,
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        metadata = Metadata(dataOrigin = DataOrigin("fixture.external.health")), title = "Provider exercise",
    )

    private fun provider(read: (ReadRecordsRequest<*>) -> ReadRecordsResponse<out Record>): HealthConnectClient {
        val permissions = Proxy.newProxyInstance(PermissionController::class.java.classLoader,
            arrayOf(PermissionController::class.java)) { _, method, _ ->
            when (method.name) {
                "getGrantedPermissions" -> HealthConnectImporter.permissionsFor(setOf(HealthConnectImporter.ImportCategory.ALL))
                else -> error("Unexpected permission operation ${method.name}")
            }
        } as PermissionController
        return Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java)) { _, method, args ->
            when (method.name) {
                "getPermissionController" -> permissions
                "readRecords" -> {
                    val request = args!![0] as ReadRecordsRequest<*>
                    check(request.recordType in HealthConnectImporter.IMPORT_READ_ALLOWLIST)
                    read(request)
                }
                else -> error("Unexpected Health Connect operation ${method.name}")
            }
        } as HealthConnectClient
    }

    private suspend fun import(client: HealthConnectClient, requireComplete: Boolean = false): ImportSummary =
        HealthConnectImporter.importFromClient(context, repo, client,
            categories = setOf(HealthConnectImporter.ImportCategory.ALL), requireCompleteRead = requireComplete)

    private fun assertSuccessStampUnchanged() {
        val prefs = context.getSharedPreferences(NoopPrefs.NAME, Context.MODE_PRIVATE)
        assertEquals(1234L, prefs.getLong("hc.lastImportOkAt", 0))
        assertEquals(42, prefs.getInt("hc.lastImportRows", 0))
        assertEquals("2000-01-01", prefs.getString("hc.lastImportThroughDay", null))
    }

    private suspend fun assertOriginalsUnchanged() {
        assertEquals(originalAggregate, db.whoopDao().appleDaily("health-connect", day, day).single())
        assertEquals(originalDaily, db.whoopDao().days("my-whoop").single())
        assertEquals(originalWorkout, db.whoopDao().workouts("manual-device", 0, Long.MAX_VALUE, 100).single())
        assertEquals(originalLab, db.whoopDao().labMarkerById(originalLab.id))
        assertTrue(db.whoopDao().workouts("health-connect", 0, Long.MAX_VALUE, 100).isEmpty())
        assertNull("No device registration can precede the complete-read boundary", db.whoopDao().device("health-connect"))
    }

    @Test fun scheduledPartialTypeFailureRetriesBeforeReplacingAnySavedDataOrSuccessStamp() = runBlocking {
        val client = provider { request -> when (request.recordType) {
            StepsRecord::class -> ReadRecordsResponse(listOf(steps()), null)
            ExerciseSessionRecord::class -> ReadRecordsResponse(listOf(exercise()), null)
            TotalCaloriesBurnedRecord::class -> throw IOException("Fixture provider read failure")
            else -> ReadRecordsResponse(emptyList<Record>(), null)
        } }
        var summary: ImportSummary? = null
        val execution = HealthConnectAutoImportPolicy.execute(HealthConnectAutoImportPolicy.Access.READY, 0) {
            import(client, requireComplete = true).also { summary = it }
        }

        assertEquals(HealthConnectAutoImportPolicy.Outcome.RETRY, execution.outcome)
        assertFalse(summary!!.successful)
        assertEquals(0, summary!!.totalRows)
        assertTrue(summary!!.message.contains("No existing data was changed"))
        assertOriginalsUnchanged()
        assertSuccessStampUnchanged()
    }

    @Test fun scheduledFailureAfterAFirstPageCannotReplaceACompleteDailyTotalWithAPartialSum() = runBlocking {
        var stepPages = 0
        val client = provider { request ->
            if (request.recordType == StepsRecord::class) {
                stepPages++
                if (request.pageToken == null) ReadRecordsResponse(listOf(steps()), "page-two")
                else throw IOException("Fixture second page failed")
            } else ReadRecordsResponse(emptyList<Record>(), null)
        }

        val result = import(client, requireComplete = true)

        assertEquals(2, stepPages)
        assertFalse(result.successful)
        assertEquals(0, result.totalRows)
        assertOriginalsUnchanged()
        assertSuccessStampUnchanged()
    }

    @Test fun completeEmptyBackgroundReadsRemainSuccessfulAndLeaveOriginalRecordsUntouched() = runBlocking {
        val result = import(provider { ReadRecordsResponse(emptyList<Record>(), null) }, requireComplete = true)

        assertTrue(result.successful)
        assertEquals(0, result.totalRows)
        assertOriginalsUnchanged()
        val prefs = context.getSharedPreferences(NoopPrefs.NAME, Context.MODE_PRIVATE)
        assertTrue(prefs.getLong("hc.lastImportOkAt", 0) > 1234)
        assertEquals(0, prefs.getInt("hc.lastImportRows", -1))
    }

    @Test fun manualPartialImportKeepsItsBestEffortBehaviorWithoutClaimingACompletedSuccessStamp() = runBlocking {
        val client = provider { request -> when (request.recordType) {
            StepsRecord::class -> ReadRecordsResponse(listOf(steps()), null)
            ExerciseSessionRecord::class -> ReadRecordsResponse(listOf(exercise()), null)
            TotalCaloriesBurnedRecord::class -> throw IOException("Fixture provider read failure")
            else -> ReadRecordsResponse(emptyList<Record>(), null)
        } }

        val result = import(client) // Existing manual behavior is the default.

        assertFalse(result.successful)
        assertEquals(1000, db.whoopDao().appleDaily("health-connect", day, day).single().steps)
        assertEquals(1, db.whoopDao().workouts("health-connect", 0, Long.MAX_VALUE, 100).size)
        assertEquals(1, result.counts["workout"])
        assertEquals(originalLab, db.whoopDao().labMarkerById(originalLab.id))
        assertEquals(originalDaily, db.whoopDao().days("my-whoop").single())
        assertSuccessStampUnchanged()
    }

    @Test fun rejectedOverlappingWorkoutsAreExcludedFromThePersistedRowCount() = runBlocking {
        val client = provider { request ->
            if (request.recordType == ExerciseSessionRecord::class) ReadRecordsResponse(listOf(exercise(0)), null)
            else ReadRecordsResponse(emptyList<Record>(), null)
        }

        val result = import(client, requireComplete = true)

        assertTrue(result.successful)
        assertEquals(0, result.totalRows)
        assertNull(result.counts["workout"])
        assertNull(result.firstDay)
        assertNull(result.lastDay)
        assertOriginalsUnchanged()
    }

    @Test fun workoutCountIncludesOnlyTheCandidateActuallyAcceptedByTheProductionOverlapGuard() = runBlocking {
        val client = provider { request ->
            if (request.recordType == ExerciseSessionRecord::class)
                ReadRecordsResponse(listOf(exercise(0), exercise(2)), null)
            else ReadRecordsResponse(emptyList<Record>(), null)
        }

        val result = import(client, requireComplete = true)

        assertTrue(result.successful)
        assertEquals(1, result.counts["workout"])
        val saved = db.whoopDao().workouts("health-connect", 0, Long.MAX_VALUE, 100).single()
        assertEquals(start.epochSecond + 7200, saved.startTs)
        assertEquals(originalWorkout, db.whoopDao().workouts("manual-device", 0, Long.MAX_VALUE, 100).single())
        assertEquals(originalAggregate, db.whoopDao().appleDaily("health-connect", day, day).single())
    }
}

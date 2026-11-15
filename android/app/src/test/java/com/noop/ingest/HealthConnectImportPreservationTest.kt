package com.noop.ingest

import android.app.Application
import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.response.ReadRecordsResponse
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Length
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
import java.time.Instant
import kotlin.reflect.KClass

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
    private val originalAggregate = AppleDaily("health-connect", day, steps = 9000, activeKcal = 600.0,
        basalKcal = 1600.0, vo2max = 44.25, avgHr = 68, maxHr = 158, walkingHr = 92, weightKg = 73.25)
    private val otherDayAggregate = originalAggregate.copy(day = LocalDate.parse(day).minusDays(1).toString(), steps = 7777)
    private val otherSourceAggregate = originalAggregate.copy(deviceId = "apple-health", steps = 5555)
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
        db.whoopDao().upsertAppleDaily(listOf(originalAggregate, otherDayAggregate, otherSourceAggregate))
        db.whoopDao().upsertDailyMetrics(listOf(originalDaily))
        db.whoopDao().upsertWorkouts(listOf(originalWorkout))
        db.whoopDao().upsertLabMarkers(listOf(originalLab))
    }

    @After fun close() { db.close() }

    private fun steps(count: Long = 1000, at: Instant = start) = StepsRecord(startTime = at, startZoneOffset = offset,
        endTime = at.plusSeconds(3600), endZoneOffset = offset, count = count,
        metadata = Metadata(dataOrigin = DataOrigin("fixture.external.health")))

    private fun active(kcal: Double = 250.0, at: Instant = start) = ActiveCaloriesBurnedRecord(
        startTime = at, startZoneOffset = offset, endTime = at.plusSeconds(3600), endZoneOffset = offset,
        energy = Energy.kilocalories(kcal), metadata = Metadata(dataOrigin = DataOrigin("fixture.external.health")))

    private fun total(kcal: Double = 2100.0, at: Instant = start) = TotalCaloriesBurnedRecord(
        startTime = at, startZoneOffset = offset, endTime = at.plusSeconds(3600), endZoneOffset = offset,
        energy = Energy.kilocalories(kcal), metadata = Metadata(dataOrigin = DataOrigin("fixture.external.health")))

    private fun permissions(vararg types: KClass<out Record>): Set<String> =
        types.mapTo(linkedSetOf()) { HealthPermission.getReadPermission(it) }

    private fun exercise(hoursAfterStart: Long = 2) = ExerciseSessionRecord(
        startTime = start.plusSeconds(hoursAfterStart * 3600), startZoneOffset = offset,
        endTime = start.plusSeconds((hoursAfterStart + 1) * 3600), endZoneOffset = offset,
        exerciseType = ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
        metadata = Metadata(dataOrigin = DataOrigin("fixture.external.health")), title = "Provider exercise",
    )

    private fun provider(
        granted: Set<String> = HealthConnectImporter.permissionsFor(setOf(HealthConnectImporter.ImportCategory.ALL)),
        read: (ReadRecordsRequest<*>) -> ReadRecordsResponse<out Record>,
    ): HealthConnectClient {
        val controller = Proxy.newProxyInstance(PermissionController::class.java.classLoader,
            arrayOf(PermissionController::class.java)) { _, method, _ ->
            when (method.name) {
                "getGrantedPermissions" -> granted
                else -> error("Unexpected permission operation ${method.name}")
            }
        } as PermissionController
        return Proxy.newProxyInstance(HealthConnectClient::class.java.classLoader,
            arrayOf(HealthConnectClient::class.java)) { _, method, args ->
            when (method.name) {
                "getPermissionController" -> controller
                "readRecords" -> {
                    val request = args!![0] as ReadRecordsRequest<*>
                    check(request.recordType in HealthConnectImporter.IMPORT_READ_ALLOWLIST)
                    read(request)
                }
                else -> error("Unexpected Health Connect operation ${method.name}")
            }
        } as HealthConnectClient
    }

    private suspend fun import(client: HealthConnectClient, requireComplete: Boolean = false,
        categories: Set<HealthConnectImporter.ImportCategory> = setOf(HealthConnectImporter.ImportCategory.ALL)): ImportSummary =
        HealthConnectImporter.importFromClient(context, repo, client,
            categories = categories, requireCompleteRead = requireComplete)

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
        assertOtherAggregateRowsUnchanged()
    }

    private suspend fun assertOtherAggregateRowsUnchanged() {
        assertEquals(otherDayAggregate, db.whoopDao().appleDaily("health-connect", otherDayAggregate.day, otherDayAggregate.day).single())
        assertEquals(otherSourceAggregate, db.whoopDao().appleDaily("apple-health", day, day).single())
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

    @Test fun manualPartialTypeFailureAlsoAbortsBeforeAnyDataOrSuccessStampWrite() = runBlocking {
        val client = provider { request -> when (request.recordType) {
            StepsRecord::class -> ReadRecordsResponse(listOf(steps()), null)
            ExerciseSessionRecord::class -> ReadRecordsResponse(listOf(exercise()), null)
            TotalCaloriesBurnedRecord::class -> throw IOException("Fixture provider read failure")
            else -> ReadRecordsResponse(emptyList<Record>(), null)
        } }

        val result = import(client)

        assertFalse(result.successful)
        assertEquals(0, result.totalRows)
        assertOriginalsUnchanged()
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

    @Test fun stepsOnlyGrantAppliesTheNewLowerCountWithoutClearingCaloriesOrLegacyFields() = runBlocking {
        val reads = mutableListOf<KClass<out Record>>()
        val result = import(provider(permissions(StepsRecord::class)) { request ->
            reads += request.recordType
            assertEquals(StepsRecord::class, request.recordType)
            ReadRecordsResponse(listOf(steps()), null)
        })

        assertTrue(result.successful)
        assertEquals(listOf(StepsRecord::class), reads)
        assertEquals(originalAggregate.copy(steps = 1000), db.whoopDao().appleDaily("health-connect", day, day).single())
        assertEquals(originalLab, db.whoopDao().labMarkerById(originalLab.id))
        assertEquals(originalDaily, db.whoopDao().days("my-whoop").single())
        assertOtherAggregateRowsUnchanged()
    }

    @Test fun activeOnlyGrantPreservesStepsBasalAndEveryLegacyField() = runBlocking {
        val result = import(provider(permissions(ActiveCaloriesBurnedRecord::class)) { request ->
            assertEquals(ActiveCaloriesBurnedRecord::class, request.recordType)
            ReadRecordsResponse(listOf(active(250.2)), null)
        })

        assertTrue(result.successful)
        assertEquals(originalAggregate.copy(activeKcal = 250.2), db.whoopDao().appleDaily("health-connect", day, day).single())
        assertOtherAggregateRowsUnchanged()
    }

    @Test fun totalOnlyGrantCannotFabricateBasalFromAnUnobservedActiveValue() = runBlocking {
        val result = import(provider(permissions(TotalCaloriesBurnedRecord::class)) { request ->
            assertEquals(TotalCaloriesBurnedRecord::class, request.recordType)
            ReadRecordsResponse(listOf(total(2100.0)), null)
        })

        assertTrue(result.successful)
        assertEquals(originalAggregate, db.whoopDao().appleDaily("health-connect", day, day).single())
        assertOtherAggregateRowsUnchanged()
    }

    @Test fun completedEmptySelectedStepReadClearsOnlyStepsOnAnOtherwiseObservedDay() = runBlocking {
        val result = import(provider(permissions(StepsRecord::class, ActiveCaloriesBurnedRecord::class)) { request ->
            when (request.recordType) {
                StepsRecord::class -> ReadRecordsResponse(emptyList<Record>(), null)
                ActiveCaloriesBurnedRecord::class -> ReadRecordsResponse(listOf(active()), null)
                else -> error("Unselected or ungranted read ${request.recordType}")
            }
        })

        assertTrue(result.successful)
        assertEquals(originalAggregate.copy(steps = null, activeKcal = 250.0),
            db.whoopDao().appleDaily("health-connect", day, day).single())
        assertOtherAggregateRowsUnchanged()
    }

    @Test fun completedEmptyActiveReadClearsActiveButDoesNotInventBasalWithoutTotalAccess() = runBlocking {
        val result = import(provider(permissions(StepsRecord::class, ActiveCaloriesBurnedRecord::class)) { request ->
            when (request.recordType) {
                StepsRecord::class -> ReadRecordsResponse(listOf(steps()), null)
                ActiveCaloriesBurnedRecord::class -> ReadRecordsResponse(emptyList<Record>(), null)
                else -> error("Unselected or ungranted read ${request.recordType}")
            }
        })

        assertTrue(result.successful)
        assertEquals(originalAggregate.copy(steps = 1000, activeKcal = null),
            db.whoopDao().appleDaily("health-connect", day, day).single())
        assertOtherAggregateRowsUnchanged()
    }

    @Test fun observedTotalAndActiveUpdateBasalButAnInvalidDifferenceExplicitlyClearsIt() = runBlocking {
        val granted = permissions(TotalCaloriesBurnedRecord::class, ActiveCaloriesBurnedRecord::class)
        val valid = import(provider(granted) { request -> when (request.recordType) {
            TotalCaloriesBurnedRecord::class -> ReadRecordsResponse(listOf(total(2100.0)), null)
            ActiveCaloriesBurnedRecord::class -> ReadRecordsResponse(listOf(active(300.0)), null)
            else -> error("Unselected or ungranted read ${request.recordType}")
        } })
        assertTrue(valid.successful)
        assertEquals(originalAggregate.copy(activeKcal = 300.0, basalKcal = 1800.0),
            db.whoopDao().appleDaily("health-connect", day, day).single())

        val invalidDifference = import(provider(granted) { request -> when (request.recordType) {
            TotalCaloriesBurnedRecord::class -> ReadRecordsResponse(listOf(total(200.0)), null)
            ActiveCaloriesBurnedRecord::class -> ReadRecordsResponse(listOf(active(300.0)), null)
            else -> error("Unselected or ungranted read ${request.recordType}")
        } })
        assertTrue(invalidDifference.successful)
        assertEquals(originalAggregate.copy(activeKcal = 300.0, basalKcal = null),
            db.whoopDao().appleDaily("health-connect", day, day).single())
        assertOtherAggregateRowsUnchanged()
    }

    @Test fun workoutOnlySelectionCannotClearActivityDespiteOlderActivityGrants() = runBlocking {
        val reads = mutableListOf<KClass<out Record>>()
        val result = import(provider { request ->
            reads += request.recordType
            when (request.recordType) {
                ExerciseSessionRecord::class -> ReadRecordsResponse(listOf(exercise()), null)
                DistanceRecord::class -> ReadRecordsResponse(emptyList<Record>(), null)
                else -> error("Workout Only must not read ${request.recordType}")
            }
        }, categories = setOf(HealthConnectImporter.ImportCategory.ACTIVITY_ONLY))

        assertTrue(result.successful)
        assertEquals(setOf(ExerciseSessionRecord::class, DistanceRecord::class), reads.toSet())
        assertEquals(1, result.counts["workout"])
        assertEquals(originalAggregate, db.whoopDao().appleDaily("health-connect", day, day).single())
        assertOtherAggregateRowsUnchanged()
    }

    @Test fun aNewStepsOnlyDayIsSparseAndCannotCopyUnrelatedLegacyData() = runBlocking {
        val newDay = LocalDate.parse(day).minusDays(3)
        val newStart = newDay.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant()
        val result = import(provider(permissions(StepsRecord::class)) {
            ReadRecordsResponse(listOf(steps(321, newStart)), null)
        })

        assertTrue(result.successful)
        assertEquals(AppleDaily("health-connect", newDay.toString(), steps = 321),
            db.whoopDao().appleDaily("health-connect", newDay.toString(), newDay.toString()).single())
        assertEquals(originalAggregate, db.whoopDao().appleDaily("health-connect", day, day).single())
        assertOtherAggregateRowsUnchanged()
    }

    @Test fun aNewTotalOnlyDayCannotCreateAnEmptyOrFabricatedAggregate() = runBlocking {
        val newDay = LocalDate.parse(day).minusDays(3)
        val newStart = newDay.atTime(10, 0).atZone(ZoneId.systemDefault()).toInstant()
        val result = import(provider(permissions(TotalCaloriesBurnedRecord::class)) {
            ReadRecordsResponse(listOf(total(2100.0, newStart)), null)
        })

        assertTrue(result.successful)
        assertTrue(db.whoopDao().appleDaily("health-connect", newDay.toString(), newDay.toString()).isEmpty())
        assertEquals(0, result.totalRows)
        assertNull(result.counts["appleDaily"])
        assertEquals(originalAggregate, db.whoopDao().appleDaily("health-connect", day, day).single())
        assertOtherAggregateRowsUnchanged()
    }

    @Test fun manualFailedSecondPageCannotSavePartialStepsOrOtherAlreadyReadTypes() = runBlocking {
        var stepPages = 0
        val result = import(provider { request -> when (request.recordType) {
            StepsRecord::class -> {
                stepPages++
                if (request.pageToken == null) ReadRecordsResponse(listOf(steps()), "page-two")
                else throw IOException("Fixture second page failed")
            }
            ActiveCaloriesBurnedRecord::class -> ReadRecordsResponse(listOf(active()), null)
            ExerciseSessionRecord::class -> ReadRecordsResponse(listOf(exercise()), null)
            else -> ReadRecordsResponse(emptyList<Record>(), null)
        } })

        assertEquals(2, stepPages)
        assertFalse(result.successful)
        assertEquals(0, result.totalRows)
        assertOriginalsUnchanged()
        assertSuccessStampUnchanged()
    }

    @Test fun manualFailedWorkoutDistancePageCannotSaveThePartialNewWorkout() = runBlocking {
        var distancePages = 0
        val workoutStart = start.plusSeconds(7200)
        val distance = DistanceRecord(startTime = workoutStart, startZoneOffset = offset,
            endTime = workoutStart.plusSeconds(3600), endZoneOffset = offset, distance = Length.meters(5000.0),
            metadata = Metadata(dataOrigin = DataOrigin("fixture.external.health")))
        val result = import(provider { request -> when (request.recordType) {
            StepsRecord::class -> ReadRecordsResponse(listOf(steps()), null)
            ExerciseSessionRecord::class -> ReadRecordsResponse(listOf(exercise()), null)
            DistanceRecord::class -> {
                distancePages++
                if (request.pageToken == null) ReadRecordsResponse(listOf(distance), "distance-page-two")
                else throw IOException("Fixture distance page failed")
            }
            else -> ReadRecordsResponse(emptyList<Record>(), null)
        } })

        assertEquals(2, distancePages)
        assertFalse(result.successful)
        assertEquals(0, result.totalRows)
        assertOriginalsUnchanged()
        assertSuccessStampUnchanged()
    }
}

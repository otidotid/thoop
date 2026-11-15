package com.noop.data

import android.app.Application
import androidx.room.Room
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Exercises the actual HC-only transaction against Room, including two competing repository callers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class HealthConnectDailyMergeTest {
    private lateinit var db: WhoopDatabase
    private lateinit var repo: WhoopRepository
    private val original = AppleDaily("health-connect", "2026-11-15", steps = 9000, activeKcal = 600.0,
        basalKcal = 1600.0, vo2max = 44.25, avgHr = 68, maxHr = 158, walkingHr = 92, weightKg = 73.25)
    private val otherDay = original.copy(day = "2026-11-14", steps = 7777)
    private val otherSource = original.copy(deviceId = "apple-health", steps = 5555)

    @Before fun open() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = WhoopRepository(db)
        db.whoopDao().upsertAppleDaily(listOf(original, otherDay, otherSource))
    }

    @After fun close() { db.close() }

    private suspend fun saved() = db.whoopDao().appleDaily(original.deviceId, original.day, original.day).single()

    private suspend fun assertUnrelatedRowsUnchanged() {
        assertEquals(otherDay, db.whoopDao().appleDaily(otherDay.deviceId, otherDay.day, otherDay.day).single())
        assertEquals(otherSource, db.whoopDao().appleDaily(otherSource.deviceId, otherSource.day, otherSource.day).single())
    }

    @Test fun anObservedNullClearsThatFieldWhileUnreadAndLegacyFieldsUseTheirCurrentValues() = runBlocking {
        val incoming = AppleDaily(original.deviceId, original.day, steps = null, activeKcal = 1.0,
            basalKcal = 2.0, vo2max = 3.0, avgHr = 4, maxHr = 5, walkingHr = 6, weightKg = 7.0)
        val written = repo.upsertHealthConnectDaily(listOf(incoming), HealthConnectDailyReadMask(steps = true))

        assertEquals(listOf(original.copy(steps = null)), written)
        assertEquals(original.copy(steps = null), saved())
        assertUnrelatedRowsUnchanged()
    }

    @Test fun newRowsContainOnlyObservedSupportedValuesAndEmptyCandidatesAreNotWritten() = runBlocking {
        val emptyDay = "2026-11-12"
        val empty = AppleDaily("health-connect", emptyDay, basalKcal = 2100.0, vo2max = 50.0, weightKg = 75.0)
        assertTrue(repo.upsertHealthConnectDaily(listOf(empty), HealthConnectDailyReadMask()).isEmpty())
        assertTrue(db.whoopDao().appleDaily("health-connect", emptyDay, emptyDay).isEmpty())

        val newDay = "2026-11-13"
        val incoming = original.copy(day = newDay, activeKcal = 222.0)
        val written = repo.upsertHealthConnectDaily(listOf(incoming), HealthConnectDailyReadMask(activeKcal = true))
        val expected = AppleDaily("health-connect", newDay, activeKcal = 222.0)
        assertEquals(listOf(expected), written)
        assertEquals(expected, db.whoopDao().appleDaily("health-connect", newDay, newDay).single())
        assertEquals(original, saved())
        assertUnrelatedRowsUnchanged()
    }

    @Test fun invalidScopeDaysOrBasalDependenciesAreRejectedBeforeAnyBatchWrite() = runBlocking {
        val valid = original.copy(steps = 1000)
        val invalidBatches = listOf(
            listOf(valid, otherSource.copy(steps = 1)),
            listOf(valid, valid.copy(steps = 2)),
            listOf(valid, original.copy(day = "2026-02-30", steps = 3)),
        )
        for (batch in invalidBatches) {
            try {
                repo.upsertHealthConnectDaily(batch, HealthConnectDailyReadMask(steps = true))
                fail("Invalid source/day batch must be rejected")
            } catch (_: IllegalArgumentException) { }
            assertEquals(original, saved())
            assertUnrelatedRowsUnchanged()
        }
        assertThrows(IllegalArgumentException::class.java) { HealthConnectDailyReadMask(basalKcal = true) }
    }

    @Test fun concurrentIndependentMasksKeepBothCompletedUpdatesAndAllUnrelatedValues() = runBlocking {
        val secondCaller = WhoopRepository(db)
        repeat(16) { index ->
            val ready = CompletableDeferred<Unit>()
            val steps = 1000 + index
            val active = 200.0 + index
            val writes = listOf(
                async(Dispatchers.Default) {
                    ready.await()
                    repo.upsertHealthConnectDaily(listOf(AppleDaily(original.deviceId, original.day, steps = steps)),
                        HealthConnectDailyReadMask(steps = true))
                },
                async(Dispatchers.Default) {
                    ready.await()
                    secondCaller.upsertHealthConnectDaily(listOf(AppleDaily(original.deviceId, original.day, activeKcal = active)),
                        HealthConnectDailyReadMask(activeKcal = true))
                },
            )
            ready.complete(Unit)
            writes.awaitAll()
            assertEquals(original.copy(steps = steps, activeKcal = active), saved())
        }
        assertUnrelatedRowsUnchanged()
    }
}

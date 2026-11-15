package com.noop.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class WorkoutReplacementDataSafetyTest {

    private data class Call(val name: String, val args: List<Any?>)

    private fun row(
        deviceId: String,
        sport: String,
        source: String,
        startTs: Long = 1_000L,
    ) = WorkoutRow(
        deviceId = deviceId,
        startTs = startTs,
        endTs = startTs + 900L,
        sport = sport,
        source = source,
    )

    @Test fun detectedReplacementWriteFailureLeavesLegacyRowUntouched() = runBlocking {
        val calls = mutableListOf<Call>()
        var legacyRowPresent = true
        val repo = WhoopRepository(
            dao(calls, onDeleteBySport = { legacyRowPresent = false }, failUpsert = true),
        )
        val legacy = row("whoop-archived-noop", "detected", "whoop-archived-noop")
        val manual = row("whoop-active", "Running", "manual")

        var threw = false
        try {
            repo.saveManualWorkout(manual, replacing = legacy)
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue("the failed replacement write must be reported", threw)
        assertTrue("the legacy row must not be deleted before replacement succeeds", legacyRowPresent)
        assertEquals(listOf("upsertWorkouts"), calls.map { it.name })
    }

    @Test fun detectedReplacementWritesThenMarksAndDeletesTheOriginal() = runBlocking {
        val calls = mutableListOf<Call>()
        val repo = WhoopRepository(dao(calls))
        val legacy = row("whoop-archived-noop", "detected", "whoop-archived-noop")
        val manual = row("whoop-active", "Running", "manual")

        repo.saveManualWorkout(manual, replacing = legacy)

        assertEquals(
            listOf("upsertWorkouts", "insertDismissed", "deleteWorkoutsBySport"),
            calls.map { it.name },
        )
        assertEquals("whoop-archived-noop", calls.last().args[0])
    }

    @Test fun relabelDeletesTheActualArchivedComputedNamespace() = runBlocking {
        val calls = mutableListOf<Call>()
        val legacy = row("whoop-archived-noop", "detected", "whoop-archived-noop")
        // The original is a real overlap; the relabel must exclude only that natural key.
        val repo = WhoopRepository(dao(calls, existingWorkouts = listOf(legacy)))

        repo.relabelDetected(legacy, sport = "Running", strapDeviceId = "whoop-active")

        assertEquals(
            listOf("upsertWorkouts", "insertDismissed", "deleteWorkoutsBySport"),
            calls.map { it.name },
        )
        assertEquals("whoop-archived-noop", calls.last().args[0])
        assertEquals("detected", calls.last().args[1])
        assertEquals(legacy.startTs, calls.last().args[2])
        @Suppress("UNCHECKED_CAST")
        val marker = (calls[1].args[0] as List<DismissedWorkout>).single()
        assertEquals("whoop-archived-noop", marker.deviceId)
        assertEquals(legacy.startTs, marker.startTs)
        assertEquals(legacy.endTs, marker.endTs)
        @Suppress("UNCHECKED_CAST")
        val inserted = (calls.first().args[0] as List<WorkoutRow>).single()
        assertEquals("whoop-active", inserted.deviceId)
        assertEquals("manual", inserted.source)
        assertEquals("Running", inserted.sport)
    }

    @Test fun movedManualEditWritesReplacementBeforeDeletingOriginal() = runBlocking {
        val calls = mutableListOf<Call>()
        val repo = WhoopRepository(dao(calls))
        val original = row("whoop-active", "Running", "manual")
        val moved = original.copy(startTs = original.startTs + 60L, endTs = original.endTs + 60L)

        repo.saveManualWorkout(moved, replacing = original)

        assertEquals(listOf("upsertWorkouts", "deleteWorkoutByKey"), calls.map { it.name })
    }

    @Test fun movedManualWriteFailureLeavesOriginalUntouched() = runBlocking {
        val calls = mutableListOf<Call>()
        var originalPresent = true
        val repo = WhoopRepository(
            dao(calls, onDeleteByKey = { originalPresent = false }, failUpsert = true),
        )
        val original = row("whoop-active", "Running", "manual")
        val moved = original.copy(startTs = original.startTs + 60L, endTs = original.endTs + 60L)

        var threw = false
        try {
            repo.saveManualWorkout(moved, replacing = original)
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue("the failed replacement write must be reported", threw)
        assertTrue("the original manual row must survive a failed replacement write", originalPresent)
        assertEquals(listOf("upsertWorkouts"), calls.map { it.name })
    }

    @Test fun rejectedOverlapLeavesDetectedOriginalAndManualReplacementUnchanged() = runBlocking {
        val calls = mutableListOf<Call>()
        val original = row("whoop-archived-noop", "detected", "whoop-archived-noop")
        val manual = row("whoop-active", "Running", "manual")
        val sleeping = SleepSession("whoop-active", manual.startTs, manual.endTs)
        val repo = WhoopRepository(dao(calls, existingSleeps = listOf(sleeping)))

        repo.saveManualWorkout(manual, replacing = original)

        assertTrue("a rejected candidate must not write, dismiss or delete either row", calls.isEmpty())
    }

    @Test fun relabelRejectsAnotherSourcesWorkoutWithoutDismissingTheOriginal() = runBlocking {
        val calls = mutableListOf<Call>()
        val original = row("whoop-archived-noop", "detected", "whoop-archived-noop")
        val conflict = row("health-connect", "Cycling", "health-connect")
        val repo = WhoopRepository(dao(calls, existingWorkouts = listOf(original, conflict)))

        repo.relabelDetected(original, "Running", "whoop-active")

        assertTrue("cross-source overlap must not write, dismiss or delete either workout", calls.isEmpty())
    }

    @Test fun relabelRejectsAnotherDevicesSleepWithoutDismissingTheOriginal() = runBlocking {
        val calls = mutableListOf<Call>()
        val original = row("whoop-archived-noop", "detected", "whoop-archived-noop")
        val sleep = SleepSession("archived-sleep-source", original.startTs, original.endTs)
        val repo = WhoopRepository(dao(calls, existingWorkouts = listOf(original), existingSleeps = listOf(sleep)))

        repo.relabelDetected(original, "Running", "whoop-active")

        assertTrue("cross-device sleep overlap must preserve the detected original", calls.isEmpty())
    }

    private fun dao(
        calls: MutableList<Call>,
        onDeleteBySport: () -> Unit = {},
        onDeleteByKey: () -> Unit = {},
        failUpsert: Boolean = false,
        existingSleeps: List<SleepSession> = emptyList(),
        existingWorkouts: List<WorkoutRow> = emptyList(),
    ): WhoopDao = Proxy.newProxyInstance(
        WhoopDao::class.java.classLoader,
        arrayOf(WhoopDao::class.java),
    ) { _, method, args ->
        // The production overlap guard now reads the registry and current sessions first.
        // Keep these reads explicit while recording every mutation for the ordering assertions.
        if (method.name == "pairedDevices") return@newProxyInstance emptyList<PairedDeviceRow>()
        if (method.name == "sleepSessions") return@newProxyInstance existingSleeps.filter { it.deviceId == args!![0] }
        if (method.name == "workouts") return@newProxyInstance emptyList<WorkoutRow>()
        if (method.name == "workoutsOverlapping") return@newProxyInstance existingWorkouts.filter {
            it.startTs < args!![1] as Long && it.endTs > args[0] as Long
        }
        if (method.name == "sleepSessionsOverlapping") return@newProxyInstance existingSleeps.filter {
            it.effectiveStartTs < args!![1] as Long && it.endTs > args[0] as Long
        }
        calls += Call(method.name, args?.toList().orEmpty())
        when (method.name) {
            "upsertWorkouts" -> if (failUpsert) throw IllegalStateException("write failed") else Unit
            "insertDismissed" -> Unit
            "deleteWorkoutsBySport" -> {
                onDeleteBySport()
                Unit
            }
            "deleteWorkoutByKey" -> {
                onDeleteByKey()
                Unit
            }
            else -> throw AssertionError("unexpected DAO call ${method.name}")
        }
    } as WhoopDao
}

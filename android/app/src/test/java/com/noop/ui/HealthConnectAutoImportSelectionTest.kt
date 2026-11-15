package com.noop.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HealthConnectAutoImportSelectionTest {
    @Test fun delayedReadyCannotReactivateAnIntervalOrShowReadyAfterOff() = runBlocking {
        val selection = HealthConnectAutoImportSelection()
        val request = selection.select(2)
        val access = CompletableDeferred<String>()
        var hours = 0
        var status = "checking"
        val pending = launch(start = CoroutineStart.UNDISPATCHED) {
            val result = access.await()
            selection.applyIfCurrent(request.generation) { hours = request.hours; status = result }
        }
        val off = selection.select(0)
        selection.applyIfCurrent(off.generation) { hours = 0; status = "disabled" }
        access.complete("ready")
        pending.join()
        assertEquals(0, hours)
        assertEquals("disabled", status)
        assertFalse(selection.beginPermissionRequest(request))
    }

    @Test fun outOfOrderChecksScheduleOnlyTheLatestInterval() = runBlocking {
        val selection = HealthConnectAutoImportSelection()
        val scheduled = mutableListOf<Int>()
        val firstResult = CompletableDeferred<Unit>()
        val first = selection.select(2)
        val older = launch(start = CoroutineStart.UNDISPATCHED) {
            firstResult.await()
            selection.applyIfCurrent(first.generation) { scheduled += first.hours }
        }
        val secondResult = CompletableDeferred<Unit>()
        val second = selection.select(4)
        val newer = launch(start = CoroutineStart.UNDISPATCHED) {
            secondResult.await()
            selection.applyIfCurrent(second.generation) { scheduled += second.hours }
        }
        secondResult.complete(Unit)
        newer.join()
        firstResult.complete(Unit)
        older.join()
        assertEquals(listOf(4), scheduled)
    }

    @Test fun offDuringThePermissionCallbacksFreshGrantCheckStillWins() = runBlocking {
        val selection = HealthConnectAutoImportSelection()
        val request = selection.select(6)
        assertTrue(selection.beginPermissionRequest(request))
        val callbackRequest = selection.finishPermissionRequest()!!
        val freshGrants = CompletableDeferred<Unit>()
        val scheduled = mutableListOf<Int>()
        val callback = launch(start = CoroutineStart.UNDISPATCHED) {
            freshGrants.await()
            selection.applyIfCurrent(callbackRequest.generation) { scheduled += callbackRequest.hours }
        }
        val off = selection.select(0)
        selection.applyIfCurrent(off.generation) { scheduled += off.hours }
        freshGrants.complete(Unit)
        callback.join()
        assertEquals(listOf(0), scheduled)
    }

    @Test fun anOldPermissionResultCannotBeAssignedToANewerSelection() {
        val selection = HealthConnectAutoImportSelection()
        val old = selection.select(2)
        assertTrue(selection.beginPermissionRequest(old))
        val latest = selection.select(12)
        // Keep the launcher's original request until its result arrives; never overwrite it.
        assertFalse(selection.beginPermissionRequest(latest))
        assertNull(selection.finishPermissionRequest())
        assertTrue(selection.beginPermissionRequest(latest))
        assertSame(latest, selection.finishPermissionRequest())
        assertNull(selection.finishPermissionRequest())
    }

    @Test fun changedCategoriesInvalidateActivationAndStatusWithoutStartingAnotherPrompt() {
        val selection = HealthConnectAutoImportSelection()
        val request = selection.select(8)
        val statusRead = selection.snapshot()
        selection.invalidate()
        var writes = 0
        assertFalse(selection.applyIfCurrent(request.generation) { writes++ })
        assertFalse(selection.applyIfCurrent(statusRead) { writes++ })
        assertFalse(selection.beginPermissionRequest(request))
        assertEquals(0, writes)
        assertTrue(selection.applyIfCurrent(selection.snapshot()) { writes++ })
        assertEquals(1, writes)
    }

    @Test fun disposedScreenRejectsItsPendingPermissionAndStatusCallbacks() {
        val selection = HealthConnectAutoImportSelection()
        val request = selection.select(24)
        val statusRead = selection.snapshot()
        assertTrue(selection.beginPermissionRequest(request))
        selection.dispose()
        assertNull(selection.finishPermissionRequest())
        var writes = 0
        assertFalse(selection.applyIfCurrent(request.generation) { writes++ })
        assertFalse(selection.applyIfCurrent(statusRead) { writes++ })
        assertFalse(selection.beginPermissionRequest(request))
        assertEquals(0, writes)
    }
}

package com.noop.ingest

import com.noop.data.ImportSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class HealthConnectAutoImportPolicyTest {
    private val privateDetail = "private health record, account and provider exception details"

    private fun access(enabled: Boolean = true, available: Boolean = true,
        supported: Boolean = true, background: Boolean = true, selectedRead: Boolean = true) =
        HealthConnectAutoImportPolicy.access(enabled, available, supported, background, selectedRead)

    private fun summary(rows: Map<String, Int> = emptyMap(), successful: Boolean = true) =
        ImportSummary(source = "Health Connect", counts = rows, message = privateDetail, successful = successful)

    @Test fun readAndBackgroundPermissionsAreIndependentAndBothAreRequired() {
        assertEquals(HealthConnectAutoImportPolicy.Access.READY, access())
        assertEquals(HealthConnectAutoImportPolicy.Access.READ_PERMISSION_REQUIRED,
            access(background = true, selectedRead = false))
        assertEquals(HealthConnectAutoImportPolicy.Access.BACKGROUND_PERMISSION_REQUIRED,
            access(background = false, selectedRead = true))
        assertEquals(HealthConnectAutoImportPolicy.Access.READ_PERMISSION_REQUIRED,
            access(background = false, selectedRead = false))
        // A stored grant cannot create a feature that this provider version does not support.
        assertEquals(HealthConnectAutoImportPolicy.Access.UNSUPPORTED, access(supported = false))
    }

    @Test fun everyBlockedConfigurationStopsBeforeTheImporterIsInvoked() = runBlocking {
        val blocked = listOf(
            access(enabled = false) to HealthConnectAutoImportPolicy.Access.DISABLED,
            access(available = false) to HealthConnectAutoImportPolicy.Access.UNAVAILABLE,
            access(supported = false) to HealthConnectAutoImportPolicy.Access.UNSUPPORTED,
            access(selectedRead = false) to HealthConnectAutoImportPolicy.Access.READ_PERMISSION_REQUIRED,
            access(background = false) to HealthConnectAutoImportPolicy.Access.BACKGROUND_PERMISSION_REQUIRED,
        )
        var calls = 0
        for ((decision, expected) in blocked) {
            assertEquals(expected, decision)
            val execution = HealthConnectAutoImportPolicy.execute(decision, 0) {
                calls++
                summary(mapOf("workout" to 5))
            }
            assertEquals(HealthConnectAutoImportPolicy.Outcome.BLOCKED, execution.outcome)
            assertTrue(execution.message.isNotBlank())
            assertFalse(execution.message.contains("completed"))
        }
        assertEquals(0, calls)
    }

    @Test fun disabledOrUnavailableProviderCannotBeOverriddenByPreviouslyGrantedAccess() {
        assertEquals(HealthConnectAutoImportPolicy.Access.DISABLED,
            access(enabled = false, available = false, supported = false, background = false, selectedRead = false))
        assertEquals(HealthConnectAutoImportPolicy.Access.UNAVAILABLE,
            access(available = false, supported = false))
        assertEquals(HealthConnectAutoImportPolicy.Access.UNSUPPORTED,
            access(supported = false, background = false, selectedRead = false))
    }

    @Test fun failedAndPartiallyWrittenImportsNeverReportCompletion() = runBlocking {
        val incomplete = listOf(
            ImportSummary.failure("Health Connect", privateDetail),
            summary(mapOf("workout" to 2, "dailyMetric" to 1), successful = false),
        )
        var calls = 0
        for (result in incomplete) {
            val execution = HealthConnectAutoImportPolicy.execute(access(), 0) { calls++; result }
            assertEquals(HealthConnectAutoImportPolicy.Outcome.RETRY, execution.outcome)
            assertFalse(execution.message.contains("import completed"))
            assertFalse(execution.message.contains(privateDetail))
        }
        assertEquals(2, calls)
        assertFalse(incomplete[0].successful)
        assertFalse(incomplete[1].successful)
        assertEquals(3, incomplete[1].totalRows)
    }

    @Test fun anEmptySuccessfulReadCompletesWithoutAnUnnecessaryRetry() = runBlocking {
        var calls = 0
        val execution = HealthConnectAutoImportPolicy.execute(access(), 0) { calls++; summary() }
        assertEquals(1, calls)
        assertEquals(HealthConnectAutoImportPolicy.Outcome.SUCCESS, execution.outcome)
        assertTrue(execution.message.contains("0 rows"))
        assertFalse(execution.message.contains(privateDetail))
    }

    @Test fun successfulRowsUseTheActualImportSummaryCount() = runBlocking {
        val imported = summary(mapOf("workout" to 2, "dailyMetric" to 3))
        var calls = 0
        val execution = HealthConnectAutoImportPolicy.execute(access(), 0) { calls++; imported }
        assertEquals(1, calls)
        assertEquals(HealthConnectAutoImportPolicy.Outcome.SUCCESS, execution.outcome)
        assertTrue(execution.message.contains("5 rows"))
        assertFalse(execution.message.contains(privateDetail))
        assertEquals(mapOf("workout" to 2, "dailyMetric" to 3), imported.counts)
    }

    @Test fun failedSummaryRetriesAreBoundedAtAttemptThreeWithoutHiddenImportRetries() = runBlocking {
        var calls = 0
        for (attempt in 0..4) {
            val execution = HealthConnectAutoImportPolicy.execute(access(), attempt) {
                calls++
                ImportSummary.failure("Health Connect", privateDetail)
            }
            val expected = if (attempt < 3) HealthConnectAutoImportPolicy.Outcome.RETRY
                else HealthConnectAutoImportPolicy.Outcome.FAILED
            assertEquals("attempt $attempt", expected, execution.outcome)
            assertEquals("one importer call per scheduled attempt", attempt + 1, calls)
            assertFalse(execution.message.contains(privateDetail))
            if (attempt >= 3) assertTrue(execution.message.contains("manually"))
        }
    }

    @Test fun thrownProviderErrorsUseTheSameBoundedRetryPolicyAndNeverExposeRawMessages() = runBlocking {
        for (attempt in listOf(0, 3)) {
            val execution = HealthConnectAutoImportPolicy.execute(access(), attempt) {
                throw IllegalStateException(privateDetail)
            }
            assertEquals(if (attempt == 0) HealthConnectAutoImportPolicy.Outcome.RETRY
                else HealthConnectAutoImportPolicy.Outcome.FAILED, execution.outcome)
            assertFalse(execution.message.contains(privateDetail))
            assertFalse(execution.message.contains("IllegalStateException"))
        }
    }

    @Test fun importerCancellationPropagatesInsteadOfBecomingRetryOrFailure() = runBlocking {
        val cancellation = CancellationException("scheduled work was cancelled")
        var calls = 0
        try {
            HealthConnectAutoImportPolicy.execute(access(), 0) { calls++; throw cancellation }
            fail("Cancellation must propagate")
        } catch (caught: CancellationException) {
            assertSame(cancellation, caught)
        }
        assertEquals(1, calls)
    }

    @Test fun cancellationWhileImporterFinishesCannotProduceASuccessStatus() = runBlocking {
        var calls = 0
        var returned: HealthConnectAutoImportPolicy.Execution? = null
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            returned = HealthConnectAutoImportPolicy.execute(access(), 0) {
                calls++
                currentCoroutineContext().cancel()
                summary(mapOf("workout" to 1))
            }
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(1, calls)
        assertNull(returned)
    }
}

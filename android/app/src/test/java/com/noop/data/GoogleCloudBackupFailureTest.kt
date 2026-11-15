package com.noop.data

import android.app.Application
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException

/** Real HTTP classification feeds the production worker handler and real Android preferences. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class GoogleCloudBackupFailureTest {
    private lateinit var prefs: GoogleCloudBackupPreferences
    private val schedule = CloudBackupSchedule(true, CloudBackupCadence.THREE_DAYS, wifiOnly = false, chargingOnly = true)
    private val paused = mutableListOf<CloudBackupSchedule>()

    @Before fun configure() {
        val context: Context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("thoop_google_cloud_backup", Context.MODE_PRIVATE).edit().clear().commit()
        prefs = GoogleCloudBackupPreferences(context)
        prefs.write(schedule)
    }

    private suspend fun httpFailure(status: Int, reason: String? = null): Throwable {
        val body = if (reason == null) "" else """{"error":{"errors":[{"reason":"$reason"}]}}"""
        val client = GoogleDriveBackupClient(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())
        return runCatching { client.list("fixture-token", "fixture-owner") }.exceptionOrNull()
            ?: error("Expected HTTP failure")
    }

    private fun record(failure: Throwable, attempt: Int = 0) =
        recordGoogleCloudBackupFailure(prefs, failure, attempt, paused::add)

    @Test fun rateLimitsUseBackoffEvenAfterSeveralAttemptsWithoutChangingTheSchedule() = runBlocking {
        for (reason in listOf("rateLimitExceeded", "userRateLimitExceeded")) {
            for (attempt in listOf(0, 3, 12)) {
                assertEquals(GoogleCloudBackupFailureAction.RETRY, record(httpFailure(403, reason), attempt))
                assertEquals(schedule, prefs.read())
                assertTrue(paused.isEmpty())
                assertTrue(prefs.status().orEmpty().contains("retry with backoff"))
                assertFalse(prefs.status().orEmpty().contains("Reauthentication"))
            }
        }
    }

    @Test fun temporaryServerAndBusyResponsesKeepTheExistingEnabledSchedule() = runBlocking {
        for (status in listOf(408, 429, 500, 503)) {
            assertEquals(GoogleCloudBackupFailureAction.RETRY, record(httpFailure(status), 8))
            assertEquals(schedule, prefs.read())
            assertTrue(paused.isEmpty())
        }
    }

    @Test fun actualAuthenticationRejectionsPauseAndKeepEveryOtherScheduleChoice() = runBlocking {
        for ((status, reason) in listOf(401 to null, 403 to "insufficientPermissions")) {
            prefs.write(schedule)
            paused.clear()
            assertEquals(GoogleCloudBackupFailureAction.FAILURE, record(httpFailure(status, reason)))
            assertEquals(schedule.copy(enabled = false), prefs.read())
            assertEquals(listOf(schedule.copy(enabled = false)), paused)
            assertTrue(prefs.status().orEmpty().contains("Reauthentication required"))
        }
    }

    @Test fun quotaFailureRequestsRecoveryAndKeepsAuthorizationAndAutomaticBackupEnabled() = runBlocking {
        assertEquals(GoogleCloudBackupFailureAction.FAILURE, record(httpFailure(403, "storageQuotaExceeded")))
        assertEquals(schedule, prefs.read())
        assertTrue(paused.isEmpty())
        assertTrue(prefs.status().orEmpty().contains("Free space"))
        assertTrue(prefs.status().orEmpty().contains("remains enabled"))
        assertFalse(prefs.status().orEmpty().contains("Reauthentication"))
    }

    @Test fun quotaFailureDoesNotClaimAnAlreadyDisabledScheduleIsEnabled() = runBlocking {
        prefs.write(schedule.copy(enabled = false))
        assertEquals(GoogleCloudBackupFailureAction.FAILURE, record(httpFailure(403, "dailyLimitExceeded")))
        assertEquals(schedule.copy(enabled = false), prefs.read())
        assertTrue(paused.isEmpty())
        assertTrue(prefs.status().orEmpty().contains("Automatic backup is off"))
    }

    @Test fun fileAccessErrorsDoNotRevokeTheGrantOrSilentlyCancelScheduledBackup() = runBlocking {
        assertEquals(GoogleCloudBackupFailureAction.FAILURE, record(httpFailure(403, "insufficientFilePermissions")))
        assertEquals(schedule, prefs.read())
        assertTrue(paused.isEmpty())
        assertTrue(prefs.status().orEmpty().contains("permissions"))
    }

    @Test fun ordinaryIoRetryExhaustionIsReportedWithoutChangingTheSchedule() {
        assertEquals(GoogleCloudBackupFailureAction.RETRY, record(IOException("Fixture connection failure"), 0))
        assertEquals(GoogleCloudBackupFailureAction.FAILURE, record(IOException("Fixture connection failure"), 3))
        assertEquals(schedule, prefs.read())
        assertTrue(paused.isEmpty())
        assertFalse(prefs.status().orEmpty().contains("will retry"))
    }

    @Test fun cancellationCannotBeReportedAsAnAuthenticationOrTemporaryFailure() {
        prefs.writeStatus("Previous successful backup")
        val cancellation = CancellationException("Fixture worker cancellation")
        val thrown = runCatching { record(cancellation) }.exceptionOrNull()
        assertSame(cancellation, thrown)
        assertEquals(schedule, prefs.read())
        assertTrue(paused.isEmpty())
        assertEquals("Previous successful backup", prefs.status())
    }
}

package com.noop.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.Scope
import com.noop.ui.GoogleAccountStore
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

internal enum class CloudBackupCadence(val days: Long, val label: String) {
    DAILY(1, "Daily"),
    THREE_DAYS(3, "Every 3 days"),
    WEEKLY(7, "Weekly"),
}

internal data class CloudBackupSchedule(
    val enabled: Boolean = false,
    val cadence: CloudBackupCadence = CloudBackupCadence.WEEKLY,
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = true,
)

internal class GoogleCloudBackupPreferences(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun read(): CloudBackupSchedule = CloudBackupSchedule(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        cadence = runCatching {
            CloudBackupCadence.valueOf(prefs.getString(KEY_CADENCE, null).orEmpty())
        }.getOrDefault(CloudBackupCadence.WEEKLY),
        wifiOnly = prefs.getBoolean(KEY_WIFI, true),
        chargingOnly = prefs.getBoolean(KEY_CHARGING, true),
    )

    fun write(value: CloudBackupSchedule) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, value.enabled)
            .putString(KEY_CADENCE, value.cadence.name)
            .putBoolean(KEY_WIFI, value.wifiOnly)
            .putBoolean(KEY_CHARGING, value.chargingOnly)
            .apply()
    }

    fun writeStatus(value: String) = prefs.edit().putString(KEY_STATUS, value).apply()
    fun status(): String? = prefs.getString(KEY_STATUS, null)

    private companion object {
        const val PREFS = "thoop_google_cloud_backup"
        const val KEY_ENABLED = "enabled"
        const val KEY_CADENCE = "cadence"
        const val KEY_WIFI = "wifi_only"
        const val KEY_CHARGING = "charging_only"
        const val KEY_STATUS = "status"
    }
}

internal object GoogleCloudBackupScheduler {
    private const val UNIQUE_PERIODIC = "thoop-google-cloud-backup"
    private const val UNIQUE_NOW = "thoop-google-cloud-backup-now"

    fun apply(context: Context, schedule: CloudBackupSchedule) {
        val workManager = WorkManager.getInstance(context)
        if (!schedule.enabled) {
            workManager.cancelUniqueWork(UNIQUE_PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (schedule.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .setRequiresCharging(schedule.chargingOnly)
            .setRequiresStorageNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<GoogleCloudBackupWorker>(
            schedule.cadence.days, TimeUnit.DAYS,
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            UNIQUE_PERIODIC,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<GoogleCloudBackupWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            UNIQUE_NOW,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

internal class GoogleCloudBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val prefs = GoogleCloudBackupPreferences(applicationContext)
        val account = GoogleAccountStore(applicationContext).read()
            ?: return Result.failure().also { prefs.writeStatus("Sign in to Google before automatic backup.") }
        val authorization = silentAuthorization(applicationContext)
            ?: return Result.failure().also {
                prefs.writeStatus("Open THOOP and re-authorize Google Drive for automatic backup.")
            }
        val token = authorization.accessToken?.takeIf(String::isNotBlank)
            ?: return Result.failure().also {
                prefs.writeStatus("Google Drive authorization requires THOOP to be opened.")
            }
        return runCatching {
            val uploaded = GoogleDriveBackupCoordinator(applicationContext)
                .backUpNow(token, account.accountId)
            prefs.writeStatus("Automatic backup uploaded: ${uploaded.name}")
            Result.success()
        }.getOrElse {
            prefs.writeStatus("Automatic backup will retry after a temporary failure.")
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private suspend fun silentAuthorization(context: Context): AuthorizationResult? =
        suspendCancellableCoroutine { continuation ->
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
                .build()
            Identity.getAuthorizationClient(context).authorize(request)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(
                        result.takeIf { it.pendingIntent == null },
                    )
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }

    private companion object {
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}

package com.noop.ingest

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.NoopApplication
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

object HealthConnectAutoImport {
    private const val PREFS = "health_connect_auto_import"
    private const val KEY_HOURS = "interval_hours"
    private const val WORK = "health-connect-auto-import"
    private const val KEY_STATUS = "last_status"
    val allowedHours = listOf(0, 2, 4, 6, 8, 12, 24)

    internal fun preferences(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun status(context: Context): String = preferences(context).getString(KEY_STATUS, "").orEmpty()
    internal fun writeStatus(context: Context, message: String) {
        preferences(context).edit().putString(KEY_STATUS, message).apply()
    }

    @OptIn(androidx.health.connect.client.feature.ExperimentalFeatureAvailabilityApi::class)
    internal suspend fun access(context: Context, enabling: Boolean = intervalHours(context) > 0): HealthConnectAutoImportPolicy.Access {
        if (!enabling) return HealthConnectAutoImportPolicy.Access.DISABLED
        if (HealthConnectImporter.sdkStatus(context) != HealthConnectClient.SDK_AVAILABLE)
            return HealthConnectAutoImportPolicy.Access.UNAVAILABLE
        return try {
            val client = HealthConnectImporter.client(context)
            val supported = client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
            val granted = client.permissionController.getGrantedPermissions()
            HealthConnectAutoImportPolicy.access(true, true, supported,
                HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted,
                granted.any { it in HealthConnectImporter.permissionsFor(HealthConnectImporter.selectedCategories(context)) })
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { HealthConnectAutoImportPolicy.Access.UNAVAILABLE }
    }

    fun intervalHours(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_HOURS, 0)
            .takeIf { it in allowedHours } ?: 0

    fun setIntervalHours(context: Context, hours: Int) {
        require(hours in allowedHours)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_HOURS, hours).apply()
        writeStatus(context, if (hours == 0) HealthConnectAutoImportPolicy.message(HealthConnectAutoImportPolicy.Access.DISABLED)
            else "Automatic import scheduled. Background access is checked before every run.")
        reschedule(context)
    }

    fun reschedule(context: Context) {
        val app = context.applicationContext
        val workManager = WorkManager.getInstance(app)
        val hours = intervalHours(app)
        if (hours == 0) {
            workManager.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<HealthConnectAutoImportWorker>(
            hours.toLong(), TimeUnit.HOURS,
        ).setConstraints(
            Constraints.Builder().setRequiredNetworkType(NetworkType.NOT_REQUIRED).build(),
        ).build()
        workManager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

class HealthConnectAutoImportWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? NoopApplication ?: return Result.failure()
        val execution = HealthConnectAutoImportPolicy.execute(
            HealthConnectAutoImport.access(applicationContext), runAttemptCount,
        ) { HealthConnectImporter.import(applicationContext, app.repository, requireCompleteRead = true) }
        HealthConnectAutoImport.writeStatus(applicationContext, execution.message)
        return when (execution.outcome) {
            HealthConnectAutoImportPolicy.Outcome.SUCCESS -> Result.success()
            HealthConnectAutoImportPolicy.Outcome.RETRY -> Result.retry()
            HealthConnectAutoImportPolicy.Outcome.BLOCKED, HealthConnectAutoImportPolicy.Outcome.FAILED -> Result.failure()
        }
    }
}

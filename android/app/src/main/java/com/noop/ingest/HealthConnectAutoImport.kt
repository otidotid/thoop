package com.noop.ingest

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.NoopApplication
import java.util.concurrent.TimeUnit

object HealthConnectAutoImport {
    private const val PREFS = "health_connect_auto_import"
    private const val KEY_HOURS = "interval_hours"
    private const val WORK = "health-connect-auto-import"
    val allowedHours = listOf(0, 2, 4, 6, 8, 12, 24)

    fun intervalHours(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_HOURS, 0)
            .takeIf { it in allowedHours } ?: 0

    fun setIntervalHours(context: Context, hours: Int) {
        require(hours in allowedHours)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_HOURS, hours).apply()
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
        return runCatching {
            HealthConnectImporter.import(applicationContext, app.repository)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

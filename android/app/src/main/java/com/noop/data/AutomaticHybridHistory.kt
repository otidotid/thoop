package com.noop.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.noop.ui.GoogleAccountStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.security.KeyStore
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * Opt-in, device-bound storage ONLY for unattended history archiving.
 * The recovery passphrase remains user-held for disaster recovery; the Android Keystore
 * protects the local working copy. No secret is uploaded, logged, or placed in backup files.
 * This store resides in noBackupFilesDir because Keystore keys are not portable.
 */
internal class AutomaticHistorySecretStore(private val context: Context) {
    private val file = File(context.noBackupFilesDir, "thoop-automatic-history-secret.enc")

    fun isConfiguredFor(ownerId: String): Boolean {
        if (!file.isFile || ownerId.isBlank()) return false
        return runCatching { load(ownerId)?.also { it.fill('\u0000') } != null }.getOrDefault(false)
    }

    fun save(ownerId: String, passphrase: CharArray) {
        require(ownerId.isNotBlank()) { "Google account is required for automatic history." }
        require(passphrase.size >= 12) { "Recovery passphrase must have at least 12 characters." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        cipher.updateAAD(ownerId.toByteArray(Charsets.UTF_8))
        val buffer = Charsets.UTF_8.encode(CharBuffer.wrap(passphrase))
        val plain = ByteArray(buffer.remaining()).also { buffer.get(it) }
        try {
            val encrypted = cipher.doFinal(plain)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                tmp.outputStream().use { out ->
                    out.write(byteArrayOf(1))
                    out.write(cipher.iv)
                    out.write(encrypted)
                    out.flush()
                }
                check(tmp.renameTo(file)) { "Could not securely save automatic history setup." }
            } finally { tmp.delete() }
        } finally { plain.fill(0) }
    }

    fun load(ownerId: String): CharArray? {
        if (!file.isFile) return null
        require(ownerId.isNotBlank()) { "Google account is required." }
        val payload = file.readBytes()
        require(payload.size > 29 && payload[0] == 1.toByte()) { "Invalid automatic history secret." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, payload.copyOfRange(1, 13)))
        cipher.updateAAD(ownerId.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(payload, 13, payload.size - 13)
        return try {
            val decoded = Charsets.UTF_8.decode(ByteBuffer.wrap(plain))
            CharArray(decoded.remaining()).also { decoded.get(it) }
        } finally { plain.fill(0) }
    }

    fun clear() {
        file.delete()
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private companion object { const val KEY_ALIAS = "thoop-automatic-history-v1" }
}

/** Status contains no health measurements, passphrase, access token or personally identifying data. */
internal class AutomaticHistoryStatus(context: Context) {
    private val prefs = context.getSharedPreferences("thoop_auto_history_status", Context.MODE_PRIVATE)
    fun message(): String = prefs.getString("message", "Automatic history setup required.").orEmpty()
    fun lastSuccess(): String? = prefs.getString("last_success_cutoff", null)
    fun update(message: String) { prefs.edit().putString("message", message).apply() }
    fun completed(cutoff: LocalDate) {
        prefs.edit().putString("message", "Verified online through $cutoff; local data preserved.")
            .putString("last_success_cutoff", cutoff.toString()).apply()
    }
    fun snapshotDay(): String? = prefs.getString("snapshot_day", null)
    fun snapshotCompleted(day: LocalDate) { prefs.edit().putString("snapshot_day", day.toString()).apply() }
}

internal object AutomaticHybridHistoryScheduler {
    private const val SNAPSHOT_PERIODIC = "thoop-rolling-seven-day-snapshots"
    private const val SNAPSHOT_CATCHUP = "thoop-rolling-snapshots-catchup"
    private const val ARCHIVE_PERIODIC = "thoop-automatic-rolling-archive"
    private const val ARCHIVE_CATCHUP = "thoop-automatic-rolling-archive-catchup"

    /** Called at application startup. WorkManager schedules are approximate, never midnight-exact. */
    fun ensureScheduled(context: Context) {
        val app = context.applicationContext
        val manager = WorkManager.getInstance(app)
        manager.enqueueUniquePeriodicWork(SNAPSHOT_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RollingSnapshotWorker>(1, TimeUnit.DAYS).build())
        if (AutomaticHistoryStatus(app).snapshotDay() != LocalDate.now().toString()) {
            manager.enqueueUniqueWork(SNAPSHOT_CATCHUP, ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<RollingSnapshotWorker>().build())
        }
        val account = GoogleAccountStore(app).read()
        if (account != null && AutomaticHistorySecretStore(app).isConfiguredFor(account.accountId)) {
            scheduleCloud(app)
            val cutoff = HybridHistoryPolicy.archiveCutoff(LocalDate.now()).toString()
            if (AutomaticHistoryStatus(app).lastSuccess() != cutoff) runCloudNow(app)
        } else {
            manager.cancelUniqueWork(ARCHIVE_PERIODIC)
        }
    }

    fun scheduleCloud(context: Context) {
        val manager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresStorageNotLow(true)
            .build()
        manager.enqueueUniquePeriodicWork(ARCHIVE_PERIODIC, ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<RollingArchiveWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build())
    }

    fun runCloudNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(ARCHIVE_CATCHUP, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<RollingArchiveWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build())
    }

    fun stopCloud(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(ARCHIVE_PERIODIC)
            cancelUniqueWork(ARCHIVE_CATCHUP)
        }
    }
}

internal class RollingSnapshotWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        CompactDailySnapshots(applicationContext).refresh()
        AutomaticHistoryStatus(applicationContext).snapshotCompleted(LocalDate.now())
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

internal class RollingArchiveWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val status = AutomaticHistoryStatus(applicationContext)
        val account = GoogleAccountStore(applicationContext).read()
        if (account == null) {
            status.update("Sign in to Google to resume automatic encrypted history.")
            return Result.success()
        }
        val secret = try { AutomaticHistorySecretStore(applicationContext).load(account.accountId) }
        catch (_: Exception) {
            status.update("Automatic history setup needs renewal. Open Backup & History.")
            return Result.failure()
        }
        if (secret == null) {
            status.update("Finish one-time encrypted history setup in Backup & History.")
            return Result.success()
        }
        try {
            val authorization = silentAuthorization() ?: run {
                status.update("Google Drive needs authorization. Open THOOP to reconnect.")
                return Result.failure()
            }
            val token = authorization.accessToken?.takeIf(String::isNotBlank) ?: run {
                status.update("Google Drive access expired. Open THOOP to reconnect.")
                return Result.failure()
            }
            // All original source rows stay local; archiving only writes encrypted copies to Drive.
            val manager = GoogleDriveArchiveManager(applicationContext)
            manager.createAndVerifyArchives(token, account.accountId, secret)
            if (manager.archives().isEmpty()) {
                status.update("No eligible historical data to archive yet. Local data is safe.")
            } else {
                status.completed(HybridHistoryPolicy.archiveCutoff(LocalDate.now()))
            }
            return Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Never log exceptions: they may contain content from upstream services.
            status.update(if (runAttemptCount >= 4) {
                "Automatic archive failed; open Backup & History to check Drive and encryption setup."
            } else "Automatic archive pending; THOOP will retry when available.")
            return if (runAttemptCount < 4) Result.retry() else Result.failure()
        } finally { secret.fill('\u0000') }
    }

    private suspend fun silentAuthorization(): AuthorizationResult? =
        suspendCancellableCoroutine { continuation ->
            val request = AuthorizationRequest.builder().setRequestedScopes(
                listOf(Scope("https://www.googleapis.com/auth/drive.appdata")),
            ).build()
            Identity.getAuthorizationClient(applicationContext).authorize(request)
                .addOnSuccessListener { result ->
                    if (continuation.isActive) continuation.resume(result.takeIf { it.pendingIntent == null })
                }
                .addOnFailureListener {
                    if (continuation.isActive) continuation.resume(null)
                }
        }
}

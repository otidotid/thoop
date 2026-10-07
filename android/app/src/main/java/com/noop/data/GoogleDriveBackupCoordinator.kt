package com.noop.data

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

/** Creates a verified .noopbak snapshot, uploads it, and removes the temporary file. */
internal class GoogleDriveBackupCoordinator(
    context: Context,
    private val driveClient: GoogleDriveBackupClient = GoogleDriveBackupClient(),
) {
    private val appContext = context.applicationContext

    suspend fun backUpNow(
        accessToken: String,
        ownerId: String,
    ): GoogleDriveBackupClient.RemoteBackup = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, CLOUD_BACKUP_DIRECTORY).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("THOOP could not create its temporary backup directory.")
            }
        }
        val backupFile = File(directory, "thoop-upload-${System.currentTimeMillis()}.noopbak")
        try {
            val uri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                backupFile,
            )
            DataBackup.exportTo(appContext, uri)
            check(backupFile.isFile && backupFile.length() > 0L) {
                "THOOP created an empty local backup."
            }
            driveClient.upload(accessToken, backupFile, ownerId)
        } finally {
            backupFile.delete()
        }
    }

    suspend fun availableBackups(
        accessToken: String,
        ownerId: String,
    ): List<GoogleDriveBackupClient.RemoteBackup> = driveClient.list(accessToken, ownerId)

    data class BackupPreview(val backup: GoogleDriveBackupClient.RemoteBackup)

    suspend fun previewBackup(
        accessToken: String,
        backup: GoogleDriveBackupClient.RemoteBackup,
    ): BackupPreview = withContext(Dispatchers.IO) {
        val downloaded = downloadForPreview(accessToken, backup.id)
        try {
            check(FileInputStream(downloaded).use(DataBackup::backupStreamIsIntact)) {
                "The selected Google Drive backup is not a valid THOOP backup."
            }
            BackupPreview(backup)
        } finally {
            downloaded.delete()
        }
    }

    suspend fun downloadForPreview(
        accessToken: String,
        backupId: String,
    ): File = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, CLOUD_BACKUP_DIRECTORY).apply { mkdirs() }
        val destination = File(directory, "thoop-restore-${System.currentTimeMillis()}.noopbak")
        try {
            driveClient.download(accessToken, backupId, destination)
            check(destination.isFile && destination.length() > 0L) {
                "Google Drive returned an empty backup."
            }
            destination
        } catch (failure: Throwable) {
            destination.delete()
            throw failure
        }
    }

    companion object {
        private const val CLOUD_BACKUP_DIRECTORY = "cloud-backups"
    }
}

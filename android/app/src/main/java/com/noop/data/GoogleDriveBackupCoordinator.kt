package com.noop.data

import android.content.Context
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import android.os.StatFs

/** Creates a verified .noopbak snapshot, uploads it, and removes the temporary file. */
internal class GoogleDriveBackupCoordinator(
    context: Context,
    private val driveClient: GoogleDriveBackupClient = GoogleDriveBackupClient(),
) {
    private val appContext = context.applicationContext

    suspend fun backUpNow(
        accessToken: String,
        ownerId: String,
        passphrase: CharArray? = null,
    ): GoogleDriveBackupClient.RemoteBackup = withContext(Dispatchers.IO) {
        val directory = File(appContext.cacheDir, CLOUD_BACKUP_DIRECTORY).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("THOOP could not create its temporary backup directory.")
            }
        }
        val backupFile = File(directory, "thoop-upload-${System.currentTimeMillis()}.noopbak")
        val encryptedFile = File(directory, "thoop-encrypted-${System.currentTimeMillis()}.noopenc")
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
            check(DataBackup.containsFoodHistory(backupFile)) {
                "Complete backup validation failed: food history is missing."
            }
            // A manually supplied passphrase enables portable encryption. Older scheduled
            // backups (without a passphrase) remain compatible until the user upgrades them.
            val uploadFile = if (passphrase != null) {
                ArchiveEncryption.encrypt(backupFile, encryptedFile, passphrase)
                encryptedFile
            } else backupFile
            val uploaded = driveClient.upload(accessToken, uploadFile, ownerId, encrypted = passphrase != null)
            val expectedSha256 = driveClient.sha256(uploadFile)
            check(uploaded.sha256 == expectedSha256) {
                "Google Drive did not preserve the THOOP backup checksum metadata."
            }
            val verificationFile = File(directory, "thoop-verify-${uploaded.id}.noopbak")
            try {
                driveClient.download(accessToken, uploaded.id, verificationFile)
                check(verificationFile.length() == uploadFile.length()) {
                    "Google Drive backup verification failed: size mismatch."
                }
                check(driveClient.sha256(verificationFile) == expectedSha256) {
                    "Google Drive backup verification failed: SHA-256 mismatch."
                }
                val verifyCleartext = File(directory, "thoop-verify-clear-${uploaded.id}.noopbak")
                try {
                    val validated = if (passphrase != null) {
                        ArchiveEncryption.decrypt(verificationFile, verifyCleartext, passphrase)
                        verifyCleartext
                    } else verificationFile
                    check(FileInputStream(validated).use(DataBackup::backupStreamIsIntact) &&
                        DataBackup.containsFoodHistory(validated)) {
                        "Google Drive backup verification failed: required databases are missing or invalid."
                    }
                } finally {
                    verifyCleartext.delete()
                }
            } finally {
                verificationFile.delete()
            }
            uploaded
        } finally {
            backupFile.delete()
            encryptedFile.delete()
        }
    }

    suspend fun availableBackups(
        accessToken: String,
        ownerId: String,
    ): List<GoogleDriveBackupClient.RemoteBackup> = driveClient.list(accessToken, ownerId)

    data class BackupPreview(
        val backup: GoogleDriveBackupClient.RemoteBackup,
        val includesFoodHistory: Boolean,
    )

    data class RestoreOutcome(
        val result: DataBackup.ImportResult,
        val safetySnapshot: File,
    )

    suspend fun previewBackup(
        accessToken: String,
        backup: GoogleDriveBackupClient.RemoteBackup,
        passphrase: CharArray? = null,
    ): BackupPreview = withContext(Dispatchers.IO) {
        val downloaded = downloadBackup(accessToken, backup, passphrase)
        try {
            check(FileInputStream(downloaded).use(DataBackup::backupStreamIsIntact)) {
                "The selected Google Drive backup is not a valid THOOP backup."
            }
            BackupPreview(backup, DataBackup.containsFoodHistory(downloaded))
        } finally {
            downloaded.delete()
        }
    }

    suspend fun restoreBackup(
        accessToken: String,
        backup: GoogleDriveBackupClient.RemoteBackup,
        passphrase: CharArray? = null,
    ): RestoreOutcome = withContext(Dispatchers.IO) {
        val requiredBytes = minimumFreeBytes(backup.sizeBytes)
        ensureFreeSpace(requiredBytes)

        val safetyDirectory = File(appContext.filesDir, SAFETY_DIRECTORY).apply {
            if (!exists() && !mkdirs()) {
                throw IllegalStateException("THOOP could not create its safety snapshot directory.")
            }
        }
        val safetySnapshot = File(
            safetyDirectory,
            "before-cloud-restore-${System.currentTimeMillis()}.noopbak",
        )
        val restoreFile = downloadBackup(accessToken, backup, passphrase)
        try {
            check(FileInputStream(restoreFile).use(DataBackup::backupStreamIsIntact)) {
                "The selected Google Drive backup failed integrity validation."
            }
            // ZIP compression ratios vary wildly; do not estimate required disk from the
            // compressed/encrypted Drive object alone. We stage both databases, then keep
            // a rollback copy of the currently installed stores during the import.
            DataBackup.restoreDatabaseBytes(restoreFile)?.let { expandedBytes ->
                check(expandedBytes <= (Long.MAX_VALUE - MIN_FREE_MARGIN_BYTES) / RESTORE_WORKING_MULTIPLIER) {
                    "Backup is too large to restore safely."
                }
                ensureFreeSpace(expandedBytes * RESTORE_WORKING_MULTIPLIER + MIN_FREE_MARGIN_BYTES)
            }

            val safetyUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                safetySnapshot,
            )
            DataBackup.exportTo(appContext, safetyUri)
            check(safetySnapshot.isFile && safetySnapshot.length() > 0L) {
                "THOOP could not create a local safety snapshot. Restore was cancelled."
            }
            check(FileInputStream(safetySnapshot).use(DataBackup::backupStreamIsIntact)) {
                "The local safety snapshot failed validation. Restore was cancelled."
            }

            ensureFreeSpace(restoreFile.length() * RESTORE_WORKING_MULTIPLIER + MIN_FREE_MARGIN_BYTES)
            val restoreUri = FileProvider.getUriForFile(
                appContext,
                "${appContext.packageName}.fileprovider",
                restoreFile,
            )
            RestoreOutcome(
                result = DataBackup.importFrom(appContext, restoreUri),
                safetySnapshot = safetySnapshot,
            )
        } catch (failure: Throwable) {
            if (!safetySnapshot.isFile || safetySnapshot.length() == 0L) safetySnapshot.delete()
            throw failure
        } finally {
            restoreFile.delete()
        }
    }

    private fun ensureFreeSpace(requiredBytes: Long) {
        val availableBytes = StatFs(appContext.filesDir.absolutePath).availableBytes
        check(availableBytes >= requiredBytes) {
            "Not enough free space for a safe restore. " +
                "Free ${formatBytes(requiredBytes - availableBytes)} more and try again."
        }
    }

    private fun minimumFreeBytes(backupBytes: Long): Long =
        backupBytes.coerceAtLeast(1L) * RESTORE_WORKING_MULTIPLIER + MIN_FREE_MARGIN_BYTES

    private fun formatBytes(bytes: Long): String =
        String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)

    private suspend fun downloadBackup(
        accessToken: String,
        backup: GoogleDriveBackupClient.RemoteBackup,
        passphrase: CharArray?,
    ): File = withContext(Dispatchers.IO) {
        val raw = downloadForPreview(accessToken, backup.id)
        try {
            backup.sha256?.let { checksum ->
                check(driveClient.sha256(raw).equals(checksum, ignoreCase = true)) {
                    "Google Drive backup checksum does not match its uploaded metadata."
                }
            }
            if (!backup.encrypted) return@withContext raw
            require(passphrase != null && passphrase.isNotEmpty()) {
                "Enter the recovery passphrase for this encrypted backup."
            }
            val clear = File(raw.parentFile, "decrypted-${backup.id}.noopbak")
            try {
                ArchiveEncryption.decrypt(raw, clear, passphrase)
                clear
            } catch (failure: Throwable) {
                clear.delete()
                throw failure
            }
        } finally {
            if (backup.encrypted) raw.delete()
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
        private const val SAFETY_DIRECTORY = "cloud-safety"
        private const val RESTORE_WORKING_MULTIPLIER = 4L
        private const val MIN_FREE_MARGIN_BYTES = 256L * 1024L * 1024L
    }
}

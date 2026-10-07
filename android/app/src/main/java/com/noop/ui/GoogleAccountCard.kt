package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.noop.data.GoogleDriveBackupClient
import com.noop.data.GoogleDriveBackupCoordinator
import com.noop.data.DataBackup
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

@Composable
internal fun GoogleAccountCard() {
    val context = LocalContext.current
    val controller = remember(context) {
        GoogleSignInController(context.applicationContext)
    }

    val driveController = remember(context) {
        GoogleDriveAuthorizationController(context.applicationContext)
    }

    val backupCoordinator = remember(context) {
        GoogleDriveBackupCoordinator(context.applicationContext)
    }

    val scope = rememberCoroutineScope()

    var account by remember {
        mutableStateOf(controller.storedAccount())
    }

    var busy by remember {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    var driveAuthorized by remember {
        mutableStateOf(false)
    }

    var driveBusy by remember {
        mutableStateOf(false)
    }

    var driveAccessToken by remember {
        mutableStateOf<String?>(null)
    }

    var backupStatus by remember {
        mutableStateOf<String?>(null)
    }
    var availableBackups by remember {
        mutableStateOf<List<GoogleDriveBackupClient.RemoteBackup>>(emptyList())
    }
    var selectedPreview by remember {
        mutableStateOf<GoogleDriveBackupCoordinator.BackupPreview?>(null)
    }
    var pendingRestore by remember {
        mutableStateOf<GoogleDriveBackupClient.RemoteBackup?>(null)
    }
    var restartRequired by remember {
        mutableStateOf(false)
    }
    var postLoginBackups by remember {
        mutableStateOf<List<GoogleDriveBackupClient.RemoteBackup>>(emptyList())
    }
    var showPostLoginRestorePrompt by remember {
        mutableStateOf(false)
    }
    var restorePromptHandledAccountId by remember {
        mutableStateOf<String?>(null)
    }

    fun acceptDriveAuthorization(result: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        driveController.accessToken(result)
            .onSuccess { token ->
                driveAccessToken = token
                driveAuthorized = true
                driveBusy = false
                errorMessage = null

                val signedInAccount = account
                if (signedInAccount != null &&
                    restorePromptHandledAccountId != signedInAccount.accountId
                ) {
                    scope.launch {
                        runCatching {
                            backupCoordinator.availableBackups(token, signedInAccount.accountId)
                        }.onSuccess { backups ->
                            restorePromptHandledAccountId = signedInAccount.accountId
                            if (backups.isNotEmpty()) {
                                postLoginBackups = backups
                                showPostLoginRestorePrompt = true
                            }
                        }.onFailure { failure ->
                            errorMessage = failure.message
                                ?: "Could not check Google Drive backups after sign-in."
                        }
                    }
                }
            }
            .onFailure { failure ->
                driveAccessToken = null
                driveAuthorized = false
                driveBusy = false
                errorMessage = failure.message
                    ?: "Google Drive returned no access token."
            }
    }

    val driveAuthorizationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { activityResult ->
            driveBusy = false

            driveController.resultFromIntent(activityResult.data)
                .onSuccess(::acceptDriveAuthorization)
                .onFailure { failure ->
                    driveAuthorized = false
                    errorMessage = failure.message
                        ?: "Google Drive authorization did not complete."
                }
        }

    if (showPostLoginRestorePrompt && postLoginBackups.isNotEmpty()) {
        val newestBackup = postLoginBackups.first()
        AlertDialog(
            onDismissRequest = { showPostLoginRestorePrompt = false },
            title = { Text("Google Drive backup found") },
            text = {
                Text(
                    "A THOOP backup from ${formatCloudBackupDate(newestBackup.createdTime)} " +
                        "is available (${formatBackupSize(newestBackup.sizeBytes)}). " +
                        "You can preview it before deciding whether to restore."
                )
            },
            dismissButton = {
                TextButton(
                    onClick = { showPostLoginRestorePrompt = false },
                ) { Text("Continue without restoring") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        availableBackups = postLoginBackups
                        selectedPreview = null
                        backupStatus = "Select a backup to validate and preview."
                        showPostLoginRestorePrompt = false
                    },
                ) { Text("Preview & Restore") }
            },
        )
    }

    pendingRestore?.let { backup ->
        AlertDialog(
            onDismissRequest = { if (!driveBusy) pendingRestore = null },
            title = { Text("Restore cloud backup?") },
            text = {
                Text(
                    "THOOP will check free space, create and validate a local safety snapshot, " +
                        "then replace local data with ${formatCloudBackupDate(backup.createdTime)}. " +
                        "Do not close THOOP during restore."
                )
            },
            dismissButton = {
                TextButton(
                    enabled = !driveBusy,
                    onClick = { pendingRestore = null },
                ) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    enabled = !driveBusy,
                    onClick = {
                        val token = driveAccessToken ?: return@TextButton
                        driveBusy = true
                        errorMessage = null
                        scope.launch {
                            runCatching { backupCoordinator.restoreBackup(token, backup) }
                                .onSuccess { outcome ->
                                    when (val result = outcome.result) {
                                        DataBackup.ImportResult.NeedsRestart -> {
                                            restartRequired = true
                                            backupStatus = "Restore completed. Safety snapshot kept locally."
                                            pendingRestore = null
                                        }
                                        is DataBackup.ImportResult.Failed -> errorMessage = result.message
                                        is DataBackup.ImportResult.TooLarge -> errorMessage = result.message
                                    }
                                }
                                .onFailure { errorMessage = it.message ?: "Cloud restore failed safely." }
                            driveBusy = false
                        }
                    },
                ) { Text(if (driveBusy) "Restoring..." else "Restore") }
            },
        )
    }

    SettingsCard(
        icon = Icons.Filled.AccountCircle,
        title = "Google Account",
        blurb = "Sign in to identify your THOOP account. " +
                "Health data remains on this device.",
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Metrics.space12),
        ) {
            val currentAccount = account

            if (currentAccount == null) {
                Text(
                    text = "Not signed in",
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )

                NoopButton(
                    text = if (busy) "Signing in..." else "Sign in with Google",
                    leadingIcon = Icons.Filled.Login,
                    fullWidth = true,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        errorMessage = null

                        scope.launch {
                            when (val result = controller.signIn(context)) {
                                is GoogleSignInResult.Success -> {
                                    account = result.account
                                }

                                GoogleSignInResult.Cancelled -> Unit

                                is GoogleSignInResult.Failure -> {
                                    errorMessage = result.message
                                }
                            }

                            busy = false
                        }
                    },
                )
            } else {
                Text(
                    text = currentAccount.displayName
                        ?.takeIf(String::isNotBlank)
                        ?: "Signed in",
                    style = NoopType.title2,
                    color = Palette.textPrimary,
                )

                Text(
                    text = currentAccount.email,
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )

                NoopButton(
                    text = if (busy) "Signing out..." else "Sign out",
                    leadingIcon = Icons.Filled.Logout,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    enabled = !busy,
                    onClick = {
                        busy = true
                        errorMessage = null

                        scope.launch {
                            controller.signOut()
                            account = null
                            driveAccessToken = null
                            driveAuthorized = false
                            backupStatus = null
                            busy = false
                        }
                    },
                )
            }

            errorMessage?.let { message ->
                Text(
                    text = message,
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }


            Text(
                text = if (driveAuthorized) {
                    "Google Drive backup: Authorized"
                } else {
                    "Google Drive backup: Not configured"
                },
                style = NoopType.subhead,
                color = if (driveAuthorized) Palette.textPrimary else Palette.textSecondary,
            )

            if (currentAccount != null && !driveAuthorized) {
                NoopButton(
                    text = if (driveBusy) "Connecting Google Drive..." else "Enable Google Drive Backup",
                    fullWidth = true,
                    enabled = !driveBusy && !busy,
                    onClick = {
                        driveBusy = true
                        errorMessage = null
                        backupStatus = null
                        driveController.authorize(
                            onSuccess = { result ->
                                if (result.hasResolution()) {
                                    val pendingIntent = result.pendingIntent
                                    if (pendingIntent == null) {
                                        driveBusy = false
                                        errorMessage = "Google Drive authorization could not be opened."
                                    } else {
                                        driveAuthorizationLauncher.launch(
                                            IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                                        )
                                    }
                                } else {
                                    acceptDriveAuthorization(result)
                                }
                            },
                            onFailure = { failure ->
                                driveBusy = false
                                errorMessage = failure.message
                                    ?: "Google Drive authorization did not complete."
                            },
                        )
                    },
                )
            }

            if (currentAccount != null && driveAuthorized) {
                NoopButton(
                    text = if (driveBusy) "Loading backups..." else "Restore from Google Drive",
                    fullWidth = true,
                    enabled = !driveBusy && !busy && driveAccessToken != null,
                    onClick = {
                        val token = driveAccessToken ?: return@NoopButton
                        driveBusy = true
                        errorMessage = null
                        selectedPreview = null
                        scope.launch {
                            runCatching { backupCoordinator.availableBackups(token, currentAccount.accountId) }
                                .onSuccess { backups ->
                                    availableBackups = backups
                                    backupStatus = if (backups.isEmpty()) "No cloud backups found."
                                        else "Select a backup to validate and preview."
                                }
                                .onFailure { errorMessage = it.message ?: "Could not load cloud backups." }
                            driveBusy = false
                        }
                    },
                )
                availableBackups.forEach { backup ->
                    NoopButton(
                        text = "${formatCloudBackupDate(backup.createdTime)} · ${formatBackupSize(backup.sizeBytes)}",
                        fullWidth = true,
                        enabled = !driveBusy,
                        onClick = {
                            val token = driveAccessToken ?: return@NoopButton
                            driveBusy = true
                            scope.launch {
                                runCatching { backupCoordinator.previewBackup(token, backup) }
                                    .onSuccess {
                                        selectedPreview = it
                                        backupStatus = "Backup validated. No local data was changed."
                                    }
                                    .onFailure { errorMessage = it.message ?: "Could not validate backup." }
                                driveBusy = false
                            }
                        },
                    )
                }
                selectedPreview?.let { preview ->
                    Text(
                        text = "${preview.backup.name}\n" +
                            "${formatCloudBackupDate(preview.backup.createdTime)} · " +
                            "${formatBackupSize(preview.backup.sizeBytes)}\nValid THOOP backup",
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                }

                selectedPreview?.let { preview ->
                    NoopButton(
                        text = "Restore this backup",
                        fullWidth = true,
                        enabled = !driveBusy,
                        onClick = { pendingRestore = preview.backup },
                    )
                }

                if (restartRequired) {
                    Text(
                        text = "Restore completed. Fully close and reopen THOOP before continuing.",
                        style = NoopType.footnote,
                        color = Palette.accent,
                    )
                }

                NoopButton(
                    text = if (driveBusy) "Backing up..." else "Back up now",
                    fullWidth = true,
                    enabled = !driveBusy && !busy && driveAccessToken != null,
                    onClick = {
                        val token = driveAccessToken ?: return@NoopButton
                        driveBusy = true
                        errorMessage = null
                        backupStatus = null
                        scope.launch {
                            runCatching {
                                backupCoordinator.backUpNow(token, currentAccount.accountId)
                            }.onSuccess { backup ->
                                val whenUploaded = DateFormat.getDateTimeInstance().format(Date())
                                backupStatus = "Uploaded $whenUploaded · ${formatBackupSize(backup.sizeBytes)} · newest 3 kept"
                            }.onFailure { failure ->
                                errorMessage = failure.message ?: "Google Drive backup failed."
                            }
                            driveBusy = false
                        }
                    },
                )
            }

            backupStatus?.let { status ->
                Text(
                    text = status,
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }

            Text(
                text = if (driveAuthorized) {
                    "The access token is kept only in memory. Preview does not change local data."
                } else {
                    "Signing in does not upload health data. Drive permission is requested only when enabled."
                },
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

private fun formatBackupSize(bytes: Long): String = when {
    bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}


private fun formatCloudBackupDate(createdTime: String): String = runCatching {
    DateFormat.getDateTimeInstance().format(Date.from(java.time.Instant.parse(createdTime)))
}.getOrDefault(createdTime.ifBlank { "Unknown date" })

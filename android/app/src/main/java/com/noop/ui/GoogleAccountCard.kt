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
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.noop.data.GoogleDriveBackupClient
import com.noop.data.GoogleDriveBackupCoordinator
import com.noop.data.GoogleDriveArchiveManager
import com.noop.data.DataBackup
import com.noop.data.CloudBackupCadence
import com.noop.data.CloudBackupSchedule
import com.noop.data.GoogleCloudBackupPreferences
import com.noop.data.GoogleCloudBackupScheduler
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.time.YearMonth
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
    val archiveManager = remember(context) {
        GoogleDriveArchiveManager(context.applicationContext)
    }

    val scope = rememberCoroutineScope()
    val drivePrefs = remember(context) {
        context.applicationContext.getSharedPreferences(
            "thoop_google_drive", android.content.Context.MODE_PRIVATE,
        )
    }

    var account by remember {
        mutableStateOf(controller.storedAccount())
    }

    var busy by remember {
        mutableStateOf(false)
    }

    var errorMessage by remember {
        mutableStateOf<String?>(null)
    }

    var driveAuthorized by remember(account?.accountId) {
        mutableStateOf(
            account?.accountId?.let { drivePrefs.getBoolean("configured.$it", false) } == true,
        )
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
    var archiveRecords by remember { mutableStateOf(archiveManager.archives()) }
    var archivePreview by remember { mutableStateOf<GoogleDriveArchiveManager.ArchivePreview?>(null) }
    var showArchiveBrowser by remember { mutableStateOf(false) }

    val cloudSchedulePreferences = remember(context) {
        GoogleCloudBackupPreferences(context.applicationContext)
    }
    var cloudSchedule by remember {
        mutableStateOf(cloudSchedulePreferences.read())
    }
    var cloudScheduleStatus by remember {
        mutableStateOf(cloudSchedulePreferences.status())
    }

    fun updateCloudSchedule(value: CloudBackupSchedule) {
        cloudSchedule = value
        cloudSchedulePreferences.write(value)
        GoogleCloudBackupScheduler.apply(context.applicationContext, value)
        cloudScheduleStatus = if (value.enabled) {
            "Automatic backup scheduled: ${value.cadence.label}."
        } else {
            "Automatic backup is off."
        }
    }

    fun acceptDriveAuthorization(result: com.google.android.gms.auth.api.identity.AuthorizationResult) {
        driveController.accessToken(result)
            .onSuccess { token ->
                driveAccessToken = token
                driveAuthorized = true
                account?.accountId?.let {
                    drivePrefs.edit().putBoolean("configured.$it", true).apply()
                }
                driveBusy = false
                errorMessage = null

                val signedInAccount = account
                if (signedInAccount != null) {
                    scope.launch {
                        runCatching {
                            backupCoordinator.availableBackups(token, signedInAccount.accountId)
                        }.onSuccess { backups ->
                            val newest = backups.firstOrNull()
                            val ack = drivePrefs.getString("restorePromptAcknowledged.${signedInAccount.accountId}", null)
                            if (newest != null && ack != newest.id) { postLoginBackups = backups; showPostLoginRestorePrompt = true }

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

    LaunchedEffect(account?.accountId) {
        val accountId = account?.accountId ?: return@LaunchedEffect
        if (!drivePrefs.getBoolean("configured.$accountId", false)) return@LaunchedEffect
        driveController.authorize(
            onSuccess = { result ->
                if (!result.hasResolution()) acceptDriveAuthorization(result)
            },
            onFailure = { /* Keep configured status; explicit Drive actions can retry. */ },
        )
    }

    if (showPostLoginRestorePrompt && postLoginBackups.isNotEmpty()) {
        val newestBackup = postLoginBackups.first()
        AlertDialog(
            onDismissRequest = { postLoginBackups.firstOrNull()?.let { newest -> account?.accountId?.let { id -> drivePrefs.edit().putString("restorePromptAcknowledged.$id", newest.id).apply() } }; showPostLoginRestorePrompt = false },
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
                    onClick = { postLoginBackups.firstOrNull()?.let { newest -> account?.accountId?.let { id -> drivePrefs.edit().putString("restorePromptAcknowledged.$id", newest.id).apply() } }; showPostLoginRestorePrompt = false },
                ) { Text("Continue without restoring") }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        postLoginBackups.firstOrNull()?.let { newest -> account?.accountId?.let { id -> drivePrefs.edit().putString("restorePromptAcknowledged.$id", newest.id).apply() } }; availableBackups = postLoginBackups
                        selectedPreview = null
                        backupStatus = "Select a backup to validate and preview."
                        showPostLoginRestorePrompt = false
                    },
                ) { Text("Preview & Restore") }
            },
        )
    }

    archivePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { archivePreview = null },
            title = { Text("Archived History · ${preview.record.month}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Text("Read-only preview", style = NoopType.subhead, color = Palette.accent)
                    Text("Total records: ${preview.totalRecords}", style = NoopType.body, color = Palette.textPrimary)
                    Text("Schema: ${preview.record.schemaVersion} · SHA-256 verified", style = NoopType.footnote, color = Palette.textSecondary)
                    preview.tableCounts.entries.filter { it.value > 0 }.take(12).forEach { row ->
                        Text("${row.key}: ${row.value}", style = NoopType.footnote, color = Palette.textSecondary)
                    }
                    Text("The temporary archive cache is removed when this preview closes.", style = NoopType.caption, color = Palette.textTertiary)
                }
            },
            confirmButton = { TextButton(onClick = { archivePreview = null }) { Text("Close") } },
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
        title = "Google Drive",
        blurb = "Sign in for verified full backup, full restore on a new phone, and read-only archived history.",
    collapsible = true,
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
                            account?.accountId?.let { drivePrefs.edit().remove("configured.$it").apply() }
                            account = null
                            driveAccessToken = null
                            driveAuthorized = false
                            backupStatus = null
                            busy = false
                        }
                    },
                )
            }

            if (driveAuthorized && currentAccount != null) {
                NoopButton(
                    text = if (driveBusy) "Working with Google Drive..." else "Create & Verify Archives",
                    fullWidth = true,
                    enabled = !driveBusy && !busy,
                    onClick = {
                        val token = driveAccessToken
                        if (token == null) {
                            errorMessage = "Google Drive authorization expired. Connect Google Drive again."
                        } else {
                            driveBusy = true
                            errorMessage = null
                            scope.launch {
                                runCatching { archiveManager.createAndVerifyArchives(token, currentAccount.accountId) }
                                    .onSuccess { created ->
                                        archiveRecords = archiveManager.archives()
                                        backupStatus = if (created.isEmpty()) {
                                            "All eligible monthly archives are already verified. Local data was kept."
                                        } else {
                                            "Created and verified ${created.size} monthly archive(s). Local data was kept."
                                        }
                                    }
                                    .onFailure { failure ->
                                        errorMessage = "Archive verification could not finish. Local data was kept. " +
                                            (failure.message?.substringBefore("/data/")?.trim()?.take(160)
                                                ?: "Try again after restarting THOOP.")
                                    }
                                driveBusy = false
                            }
                        }
                    },
                )
                NoopButton(
                    text = "Browse Archived History",
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    enabled = !driveBusy,
                    onClick = { archiveRecords = archiveManager.archives(); showArchiveBrowser = !showArchiveBrowser },
                )
                if (showArchiveBrowser) {
                    if (archiveRecords.isEmpty()) {
                        Text("No verified monthly archives yet.", style = NoopType.footnote, color = Palette.textSecondary)
                    } else {
                        archiveRecords.sortedByDescending { it.month }.forEach { archive ->
                            NoopButton(
                                text = "${archive.month} · ${archive.recordCount} records · Verified",
                                kind = NoopButtonKind.Tertiary,
                                fullWidth = true,
                                enabled = !driveBusy,
                                onClick = {
                                    val token = driveAccessToken
                                    if (token == null) {
                                        errorMessage = "Internet connection and Google Drive authorization are required."
                                    } else {
                                        driveBusy = true
                                        scope.launch {
                                            runCatching { archiveManager.previewMonthOnline(token, YearMonth.parse(archive.month)) }
                                                .onSuccess { archivePreview = it }
                                                .onFailure { failure ->
                                                    errorMessage = "Archived history could not be opened. Check the internet connection and try again."
                                                }
                                            driveBusy = false
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
                Text(
                    "The latest 90 days stay offline. Create & Verify never deletes local data. Older archives open read-only from private Google Drive app data.",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
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

            if (currentAccount != null && driveAuthorized) {
                Text(
                    text = "Automatic backup",
                    style = NoopType.subhead,
                    color = Palette.textPrimary,
                )
                Switch(
                    checked = cloudSchedule.enabled,
                    onCheckedChange = { enabled ->
                        updateCloudSchedule(cloudSchedule.copy(enabled = enabled))
                    },
                )
                NoopButton(
                    text = "Schedule: ${cloudSchedule.cadence.label}",
                    fullWidth = true,
                    enabled = !driveBusy,
                    onClick = {
                        val next = when (cloudSchedule.cadence) {
                            CloudBackupCadence.DAILY -> CloudBackupCadence.THREE_DAYS
                            CloudBackupCadence.THREE_DAYS -> CloudBackupCadence.WEEKLY
                            CloudBackupCadence.WEEKLY -> CloudBackupCadence.DAILY
                        }
                        updateCloudSchedule(cloudSchedule.copy(cadence = next))
                    },
                )
                Text(text = "Wi-Fi only", style = NoopType.footnote, color = Palette.textSecondary)
                Switch(
                    checked = cloudSchedule.wifiOnly,
                    onCheckedChange = { updateCloudSchedule(cloudSchedule.copy(wifiOnly = it)) },
                )
                Text(text = "Charging only", style = NoopType.footnote, color = Palette.textSecondary)
                Switch(
                    checked = cloudSchedule.chargingOnly,
                    onCheckedChange = { updateCloudSchedule(cloudSchedule.copy(chargingOnly = it)) },
                )
                NoopButton(
                    text = "Run automatic backup now",
                    fullWidth = true,
                    enabled = !driveBusy,
                    onClick = {
                        GoogleCloudBackupScheduler.runNow(context.applicationContext)
                        cloudScheduleStatus = "Automatic backup queued."
                    },
                )
                cloudScheduleStatus?.let { status ->
                    Text(text = status, style = NoopType.footnote, color = Palette.textTertiary)
                }
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

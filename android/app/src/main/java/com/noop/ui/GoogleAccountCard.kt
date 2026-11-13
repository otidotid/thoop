package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.noop.data.CompactDailySnapshots
import com.noop.data.HybridHistoryPolicy
import com.noop.data.AutomaticHistorySecretStore
import com.noop.data.AutomaticHistoryStatus
import com.noop.data.AutomaticHybridHistoryScheduler
import com.noop.data.DataBackup
import com.noop.data.CloudBackupCadence
import com.noop.data.CloudBackupSchedule
import com.noop.data.GoogleCloudBackupPreferences
import com.noop.data.GoogleCloudBackupScheduler
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.time.YearMonth
import java.time.LocalDate
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
    val snapshotsManager = remember(context) {
        CompactDailySnapshots(context.applicationContext)
    }
    val automaticHistorySecrets = remember(context) {
        AutomaticHistorySecretStore(context.applicationContext)
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
    var autoHistoryConfigured by remember(account?.accountId) {
        mutableStateOf(account?.accountId?.let(automaticHistorySecrets::isConfiguredFor) == true)
    }
    var showAutomaticHistorySetup by remember { mutableStateOf(false) }
    var automaticHistoryStatus by remember {
        mutableStateOf(AutomaticHistoryStatus(context.applicationContext).message())
    }
    var archiveRecords by remember { mutableStateOf(archiveManager.archives()) }
    var archivePreview by remember { mutableStateOf<GoogleDriveArchiveManager.ArchivePreview?>(null) }
    var showArchiveBrowser by remember { mutableStateOf(false) }
    // Transient, opt-in passphrase prompt. No password is written to settings or Drive metadata.
    var showArchivePassphraseDialog by remember { mutableStateOf(false) }
    var archiveOpenMonth by remember { mutableStateOf<YearMonth?>(null) }
    var archiveOpenDay by remember { mutableStateOf<LocalDate?>(null) }
    var selectedHistoryDay by remember {
        mutableStateOf(HybridHistoryPolicy.archiveCutoff(LocalDate.now()).toString())
    }
    var archivePassphrase by remember { mutableStateOf("") }
    var archivePassphraseConfirm by remember { mutableStateOf("") }
    var archiveMigrationMonth by remember { mutableStateOf<YearMonth?>(null) }
    var compactSnapshots by remember { mutableStateOf<List<CompactDailySnapshots.Summary>>(emptyList()) }
    // Each screen opening rebuilds the rolling seven-day cache using the actual local day.
    // No Google Drive token, passphrase or internet connection is needed.
    LaunchedEffect(snapshotsManager) {
        runCatching { snapshotsManager.refresh() }
            .onSuccess { compactSnapshots = it }
            .onFailure { errorMessage = "Local history snapshot could not refresh: " +
                (it.message?.take(120) ?: "please try again") }
    }
    // Full disaster-recovery backups use a user-held password. Never persist the password
    // to preferences or Google Drive metadata; another phone can restore with the same secret.
    var fullBackupAction by remember { mutableStateOf<String?>(null) }
    var fullBackupTarget by remember { mutableStateOf<GoogleDriveBackupClient.RemoteBackup?>(null) }
    var fullBackupPassword by remember { mutableStateOf("") }
    var fullBackupConfirm by remember { mutableStateOf("") }

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
                account?.accountId?.let { id ->
                    if (automaticHistorySecrets.isConfiguredFor(id)) {
                        AutomaticHybridHistoryScheduler.ensureScheduled(context.applicationContext)
                    }
                }
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

    // One-time enrollment only. Afterwards encrypted cloud uploads and snapshots run automatically;
    // there is no recurring Sync or Refresh button.
    if (showAutomaticHistorySetup) {
        AlertDialog(
            onDismissRequest = { if (!driveBusy) {
                showAutomaticHistorySetup = false
                archivePassphrase = ""
                archivePassphraseConfirm = ""
            } },
            title = { Text("Set up automatic encrypted history") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Text(
                        "Enter your EXISTING archive recovery passphrase (12+ characters). " +
                            "THOOP will protect a device-only working copy with Android Keystore, " +
                            "then automatically encrypt and verify rolling archives. " +
                            "Keep your original passphrase in your password manager for recovery. " +
                            "No local health records will be removed.",
                        style = NoopType.footnote, color = Palette.textSecondary,
                    )
                    OutlinedTextField(value = archivePassphrase,
                        onValueChange = { archivePassphrase = it },
                        label = { Text("Recovery passphrase") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                    OutlinedTextField(value = archivePassphraseConfirm,
                        onValueChange = { archivePassphraseConfirm = it },
                        label = { Text("Confirm recovery passphrase") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation())
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAutomaticHistorySetup = false
                    archivePassphrase = ""
                    archivePassphraseConfirm = ""
                }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    enabled = !driveBusy && archivePassphrase.length >= 12 &&
                        archivePassphrase == archivePassphraseConfirm,
                    onClick = {
                        val owner = account?.accountId ?: return@TextButton
                        val token = driveAccessToken ?: return@TextButton
                        val secret = archivePassphrase.toCharArray()
                        archivePassphrase = ""
                        archivePassphraseConfirm = ""
                        showAutomaticHistorySetup = false
                        driveBusy = true
                        errorMessage = null
                        scope.launch {
                            try {
                                // Reject a different passphrase before scheduling background writes.
                                val existing = archiveManager.archives()
                                    .filter { it.encrypted }.maxByOrNull { it.month }
                                if (existing != null) {
                                    archiveManager.previewMonthOnline(
                                        token, YearMonth.parse(existing.month), secret,
                                    )
                                }
                                automaticHistorySecrets.save(owner, secret)
                                autoHistoryConfigured = true
                                AutomaticHybridHistoryScheduler.scheduleCloud(context.applicationContext)
                                AutomaticHybridHistoryScheduler.runCloudNow(context.applicationContext)
                                automaticHistoryStatus = "Automatic encrypted history enabled; first sync queued."
                                backupStatus = automaticHistoryStatus
                            } catch (_: Exception) {
                                errorMessage = "Automatic history setup failed. Check your existing " +
                                    "archive passphrase and Google Drive connection. No local data changed."
                            } finally {
                                secret.fill('\u0000')
                                driveBusy = false
                            }
                        }
                    },
                ) { Text("Enable automatic history") }
            },
        )
    }

    if (showArchivePassphraseDialog) {
        val creating = archiveOpenMonth == null
        val migrating = archiveMigrationMonth != null
        AlertDialog(
            onDismissRequest = {
                if (!driveBusy) {
                    showArchivePassphraseDialog = false
                    archivePassphrase = ""
                    archivePassphraseConfirm = ""
                }
            },
            title = { Text(when { migrating -> "Encrypt existing archive"; creating -> "Encrypt monthly archives"; else -> "Unlock encrypted history" }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Text(
                        if (creating) {
                            (if (migrating) "Encrypt a new copy of ${archiveMigrationMonth}; the original stays in Google Drive. " else "") +
                            "Choose a recovery passphrase (12+ characters) and keep it in your password manager. " +
                                "It is NOT saved by THOOP. Without it, archives cannot be recovered on another phone."
                        } else {
                            "Enter the passphrase used when you created this archive. " +
                                "THOOP does not store your recovery passphrase."
                        },
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                    OutlinedTextField(
                        value = archivePassphrase,
                        onValueChange = { archivePassphrase = it },
                        label = { Text("Recovery passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    if (creating) {
                        OutlinedTextField(
                            value = archivePassphraseConfirm,
                            onValueChange = { archivePassphraseConfirm = it },
                            label = { Text("Confirm passphrase") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                        )
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showArchivePassphraseDialog = false
                    archivePassphrase = ""
                    archivePassphraseConfirm = ""
                }) { Text("Cancel") }
            },
            confirmButton = {
                TextButton(
                    enabled = !driveBusy && archivePassphrase.length >= 12 &&
                        (!creating || archivePassphrase == archivePassphraseConfirm),
                    onClick = {
                        val token = driveAccessToken ?: return@TextButton
                        val owner = account?.accountId ?: return@TextButton
                        val month = archiveOpenMonth
                        val day = archiveOpenDay
                        val migrationMonth = archiveMigrationMonth
                        val secret = archivePassphrase.toCharArray()
                        archivePassphrase = ""
                        archivePassphraseConfirm = ""
                        showArchivePassphraseDialog = false
                        archiveMigrationMonth = null
                        driveBusy = true
                        errorMessage = null
                        scope.launch {
                            try {
                                if (migrationMonth != null) {
                                    archiveManager.encryptLegacyMonth(token, owner, migrationMonth, secret)
                                    archiveRecords = archiveManager.archives()
                                    backupStatus = "$migrationMonth encrypted and verified. Original Drive archive and all local data kept."
                                } else if (month == null) {
                                    val created = archiveManager.createAndVerifyArchives(token, owner, secret)
                                    archiveRecords = archiveManager.archives()
                                    backupStatus = if (created.isEmpty()) {
                                        "Rolling cloud history is current through " +
                                            "${HybridHistoryPolicy.archiveCutoff(LocalDate.now())}. Local data kept."
                                    } else {
                                        "Encrypted and verified ${created.size} rolling archive(s), " +
                                            "through ${HybridHistoryPolicy.archiveCutoff(LocalDate.now())}. " +
                                            "All local health and food data kept."
                                    }
                                } else {
                                    archivePreview = if (day == null) {
                                        archiveManager.previewMonthOnline(token, month, secret)
                                    } else {
                                        archiveManager.previewDayOnline(token, day, secret)
                                    }
                                }
                            } catch (failure: Exception) {
                                errorMessage = if (month == null) {
                                    "Encrypted archive could not be verified. No local history was deleted. " +
                                        (failure.message?.take(120) ?: "Try again.")
                                } else {
                                    "Could not unlock the archive. Check your passphrase and internet connection."
                                }
                            } finally {
                                secret.fill('\u0000')
                                driveBusy = false
                            }
                        }
                    },
                ) { Text(if (migrating) "Encrypt copy" else if (creating) "Encrypt & archive" else "Unlock") }
            },
        )
    }

    archivePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = { archivePreview = null },
            title = { Text("Archived History · ${preview.selectedDay ?: preview.record.month}") },
            text = {
                Column(
                    modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Metrics.space12),
                ) {
                    Text("Read-only verified online history", style = NoopType.subhead, color = Palette.accent)
                    preview.record.coveredThrough?.let {
                        Text("Archived through: $it", style = NoopType.footnote, color = Palette.textSecondary)
                    }
                    Text("Total records: ${preview.totalRecords}", style = NoopType.body, color = Palette.textPrimary)
                    Text("Schema: ${preview.record.schemaVersion} · " +
                        (if (preview.record.encrypted) "Encrypted + authenticated" else "Legacy unencrypted") +
                        " · SHA-256 verified", style = NoopType.footnote, color = Palette.textSecondary)
                    preview.tableCounts.entries.filter { it.value > 0 }.take(12).forEach { row ->
                        Text("${row.key}: ${row.value}", style = NoopType.footnote, color = Palette.textSecondary)
                    }
                    Text("Historical data preview (read-only)", style = NoopType.subhead, color = Palette.accent)
                    preview.history.forEach { line ->
                        Text(line, style = NoopType.footnote, color = Palette.textSecondary)
                    }
                    Text("The temporary archive cache is removed when this preview closes.", style = NoopType.caption, color = Palette.textTertiary)
                }
            },
            confirmButton = { TextButton(onClick = { archivePreview = null }) { Text("Close") } },
        )
    }

    fullBackupAction?.let { action ->
        val creating = action == "backup"
        AlertDialog(
            onDismissRequest = {
                if (!driveBusy) {
                    fullBackupAction = null
                    fullBackupTarget = null
                    fullBackupPassword = ""
                    fullBackupConfirm = ""
                }
            },
            title = { Text(when (action) {
                "backup" -> "Encrypt complete cloud backup"
                "restore" -> "Unlock and restore backup"
                else -> "Unlock cloud backup"
            }) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                    Text(if (creating)
                        "Choose a recovery passphrase (12+ characters). Keep it in a password " +
                            "manager. You will need it on a replacement phone; THOOP does not store it. " +
                            "This includes the health and food databases and supported profile settings."
                    else "Enter the recovery passphrase used for this encrypted backup. " +
                        "Never share your passphrase in a chat or screenshot.")
                    OutlinedTextField(
                        value = fullBackupPassword,
                        onValueChange = { fullBackupPassword = it },
                        label = { Text("Recovery passphrase") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                    )
                    if (creating) {
                        OutlinedTextField(
                            value = fullBackupConfirm,
                            onValueChange = { fullBackupConfirm = it },
                            label = { Text("Confirm passphrase") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                        )
                    }
                }
            },
            dismissButton = { TextButton(enabled = !driveBusy, onClick = {
                fullBackupAction = null; fullBackupTarget = null
                fullBackupPassword = ""; fullBackupConfirm = ""
            }) { Text("Cancel") } },
            confirmButton = {
                TextButton(
                    enabled = !driveBusy && fullBackupPassword.length >= 12 &&
                        (!creating || fullBackupPassword == fullBackupConfirm),
                    onClick = {
                        val token = driveAccessToken ?: return@TextButton
                        val current = account ?: return@TextButton
                        val target = fullBackupTarget
                        val password = fullBackupPassword.toCharArray()
                        fullBackupPassword = ""; fullBackupConfirm = ""
                        fullBackupAction = null; fullBackupTarget = null
                        driveBusy = true
                        errorMessage = null
                        scope.launch {
                            try {
                                when (action) {
                                    "backup" -> {
                                        val uploaded = backupCoordinator.backUpNow(token, current.accountId, password)
                                        backupStatus = "Encrypted complete backup uploaded and verified: " +
                                            formatBackupSize(uploaded.sizeBytes) + ". Save your recovery passphrase."
                                    }
                                    "preview" -> {
                                        val remote = target ?: error("No backup selected.")
                                        selectedPreview = backupCoordinator.previewBackup(token, remote, password)
                                        backupStatus = "Encrypted backup authenticated. Database integrity will be checked before restore."
                                    }
                                    "restore" -> {
                                        val remote = target ?: error("No backup selected.")
                                        val outcome = backupCoordinator.restoreBackup(token, remote, password)
                                        when (val result = outcome.result) {
                                            DataBackup.ImportResult.NeedsRestart -> {
                                                restartRequired = true
                                                backupStatus = "Restore completed. Restart THOOP. A safety backup was kept locally."
                                                pendingRestore = null
                                            }
                                            is DataBackup.ImportResult.Failed -> errorMessage = result.message
                                            is DataBackup.ImportResult.TooLarge -> errorMessage = result.message
                                        }
                                    }
                                }
                            } catch (failure: Exception) {
                                errorMessage = failure.message ?: "Google Drive recovery failed safely."
                            } finally {
                                password.fill('\u0000')
                                driveBusy = false
                            }
                        }
                    },
                ) { Text(when (action) { "backup" -> "Encrypt & back up"; "restore" -> "Unlock & restore"; else -> "Unlock & preview" }) }
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
                        if (backup.encrypted) {
                            pendingRestore = null
                            fullBackupTarget = backup
                            fullBackupAction = "restore"
                            return@TextButton
                        }
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
                            automaticHistorySecrets.clear()
                            AutomaticHybridHistoryScheduler.stopCloud(context.applicationContext)
                            autoHistoryConfigured = false
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
                Text(
                    if (autoHistoryConfigured) "Automatic encrypted cloud history: enabled" else
                        "Automatic encrypted cloud history: one-time setup required",
                    style = NoopType.subhead,
                    color = if (autoHistoryConfigured) Palette.accent else Palette.textSecondary,
                )
                Text(
                    AutomaticHistoryStatus(context.applicationContext).message(),
                    style = NoopType.footnote, color = Palette.textSecondary,
                )
                if (!autoHistoryConfigured) {
                    NoopButton(
                        text = "Set up automatic encrypted history once",
                        kind = NoopButtonKind.Secondary,
                        fullWidth = true,
                        enabled = !driveBusy && !busy,
                        onClick = {
                            if (driveAccessToken == null) {
                                errorMessage = "Reconnect Google Drive to finish automatic history setup."
                            } else {
                                archivePassphrase = ""
                                archivePassphraseConfirm = ""
                                showAutomaticHistorySetup = true
                            }
                        },
                    )
                }
                Text(
                    "Cloud history is eligible from day 22 onward. " +
                        "Days 15–21 use the rolling local snapshot window; " +
                        "no local records are deleted.",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
                OutlinedTextField(
                    value = selectedHistoryDay,
                    onValueChange = { selectedHistoryDay = it.take(10) },
                    label = { Text("Online history day (YYYY-MM-DD)") },
                    singleLine = true,
                )
                NoopButton(
                    text = "Open verified online history for day",
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    enabled = !driveBusy,
                    onClick = {
                        val day = runCatching { LocalDate.parse(selectedHistoryDay.trim()) }.getOrNull()
                        val token = driveAccessToken
                        when {
                            day == null -> errorMessage = "Enter a valid day in YYYY-MM-DD format."
                            HybridHistoryPolicy.tier(day, LocalDate.now()) !=
                                HybridHistoryPolicy.Tier.ENCRYPTED_CLOUD_ELIGIBLE -> {
                                errorMessage = "This date is not yet in the cloud window. " +
                                    "Cloud history starts at day 22."
                            }
                            token == null -> errorMessage = "Reconnect Google Drive to read online history."
                            else -> {
                                val record = archiveRecords.firstOrNull { it.month == YearMonth.from(day).toString() }
                                if (record == null) {
                                    errorMessage = "This day has no verified cloud archive yet. " +
                                        "Automatic archiving is pending. Check the sync status or reconnect Drive."
                                } else if (record.encrypted) {
                                    archiveOpenDay = day
                                    archiveOpenMonth = YearMonth.from(day)
                                    archiveMigrationMonth = null
                                    archivePassphrase = ""
                                    archivePassphraseConfirm = ""
                                    showArchivePassphraseDialog = true
                                } else {
                                    driveBusy = true
                                    scope.launch {
                                        runCatching { archiveManager.previewDayOnline(token, day) }
                                            .onSuccess { archivePreview = it }
                                            .onFailure { errorMessage = "Unable to verify this historical day." }
                                        driveBusy = false
                                    }
                                }
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
                                text = "${archive.month} · " +
                                    "through ${archive.coveredThrough ?: "month end"} · " +
                                    "${archive.recordCount} records · " +
                                    (if (archive.encrypted) "Encrypted" else "Legacy unencrypted"),
                                kind = NoopButtonKind.Tertiary,
                                fullWidth = true,
                                enabled = !driveBusy,
                                onClick = {
                                    val token = driveAccessToken
                                    if (token == null) {
                                        errorMessage = "Internet connection and Google Drive authorization are required."
                                    } else if (archive.encrypted) {
                                        archiveOpenMonth = YearMonth.parse(archive.month)
                                        archiveOpenDay = null
                                        archivePassphrase = ""
                                        archivePassphraseConfirm = ""
                                        showArchivePassphraseDialog = true
                                    } else {
                                        // Existing archives are plaintext. Keep the legacy reader for compatibility.
                                        driveBusy = true
                                        scope.launch {
                                            runCatching { archiveManager.previewMonthOnline(token, YearMonth.parse(archive.month)) }
                                                .onSuccess { archivePreview = it }
                                                .onFailure {
                                                    errorMessage = "Legacy archived history could not be opened."
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
                    "Once set up, encrypted rolling archives update automatically as each new day becomes eligible. " +
                        "Android may defer uploads until network access is available. " +
                        "Encrypted cloud history does not replace your local data or full backups.",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
            Text(
                "Rolling local history · days 15–21 (offline)",
                style = NoopType.subhead,
                color = Palette.textPrimary,
            )
            Text(
                "Refreshed daily in the background and when this screen opens. All original records stay local.",
                style = NoopType.footnote,
                color = Palette.textSecondary,
            )
            if (compactSnapshots.isEmpty()) {
                Text("No daily snapshot data in this seven-day window.",
                    style = NoopType.footnote, color = Palette.textSecondary)
            } else {
                compactSnapshots.take(35).forEach { snapshot ->
                    val details = buildList {
                        snapshot.sleepMin?.let { add("sleep ${it.toInt()} min") }
                        snapshot.avgHrv?.let { add("HRV ${it.toInt()} ms") }
                        snapshot.recovery?.let { add("recovery ${it.toInt()}") }
                        snapshot.steps?.let { add("${it} steps") }
                        snapshot.foodCalories?.let { add("${it.toInt()} kcal") }
                        add("${snapshot.recordCount} records")
                    }
                    Text("${snapshot.day} · ${snapshot.source}: ${details.joinToString(" · ")}",
                        style = NoopType.footnote, color = Palette.textSecondary)
                }
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
                        text = "${formatCloudBackupDate(backup.createdTime)} · ${formatBackupSize(backup.sizeBytes)} · " +
                            if (backup.encrypted) "Encrypted" else "Legacy unencrypted",
                        fullWidth = true,
                        enabled = !driveBusy,
                        onClick = {
                            val token = driveAccessToken ?: return@NoopButton
                            if (backup.encrypted) {
                                fullBackupTarget = backup
                                fullBackupAction = "preview"
                                return@NoopButton
                            }
                            driveBusy = true
                            scope.launch {
                                runCatching { backupCoordinator.previewBackup(token, backup) }
                                    .onSuccess {
                                        selectedPreview = it
                                        backupStatus = "Backup downloaded and checked. Database integrity will be checked before restore."
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
                            "${formatBackupSize(preview.backup.sizeBytes)}\nValid THOOP backup · " +
                            if (preview.includesFoodHistory) "Food history included" else "Legacy health-only backup",
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                }

                selectedPreview?.let { preview ->
                    NoopButton(
                        text = "Restore this backup",
                        fullWidth = true,
                        enabled = !driveBusy && !restartRequired,
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
                    text = if (driveBusy) "Backing up..." else "Encrypted full backup now",
                    fullWidth = true,
                    enabled = !driveBusy && !busy && driveAccessToken != null,
                    onClick = {
                        fullBackupAction = "backup"
                        fullBackupTarget = null
                        fullBackupPassword = ""
                        fullBackupConfirm = ""
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
                Text(
                    "Scheduled backups currently use the legacy unencrypted format. " +
                        "Use Encrypted full backup now for password-protected recovery. " +
                        "Account sign-in and hardware pairing need to be repeated on a new phone.",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
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

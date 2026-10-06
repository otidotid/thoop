package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
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

    val driveAuthorizationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartIntentSenderForResult(),
        ) { activityResult ->
            driveBusy = false

            driveController.resultFromIntent(activityResult.data)
                .onSuccess {
                    driveAuthorized = true
                    errorMessage = null
                }
                .onFailure { failure ->
                    driveAuthorized = false
                    errorMessage = failure.message
                        ?: "Google Drive authorization did not complete."
                }
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
                text = "Cloud backup: Not configured",
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )

            Text(
                text = "Signing in does not upload health data. " +
                        "Google Drive permission will be requested separately later.",
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}
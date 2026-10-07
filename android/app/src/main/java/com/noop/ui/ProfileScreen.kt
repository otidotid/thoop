package com.noop.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.R

/** Account-facing surface: profile details, local photo, and Google/Drive. */
@Composable
internal fun ProfileScreen(onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val profile = remember(context) { ProfileStore.from(context) }
    var revision by remember { mutableIntStateOf(0) }
    @Suppress("UNUSED_VARIABLE") val tick = revision
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && !ProfileAvatarStore.setAvatarFromUri(context, uri)) {
            Toast.makeText(context, "Couldn't use that photo. Try another.", Toast.LENGTH_LONG).show()
        }
    }

    ScreenScaffold(
        title = uiString(R.string.l10n_settings_screen_profile_ff4fc027),
        subtitle = "Your profile, profile photo, and Google account.",
    ) {
        SettingsCard(
            icon = Icons.Filled.AccountCircle,
            title = uiString(R.string.l10n_settings_screen_profile_ff4fc027),
            blurb = "Used only for THOOP's on-device calculations.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileNumberField("Age", profile.age.toString()) { value ->
                    value.toIntOrNull()?.let { profile.setAge(it); revision++ }
                }
                ProfileNumberField("Weight (kg)", profile.weightKg.toString()) { value ->
                    value.toDoubleOrNull()?.let { profile.weightKg = it; revision++ }
                }
                ProfileNumberField("Height (cm)", profile.heightCm.toString()) { value ->
                    value.toDoubleOrNull()?.let { profile.heightCm = it; revision++ }
                }
                ProfileNumberField("Waist (cm, optional)", profile.waistCm.toString()) { value ->
                    value.toDoubleOrNull()?.let { profile.waistCm = it; revision++ }
                }
            }
        }

        SettingsCard(
            icon = Icons.Filled.AccountCircle,
            title = uiString(R.string.l10n_settings_screen_profile_photo_33f385bb),
            blurb = "Optional and stored only on this phone.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProfileAvatar(size = 64.dp, contentDescription = "Profile photo")
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    NoopButton(
                        text = if (ProfileAvatarStore.hasAvatar) "Change photo" else "Choose photo",
                        kind = NoopButtonKind.Secondary,
                        onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    )
                    if (ProfileAvatarStore.hasAvatar) {
                        NoopButton(
                            text = "Remove photo",
                            kind = NoopButtonKind.Secondary,
                            onClick = { ProfileAvatarStore.clearAvatar(context) },
                        )
                    }
                }
            }
        }

        GoogleAccountCard()

        SettingsCard(
            icon = Icons.Filled.Settings,
            title = uiString(R.string.nav_settings),
            blurb = "Device, scoring, display, automation, backup, and other general preferences.",
        ) {
            NoopButton(text = "Open Settings", kind = NoopButtonKind.Secondary, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun ProfileNumberField(label: String, initial: String, onCommit: (String) -> Unit) {
    var value by remember(initial) { androidx.compose.runtime.mutableStateOf(initial) }
    OutlinedTextField(
        value = value,
        onValueChange = { value = it; onCommit(it) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )
}

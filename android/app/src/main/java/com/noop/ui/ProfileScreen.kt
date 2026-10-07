package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noop.R

/**
 * Account-facing home opened from the Today avatar.
 *
 * General application settings deliberately remain on their own route. Profile editing and photo
 * management still use the established Settings implementation for this checkpoint, while the Google
 * account/Drive module lives here as the canonical account surface.
 */
@Composable
internal fun ProfileScreen(onOpenSettings: () -> Unit) {
    ScreenScaffold(
        title = uiString(R.string.l10n_settings_screen_profile_ff4fc027),
        subtitle = "Your profile, profile photo, and Google account.",
    ) {
        SettingsCard(
            icon = Icons.Filled.AccountCircle,
            title = uiString(R.string.l10n_settings_screen_profile_ff4fc027),
            blurb = "Review or update the personal details used by THOOP's on-device calculations.",
        ) {
            NoopButton(
                text = "Open profile details",
                kind = NoopButtonKind.Secondary,
                onClick = onOpenSettings,
            )
        }

        SettingsCard(
            icon = Icons.Filled.AccountCircle,
            title = uiString(R.string.l10n_settings_screen_profile_photo_33f385bb),
            blurb = "Your optional avatar stays on this phone. Open profile details to choose, change, or remove it.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProfileAvatar(size = 64.dp, contentDescription = "Profile photo")
                Column(modifier = Modifier.weight(1f)) {
                    NoopButton(
                        text = "Manage profile photo",
                        kind = NoopButtonKind.Secondary,
                        onClick = onOpenSettings,
                    )
                }
            }
        }

        GoogleAccountCard()

        SettingsCard(
            icon = Icons.Filled.Settings,
            title = uiString(R.string.nav_settings),
            blurb = "Device, scoring, display, automation, backup, and other general preferences.",
        ) {
            NoopButton(
                text = "Open Settings",
                kind = NoopButtonKind.Secondary,
                onClick = onOpenSettings,
            )
        }
    }
}

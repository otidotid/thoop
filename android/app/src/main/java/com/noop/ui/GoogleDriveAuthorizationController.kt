package com.noop.ui

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

private const val DRIVE_APPDATA_SCOPE =
    "https://www.googleapis.com/auth/drive.appdata"

/**
 * Handles Google Drive authorization separately from Google authentication.
 *
 * This controller requests access only to THOOP's private Drive appDataFolder.
 * It does not upload health data and does not persist OAuth access tokens.
 */
internal class GoogleDriveAuthorizationController(
    context: Context,
) {
    private val authorizationClient =
        Identity.getAuthorizationClient(context.applicationContext)

    private val authorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(
                listOf(Scope(DRIVE_APPDATA_SCOPE)),
            )
            .build()

    fun authorize(
        onSuccess: (AuthorizationResult) -> Unit,
        onFailure: (Exception) -> Unit,
    ) {
        authorizationClient.authorize(authorizationRequest)
            .addOnSuccessListener(onSuccess)
            .addOnFailureListener(onFailure)
    }

    fun accessToken(result: AuthorizationResult): Result<String> =
        result.accessToken
            ?.takeIf(String::isNotBlank)
            ?.let { Result.success(it) }
            ?: Result.failure(
                IllegalStateException("Google Drive returned no access token."),
            )

    fun resultFromIntent(
        data: Intent?,
    ): Result<AuthorizationResult> {
        if (data == null) {
            return Result.failure(
                IllegalStateException("Google Drive authorization returned no data."),
            )
        }

        return runCatching {
            authorizationClient.getAuthorizationResultFromIntent(data)
        }
    }
}
package com.noop.ui

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

private const val GOOGLE_WEB_CLIENT_ID =
    "542598370283-1g40ddq1h2ieus49dnf6v0msoqdqdpb9.apps.googleusercontent.com"

internal data class GoogleAccountState(
    val accountId: String,
    val displayName: String?,
    val email: String,
)

internal sealed interface GoogleSignInResult {
    data class Success(
        val account: GoogleAccountState,
    ) : GoogleSignInResult

    data object Cancelled : GoogleSignInResult

    data class Failure(
        val message: String,
    ) : GoogleSignInResult
}
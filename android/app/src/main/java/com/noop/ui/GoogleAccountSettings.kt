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

/**
 * Stores account display metadata only.
 *
 * Google ID tokens and OAuth access tokens are never persisted.
 */
internal class GoogleAccountStore(
    context: Context,
) {
    private val preferences = context.getSharedPreferences(
        "thoop_google_account",
        Context.MODE_PRIVATE,
    )

    fun read(): GoogleAccountState? {
        val accountId = preferences.getString("account_id", null) ?: return null
        val email = preferences.getString("email", null) ?: return null

        return GoogleAccountState(
            accountId = accountId,
            displayName = preferences.getString("display_name", null),
            email = email,
        )
    }

    fun write(account: GoogleAccountState) {
        preferences.edit()
            .putString("account_id", account.accountId)
            .putString("display_name", account.displayName)
            .putString("email", account.email)
            .apply()
    }

    fun clear() {
        preferences.edit().clear().apply()
    }
}

internal class GoogleSignInController(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val credentialManager = CredentialManager.create(appContext)
    private val accountStore = GoogleAccountStore(appContext)

    fun storedAccount(): GoogleAccountState? {
        return accountStore.read()
    }

    suspend fun signIn(
        hostContext: Context,
    ): GoogleSignInResult {
        val activity = hostContext.findComponentActivity()
            ?: return GoogleSignInResult.Failure(
                "Google Sign-In requires an active THOOP screen.",
            )

        val googleOption = GetSignInWithGoogleOption.Builder(
            GOOGLE_WEB_CLIENT_ID,
        ).build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        return try {
            val response = credentialManager.getCredential(
                context = activity,
                request = request,
            )

            val customCredential = response.credential as? CustomCredential
                ?: return GoogleSignInResult.Failure(
                    "Google returned an unsupported credential.",
                )

            if (
                customCredential.type !=
                GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                return GoogleSignInResult.Failure(
                    "Google returned an unsupported credential.",
                )
            }

            val googleCredential = GoogleIdTokenCredential.createFrom(
                customCredential.data,
            )

            val account = GoogleAccountState(
                accountId = googleCredential.id,
                displayName = googleCredential.displayName,
                email = googleCredential.id,
            )

            accountStore.write(account)

            GoogleSignInResult.Success(account)
        } catch (_: GetCredentialCancellationException) {
            GoogleSignInResult.Cancelled
        } catch (failure: GetCredentialException) {
            GoogleSignInResult.Failure(
                failure.message ?: "Google Sign-In did not complete.",
            )
        } catch (failure: Exception) {
            GoogleSignInResult.Failure(
                failure.message ?: "Google Sign-In did not complete.",
            )
        }
    }

    suspend fun signOut() {
        runCatching {
            credentialManager.clearCredentialState(
                ClearCredentialStateRequest(),
            )
        }

        accountStore.clear()
    }
}

private tailrec fun Context.findComponentActivity(): ComponentActivity? {
    return when (this) {
        is ComponentActivity -> this
        is ContextWrapper -> baseContext.findComponentActivity()
        else -> null
    }
}
package com.noop.ingest

import com.noop.data.ImportSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Background access is an additional explicit grant; scheduling is never an access grant. */
internal object HealthConnectAutoImportPolicy {
    enum class Access { READY, DISABLED, UNAVAILABLE, UNSUPPORTED, READ_PERMISSION_REQUIRED, BACKGROUND_PERMISSION_REQUIRED }
    enum class Outcome { SUCCESS, BLOCKED, RETRY, FAILED }
    data class Execution(val outcome: Outcome, val message: String)

    fun access(enabled: Boolean, available: Boolean, backgroundSupported: Boolean,
        backgroundGranted: Boolean, hasSelectedReadGrant: Boolean): Access = when {
        !enabled -> Access.DISABLED
        !available -> Access.UNAVAILABLE
        !backgroundSupported -> Access.UNSUPPORTED
        !hasSelectedReadGrant -> Access.READ_PERMISSION_REQUIRED
        !backgroundGranted -> Access.BACKGROUND_PERMISSION_REQUIRED
        else -> Access.READY
    }

    fun message(access: Access): String = when (access) {
        Access.READY -> "Background access is allowed for the selected Health Connect data."
        Access.DISABLED -> "Automatic import is off."
        Access.UNAVAILABLE -> "Health Connect is unavailable. Manual import remains available when it is installed."
        Access.UNSUPPORTED -> "This Health Connect version does not support background reads. Use manual import."
        Access.READ_PERMISSION_REQUIRED -> "Automatic import is paused. Allow at least one selected data type."
        Access.BACKGROUND_PERMISSION_REQUIRED -> "Automatic import is paused. Allow background Health Connect access."
    }

    suspend fun execute(access: Access, runAttempt: Int, import: suspend () -> ImportSummary): Execution {
        currentCoroutineContext().ensureActive()
        if (access != Access.READY) return Execution(Outcome.BLOCKED, message(access))
        return try {
            val summary = import()
            currentCoroutineContext().ensureActive()
            if (summary.successful) Execution(Outcome.SUCCESS,
                "Health Connect import completed: ${summary.totalRows} rows.")
            else incomplete(runAttempt)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { incomplete(runAttempt) }
    }

    private fun incomplete(attempt: Int): Execution = if (attempt < 3)
        Execution(Outcome.RETRY, "Health Connect import did not complete. It will retry.")
    else Execution(Outcome.FAILED, "Health Connect import did not complete. Check access or retry manually.")
}

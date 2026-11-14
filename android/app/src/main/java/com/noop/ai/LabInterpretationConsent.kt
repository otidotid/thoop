package com.noop.ai

import android.content.Context

/** Separate, default-off grant for exact Lab Book evidence; never inferred from old summary consent. */
object LabInterpretationConsent {
    private const val PREF = "lab_interpretation_consent_v1"
    fun read(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("granted", false)

    fun write(context: Context, granted: Boolean) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean("granted", granted).apply()
    }
}

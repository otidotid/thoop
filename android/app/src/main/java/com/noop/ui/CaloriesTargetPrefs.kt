package com.noop.ui

import android.content.Context

/** Local display target. It never changes Total, Resting, or Active Calories calculation. */
object CaloriesTargetPrefs {
    private const val KEY = "today.totalCaloriesTargetKcal"
    const val MIN = 500
    const val MAX = 10_000
    fun get(context: Context): Int? = NoopPrefs.of(context).getInt(KEY, 0).takeIf { it in MIN..MAX }
    fun set(context: Context, kcal: Int?) {
        val editor = NoopPrefs.of(context).edit()
        if (kcal == null) editor.remove(KEY) else editor.putInt(KEY, kcal.coerceIn(MIN, MAX))
        editor.apply()
    }
}

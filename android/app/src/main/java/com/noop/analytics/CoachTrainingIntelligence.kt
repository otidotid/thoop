package com.noop.analytics

import com.noop.data.FoodIntakeEntry
import com.noop.data.WorkoutRow
import java.time.Instant

/** Bounded, deterministic summaries for Cloud Coach. No raw arrays leave the device. */
object CoachTrainingIntelligence {
    fun context(nowSeconds: Long, workouts: List<WorkoutRow>, foods: List<FoodIntakeEntry>, goal: RaceGoal? = null): String {
        fun window(days: Int): List<WorkoutRow> = workouts.filter { it.startTs in (nowSeconds - days * 86400L)..nowSeconds }
        fun summary(days: Int): String {
            val rows = window(days); val run = rows.filter { it.sport.contains("run", true) || it.sport.contains("treadmill", true) }
            return "LAST $days DAYS: workouts=${rows.size}; runningDistanceKm=${"%.1f".format(run.sumOf { it.distanceM ?: 0.0 } / 1000.0)}; durationHours=${"%.1f".format(rows.sumOf { it.durationS ?: (it.endTs-it.startTs).toDouble() } / 3600.0)}; workoutCalories=${rows.sumOf { it.energyKcal ?: 0.0 }.toInt()}"
        }
        val race = goal?.let { "RACE GOAL: ${it.title}; date=${it.eventDate}; distanceKm=${it.distanceKm}; targetMinutes=${it.targetMinutes ?: "none"}; phase=${it.phase(nowSeconds)}" }.orEmpty()
        return listOf(summary(7), summary(28), summary(90), race).filter { it.isNotBlank() }.joinToString("\n")
    }
}

data class RaceGoal(val title: String, val eventDate: Long, val distanceKm: Double, val targetMinutes: Int? = null) {
    fun phase(nowSeconds: Long): String {
        val days = ((eventDate - nowSeconds) / 86400L).toInt()
        return when { days < 0 -> "post_race_recovery"; days <= 7 -> "race_week"; days <= 21 -> "taper"; days <= 42 -> "peak"; days <= 84 -> "build"; else -> "base" }
    }
}

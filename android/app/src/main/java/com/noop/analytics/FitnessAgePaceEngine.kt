package com.noop.analytics

import java.time.LocalDate

/**
 * A fitness-specific longitudinal pace derived from weekly Fitness Age readings.
 *
 * This is deliberately NOT a clinical biological Pace of Aging score. 1.0x means
 * Fitness Age increased one year per calendar year; 0.0x means it was flat; a
 * negative value means the Fitness Age trend improved. Theil-Sen's median of all
 * pairwise slopes makes the result deterministic and resistant to one noisy week.
 */
object FitnessAgePaceEngine {
    const val MIN_POINTS = 4
    const val MAX_POINTS = 12
    const val MIN_DISPLAY = -2.0
    const val MAX_DISPLAY = 3.0

    data class Point(val day: String, val fitnessAge: Double)

    enum class Confidence { CALIBRATING, LOW, MEDIUM, HIGH }

    data class Result(
        val pace: Double?,
        val sampleWeeks: Int,
        val confidence: Confidence,
        val weeksNeeded: Int,
    ) {
        val ready: Boolean get() = pace != null
    }

    fun calculate(points: List<Point>): Result {
        // Invalid values are ignored. Duplicate dates are consolidated by keeping
        // the last supplied result for that weekly anchor.
        val clean = points.asSequence()
            .filter { it.fitnessAge.isFinite() }
            .mapNotNull { point ->
                runCatching { LocalDate.parse(point.day) }.getOrNull()
                    ?.let { it to point.fitnessAge }
            }
            .toList()
            .groupBy({ it.first }, { it.second })
            .map { (date, values) -> date to values.last() }
            .sortedBy { it.first }
            .takeLast(MAX_POINTS)

        val confidence = when {
            clean.size < MIN_POINTS -> Confidence.CALIBRATING
            clean.size < 8 -> Confidence.LOW
            clean.size < MAX_POINTS -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }
        if (clean.size < MIN_POINTS) {
            return Result(null, clean.size, confidence, MIN_POINTS - clean.size)
        }

        val slopes = ArrayList<Double>()
        for (i in 0 until clean.lastIndex) {
            for (j in i + 1 until clean.size) {
                val elapsedDays = clean[j].first.toEpochDay() - clean[i].first.toEpochDay()
                if (elapsedDays > 0L) {
                    val elapsedYears = elapsedDays / 365.2425
                    slopes += (clean[j].second - clean[i].second) / elapsedYears
                }
            }
        }
        if (slopes.isEmpty()) return Result(null, clean.size, Confidence.CALIBRATING, 1)
        slopes.sort()
        val middle = slopes.size / 2
        val median = if (slopes.size % 2 == 1) slopes[middle]
        else (slopes[middle - 1] + slopes[middle]) / 2.0
        return Result(
            pace = median.coerceIn(MIN_DISPLAY, MAX_DISPLAY),
            sampleWeeks = clean.size,
            confidence = confidence,
            weeksNeeded = 0,
        )
    }

    fun label(result: Result): String {
        val pace = result.pace ?: return "CALIBRATING"
        return when {
            pace < 0.0 -> "IMPROVING"
            pace < 0.75 -> "SLOWER"
            pace < 1.25 -> "STEADY"
            pace < 1.75 -> "FASTER"
            else -> "MUCH FASTER"
        }
    }
}

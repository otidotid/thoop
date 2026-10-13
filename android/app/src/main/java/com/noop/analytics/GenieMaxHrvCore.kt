package com.noop.analytics

import kotlin.math.sqrt

/**
 * Clean-room Kotlin port of GenieMax Core's time-domain and Poincare HRV kernel.
 * Input is an already-clean, contiguous NN interval set in milliseconds. THOOP owns RR cleaning,
 * coverage, over-count and beat-quality gates; this object owns only deterministic statistics.
 */
object GenieMaxHrvCore {
    data class Metrics(
        val rmssd: Double,
        val sdnn: Double,
        val meanNN: Double,
        val pnn50: Double,
        val sd1: Double,
        val sd2: Double,
        val sd1Sd2Ratio: Double?,
    )

    fun metrics(nn: List<Double>): Metrics? {
        if (nn.size < 3 || nn.any { !it.isFinite() || it <= 0.0 }) return null
        val differences = nn.zipWithNext { a, b -> b - a }
        val rmssd = sqrt(differences.sumOf { it * it } / differences.size.toDouble())
        val mean = nn.average()
        val sdnn = sqrt(nn.sumOf { (it - mean) * (it - mean) } / nn.size.toDouble())
        val pnn50 = differences.count { kotlin.math.abs(it) > 50.0 } * 100.0 / differences.size.toDouble()
        val sd1 = rmssd / sqrt(2.0)
        val sd2 = sqrt(maxOf(0.0, 2.0 * sdnn * sdnn - sd1 * sd1))
        val ratio = if (sd2 > 0.0) sd1 / sd2 else null
        if (listOf(rmssd, sdnn, mean, pnn50, sd1, sd2).any { !it.isFinite() }) return null
        return Metrics(rmssd, sdnn, mean, pnn50, sd1, sd2, ratio)
    }
}

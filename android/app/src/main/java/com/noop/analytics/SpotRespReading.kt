package com.noop.analytics

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Spot resting respiration from the same R-R capture used by [SpotHrvReading]. Diagnostic only. */
object SpotRespReading {
    const val MIN_RPM = 8.0
    const val MAX_RPM = 25.0
    const val MIN_CLEAN_BEATS = 20
    const val MIN_SPAN_SECONDS = 45.0
    const val MAX_REJECTED_FRACTION = 0.35
    private const val RESAMPLE_HZ = 4.0
    private const val STEP_RPM = 0.25
    private const val MIN_PEAK_RATIO = 1.8

    enum class Quality { GOOD, FAIR }
    enum class Reason { TOO_MUCH_NOISE, TOO_SHORT, UNSTABLE_PATTERN }

    sealed interface Outcome {
        data class Reading(val rpm: Double, val confidence: Double, val quality: Quality) : Outcome
        data class Collecting(val clean: Int, val needed: Int) : Outcome
        data class Incomplete(val reason: Reason) : Outcome
    }

    fun compute(rrMs: List<Int>): Outcome {
        if (rrMs.isEmpty()) return Outcome.Collecting(0, MIN_CLEAN_BEATS)
        val raw = rrMs.map(Int::toDouble)
        val clean = HrvAnalyzer.cleanRR(raw)
        val rejected = 1.0 - clean.size.toDouble() / raw.size.toDouble()
        if (rejected > MAX_REJECTED_FRACTION) return Outcome.Incomplete(Reason.TOO_MUCH_NOISE)
        if (clean.size < MIN_CLEAN_BEATS) return Outcome.Collecting(clean.size, MIN_CLEAN_BEATS)

        val beatTimes = DoubleArray(clean.size)
        var elapsed = 0.0
        clean.forEachIndexed { index, rr ->
            elapsed += rr / 1000.0
            beatTimes[index] = elapsed
        }
        if (elapsed < MIN_SPAN_SECONDS) return Outcome.Incomplete(Reason.TOO_SHORT)

        val dt = 1.0 / RESAMPLE_HZ
        val count = (elapsed / dt).toInt() + 1
        if (count < 16) return Outcome.Incomplete(Reason.TOO_SHORT)
        val signal = DoubleArray(count)
        var segment = 0
        for (i in 0 until count) {
            val t = i * dt
            while (segment + 1 < beatTimes.size && beatTimes[segment + 1] < t) segment++
            val next = (segment + 1).coerceAtMost(clean.lastIndex)
            val t0 = if (segment == 0) 0.0 else beatTimes[segment - 1]
            val t1 = beatTimes[next]
            val v0 = clean[segment]
            val v1 = clean[next]
            val fraction = if (t1 <= t0) 0.0 else ((t - t0) / (t1 - t0)).coerceIn(0.0, 1.0)
            signal[i] = v0 + fraction * (v1 - v0)
        }

        val meanT = (count - 1) * dt / 2.0
        val meanY = signal.average()
        var covariance = 0.0
        var varianceT = 0.0
        for (i in signal.indices) {
            val x = i * dt - meanT
            covariance += x * (signal[i] - meanY)
            varianceT += x * x
        }
        val slope = if (varianceT > 0.0) covariance / varianceT else 0.0
        val detrended = DoubleArray(count) { i -> signal[i] - (meanY + slope * (i * dt - meanT)) }

        val powers = ArrayList<Pair<Double, Double>>()
        var candidateRpm = MIN_RPM
        while (candidateRpm <= MAX_RPM + 1e-9) {
            val hz = candidateRpm / 60.0
            var real = 0.0
            var imaginary = 0.0
            for (i in detrended.indices) {
                val hann = if (count <= 1) 1.0 else 0.5 - 0.5 * cos(2.0 * PI * i / (count - 1))
                val angle = 2.0 * PI * hz * i * dt
                val value = detrended[i] * hann
                real += value * cos(angle)
                imaginary -= value * sin(angle)
            }
            powers += candidateRpm to (real * real + imaginary * imaginary)
            candidateRpm += STEP_RPM
        }
        val peak = powers.maxByOrNull { it.second } ?: return Outcome.Incomplete(Reason.UNSTABLE_PATTERN)
        val sorted = powers.map { it.second }.sorted()
        val baseline = sorted[sorted.size / 2]
        if (baseline <= 1e-9) return Outcome.Incomplete(Reason.UNSTABLE_PATTERN)
        val ratio = peak.second / baseline
        if (!ratio.isFinite() || ratio < MIN_PEAK_RATIO) return Outcome.Incomplete(Reason.UNSTABLE_PATTERN)

        val confidence = ((ratio - MIN_PEAK_RATIO) / 4.0).coerceIn(0.0, 1.0)
        val quality = if (ratio >= 3.0 && rejected <= 0.15) Quality.GOOD else Quality.FAIR
        return Outcome.Reading(peak.first, confidence, quality)
    }
}

package com.noop.analytics

import com.noop.data.MetricSeriesRow
import com.noop.data.ScoreInputProvenanceRow

/** Read-only pairing: an old AI factor must not outlive the score calculation that produced it. */
internal fun currentFitnessAgeMetabolicFactor(
    scoreSource: String,
    scoreDay: String,
    scoreProvenance: ScoreInputProvenanceRow?,
    factor: MetricSeriesRow?,
    factorProvenance: ScoreInputProvenanceRow?,
): MetricSeriesRow? {
    if (factor == null || factor.deviceId != scoreSource || factor.day != scoreDay ||
        factor.key != "fitness_factor_metabolic_delta") return null
    val aiVersion = MetabolicFitnessAge.ALGORITHM_VERSION
    // Preserve historical local results, including legacy records without provenance.
    if (scoreProvenance?.algorithmVersion != aiVersion && factorProvenance?.algorithmVersion != aiVersion)
        return factor
    fun matches(trace: ScoreInputProvenanceRow?, key: String) = trace != null &&
        trace.deviceId == scoreSource && trace.sourceId == scoreSource && trace.day == scoreDay &&
        trace.key == key && trace.algorithmVersion == aiVersion
    if (!matches(scoreProvenance, "fitness_age") || !matches(factorProvenance, factor.key)) return null
    // Both rows are written with the same captured weekly context, including the immutable request ID.
    val captured = scoreProvenance?.observedInputs?.takeIf { it.isNotBlank() } ?: return null
    return factor.takeIf { factorProvenance?.observedInputs == captured }
}

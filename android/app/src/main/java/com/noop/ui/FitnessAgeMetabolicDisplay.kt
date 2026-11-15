package com.noop.ui

import com.noop.data.ScoreInputProvenanceRow

/** The AI label belongs only to the exact stored score point, never today's profile or another source. */
internal fun hasAiMetabolicProvenance(
    provenance: ScoreInputProvenanceRow?,
    scoreSource: String,
    scoreDay: String,
): Boolean = provenance != null &&
    provenance.deviceId == scoreSource && provenance.day == scoreDay &&
    provenance.key == "fitness_age" && provenance.algorithmVersion == "fitness-age-ai-metabolic-v1"

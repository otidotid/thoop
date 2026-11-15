package com.noop.analytics

/** Android opt-in experiment: AI supplies the metabolic input; THOOP owns the fixed blend. */
object MetabolicFitnessAge {
    const val ALGORITHM_VERSION = "fitness-age-ai-metabolic-v1"
    const val MIN_DELTA_YEARS = -2.0
    const val MAX_DELTA_YEARS = 4.0

    /** Product-bounded metabolic range: a product bound, not clinical validation of an AI estimate. */
    fun apply(base: FitnessAgeResult, fitnessDomainAge: Double, deltaYears: Double): FitnessAgeResult {
        require(deltaYears.isFinite() && deltaYears in MIN_DELTA_YEARS..MAX_DELTA_YEARS)
        require(fitnessDomainAge.isFinite() && base.chronoAge.isFinite())
        val age = FitnessAgeEngine.blendMetabolicAge(fitnessDomainAge, base.chronoAge, deltaYears)
        return base.copy(fitnessAge = age, deltaYears = base.chronoAge - age,
            metabolicDeltaYears = deltaYears, lowerConfidence = true)
    }
}

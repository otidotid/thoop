package com.noop.data

/** Columns authoritatively observed by a completed, selected and granted Health Connect read. */
internal data class HealthConnectDailyReadMask(
    val steps: Boolean = false,
    val activeKcal: Boolean = false,
    val basalKcal: Boolean = false,
) {
    init {
        require(!basalKcal || activeKcal) { "Basal observation requires an active-calorie observation." }
    }

    val hasObservedFields: Boolean get() = steps || activeKcal || basalKcal

    fun merge(incoming: AppleDaily, previous: AppleDaily?): AppleDaily {
        require(incoming.deviceId == SOURCE_ID) { "Expected the Health Connect source." }
        require(previous == null || previous.deviceId == incoming.deviceId && previous.day == incoming.day) {
            "Health Connect daily rows must have the same source and day."
        }
        // Unsupported legacy columns (VO2, HR and weight) remain exactly as stored. An observed null
        // still clears its column; absent permission/selection is not evidence of a source deletion.
        val base = previous ?: AppleDaily(SOURCE_ID, incoming.day)
        return base.copy(
            steps = if (steps) incoming.steps else base.steps,
            activeKcal = if (activeKcal) incoming.activeKcal else base.activeKcal,
            basalKcal = if (basalKcal) incoming.basalKcal else base.basalKcal,
        )
    }

    companion object {
        const val SOURCE_ID = "health-connect"
    }
}

package com.noop.analytics

/**
 * Clean-room Kotlin port of GenieMax SleepArchitecture's conservative despeckle pass.
 * THOOP keeps ownership of window detection, stage classification, physiology guards, session
 * reconciliation, manual edits and persistence. This pass only removes isolated A-B-A stage flecks.
 */
object GenieMaxSleepArchitecture {
    private val valid = setOf("deep", "light", "rem", "wake")

    fun smooth(labels: List<String>): List<String> {
        if (labels.size < 3 || labels.any { it !in valid }) return labels
        var current = labels
        repeat(2) {
            val next = current.toMutableList()
            for (i in 1 until current.lastIndex) {
                if (current[i] != current[i - 1] && current[i - 1] == current[i + 1]) {
                    next[i] = current[i - 1]
                }
            }
            current = next
        }
        return current
    }

    fun hybridSmooth(labels: List<String>, thoopSmoother: (List<String>) -> List<String>): List<String> =
        smooth(thoopSmoother(labels))

    fun invariantPreserving(before: List<String>, after: List<String>): Boolean =
        before.size == after.size && after.all { it in valid }
}

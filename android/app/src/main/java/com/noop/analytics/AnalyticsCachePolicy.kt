package com.noop.analytics

/**
 * Memory policy shared by recent-analysis caches.
 *
 * Only compact scored-day results may be retained. Raw HR, R-R, motion, sleep-stage, or temperature
 * arrays must never be placed in these caches. The normal analyzeRecent window is 21 days; the larger
 * ceiling exists only so an explicit history repair cannot grow the process cache without bound.
 */
internal object AnalyticsCachePolicy {
    const val MAX_RETAINED_DAYS: Int = 64

    /** Returns the newest ISO local-day keys that may remain cached. */
    fun retainedDays(days: Collection<String>, limit: Int = MAX_RETAINED_DAYS): Set<String> {
        if (limit <= 0 || days.isEmpty()) return emptySet()
        return days.asSequence()
            .distinct()
            .sortedDescending()
            .take(limit)
            .toSet()
    }
}

package com.noop.analytics

/** One daily step total reported by an external source. */
data class DailyStepSource(val packageName: String, val steps: Long)

enum class StepSourceClass { ZEPP, PHONE, OTHER }

data class ResolvedDailySteps(
    val steps: Long,
    val packageName: String,
    val sourceClass: StepSourceClass,
)

/**
 * THOOP's explicit per-day source policy: Zepp first, then the phone step counter, then any other
 * real imported counter. WHOOP's motion estimate is not mixed here; the estimate engine is invoked
 * only when this resolver returns null. Totals are never added across sources.
 */
object StepsSourcePriority {
    private val zeppTokens = listOf("zepp", "amazfit", "huami")
    private val phoneTokens = listOf("android", "google", "samsung", "pixel", "phone")

    fun classify(packageName: String): StepSourceClass {
        val id = packageName.lowercase()
        return when {
            zeppTokens.any(id::contains) -> StepSourceClass.ZEPP
            phoneTokens.any(id::contains) -> StepSourceClass.PHONE
            else -> StepSourceClass.OTHER
        }
    }

    fun resolve(sources: Collection<DailyStepSource>): ResolvedDailySteps? = sources
        .asSequence()
        .filter { it.steps > 0L }
        .map { it to classify(it.packageName) }
        .sortedWith(
            compareBy<Pair<DailyStepSource, StepSourceClass>> {
                when (it.second) {
                    StepSourceClass.ZEPP -> 0
                    StepSourceClass.PHONE -> 1
                    StepSourceClass.OTHER -> 2
                }
            }.thenByDescending { it.first.steps },
        )
        .firstOrNull()
        ?.let { (source, kind) -> ResolvedDailySteps(source.steps, source.packageName, kind) }
}

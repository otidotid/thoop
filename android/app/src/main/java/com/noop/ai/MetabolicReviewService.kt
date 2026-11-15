package com.noop.ai

import android.content.Context
import com.noop.analytics.IntelligenceEngine
import com.noop.analytics.UserProfile
import com.noop.data.LabMarkerRow
import com.noop.data.MetabolicEstimateStore
import com.noop.data.WhoopRepository
import com.noop.ui.NoopPrefs
import com.noop.ui.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Explicit review is the only network entry point. Normal calculations only read accepted local evidence. */
class MetabolicReviewService(
    private val context: Context,
    private val repo: WhoopRepository,
    private val sourceId: String,
    private val activeSource: () -> String = { sourceId },
) {
    private val store = MetabolicEstimateStore(context)
    private fun profile() = ProfileStore(context.getSharedPreferences(ProfileStore.PREFS, Context.MODE_PRIVATE)).toUserProfile()
    private fun consent() = activeSource() == sourceId && store.enabled() && AiKeyStore.readConsent(context) &&
        NoopPrefs.coachSignals(context) && LabInterpretationConsent.read(context)

    data class Outcome(val message: String, val saved: Boolean = false)

    suspend fun review(): Outcome = withContext(Dispatchers.IO) {
        if (!consent()) return@withContext fallbackOutcome("Enable health sharing, on-device signals, exact Lab Book sharing and AI metabolic estimates before requesting a new estimate.")
        reuseCurrent()?.let { return@withContext it }
        val day = scoreDay()
        val rows = MetabolicEstimateStore.sourceRows(repo, sourceId)
        if (rows.isEmpty()) return@withContext fallbackOutcome("Add Lab Book readings before reviewing metabolic impact.")
        val plan = LabInterpretationBatcher.plan(rows, day)
        val client = CloudCoachClient()
        val findings = LabInterpretationBatcher.run(plan, ::consent,
            { MetabolicEstimateStore.sourceRows(repo, sourceId) }, { client.interpretLabs(it, ::consent) })
        applyValidated(findings, rows)
    }

    /** Reuses a completed lab review without giving the ordinary Coach response numerical authority. */
    suspend fun applyValidated(findings: LabInterpretation.Result, originals: List<LabMarkerRow>): Outcome = withContext(Dispatchers.IO) {
        if (!consent()) return@withContext fallbackOutcome("No new AI estimate was requested because consent is off.")
        reuseCurrent()?.let { return@withContext it }
        val day = scoreDay()
        val capturedProfile = profile()
        val request = MetabolicEstimate.prepare(originals, findings, day, capturedProfile.age, capturedProfile.sex, true)
            ?: return@withContext fallbackOutcome("Metabolic estimate unavailable: the latest readings are incomplete, ambiguous, invalid, or exceed the request size.")
        // Network work finishes before entering the calculation transaction.
        val wire = CloudCoachClient().metabolicEstimate(request, ::consent)
        currentCoroutineContext().ensureActive()
        repo.withMetabolicCalculationTransaction activation@ {
            if (activeSource() != sourceId) return@activation sourceChangedOutcome()
            // Capture the previous output and its provenance in the same source snapshot as validation
            // and calculation. The append-only receipt is never rewritten if activation is retried.
            val computedId = sourceId + "-noop"
            val keys = listOf("fitness_age", "fitness_factor_metabolic_delta")
            val previous = keys.flatMap { repo.metricSeries(computedId, it, day, day) }
            val provenance = keys.mapNotNull { repo.scoreInputDetails(computedId, day, it) }
            val accepted = MetabolicEstimate.validate(wire, request, MetabolicEstimateStore.sourceRows(repo, sourceId))
            currentCoroutineContext().ensureActive()
            if (!consent()) return@activation fallbackLocked("Consent or the active device changed; no new AI estimate was activated.")
            if (!sameProfile(capturedProfile, profile()) || day != scoreDay())
                return@activation fallbackLocked("Profile or score date changed during review. Please review again.")
            if (accepted == null)
                return@activation fallbackLocked("AI metabolic estimate unavailable or rejected by validation.")
            if (!store.save(accepted, sourceId, previous, provenance))
                return@activation fallbackLocked("The new AI estimate could not be activated.")
            if (activeSource() != sourceId) return@activation sourceChangedOutcome()
            val saved = IntelligenceEngine.recomputeFitnessAgeOnly(repo, profile(), sourceId)
            savedOutcome(saved, day, accepted.requestId)
        }
    }

    /** A cached estimate must be checked and consumed under the same Room snapshot as its output. */
    private suspend fun reuseCurrent(): Outcome? = repo.withMetabolicCalculationTransaction reuse@ {
        currentCoroutineContext().ensureActive()
        if (activeSource() != sourceId) return@reuse sourceChangedOutcome()
        val day = scoreDay()
        val capturedProfile = profile()
        val accepted = store.current(repo, sourceId, day, capturedProfile) ?: return@reuse null
        if (!consent() || day != scoreDay() || !sameProfile(capturedProfile, profile()))
            return@reuse fallbackLocked("The review settings changed; no new AI estimate was activated.")
        val saved = IntelligenceEngine.recomputeFitnessAgeOnly(repo, capturedProfile, sourceId)
        savedOutcome(saved, day, accepted.requestId)
    }

    private suspend fun fallbackOutcome(reason: String): Outcome =
        repo.withMetabolicCalculationTransaction { fallbackLocked(reason) }

    /** Refresh invalid cached output instead of claiming that a stale saved AI score is a local result. */
    private suspend fun fallbackLocked(reason: String): Outcome {
        currentCoroutineContext().ensureActive()
        if (activeSource() != sourceId) return sourceChangedOutcome()
        val saved = IntelligenceEngine.recomputeFitnessAgeOnly(repo, profile(), sourceId)
        if (!saved) return Outcome("$reason No replacement Fitness Age was saved: more resting-heart-rate history is needed. Existing saved scores remain.")
        val trace = repo.scoreInputDetails(sourceId + "-noop", scoreDay(), "fitness_age")
        return if (trace?.algorithmVersion == com.noop.analytics.MetabolicFitnessAge.ALGORITHM_VERSION)
            Outcome("$reason A previously saved AI estimate remains in use; no new estimate was activated.", true)
        else Outcome("$reason Fitness Age was recalculated from current local inputs.", true)
    }

    private fun sameProfile(first: UserProfile, second: UserProfile): Boolean =
        first.age == second.age && first.sex == second.sex

    private fun sourceChangedOutcome() = Outcome("The active device changed. No Fitness Age was updated by this review; review again for the active device.")

    private suspend fun savedOutcome(saved: Boolean, day: String, requestId: String): Outcome {
        if (!saved) return Outcome("AI metabolic estimate saved, but no replacement Fitness Age was saved: more resting-heart-rate history is needed. Existing saved scores remain.")
        val trace = repo.scoreInputDetails(sourceId + "-noop", day, "fitness_age")
        return if (trace?.algorithmVersion == com.noop.analytics.MetabolicFitnessAge.ALGORITHM_VERSION &&
            trace.observedInputs?.contains("request=$requestId;") == true)
            Outcome("Fitness Age updated using the saved AI metabolic estimate and THOOP’s fixed 20% blend.", true)
        else if (trace?.algorithmVersion == com.noop.analytics.FitnessAgeEngine.ALGORITHM_VERSION)
            Outcome("The estimate no longer matches the current inputs. Fitness Age was recalculated from current local inputs.", true)
        else Outcome("Fitness Age was saved, but it did not use this review’s estimate.", true)
    }

    /** Also used after disabling the experiment, restoring the existing local calculation without network access. */
    suspend fun recompute(): Boolean = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (activeSource() != sourceId) return@withContext false
        repo.withMetabolicCalculationTransaction {
            if (activeSource() != sourceId) return@withMetabolicCalculationTransaction false
            IntelligenceEngine.recomputeFitnessAgeOnly(repo, profile(), sourceId)
        }
    }

    companion object {
        fun scoreDay(today: LocalDate = LocalDate.now()): String =
            today.with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY)).toString()
    }
}

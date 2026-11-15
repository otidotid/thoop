package com.noop.data

import android.app.Application
import android.content.Context
import androidx.room.Room
import com.noop.ai.LabInterpretation
import com.noop.ai.MetabolicEstimate
import com.noop.ai.MetabolicReviewService
import com.noop.analytics.IntelligenceEngine
import com.noop.analytics.MetabolicFitnessAge
import com.noop.analytics.UserProfile
import com.noop.ui.ProfileStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Real Room + private-file persistence + the production recompute path. No mocked repository/engine. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class MetabolicEstimatePersistenceTest {
    private lateinit var context: Context
    private lateinit var db: WhoopDatabase
    private lateinit var repo: WhoopRepository
    private lateinit var store: MetabolicEstimateStore
    private val profile = UserProfile(age = 40.0, sex = "male")
    private val source = "my-whoop"
    private val computed = "my-whoop-noop"
    private val anchor = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.SATURDAY))
    private val day = anchor.toString()
    private val sampleDay = anchor.minusDays(2).toString()
    private val originalRows: List<LabMarkerRow> get() = listOf(
        lab("weight", 81.0, "kg"), lab("height", 180.0, "cm"),
        lab("waist", 90.0, "cm"), lab("body_fat", 20.0, "%"),
        lab("custom_apo_b", 112.123456789, "mg/dL").copy(category = "other", displayName = "Apolipoprotein B"),
    )
    private fun lab(key: String, value: Double, unit: String) = LabMarkerRow(
        id = "source-$key", deviceId = source, markerKey = key, category = "bodyMeasurement", day = sampleDay,
        takenAt = anchor.minusDays(2).toEpochDay() * 86_400, value = value, unit = unit, source = "manual",
        note = "Original source note", referenceText = "Original reported range",
    )
    private fun findings(rows: List<LabMarkerRow>): LabInterpretation.Result {
        val request = LabInterpretation.prepare(rows, day, true)!!
        val observations = JSONObject(request.json).getJSONArray("observations")
        val findings = JSONArray((0 until observations.length()).map { index ->
            val observation = observations.getJSONObject(index)
            JSONObject().put("recordRef", observation.getString("recordRef"))
                .put("fingerprint", observation.getString("fingerprint"))
                .put("identity", "IDENTIFIED").put("confidence", "MODERATE")
                .put("domains", JSONArray(listOf("metabolic")))
                .put("interpretation", "Use the dated original measurement and unit.")
                .put("evidence", JSONArray()).put("limitations", JSONArray(listOf("Experimental interpretation, not clinical validation.")))
        })
        val wire = JSONObject().put("schemaVersion", LabInterpretation.VERSION)
            .put("requestId", request.requestId).put("findings", findings)
        return LabInterpretation.validate(wire.toString(), request, rows)
    }
    private fun request() = MetabolicEstimate.prepare(originalRows, findings(originalRows), day, 40.0, "male", true)!!
    private fun wire(request: MetabolicEstimate.Request, delta: Double = 2.0): JSONObject {
        val rows = JSONObject(request.json).getJSONArray("observations")
        val refs = (0 until rows.length()).map { rows.getJSONObject(it).getString("recordRef") }
        return JSONObject().put("schemaVersion", MetabolicEstimate.VERSION).put("requestId", request.requestId)
            .put("metabolicAgeDeltaYears", delta).put("confidence", "HIGH").put("recordRefs", JSONArray(refs))
            .put("reasoning", "Explicit experimental estimate from the complete dated snapshot.")
            .put("limitations", JSONArray(listOf("Not a clinically validated age conversion.")))
    }
    private fun accepted(delta: Double = 2.0): MetabolicEstimate.Accepted {
        val request = request()
        return MetabolicEstimate.validate(wire(request, delta).toString(), request, originalRows)!!
    }
    private fun records(): List<File> = File(context.filesDir, "metabolic-estimates-v1").walkTopDown()
        .filter { it.isFile && it.extension == "json" }.toList()

    @Before fun createDatabase() = runBlocking {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("metabolic_estimate_v1", Context.MODE_PRIVATE).edit().clear().commit()
        val dir = File(context.filesDir, "metabolic-estimates-v1")
        if (dir.exists()) {
            check(dir.canonicalPath.startsWith(context.filesDir.canonicalPath + File.separator))
            dir.walkBottomUp().filter { it != dir }.forEach { check(it.delete()) }
        }
        db = Room.inMemoryDatabaseBuilder(context, WhoopDatabase::class.java).allowMainThreadQueries().build()
        repo = WhoopRepository(db, context)
        store = MetabolicEstimateStore(context)
        repo.upsertLabMarkers(originalRows)
        db.whoopDao().upsertDailyMetrics((0..6).map { offset -> DailyMetric(
            deviceId = source, day = anchor.minusDays((6 - offset).toLong()).toString(),
            restingHr = 65, strain = 60.0, totalSleepMin = 450.0, avgHrv = 33.0, steps = 7000,
        ) })
    }

    @After fun closeDatabase() { db.close() }

    private suspend fun score(): Double = db.whoopDao().metricSeries(computed, "fitness_age", day, day).single().value
    private suspend fun recompute(): Double {
        assertTrue(IntelligenceEngine.recomputeFitnessAgeOnly(repo, profile, source))
        return score()
    }
    private suspend fun assertOriginalRowsUnchanged() {
        for (row in originalRows) assertEquals(row, db.whoopDao().labMarkerById(row.id))
    }
    private suspend fun activate(accepted: MetabolicEstimate.Accepted = accepted()): Boolean {
        store.setEnabled(true)
        val previous = listOf("fitness_age", "fitness_factor_metabolic_delta").flatMap {
            db.whoopDao().metricSeries(computed, it, day, day)
        }
        val provenance = previous.mapNotNull { db.whoopDao().scoreInputDetails(computed, it.day, it.key) }
        return store.save(accepted, source, previous, provenance)
    }

    @Test fun validatedAiScalarReachesSavedProductionScoreWithOriginalEvidenceAndPriorOutputAudit() = runBlocking {
        val baseline = recompute()
        assertEquals(36.94594594594595, baseline, 1e-10)
        val estimate = accepted()
        assertTrue(activate(estimate))
        assertEquals(37.34594594594594, recompute(), 1e-10)
        assertEquals(0.4, score() - baseline, 1e-10)
        assertEquals(2.0, db.whoopDao().metricSeries(computed, "fitness_factor_metabolic_delta", day, day).single().value, 0.0)
        val provenance = db.whoopDao().scoreInputDetails(computed, day, "fitness_age")!!
        assertEquals(MetabolicFitnessAge.ALGORITHM_VERSION, provenance.algorithmVersion)
        assertTrue(provenance.observedInputs!!.contains(estimate.requestId))
        assertTrue(provenance.observedInputs!!.contains(MetabolicEstimate.MODEL))
        assertTrue(provenance.observedInputs!!.contains(MetabolicEstimate.PROMPT_VERSION))
        assertTrue(provenance.observedInputs!!.contains("source=manual"))
        val record = JSONObject(records().single().readText())
        val previous = record.getJSONArray("previousOutputs")
        val priorAge = (0 until previous.length()).map { previous.getJSONObject(it) }.single { it.getString("key") == "fitness_age" }
        assertEquals(baseline, priorAge.getDouble("value"), 0.0)
        assertTrue(record.getJSONArray("previousProvenance").length() > 0)
        assertNotNull(MetabolicEstimate.restore(record.getString("estimate"), originalRows, 40.0, "male", day))
        assertOriginalRowsUnchanged()
        // The accepted scalar changes only the metabolic contribution; waist VO2 stays deterministic.
        assertEquals(47.405, db.whoopDao().metricSeries(computed, "vo2max_est", day, day).single().value, 1e-10)
    }

    @Test fun disablingTheExperimentRestoresTheExistingLocalCalculationAndKeepsAuditHistory() = runBlocking {
        val baseline = recompute()
        assertTrue(activate())
        assertNotEquals(baseline, recompute(), 1e-10)
        val audit = records().single().readBytes()
        store.setEnabled(false)
        assertNull(store.current(repo, source, day, profile))
        assertEquals(baseline, recompute(), 0.0)
        assertEquals(com.noop.analytics.FitnessAgeEngine.ALGORITHM_VERSION,
            db.whoopDao().scoreInputDetails(computed, day, "fitness_age")!!.algorithmVersion)
        assertArrayEquals(audit, records().single().readBytes())
        assertOriginalRowsUnchanged()
    }

    @Test fun editingOrDeletingAnInputInvalidatesTheSavedEstimateAndFallsBackWithoutRewritingOtherSources() = runBlocking {
        val baseline = recompute()
        assertTrue(activate())
        assertNotEquals(baseline, recompute(), 1e-10)
        val apo = originalRows.single { it.markerKey == "custom_apo_b" }
        val edited = apo.copy(value = 130.5, note = "Intentional user edit")
        repo.upsertLabMarkers(listOf(edited))
        assertNull(store.current(repo, source, day, profile))
        assertEquals(baseline, recompute(), 0.0)
        assertEquals(edited, db.whoopDao().labMarkerById(apo.id))
        for (original in originalRows.filter { it.id != apo.id }) assertEquals(original, db.whoopDao().labMarkerById(original.id))
        repo.upsertLabMarkers(listOf(apo))
        assertNotNull(store.current(repo, source, day, profile))
        assertTrue(repo.deleteLabMarker(apo.id))
        assertNull(store.current(repo, source, day, profile))
        assertEquals(baseline, recompute(), 0.0)
        assertEquals(1, records().size)
    }

    @Test fun rejectedWireAndDisabledConsentCannotWriteAnAcceptedEstimate() = runBlocking {
        val baseline = recompute()
        val request = request()
        assertNull(MetabolicEstimate.validate(wire(request).put("weights", 0.5).toString(), request, originalRows))
        assertFalse(store.save(accepted(), source, emptyList(), emptyList()))
        assertTrue(records().isEmpty())
        assertEquals(baseline, recompute(), 0.0)
        assertOriginalRowsUnchanged()
    }

    @Test fun identicalRequestsCannotRewriteThePreviouslyAcceptedAnswerOrPriorOutputAudit() = runBlocking {
        recompute()
        assertTrue(activate(accepted(2.0)))
        val audit = records().single().readBytes()
        assertFalse(store.save(accepted(3.0), source, emptyList(), emptyList()))
        assertArrayEquals(audit, records().single().readBytes())
        assertEquals(2.0, store.current(repo, source, day, profile)!!.deltaYears, 0.0)
        assertEquals(37.34594594594594, recompute(), 1e-10)
        assertOriginalRowsUnchanged()
    }

    private fun configureServiceProfile() {
        ProfileStore(context.getSharedPreferences(ProfileStore.PREFS, Context.MODE_PRIVATE)).apply {
            setAge(40)
            sex = "male"
        }
    }

    @Test fun disabledReviewReplacesAStaleAiScoreAndReportsTheActualLocalResult() = runBlocking {
        configureServiceProfile()
        val baseline = recompute()
        assertTrue(activate())
        assertNotEquals(baseline, recompute(), 1e-10)
        val audit = records().single().readBytes()
        store.setEnabled(false)

        val result = MetabolicReviewService(context, repo, source).review()

        assertTrue(result.saved)
        assertTrue(result.message.contains("recalculated from current local inputs"))
        assertEquals(baseline, score(), 1e-10)
        assertEquals(com.noop.analytics.FitnessAgeEngine.ALGORITHM_VERSION,
            db.whoopDao().scoreInputDetails(computed, day, "fitness_age")!!.algorithmVersion)
        assertArrayEquals(audit, records().single().readBytes())
        assertOriginalRowsUnchanged()
    }

    @Test fun unavailableFallbackHistoryReportsThatThePreviouslySavedScoreRemains() = runBlocking {
        configureServiceProfile()
        recompute()
        assertTrue(activate())
        val previousAiScore = recompute()
        val audit = records().single().readBytes()
        db.whoopDao().deleteDailyMetricsInRange(source, anchor.minusDays(6).toString(), day)
        store.setEnabled(false)

        val result = MetabolicReviewService(context, repo, source).review()

        assertFalse(result.saved)
        assertTrue(result.message.contains("No replacement Fitness Age was saved"))
        assertTrue(result.message.contains("Existing saved scores remain"))
        assertEquals(previousAiScore, score(), 0.0)
        assertEquals(MetabolicFitnessAge.ALGORITHM_VERSION,
            db.whoopDao().scoreInputDetails(computed, day, "fitness_age")!!.algorithmVersion)
        assertArrayEquals(audit, records().single().readBytes())
        assertOriginalRowsUnchanged()
    }

    @Test fun changingTheActiveSourceCannotRefreshOrRewriteThePreviousSourcesScore() = runBlocking {
        configureServiceProfile()
        recompute()
        assertTrue(activate())
        val previousAiScore = recompute()
        val audit = records().single().readBytes()
        store.setEnabled(false)

        val result = MetabolicReviewService(context, repo, source) { "another-device" }.review()

        assertFalse(result.saved)
        assertTrue(result.message.contains("active device changed"))
        assertEquals(previousAiScore, score(), 0.0)
        assertArrayEquals(audit, records().single().readBytes())
        assertOriginalRowsUnchanged()
    }
}

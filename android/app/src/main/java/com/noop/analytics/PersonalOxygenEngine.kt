package com.noop.analytics

import android.content.Context
import com.noop.data.DailyMetric
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Experimental personal oxygen estimator.
 *
 * Zepp SpO2 from Health Connect is used only for calibration.
 * After calibration, prediction uses THOOP/WHOOP daily metrics.
 *
 * This is a wellness estimate, not a medical measurement.
 */
object PersonalOxygenEngine {

    private const val PREFS_NAME = "thoop.personalOxygen"
    private const val MODEL_KEY = "model.v1"

    private const val MIN_TRAINING_DAYS = 7
    private const val MIN_PREDICTION_FEATURES = 3
    private const val FEATURE_COUNT = 6

    private const val LEARNING_RATE = 0.015
    private const val RIDGE_LAMBDA = 0.08
    private const val TRAINING_STEPS = 2500

    private const val MIN_OUTPUT = 85.0
    private const val MAX_OUTPUT = 100.0

    data class TrainingPoint(
        val day: String,
        val measuredSpo2: Double,
        val daily: DailyMetric,
    )

    data class Model(
        val trainedAtEpochS: Long,
        val trainingDays: Int,
        val targetMean: Double,
        val featureMeans: List<Double>,
        val featureStdDevs: List<Double>,
        val weights: List<Double>,
        val bias: Double,
        val meanAbsoluteError: Double,
    )

    enum class Confidence {
        INSUFFICIENT,
        LOW,
        MODERATE,
        HIGH,
    }

    data class Estimate(
        val estimatedSpo2: Double?,
        val confidence: Confidence,
        val stabilityScore: Int?,
        val availableFeatures: Int,
        val trainingDays: Int,
        val modelError: Double?,
        val source: String,
    )

    data class TrainingResult(
        val model: Model?,
        val acceptedDays: Int,
        val rejectedDays: Int,
        val message: String,
    )

    fun trainAndSave(
        context: Context,
        points: List<TrainingPoint>,
    ): TrainingResult {
        val accepted = points
            .asSequence()
            .filter { it.measuredSpo2 in MIN_OUTPUT..MAX_OUTPUT }
            .mapNotNull { point ->
                rawFeatures(point.daily)?.let { features ->
                    PreparedPoint(
                        day = point.day,
                        target = point.measuredSpo2,
                        features = features,
                    )
                }
            }
            .distinctBy { it.day }
            .sortedBy { it.day }
            .toList()

        val rejected = points.size - accepted.size

        if (accepted.size < MIN_TRAINING_DAYS) {
            return TrainingResult(
                model = null,
                acceptedDays = accepted.size,
                rejectedDays = rejected,
                message = "Need at least $MIN_TRAINING_DAYS paired calibration days.",
            )
        }

        val featureMeans = List(FEATURE_COUNT) { index ->
            accepted.map { it.features[index] }.average()
        }

        val featureStdDevs = List(FEATURE_COUNT) { index ->
            standardDeviation(
                values = accepted.map { it.features[index] },
                mean = featureMeans[index],
            ).coerceAtLeast(0.001)
        }

        val targetMean = accepted.map { it.target }.average()

        val normalized = accepted.map { point ->
            PreparedPoint(
                day = point.day,
                target = point.target,
                features = point.features.mapIndexed { index, value ->
                    (value - featureMeans[index]) /
                            featureStdDevs[index]
                },
            )
        }

        val weights = MutableList(FEATURE_COUNT) { 0.0 }
        var bias = targetMean

        repeat(TRAINING_STEPS) {
            val weightGradients =
                MutableList(FEATURE_COUNT) { 0.0 }

            var biasGradient = 0.0

            for (point in normalized) {
                val predicted =
                    bias + dot(weights, point.features)

                val error = predicted - point.target

                biasGradient += error

                for (index in weights.indices) {
                    weightGradients[index] +=
                        error * point.features[index]
                }
            }

            val sampleCount = normalized.size.toDouble()

            bias -= LEARNING_RATE *
                    (biasGradient / sampleCount)

            for (index in weights.indices) {
                val dataGradient =
                    weightGradients[index] / sampleCount

                val ridgeGradient =
                    RIDGE_LAMBDA * weights[index]

                weights[index] -= LEARNING_RATE *
                        (dataGradient + ridgeGradient)
            }
        }

        val errors = normalized.map { point ->
            val predicted =
                bias + dot(weights, point.features)

            abs(predicted - point.target)
        }

        val model = Model(
            trainedAtEpochS =
                System.currentTimeMillis() / 1000,
            trainingDays = accepted.size,
            targetMean = targetMean,
            featureMeans = featureMeans,
            featureStdDevs = featureStdDevs,
            weights = weights,
            bias = bias,
            meanAbsoluteError = errors.average(),
        )

        saveModel(context, model)

        return TrainingResult(
            model = model,
            acceptedDays = accepted.size,
            rejectedDays = rejected,
            message =
                "Personal oxygen model trained from ${accepted.size} days.",
        )
    }

    /**
     * Estimate oxygen using the saved model and WHOOP metrics.
     *
     * Health Connect is not read during prediction.
     */
    fun estimate(
        context: Context,
        daily: DailyMetric,
    ): Estimate {
        val model = loadModel(context) ?: return conservativeEstimate(daily)

        val availableFeatures = countFeatures(daily)

        if (availableFeatures < MIN_PREDICTION_FEATURES) {
            return conservativeEstimate(daily).copy(
                trainingDays = model.trainingDays,
                modelError = round2(model.meanAbsoluteError),
                source = "whoop4_cold_start_model_waiting_for_features",
            )
        }

        val rawFeatures = rawFeaturesWithImputation(
            daily = daily,
            means = model.featureMeans,
        )

        val normalized = rawFeatures.mapIndexed {
                index,
                value,
            ->
            (value - model.featureMeans[index]) /
                    model.featureStdDevs[index]
                        .coerceAtLeast(0.001)
        }

        val estimated = (
                model.bias +
                        dot(model.weights, normalized)
                )
            .coerceIn(MIN_OUTPUT, MAX_OUTPUT)

        val confidence = confidenceFor(
            trainingDays = model.trainingDays,
            availableFeatures = availableFeatures,
            meanAbsoluteError = model.meanAbsoluteError,
        )

        return Estimate(
            estimatedSpo2 = round1(estimated),
            confidence = confidence,
            stabilityScore = stabilityScore(
                estimatedSpo2 = estimated,
                confidence = confidence,
            ),
            availableFeatures = availableFeatures,
            trainingDays = model.trainingDays,
            modelError = round2(
                model.meanAbsoluteError
            ),
            source =
                "THOOP personal WHOOP-derived model",
        )
    }

    fun conservativeEstimate(daily: DailyMetric): Estimate {
        val features = countFeatures(daily)
        val optical = daily.spo2Red?.let { it > 0 } == true || daily.spo2Ir?.let { it > 0 } == true
        val hasData = optical || features > 0 || daily.avgSdnn != null || daily.skinTempC != null ||
            daily.steps != null || daily.activeKcalEst != null
        if (!hasData) return Estimate(null, Confidence.INSUFFICIENT, null, 0, 0, null, "no_whoop4_daily_data")
        var value = 96.0
        daily.respRateBpm?.let { value -= (it - 14.0).coerceIn(-4.0, 6.0) * 0.08 }
        daily.restingHr?.let { value -= (it - 60.0).coerceIn(-20.0, 35.0) * 0.01 }
        daily.recovery?.let { value += ((it - 50.0) / 50.0).coerceIn(-1.0, 1.0) * 0.20 }
        value = value.coerceIn(92.0, 99.0)
        return Estimate(round1(value), Confidence.LOW, stabilityScore(value, Confidence.LOW),
            features + if (optical) 1 else 0, 0, null,
            if (optical) "whoop4_cold_start_optical_available" else "whoop4_cold_start_daily")
    }

    suspend fun refreshAfterRescore(context: Context, repository: com.noop.data.WhoopRepository, activeDeviceId: String) {
        val computedIds = listOf("$activeDeviceId-noop", "my-whoop-noop").distinct()
        val byDay = linkedMapOf<String, DailyMetric>()
        for (id in computedIds + listOf(activeDeviceId, "my-whoop")) {
            for (row in repository.days(id)) byDay.putIfAbsent(row.day, row)
        }
        val measured = repository.metricSeries("health-connect", "spo2", "2020-01-01", "2099-12-31")
        val points = measured.mapNotNull { m -> byDay[m.day]?.let { TrainingPoint(m.day, m.value, it) } }
        if (points.size >= MIN_TRAINING_DAYS) trainAndSave(context, points)
        for (id in computedIds) {
            val updates = mutableListOf<DailyMetric>()
            val diag = mutableListOf<com.noop.data.MetricSeriesRow>()
            val rows = repository.days(id)
            val priorSkin = rows.mapNotNull { it.skinTempC }.sorted()
            for (row in rows) {
                var updated = row

                val result = estimate(context, row)
                result.estimatedSpo2?.let { value ->
                    if (row.spo2Pct == null) updated = updated.copy(spo2Pct = value)
                }
                val cold = result.source.startsWith("whoop4_cold_start")
                val conf = when (result.confidence) { Confidence.INSUFFICIENT -> 0.0; Confidence.LOW -> 0.25; Confidence.MODERATE -> 0.60; Confidence.HIGH -> 0.90 }

                val skinSource: String
                val skinConfidence: Double
                if (row.skinTempC != null) {
                    skinSource = "SENSOR_PIPELINE"
                    skinConfidence = 0.60
                } else {
                    // Restrict wear evidence to nightly WHOOP-derived physiology. Phone/Zepp steps and
                    // activity calories are intentionally excluded: they do not prove the strap was worn.
                    val wearEvidence = row.totalSleepMin?.let { it > 0 } == true ||
                        row.restingHr != null || row.avgHrv != null || row.respRateBpm != null
                    if (wearEvidence) {
                        val personal = priorSkin.takeIf { it.isNotEmpty() }?.let { it[it.size / 2] }
                        var estimateC = personal ?: 34.0
                        row.restingHr?.let { estimateC += (it - 60).coerceIn(-20, 30) * 0.005 }
                        row.respRateBpm?.let { estimateC += (it - 14.0).coerceIn(-4.0, 6.0) * 0.02 }
                        estimateC = kotlin.math.round(estimateC.coerceIn(32.0, 36.0) * 10.0) / 10.0
                        updated = updated.copy(skinTempC = estimateC)
                        skinSource = if (personal != null) "PERSONAL_ROLLING_MEDIAN" else "COLD_START_ESTIMATE"
                        skinConfidence = if (personal != null) 0.35 else 0.20
                    } else {
                        skinSource = "NO_DATA"
                        skinConfidence = 0.0
                    }
                }
                if (updated != row) updates += updated
                result.estimatedSpo2?.let {
                    diag += com.noop.data.MetricSeriesRow(id,row.day,"spo2_estimate_source",if(cold) 1.0 else 2.0)
                    diag += com.noop.data.MetricSeriesRow(id,row.day,"spo2_estimate_confidence",conf)
                    diag += com.noop.data.MetricSeriesRow(id,row.day,"spo2_estimate_features",result.availableFeatures.toDouble())
                    diag += com.noop.data.MetricSeriesRow(id,row.day,"spo2_estimate_training_days",result.trainingDays.toDouble())
                    result.modelError?.let { error -> diag += com.noop.data.MetricSeriesRow(id,row.day,"spo2_estimate_model_error",error) }
                    diag += com.noop.data.MetricSeriesRow(id,row.day,"spo2_raw_red_available",if(row.spo2Red != null) 1.0 else 0.0)
                    diag += com.noop.data.MetricSeriesRow(id,row.day,"spo2_raw_ir_available",if(row.spo2Ir != null) 1.0 else 0.0)
                }
                if (skinSource != "NO_DATA") {
                    val sourceCode = when (skinSource) {
                        "SENSOR_PIPELINE" -> 1.0
                        "PERSONAL_ROLLING_MEDIAN" -> 2.0
                        else -> 3.0
                    }
                    diag += com.noop.data.MetricSeriesRow(id,row.day,"skin_temp_estimate_source",sourceCode)
                    diag += com.noop.data.MetricSeriesRow(id,row.day,"skin_temp_estimate_confidence",skinConfidence)
                }
                context.getSharedPreferences("skin_temp_provenance", Context.MODE_PRIVATE).edit()
                    .putString("source.$id.${row.day}", skinSource)
                    .putFloat("confidence.$id.${row.day}", skinConfidence.toFloat())
                    .apply()
            }
            if (updates.isNotEmpty()) repository.upsertDailyMetrics(updates)
            if (diag.isNotEmpty()) repository.upsertMetricSeries(diag)
        }
    }

    fun loadModel(context: Context): Model? {
        val raw = context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            .getString(MODEL_KEY, null)
            ?: return null

        return runCatching {
            val json = JSONObject(raw)

            Model(
                trainedAtEpochS =
                    json.getLong("trainedAtEpochS"),
                trainingDays =
                    json.getInt("trainingDays"),
                targetMean =
                    json.getDouble("targetMean"),
                featureMeans = json
                    .getJSONArray("featureMeans")
                    .toDoubleList(),
                featureStdDevs = json
                    .getJSONArray("featureStdDevs")
                    .toDoubleList(),
                weights = json
                    .getJSONArray("weights")
                    .toDoubleList(),
                bias = json.getDouble("bias"),
                meanAbsoluteError = json.getDouble(
                    "meanAbsoluteError"
                ),
            )
        }.getOrNull()
    }

    fun clearModel(context: Context) {
        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            .edit()
            .remove(MODEL_KEY)
            .apply()
    }

    private fun saveModel(
        context: Context,
        model: Model,
    ) {
        val json = JSONObject()
            .put(
                "trainedAtEpochS",
                model.trainedAtEpochS,
            )
            .put(
                "trainingDays",
                model.trainingDays,
            )
            .put("targetMean", model.targetMean)
            .put(
                "featureMeans",
                JSONArray(model.featureMeans),
            )
            .put(
                "featureStdDevs",
                JSONArray(model.featureStdDevs),
            )
            .put(
                "weights",
                JSONArray(model.weights),
            )
            .put("bias", model.bias)
            .put(
                "meanAbsoluteError",
                model.meanAbsoluteError,
            )

        context
            .getSharedPreferences(
                PREFS_NAME,
                Context.MODE_PRIVATE,
            )
            .edit()
            .putString(MODEL_KEY, json.toString())
            .apply()
    }

    /**
     * Permanent model-v1 feature order:
     *
     * 0 HRV RMSSD
     * 1 Respiratory rate
     * 2 Resting heart rate
     * 3 Total sleep minutes
     * 4 Recovery
     * 5 Strain
     */
    private fun rawFeatures(
        daily: DailyMetric,
    ): List<Double>? {
        val values = listOf(
            daily.avgHrv,
            daily.respRateBpm,
            daily.restingHr?.toDouble(),
            daily.totalSleepMin,
            daily.recovery,
            daily.strain,
        )

        if (values.any { it == null }) return null

        return values.filterNotNull()
    }

    /**
     * Prediction may continue with at least three inputs.
     *
     * Missing inputs use the personal training mean, which becomes
     * zero deviation after normalization.
     */
    private fun rawFeaturesWithImputation(
        daily: DailyMetric,
        means: List<Double>,
    ): List<Double> = listOf(
        daily.avgHrv ?: means[0],
        daily.respRateBpm ?: means[1],
        daily.restingHr?.toDouble() ?: means[2],
        daily.totalSleepMin ?: means[3],
        daily.recovery ?: means[4],
        daily.strain ?: means[5],
    )

    private fun countFeatures(
        daily: DailyMetric,
    ): Int = listOf(
        daily.avgHrv,
        daily.respRateBpm,
        daily.restingHr,
        daily.totalSleepMin,
        daily.recovery,
        daily.strain,
    ).count { it != null }

    private fun confidenceFor(
        trainingDays: Int,
        availableFeatures: Int,
        meanAbsoluteError: Double,
    ): Confidence {
        if (
            trainingDays < MIN_TRAINING_DAYS ||
            availableFeatures < MIN_PREDICTION_FEATURES
        ) {
            return Confidence.INSUFFICIENT
        }

        return when {
            trainingDays >= 21 &&
                    availableFeatures >= 5 &&
                    meanAbsoluteError <= 1.0 ->
                Confidence.HIGH

            trainingDays >= 10 &&
                    availableFeatures >= 4 &&
                    meanAbsoluteError <= 1.8 ->
                Confidence.MODERATE

            else -> Confidence.LOW
        }
    }

    private fun stabilityScore(
        estimatedSpo2: Double,
        confidence: Confidence,
    ): Int {
        val oxygenComponent = (
                (estimatedSpo2 - MIN_OUTPUT) /
                        (MAX_OUTPUT - MIN_OUTPUT) * 100.0
                ).coerceIn(0.0, 100.0)

        val confidenceMultiplier = when (confidence) {
            Confidence.HIGH -> 1.0
            Confidence.MODERATE -> 0.90
            Confidence.LOW -> 0.75
            Confidence.INSUFFICIENT -> 0.50
        }

        return round(
            oxygenComponent * confidenceMultiplier
        ).toInt().coerceIn(0, 100)
    }

    private fun standardDeviation(
        values: List<Double>,
        mean: Double,
    ): Double {
        if (values.size < 2) return 1.0

        val variance = values.sumOf { value ->
            val delta = value - mean
            delta * delta
        } / (values.size - 1)

        return sqrt(variance)
    }

    private fun dot(
        left: List<Double>,
        right: List<Double>,
    ): Double = left.indices.sumOf { index ->
        left[index] * right[index]
    }

    private fun JSONArray.toDoubleList(): List<Double> =
        List(length()) { index ->
            getDouble(index)
        }

    private fun round1(value: Double): Double =
        round(value * 10.0) / 10.0

    private fun round2(value: Double): Double =
        round(value * 100.0) / 100.0

    private data class PreparedPoint(
        val day: String,
        val target: Double,
        val features: List<Double>,
    )
}
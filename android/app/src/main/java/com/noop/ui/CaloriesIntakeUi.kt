package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.data.FoodIntakeEntry
import com.noop.data.NutritionMetric
import com.noop.data.NutritionTotals
import kotlin.math.roundToInt

@Composable
internal fun CaloriesIntakeSection(
    todayEntries: List<FoodIntakeEntry>,
    allEntries: List<FoodIntakeEntry>,
    totalBurnedKcal: Double,
    onSave: (FoodIntakeEntry) -> Unit,
    onDelete: (FoodIntakeEntry) -> Unit,
) {
    val totals = remember(todayEntries) { NutritionTotals.from(todayEntries) }
    var selectedMetric by remember { mutableStateOf<NutritionMetric?>(null) }
    var editing by remember { mutableStateOf<FoodIntakeEntry?>(null) }
    var deleting by remember { mutableStateOf<FoodIntakeEntry?>(null) }

    SectionHeader("Calories Intake", overline = "TODAY")
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (todayEntries.isEmpty()) "No food logged today" else "${totals.caloriesKcal.roundToInt()} kcal consumed",
                style = NoopType.title2,
                color = Palette.textPrimary,
                modifier = Modifier.clickable(enabled = todayEntries.isNotEmpty()) {
                    selectedMetric = NutritionMetric.CALORIES
                },
            )
            if (todayEntries.isNotEmpty()) Text(
                "Total burned ${totalBurnedKcal.roundToInt()} kcal · Difference ${(totals.caloriesKcal - totalBurnedKcal).roundToInt()} kcal",
                style = NoopType.footnote,
                color = Palette.textSecondary,
            )
        }
    }

    SectionHeader("Today's Nutrition Breakdown")
    NoopCard {
        Column {
            NutritionMetric.entries.drop(1).forEachIndexed { index, metric ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(enabled = todayEntries.isNotEmpty()) {
                        selectedMetric = metric
                    }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(metric.label, style = NoopType.body, modifier = Modifier.weight(1f))
                    Text(
                        if (todayEntries.isEmpty()) "No data" else "${metric.value(totals).roundToInt()} ${metric.unit}  ›",
                        style = NoopType.bodyNumber,
                        color = Palette.textSecondary,
                    )
                }
                if (index < 3) HorizontalDivider(color = Palette.hairline)
            }
        }
    }

    SectionHeader("AI Suggestion")
    NoopCard {
        Text(nutritionSuggestion(todayEntries, totals, totalBurnedKcal), style = NoopType.body, color = Palette.textSecondary)
    }

    SectionHeader("Food Log")
    NoopCard {
        if (todayEntries.isEmpty()) {
            Text("Food saved through Coach will appear here.", style = NoopType.body, color = Palette.textSecondary)
        } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            todayEntries.forEachIndexed { index, entry ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.foodName, style = NoopType.body)
                            entry.servingDescription?.takeIf(String::isNotBlank)?.let {
                                Text(it, style = NoopType.footnote, color = Palette.textSecondary)
                            }
                        }
                        Text("≈${entry.caloriesKcal.roundToInt()} kcal", style = NoopType.bodyNumber, color = Palette.metricAmber)
                    }
                    Text(
                        "Protein ${entry.proteinGrams?.roundToInt() ?: 0} g · Carbs ${entry.carbohydrateGrams?.roundToInt() ?: 0} g · Fat ${entry.fatGrams?.roundToInt() ?: 0} g · Fiber ${entry.fiberGrams?.roundToInt() ?: 0} g",
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("EDIT", modifier = Modifier.clickable { editing = entry }, style = NoopType.footnote, color = Palette.accent)
                        Text("DELETE", modifier = Modifier.clickable { deleting = entry }, style = NoopType.footnote, color = Palette.metricRose)
                    }
                }
                if (index < todayEntries.lastIndex) HorizontalDivider(color = Palette.hairline)
            }
        }
    }

    SectionHeader("Nutrition Trend", overline = "7D")
    NutritionTrendCard(allEntries)


    selectedMetric?.let { NutritionHistoryDialog(it, allEntries) { selectedMetric = null } }
    editing?.let { entry -> FoodEditDialog(entry, { editing = null }) { editing = null; onSave(it) } }
    deleting?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete food entry?") },
            text = { Text(entry.foodName) },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(entry) }) { Text("DELETE") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("CANCEL") } },
        )
    }
}

@Composable
private fun NutritionTrendCard(entries: List<FoodIntakeEntry>) {
    val points = remember(entries) {
        entries.groupBy { it.localDay }
            .toSortedMap()
            .entries
            .toList()
            .takeLast(7)
            .map { (_, dayEntries) -> NutritionTotals.from(dayEntries).caloriesKcal }
    }
    NoopCard {
        if (points.isEmpty()) Text(
            "Nutrition history will appear after food is saved through Coach.",
            style = NoopType.body, color = Palette.textSecondary,
        ) else {
            val max = points.maxOrNull()?.coerceAtLeast(1.0) ?: 1.0
            Canvas(modifier = Modifier.fillMaxWidth().height(150.dp)) {
                val slot = size.width / points.size.coerceAtLeast(1)
                points.forEachIndexed { index, kcal ->
                    val barHeight = (kcal / max).toFloat() * size.height
                    drawLine(
                        color = Color(0xFFFFB547),
                        start = Offset(slot * index + slot / 2f, size.height),
                        end = Offset(slot * index + slot / 2f, size.height - barHeight),
                        strokeWidth = (slot * 0.42f).coerceAtLeast(5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NutritionHistoryDialog(metric: NutritionMetric, entries: List<FoodIntakeEntry>, onDismiss: () -> Unit) {
    val daily = remember(entries, metric) {
        entries.groupBy { it.localDay }.toSortedMap(compareByDescending { it }).entries
            .map { (day, rows) -> day to NutritionTotals.from(rows) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${metric.label} · Daily History") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (daily.isEmpty()) Text("No history yet.")
                daily.take(14).forEach { (day, totals) ->
                    Text("$day   ${metric.value(totals).roundToInt()} ${metric.unit}", style = NoopType.body)
                    entries.filter { it.localDay == day && metric.value(it) > 0.0 }.take(4).forEach {
                        Text("  ${it.foodName}: ≈${metric.value(it).roundToInt()} ${metric.unit}", style = NoopType.footnote, color = Palette.textSecondary)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE") } },
    )
}

@Composable
private fun FoodEditDialog(entry: FoodIntakeEntry, onDismiss: () -> Unit, onSave: (FoodIntakeEntry) -> Unit) {
    var name by remember(entry.id) { mutableStateOf(entry.foodName) }
    var calories by remember(entry.id) { mutableStateOf(entry.caloriesKcal.toString()) }
    var protein by remember(entry.id) { mutableStateOf(entry.proteinGrams?.toString().orEmpty()) }
    var carbs by remember(entry.id) { mutableStateOf(entry.carbohydrateGrams?.toString().orEmpty()) }
    var fat by remember(entry.id) { mutableStateOf(entry.fatGrams?.toString().orEmpty()) }
    var fiber by remember(entry.id) { mutableStateOf(entry.fiberGrams?.toString().orEmpty()) }
    val numeric = KeyboardOptions(keyboardType = KeyboardType.Decimal)
    fun clean(value: String) = value.filter { it.isDigit() || it == '.' }.take(8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit food entry") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(name, { name = it.take(120) }, label = { Text("Food") }, singleLine = true)
            OutlinedTextField(calories, { calories = clean(it) }, label = { Text("Calories kcal") }, keyboardOptions = numeric, singleLine = true)
            OutlinedTextField(protein, { protein = clean(it) }, label = { Text("Protein g") }, keyboardOptions = numeric, singleLine = true)
            OutlinedTextField(carbs, { carbs = clean(it) }, label = { Text("Carbohydrates g") }, keyboardOptions = numeric, singleLine = true)
            OutlinedTextField(fat, { fat = clean(it) }, label = { Text("Fat g") }, keyboardOptions = numeric, singleLine = true)
            OutlinedTextField(fiber, { fiber = clean(it) }, label = { Text("Fiber g") }, keyboardOptions = numeric, singleLine = true)
        } },
        confirmButton = { TextButton(onClick = {
            val kcal = calories.toDoubleOrNull()
            if (name.isNotBlank() && kcal != null && kcal >= 0.0) onSave(entry.copy(
                foodName = name.trim(), caloriesKcal = kcal,
                proteinGrams = protein.toDoubleOrNull(), carbohydrateGrams = carbs.toDoubleOrNull(),
                fatGrams = fat.toDoubleOrNull(), fiberGrams = fiber.toDoubleOrNull(),
                source = if (entry.source.endsWith("-edited")) entry.source else "${entry.source}-edited",
                updatedAt = System.currentTimeMillis() / 1000L,
            ))
        }) { Text("SAVE") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("CANCEL") } },
    )
}

internal fun nutritionSuggestion(
    entries: List<FoodIntakeEntry>, totals: NutritionTotals, totalBurnedKcal: Double,
): String {
    if (entries.isEmpty()) return "Log food through Coach to receive a daily nutrition suggestion."
    val parts = mutableListOf<String>()
    if (totals.fiberGrams < 10.0 && totals.caloriesKcal >= 500.0) parts +=
        "Fiber appears relatively low across the foods logged today. Consider adding vegetables, fruit, beans, or another fiber-rich food."
    if (totals.carbohydrateGrams > 0.0 && totals.fiberGrams < totals.carbohydrateGrams / 20.0) parts +=
        "Today's logged foods are weighted toward carbohydrates compared with fiber."
    if (totalBurnedKcal > 0.0 && totals.caloriesKcal > totalBurnedKcal) parts +=
        "Consumed calories currently exceed THOOP Total Calories for today; both values can still change before the day ends."
    return parts.joinToString(" ").ifBlank {
        "No obvious imbalance is visible from the available entries. Add more foods through Coach for a more complete suggestion."
    }
}

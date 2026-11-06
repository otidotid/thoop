package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.data.FoodIntakeEntry
import com.noop.data.NutritionMetric
import com.noop.data.NutritionTotals
import kotlin.math.roundToInt
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
                "Total burned ${totalBurnedKcal.roundToInt()} kcal | Difference ${(totals.caloriesKcal - totalBurnedKcal).roundToInt()} kcal",
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
                        if (todayEntries.isEmpty()) "No data" else "${metric.value(totals).roundToInt()} ${metric.unit}  >",
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

    SectionHeader("Nutrition Trend", overline = "7D")
    var selectedDay by remember { mutableStateOf<String?>(null) }
    NutritionTrendCard(allEntries) { selectedDay = it }
    selectedDay?.let { day ->
        NutritionDayDialog(
            day = day, entries = allEntries.filter { it.localDay == day },
            onDismiss = { selectedDay = null }, onEdit = { editing = it }, onDelete = { deleting = it },
        )
    }

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
private fun NutritionTrendCard(entries: List<FoodIntakeEntry>, onSelectDay: (String) -> Unit) {
    val days = remember(entries) {
        val today = LocalDate.now()
        (6 downTo 0).map { today.minusDays(it.toLong()) }.map { day ->
            day to entries.filter { it.localDay == day.toString() }.sumOf { it.caloriesKcal }
        }
    }
    val max = days.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(
                "Tap a date to view its Food Log. Editing is available for today and the previous 6 days.",
                style = NoopType.footnote,
                color = Palette.textSecondary,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.Bottom,
            ) {
                days.forEach { (day, kcal) ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onSelectDay(day.toString()) }
                            .padding(top = 4.dp, bottom = 2.dp),
                    ) {
                        Canvas(Modifier.width(22.dp).height(104.dp)) {
                            val h = ((kcal / max) * size.height).toFloat()
                            drawRect(
                                Color(0xFFFFB547),
                                topLeft = Offset(0f, size.height - h),
                                size = androidx.compose.ui.geometry.Size(size.width, h),
                            )
                        }
                        Text(
                            day.format(DateTimeFormatter.ofPattern("d MMM")),
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                            maxLines = 1,
                        )
                        Text(
                            kcal.roundToInt().toString(),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NutritionDayDialog(
    day: String,
    entries: List<FoodIntakeEntry>,
    onDismiss: () -> Unit,
    onEdit: (FoodIntakeEntry) -> Unit,
    onDelete: (FoodIntakeEntry) -> Unit,
) {
    val editable = com.noop.data.FoodLogEditWindow.isEditable(day)
    val totals = remember(entries) { NutritionTotals.from(entries) }
    val selectedDate = remember(day) { runCatching { LocalDate.parse(day) }.getOrNull() }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()
    val dateTitle = selectedDate?.format(DateTimeFormatter.ofPattern("d MMMM yyyy")) ?: day
    val dateSubtitle = selectedDate
        ?.dayOfWeek
        ?.getDisplayName(TextStyle.FULL, Locale.getDefault())
        .orEmpty()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(dateTitle, style = NoopType.title2, color = Palette.textPrimary)
                    if (dateSubtitle.isNotBlank()) {
                        Text(dateSubtitle, style = NoopType.footnote, color = Palette.textSecondary)
                    }
                    Text(
                        if (editable) "Food Log" else "Historical Food Log - read only",
                        style = NoopType.footnote,
                        color = if (editable) Palette.accent else Palette.textTertiary,
                    )
                }
                TextButton(onClick = onDismiss) { Text("CLOSE") }
            }

            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("TOTAL INTAKE", style = NoopType.footnote, color = Palette.textSecondary)
                    Text(
                        "${totals.caloriesKcal.roundToInt()} kcal",
                        style = NoopType.title2,
                        color = Palette.metricAmber,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NutritionSummaryItem("Protein", totals.proteinGrams, "g", Modifier.weight(1f))
                        NutritionSummaryItem("Carbs", totals.carbohydrateGrams, "g", Modifier.weight(1f))
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        NutritionSummaryItem("Fat", totals.fatGrams, "g", Modifier.weight(1f))
                        NutritionSummaryItem("Fiber", totals.fiberGrams, "g", Modifier.weight(1f))
                    }
                }
            }

            if (entries.isEmpty()) {
                NoopCard {
                    Text(
                        "No food logged for this date.",
                        style = NoopType.body,
                        color = Palette.textSecondary,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                }
            } else {
                entries.groupBy { it.mealType }.forEach { (mealType, rows) ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            mealType.replace('_', ' ').uppercase(),
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                        )
                        NoopCard {
                            Column {
                                rows.forEachIndexed { index, entry ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                entry.foodName,
                                                style = NoopType.body,
                                                color = Palette.textPrimary,
                                                maxLines = 2,
                                            )
                                            entry.servingDescription
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { serving ->
                                                    Text(
                                                        serving,
                                                        style = NoopType.footnote,
                                                        color = Palette.textTertiary,
                                                        maxLines = 1,
                                                    )
                                                }
                                            if (editable) {
                                                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                                    Text(
                                                        "EDIT",
                                                        modifier = Modifier.clickable { onEdit(entry) },
                                                        style = NoopType.footnote,
                                                        color = Palette.accent,
                                                    )
                                                    Text(
                                                        "DELETE",
                                                        modifier = Modifier.clickable { onDelete(entry) },
                                                        style = NoopType.footnote,
                                                        color = Palette.metricRose,
                                                    )
                                                }
                                            }
                                        }
                                        Spacer(Modifier.width(14.dp))
                                        Text(
                                            "~${entry.caloriesKcal.roundToInt()} kcal",
                                            style = NoopType.bodyNumber,
                                            color = Palette.metricAmber,
                                        )
                                    }
                                    if (index < rows.lastIndex) HorizontalDivider(color = Palette.hairline)
                                }
                            }
                        }
                    }
                }
            }

            if (!editable) {
                Text(
                    "Food Log editing is available only for today and the previous 6 days.",
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun NutritionSummaryItem(
    label: String,
    value: Double,
    unit: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = NoopType.footnote, color = Palette.textSecondary)
        Text("${value.roundToInt()} $unit", style = NoopType.headline, color = Palette.textPrimary)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NutritionHistoryDialog(
    metric: NutritionMetric,
    entries: List<FoodIntakeEntry>,
    onDismiss: () -> Unit,
) {
    val today = LocalDate.now()
    val historyDays = remember(entries, metric) {
        (0L..13L).map { today.minusDays(it) }
    }
    var selectedDay by remember(metric) { mutableStateOf(today) }
    val selectedEntries = remember(entries, selectedDay) {
        entries.filter { it.localDay == selectedDay.toString() }
    }
    val selectedTotals = remember(selectedEntries) { NutritionTotals.from(selectedEntries) }
    val todayTotals = remember(entries, today) {
        NutritionTotals.from(entries.filter { it.localDay == today.toString() })
    }
    val chartDays = remember(entries, metric, today) {
        (6L downTo 0L).map { offset ->
            val day = today.minusDays(offset)
            val totals = NutritionTotals.from(entries.filter { it.localDay == day.toString() })
            day to metric.value(totals)
        }
    }
    val sevenDayAverage = remember(chartDays) {
        chartDays.map { it.second }.average().takeIf { !it.isNaN() } ?: 0.0
    }
    val accent = when (metric) {
        NutritionMetric.CALORIES -> Palette.metricAmber
        NutritionMetric.PROTEIN -> Palette.metricRose
        NutritionMetric.CARBOHYDRATES -> Palette.accent
        NutritionMetric.FAT -> Color(0xFFB69CFF)
        NutritionMetric.FIBER -> Color(0xFF58D6A3)
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(metric.label, style = NoopType.title2, color = Palette.textPrimary)
                    Text(
                        "${metric.value(todayTotals).roundToInt()} ${metric.unit} today",
                        style = NoopType.headline,
                        color = accent,
                    )
                    Text(
                        "7-day average ${sevenDayAverage.roundToInt()} ${metric.unit}",
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                }
                TextButton(onClick = onDismiss) { Text("CLOSE") }
            }

            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("LAST 7 DAYS", style = NoopType.footnote, color = Palette.textSecondary)
                    val chartMax = chartDays.maxOfOrNull { it.second }?.coerceAtLeast(1.0) ?: 1.0
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        chartDays.forEach { (day, value) ->
                            val selected = day == selectedDay
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable { selectedDay = day }
                                    .padding(horizontal = 2.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(5.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(26.dp)
                                        .height(104.dp),
                                    contentAlignment = Alignment.BottomCenter,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(104.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .padding(top = 2.dp),
                                    )
                                    Box(
                                        modifier = Modifier
                                            .width(if (selected) 22.dp else 18.dp)
                                            .height(((value / chartMax) * 96.0).coerceAtLeast(if (value > 0) 6.0 else 2.0).dp)
                                            .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp))
                                            .clickable { selectedDay = day },
                                    )
                                    Canvas(Modifier.width(if (selected) 22.dp else 18.dp).height(((value / chartMax) * 96.0).coerceAtLeast(if (value > 0) 6.0 else 2.0).dp)) {
                                        drawRoundRect(
                                            color = if (selected) accent else accent.copy(alpha = 0.40f),
                                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f),
                                        )
                                    }
                                }
                                Text(
                                    day.dayOfMonth.toString(),
                                    style = NoopType.footnote,
                                    color = if (selected) accent else Palette.textSecondary,
                                )
                                Text(
                                    value.roundToInt().toString(),
                                    style = NoopType.footnote,
                                    color = Palette.textTertiary,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            nutritionHistoryDayLabel(selectedDay, today),
                            style = NoopType.headline,
                            color = Palette.textPrimary,
                        )
                        Text(
                            if (selectedEntries.isEmpty()) "No food entries" else "${selectedEntries.size} food entries",
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                        )
                    }
                    Text(
                        "${metric.value(selectedTotals).roundToInt()} ${metric.unit}",
                        style = NoopType.title2,
                        color = accent,
                    )
                }

                NoopCard {
                    Column {
                        if (selectedEntries.isEmpty()) {
                            Text(
                                "No ${metric.label.lowercase()} data for this date.",
                                style = NoopType.body,
                                color = Palette.textSecondary,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        } else {
                            selectedEntries.forEachIndexed { index, entry ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            entry.foodName,
                                            style = NoopType.body,
                                            color = Palette.textPrimary,
                                            maxLines = 2,
                                        )
                                        Text(
                                            entry.mealType.replace('_', ' ').replaceFirstChar { it.uppercase() },
                                            style = NoopType.footnote,
                                            color = Palette.textTertiary,
                                            maxLines = 1,
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Text(
                                        "${metric.value(entry).roundToInt()} ${metric.unit}",
                                        style = NoopType.bodyNumber,
                                        color = accent,
                                    )
                                }
                                if (index < selectedEntries.lastIndex) {
                                    HorizontalDivider(color = Palette.hairline)
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = {
                        val older = selectedDay.minusDays(1)
                        if (older in historyDays) selectedDay = older
                    },
                    enabled = selectedDay > historyDays.last(),
                ) { Text("< PREVIOUS") }
                TextButton(
                    onClick = {
                        val newer = selectedDay.plusDays(1)
                        if (newer <= today) selectedDay = newer
                    },
                    enabled = selectedDay < today,
                ) { Text("NEXT >") }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun nutritionHistoryDayLabel(day: LocalDate, today: LocalDate): String {
    val prefix = when (day) {
        today -> "TODAY"
        today.minusDays(1) -> "YESTERDAY"
        else -> day.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
    }
    return "$prefix, ${day.format(DateTimeFormatter.ofPattern("d MMM"))}"
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

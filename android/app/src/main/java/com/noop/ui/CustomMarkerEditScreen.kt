package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.analytics.CustomLabAgePolicy
import com.noop.data.LabMarkerRow
import java.time.LocalDate
import java.time.ZoneId

@Composable
internal fun CustomMarkerEditScreen(
    row: LabMarkerRow,
    onDismiss: () -> Unit,
    onSave: (LabMarkerRow) -> Unit,
) {
    val fallbackName = row.markerKey.removePrefix("custom_").replace('_', ' ')
    var name by remember(row.id) { mutableStateOf(row.displayName ?: fallbackName) }
    var value by remember(row.id) { mutableStateOf(row.value?.toString() ?: row.valueText.orEmpty()) }
    var unit by remember(row.id) { mutableStateOf(row.unit) }
    var day by remember(row.id) { mutableStateOf(row.day) }
    var note by remember(row.id) { mutableStateOf(row.note.orEmpty()) }
    var reference by remember(row.id) { mutableStateOf(row.referenceText.orEmpty()) }
    var condition by remember(row.id) { mutableStateOf(row.sampleCondition ?: "unknown") }
    var hoursAfterMeal by remember(row.id) { mutableStateOf(row.hoursSinceMeal?.toString().orEmpty()) }

    val parsedValue = value.trim().toDoubleOrNull()
    val parsedDay = runCatching { LocalDate.parse(day.trim()) }.getOrNull()
    val parsedHours = hoursAfterMeal.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    val validHours = parsedHours == null || parsedHours in 0.0..48.0
    val valid = name.isNotBlank() && unit.isNotBlank() && parsedValue != null && parsedDay != null && validHours

    NoopBottomSheet(onDismiss = onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Edit custom marker", style = NoopType.title2, color = Palette.textPrimary)
            Text("Correct the original entry. Saving updates this reading instead of creating a duplicate.", style = NoopType.subhead, color = Palette.textSecondary)

            OutlinedTextField(name, { name = it }, label = { Text("Marker title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value, { value = it }, label = { Text("Value") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.weight(1f))
                OutlinedTextField(unit, { unit = it }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(day, { day = it }, label = { Text("Date") }, supportingText = { Text("YYYY-MM-DD") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Test condition", style = NoopType.caption, color = Palette.textSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("unknown" to "Unknown", "fasting" to "Fasting", "non_fasting" to "Non-fasting", "post_meal" to "Post-meal").forEach { (key, label) ->
                    FilterChip(selected = condition == key, onClick = { condition = key }, label = { Text(label) })
                }
            }
            if (condition == "non_fasting" || condition == "post_meal") {
                OutlinedTextField(hoursAfterMeal, { hoursAfterMeal = it }, label = { Text("Hours since meal (optional)") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            }
            OutlinedTextField(reference, { reference = it }, label = { Text("Reference range (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth())

            PrimaryActionButton("Save changes", androidx.compose.material.icons.Icons.Filled.Check, enabled = valid) {
                if (!valid) return@PrimaryActionButton
                val epoch = parsedDay!!.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()
                val key = MarkerUnits.slug(name.trim())
                val isGlucose = name.contains("glucose", ignoreCase = true)
                val evidenceId = if (isGlucose && (condition == "non_fasting" || condition == "post_meal")) "glycemic_non_fasting" else null
                onSave(row.copy(
                    markerKey = key,
                    day = parsedDay.toString(),
                    takenAt = epoch,
                    value = parsedValue,
                    valueText = null,
                    unit = unit.trim(),
                    source = "manual_edit",
                    note = note.trim().ifEmpty { null },
                    referenceText = reference.trim().ifEmpty { null },
                    displayName = name.trim(),
                    sampleCondition = condition,
                    hoursSinceMeal = parsedHours,
                    evidenceProfileId = evidenceId,
                    evidenceVersion = evidenceId?.let { CustomLabAgePolicy.EVIDENCE_VERSION },
                ))
            }
        }
    }
}
package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.noop.ingest.HealthConnectImporter.ImportCategory

/** Manual Health Connect scope: activity only, or activity plus workouts. */
@Composable
internal fun HealthConnectCategorySelector(
    selected: Set<ImportCategory>,
    onSelectionChange: (Set<ImportCategory>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val choice = if (ImportCategory.ALL in selected) ImportCategory.ALL else ImportCategory.ACTIVITY_ONLY
    SegmentedPillControl(
        items = listOf(ImportCategory.ACTIVITY_ONLY, ImportCategory.ALL),
        selection = choice,
        label = { if (it == ImportCategory.ACTIVITY_ONLY) "Activity only" else "All" },
        onSelect = { onSelectionChange(setOf(it)) },
        modifier = modifier,
    )
}

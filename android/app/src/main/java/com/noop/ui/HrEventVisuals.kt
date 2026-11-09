package com.noop.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.HotTub
import androidx.compose.material.icons.filled.Pool
import androidx.compose.material.icons.filled.Rowing
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.SportsMartialArts
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material.icons.filled.TrackChanges
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

internal enum class HrEventKind { SLEEP, WALK, RUN, CYCLE, SWIM, STRENGTH, MIND_BODY, COLD, HEAT, TEAM, RACKET, COMBAT, HIKE, ROW, OTHER }

internal data class HrEventVisual(
    val kind: HrEventKind,
    val icon: ImageVector,
    val label: String,
    val color: Color,
    val iconRotationDegrees: Float = 0f,
)

internal fun classifyHrEvent(raw: String): HrEventKind {
    val value = raw.trim().lowercase()
    return when {
        value.contains("sleep") || value.contains("nap") -> HrEventKind.SLEEP
        value.contains("cold bath") || value.contains("ice bath") || value.contains("cold shower") || value.contains("ice") -> HrEventKind.COLD
        value.contains("warm bath") || value.contains("hot tub") || value.contains("sauna") || value.contains("steam") -> HrEventKind.HEAT
        value.contains("walk") -> HrEventKind.WALK
        value.contains("hike") || value.contains("hiking") -> HrEventKind.HIKE
        value.contains("run") || value.contains("jog") || value.contains("treadmill") -> HrEventKind.RUN
        value.contains("cycling") || value.contains("biking") || value.contains("bike") -> HrEventKind.CYCLE
        value.contains("swim") || value.contains("surf") -> HrEventKind.SWIM
        value.contains("rowing") || value.contains("paddl") -> HrEventKind.ROW
        value.contains("strength") || value.contains("weight") || value.contains("lifting") || value.contains("crossfit") || value.contains("functional") || value.contains("hiit") -> HrEventKind.STRENGTH
        value.contains("yoga") || value.contains("pilates") || value.contains("meditation") || value.contains("breath") -> HrEventKind.MIND_BODY
        value.contains("boxing") || value.contains("martial") || value.contains("judo") || value.contains("jiu") || value.contains("muay") -> HrEventKind.COMBAT
        value.contains("tennis") || value.contains("badminton") || value.contains("padel") || value.contains("squash") -> HrEventKind.RACKET
        value.contains("football") || value.contains("soccer") || value.contains("futsal") || value.contains("basketball") || value.contains("volleyball") -> HrEventKind.TEAM
        else -> HrEventKind.OTHER
    }
}

internal fun hrEventVisual(raw: String): HrEventVisual {
    val label = raw.trim().ifEmpty { "Activity" }
    return when (val kind = classifyHrEvent(label)) {
        HrEventKind.SLEEP -> HrEventVisual(kind, Icons.Filled.Bedtime, label, Color(0xFF63A9FF))
        HrEventKind.WALK -> HrEventVisual(kind, Icons.Filled.DirectionsWalk, label, Color(0xFF63D7B0))
        HrEventKind.RUN -> HrEventVisual(kind, Icons.Filled.DirectionsRun, label, Color(0xFFFF9B4A))
        HrEventKind.CYCLE -> HrEventVisual(kind, Icons.Filled.DirectionsBike, label, Color(0xFF55C9D8))
        HrEventKind.SWIM -> HrEventVisual(kind, Icons.Filled.Pool, label, Color(0xFF4BB8F0))
        HrEventKind.STRENGTH -> HrEventVisual(kind, Icons.Filled.FitnessCenter, label, Color(0xFFF2A14C), iconRotationDegrees = -45f)
        HrEventKind.MIND_BODY -> HrEventVisual(kind, Icons.Filled.SelfImprovement, label, Color(0xFFB89AF7))
        HrEventKind.COLD -> HrEventVisual(kind, Icons.Filled.AcUnit, label, Color(0xFF60D8EF))
        HrEventKind.HEAT -> HrEventVisual(kind, Icons.Filled.HotTub, label, Color(0xFFFFB45C))
        HrEventKind.TEAM -> HrEventVisual(kind, Icons.Filled.SportsSoccer, label, Color(0xFF75D39A))
        HrEventKind.RACKET -> HrEventVisual(kind, Icons.Filled.SportsTennis, label, Color(0xFFD8C75B))
        HrEventKind.COMBAT -> HrEventVisual(kind, Icons.Filled.SportsMartialArts, label, Color(0xFFE08080))
        HrEventKind.HIKE -> HrEventVisual(kind, Icons.Filled.Hiking, label, Color(0xFF83C978))
        HrEventKind.ROW -> HrEventVisual(kind, Icons.Filled.Rowing, label, Color(0xFF68B8D8))
        HrEventKind.OTHER -> HrEventVisual(kind, Icons.Filled.TrackChanges, label, Color(0xFF99A4B2))
    }
}

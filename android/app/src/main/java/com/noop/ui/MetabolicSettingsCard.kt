package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noop.NoopApplication
import com.noop.ai.MetabolicReviewService
import com.noop.data.MetabolicEstimateStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun MetabolicSettingsCard() {
    val context = LocalContext.current
    val store = remember { MetabolicEstimateStore(context) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(store.enabled()) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    fun service(): MetabolicReviewService {
        val app = context.applicationContext as NoopApplication
        return MetabolicReviewService(context.applicationContext, app.repository, app.activeDeviceId, { app.activeDeviceId })
    }
    NoopCard(padding = 14.dp, tint = Palette.chargeColor) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("AI metabolic estimate", style = NoopType.subhead, color = Palette.textPrimary)
                    Text("AI estimates metabolic impact from your latest Lab Book readings. THOOP applies its fixed 20% blend to Fitness Age. Experimental wellness estimate; original readings are preserved.",
                        style = NoopType.footnote, color = Palette.textSecondary)
                }
                Switch(checked = enabled, enabled = !busy, onCheckedChange = { value ->
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.setEnabled(value) }
                            enabled = value
                            status = if (value) "Ready to review. Exact Lab Book sharing must also be enabled." else {
                                if (service().recompute()) "AI contribution turned off. Fitness Age recalculated locally."
                                else "AI contribution turned off for future calculations. Existing saved scores remain; more history is needed for a new result."
                            }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { status = "Could not update the setting. Please try again." }
                        finally { busy = false }
                    }
                })
            }
            Text("Uses the latest reading for each marker as of the current weekly score date. A saved estimate expires after 30 days or when its readings or profile change.",
                style = NoopType.footnote, color = Palette.textTertiary)
            TextButton(modifier = Modifier.fillMaxWidth(), enabled = enabled && !busy, onClick = {
                busy = true
                status = "Reviewing labs…"
                scope.launch {
                    try { status = service().review().message }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { status = "Review unavailable. Your original readings are unchanged. Please try again." }
                    finally { busy = false }
                }
            }) { Text(if (busy) "Reviewing…" else "Review labs and update Fitness Age") }
            status?.let { Text(it, style = NoopType.footnote, color = Palette.textSecondary) }
        }
    }
}

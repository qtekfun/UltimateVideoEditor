package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.CutSpan
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.Reframe
import com.qtekfun.ultimatevideoeditor.domain.ReframePoint
import com.qtekfun.ultimatevideoeditor.domain.SilenceSettings
import kotlin.math.roundToInt

/** The silence-based auto cut sheet: its settings, the proposed cuts and which of them stay selected. */
data class AutoCutUiState(
    val open: Boolean = false,
    /** The base clip the cuts were found in. */
    val clipId: String? = null,
    val settings: SilenceSettings = SilenceSettings(),
    val analyzing: Boolean = false,
    /** Proposed cuts, in timeline frames; empty until an analysis ran (or when it found none). */
    val cuts: List<CutSpan> = emptyList(),
    /** Indices of [cuts] the user switched off. */
    val excluded: Set<Int> = emptySet(),
    /** Set after an analysis: what it found or why it could not. */
    val message: String? = null,
) {
    val chosen: List<CutSpan> get() = cuts.filterIndexed { i, _ -> i !in excluded }
}

/** The manual reframe sheet: the point of interest, the zoom, and the moments already marked. */
data class ReframeUiState(
    val open: Boolean = false,
    val clipId: String? = null,
    /** Point of interest in the picture, as fractions (0.5 is the middle). */
    val u: Double = 0.5,
    val v: Double = 0.5,
    val zoom: Double = 1.0,
    /** Moments marked so far (frames relative to the clip's start); one makes a fixed pose, several follow along. */
    val points: List<ReframePoint> = emptyList(),
    val message: String? = null,
)

data class QuickEditsUiState(
    val autoCut: AutoCutUiState = AutoCutUiState(),
    val reframe: ReframeUiState = ReframeUiState(),
)

sealed interface QuickEditIntent : EditorIntent {
    data object OpenAutoCut : QuickEditIntent
    data object CloseAutoCut : QuickEditIntent
    data class SetSilenceSettings(val settings: SilenceSettings) : QuickEditIntent
    data object FindSilences : QuickEditIntent
    data class ToggleCut(val index: Int) : QuickEditIntent
    data object ApplyAutoCut : QuickEditIntent

    data object OpenReframe : QuickEditIntent
    data object CloseReframe : QuickEditIntent
    data class SetReframe(val u: Double, val v: Double, val zoom: Double) : QuickEditIntent

    /** Marks the current point of interest at the playhead (a keyframe once there are several). */
    data object MarkReframePoint : QuickEditIntent
    data object ClearReframePoints : QuickEditIntent

    /** Writes the reframe: marked moments if any, otherwise the current point for the whole clip. */
    data object ApplyReframe : QuickEditIntent
}

/** Toolbar entry for the quick edits that work from the clip's audio or from the canvas shape. */
@Composable
internal fun QuickEditMenu(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(EditorIcons.Silence, "Quick edits: cut silences, reframe for a vertical canvas") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Cut silences in the selected clip…") },
                enabled = state.selectedClipOnBase,
                onClick = { open = false; onIntent(QuickEditIntent.OpenAutoCut) },
            )
            DropdownMenuItem(
                text = { Text("Reframe the selected clip…") },
                enabled = state.selectedClipId != null,
                onClick = { open = false; onIntent(QuickEditIntent.OpenReframe) },
            )
        }
    }
}

@Composable
internal fun QuickEditSheets(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    if (state.quickEdits.autoCut.open) AutoCutSheet(state.quickEdits.autoCut, state.fps, onIntent)
    if (state.quickEdits.reframe.open) ReframeSheet(state, onIntent)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoCutSheet(auto: AutoCutUiState, fps: FrameRate, onIntent: (EditorIntent) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(QuickEditIntent.CloseAutoCut) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Cut silences", style = MaterialTheme.typography.titleMedium)
            Text(
                "Finds the quiet stretches of the selected clip's audio and removes the ones you keep ticked. The gap closes and overlays follow, in one undo step.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val s = auto.settings
            SettingSlider("Quiet below ${s.thresholdDb.roundToInt()} dB", s.thresholdDb.toFloat(), SilenceSettings.MIN_THRESHOLD_DB.toFloat()..SilenceSettings.MAX_THRESHOLD_DB.toFloat(), "Silence level") {
                onIntent(QuickEditIntent.SetSilenceSettings(s.copy(thresholdDb = it.toDouble())))
            }
            SettingSlider("At least ${"%.1f".format(s.minSilenceSeconds)} s long", s.minSilenceSeconds.toFloat(), 0.1f..3f, "Shortest silence") {
                onIntent(QuickEditIntent.SetSilenceSettings(s.copy(minSilenceSeconds = it.toDouble())))
            }
            SettingSlider("Keep ${"%.2f".format(s.paddingSeconds)} s at each end", s.paddingSeconds.toFloat(), 0f..0.5f, "Padding") {
                onIntent(QuickEditIntent.SetSilenceSettings(s.copy(paddingSeconds = it.toDouble())))
            }
            if (auto.analyzing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onIntent(QuickEditIntent.FindSilences) }, enabled = !auto.analyzing) {
                    Text(if (auto.analyzing) "Looking…" else "Find silences")
                }
                TextButton(onClick = { onIntent(QuickEditIntent.CloseAutoCut) }) { Text("Close") }
            }
            auto.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            if (auto.cuts.isNotEmpty()) {
                val saved = auto.chosen.sumOf { it.length }
                Text(
                    "${auto.chosen.size} of ${auto.cuts.size} cuts, ${"%.1f".format(saved * fps.den.toDouble() / fps.num)} s shorter",
                    style = MaterialTheme.typography.labelLarge,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    itemsIndexed(auto.cuts) { index, cut ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = index !in auto.excluded,
                                onCheckedChange = { onIntent(QuickEditIntent.ToggleCut(index)) },
                                modifier = Modifier.semantics { contentDescription = "Cut ${index + 1}" },
                            )
                            Text(
                                "${formatTimecode(cut.startFrame, fps)} → ${formatTimecode(cut.endFrame, fps)}  (${"%.1f".format(cut.length * fps.den.toDouble() / fps.num)} s)",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Button(onClick = { onIntent(QuickEditIntent.ApplyAutoCut) }, enabled = auto.chosen.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text("Remove ${auto.chosen.size} silences")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReframeSheet(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    val r = state.quickEdits.reframe
    ModalBottomSheet(
        onDismissRequest = { onIntent(QuickEditIntent.CloseReframe) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Reframe for ${state.canvasWidth} x ${state.canvasHeight}", style = MaterialTheme.typography.titleMedium)
            Text(
                "Fills the canvas with the picture and keeps the point you choose in the middle. Move to another moment and mark again to follow it; with one mark the whole clip uses it. You choose the point, nothing is detected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSlider("Horizontal: ${(r.u * 100).roundToInt()}% from the left", r.u.toFloat(), 0f..1f, "Point of interest, horizontal") {
                onIntent(QuickEditIntent.SetReframe(it.toDouble(), r.v, r.zoom))
            }
            SettingSlider("Vertical: ${(r.v * 100).roundToInt()}% from the top", r.v.toFloat(), 0f..1f, "Point of interest, vertical") {
                onIntent(QuickEditIntent.SetReframe(r.u, it.toDouble(), r.zoom))
            }
            SettingSlider("Zoom ${"%.2f".format(r.zoom)}x", r.zoom.toFloat(), Reframe.MIN_ZOOM.toFloat()..Reframe.MAX_ZOOM.toFloat(), "Zoom") {
                onIntent(QuickEditIntent.SetReframe(r.u, r.v, it.toDouble()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onIntent(QuickEditIntent.MarkReframePoint) }) { Text("Mark at playhead") }
                TextButton(onClick = { onIntent(QuickEditIntent.ClearReframePoints) }, enabled = r.points.isNotEmpty()) { Text("Clear marks") }
            }
            Text(
                if (r.points.isEmpty()) "No marks yet: Apply uses the point above for the whole clip." else "${r.points.size} marks at ${r.points.joinToString { formatTimecode(it.frame, state.fps) }}",
                style = MaterialTheme.typography.bodyMedium,
            )
            r.message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onIntent(QuickEditIntent.ApplyReframe) }) { Text("Apply") }
                TextButton(onClick = { onIntent(QuickEditIntent.CloseReframe) }) { Text("Close") }
            }
        }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, description: String, onChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            modifier = Modifier.semantics { contentDescription = description },
        )
    }
}

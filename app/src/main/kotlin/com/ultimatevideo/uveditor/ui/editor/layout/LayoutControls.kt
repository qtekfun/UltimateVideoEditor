package com.ultimatevideo.uveditor.ui.editor.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.engine.timeline.WaveformScale
import com.ultimatevideo.uveditor.ui.editor.EditorIcons
import com.ultimatevideo.uveditor.ui.editor.ToolButton

/** What a panel is called in the layout controls. */
internal fun Panel.label(): String = if (this == Panel.TRAY) "Media tray" else "Inspector"

internal fun Dock.label(): String = when (this) {
    Dock.BOTTOM -> "Bottom"
    Dock.OVERLAY -> "Over timeline"
    Dock.LEFT -> "Left"
    Dock.RIGHT -> "Right"
}

/**
 * A collapsed side column: a narrow strip with one button that brings the panels back. [label] says which
 * panels it holds, for screen readers.
 */
@Composable
internal fun CollapsedStrip(side: Side, label: String, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxHeight().width(COLLAPSED_STRIP)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 8.dp)) {
            ToolButton(
                icon = if (side == Side.LEFT) EditorIcons.ChevronRight else EditorIcons.ChevronLeft,
                description = "Show $label",
                onClick = onExpand,
            )
        }
    }
}

/**
 * The frame around a panel docked at a side: a slim title row with the collapse button, and in customise
 * mode buttons that send the panel to another dock. The panel itself is [content], unchanged.
 */
@Composable
internal fun SidePanelFrame(
    panel: Panel,
    side: Side,
    customising: Boolean,
    sideDocksAllowed: Boolean,
    onCollapse: () -> Unit,
    onDock: (Dock) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
        ) {
            Text(panel.label(), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (customising) {
                val here = if (side == Side.LEFT) Dock.LEFT else Dock.RIGHT
                for (dock in panel.allowedDocks().filter { it != here && (!it.isSide || sideDocksAllowed) }) {
                    OutlinedButton(onClick = { onDock(dock) }, modifier = Modifier.padding(end = 4.dp)) { Text(dock.label()) }
                }
            }
            ToolButton(
                icon = if (side == Side.LEFT) EditorIcons.ChevronLeft else EditorIcons.ChevronRight,
                description = "Collapse the ${panel.label().lowercase()}",
                onClick = onCollapse,
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) { content() }
    }
}

/** The bottom tray folded away: a thin bar that shows it again. */
@Composable
internal fun CollapsedBottomBar(label: String, onExpand: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            ToolButton(icon = EditorIcons.ChevronUp, description = "Show the $label", onClick = onExpand)
        }
    }
}

/**
 * The layout sheet: presets, lane height, where each panel sits, collapse, customise mode and reset.
 * Everything here dispatches [LayoutAction]s, so it behaves the same as dragging.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LayoutSheet(controller: EditorLayoutController, onDismiss: () -> Unit) {
    // Read here, in the sheet's own scope: the editor behind it must not recompose for these.
    val state = controller.state
    val window = controller.window
    val onAction: (LayoutAction) -> Unit = { controller.dispatch(it) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Layout", style = MaterialTheme.typography.titleMedium)

            Text("Presets", style = MaterialTheme.typography.labelLarge)
            ChipRow {
                for (preset in LayoutPreset.entries) {
                    FilterChip(
                        selected = state.preset == preset,
                        onClick = { onAction(LayoutAction.ApplyPreset(preset)) },
                        label = { Text(preset.label) },
                    )
                }
            }

            Text("Track height", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolButton(
                    icon = EditorIcons.Minus,
                    description = "Shorter tracks",
                    enabled = state.laneHeight != LaneHeight.SMALL,
                ) { onAction(LayoutAction.StepLaneHeight(-1)) }
                ChipRow(modifier = Modifier.weight(1f)) {
                    for (height in LaneHeight.entries) {
                        FilterChip(
                            selected = state.laneHeight == height,
                            onClick = { onAction(LayoutAction.SetLaneHeight(height)) },
                            label = { Text(height.label) },
                        )
                    }
                }
                ToolButton(
                    icon = EditorIcons.Add,
                    description = "Taller tracks",
                    enabled = state.laneHeight != LaneHeight.LARGE,
                ) { onAction(LayoutAction.StepLaneHeight(1)) }
            }

            Text("Audio track height", style = MaterialTheme.typography.labelLarge)
            ChipRow {
                for (height in AudioLaneHeight.entries) {
                    FilterChip(
                        selected = state.audioLaneHeight == height,
                        onClick = { onAction(LayoutAction.SetAudioLaneHeight(height)) },
                        label = { Text(height.label) },
                    )
                }
            }

            Text("Waveform scale", style = MaterialTheme.typography.labelLarge)
            ChipRow {
                for (scale in WaveformScale.entries) {
                    FilterChip(
                        selected = controller.waveformScale == scale,
                        onClick = { controller.chooseWaveformScale(scale) },
                        label = { Text(scale.label) },
                    )
                }
            }

            for (panel in Panel.entries) {
                Text(panel.label(), style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    for (dock in panel.allowedDocks().filter { !it.isSide || window.sideDocksAllowed }) {
                        FilterChip(
                            selected = state.panel(panel).dock == dock,
                            onClick = { onAction(LayoutAction.SetDock(panel, dock)) },
                            label = { Text(dock.label()) },
                        )
                    }
                    FilterChip(
                        selected = state.panel(panel).collapsed,
                        onClick = { onAction(LayoutAction.SetCollapsed(panel, !state.panel(panel).collapsed)) },
                        label = { Text("Collapsed") },
                    )
                }
            }
            if (!window.sideDocksAllowed) {
                Text(
                    "Side panels need a wider window; turn the device sideways or open the app full screen.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Customise layout", style = MaterialTheme.typography.bodyLarge)
                    Text("Make the dividers bigger and show buttons to move panels.", style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.customising,
                    onCheckedChange = { onAction(LayoutAction.SetCustomising(it)) },
                    modifier = Modifier.semantics { contentDescription = "Customise layout" },
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onAction(LayoutAction.Reset) }) { Text("Reset layout") }
                Button(onClick = onDismiss) { Text("Done") }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

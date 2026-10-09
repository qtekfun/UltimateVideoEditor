package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import com.qtekfun.ultimatevideoeditor.ui.editor.described
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.engine.timeline.WaveformScale
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import com.qtekfun.ultimatevideoeditor.ui.editor.ToolButton
import com.qtekfun.ultimatevideoeditor.ui.editor.toolbar.ToolbarEditorDialog

/** What a panel is called in the layout controls. */
internal fun Panel.labelRes(): Int = if (this == Panel.TRAY) R.string.ed_2a_panel_media_tray else R.string.ed_2a_panel_inspector

internal fun Dock.labelRes(): Int = when (this) {
    Dock.BOTTOM -> R.string.ed_2a_dock_bottom
    Dock.OVERLAY -> R.string.ed_2a_dock_overlay
    Dock.LEFT -> R.string.ed_2a_dock_left
    Dock.RIGHT -> R.string.ed_2a_dock_right
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
                description = stringResource(R.string.ed_2a_show, label),
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
            Text(stringResource(panel.labelRes()), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (customising) {
                val here = if (side == Side.LEFT) Dock.LEFT else Dock.RIGHT
                for (dock in panel.allowedDocks().filter { it != here && (!it.isSide || sideDocksAllowed) }) {
                    OutlinedButton(onClick = { onDock(dock) }, modifier = Modifier.padding(end = 4.dp)) { Text(stringResource(dock.labelRes())) }
                }
            }
            ToolButton(
                icon = if (side == Side.LEFT) EditorIcons.ChevronLeft else EditorIcons.ChevronRight,
                description = stringResource(R.string.ed_2a_collapse_the, stringResource(panel.labelRes()).lowercase()),
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
            ToolButton(icon = EditorIcons.ChevronUp, description = stringResource(R.string.ed_2a_show_the, label), onClick = onExpand)
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
            Text(stringResource(R.string.ed_2a_layout), style = MaterialTheme.typography.titleMedium)

            Text(stringResource(R.string.ed_2a_presets), style = MaterialTheme.typography.labelLarge)
            ChipRow {
                for (preset in LayoutPreset.entries) {
                    FilterChip(
                        selected = state.preset == preset,
                        onClick = { onAction(LayoutAction.ApplyPreset(preset)) },
                        label = { Text(stringResource(preset.labelRes)) },
                    )
                }
            }

            Text(stringResource(R.string.ed_2a_track_height), style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolButton(
                    icon = EditorIcons.Minus,
                    description = stringResource(R.string.ed_2a_shorter_tracks),
                    enabled = state.laneHeight != LaneHeight.SMALL,
                ) { onAction(LayoutAction.StepLaneHeight(-1)) }
                ChipRow(modifier = Modifier.weight(1f)) {
                    for (height in LaneHeight.entries) {
                        FilterChip(
                            selected = state.laneHeight == height,
                            onClick = { onAction(LayoutAction.SetLaneHeight(height)) },
                            label = { Text(stringResource(height.labelRes)) },
                        )
                    }
                }
                ToolButton(
                    icon = EditorIcons.Add,
                    description = stringResource(R.string.ed_2a_taller_tracks),
                    enabled = state.laneHeight != LaneHeight.LARGE,
                ) { onAction(LayoutAction.StepLaneHeight(1)) }
            }

            Text(stringResource(R.string.ed_2a_audio_track_height), style = MaterialTheme.typography.labelLarge)
            ChipRow {
                for (height in AudioLaneHeight.entries) {
                    FilterChip(
                        selected = state.audioLaneHeight == height,
                        onClick = { onAction(LayoutAction.SetAudioLaneHeight(height)) },
                        label = { Text(stringResource(height.labelRes)) },
                    )
                }
            }

            Text(stringResource(R.string.ed_2a_waveform_scale), style = MaterialTheme.typography.labelLarge)
            ChipRow {
                for (scale in WaveformScale.entries) {
                    FilterChip(
                        selected = controller.waveformScale == scale,
                        onClick = { controller.chooseWaveformScale(scale) },
                        label = { Text(scale.label) },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ed_2a_put_video_audio_on_an), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.ed_2a_new_video_clips_come_in),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = controller.videoAudioOnTrack,
                    onCheckedChange = { controller.chooseVideoAudioOnTrack(it) },
                    modifier = Modifier.described(stringResource(R.string.ed_2a_put_video_audio_on_an)),
                )
            }

            var toolbarOpen by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ed_2a_toolbar), style = MaterialTheme.typography.labelLarge)
                    Text(stringResource(R.string.ed_2a_choose_the_order_of_the), style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = { toolbarOpen = true }) { Text(stringResource(R.string.ed_2a_edit_toolbar)) }
            }
            if (toolbarOpen) {
                ToolbarEditorDialog(
                    order = controller.toolbarOrder,
                    onChange = controller::changeToolbar,
                    onReset = controller::resetToolbar,
                    onDismiss = { toolbarOpen = false },
                )
            }

            for (panel in Panel.entries) {
                Text(stringResource(panel.labelRes()), style = MaterialTheme.typography.labelLarge)
                ChipRow {
                    for (dock in panel.allowedDocks().filter { !it.isSide || window.sideDocksAllowed }) {
                        FilterChip(
                            selected = state.panel(panel).dock == dock,
                            onClick = { onAction(LayoutAction.SetDock(panel, dock)) },
                            label = { Text(stringResource(dock.labelRes())) },
                        )
                    }
                    FilterChip(
                        selected = state.panel(panel).collapsed,
                        onClick = { onAction(LayoutAction.SetCollapsed(panel, !state.panel(panel).collapsed)) },
                        label = { Text(stringResource(R.string.ed_2a_collapsed)) },
                    )
                }
            }
            if (!window.sideDocksAllowed) {
                Text(
                    stringResource(R.string.ed_2a_side_panels_need_a_wider),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ed_2a_customise_layout), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(R.string.ed_2a_make_the_dividers_bigger_and), style = MaterialTheme.typography.bodySmall)
                }
                Switch(
                    checked = state.customising,
                    onCheckedChange = { onAction(LayoutAction.SetCustomising(it)) },
                    modifier = Modifier.described(stringResource(R.string.ed_2a_customise_layout)),
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { onAction(LayoutAction.Reset) }) { Text(stringResource(R.string.ed_2a_reset_layout)) }
                Button(onClick = onDismiss) { Text(stringResource(R.string.ed_2a_done)) }
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

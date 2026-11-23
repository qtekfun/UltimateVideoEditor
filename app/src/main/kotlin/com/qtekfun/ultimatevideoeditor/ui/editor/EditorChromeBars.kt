package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.engine.audio.PeakLevels
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.TimecodeForm
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.TopBarAction
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.TransportAction
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.resolveTopBar
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.resolveTransport

/** One button of a bar that can also be a line of its overflow menu. */
private class BarItem(val icon: ImageVector, val description: String, val enabled: Boolean, val onClick: () -> Unit)

/** The "more" button and the menu of the [items] that did not fit the bar. */
@Composable
private fun OverflowMenu(items: List<BarItem>) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(SelectionIcons.More, stringResource(R.string.ed_chrome_more_actions)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (item in items) {
                DropdownMenuItem(
                    text = { Text(item.description) },
                    leadingIcon = { Icon(item.icon, contentDescription = null) },
                    enabled = item.enabled,
                    onClick = { open = false; item.onClick() },
                )
            }
        }
    }
}

/**
 * Back, the project name and the editor's actions. What fits is decided by [resolveTopBar]: in a narrow window or with a large
 * font the least needed actions (Help, Layout, Redo) wait in a menu, and the name shrinks to an ellipsis, so nothing is cut off.
 */
@Composable
internal fun EditorTopBar(
    projectName: String,
    canUndo: Boolean,
    canRedo: Boolean,
    exportEnabled: Boolean,
    exportDescription: String,
    onBack: () -> Unit,
    onHelp: () -> Unit,
    onLayout: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val items = mapOf(
        TopBarAction.BACK to BarItem(EditorIcons.Back, stringResource(R.string.common_back), true, onBack),
        TopBarAction.HELP to BarItem(EditorIcons.Help, stringResource(R.string.ed_2a_toolbar_guide_what_every_symbol), true, onHelp),
        TopBarAction.LAYOUT to BarItem(EditorIcons.LayoutPanes, stringResource(R.string.ed_2a_layout_presets_panels_track_height), true, onLayout),
        TopBarAction.UNDO to BarItem(EditorIcons.Undo, stringResource(R.string.ed_2a_undo), canUndo, onUndo),
        TopBarAction.REDO to BarItem(EditorIcons.Redo, stringResource(R.string.ed_2a_redo), canRedo, onRedo),
        TopBarAction.EXPORT to BarItem(EditorIcons.Export, exportDescription, exportEnabled, onExport),
    )
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val plan = resolveTopBar(maxWidth.value, LocalDensity.current.fontScale)
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun button(action: TopBarAction) {
                val item = items.getValue(action)
                ToolButton(item.icon, item.description, enabled = item.enabled, onClick = item.onClick)
            }
            button(TopBarAction.BACK)
            Text(
                text = projectName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            for (action in plan.inline) if (action != TopBarAction.BACK) button(action)
            if (plan.hasOverflow) OverflowMenu(plan.overflow.map { items.getValue(it) })
        }
    }
}

/**
 * Timecode and level meter on the left, previous / play / next in the middle, frame / zoom / fit on the right. [resolveTransport]
 * decides, from the width and the font size, how many right-hand actions stay (the rest go to a menu), whether the timecode is
 * written short, and whether the row splits in two lines, so the parts never overlap or run out of the window.
 */
@Composable
internal fun EditorTransportRow(
    holder: State<EditorState>,
    isPlaying: Boolean,
    takePeaks: () -> PeakLevels,
    detailZoom: Boolean,
    onPrevious: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    onSaveFrame: () -> Unit,
    onZoomDetail: () -> Unit,
    onFit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actions = buildList {
        add(TransportAction.SAVE_FRAME)
        if (detailZoom) add(TransportAction.ZOOM_DETAIL)
        add(TransportAction.FIT)
    }
    val items = mapOf(
        TransportAction.SAVE_FRAME to BarItem(EditorIcons.FrameImage, stringResource(R.string.ed_2a_save_frame_as_image_the), true, onSaveFrame),
        TransportAction.ZOOM_DETAIL to BarItem(EditorIcons.ZoomDetail, stringResource(R.string.ed_zd_zoom_to_detail), true, onZoomDetail),
        TransportAction.FIT to BarItem(EditorIcons.Fit, stringResource(R.string.ed_2a_fit_the_whole_project), true, onFit),
    )
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val plan = resolveTransport(maxWidth.value, LocalDensity.current.fontScale, actions)
        val compact = plan.timecode == TimecodeForm.COMPACT

        @Composable fun readout() = Column {
            Timecode(holder, compact)
            LevelMeter(takePeaks, isPlaying)
        }

        @Composable fun transport() = Row(verticalAlignment = Alignment.CenterVertically) {
            ToolButton(EditorIcons.SkipPrevious, stringResource(R.string.ed_2a_previous_clip_boundary), onClick = onPrevious)
            ToolButton(
                icon = if (isPlaying) EditorIcons.Pause else EditorIcons.Play,
                description = if (isPlaying) stringResource(R.string.ed_2a_pause) else stringResource(R.string.ed_2a_play),
                onClick = onTogglePlay,
            )
            ToolButton(EditorIcons.SkipNext, stringResource(R.string.ed_2a_next_clip_boundary), onClick = onNext)
        }

        @Composable fun trailing() = Row(verticalAlignment = Alignment.CenterVertically) {
            for (action in plan.inline) {
                val item = items.getValue(action)
                ToolButton(item.icon, item.description, onClick = item.onClick)
            }
            if (plan.overflow.isNotEmpty()) OverflowMenu(plan.overflow.map { items.getValue(it) })
        }

        if (!plan.stacked) {
            // Both sides get the same share, so the three buttons stay in the middle of the window.
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { readout() }
                transport()
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { trailing() }
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    readout()
                    trailing()
                }
                transport()
            }
        }
    }
}

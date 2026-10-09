package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.AlignEdge
import com.qtekfun.ultimatevideoeditor.domain.GroupTransition

/** Icons for the selection tools, drawn from Material path data like [EditorIcons]. */
internal object SelectionIcons {
    val SelectMode = icon(
        "SelectMode",
        "M3,5h2L5,3C3.9,3 3,3.9 3,5zM3,13h2v-2L3,11v2zM7,21h2v-2L7,19v2zM3,9h2L5,7L3,7v2zM13,3h-2v2h2L13,3zM19,3v2h2c0,-1.1 " +
            "-0.9,-2 -2,-2zM5,21v-2L3,19c0,1.1 0.9,2 2,2zM3,17h2v-2L3,15v2zM9,3L7,3v2h2L9,3zM11,21h2v-2h-2v2zM19,13h2v-2h-2v2zM19,21c1.1,0 " +
            "2,-0.9 2,-2h-2v2zM19,9h2L21,7h-2v2zM19,17h2v-2h-2v2zM15,21h2v-2h-2v2zM15,5h2L17,3h-2v2zM7,17h10L17,7L7,7v10zM9,9h6v6L9,15L9,9z",
    )
    val Copy = icon(
        "Copy",
        "M16,1L4,1c-1.1,0 -2,0.9 -2,2v14h2L4,3h12L16,1zM19,5L8,5c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h11c1.1,0 2,-0.9 2,-2L21,7c0,-1.1 " +
            "-0.9,-2 -2,-2zM19,21L8,21L8,7h11v14z",
    )
    val Cut = icon(
        "Cut",
        "M9.64,7.64c0.23,-0.5 0.36,-1.05 0.36,-1.64 0,-2.21 -1.79,-4 -4,-4S2,3.79 2,6s1.79,4 4,4c0.59,0 1.14,-0.13 1.64,-0.36L10,12l-2.36,2.36C7.14," +
            "14.13 6.59,14 6,14c-2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4c0,-0.59 -0.13,-1.14 -0.36,-1.64L12,14l7,7h3v-1L9.64,7.64zM6,8c-1.1,0 -2," +
            "-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM6,20c-1.1,0 -2,-0.89 -2,-2s0.9,-2 2,-2 2,0.89 2,2 -0.9,2 -2,2zM12,12.5c-0.28,0 -0.5," +
            "-0.22 -0.5,-0.5s0.22,-0.5 0.5,-0.5 0.5,0.22 0.5,0.5 -0.22,0.5 -0.5,0.5zM19,3l-6,6 2,2 7,-7L22,3z",
    )
    val Paste = icon(
        "Paste",
        "M19,2h-4.18C14.4,0.84 13.3,0 12,0c-1.3,0 -2.4,0.84 -2.82,2L5,2c-1.1,0 -2,0.9 -2,2v16c0,1.1 0.9,2 2,2h14c1.1,0 2,-0.9 2,-2L21,4c0,-1.1 " +
            "-0.9,-2 -2,-2zM12,2c0.55,0 1,0.45 1,1s-0.45,1 -1,1 -1,-0.45 -1,-1 0.45,-1 1,-1zM19,20L5,20L5,4h2v3h10L17,4h2v16z",
    )
    val Duplicate = icon(
        "Duplicate",
        "M4,6L2,6v14c0,1.1 0.9,2 2,2h14v-2L4,20L4,6zM20,2L8,2c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2L22,4c0,-1.1 -0.9,-2 " +
            "-2,-2zM19,11h-4v4h-2v-4L9,11L9,9h4L13,5h2v4h4v2z",
    )
    val AlignLeft = icon("AlignLeft", "M15,15H3v2h12V15zM15,7H3v2h12V7zM3,13h18v-2H3V13zM3,21h18v-2H3V21zM3,3v2h18V3H3z")
    val AlignRight = icon("AlignRight", "M3,21h18v-2H3V21zM9,17h12v-2H9V17zM3,13h18v-2H3V13zM9,9h12V7H9V9zM3,3v2h18V3H3z")
    val PasteAttributes = icon(
        "PasteAttributes",
        "M18,4V3c0,-0.55 -0.45,-1 -1,-1H5c-0.55,0 -1,0.45 -1,1v4c0,0.55 0.45,1 1,1h12c0.55,0 1,-0.45 1,-1V6h1v4H9v11c0,0.55 0.45,1 1,1h2c0.55," +
            "0 1,-0.45 1,-1v-9h8V4h-3z",
    )
    val Close = icon("Close", "M19,6.41L17.59,5 12,10.59 6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 12,13.41 17.59,19 19,17.59 13.41,12z")
    val More = icon("More", "M12,8c1.1,0 2,-0.9 2,-2s-0.9,-2 -2,-2 -2,0.9 -2,2 0.9,2 2,2zM12,10c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2zM12,16c-1.1,0 -2,0.9 -2,2s0.9,2 2,2 2,-0.9 2,-2 -0.9,-2 -2,-2z")

    private fun icon(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .addPath(
                PathParser().parsePathString(pathData).toNodes(),
                pathFillType = PathFillType.NonZero,
                fill = SolidColor(Color.Black),
            )
            .build()
}

/** Toolbar toggle for select mode: highlighted while on. Taps then add and remove clips, and dragging empty space draws a marquee. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectModeButton(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    val description = if (state.selectMode) stringResource(R.string.ed_2a_select_mode_is_on_tap) else
        stringResource(R.string.ed_2a_select_several_clips_tap_them)
    Box {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(description) } },
            state = rememberTooltipState(),
        ) {
            if (state.selectMode) {
                FilledTonalIconButton(onClick = { onIntent(SelectionIntent.ToggleSelectMode) }, modifier = Modifier.size(40.dp)) {
                    Icon(SelectionIcons.SelectMode, contentDescription = description, modifier = Modifier.size(22.dp))
                }
            } else {
                IconButton(onClick = { onIntent(SelectionIntent.ToggleSelectMode) }, modifier = Modifier.size(40.dp)) {
                    Icon(SelectionIcons.SelectMode, contentDescription = description, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}

/**
 * Shown while several clips are selected or select mode is on: how many are selected and the actions that
 * apply to all of them. Group edits are one undo step each; what cannot apply is explained in a message.
 */
@Composable
internal fun SelectionBar(state: EditorState, onIntent: (EditorIntent) -> Unit, modifier: Modifier = Modifier) {
    val count = state.selection.size
    val hasClipboard = state.clipboardCount > 0
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (count == 0) stringResource(R.string.ed_2a_select_clips) else stringResource(R.string.ed_2a_selected, count),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(end = 8.dp),
        )
        ToolButton(SelectionIcons.Copy, stringResource(R.string.ed_2a_copy_the_selected_clips), enabled = count > 0) { onIntent(SelectionIntent.Copy) }
        ToolButton(SelectionIcons.Cut, stringResource(R.string.ed_2a_cut_the_selected_clips_copy), enabled = count > 0) { onIntent(SelectionIntent.Cut) }
        ToolButton(SelectionIcons.Paste, stringResource(R.string.ed_2a_paste_the_copied_clips_at), enabled = hasClipboard) { onIntent(SelectionIntent.Paste) }
        ToolButton(SelectionIcons.Duplicate, stringResource(R.string.ed_2a_duplicate_the_selected_clips_right), enabled = count > 0) { onIntent(SelectionIntent.Duplicate) }
        ToolButton(EditorIcons.Delete, stringResource(R.string.ed_2a_delete_the_selected_clips_the), enabled = count > 0) { onIntent(SelectionIntent.DeleteSelection) }
        ToolButton(
            SelectionIcons.PasteAttributes,
            stringResource(R.string.ed_2a_paste_attributes),
            enabled = count > 0 && hasClipboard,
        ) { onIntent(SelectionIntent.PasteAttributes) }
        ToolButton(SelectionIcons.AlignLeft, stringResource(R.string.ed_2a_align_the_starts_of_the), enabled = count > 1) { onIntent(SelectionIntent.Align(AlignEdge.START)) }
        ToolButton(SelectionIcons.AlignRight, stringResource(R.string.ed_2a_align_the_ends_of_the), enabled = count > 1) { onIntent(SelectionIntent.Align(AlignEdge.END)) }
        TransitionsMenu(count > 0, onIntent)
        MoreMenu(count, onIntent)
        ToolButton(SelectionIcons.Close, stringResource(R.string.ed_2a_clear_the_selection), enabled = count > 0) { onIntent(SelectionIntent.ClearSelection) }
    }
}

@Composable
private fun TransitionsMenu(enabled: Boolean, onIntent: (EditorIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(EditorIcons.Transition, stringResource(R.string.ed_2a_add_the_same_transition_to), enabled = enabled) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ed_2a_crossfade_at_each_cut)) },
                onClick = { open = false; onIntent(SelectionIntent.ApplyTransitions(GroupTransition.BETWEEN)) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.ed_2a_fade_in_and_out_head)) },
                onClick = { open = false; onIntent(SelectionIntent.ApplyTransitions(GroupTransition.HEAD_AND_TAIL)) },
            )
        }
    }
}

@Composable
private fun MoreMenu(count: Int, onIntent: (EditorIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(SelectionIcons.More, stringResource(R.string.ed_2a_more_select_a_lane_or)) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            @Composable fun item(label: String, enabled: Boolean = true, intent: EditorIntent) = DropdownMenuItem(
                text = { Text(label) },
                enabled = enabled,
                onClick = { open = false; onIntent(intent) },
            )
            item(stringResource(R.string.ed_2a_select_lane), intent = SelectionIntent.SelectLane)
            item(stringResource(R.string.ed_2a_select_from_playhead), intent = SelectionIntent.SelectFromPlayhead)
            item(stringResource(R.string.ed_2a_select_all_clips), intent = SelectionIntent.SelectAll)
            item(stringResource(R.string.ed_2a_speed_half), count > 0, SelectionIntent.SetGroupSpeed(1, 2))
            item(stringResource(R.string.ed_2a_speed_1x), count > 0, SelectionIntent.SetGroupSpeed(1, 1))
            item(stringResource(R.string.ed_2a_speed_2x), count > 0, SelectionIntent.SetGroupSpeed(2, 1))
            item(stringResource(R.string.ed_2a_volume_0), count > 0, SelectionIntent.SetGroupGain(0.0))
            item(stringResource(R.string.ed_2a_volume_minus_6), count > 0, SelectionIntent.SetGroupGain(-6.0))
            item(stringResource(R.string.ed_2a_mute), count > 0, SelectionIntent.SetGroupGain(-96.0))
            item(stringResource(R.string.ed_2a_opacity_value, 100), count > 0, SelectionIntent.SetGroupOpacity(1.0))
            item(stringResource(R.string.ed_2a_opacity_value, 50), count > 0, SelectionIntent.SetGroupOpacity(0.5))
        }
    }
}

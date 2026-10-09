package com.qtekfun.ultimatevideoeditor.ui.editor.toolbar

import com.qtekfun.ultimatevideoeditor.ui.editor.described
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import com.qtekfun.ultimatevideoeditor.ui.editor.SelectionIcons
import com.qtekfun.ultimatevideoeditor.ui.editor.ToolButton

/** The symbol the tool row draws for [this]; the settings list shows the same one. */
internal fun ToolbarItem.icon(): ImageVector = when (this) {
    ToolbarItem.IMPORT -> EditorIcons.Add
    ToolbarItem.SPLIT -> EditorIcons.Split
    ToolbarItem.DETACH_AUDIO -> EditorIcons.DetachAudio
    ToolbarItem.DELETE -> EditorIcons.Delete
    ToolbarItem.MARKER -> EditorIcons.Flag
    ToolbarItem.SELECT_MODE -> SelectionIcons.SelectMode
    ToolbarItem.CLOSE_GAP -> EditorIcons.CloseGap
    ToolbarItem.TITLE -> EditorIcons.Title
    ToolbarItem.CAPTIONS -> EditorIcons.Captions
    ToolbarItem.STICKERS -> EditorIcons.Sticker
    ToolbarItem.TEMPLATES -> EditorIcons.TextTemplate
    ToolbarItem.QUICK_EDITS -> EditorIcons.Silence
    ToolbarItem.LIBRARY -> EditorIcons.Library
    ToolbarItem.PROXY -> EditorIcons.Proxy
    ToolbarItem.MIXER -> EditorIcons.Mixer
    ToolbarItem.MULTICAM -> EditorIcons.Multicam
    ToolbarItem.SCOPES -> EditorIcons.Scopes
    ToolbarItem.TRANSITION -> EditorIcons.Transition
    ToolbarItem.ADJUST -> EditorIcons.Tune
    ToolbarItem.TRACK_CONTROLS -> EditorIcons.Layers
    ToolbarItem.CANVAS -> EditorIcons.CanvasFormat
    ToolbarItem.SAFE_ZONE -> EditorIcons.SafeZone
}

/**
 * The toolbar settings: every tool with its symbol, arrows to move it, a switch to show or hide it (hidden tools wait in the
 * "More" menu at the end of the row; Split and Delete always stay) and a button to go back to the default order.
 */
@Composable
internal fun ToolbarEditorDialog(order: ToolbarOrder, onChange: ((ToolbarOrder) -> ToolbarOrder) -> Unit, onReset: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ed_2a_toolbar)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    stringResource(R.string.ed_2a_move_the_tools_up_or),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                order.order.forEachIndexed { index, item ->
                    val hidden = order.isHidden(item)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(item.icon(), contentDescription = null, modifier = Modifier.size(22.dp))
                        Text(
                            stringResource(item.labelRes),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                        ToolButton(EditorIcons.LaneUp, stringResource(R.string.ed_2a_move_earlier, stringResource(item.labelRes)), enabled = index > 0) { onChange { it.move(item, -1) } }
                        ToolButton(EditorIcons.LaneDown, stringResource(R.string.ed_2a_move_later, stringResource(item.labelRes)), enabled = index < order.order.lastIndex) { onChange { it.move(item, 1) } }
                        Switch(
                            checked = !hidden,
                            enabled = !item.mandatory,
                            onCheckedChange = { shown -> onChange { it.withHidden(item, !shown) } },
                            modifier = Modifier.described(stringResource(R.string.ed_2a_show_in_the_bar, stringResource(item.labelRes))),
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ed_2a_done)) } },
        dismissButton = { TextButton(onClick = onReset, enabled = !order.isDefault) { Text(stringResource(R.string.ed_2a_reset_to_default)) } },
    )
}

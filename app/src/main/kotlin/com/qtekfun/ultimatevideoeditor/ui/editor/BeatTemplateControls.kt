package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.MarkerKind
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.domain.TextTemplates

/**
 * The marker button. One tap drops a marker at the playhead (the hint chip over the timeline offers Edit);
 * a long press opens the rest: previous and next marker, the beat tools and marker snapping.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MarkerMenu(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val markers = state.timeline.markers
    val beatCount = markers.count { it.kind == MarkerKind.BEAT }
    val description = "Add a marker at the playhead; long press for beats and marker tools. ${markers.size - beatCount} markers, $beatCount beats"
    Box {
        // Not a ToolButton: its tooltip also listens for a long press and would fight the menu.
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = "Add a marker at the playhead",
                    onLongClickLabel = "Open marker and beat tools",
                    onClick = { onIntent(MarkerIntent.AddAtPlayhead) },
                    onLongClick = { open = true },
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(imageVector = EditorIcons.Flag, contentDescription = null, modifier = Modifier.size(22.dp))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Previous marker") },
                enabled = markers.isNotEmpty(),
                onClick = { open = false; onIntent(MarkerIntent.SeekPrevious) },
            )
            DropdownMenuItem(
                text = { Text("Next marker") },
                enabled = markers.isNotEmpty(),
                onClick = { open = false; onIntent(MarkerIntent.SeekNext) },
            )
            DropdownMenuItem(
                text = { Text(if (state.isAnalyzingBeats) "Finding beats…" else "Find beats in the selected clip") },
                enabled = state.selectedClipId != null && !state.isAnalyzingBeats,
                onClick = { open = false; onIntent(EditorIntent.AnalyzeBeats) },
            )
            DropdownMenuItem(
                text = { Text("Cut to beat from the selected clip") },
                enabled = state.selectedClipOnBase && markers.isNotEmpty(),
                onClick = { open = false; onIntent(EditorIntent.CutToBeatFromSelected) },
            )
            DropdownMenuItem(
                text = { Text("Clear detected beats") },
                enabled = beatCount > 0,
                onClick = { open = false; onIntent(EditorIntent.ClearBeatMarkers) },
            )
            DropdownMenuItem(
                text = { Text(if (state.snapToMarkers) "Snap to markers: on ✓" else "Snap to markers: off") },
                onClick = { open = false; onIntent(EditorIntent.ToggleMarkerSnap) },
            )
        }
    }
}

/**
 * Lists the animated text templates. A template lands at the playhead with the text typed here (or
 * its own sample text when the field is empty); its text is then selected for editing in the inspector.
 * Shown as the Titles tab of the media tray.
 */
@Composable
internal fun TextTemplateChooser(
    onApply: (templateId: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
    /** Presets the user saved or imported, listed after the built-in templates. */
    userPresets: List<TextTemplate> = emptyList(),
    onApplyPreset: (TextTemplate, text: String) -> Unit = { _, _ -> },
) {
    var text by remember { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Text (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        for (template in TextTemplates.all) {
            TemplateRow(template) { onApply(template.id, text) }
        }
        if (userPresets.isNotEmpty()) {
            Text("My presets", style = MaterialTheme.typography.titleSmall)
            for (preset in userPresets) {
                TemplateRow(preset) { onApplyPreset(preset, text) }
            }
        }
    }
}

@Composable
private fun TemplateRow(template: TextTemplate, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClickLabel = "Add ${template.name}", onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(template.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            "${template.defaultSeconds.toInt()} s · \"${template.defaultText}\"",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

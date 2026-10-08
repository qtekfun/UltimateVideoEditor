package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.MarkerColor
import com.qtekfun.ultimatevideoeditor.ui.library.markerColour

/** The "Marker added · Edit" chip: a state-based hint (no snackbar queue), so it appears in the frame of the tap. */
@Composable
internal fun MarkerHintChip(hint: MarkerHint, onIntent: (EditorIntent) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        tonalElevation = 6.dp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Marker added", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { onIntent(MarkerIntent.Open(hint.markerId)) }) { Text("Edit", color = MaterialTheme.colorScheme.inversePrimary) }
        }
    }
}

/**
 * The compact marker popup: a name, a note, six colours, delete and the previous / next markers. Everything
 * shows live; closing it keeps the edits as one undo step.
 */
@Composable
internal fun MarkerPopupCard(popup: MarkerPopup, onIntent: (EditorIntent) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
        modifier = modifier.widthIn(max = 360.dp).fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { onIntent(MarkerIntent.PopupPrevious) }, enabled = popup.hasPrevious) {
                    Icon(EditorIcons.ChevronLeft, contentDescription = "Previous marker")
                }
                OutlinedTextField(
                    value = popup.name,
                    onValueChange = { onIntent(MarkerIntent.NameChanged(it)) },
                    placeholder = { Text(if (popup.isBeat) "Beat" else "Marker name") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onIntent(MarkerIntent.PopupNext) }, enabled = popup.hasNext) {
                    Icon(EditorIcons.ChevronRight, contentDescription = "Next marker")
                }
            }
            OutlinedTextField(
                value = popup.note,
                onValueChange = { onIntent(MarkerIntent.NoteChanged(it)) },
                placeholder = { Text("Note") },
                minLines = 1,
                maxLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
            Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (color in MarkerColor.entries) MarkerSwatch(color, popup.color == color, onIntent)
                }
                IconButton(onClick = { onIntent(MarkerIntent.DeleteOpen) }) {
                    Icon(EditorIcons.Delete, contentDescription = "Delete marker")
                }
                TextButton(onClick = { onIntent(MarkerIntent.Close) }) { Text("Done") }
            }
        }
    }
}

/** One colour; tapping the chosen one again clears the colour. */
@Composable
private fun MarkerSwatch(color: MarkerColor, selected: Boolean, onIntent: (EditorIntent) -> Unit) {
    val name = color.name.lowercase()
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(markerColour(color))
            .then(if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
            .clickable(role = Role.RadioButton, onClickLabel = if (selected) "Clear marker colour" else "Colour the marker $name") {
                onIntent(MarkerIntent.ColorChosen(if (selected) null else color))
            }
            .semantics { contentDescription = "Marker colour $name${if (selected) ", selected" else ""}" },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Text("✓", color = Color.White)
    }
}

package com.qtekfun.ultimatevideoeditor.ui.hub

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import com.qtekfun.ultimatevideoeditor.ui.theme.LocalPalette

/**
 * Three stacked bars in the timeline's clip colours (video, audio, title): the mark of the app. Drawn from the palette
 * so it follows AMOLED mode; decorative, so it has no description of its own.
 */
@Composable
internal fun WordmarkGlyph(size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Canvas(modifier = modifier.size(size).clearAndSetSemantics { }) {
        val bar = this.size.height * 0.26f
        val gap = (this.size.height - bar * 3) / 2f
        val radius = CornerRadius(bar / 2f, bar / 2f)
        val w = this.size.width
        // Lengths and offsets read as clips on a timeline: a long video clip, an audio clip under it, a short title.
        val rows = listOf(
            Triple(Color(palette.clipVideo), 0f, 1f),
            Triple(Color(palette.clipAudio), 0.18f, 0.82f),
            Triple(Color(palette.clipTitle), 0.08f, 0.56f),
        )
        rows.forEachIndexed { i, (colour, start, end) ->
            drawRoundRect(
                color = colour,
                topLeft = Offset(w * start, i * (bar + gap)),
                size = Size(w * (end - start), bar),
                cornerRadius = radius,
            )
        }
    }
}

@Composable
private fun Wordmark() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "ultimateVE" },
    ) {
        WordmarkGlyph()
        Text("ultimateVE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** Wordmark, search and the overflow menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HubTopBar(
    searchOpen: Boolean,
    canSearch: Boolean,
    onToggleSearch: () -> Unit,
    onImport: () -> Unit,
    onOpenAbout: () -> Unit,
    onTemplates: (() -> Unit)?,
) {
    TopAppBar(
        title = { Wordmark() },
        actions = {
            if (canSearch) {
                IconButton(onClick = onToggleSearch) {
                    Icon(
                        if (searchOpen) HubIcons.Close else HubIcons.Search,
                        contentDescription = if (searchOpen) "Close search" else "Search projects",
                    )
                }
            }
            HubOverflowMenu(onImport, onOpenAbout, onTemplates)
        },
    )
}

/** Replaces the top bar in selection mode: close, "2 selected" and select all. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SelectionTopBar(count: Int, allSelected: Boolean, onClose: () -> Unit, onSelectAll: () -> Unit) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(HubIcons.Close, contentDescription = "Leave selection mode") }
        },
        title = { Text("$count selected") },
        actions = {
            IconButton(onClick = onSelectAll, enabled = !allSelected) {
                Icon(HubIcons.SelectAll, contentDescription = "Select all projects")
            }
        },
    )
}

/** The one overflow menu of the top bar: importing a project file lives here, not on its own button. */
@Composable
private fun HubOverflowMenu(onImport: () -> Unit, onOpenAbout: () -> Unit, onTemplates: (() -> Unit)?) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(HubIcons.MoreVert, contentDescription = "More options") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (onTemplates != null) DropdownMenuItem(text = { Text("New from a template…") }, onClick = { open = false; onTemplates() })
            DropdownMenuItem(text = { Text("Import project, bundle or LumaFusion package") }, onClick = { open = false; onImport() })
            DropdownMenuItem(text = { Text("About, privacy and help") }, onClick = { open = false; onOpenAbout() })
        }
    }
}

/** Icon of the "New project" button, shared by the FAB and the empty state. */
internal val NewProjectIcon get() = EditorIcons.Add

package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.data.MediaProblem
import com.ultimatevideo.uveditor.data.MissingAsset

/** Wording for why a file is unusable, shared by the dialog and the tests. */
internal fun problemText(problem: MediaProblem): String = when (problem) {
    MediaProblem.UNREADABLE -> "File not found"
    MediaProblem.PERMISSION_LOST -> "No permission to read it"
    MediaProblem.UNSUPPORTED -> "Cannot be decoded"
}

/** Banners under the top bar: unsaved changes (an error) and unreadable media (a warning). */
@Composable
internal fun MediaBanners(state: EditorState, onImportFont: () -> Unit = {}, onIntent: (EditorIntent) -> Unit) {
    // Titles that name an imported font this device does not have are drawn in the default font.
    val missingFonts = remember(state.timeline, state.availableFonts) { state.missingFonts }
    if (missingFonts.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (missingFonts.size == 1) "1 font used by a title is not on this device (default font shown)" else "${missingFonts.size} fonts used by titles are not on this device (default font shown)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onImportFont) { Text("Import font") }
            }
        }
    }
    val saveError = state.saveError
    if (saveError != null) {
        Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Changes are not saved: $saveError",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onIntent(EditorIntent.RetrySave) }) { Text("Retry") }
            }
        }
    }
    val missing = state.missingAssets
    if (missing.isNotEmpty()) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (missing.size == 1) "1 media file is missing" else "${missing.size} media files are missing",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onIntent(EditorIntent.ShowRelink) }) { Text("Relink") }
            }
        }
    }
}

/** Lists the unreadable files; each has a button that opens the picker for its replacement. */
@Composable
internal fun RelinkDialog(missing: List<MissingAsset>, onIntent: (EditorIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(EditorIntent.HideRelink) },
        title = { Text("Missing media") },
        text = {
            Column {
                Text(
                    "These files cannot be read. Their clips are marked on the timeline and are skipped in the preview and export until you relink them.",
                    style = MaterialTheme.typography.bodySmall,
                )
                LazyColumn(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(missing, key = { it.assetId }) { asset ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(asset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                val uses = if (asset.clipCount == 1) "1 clip" else "${asset.clipCount} clips"
                                Text(
                                    "${problemText(asset.problem)} · $uses",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            TextButton(onClick = { onIntent(EditorIntent.RequestRelink(asset.assetId)) }) { Text("Relink") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onIntent(EditorIntent.HideRelink) }) { Text("Close") } },
    )
}

/** Shown when leaving was refused because the project could not be written. */
@Composable
internal fun SaveFailedDialog(message: String?, onIntent: (EditorIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(EditorIntent.RetrySave) },
        title = { Text("Changes not saved") },
        text = { Text("The project could not be saved${message?.let { ": $it" }.orEmpty()}. Leaving now would lose your latest changes.") },
        confirmButton = { TextButton(onClick = { onIntent(EditorIntent.RetrySave) }) { Text("Retry") } },
        dismissButton = { TextButton(onClick = { onIntent(EditorIntent.LeaveWithoutSaving) }) { Text("Leave without saving") } },
    )
}

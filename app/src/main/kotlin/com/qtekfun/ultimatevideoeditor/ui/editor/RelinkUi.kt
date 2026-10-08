package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.data.MediaProblem
import com.qtekfun.ultimatevideoeditor.data.MissingAsset
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.relink.FolderRelinkOutcome

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

/**
 * The missing-media dialog: the list of unreadable files (each with a Relink button, and one "Scan a folder" button for all),
 * the progress of a folder scan, or its results.
 */
@Composable
internal fun RelinkDialog(missing: List<MissingAsset>, folderRelink: FolderRelinkUi, onIntent: (EditorIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(EditorIntent.HideRelink) },
        title = { Text(if (folderRelink is FolderRelinkUi.Done) "Folder scan" else "Missing media") },
        text = {
            when (folderRelink) {
                FolderRelinkUi.Idle -> MissingList(missing, onIntent)
                is FolderRelinkUi.Running -> ScanProgress(folderRelink)
                is FolderRelinkUi.Done -> ScanResults(folderRelink.outcome, onIntent)
            }
        },
        dismissButton = when (folderRelink) {
            FolderRelinkUi.Idle -> ({ TextButton(onClick = { onIntent(EditorIntent.RequestFolderRelink) }) { Text("Scan a folder…") } })
            is FolderRelinkUi.Running -> ({ TextButton(onClick = { onIntent(EditorIntent.CancelFolderRelink) }) { Text("Cancel scan") } })
            is FolderRelinkUi.Done ->
                if (missing.isNotEmpty()) ({ TextButton(onClick = { onIntent(EditorIntent.DismissFolderRelink) }) { Text("Back to the list") } }) else null
        },
        confirmButton = { TextButton(onClick = { onIntent(EditorIntent.HideRelink) }) { Text("Close") } },
    )
}

@Composable
private fun MissingList(missing: List<MissingAsset>, onIntent: (EditorIntent) -> Unit) {
    Column {
        Text(
            "These files cannot be read. Their clips are marked on the timeline and are skipped in the preview and export until you relink them. " +
                "If you moved them together, \"Scan a folder\" finds all of them in one go.",
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
}

/** Words for the progress of a scan; shared with the tests. */
internal fun scanProgressText(progress: FolderRelinkUi.Running): String = when {
    progress.total > 0 -> "Checking the files found: ${progress.checked} of ${progress.total}"
    progress.files == 0 && progress.folders == 0 -> "Starting…"
    else -> "Scanning: ${progress.files} media files in ${progress.folders} folders"
}

@Composable
private fun ScanProgress(progress: FolderRelinkUi.Running) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(scanProgressText(progress), style = MaterialTheme.typography.bodyMedium)
        if (progress.total > 0) {
            LinearProgressIndicator(progress = { progress.checked.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            "Looking in the folder and the folders inside it. Nothing changes until the scan is done; Cancel stops it.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The headline of the results, shared with the tests. */
internal fun scanSummaryText(outcome: FolderRelinkOutcome): String = "Relinked ${outcome.relinked.size} of ${outcome.total}"

@Composable
private fun ScanResults(outcome: FolderRelinkOutcome, onIntent: (EditorIntent) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(scanSummaryText(outcome), style = MaterialTheme.typography.titleMedium)
            Text(
                "Looked at ${outcome.filesSeen} media files. Relinking is saved straight away and is not an undo step: " +
                    "to point a file somewhere else, relink it again.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (outcome.truncated) {
                Text(
                    "The folder is very large: the scan stopped early, so some files may not have been seen.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!outcome.accessKept && outcome.relinked.isNotEmpty()) {
                Text(
                    "Android would not keep access to this folder: the relinked files may be missing again after a restart.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val withWarnings = outcome.relinked.filter { it.warnings.isNotEmpty() }
        if (withWarnings.isNotEmpty()) {
            item { Text("Relinked with a note", style = MaterialTheme.typography.titleSmall) }
            items(withWarnings, key = { "w-" + it.old.id }) { r ->
                Text("${MissingMedia.nameOf(r.asset)}: ${r.warnings.joinToString(". ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (outcome.ambiguous.isNotEmpty()) {
            item { Text("Several files with the same name (${outcome.ambiguous.size})", style = MaterialTheme.typography.titleSmall) }
            items(outcome.ambiguous, key = { "a-" + it.assetId }) { item ->
                Column {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    for (candidate in item.candidates) {
                        TextButton(onClick = { onIntent(EditorIntent.RelinkFromCandidate(item.assetId, candidate.uri)) }) {
                            Text(candidate.path.joinToString("/"), maxLines = 2)
                        }
                    }
                    TextButton(onClick = { onIntent(EditorIntent.RequestRelink(item.assetId)) }) { Text("Pick another file…") }
                }
            }
        }
        if (outcome.notFound.isNotEmpty()) {
            item { Text("Not found (${outcome.notFound.size})", style = MaterialTheme.typography.titleSmall) }
            items(outcome.notFound, key = { "n-" + it.assetId }) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(item.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onIntent(EditorIntent.RequestRelink(item.assetId)) }) { Text("Relink") }
                }
            }
        }
    }
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

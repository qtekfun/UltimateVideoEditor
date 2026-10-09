package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
internal fun problemText(problem: MediaProblem): UiText = UiText.res(
    when (problem) {
        MediaProblem.UNREADABLE -> R.string.ed_2b_problem_unreadable
        MediaProblem.PERMISSION_LOST -> R.string.ed_2b_problem_permission
        MediaProblem.UNSUPPORTED -> R.string.ed_2b_problem_unsupported
    },
)

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
                    text = if (missingFonts.size == 1) stringResource(R.string.ed_2b_font_used_by_a_title) else stringResource(R.string.ed_2b_fonts_used_by_titles_are, missingFonts.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onImportFont) { Text(stringResource(R.string.ed_2b_import_font)) }
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
                    text = stringResource(R.string.ed_2b_changes_are_not_saved, saveError),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onIntent(EditorIntent.RetrySave) }) { Text(stringResource(R.string.ed_2b_retry)) }
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
                    text = if (missing.size == 1) stringResource(R.string.ed_2b_media_file_is_missing) else stringResource(R.string.ed_2b_media_files_are_missing, missing.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onIntent(EditorIntent.ShowRelink) }) { Text(stringResource(R.string.ed_2b_relink)) }
            }
        }
    }
}

/**
 * The missing-media dialog: the list of unreadable files (each with a Relink button, and one "Scan a folder" button for all),
 * the progress of a folder scan, or its results.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RelinkDialog(missing: List<MissingAsset>, folderRelink: FolderRelinkUi, onIntent: (EditorIntent) -> Unit) {
    // One session from the first scan until Close: the scan action is "Scan a folder…" before it and "Scan another folder…" after it,
    // once per screen, and it is not offered when nothing is missing any more.
    val results = folderRelink is FolderRelinkUi.Done && !(folderRelink.showList && missing.isNotEmpty())
    AlertDialog(
        onDismissRequest = { onIntent(EditorIntent.HideRelink) },
        title = { Text(if (results) stringResource(R.string.ed_2b_folder_scan) else stringResource(R.string.ed_2b_missing_media)) },
        text = {
            when {
                folderRelink is FolderRelinkUi.Running -> ScanProgress(folderRelink)
                folderRelink is FolderRelinkUi.Done && results -> ScanResults(folderRelink.outcome, onIntent)
                else -> MissingList(missing, onIntent)
            }
        },
        dismissButton = when {
            folderRelink is FolderRelinkUi.Running -> ({ TextButton(onClick = { onIntent(EditorIntent.CancelFolderRelink) }) { Text(stringResource(R.string.ed_2b_cancel_scan)) } })
            results -> null
            else -> ({ TextButton(onClick = { onIntent(EditorIntent.RequestFolderRelink) }) { Text(scanActionLabel(folderRelink).asString()) } })
        },
        confirmButton = {
            if (results) {
                ResultsButtons(missing.isNotEmpty(), onIntent)
            } else if (folderRelink is FolderRelinkUi.Running) {
                TextButton(onClick = { onIntent(EditorIntent.HideRelink) }) { Text(stringResource(R.string.common_close)) }
            } else {
                FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { onIntent(EditorIntent.RecheckMissingMedia) }) { Text(stringResource(R.string.ed_2b_check_again)) }
                    TextButton(onClick = { onIntent(EditorIntent.HideRelink) }) { Text(stringResource(R.string.common_close)) }
                }
            }
        },
    )
}

/** The label of the scan action: the first scan of a session, or one more after results exist. Shared with the tests. */
internal fun scanActionLabel(folderRelink: FolderRelinkUi): UiText =
    if (folderRelink is FolderRelinkUi.Done) UiText.res(R.string.ed_2b_scan_another_folder) else UiText.res(R.string.ed_2b_scan_a_folder)

/**
 * Close, Scan another folder… and Back to the list (the last two only while something is still missing). A flow row, so on a
 * narrow phone the buttons wrap onto a second line instead of running out of the dialog.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultsButtons(somethingMissing: Boolean, onIntent: (EditorIntent) -> Unit) {
    FlowRow(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { onIntent(EditorIntent.HideRelink) }) { Text(stringResource(R.string.common_close)) }
        if (somethingMissing) {
            TextButton(onClick = { onIntent(EditorIntent.RequestFolderRelink) }) { Text(stringResource(R.string.ed_2b_scan_another_folder)) }
            TextButton(onClick = { onIntent(EditorIntent.DismissFolderRelink) }) { Text(stringResource(R.string.ed_2b_back_to_the_list)) }
        }
    }
}

@Composable
private fun MissingList(missing: List<MissingAsset>, onIntent: (EditorIntent) -> Unit) {
    Column {
        Text(
            stringResource(R.string.ed_2b_missing_list_note),
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(missing, key = { it.assetId }) { asset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(asset.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(
                            UiText.join(" · ", problemText(asset.problem), UiText.plural(R.plurals.ed_2b_clips_count, asset.clipCount)).asString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onIntent(EditorIntent.RequestRelink(asset.assetId)) }) { Text(stringResource(R.string.ed_2b_relink)) }
                }
            }
        }
    }
}

/** Words for the progress of a scan; shared with the tests. */
internal fun scanProgressText(progress: FolderRelinkUi.Running): UiText = when {
    progress.total > 0 -> UiText.res(R.string.ed_2b_scan_checking, progress.checked, progress.total)
    progress.files == 0 && progress.folders == 0 -> UiText.res(R.string.ed_2b_scan_starting)
    else -> UiText.res(R.string.ed_2b_scan_scanning, progress.files, progress.folders)
}

@Composable
private fun ScanProgress(progress: FolderRelinkUi.Running) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(scanProgressText(progress).asString(), style = MaterialTheme.typography.bodyMedium)
        if (progress.total > 0) {
            LinearProgressIndicator(progress = { progress.checked.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        Text(
            stringResource(R.string.ed_2b_looking_in_the_folder_and),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The headline of the results, shared with the tests. */
internal fun scanSummaryText(outcome: FolderRelinkOutcome): UiText = UiText.res(R.string.ed_2b_scan_summary, outcome.relinked.size, outcome.total)

@Composable
private fun ScanResults(outcome: FolderRelinkOutcome, onIntent: (EditorIntent) -> Unit) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text(scanSummaryText(outcome).asString(), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ed_2b_scan_looked_at, outcome.filesSeen),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (outcome.truncated) {
                Text(
                    stringResource(R.string.ed_2b_the_folder_is_very_large),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!outcome.accessKept && outcome.relinked.isNotEmpty()) {
                Text(
                    stringResource(R.string.ed_2b_android_would_not_keep_access),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        val withWarnings = outcome.relinked.filter { it.warnings.isNotEmpty() }
        if (withWarnings.isNotEmpty()) {
            item { Text(stringResource(R.string.ed_2b_relinked_with_a_note), style = MaterialTheme.typography.titleSmall) }
            items(withWarnings, key = { "w-" + it.old.id }) { r ->
                Text("${MissingMedia.nameOf(r.asset)}: ${r.warnings.joinToString(". ")}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (outcome.ambiguous.isNotEmpty()) {
            item { Text(stringResource(R.string.ed_2b_several_files_with_the_same, outcome.ambiguous.size), style = MaterialTheme.typography.titleSmall) }
            items(outcome.ambiguous, key = { "a-" + it.assetId }) { item ->
                Column {
                    Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                    for (candidate in item.candidates) {
                        TextButton(onClick = { onIntent(EditorIntent.RelinkFromCandidate(item.assetId, candidate.uri)) }) {
                            Text(candidate.path.joinToString("/"), maxLines = 2)
                        }
                    }
                    TextButton(onClick = { onIntent(EditorIntent.RequestRelink(item.assetId)) }) { Text(stringResource(R.string.ed_2b_pick_another_file)) }
                }
            }
        }
        if (outcome.notFound.isNotEmpty()) {
            item { Text(stringResource(R.string.ed_2b_not_found, outcome.notFound.size), style = MaterialTheme.typography.titleSmall) }
            items(outcome.notFound, key = { "n-" + it.assetId }) { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                        Text(item.reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onIntent(EditorIntent.RequestRelink(item.assetId)) }) { Text(stringResource(R.string.ed_2b_relink)) }
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
        title = { Text(stringResource(R.string.ed_2b_changes_not_saved)) },
        text = { Text(if (message != null) stringResource(R.string.ed_2b_save_failed_with, message) else stringResource(R.string.ed_2b_save_failed)) },
        confirmButton = { TextButton(onClick = { onIntent(EditorIntent.RetrySave) }) { Text(stringResource(R.string.ed_2b_retry)) } },
        dismissButton = { TextButton(onClick = { onIntent(EditorIntent.LeaveWithoutSaving) }) { Text(stringResource(R.string.ed_2b_leave_without_saving)) } },
    )
}

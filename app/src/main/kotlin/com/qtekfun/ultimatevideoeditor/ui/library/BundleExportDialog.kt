package com.qtekfun.ultimatevideoeditor.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.data.interchange.BundleChoice

/**
 * Asks what a project bundle should contain: the media files, the LUTs and the fonts the project uses, each
 * with its own switch, and how big the result would be. Fonts are off until ticked, because their licences may
 * not allow giving the file to others.
 */
@Composable
fun BundleExportDialog(
    draft: BundleExportDraft,
    onChoice: (BundleChoice) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Project bundle") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val preview = draft.preview
                when {
                    draft.failed != null -> Text(draft.failed, color = MaterialTheme.colorScheme.error)
                    preview == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Measuring the project…")
                    }
                    else -> {
                        Text(
                            "A bundle is one file you can move to another phone. It always has the project; choose what else goes in.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SwitchRow(
                            title = BundleExportText.mediaLine(preview),
                            checked = draft.choice.includeMedia,
                            enabled = preview.mediaCount > 0,
                            onChange = { onChoice(draft.choice.copy(includeMedia = it)) },
                        )
                        BundleExportText.lutLine(preview)?.let { line ->
                            SwitchRow(
                                title = line,
                                checked = draft.choice.includeLuts,
                                enabled = preview.luts.any { it.available },
                                onChange = { onChoice(draft.choice.copy(includeLuts = it)) },
                            )
                        }
                        BundleExportText.fontLine(preview)?.let { line ->
                            SwitchRow(
                                title = line,
                                checked = draft.choice.includeFonts,
                                enabled = preview.fonts.any { it.available },
                                onChange = { onChoice(draft.choice.copy(includeFonts = it)) },
                                note = BundleExportText.FONT_LICENCE_NOTE,
                            )
                        }
                        if (!preview.hasResources) {
                            Text(
                                "This project uses no imported LUTs or fonts.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(BundleExportText.estimateLine(draft), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = draft.canExport) { Text("Choose where to save…") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SwitchRow(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit, note: String? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(title, modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = checked && enabled, onCheckedChange = null, enabled = enabled)
        }
        if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What an import could not install, listed until dismissed so nothing is lost in a disappearing message. */
@Composable
fun ImportReportDialog(notes: ImportReportNotes, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("\"${notes.projectName}\" imported") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notes.problems.isNotEmpty()) {
                    Text(notes.problemsHeading ?: "These LUTs and fonts are not available, so the project shows them as missing:", style = MaterialTheme.typography.bodyMedium)
                    for (line in notes.problems) Text("• $line", style = MaterialTheme.typography.bodySmall)
                }
                for (line in notes.notes) Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

/** Shown while a big import (a LumaFusion package) copies its footage: what is being copied, how far, and a way to stop. */
@Composable
fun ImportProgressDialog(progress: com.qtekfun.ultimatevideoeditor.data.interchange.ImportProgress, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Importing footage") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(progress.what, style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    "%.1f of %.1f GB".format(progress.doneBytes / GIB, progress.totalBytes / GIB),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

private const val GIB = 1024.0 * 1024.0 * 1024.0

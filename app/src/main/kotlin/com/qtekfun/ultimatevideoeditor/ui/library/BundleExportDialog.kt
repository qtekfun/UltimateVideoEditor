package com.qtekfun.ultimatevideoeditor.ui.library

import com.qtekfun.ultimatevideoeditor.ui.text.asString
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatevideoeditor.R
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
        title = { Text(stringResource(R.string.bundle_dlg_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val preview = draft.preview
                when {
                    draft.failed != null -> Text(draft.failed.asString(), color = MaterialTheme.colorScheme.error)
                    preview == null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(stringResource(R.string.bundle_dlg_measuring))
                    }
                    else -> {
                        Text(
                            stringResource(R.string.bundle_dlg_intro),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SwitchRow(
                            title = BundleExportText.mediaLine(preview).asString(),
                            checked = draft.choice.includeMedia,
                            enabled = preview.mediaCount > 0,
                            onChange = { onChoice(draft.choice.copy(includeMedia = it)) },
                        )
                        BundleExportText.lutLine(preview)?.let { line ->
                            SwitchRow(
                                title = line.asString(),
                                checked = draft.choice.includeLuts,
                                enabled = preview.luts.any { it.available },
                                onChange = { onChoice(draft.choice.copy(includeLuts = it)) },
                            )
                        }
                        BundleExportText.fontLine(preview)?.let { line ->
                            SwitchRow(
                                title = line.asString(),
                                checked = draft.choice.includeFonts,
                                enabled = preview.fonts.any { it.available },
                                onChange = { onChoice(draft.choice.copy(includeFonts = it)) },
                                note = stringResource(R.string.bundle_font_licence_note),
                            )
                        }
                        if (!preview.hasResources) {
                            Text(
                                stringResource(R.string.bundle_dlg_no_resources),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(BundleExportText.estimateLine(draft).asString(), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = draft.canExport) { Text(stringResource(R.string.bundle_dlg_choose_where)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
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
        title = { Text(stringResource(R.string.import_dlg_title, notes.projectName)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (notes.problems.isNotEmpty()) {
                    Text(notes.problemsHeading?.asString() ?: stringResource(R.string.import_dlg_heading), style = MaterialTheme.typography.bodyMedium)
                    for (line in notes.problems) Text(stringResource(R.string.list_bullet, line.asString()), style = MaterialTheme.typography.bodySmall)
                }
                for (line in notes.notes) Text(line.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) } },
    )
}

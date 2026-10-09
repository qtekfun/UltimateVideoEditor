package com.qtekfun.ultimatevideoeditor.ui.library

import com.qtekfun.ultimatevideoeditor.ui.text.isNotEmpty
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.ui.export.ImportJobHost
import com.qtekfun.ultimatevideoeditor.ui.export.ImportView
import com.qtekfun.ultimatevideoeditor.ui.export.importViewFor

/**
 * The progress and the result of a project import, over whatever screen is showing, driven by the process-wide [ImportJobHost].
 * Running: "Importing project: file 3 of 12: IMG_0014.mov", bytes done of total, percent, speed, time left, and Cancel (stops
 * within one chunk and removes everything the import made) or Hide (the import goes on; the bar of the project list and the
 * notification still show it). Ended: the result with Open, what it did not do cleanly, and Close. Opens by itself only after a
 * moment ([com.qtekfun.ultimatevideoeditor.ui.export.BundleImportExecutor]), so a small file never flashes it.
 */
@Composable
fun ImportJobDialog(host: ImportJobHost, onOpenProject: (String) -> Unit) {
    val state by host.state.collectAsState()
    val open by host.detailsOpen.collectAsState()
    val view = remember(state) { importViewFor(state) }
    if (!open || view == null) return
    val problem = view.phase == ImportView.Phase.FAILED || view.phase == ImportView.Phase.NOTES
    AlertDialog(
        // Back or a tap outside only hides the dialog; the import and its bar are not touched.
        onDismissRequest = host::hideDetails,
        title = { Text(view.title.asString()) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view.running) {
                    Text(view.step.asString(), style = MaterialTheme.typography.bodyMedium)
                    if (view.indeterminate) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { view.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    if (view.progressLine.isNotEmpty()) {
                        Text(stringResource(R.string.percent_then_line, view.percent, view.progressLine.asString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (view.rateLine.isNotEmpty()) {
                        Text(view.rateLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        stringResource(R.string.import_job_background),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(view.message.asString(), style = MaterialTheme.typography.bodyMedium, color = if (view.phase == ImportView.Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (view.detail.isNotEmpty()) {
                        Text(view.detail.asString(), style = MaterialTheme.typography.bodySmall, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (view.running) {
                TextButton(onClick = host::hideDetails) { Text(stringResource(R.string.common_hide)) }
            } else {
                TextButton(onClick = host::acknowledge) { Text(stringResource(R.string.common_close)) }
            }
        },
        dismissButton = {
            val id = view.projectId
            if (view.running) {
                TextButton(onClick = host::cancel) { Text(stringResource(R.string.common_cancel)) }
            } else if (id != null) {
                TextButton(onClick = { host.acknowledge(); onOpenProject(id) }) { Text(stringResource(R.string.common_open)) }
            }
        },
    )
}

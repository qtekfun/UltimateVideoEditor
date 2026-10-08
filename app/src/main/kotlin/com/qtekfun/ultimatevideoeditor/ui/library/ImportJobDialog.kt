package com.qtekfun.ultimatevideoeditor.ui.library

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
        title = { Text(view.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view.running) {
                    Text(view.step, style = MaterialTheme.typography.bodyMedium)
                    if (view.indeterminate) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { view.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    }
                    if (view.progressLine.isNotEmpty()) {
                        Text("${view.percent}% · ${view.progressLine}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (view.rateLine.isNotEmpty()) {
                        Text(view.rateLine, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "You can leave the app or turn the screen off; the import keeps going and the notification shows how far it is. " +
                            "Cancel removes everything it has copied so far.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(view.message, style = MaterialTheme.typography.bodyMedium, color = if (view.phase == ImportView.Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (view.detail.isNotEmpty()) {
                        Text(view.detail, style = MaterialTheme.typography.bodySmall, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (view.running) {
                TextButton(onClick = host::hideDetails) { Text("Hide") }
            } else {
                TextButton(onClick = host::acknowledge) { Text("Close") }
            }
        },
        dismissButton = {
            val id = view.projectId
            if (view.running) {
                TextButton(onClick = host::cancel) { Text("Cancel") }
            } else if (id != null) {
                TextButton(onClick = { host.acknowledge(); onOpenProject(id) }) { Text("Open") }
            }
        },
    )
}

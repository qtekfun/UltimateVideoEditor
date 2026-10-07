package com.ultimatevideo.uveditor.ui.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.ui.export.ExportBar
import com.ultimatevideo.uveditor.ui.export.ResultSeverity

/**
 * The export of the app, at the bottom of the project list so it is clear one is going and a second is not started.
 * Running: progress, time left and Cancel. Ended: the result with Share (when it worked) and Dismiss; it stays until
 * dismissed. Tapping the bar opens the project's editor with the export dialog.
 */
@Composable
internal fun ExportBarView(bar: ExportBar, onIntent: (HubIntent) -> Unit) {
    Surface(
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable { onIntent(HubIntent.OpenExportProject) },
    ) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            when (bar) {
                is ExportBar.Running -> {
                    Text(if (bar.verifying) "Verifying ${bar.projectName}" else "Exporting ${bar.projectName}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { bar.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(bar.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onIntent(HubIntent.CancelExport) }) { Text("Cancel") }
                    }
                }
                is ExportBar.Finished -> {
                    Text("Export finished: ${bar.fileName}", style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(bar.projectName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (bar.result.headline.isNotEmpty()) {
                        Text(
                            bar.result.headline,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (bar.result.severity == ResultSeverity.OK) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onIntent(HubIntent.ShareExport) }) { Text("Share") }
                        TextButton(onClick = { onIntent(HubIntent.DismissExportBar) }) { Text("Dismiss") }
                    }
                }
                is ExportBar.Failed -> {
                    Text("Export of ${bar.projectName} failed", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(bar.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onIntent(HubIntent.DismissExportBar) }) { Text("Dismiss") }
                    }
                }
            }
        }
    }
}

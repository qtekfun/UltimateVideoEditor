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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.ui.export.BundleJobHost
import com.qtekfun.ultimatevideoeditor.ui.export.BundleView
import com.qtekfun.ultimatevideoeditor.ui.export.bundleViewFor
import com.qtekfun.ultimatevideoeditor.ui.export.shareBundleIntent

/**
 * The progress and the result of a project backup, over whatever screen is showing (the project list or an editor), driven by
 * the process-wide [BundleJobHost]. Running: what is being packed, bytes done of total, percent, speed, time left, and Cancel
 * (stops at once and removes the partial file) or Hide (the backup goes on; the bar of the project list and the notification
 * still show it). Ended: the result, a red warning if the saved file did not check out, Share, and Close.
 */
@Composable
fun BundleJobDialog(host: BundleJobHost) {
    val state by host.state.collectAsState()
    val open by host.detailsOpen.collectAsState()
    val view = remember(state) { bundleViewFor(state) }
    if (!open || view == null) return
    val context = LocalContext.current
    val problem = view.phase == BundleView.Phase.FAILED || view.phase == BundleView.Phase.WARNING || view.phase == BundleView.Phase.CANCELLED
    AlertDialog(
        // Back or a tap outside only hides the dialog; the backup and its bar are not touched.
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
                        stringResource(R.string.bundle_job_background),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(view.message.asString(), style = MaterialTheme.typography.bodyMedium, color = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (view.detail.isNotEmpty()) {
                        Text(
                            view.detail.asString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (view.phase == BundleView.Phase.WARNING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
            if (view.running) {
                TextButton(onClick = host::cancel) { Text(stringResource(R.string.common_cancel)) }
            } else if (view.canShare) {
                TextButton(onClick = { context.startActivity(shareBundleIntent(view.uri.orEmpty())) }) { Text(stringResource(R.string.common_share)) }
            }
        },
    )
}

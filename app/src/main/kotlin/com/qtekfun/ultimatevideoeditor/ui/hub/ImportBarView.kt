package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.ui.text.isNotEmpty
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatevideoeditor.R
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
import com.qtekfun.ultimatevideoeditor.ui.export.ImportView

/**
 * The project import, at the bottom of the project list like the backup's bar: what is being read and copied, how far and how long
 * is left, with Cancel; when it ends, the result with Open and Dismiss (a failure in red, with its cause), staying until dismissed.
 * Tapping the bar opens the progress dialog.
 */
@Composable
internal fun ImportBarView(bar: ImportView, onIntent: (HubIntent) -> Unit) {
    Surface(
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().clickable { onIntent(HubIntent.ShowImportDetails) },
    ) {
        Column(
            modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(bar.title.asString(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (bar.running) {
                Text(bar.step.asString(), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (bar.indeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp))
                } else {
                    LinearProgressIndicator(progress = { bar.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(end = 8.dp, top = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(bar.barLine.asString(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { onIntent(HubIntent.CancelImport) }) { Text(stringResource(R.string.common_cancel)) }
                }
            } else {
                Text(
                    bar.message.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bar.phase == ImportView.Phase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
                if (bar.detail.isNotEmpty()) {
                    Text(bar.detail.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    if (bar.canOpen) TextButton(onClick = { onIntent(HubIntent.OpenImported) }) { Text(stringResource(R.string.common_open)) }
                    TextButton(onClick = { onIntent(HubIntent.DismissImportBar) }) { Text(stringResource(R.string.common_dismiss)) }
                }
            }
        }
    }
}

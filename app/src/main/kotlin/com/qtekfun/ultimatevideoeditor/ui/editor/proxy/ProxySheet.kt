package com.qtekfun.ultimatevideoeditor.ui.editor.proxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.proxy.ProxyPrefs
import com.qtekfun.ultimatevideoeditor.proxy.ProxyStatus
import com.qtekfun.ultimatevideoeditor.proxy.ProxySuggestion
import com.qtekfun.ultimatevideoeditor.proxy.ProxyTargets
import com.qtekfun.ultimatevideoeditor.proxy.SuggestionReason

/**
 * The live proxy state, as a State so only the composables that read it (badges, banner, sheet) recompose
 * when a proxy makes progress, never the whole editor screen.
 */
val LocalProxyUi = staticCompositionLocalOf<State<ProxyUiState>?> { null }

val LocalProxyIntent = staticCompositionLocalOf<(ProxyIntent) -> Unit> { {} }

/** The proxy status of [assetId] in the open project (None outside an editor). Read in the leaf that shows it. */
@Composable
fun proxyStatusOf(assetId: String): ProxyStatus = LocalProxyUi.current?.value?.statuses?.get(assetId) ?: ProxyStatus.None

/** The suggestion banner of the editor's notice area; nothing when there is nothing to suggest. */
@Composable
fun ProxyBannerHost(modifier: Modifier = Modifier) {
    val suggestion = LocalProxyUi.current?.value?.suggestion ?: return
    ProxySuggestionBanner(suggestion, LocalProxyIntent.current, modifier)
}

/** Shows [ProxySheet] while it is open; reads the state itself so the screen does not recompose for it. */
@Composable
fun ProxySheetHost(holder: State<ProxyUiState>, onIntent: (ProxyIntent) -> Unit) {
    val state = holder.value
    if (state.sheetOpen) ProxySheet(state, onIntent)
}

/** A short badge for a tile, or null when the asset has no proxy to show. */
fun ProxyStatus.badgeLabel(): String? = when (this) {
    ProxyStatus.None -> null
    ProxyStatus.Queued -> "Proxy…"
    is ProxyStatus.Making -> "Proxy ${permille / 10}%"
    is ProxyStatus.Ready -> "Proxy"
    ProxyStatus.OutOfDate -> "Proxy old"
    is ProxyStatus.Failed -> "Proxy failed"
}

/** What the sheet writes under a video's name. */
fun ProxyStatus.describe(): String = when (this) {
    ProxyStatus.None -> "No proxy"
    ProxyStatus.Queued -> "Waiting to be made"
    is ProxyStatus.Making -> "Making… ${permille / 10}%"
    is ProxyStatus.Ready -> "Ready, ${width}x$height, ${megabytes(bytes)}"
    ProxyStatus.OutOfDate -> "Out of date: the source changed"
    is ProxyStatus.Failed -> "Failed: $reason"
}

/** The dismissible offer to make proxies, shown with the other notices at the top of the editor. */
@Composable
fun ProxySuggestionBanner(suggestion: ProxySuggestion, onIntent: (ProxyIntent) -> Unit, modifier: Modifier = Modifier) {
    val count = suggestion.assetIds.size
    val text = when (suggestion.reason) {
        SuggestionReason.HEAVY_MEDIA ->
            "This project has heavy video ($count file${if (count == 1) "" else "s"}). Proxies are small copies used only for editing, so it plays and scrubs smoothly. Export always uses the originals."
        SuggestionReason.DROPPED_FRAMES ->
            "The preview is dropping frames. Proxies are small copies used only for editing; export always uses the originals."
    }
    Card(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onIntent(ProxyIntent.AcceptSuggestion) }) { Text("Make proxies") }
                TextButton(onClick = { onIntent(ProxyIntent.DismissSuggestion) }) { Text("Not now") }
            }
        }
    }
}

/** The proxy settings and the queue: the project switch, quality, a button per video, storage and clearing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProxySheet(state: ProxyUiState, onIntent: (ProxyIntent) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(ProxyIntent.CloseSheet) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Proxy media", style = MaterialTheme.typography.titleMedium)
            Text(
                "A proxy is a small copy of a heavy video, used only while you edit so the preview and the timeline stay smooth. " +
                    "The exported movie is always rendered from the original files. Proxies stay on this device.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth().toggleable(value = state.enabled, role = Role.Switch, onValueChange = { onIntent(ProxyIntent.SetEnabled(it)) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Use proxies for editing in this project", Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = null)
            }
            Text("Proxy size", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (shortSide in ProxyTargets.choices) {
                    FilterChip(
                        selected = state.targetShortSide == shortSide,
                        onClick = { onIntent(ProxyIntent.SetTarget(shortSide)) },
                        label = { Text("${shortSide}p") },
                    )
                }
            }
            Text("Videos in this project", style = MaterialTheme.typography.labelLarge)
            if (state.items.isEmpty()) {
                Text("There is no video in this project yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { onIntent(ProxyIntent.GenerateAll) }) { Text("Make proxies for all videos") }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.items, key = { it.assetId }) { item -> ProxyRow(item, onIntent) }
                }
            }
            Text("Storage", style = MaterialTheme.typography.labelLarge)
            Text("Using ${megabytes(state.usage.bytes)} of ${megabytes(state.usage.budgetBytes)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (budget in ProxyPrefs.budgetChoices) {
                    FilterChip(
                        selected = state.usage.budgetBytes == budget,
                        onClick = { onIntent(ProxyIntent.SetBudget(budget)) },
                        label = { Text("${budget shr 30} GB") },
                    )
                }
            }
            Text(
                "When the limit is reached the proxies used least recently are deleted first; the ones this project uses are kept.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { onIntent(ProxyIntent.RequestClear) }) { Text("Clear proxy cache") }
            state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = { onIntent(ProxyIntent.CloseSheet) }, modifier = Modifier.padding(bottom = 16.dp)) { Text("Done") }
        }
    }
    if (state.confirmClear) {
        AlertDialog(
            onDismissRequest = { onIntent(ProxyIntent.CancelClear) },
            title = { Text("Clear the proxy cache?") },
            text = { Text("Every proxy on this device is deleted. Projects keep their media; proxies can be made again. A proxy being made is left alone.") },
            confirmButton = { TextButton(onClick = { onIntent(ProxyIntent.ConfirmClear) }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { onIntent(ProxyIntent.CancelClear) }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProxyRow(item: ProxyItem, onIntent: (ProxyIntent) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(item.status.describe(), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (item.status) {
            ProxyStatus.None, ProxyStatus.OutOfDate -> TextButton(onClick = { onIntent(ProxyIntent.Generate(item.assetId)) }) { Text("Make") }
            is ProxyStatus.Failed -> TextButton(onClick = { onIntent(ProxyIntent.Generate(item.assetId)) }) { Text("Retry") }
            ProxyStatus.Queued, is ProxyStatus.Making -> TextButton(onClick = { onIntent(ProxyIntent.Remove(item.assetId)) }) { Text("Cancel") }
            is ProxyStatus.Ready -> TextButton(onClick = { onIntent(ProxyIntent.Remove(item.assetId)) }) { Text("Remove") }
        }
    }
}

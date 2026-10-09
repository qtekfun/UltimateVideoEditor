package com.qtekfun.ultimatevideoeditor.ui.editor.proxy

import androidx.compose.ui.res.pluralStringResource
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
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
fun ProxyStatus.badgeLabel(): UiText? = when (this) {
    ProxyStatus.None -> null
    ProxyStatus.Queued -> UiText.res(R.string.ed_s3_proxy_badge_queued)
    is ProxyStatus.Making -> UiText.res(R.string.ed_s3_proxy_badge_making, permille / 10)
    is ProxyStatus.Ready -> UiText.res(R.string.ed_s3_proxy_badge_ready)
    ProxyStatus.OutOfDate -> UiText.res(R.string.ed_s3_proxy_badge_old)
    is ProxyStatus.Failed -> UiText.res(R.string.ed_s3_proxy_badge_failed)
}

/** What the sheet writes under a video's name. */
fun ProxyStatus.describe(): UiText = when (this) {
    ProxyStatus.None -> UiText.res(R.string.ed_s3_proxy_none)
    ProxyStatus.Queued -> UiText.res(R.string.ed_s3_proxy_queued)
    is ProxyStatus.Making -> UiText.res(R.string.ed_s3_proxy_making, permille / 10)
    is ProxyStatus.Ready -> UiText.res(R.string.ed_s3_proxy_ready, width, height, megabytes(bytes))
    ProxyStatus.OutOfDate -> UiText.res(R.string.ed_s3_proxy_old)
    is ProxyStatus.Failed -> UiText.res(R.string.ed_s3_proxy_failed, reason)
}

/** The dismissible offer to make proxies, shown with the other notices at the top of the editor. */
@Composable
fun ProxySuggestionBanner(suggestion: ProxySuggestion, onIntent: (ProxyIntent) -> Unit, modifier: Modifier = Modifier) {
    val count = suggestion.assetIds.size
    val text = when (suggestion.reason) {
        SuggestionReason.HEAVY_MEDIA -> pluralStringResource(R.plurals.ed_s3_proxy_heavy, count, count)
        SuggestionReason.DROPPED_FRAMES -> stringResource(R.string.ed_s3_proxy_dropping)
    }
    Card(modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onIntent(ProxyIntent.AcceptSuggestion) }) { Text(stringResource(R.string.ed_s3_make_proxies)) }
                TextButton(onClick = { onIntent(ProxyIntent.DismissSuggestion) }) { Text(stringResource(R.string.ed_s3_not_now)) }
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
            Text(stringResource(R.string.ed_2a_tool_proxy), style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(R.string.ed_s3_proxy_intro),
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                Modifier.fillMaxWidth().toggleable(value = state.enabled, role = Role.Switch, onValueChange = { onIntent(ProxyIntent.SetEnabled(it)) }),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(stringResource(R.string.ed_s3_use_proxies_for_editing_in), Modifier.weight(1f))
                Switch(checked = state.enabled, onCheckedChange = null)
            }
            Text(stringResource(R.string.ed_s3_proxy_size), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (shortSide in ProxyTargets.choices) {
                    FilterChip(
                        selected = state.targetShortSide == shortSide,
                        onClick = { onIntent(ProxyIntent.SetTarget(shortSide)) },
                        label = { Text("${shortSide}p") },
                    )
                }
            }
            Text(stringResource(R.string.ed_s3_videos_in_this_project), style = MaterialTheme.typography.labelLarge)
            if (state.items.isEmpty()) {
                Text(stringResource(R.string.ed_s3_there_is_no_video_in), style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { onIntent(ProxyIntent.GenerateAll) }) { Text(stringResource(R.string.ed_s3_make_proxies_for_all_videos)) }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(state.items, key = { it.assetId }) { item -> ProxyRow(item, onIntent) }
                }
            }
            Text(stringResource(R.string.about_storage), style = MaterialTheme.typography.labelLarge)
            Text(stringResource(R.string.ed_s3_using_of, megabytes(state.usage.bytes), megabytes(state.usage.budgetBytes)), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (budget in ProxyPrefs.budgetChoices) {
                    FilterChip(
                        selected = state.usage.budgetBytes == budget,
                        onClick = { onIntent(ProxyIntent.SetBudget(budget)) },
                        label = { Text(stringResource(R.string.ed_s3_gb, budget shr 30)) },
                    )
                }
            }
            Text(
                stringResource(R.string.ed_s3_when_the_limit_is_reached),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { onIntent(ProxyIntent.RequestClear) }) { Text(stringResource(R.string.ed_s3_clear_proxy_cache)) }
            state.message?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            TextButton(onClick = { onIntent(ProxyIntent.CloseSheet) }, modifier = Modifier.padding(bottom = 16.dp)) { Text(stringResource(R.string.ed_2a_done)) }
        }
    }
    if (state.confirmClear) {
        AlertDialog(
            onDismissRequest = { onIntent(ProxyIntent.CancelClear) },
            title = { Text(stringResource(R.string.ed_s3_clear_the_proxy_cache)) },
            text = { Text(stringResource(R.string.ed_s3_every_proxy_on_this_device)) },
            confirmButton = { TextButton(onClick = { onIntent(ProxyIntent.ConfirmClear) }) { Text(stringResource(R.string.common_clear)) } },
            dismissButton = { TextButton(onClick = { onIntent(ProxyIntent.CancelClear) }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
private fun ProxyRow(item: ProxyItem, onIntent: (ProxyIntent) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            Text(item.status.describe().asString(), maxLines = 2, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (item.status) {
            ProxyStatus.None, ProxyStatus.OutOfDate -> TextButton(onClick = { onIntent(ProxyIntent.Generate(item.assetId)) }) { Text(stringResource(R.string.ed_s3_make)) }
            is ProxyStatus.Failed -> TextButton(onClick = { onIntent(ProxyIntent.Generate(item.assetId)) }) { Text(stringResource(R.string.ed_2b_retry)) }
            ProxyStatus.Queued, is ProxyStatus.Making -> TextButton(onClick = { onIntent(ProxyIntent.Remove(item.assetId)) }) { Text(stringResource(R.string.common_cancel)) }
            is ProxyStatus.Ready -> TextButton(onClick = { onIntent(ProxyIntent.Remove(item.assetId)) }) { Text(stringResource(R.string.ed_2b_remove)) }
        }
    }
}

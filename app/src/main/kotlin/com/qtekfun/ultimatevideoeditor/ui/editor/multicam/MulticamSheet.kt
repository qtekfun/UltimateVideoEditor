package com.qtekfun.ultimatevideoeditor.ui.editor.multicam

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.multicam.AngleFeed
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamClip
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamOps
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorState

/**
 * The multicam sheet: pick two to six files as angles, line them up by their sound (with a manual nudge),
 * create the clip on the base, then cut between angles at the playhead, live while recording or one tap at
 * a time. "Flatten" keeps the cuts as ordinary clips.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MulticamSheet(
    state: EditorState,
    group: MulticamClip?,
    feedsOf: (MulticamClip, Int) -> List<AngleFeed>,
    onIntent: (MulticamIntent) -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(MulticamIntent.Close) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Multicam", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onIntent(MulticamIntent.Close) }) { Text("Done") }
            }
            if (group != null) {
                LiveCutting(state, group, feedsOf, onIntent)
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }
            NewMulticam(state, onIntent)
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NewMulticam(state: EditorState, onIntent: (MulticamIntent) -> Unit) {
    val ui = state.multicam
    Text("New multicam clip", style = MaterialTheme.typography.titleSmall)
    Text(
        "Pick 2 to 6 clips that recorded the same event. The first is the reference; the others are lined up with it by their sound.",
        style = MaterialTheme.typography.bodySmall,
    )
    val candidates = state.assets.filter { it.hasAudio && !it.isImage }
    if (candidates.isEmpty()) Text("Import videos with sound first.", style = MaterialTheme.typography.bodyMedium)
    for (asset in candidates) AngleChoice(state, asset, onIntent)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onIntent(MulticamIntent.Sync) },
            enabled = ui.draft.assetIds.size >= MulticamOps.MIN_ANGLES && !ui.syncing,
        ) { Text(if (ui.syncing) "Listening…" else "Sync by sound") }
        Button(
            onClick = { onIntent(MulticamIntent.Create) },
            enabled = ui.draft.assetIds.size >= MulticamOps.MIN_ANGLES && !ui.syncing,
        ) { Text("Create at playhead") }
    }
}

@Composable
private fun AngleChoice(state: EditorState, asset: MediaAssetDto, onIntent: (MulticamIntent) -> Unit) {
    val draft = state.multicam.draft
    val position = draft.assetIds.indexOf(asset.id)
    val chosen = position >= 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = chosen,
            onClick = { onIntent(MulticamIntent.ToggleAsset(asset.id)) },
            label = { Text(if (chosen) "${position + 1}. ${assetLabel(asset)}" else assetLabel(asset)) },
            modifier = Modifier.weight(1f).semantics {
                contentDescription = if (chosen) "Remove ${assetLabel(asset)} from the angles" else "Use ${assetLabel(asset)} as an angle"
            },
        )
        if (chosen && position > 0) {
            val outcome = draft.outcomes[asset.id]
            Text(outcomeText(outcome, draft.offsetOf(asset.id)), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { onIntent(MulticamIntent.NudgeDraft(asset.id, -1)) }, modifier = Modifier.semantics { contentDescription = "Nudge one frame earlier" }) { Text("−1") }
            TextButton(onClick = { onIntent(MulticamIntent.NudgeDraft(asset.id, 1)) }, modifier = Modifier.semantics { contentDescription = "Nudge one frame later" }) { Text("+1") }
        }
    }
}

@Composable
private fun LiveCutting(
    state: EditorState,
    group: MulticamClip,
    feedsOf: (MulticamClip, Int) -> List<AngleFeed>,
    onIntent: (MulticamIntent) -> Unit,
) {
    val ui = state.multicam
    val frame = (state.playhead.value - group.startFrame).coerceIn(0, group.lengthFrames - 1)
    val onScreen = group.angleAt(frame)
    val feeds = feedsOf(group, onScreen)
    Text(group.name, style = MaterialTheme.typography.titleSmall)
    Text(
        if (ui.recording) "Recording: ${ui.pendingCuts.size} cut(s) so far. Play and tap an angle to switch to it."
        else "Tap an angle to cut to it at the playhead, or record while the video plays.",
        style = MaterialTheme.typography.bodySmall,
    )
    for (rowOfAngles in group.angles.withIndex().chunked(3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for ((i, angle) in rowOfAngles) {
                val active = i == onScreen
                val button: @Composable () -> Unit = {
                    Text("${angle.name}\n${feedLabel(feeds.getOrNull(i))}", style = MaterialTheme.typography.labelMedium)
                }
                val modifier = Modifier.weight(1f).semantics {
                    contentDescription = "Cut to ${angle.name}" + if (active) ", on screen now" else ""
                }
                if (active) Button(onClick = { onIntent(MulticamIntent.CutTo(i)) }, modifier = modifier) { button() }
                else OutlinedButton(
                    onClick = { onIntent(MulticamIntent.CutTo(i)) },
                    modifier = modifier,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                ) { button() }
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.recording) Button(onClick = { onIntent(MulticamIntent.ToggleRecording) }) { Text("Stop recording") }
        else OutlinedButton(onClick = { onIntent(MulticamIntent.ToggleRecording) }) { Text("Record cuts") }
        TextButton(onClick = { onIntent(MulticamIntent.RemoveCutHere(group.id)) }) { Text("Remove cut here") }
    }
    if (group.audioTrackId != null) {
        Text("Sound from", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, angle) in group.angles.withIndex()) {
                FilterChip(
                    selected = i == group.audioAngle,
                    onClick = { onIntent(MulticamIntent.SetAudioAngle(group.id, i)) },
                    label = { Text(angle.name) },
                    modifier = Modifier.semantics { contentDescription = "Use the sound of ${angle.name}" },
                )
            }
        }
    }
    Text("Fine sync", style = MaterialTheme.typography.labelLarge)
    for ((i, angle) in group.angles.withIndex().drop(1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${angle.name}: ${signed(angle.offsetFrames)} frames", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onIntent(MulticamIntent.NudgeAngle(group.id, i, -1)) }, modifier = Modifier.semantics { contentDescription = "Move ${angle.name} one frame earlier" }) { Text("−1") }
            TextButton(onClick = { onIntent(MulticamIntent.NudgeAngle(group.id, i, 1)) }, modifier = Modifier.semantics { contentDescription = "Move ${angle.name} one frame later" }) { Text("+1") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onIntent(MulticamIntent.Resync(group.id)) }, enabled = !ui.syncing) { Text(if (ui.syncing) "Listening…" else "Sync again") }
        OutlinedButton(onClick = { onIntent(MulticamIntent.Flatten(group.id)) }) { Text("Flatten") }
    }
}

private fun feedLabel(feed: AngleFeed?): String = when (feed) {
    AngleFeed.FULL -> "live"
    AngleFeed.PROXY -> "proxy"
    AngleFeed.STILL, null -> "still"
}

private fun signed(value: Long) = if (value > 0) "+$value" else value.toString()

private fun outcomeText(outcome: SyncOutcome?, offset: Long): String = when (outcome) {
    null -> if (offset != 0L) signed(offset) else ""
    SyncOutcome.Reference -> "reference"
    is SyncOutcome.Found -> (if (outcome.confident) "synced " else "unsure ") + signed(offset)
    is SyncOutcome.Failed -> "no match: nudge"
}

/** A short name for a library file: its file name, else its id. */
internal fun assetLabel(asset: MediaAssetDto): String =
    asset.displayName?.ifBlank { null }
        ?: android.net.Uri.parse(asset.uri).lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
        ?: asset.id

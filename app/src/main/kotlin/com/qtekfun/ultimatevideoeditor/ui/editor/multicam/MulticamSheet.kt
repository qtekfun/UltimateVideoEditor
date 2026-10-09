package com.qtekfun.ultimatevideoeditor.ui.editor.multicam

import androidx.compose.ui.res.pluralStringResource
import com.qtekfun.ultimatevideoeditor.ui.editor.described
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
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
                Text(stringResource(R.string.ed_2a_tool_multicam), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onIntent(MulticamIntent.Close) }) { Text(stringResource(R.string.ed_2a_done)) }
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
    Text(stringResource(R.string.ed_s3_new_multicam_clip), style = MaterialTheme.typography.titleSmall)
    Text(
        stringResource(R.string.ed_s3_pick_to_clips_that_recorded),
        style = MaterialTheme.typography.bodySmall,
    )
    val candidates = state.assets.filter { it.hasAudio && !it.isImage }
    if (candidates.isEmpty()) Text(stringResource(R.string.ed_s3_import_videos_with_sound_first), style = MaterialTheme.typography.bodyMedium)
    for (asset in candidates) AngleChoice(state, asset, onIntent)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { onIntent(MulticamIntent.Sync) },
            enabled = ui.draft.assetIds.size >= MulticamOps.MIN_ANGLES && !ui.syncing,
        ) { Text(if (ui.syncing) stringResource(R.string.ed_s3_listening) else stringResource(R.string.ed_s3_sync_by_sound)) }
        Button(
            onClick = { onIntent(MulticamIntent.Create) },
            enabled = ui.draft.assetIds.size >= MulticamOps.MIN_ANGLES && !ui.syncing,
        ) { Text(stringResource(R.string.ed_s3_create_at_playhead)) }
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
            label = { Text(if (chosen) stringResource(R.string.ed_s3_numbered, position + 1, assetLabel(asset)) else assetLabel(asset)) },
            modifier = Modifier.weight(1f).described(
                if (chosen) stringResource(R.string.ed_s3_remove_from_the_angles, assetLabel(asset)) else stringResource(R.string.ed_s3_use_as_an_angle, assetLabel(asset)),
            ),
        )
        if (chosen && position > 0) {
            val outcome = draft.outcomes[asset.id]
            Text(outcomeText(outcome, draft.offsetOf(asset.id)), style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { onIntent(MulticamIntent.NudgeDraft(asset.id, -1)) }, modifier = Modifier.described(stringResource(R.string.ed_s3_nudge_one_frame_earlier))) { Text("−1") }
            TextButton(onClick = { onIntent(MulticamIntent.NudgeDraft(asset.id, 1)) }, modifier = Modifier.described(stringResource(R.string.ed_s3_nudge_one_frame_later))) { Text("+1") }
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
        if (ui.recording) pluralStringResource(R.plurals.ed_s3_recording_cuts, ui.pendingCuts.size, ui.pendingCuts.size)
        else stringResource(R.string.ed_s3_tap_an_angle_to_cut),
        style = MaterialTheme.typography.bodySmall,
    )
    for (rowOfAngles in group.angles.withIndex().chunked(3)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            for ((i, angle) in rowOfAngles) {
                val active = i == onScreen
                val button: @Composable () -> Unit = {
                    Text("${angle.name}\n${feedLabel(feeds.getOrNull(i))}", style = MaterialTheme.typography.labelMedium)
                }
                val modifier = Modifier.weight(1f).described(
                    stringResource(if (active) R.string.ed_s3_cut_to_active else R.string.ed_s3_cut_to, angle.name),
                )
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
        if (ui.recording) Button(onClick = { onIntent(MulticamIntent.ToggleRecording) }) { Text(stringResource(R.string.ed_s3_stop_recording)) }
        else OutlinedButton(onClick = { onIntent(MulticamIntent.ToggleRecording) }) { Text(stringResource(R.string.ed_s3_record_cuts)) }
        TextButton(onClick = { onIntent(MulticamIntent.RemoveCutHere(group.id)) }) { Text(stringResource(R.string.ed_s3_remove_cut_here)) }
    }
    if (group.audioTrackId != null) {
        Text(stringResource(R.string.ed_s3_sound_from), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, angle) in group.angles.withIndex()) {
                FilterChip(
                    selected = i == group.audioAngle,
                    onClick = { onIntent(MulticamIntent.SetAudioAngle(group.id, i)) },
                    label = { Text(angle.name) },
                    modifier = Modifier.described(stringResource(R.string.ed_s3_use_the_sound_of, angle.name)),
                )
            }
        }
    }
    Text(stringResource(R.string.ed_s3_fine_sync), style = MaterialTheme.typography.labelLarge)
    for ((i, angle) in group.angles.withIndex().drop(1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.ed_s3_frames, angle.name, signed(angle.offsetFrames)), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { onIntent(MulticamIntent.NudgeAngle(group.id, i, -1)) }, modifier = Modifier.described(stringResource(R.string.ed_s3_move_one_frame_earlier, angle.name))) { Text("−1") }
            TextButton(onClick = { onIntent(MulticamIntent.NudgeAngle(group.id, i, 1)) }, modifier = Modifier.described(stringResource(R.string.ed_s3_move_one_frame_later, angle.name))) { Text("+1") }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { onIntent(MulticamIntent.Resync(group.id)) }, enabled = !ui.syncing) { Text(if (ui.syncing) stringResource(R.string.ed_s3_listening) else stringResource(R.string.ed_s3_sync_again)) }
        OutlinedButton(onClick = { onIntent(MulticamIntent.Flatten(group.id)) }) { Text(stringResource(R.string.ed_s3_flatten)) }
    }
}

@Composable
private fun feedLabel(feed: AngleFeed?): String = stringResource(
    when (feed) {
        AngleFeed.FULL -> R.string.ed_s3_feed_live
        AngleFeed.PROXY -> R.string.ed_s3_feed_proxy
        AngleFeed.STILL, null -> R.string.ed_s3_feed_still
    },
)

private fun signed(value: Long) = if (value > 0) "+$value" else value.toString()

@Composable
private fun outcomeText(outcome: SyncOutcome?, offset: Long): String = when (outcome) {
    null -> if (offset != 0L) signed(offset) else ""
    SyncOutcome.Reference -> stringResource(R.string.ed_s3_sync_reference)
    is SyncOutcome.Found -> stringResource(if (outcome.confident) R.string.ed_s3_sync_found else R.string.ed_s3_sync_unsure, signed(offset))
    is SyncOutcome.Failed -> stringResource(R.string.ed_s3_sync_failed)
}

/** A short name for a library file: its file name, else its id. */
internal fun assetLabel(asset: MediaAssetDto): String =
    asset.displayName?.ifBlank { null }
        ?: android.net.Uri.parse(asset.uri).lastPathSegment?.substringAfterLast('/')?.ifBlank { null }
        ?: asset.id

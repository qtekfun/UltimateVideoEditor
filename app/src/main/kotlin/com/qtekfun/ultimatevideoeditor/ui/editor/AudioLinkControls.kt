package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.ui.res.pluralStringResource
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipLinks
import kotlin.math.abs

/**
 * Detached and linked sound of the selected video or audio clip (SPECS 5.38): detach, unlink, relink (with the offset in frames when the
 * pair is out of sync) and restore the embedded sound. Shown for video clips and for audio-lane clips of a video's media; nothing
 * for clips without sound.
 */
@Composable
internal fun AudioLinkControls(state: EditorState, clip: Clip, onIntent: (EditorIntent) -> Unit) {
    val hasAudio = state.assets.firstOrNull { it.id == clip.assetId }?.hasAudio == true
    val info = ClipLinks.infoFor(state.timeline, clip.id, hasAudio)?.takeIf { it.relevant } ?: return
    Text(stringResource(R.string.ed_2b_linked_audio), style = MaterialTheme.typography.titleSmall)
    Text(summary(info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val offset = info.offsetFrames
    if (offset != null && offset != 0L && (info.linked || info.canRelink)) {
        Text(
            pluralStringResource(if (offset > 0) R.plurals.ed_2b_audio_late else R.plurals.ed_2b_audio_early, abs(offset).toInt(), abs(offset).toInt()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (info.canDetach) {
                OutlinedButton(onClick = { onIntent(EditorIntent.DetachAudio) }) { Text(stringResource(R.string.ed_2a_tool_detach_audio)) }
            }
            if (info.linked) {
                OutlinedButton(onClick = { onIntent(EditorIntent.UnlinkAudio) }) { Text(stringResource(R.string.ed_2b_unlink)) }
            }
            if (info.canRelink) {
                OutlinedButton(onClick = { onIntent(EditorIntent.RelinkAudio(realign = false)) }) { Text(stringResource(R.string.ed_2b_relink)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (info.canRelink && offset != null && offset != 0L) {
                TextButton(onClick = { onIntent(EditorIntent.RelinkAudio(realign = true)) }) { Text(stringResource(R.string.ed_2b_relink_and_realign)) }
            }
            if (info.canRestore) {
                TextButton(onClick = { onIntent(EditorIntent.RestoreEmbeddedAudio) }) { Text(stringResource(R.string.ed_2b_restore_embedded_audio)) }
            }
        }
    }
}

@Composable
private fun summary(info: ClipLinks.LinkInfo): String = stringResource(
    when {
        info.isVideo && info.detached && info.linked -> R.string.ed_2b_link_detached_linked
        info.isVideo && info.detached -> R.string.ed_2b_link_detached
        info.isVideo && info.canRelink -> R.string.ed_2b_link_can_relink
        info.isVideo -> R.string.ed_2b_link_embedded
        info.linked -> R.string.ed_2b_link_audio_linked
        else -> R.string.ed_2b_link_not_linked
    },
)

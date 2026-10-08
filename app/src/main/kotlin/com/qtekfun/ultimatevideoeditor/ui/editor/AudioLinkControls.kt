package com.qtekfun.ultimatevideoeditor.ui.editor

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
    Text("Linked audio", style = MaterialTheme.typography.titleSmall)
    Text(summary(info), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val offset = info.offsetFrames
    if (offset != null && offset != 0L && (info.linked || info.canRelink)) {
        val side = if (offset > 0) "late" else "early"
        Text(
            "The audio is ${abs(offset)} ${if (abs(offset) == 1L) "frame" else "frames"} $side against the picture",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (info.canDetach) {
                OutlinedButton(onClick = { onIntent(EditorIntent.DetachAudio) }) { Text("Detach audio") }
            }
            if (info.linked) {
                OutlinedButton(onClick = { onIntent(EditorIntent.UnlinkAudio) }) { Text("Unlink") }
            }
            if (info.canRelink) {
                OutlinedButton(onClick = { onIntent(EditorIntent.RelinkAudio(realign = false)) }) { Text("Relink") }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            if (info.canRelink && offset != null && offset != 0L) {
                TextButton(onClick = { onIntent(EditorIntent.RelinkAudio(realign = true)) }) { Text("Relink and realign") }
            }
            if (info.canRestore) {
                TextButton(onClick = { onIntent(EditorIntent.RestoreEmbeddedAudio) }) { Text("Restore embedded audio") }
            }
        }
    }
}

private fun summary(info: ClipLinks.LinkInfo): String = when {
    info.isVideo && info.detached && info.linked -> "This clip's sound is on an audio lane, linked: they move, trim, split and delete together."
    info.isVideo && info.detached -> "This clip is silent. Its sound is on an audio lane, unlinked, or gone; relink an audio clip or restore the embedded sound."
    info.isVideo && info.canRelink -> "An audio clip of the same media is on the timeline. Relinking silences this clip's own sound."
    info.isVideo -> "The sound is part of the clip. Detach it to cut, move or delete it on an audio lane."
    info.linked -> "Linked to a video clip: they move, trim, split together. Deleting this audio keeps the picture, silent."
    else -> "Not linked. A video clip of the same media is on the timeline."
}

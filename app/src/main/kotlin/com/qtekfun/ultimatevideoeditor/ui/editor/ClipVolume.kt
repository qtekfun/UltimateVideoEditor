package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipGain
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import kotlin.math.roundToInt

/** The volume of the selected clips, as the toolbar's own button offers it (the clip panel and the mixer reach the same values). */
internal object ClipVolume {
    /** The lowest slider position; at or under it the clip is muted. */
    const val SLIDER_MIN = -60f
    const val SLIDER_MAX = 12f

    /**
     * The selected clips that make a sound: audio clips and video clips whose own sound is still on them (a detached one is
     * silent: its sound is the audio clip). Titles, stickers and photos have none.
     */
    fun soundClips(timeline: Timeline, assets: List<MediaAssetDto>, ids: Collection<String>): List<Clip> =
        ids.mapNotNull { id ->
            val track = timeline.trackOfClip(id) ?: return@mapNotNull null
            val clip = track.clip(id) ?: return@mapNotNull null
            val hasAudio = assets.firstOrNull { it.id == clip.assetId }?.hasAudio == true
            clip.takeIf { it.hasMedia && track.type != TrackType.TITLE && hasAudio && !it.audioDetached }
        }

    /** The slider position of a gain in dB: a mute sits at the bottom. */
    fun sliderOf(gainDb: Double): Float = gainDb.toFloat().coerceIn(SLIDER_MIN, SLIDER_MAX)

    /** The gain in dB a slider position means: the bottom is mute (the lowest gain), the rest is rounded to a tenth of a dB. */
    fun gainOf(slider: Float): Double =
        if (slider <= SLIDER_MIN) ClipGain.MIN_DB else (slider.coerceAtMost(SLIDER_MAX) * 10).roundToInt() / 10.0
}

/**
 * The tool row's volume button: a small slider for the selected clips only (not the whole track, which is the mixer's job),
 * with Mute and Reset. The change is made when the finger lifts, as one undo step.
 */
@Composable
internal fun ClipVolumeMenu(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    val targets = ClipVolume.soundClips(state.timeline, state.assets, state.selection)
    var open by remember { mutableStateOf(false) }
    val shownDescription = stringResource(R.string.ed_clipvol_button)
    Box {
        ToolButton(EditorIcons.Volume, shownDescription, enabled = targets.isNotEmpty()) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val current = targets.firstOrNull()?.gainDb ?: 0.0
            var slider by remember(open, current) { mutableFloatStateOf(ClipVolume.sliderOf(current)) }
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).widthIn(min = 240.dp, max = 320.dp)) {
                Text(
                    pluralStringResource(R.plurals.ed_clipvol_title, targets.size, targets.size),
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    if (slider <= ClipVolume.SLIDER_MIN) stringResource(R.string.ed_2b_readout_mute) else stringResource(R.string.ed_clipvol_db, ClipVolume.gainOf(slider)),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = slider,
                    onValueChange = { slider = it },
                    onValueChangeFinished = { onIntent(SelectionIntent.SetGroupGain(ClipVolume.gainOf(slider))) },
                    valueRange = ClipVolume.SLIDER_MIN..ClipVolume.SLIDER_MAX,
                    modifier = Modifier.semantics { contentDescription = shownDescription },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        slider = ClipVolume.SLIDER_MIN
                        onIntent(SelectionIntent.SetGroupGain(ClipGain.MIN_DB))
                    }) { Text(stringResource(R.string.ed_clipvol_mute)) }
                    TextButton(onClick = {
                        slider = 0f
                        onIntent(SelectionIntent.SetGroupGain(0.0))
                    }) { Text(stringResource(R.string.ed_clipvol_reset)) }
                }
            }
        }
    }
}

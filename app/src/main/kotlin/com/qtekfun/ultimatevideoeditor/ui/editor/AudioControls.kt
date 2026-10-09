package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.ui.res.pluralStringResource
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.AudioRole
import com.qtekfun.ultimatevideoeditor.domain.BusCompressor
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipAudio
import com.qtekfun.ultimatevideoeditor.domain.ClipEq
import com.qtekfun.ultimatevideoeditor.domain.Ducking
import com.qtekfun.ultimatevideoeditor.domain.EqBand
import com.qtekfun.ultimatevideoeditor.domain.FadeShape
import com.qtekfun.ultimatevideoeditor.domain.paramKeys
import com.qtekfun.ultimatevideoeditor.domain.ParamIds
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackAudio
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.VoiceFx
import com.qtekfun.ultimatevideoeditor.domain.VoicePreset
import com.qtekfun.ultimatevideoeditor.domain.VoiceSlider
import com.qtekfun.ultimatevideoeditor.engine.audio.PeakLevels
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------------------------
// Clip sound tools (inspector)
// ---------------------------------------------------------------------------------------------

/**
 * Sound tools of the selected media clip: pan, fade handles, EQ, noise suppression and loudness
 * normalisation. Sliders are heard live and become one undo step when released.
 */
@Composable
internal fun AudioControls(state: EditorState, clip: Clip, onIntent: (EditorIntent) -> Unit) {
    // Fades and the volume curve are what people look for first on an audio lane, so its clips open the tools.
    var expanded by remember(clip.id) { mutableStateOf(state.timeline.trackOfClip(clip.id)?.type == TrackType.AUDIO) }
    val audio = clip.audio
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ed_2b_sound_tools), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { onIntent(EditorIntent.ToggleMixer) }) { Text(stringResource(R.string.ed_2a_tool_mixer)) }
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) stringResource(R.string.common_hide) else stringResource(R.string.ed_2b_show)) }
    }
    if (!expanded) {
        if (!audio.isNeutral) Text(summaryOf(audio), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    val end = EditorIntent.EndAudioEdit(commit = true)
    fun change(next: ClipAudio) = onIntent(EditorIntent.UpdateClipAudio(next))
    fun commit(next: ClipAudio) {
        onIntent(EditorIntent.UpdateClipAudio(next))
        onIntent(end)
    }

    // Pan.
    InspectorSlider(stringResource(R.string.ed_2b_pan), audio.pan.toFloat(), -1f..1f, panReadout(audio.pan), onIntent, end, paramId = ParamIds.PAN) {
        change(audio.copy(pan = it.toDouble()))
    }

    // Fade handles, in seconds.
    val fpsValue = state.fps.num.toDouble() / state.fps.den
    val maxFade = min(clip.durationFrames.toDouble(), fpsValue * MAX_FADE_SECONDS).coerceAtLeast(1.0).toFloat()
    InspectorSlider(stringResource(R.string.ed_2b_fade_in), audio.fadeInFrames.toFloat().coerceIn(0f, maxFade), 0f..maxFade, fadeReadout(audio.fadeInFrames, fpsValue), onIntent, end) {
        change(audio.copy(fadeInFrames = it.roundToInt().toLong()))
    }
    InspectorSlider(stringResource(R.string.ed_2b_fade_out), audio.fadeOutFrames.toFloat().coerceIn(0f, maxFade), 0f..maxFade, fadeReadout(audio.fadeOutFrames, fpsValue), onIntent, end) {
        change(audio.copy(fadeOutFrames = it.roundToInt().toLong()))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        Text(stringResource(R.string.ed_2b_fade_curve), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
        for (shape in FadeShape.entries) {
            FilterChip(
                selected = audio.fadeShape == shape,
                onClick = { commit(audio.copy(fadeShape = shape)) },
                label = { Text(stringResource(shape.labelRes())) },
                modifier = Modifier.described(stringResource(R.string.ed_2b_fade_curve_2, stringResource(shape.labelRes()))),
            )
        }
    }

    // Volume curve: points on the clip (drawn over its waveform), also edited on the timeline.
    val curvePoints = clip.paramKeys(ParamIds.GAIN_DB).size
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            if (curvePoints == 0) stringResource(R.string.ed_2b_volume_curve) else stringResource(R.string.ed_2b_volume_curve_points, curvePoints),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onIntent(AudioShapeIntent.AddPointAtPlayhead) }) { Text(stringResource(R.string.ed_2b_add_point_at_playhead)) }
        TextButton(onClick = { onIntent(EditorIntent.ClearParamTrack(ParamIds.GAIN_DB)) }, enabled = curvePoints > 0) { Text(stringResource(R.string.common_clear)) }
    }
    Text(
        stringResource(R.string.ed_2b_timeline_hint),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // EQ.
    EqControls(audio, ::change, ::commit, onIntent)

    // Noise suppression.
    NoiseControls(state, clip, onIntent)

    // Voice effects (pitch, whisper, robot, echo, reverb, ...).
    VoiceControls(clip, onIntent)

    // Loudness.
    LoudnessControls(state, audio, onIntent)

    Row(verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onIntent(EditorIntent.ResetClipAudio) }, enabled = !audio.isNeutral) { Text(stringResource(R.string.ed_2b_reset_sound)) }
    }
}

@Composable
private fun EqControls(audio: ClipAudio, change: (ClipAudio) -> Unit, commit: (ClipAudio) -> Unit, onIntent: (EditorIntent) -> Unit) {
    val eq = audio.eq
    val end = EditorIntent.EndAudioEdit(commit = true)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ed_2b_equaliser), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { commit(audio.copy(eq = ClipEq.FLAT)) }, enabled = !eq.isFlat) { Text(stringResource(R.string.ed_2b_flat)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ed_2b_low_cut), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
        for (hz in HIGH_PASS_CHOICES) {
            FilterChip(
                selected = eq.highPassHz == hz,
                onClick = { commit(audio.copy(eq = eq.copy(highPassHz = hz))) },
                label = { Text(if (hz == 0.0) stringResource(R.string.ed_2b_off) else hertz(hz)) },
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ed_2b_high_cut), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
        for (hz in LOW_PASS_CHOICES) {
            FilterChip(
                selected = eq.lowPassHz == hz,
                onClick = { commit(audio.copy(eq = eq.copy(lowPassHz = hz))) },
                label = { Text(if (hz == 0.0) stringResource(R.string.ed_2b_off) else hertz(hz)) },
            )
        }
    }
    eq.bands.forEachIndexed { index, band ->
        InspectorSlider(
            label = stringResource(BAND_LABELS[index]),
            value = band.gainDb.toFloat(),
            range = EqBand.MIN_GAIN_DB.toFloat()..EqBand.MAX_GAIN_DB.toFloat(),
            readout = "${signedDb(band.gainDb)} dB",
            onIntent = onIntent,
            finish = end,
            paramId = ParamIds.eqGain(index),
        ) { change(audio.copy(eq = eq.withBand(index, band.copy(gainDb = it.toDouble().let { v -> (v * TENTH).roundToInt() / TENTH })))) }
    }
}

@Composable
private fun NoiseControls(state: EditorState, clip: Clip, onIntent: (EditorIntent) -> Unit) {
    val audio = clip.audio
    val denoise = audio.denoise
    val region = state.noiseRegion?.takeIf { it.clipId == clip.id }
    val fpsValue = state.fps.num.toDouble() / state.fps.den
    var strength by remember(clip.id) { mutableFloatStateOf((denoise?.strength ?: DEFAULT_STRENGTH).toFloat()) }
    val busy = state.audioBusy != null

    Text(stringResource(R.string.ed_2b_noise_suppression), style = MaterialTheme.typography.titleSmall)
    Text(
        text = when {
            denoise != null -> stringResource(R.string.ed_2b_noise_on_at, (denoise.strength * PERCENT).roundToInt())
            region == null || (region.startFrame == null && region.endFrame == null) ->
                stringResource(R.string.ed_2b_noise_park)
            else -> stringResource(R.string.ed_2b_noise_quiet_stretch, region.startFrame?.let { seconds(it / fpsValue) } ?: "?", region.endFrame?.let { seconds(it / fpsValue) } ?: "?")
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onIntent(EditorIntent.MarkNoiseRegion(atStart = true)) }, enabled = !busy) { Text(stringResource(R.string.ed_2b_mark_start)) }
        TextButton(onClick = { onIntent(EditorIntent.MarkNoiseRegion(atStart = false)) }, enabled = !busy) { Text(stringResource(R.string.ed_2b_mark_end)) }
        TextButton(onClick = { onIntent(EditorIntent.ClearNoiseRegion) }, enabled = region != null && !busy) { Text(stringResource(R.string.common_clear)) }
    }
    val end = EditorIntent.EndAudioEdit(commit = true)
    InspectorSlider(stringResource(R.string.ed_2b_strength), strength, STRENGTH_MIN..1f, "${(strength * PERCENT).roundToInt()}%", onIntent, end) {
        strength = it
        // With the profile already measured the strength follows the slider live; otherwise it is used on Analyse.
        if (denoise != null) onIntent(EditorIntent.UpdateClipAudio(audio.copy(denoise = denoise.copy(strength = it.toDouble()))))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = { onIntent(EditorIntent.AnalyzeNoise(strength.toDouble())) },
            enabled = !busy && region?.isComplete == true,
        ) { Text(if (denoise == null) stringResource(R.string.ed_2b_remove_noise) else stringResource(R.string.ed_2b_measure_again)) }
        TextButton(onClick = { onIntent(EditorIntent.RemoveNoiseSuppression) }, enabled = denoise != null && !busy) { Text(stringResource(R.string.ed_2b_turn_off)) }
    }
}

/**
 * Voice effects of the selected clip: a preset (pitch, whisper, robot, echo, reverb, ...) and the one to three
 * sliders it exposes. The engine reads the clip again when an effect changes, so a slider applies when it is
 * released (one undo step) instead of re-reading the clip on every tick of the drag.
 */
@Composable
private fun VoiceControls(clip: Clip, onIntent: (EditorIntent) -> Unit) {
    val audio = clip.audio
    val voice = audio.voice
    val end = EditorIntent.EndAudioEdit(commit = true)
    fun commit(next: ClipAudio) {
        onIntent(EditorIntent.UpdateClipAudio(next))
        onIntent(end)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.ed_2b_voice_effects), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        TextButton(onClick = { commit(audio.copy(voice = null)) }, enabled = voice != null) { Text(stringResource(R.string.ed_2b_off)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        for (preset in VoicePreset.entries) {
            FilterChip(
                selected = voice?.preset == preset,
                onClick = { commit(audio.copy(voice = preset.defaults())) },
                label = { Text(stringResource(preset.labelRes())) },
                modifier = Modifier.described(stringResource(R.string.ed_2b_voice_effect, stringResource(preset.labelRes()))),
            )
        }
    }
    if (voice == null) {
        Text(
            stringResource(R.string.ed_2b_change_how_this_clip_s),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    voice.preset.sliders.forEachIndexed { index, slider ->
        VoiceSliderRow(clip.id, voice, index, slider, ParamIds.voice(index), onIntent) { commit(audio.copy(voice = it)) }
    }
    Text(
        stringResource(R.string.ed_2b_the_clip_is_read_again),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun VoiceSliderRow(
    clipId: String,
    voice: VoiceFx,
    index: Int,
    slider: VoiceSlider,
    paramId: String,
    onIntent: (EditorIntent) -> Unit,
    onCommit: (VoiceFx) -> Unit,
) {
    var local by remember(clipId, voice.preset, voice.values[index]) { mutableFloatStateOf(voice.values[index].toFloat()) }
    val readout = voiceReadout(slider, local.toDouble())
    val sliderDescription = "${voiceSliderName(slider.name)} $readout"
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(voiceSliderName(slider.name), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
        Slider(
            value = local.coerceIn(slider.min.toFloat(), slider.max.toFloat()),
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(voice.with(index, local.toDouble())) },
            valueRange = slider.min.toFloat()..slider.max.toFloat(),
            modifier = Modifier.weight(1f).semantics { contentDescription = sliderDescription },
        )
        Text(readout, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
        // The diamond keys the slider at the playhead; once it has keys the slider shows the value at the playhead.
        val keyUi = LocalParamKeys.current(paramId)
        if (keyUi != null) KeyDiamond(keyUi, onIntent)
    }
}

/** "+3.0 st", "280 ms", "60%": a slider's value with its unit; fractions of 1 read as percent. */
internal fun voiceReadout(slider: VoiceSlider, value: Double): String = when {
    slider.unit.isEmpty() -> "${(value * PERCENT).roundToInt()}%"
    slider.unit == "st" || slider.unit == "dB" -> "${signedDb(value)} ${slider.unit}"
    else -> "${value.roundToInt()} ${slider.unit}"
}

@Composable
private fun LoudnessControls(state: EditorState, audio: ClipAudio, onIntent: (EditorIntent) -> Unit) {
    val busy = state.audioBusy != null
    Text(stringResource(R.string.ed_2b_loudness), style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        for ((lufs, labelRes) in LOUDNESS_TARGETS) {
            val label = stringResource(labelRes)
            FilterChip(
                selected = audio.targetLufs == lufs,
                enabled = !busy,
                onClick = { onIntent(EditorIntent.NormalizeLoudness(lufs)) },
                label = { Text(label) },
                modifier = Modifier.described(stringResource(R.string.ed_2b_normalise_to_lufs, lufs.roundToInt(), label)),
            )
        }
    }
    val target = audio.targetLufs
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (target != null) stringResource(R.string.ed_2b_normalised_to_lufs_db, target.roundToInt(), signedDb(audio.normalizeDb)) else stringResource(R.string.ed_2b_not_normalised),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { onIntent(EditorIntent.ClearNormalize) }, enabled = target != null && !busy) { Text(stringResource(R.string.common_clear)) }
    }
    val message = state.audioBusy
    if (message != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
            Text(message, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onIntent(EditorIntent.CancelAudioAnalysis) }) { Text(stringResource(R.string.common_cancel)) }
        }
    }
}

@Composable
private fun summaryOf(audio: ClipAudio): String = buildList {
    if (audio.pan != 0.0) add(stringResource(R.string.ed_2b_sum_pan, panReadout(audio.pan)))
    if (audio.fadeInFrames > 0 || audio.fadeOutFrames > 0) add(stringResource(R.string.ed_2b_sum_fades))
    if (!audio.eq.isFlat) add(stringResource(R.string.ed_2b_sum_eq))
    if (audio.denoise != null) add(stringResource(R.string.ed_2b_sum_noise))
    audio.voice?.let { add(stringResource(R.string.ed_2b_sum_voice, stringResource(it.preset.labelRes()))) }
    if (audio.targetLufs != null) add(stringResource(R.string.ed_2b_sum_normalised))
}.joinToString(" · ")

// ---------------------------------------------------------------------------------------------
// Track mixer
// ---------------------------------------------------------------------------------------------

/** "V1" for the base video track, "V2" above it, "A1" for the first audio track, in display order. */
internal fun mixerTrackLabel(timeline: Timeline, track: Track): String {
    val sameType = timeline.tracks.filter { it.type == track.type }
    val at = sameType.indexOfFirst { it.id == track.id }
    return when (track.type) {
        TrackType.VIDEO -> "V${sameType.size - at}"
        TrackType.AUDIO -> "A${at + 1}"
        TrackType.TITLE -> "T${at + 1}"
    }
}

/** Tracks that can carry sound: video tracks (embedded audio) and audio tracks. */
internal fun mixerTracks(timeline: Timeline): List<Track> = timeline.tracks.filter { it.type != TrackType.TITLE }

/**
 * The track mixer: volume, mute, solo, role (voice or music, for ducking) and a bus compressor
 * per track, plus the ducking settings. Sliders are heard live and become one undo step on release.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MixerSheet(state: EditorState, onIntent: (EditorIntent) -> Unit) {
    ModalBottomSheet(
        onDismissRequest = { onIntent(EditorIntent.ToggleMixer) },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        val timeline = state.visibleTimeline
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ed_2a_tool_mixer), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onIntent(EditorIntent.ToggleMixer) }) { Text(stringResource(R.string.ed_2a_done)) }
            }
            for (track in mixerTracks(timeline)) {
                MixerTrackRow(timeline, track, onIntent)
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            }
            DuckingControls(timeline, onIntent)
        }
    }
}

@Composable
private fun MixerTrackRow(timeline: Timeline, track: Track, onIntent: (EditorIntent) -> Unit) {
    val a = track.audio
    val label = mixerTrackLabel(timeline, track)
    val end = EditorIntent.EndAudioEdit(commit = true)
    fun change(next: TrackAudio) = onIntent(EditorIntent.UpdateTrackAudio(track.id, next))
    fun commit(next: TrackAudio) {
        change(next)
        onIntent(end)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(34.dp))
        FilterChip(
            selected = a.mute,
            onClick = { commit(a.copy(mute = !a.mute)) },
            label = { Text(stringResource(R.string.ed_2a_mute)) },
            modifier = Modifier.described(stringResource(R.string.ed_2b_mute_track, label)),
        )
        FilterChip(
            selected = a.solo,
            onClick = { commit(a.copy(solo = !a.solo)) },
            label = { Text(stringResource(R.string.ed_2b_solo)) },
            modifier = Modifier.described(stringResource(R.string.ed_2b_solo_track, label)),
        )
        Text(pluralStringResource(R.plurals.ed_2b_clips_count, track.clips.size, track.clips.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ed_2b_volume), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
        Slider(
            value = a.volumeDb.toFloat().coerceIn(VOLUME_MIN, VOLUME_MAX),
            onValueChange = { change(a.copy(volumeDb = (it.toDouble() * TENTH).roundToInt() / TENTH)) },
            onValueChangeFinished = { onIntent(end) },
            valueRange = VOLUME_MIN..VOLUME_MAX,
            modifier = Modifier.weight(1f).described(stringResource(R.string.ed_2b_volume_of_track_db, label, signedDb(a.volumeDb))),
        )
        Text(stringResource(R.string.ed_2b_db, signedDb(a.volumeDb)), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ed_2b_role), style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(60.dp))
        for (role in ROLE_CHOICES) {
            val name = stringResource(role.labelRes())
            FilterChip(selected = a.role == role, onClick = { commit(a.copy(role = role)) }, label = { Text(name) })
        }
    }
    val comp = a.compressor
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ed_2b_compressor), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Switch(checked = comp != null, onCheckedChange = { commit(a.copy(compressor = if (it) BusCompressor() else null)) })
    }
    if (comp != null) {
        MixerSlider(stringResource(R.string.ed_2b_threshold), comp.thresholdDb.toFloat(), -60f..0f, "${comp.thresholdDb.roundToInt()} dB", onIntent) {
            change(a.copy(compressor = comp.copy(thresholdDb = it.roundToInt().toDouble())))
        }
        MixerSlider(stringResource(R.string.ed_2b_ratio), comp.ratio.toFloat(), 1f..10f, "${"%.1f".format(Locale.US, comp.ratio)}:1", onIntent) {
            change(a.copy(compressor = comp.copy(ratio = (it * TENTH).roundToInt() / TENTH)))
        }
        MixerSlider(stringResource(R.string.ed_2b_make_up), comp.makeupDb.toFloat(), 0f..12f, "+${"%.1f".format(Locale.US, comp.makeupDb)} dB", onIntent) {
            change(a.copy(compressor = comp.copy(makeupDb = (it * TENTH).roundToInt() / TENTH)))
        }
    }
}

@Composable
private fun DuckingControls(timeline: Timeline, onIntent: (EditorIntent) -> Unit) {
    val ducking = timeline.ducking
    val end = EditorIntent.EndAudioEdit(commit = true)
    val tracks = mixerTracks(timeline)
    val hasVoice = tracks.any { it.audio.role == AudioRole.VOICE }
    val hasMusic = tracks.any { it.audio.role == AudioRole.MUSIC }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.ed_2b_duck_music_under_voice), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        Switch(
            checked = ducking != null,
            onCheckedChange = {
                onIntent(EditorIntent.UpdateDucking(if (it) Ducking() else null))
                onIntent(end)
            },
        )
    }
    Text(
        text = when {
            ducking == null -> stringResource(R.string.ed_2b_duck_off)
            !hasVoice || !hasMusic -> stringResource(R.string.ed_2b_duck_roles)
            else -> stringResource(R.string.ed_2b_duck_on, ducking.amountDb.roundToInt())
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (ducking != null) {
        fun change(next: Ducking) = onIntent(EditorIntent.UpdateDucking(next))
        MixerSlider(stringResource(R.string.ed_2b_amount), ducking.amountDb.toFloat(), 1f..24f, "${ducking.amountDb.roundToInt()} dB", onIntent) {
            change(ducking.copy(amountDb = it.roundToInt().toDouble()))
        }
        MixerSlider(stringResource(R.string.ed_2b_trigger), ducking.thresholdDb.toFloat(), -60f..-15f, "${ducking.thresholdDb.roundToInt()} dB", onIntent) {
            change(ducking.copy(thresholdDb = it.roundToInt().toDouble()))
        }
        MixerSlider(stringResource(R.string.ed_2b_recovery), ducking.releaseMs.toFloat(), 100f..1500f, "${ducking.releaseMs.roundToInt()} ms", onIntent) {
            change(ducking.copy(releaseMs = it.roundToInt().toDouble()))
        }
    }
}

@Composable
private fun MixerSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, readout: String, onIntent: (EditorIntent) -> Unit, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(72.dp))
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            onValueChangeFinished = { onIntent(EditorIntent.EndAudioEdit(commit = true)) },
            valueRange = range,
            modifier = Modifier.weight(1f).semantics { contentDescription = "$label $readout" },
        )
        Text(readout, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(64.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Level meters
// ---------------------------------------------------------------------------------------------

/**
 * Two thin peak bars (left over right) that move while [active]. [takePeaks] returns the peaks since
 * its previous call; the bar jumps up with a peak and falls back smoothly. The state lives here, not
 * in the editor state, so a level change never recomposes the editor.
 */
@Composable
internal fun LevelMeter(takePeaks: () -> PeakLevels, active: Boolean, modifier: Modifier = Modifier) {
    var left by remember { mutableFloatStateOf(0f) }
    var right by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(active) {
        if (!active) {
            left = 0f
            right = 0f
            takePeaks() // drop what accumulated while it was not shown
            return@LaunchedEffect
        }
        while (true) {
            val p = takePeaks()
            left = max(meterLevel(p.left), left * METER_FALL)
            right = max(meterLevel(p.right), right * METER_FALL)
            delay(METER_FRAME_MILLIS)
        }
    }
    Canvas(
        modifier = modifier
            .width(METER_WIDTH)
            .height(METER_HEIGHT)
            .described(stringResource(R.string.ed_2b_output_level)),
    ) {
        val barHeight = size.height / 2f - 1f
        fun bar(level: Float, top: Float) {
            drawRect(Color(0x33FFFFFF), Offset(0f, top), Size(size.width, barHeight))
            val w = size.width * level.coerceIn(0f, 1f)
            val color = when {
                level > METER_RED -> Color(0xFFFF453A)
                level > METER_YELLOW -> Color(0xFFFFD60A)
                else -> Color(0xFF30D158)
            }
            drawRect(color, Offset(0f, top), Size(w, barHeight))
        }
        bar(left, 0f)
        bar(right, size.height / 2f + 1f)
    }
}

/** A linear peak as 0..1 on a -60..0 dBFS scale. */
internal fun meterLevel(peak: Float): Float {
    if (peak <= 0f) return 0f
    val db = 20f * log10(peak)
    return ((db - METER_FLOOR_DB) / -METER_FLOOR_DB).coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------------------------
// Formatting and constants
// ---------------------------------------------------------------------------------------------

internal fun panReadout(pan: Double): String = when {
    pan > PAN_EPS -> "R ${(pan * PERCENT).roundToInt()}"
    pan < -PAN_EPS -> "L ${(-pan * PERCENT).roundToInt()}"
    else -> "C"
}

internal fun signedDb(db: Double): String = String.format(Locale.US, "%+.1f", db).let { if (db == 0.0) "0.0" else it }

internal fun seconds(value: Double): String = String.format(Locale.US, "%.1f s", value)

/** A fade length as the sheet shows it: seconds to the hundredth and the exact frame count. */
internal fun fadeReadout(frames: Long, fps: Double): String = String.format(Locale.US, "%.2f s (%d f)", frames / fps, frames)

internal fun hertz(hz: Double): String = if (hz >= KILO) String.format(Locale.US, "%.0fk", hz / KILO) else String.format(Locale.US, "%.0f", hz)

private const val PAN_EPS = 0.005
private const val PERCENT = 100.0
private const val TENTH = 10.0
private const val KILO = 1000.0
private const val MAX_FADE_SECONDS = 5.0
private const val DEFAULT_STRENGTH = 0.6
private const val STRENGTH_MIN = 0.1f
private const val VOLUME_MIN = -60f
private const val VOLUME_MAX = 12f
private const val METER_WIDTH_DP = 54
private const val METER_HEIGHT_DP = 8
private val METER_WIDTH = METER_WIDTH_DP.dp
private val METER_HEIGHT = METER_HEIGHT_DP.dp
private const val METER_FLOOR_DB = -60f
private const val METER_FALL = 0.82f
private const val METER_YELLOW = 0.9f
private const val METER_RED = 0.985f
private const val METER_FRAME_MILLIS = 33L

private val HIGH_PASS_CHOICES = listOf(0.0, 80.0, 120.0, 200.0)
private val LOW_PASS_CHOICES = listOf(0.0, 8000.0, 12000.0, 16000.0)
private val BAND_LABELS = listOf(R.string.ed_2b_band_low, R.string.ed_2b_band_mid, R.string.ed_2b_band_mid_high, R.string.ed_2b_band_high, R.string.ed_2b_band_air)
private val LOUDNESS_TARGETS = listOf(-23.0 to R.string.ed_2b_lufs_broadcast, -16.0 to R.string.ed_2b_lufs_online, -14.0 to R.string.ed_2b_lufs_music)
private val ROLE_CHOICES = listOf(AudioRole.NORMAL, AudioRole.VOICE, AudioRole.MUSIC)

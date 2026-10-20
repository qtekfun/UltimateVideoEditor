package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.HdrLevel
import com.qtekfun.ultimatevideoeditor.domain.HdrLevels
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace
import java.text.NumberFormat
import java.util.Locale

/** What the HDR level of a clip is doing, for the one line the inspector shows. */
internal sealed interface HdrLevelStatus {
    data object Off : HdrLevelStatus

    data object Measuring : HdrLevelStatus

    data object NoChange : HdrLevelStatus

    /** The clip is dimmed by [steps] stops (a negative number). */
    data class Dimmed(val steps: Double) : HdrLevelStatus
}

/** The status of [clip] over [asset], or null when the clip has no HDR level because its source is not HLG or PQ. */
internal fun hdrLevelStatus(clip: Clip, asset: MediaAssetDto?, hdr: HdrLevels): HdrLevelStatus? {
    asset ?: return null
    val detected = SourceColorSpace.fromId(asset.colorSpace)
    val space = clip.colorOverride ?: detected
    if (space == SourceColorSpace.SDR) return null
    if (!hdr.wantsAnalysis(clip.hdrAutoLevel)) return HdrLevelStatus.Off
    if (hdr.levelOf(asset.uri, asset.durationFrames, space) == null) return HdrLevelStatus.Measuring
    val gain = hdr.gainOf(clip.hdrAutoLevel, asset.uri, asset.durationFrames, detected, clip.colorOverride)
    return if (gain >= 1.0) HdrLevelStatus.NoChange else HdrLevelStatus.Dimmed(HdrLevel.stepsOf(gain))
}

/** One discreet line, "HDR level: -0.8 steps (auto)", with a switch that turns the level off for this clip. */
@Composable
internal fun HdrLevelControls(clip: Clip, asset: MediaAssetDto?, hdr: HdrLevels, onIntent: (EditorIntent) -> Unit) {
    val status = hdrLevelStatus(clip, asset, hdr) ?: return
    val text = when (status) {
        HdrLevelStatus.Off -> stringResource(R.string.ed_2b_hdr_level_off)
        HdrLevelStatus.Measuring -> stringResource(R.string.ed_2b_hdr_level_measuring)
        HdrLevelStatus.NoChange -> stringResource(R.string.ed_2b_hdr_level_none)
        is HdrLevelStatus.Dimmed -> stringResource(R.string.ed_2b_hdr_level_steps, formatSteps(status.steps))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Switch(
            checked = status != HdrLevelStatus.Off,
            onCheckedChange = { on -> onIntent(EditorIntent.SetClipHdrLevel(on)) },
            modifier = Modifier.described(stringResource(R.string.ed_2b_hdr_level_switch)),
        )
    }
}

/** "−0.8" in the language in use (a true minus sign, one decimal). */
internal fun formatSteps(steps: Double, locale: Locale = Locale.getDefault()): String {
    val format = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 1; maximumFractionDigits = 1 }
    return (if (steps < 0) "−" else "") + format.format(kotlin.math.abs(steps))
}

package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace

/**
 * How one clip's source colour is read. "Auto" trusts the file's metadata (shown), the other chips
 * force SDR, HLG or PQ for this clip only; the project's colour space stays the working and export space and
 * every clip is converted to it individually.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun ClipColorControls(
    detected: SourceColorSpace,
    override: SourceColorSpace?,
    projectSpace: ProjectColorSpace,
    onIntent: (EditorIntent) -> Unit,
) {
    Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.ed_2b_source_colour), style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = override == null,
                onClick = { onIntent(EditorIntent.SetClipColor(null)) },
                label = { Text(stringResource(R.string.ed_2b_auto, detected.label)) },
            )
            for (space in SourceColorSpace.entries) {
                FilterChip(
                    selected = override == space,
                    onClick = { onIntent(EditorIntent.SetClipColor(space)) },
                    label = { Text(space.label) },
                )
            }
        }
        Text(
            conversionNote(override ?: detected, projectSpace).asString(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line saying what the engine does with a clip of [source] in a project of [project]. */
internal fun conversionNote(source: SourceColorSpace, project: ProjectColorSpace): UiText = UiText.res(when {
    !project.isHdr && source == SourceColorSpace.SDR -> R.string.ed_2b_conv_sdr_sdr
    !project.isHdr && source == SourceColorSpace.HLG -> R.string.ed_2b_conv_hlg_sdr
    !project.isHdr -> R.string.ed_2b_conv_pq_sdr
    source == SourceColorSpace.SDR -> R.string.ed_2b_conv_sdr_hlg
    source == SourceColorSpace.HLG -> R.string.ed_2b_conv_hlg_hlg
    else -> R.string.ed_2b_conv_pq_hlg
})

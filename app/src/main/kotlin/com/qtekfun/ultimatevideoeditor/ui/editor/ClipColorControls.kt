package com.qtekfun.ultimatevideoeditor.ui.editor

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
        Text("Source colour", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = override == null,
                onClick = { onIntent(EditorIntent.SetClipColor(null)) },
                label = { Text("Auto (${detected.label})") },
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
            conversionNote(override ?: detected, projectSpace),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One line saying what the engine does with a clip of [source] in a project of [project]. */
internal fun conversionNote(source: SourceColorSpace, project: ProjectColorSpace): String = when {
    !project.isHdr && source == SourceColorSpace.SDR -> "Used as is in this SDR project."
    !project.isHdr && source == SourceColorSpace.HLG -> "HLG is tone-mapped to SDR Rec.709 in this project."
    !project.isHdr -> "PQ is tone-mapped to SDR Rec.709 in this project."
    source == SourceColorSpace.SDR -> "SDR is lifted into this HLG project (reference white at 203 nit)."
    source == SourceColorSpace.HLG -> "HLG is used as is in this HLG project."
    else -> "PQ is converted to HLG in this project (clipped at 1000 nit)."
}

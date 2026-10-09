package com.qtekfun.ultimatevideoeditor.ui.hub

import com.qtekfun.ultimatevideoeditor.ui.text.asString
import androidx.compose.ui.res.stringResource
import com.qtekfun.ultimatevideoeditor.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** Windows at least this wide show the New project form as a dialog; narrower ones as a bottom sheet. */
private const val WIDE_WINDOW_DP = 600

/**
 * The New project form: a name, a row of one-tap presets, four selectors (aspect ratio, resolution, frame
 * rate, colour space) that each show only their current value, a one-line summary, and a single Create button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NewProjectSheet(
    draft: NewProjectDraft,
    nameTaken: Boolean,
    onIntent: (HubIntent) -> Unit,
    onPickClip: () -> Unit,
) {
    val dismiss = { onIntent(HubIntent.DismissDialogs) }
    val form: @Composable () -> Unit = { NewProjectForm(draft, nameTaken, onIntent, onPickClip) }
    if (LocalConfiguration.current.screenWidthDp >= WIDE_WINDOW_DP) {
        Dialog(onDismissRequest = dismiss) {
            Surface(shape = RoundedCornerShape(28.dp), tonalElevation = 6.dp) { form() }
        }
    } else {
        ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) { form() }
    }
}

@Composable
private fun NewProjectForm(
    draft: NewProjectDraft,
    nameTaken: Boolean,
    onIntent: (HubIntent) -> Unit,
    onPickClip: () -> Unit,
) {
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.hub_new_project), style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = draft.name,
            onValueChange = { onIntent(HubIntent.DraftNameChanged(it)) },
            label = { Text(stringResource(R.string.hub_field_name)) },
            singleLine = true,
            isError = nameTaken,
            supportingText = if (nameTaken) ({ Text(stringResource(R.string.hub_name_taken)) }) else null,
            modifier = Modifier.fillMaxWidth(),
        )

        QuickPresets(draft, onIntent)

        if (draft.startMode == StartMode.MATCH_FIRST_CLIP) MatchFromClipRow(draft, onPickClip)

        Selector(
            label = stringResource(R.string.hub_aspect_ratio),
            value = stringResource(draft.aspect.labelRes),
            options = ProjectPresets.aspects,
            optionLabel = { stringResource(it.labelRes) },
            onSelect = { onIntent(HubIntent.DraftAspectSelected(it)) },
        )

        if (draft.aspect.custom) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(stringResource(R.string.hub_width_px), draft.customWidth, { onIntent(HubIntent.DraftCustomWidthChanged(it)) }, Modifier.weight(1f))
                NumberField(stringResource(R.string.hub_height_px), draft.customHeight, { onIntent(HubIntent.DraftCustomHeightChanged(it)) }, Modifier.weight(1f))
            }
        } else {
            Selector(
                label = stringResource(R.string.hub_resolution),
                value = stringResource(draft.tier.labelRes),
                options = ProjectPresets.tiers,
                optionLabel = { stringResource(it.labelRes) },
                onSelect = { onIntent(HubIntent.DraftTierSelected(it)) },
            )
            if (draft.tier.custom) {
                NumberField(stringResource(R.string.hub_short_side_px), draft.customShortSide, { onIntent(HubIntent.DraftCustomShortSideChanged(it)) }, Modifier.fillMaxWidth())
            }
        }
        val problem = draft.sizeProblem
        Text(
            text = problem?.asString() ?: stringResource(R.string.hub_size_pixels, sizeLabel(draft.width, draft.height)),
            style = MaterialTheme.typography.bodySmall,
            color = if (problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Selector(
            label = stringResource(R.string.hub_frame_rate),
            value = stringResource(R.string.fps_value, draft.fps.label),
            options = if (draft.fps in ProjectPresets.fps) ProjectPresets.fps else listOf(draft.fps) + ProjectPresets.fps,
            optionLabel = { stringResource(R.string.fps_value, it.label) },
            onSelect = { onIntent(HubIntent.DraftFpsSelected(it)) },
        )

        Selector(
            label = stringResource(R.string.hub_colour_space),
            value = draft.colorSpace.label,
            options = ProjectPresets.colorSpaces,
            optionLabel = { it.label },
            onSelect = { onIntent(HubIntent.DraftColorSpaceSelected(it)) },
        )
        Text(
            stringResource(R.string.hub_colour_space_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SummaryRow(draft)

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { onIntent(HubIntent.DismissDialogs) }) { Text(stringResource(R.string.common_cancel)) }
            Button(
                onClick = { onIntent(HubIntent.ConfirmCreate) },
                enabled = draft.canCreate && !nameTaken,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(stringResource(R.string.common_create)) }
        }
    }
}

/** One-tap starting points in a single scrolling row; the chip that matches the current selectors is highlighted. */
@Composable
private fun QuickPresets(draft: NewProjectDraft, onIntent: (HubIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.hub_quick_start), style = MaterialTheme.typography.labelLarge)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ProjectPresets.quick.forEach { preset ->
                val selected = if (preset.matchFirstClip) {
                    draft.startMode == StartMode.MATCH_FIRST_CLIP
                } else {
                    draft.startMode == StartMode.BLANK && !draft.aspect.custom && !draft.tier.custom &&
                        preset.aspect == draft.aspect && preset.tier == draft.tier && preset.fps == draft.fps && preset.colorSpace == draft.colorSpace
                }
                FilterChip(
                    selected = selected,
                    onClick = { onIntent(HubIntent.DraftQuickPreset(preset)) },
                    label = { Text(stringResource(preset.labelRes)) },
                )
            }
        }
    }
}

/** "Match first clip": pick a video or photo and the project takes its size, frame rate and colour space. */
@Composable
private fun MatchFromClipRow(draft: NewProjectDraft, onPickClip: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onPickClip, enabled = !draft.isMatching) {
                Text(stringResource(if (draft.matchedClip == null) R.string.hub_choose_clip else R.string.hub_choose_another_clip))
            }
            if (draft.isMatching) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        val clip = draft.matchedClip
        when {
            draft.matchError != null ->
                Text(draft.matchError.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            clip != null ->
                Text(
                    stringResource(R.string.hub_copied_from, clip.displayName ?: stringResource(R.string.hub_the_clip)),
                    style = MaterialTheme.typography.bodySmall,
                )
            else ->
                Text(
                    stringResource(R.string.hub_match_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
        }
    }
}

@Composable
private fun SummaryRow(draft: NewProjectDraft) {
    val ratio = if (draft.sizeProblem == null) draft.width.toFloat() / draft.height.toFloat() else 16f / 9f
    val shapeDescription = stringResource(R.string.hub_shape_of_picture)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // A small rectangle with the shape of the picture; it is decoration, the summary text says the same.
        Box(
            modifier = Modifier
                .height(40.dp)
                .aspectRatio(ratio.coerceIn(0.3f, 3.5f), matchHeightConstraintsFirst = true)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(4.dp))
                .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                .semantics { contentDescription = shapeDescription },
        )
        Text(draft.summary.asString(), style = MaterialTheme.typography.titleSmall)
    }
}

/** A read-only field that opens a menu: it shows only its current value. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Selector(
    label: String,
    value: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: Int, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = if (value > 0) value.toString() else "",
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}


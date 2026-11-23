package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.multicam.AngleFeed
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamClip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.HdrBrightness
import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamController
import com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamSheet
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.LocalProxyIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.LocalProxyUi
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxySheetHost
import com.qtekfun.ultimatevideoeditor.ui.hub.ProjectPresets
import com.qtekfun.ultimatevideoeditor.ui.hub.aspectLabelOf
import com.qtekfun.ultimatevideoeditor.ui.library.LibraryOverlays

/** The sheets and dialogs the editor can show over everything else; each one opens from state, none holds a native view. */
@Composable
internal fun EditorSheets(
    state: EditorState,
    feedsOf: (MulticamClip, Int) -> List<AngleFeed>,
    onIntent: (EditorIntent) -> Unit,
    linkedTrees: List<String>,
    guideOpen: Boolean,
    onCloseGuide: () -> Unit,
) {
    if (state.mixerOpen) MixerSheet(state) { onIntent(it) }
    QuickEditSheets(state) { onIntent(it) }
    if (state.multicam.open) {
        MulticamSheet(
            state = state,
            group = MulticamController.groupOf(state.timeline, state.selectedClipId),
            feedsOf = feedsOf,
            onIntent = { onIntent(EditorIntent.Multicam(it)) },
        )
    }
    LibraryOverlays(state) { onIntent(it) }
    LocalProxyUi.current?.let { ProxySheetHost(it, LocalProxyIntent.current) }
    if (guideOpen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { onCloseGuide() },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                com.qtekfun.ultimatevideoeditor.ui.editor.guide.ToolbarGuideScreen(onClose = { onCloseGuide() })
            }
        }
    }
    if (state.relinkOpen && (state.missingAssets.isNotEmpty() || state.folderRelink !is FolderRelinkUi.Idle)) {
        RelinkDialog(state.missingAssets, state.folderRelink, { onIntent(it) }, linkedTrees)
    }
    if (state.leaveBlockedBySave) SaveFailedDialog(state.saveError) { onIntent(it) }
}

/** Pick another canvas shape or size from the same presets as New project. The current one is marked. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CanvasDialog(width: Int, height: Int, colorSpace: ProjectColorSpace, hdrBrightness: HdrBrightness, onIntent: (EditorIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(EditorIntent.DismissCanvasDialog) },
        title = { Text(stringResource(R.string.ed_2a_project, width, height, aspectLabelOf(width, height))) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.ed_2a_clips_keep_their_relative_position),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(stringResource(R.string.hub_colour_space), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (space in ProjectColorSpace.entries) {
                        FilterChip(
                            selected = space == colorSpace,
                            onClick = { onIntent(EditorIntent.ChangeColorSpace(space)) },
                            label = { Text(space.label) },
                        )
                    }
                }
                if (colorSpace.isHdr) {
                    Text(
                        stringResource(R.string.ed_2a_hdr_projects_note),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stringResource(R.string.ed_2a_hdr_brightness), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (option in HdrBrightness.entries) {
                            FilterChip(
                                selected = option == hdrBrightness,
                                onClick = { onIntent(EditorIntent.SetHdrBrightness(option)) },
                                label = {
                                    Text(
                                        stringResource(
                                            if (option == HdrBrightness.COMFORTABLE) R.string.ed_2a_hdr_brightness_comfortable else R.string.ed_2a_hdr_brightness_original,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                    Text(stringResource(R.string.ed_2a_hdr_brightness_note), style = MaterialTheme.typography.bodySmall)
                }
                for (group in ProjectPresets.resolutionGroups) {
                    Text(stringResource(group.titleRes), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (preset in group.presets) {
                            FilterChip(
                                selected = preset.width == width && preset.height == height,
                                onClick = { onIntent(EditorIntent.ChangeCanvas(preset.width, preset.height)) },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onIntent(EditorIntent.DismissCanvasDialog) }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** The question Paste asks when another clip is selected: paste the content (a copy at the playhead) or the style (look and sound). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun PasteChoiceDialog(choice: PasteChoice, onIntent: (EditorIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(SelectionIntent.DismissPasteChoice) },
        title = { Text(stringResource(R.string.ed_paste_choice_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.ed_paste_choice_content_desc))
                Text(pluralStringResource(R.plurals.ed_paste_choice_style_desc, choice.destinations.size, choice.destinations.size))
            }
        },
        confirmButton = {
            androidx.compose.foundation.layout.FlowRow {
                TextButton(onClick = { onIntent(SelectionIntent.PasteContent) }) { Text(stringResource(R.string.ed_paste_choice_content)) }
                TextButton(onClick = { onIntent(SelectionIntent.PasteStyle) }) { Text(stringResource(R.string.ed_paste_choice_style)) }
            }
        },
        dismissButton = { TextButton(onClick = { onIntent(SelectionIntent.DismissPasteChoice) }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

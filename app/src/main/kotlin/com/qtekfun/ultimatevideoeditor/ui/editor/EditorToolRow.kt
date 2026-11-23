package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.domain.ClipLinks
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.labelRes
import com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.LocalProxyIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.toolbar.ToolbarItem
import com.qtekfun.ultimatevideoeditor.ui.editor.toolbar.ToolbarOrder
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayTab
import com.qtekfun.ultimatevideoeditor.ui.editor.voice.VoiceOverController
import com.qtekfun.ultimatevideoeditor.ui.library.LibraryButton
import kotlinx.coroutines.flow.map

/**
 * The tool row: the tools in the order the person chose (ToolbarOrder); hidden ones wait in the More menu at the end.
 * A new tool needs its [ToolbarItem] entry, one branch of the `when` below and a `ToolbarGuide` entry; nothing else.
 */
@Composable
internal fun EditorToolRow(
    state: EditorState,
    toolbarOrder: ToolbarOrder,
    voice: VoiceOverController,
    onIntent: (EditorIntent) -> Unit,
    onImport: () -> Unit,
    onCaptions: () -> Unit,
    onOpenTray: (TrayTab) -> Unit,
    onToggleScopes: () -> Unit,
) {
    val hasSelection = state.selectedClipId != null
    val proxyIntent = LocalProxyIntent.current
    val toolbarItem: @Composable (ToolbarItem, () -> Unit) -> Unit = { item, after ->
        when (item) {
            ToolbarItem.IMPORT -> ToolButton(EditorIcons.Add, stringResource(R.string.ed_2a_import_media), enabled = !state.isImporting) { onImport(); after() }
            ToolbarItem.VOICE_OVER -> ToolButton(EditorIcons.Mic, stringResource(R.string.ed_vo_record), enabled = !voice.active) { voice.start(); after() }
            ToolbarItem.SPLIT -> ToolButton(EditorIcons.Split, stringResource(R.string.ed_2a_split_at_playhead)) { // always on: with nothing selected it cuts every lane
                onIntent(EditorIntent.SplitAtPlayhead); after()
            }
            ToolbarItem.DETACH_AUDIO -> ToolButton(
                EditorIcons.DetachAudio,
                stringResource(R.string.ed_2a_detach_audio),
                enabled = state.selectedClipId?.let { id ->
                    val hasAudio = state.assets.firstOrNull { it.id == state.timeline.trackOfClip(id)?.clip(id)?.assetId }?.hasAudio == true
                    ClipLinks.infoFor(state.timeline, id, hasAudio)?.canDetach == true
                } == true,
            ) { onIntent(EditorIntent.DetachAudio); after() }
            ToolbarItem.CLIP_VOLUME -> ClipVolumeMenu(state, onIntent)
            ToolbarItem.DELETE -> ToolButton(EditorIcons.Delete, stringResource(R.string.ed_2a_delete_the_base_track_closes), enabled = hasSelection) {
                onIntent(EditorIntent.RippleDeleteSelected); after()
            }
            ToolbarItem.MARKER -> MarkerMenu(state, onIntent)
            ToolbarItem.FLAG_LIST -> ToolButton(EditorIcons.FlagList, stringResource(R.string.flags_menu_list)) {
                onIntent(MarkerIntent.OpenList); after()
            }
            ToolbarItem.DROP_MODE -> DropModeButton(state.dropMode) { onIntent(EditorIntent.CycleDropMode) }
            ToolbarItem.SELECT_MODE -> SelectModeButton(state, onIntent)
            ToolbarItem.CLOSE_GAP -> ToolButton(EditorIcons.CloseGap, stringResource(R.string.ed_2a_close_gap_before_clip_the), enabled = hasSelection && !state.selectedClipOnBase) {
                onIntent(EditorIntent.RippleAppendSelected); after()
            }
            ToolbarItem.TITLE -> ToolButton(EditorIcons.Title, stringResource(R.string.ed_2a_add_a_title_at_the)) { onIntent(EditorIntent.AddTitle); after() }
            ToolbarItem.CAPTIONS -> ToolButton(EditorIcons.Captions, stringResource(R.string.ed_2a_captions_type_them_or_import)) { onCaptions(); after() }
            ToolbarItem.STICKERS -> ToolButton(EditorIcons.Sticker, stringResource(R.string.ed_2a_stickers_open_the_media_tray)) { onOpenTray(TrayTab.STICKERS); after() }
            ToolbarItem.TEMPLATES -> ToolButton(EditorIcons.TextTemplate, stringResource(R.string.ed_2a_titles_and_text_templates_open)) { onOpenTray(TrayTab.TEMPLATES); after() }
            ToolbarItem.QUICK_EDITS -> QuickEditMenu(state, onIntent)
            ToolbarItem.LIBRARY -> LibraryButton(onIntent)
            ToolbarItem.PROXY -> ToolButton(EditorIcons.Proxy, stringResource(R.string.ed_2a_proxy_media_small_copies_for)) {
                proxyIntent(ProxyIntent.OpenSheet); after()
            }
            ToolbarItem.MIXER -> ToolButton(EditorIcons.Mixer, stringResource(R.string.ed_2a_mixer_track_volume_mute_solo)) {
                onIntent(EditorIntent.ToggleMixer); after()
            }
            ToolbarItem.MULTICAM -> ToolButton(EditorIcons.Multicam, stringResource(R.string.ed_2a_multicam_line_up_several_cameras)) {
                onIntent(EditorIntent.Multicam(MulticamIntent.Open)); after()
            }
            ToolbarItem.SCOPES -> ToolButton(EditorIcons.Scopes, stringResource(R.string.ed_2a_video_scopes_waveform_rgb_parade)) {
                onToggleScopes(); after()
            }
            ToolbarItem.TRANSITION -> ToolButton(
                EditorIcons.Transition,
                stringResource(R.string.ed_2a_add_crossfade),
                enabled = state.transitionCut != null,
            ) { onIntent(EditorIntent.AddTransition); after() }
            ToolbarItem.ADJUST -> ToolButton(EditorIcons.Tune, stringResource(R.string.ed_2a_adjust_clip_text_position_scale), enabled = hasSelection || state.inspectorOpen) {
                onIntent(EditorIntent.ToggleInspector); after()
            }
            ToolbarItem.TRACK_CONTROLS -> TrackControls(
                state.selectedTrackLabel,
                onAdd = { onIntent(EditorIntent.AddTrack(it)) },
                onMove = { onIntent(EditorIntent.MoveSelectedTrack(it)) },
            ) {
                onIntent(EditorIntent.RemoveSelectedTrack)
            }
            ToolbarItem.CANVAS -> ToolButton(EditorIcons.CanvasFormat, stringResource(R.string.ed_2a_change_the_canvas_format_and)) {
                onIntent(EditorIntent.ShowCanvasDialog); after()
            }
            ToolbarItem.SAFE_ZONE -> SafeZoneMenu(state.safeZone) { onIntent(EditorIntent.SetSafeZone(it)) }
        }
    }
    var moreOpen by remember { mutableStateOf(false) }
    val overflow = toolbarOrder.overflow

    // Scrolls sideways when the buttons do not fit a narrow window.
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (item in toolbarOrder.visible) key(item) { toolbarItem(item) {} }
        if (overflow.isNotEmpty()) {
            Box {
                ToolButton(SelectionIcons.More, stringResource(R.string.ed_2a_more_tools, overflow.map { stringResource(it.labelRes) }.joinToString())) { moreOpen = true }
                DropdownMenu(expanded = moreOpen, onDismissRequest = { moreOpen = false }) {
                    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.widthIn(max = 260.dp).padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        for (item in overflow) key(item) { toolbarItem(item) { moreOpen = false } }
                    }
                }
            }
        }
    }
}

/** Add a video or audio track, remove the selected empty one, and show which track is selected. */
@Composable
private fun TrackControls(
    selectedLabel: String?,
    onAdd: (TrackType) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        ToolButton(EditorIcons.Layers, stringResource(R.string.ed_2a_add_track)) { menuOpen = true }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ed_2a_video_track)) }, onClick = { menuOpen = false; onAdd(TrackType.VIDEO) })
            DropdownMenuItem(text = { Text(stringResource(R.string.ed_2a_audio_track)) }, onClick = { menuOpen = false; onAdd(TrackType.AUDIO) })
        }
    }
    ToolButton(EditorIcons.Minus, stringResource(R.string.ed_2a_remove_selected_track), enabled = selectedLabel != null, onClick = onRemove)
    ToolButton(EditorIcons.LaneUp, stringResource(R.string.ed_2a_move_the_selected_lane_up), enabled = selectedLabel != null) { onMove(-1) }
    ToolButton(EditorIcons.LaneDown, stringResource(R.string.ed_2a_move_the_selected_lane_down), enabled = selectedLabel != null) { onMove(1) }
    Text(
        text = selectedLabel ?: "",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp).width(28.dp),
    )
}

/** Choose whose safe zones to outline over the preview, or none. */
@Composable
private fun SafeZoneMenu(current: SafeZonePlatform?, onSelect: (SafeZonePlatform?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        ToolButton(EditorIcons.SafeZone, stringResource(R.string.ed_2a_safe_zones_for, current?.label ?: stringResource(R.string.ed_2a_off))) { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text(stringResource(R.string.ed_2a_no_safe_zones)) }, onClick = { open = false; onSelect(null) })
            for (platform in SafeZonePlatform.entries) {
                DropdownMenuItem(
                    text = { Text(if (platform == current) "${platform.label} ✓" else platform.label) },
                    onClick = { open = false; onSelect(platform) },
                )
            }
        }
    }
}

/** Small icon-only button; [description] is read by screen readers and shown as a tooltip on long press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // The wrapper Box is the direct child of the caller's layout, so scope modifiers such as Box.align()
    // in [modifier] keep working (TooltipBox does not forward them to its root node).
    Box(modifier = modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
            tooltip = { PlainTooltip { Text(description) } },
            state = rememberTooltipState(),
        ) {
            IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(40.dp)) {
                Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(22.dp))
            }
        }
    }
}

package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.engine.audio.PeakLevels
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.BandSlots
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.DragHandle
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.EditorLayoutController
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.LayoutAction
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.LayoutState
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.PreviewTimelineLayout
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.SplitMetrics
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.handleThickness
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.rememberTicker
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyBannerHost
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleTools
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayTab
import com.qtekfun.ultimatevideoeditor.ui.editor.voice.VoiceOverController
import com.qtekfun.ultimatevideoeditor.ui.editor.voice.VoiceOverPanel

@Composable
internal fun EditorMain(
    state: EditorState,
    selectedClipVisible: Boolean,
    holder: State<EditorState>,
    viewModel: EditorViewModel,
    engine: TimelineEngine,
    preview: EditorPreview,
    editing: TimelineEditing,
    dropTarget: TimelineDropTarget,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onCaptions: () -> Unit,
    onSaveFrame: () -> Unit,
    onOpenTray: (TrayTab) -> Unit,
    layout: EditorLayoutController,
    voice: VoiceOverController,
    onUseOtherApp: () -> Unit,
    linkedTrees: List<String>,
    band: BandSlots,
    inspectorOverlay: Boolean,
    onOpenLayout: () -> Unit,
    bottomTray: @Composable () -> Unit,
    titleTools: TitleTools,
    fullscreen: FullscreenState,
    onFullscreen: (FullscreenAction) -> Unit,
    modifier: Modifier = Modifier,
    /** Output peaks since the previous call, for the level meter next to the timecode. */
    takePeaks: () -> PeakLevels = { PeakLevels.SILENT },
    /** Told where the timeline view is, so a tile dragged from the tray can be hit-tested against it. */
    trayDropBounds: TimelineTrayDrop? = null,
    /** Another project is exporting: the Export button explains that instead of opening the dialog. */
    exportBlockedBy: String? = null,
) {
    val selecting = remember(holder, viewModel) {
        object : TimelineSelecting {
            override val selectMode: Boolean get() = holder.value.selectMode
            override fun onLongPress(hit: TimelineHit) = viewModel.onIntent(SelectionIntent.LongPress(hit))
            override fun onMarquee(clipKeys: List<Long>) = viewModel.onIntent(SelectionIntent.Marquee(clipKeys))
        }
    }
    val shaping = remember(holder, viewModel) {
        object : TimelineShaping {
            override fun canDrag(hit: TimelineHit) = AudioShapeGesture.isShapeHit(hit.kind) && hit.clipKey == holder.value.selectedClipId?.let(viewModel::clipKey)
            override fun onDragStart(hit: TimelineHit) = viewModel.onIntent(AudioShapeIntent.Start(hit))
            override fun onDragMove(hit: TimelineHit) = viewModel.onIntent(AudioShapeIntent.Move(hit))
            override fun onDragEnd(commit: Boolean) = viewModel.onIntent(AudioShapeIntent.End(commit))
            override fun onDoubleTap(hit: TimelineHit) = viewModel.onIntent(AudioShapeIntent.DoubleTap(hit))
        }
    }
    var scopesOpen by remember { mutableStateOf(false) }
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    EditorSheets(state, viewModel::multicamFeeds, { viewModel.onIntent(it) }, linkedTrees, guideOpen, { guideOpen = false })
    Column(modifier = modifier) {
        // Fullscreen: only the rows above and below the preview/timeline block go (they hold no native view).
        if (!fullscreen.active) EditorTopBar(
            projectName = state.projectName,
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            exportEnabled = !state.isPlaying,
            exportDescription = if (exportBlockedBy != null) stringResource(R.string.ed_2a_export_unavailable, exportBlockedBy) else stringResource(R.string.ed_2a_export_movie),
            onBack = { viewModel.onIntent(EditorIntent.Back) },
            onHelp = { guideOpen = true },
            onLayout = onOpenLayout,
            onUndo = { viewModel.onIntent(EditorIntent.Undo) },
            onRedo = { viewModel.onIntent(EditorIntent.Redo) },
            onExport = onExport,
        )
        if (!fullscreen.active) {
            MediaBanners(state, onImportFont = titleTools.onImportFont) { viewModel.onIntent(it) }
            ProxyBannerHost()
        }

        val splitMetrics = remember { SplitMetrics() }
        val ticker = rememberTicker()
        // The preview and the timeline share what the controls leave; the share is read while measuring, so
        // dragging the handle between them re-measures the block instead of recomposing the editor.
        PreviewTimelineLayout(
            workspace = { layout.state.workspace },
            fraction = { layout.state.previewFraction },
            widthFraction = { layout.state.previewWidthFraction },
            metrics = splitMetrics,
            handleThickness = { handleThickness(layout.state.customising) },
            fullscreen = fullscreen.active,
            modifier = Modifier.fillMaxWidth().weight(1f),
            band = band,
            sideHandle = {
                // Beside the picture (side by side): a vertical bar that moves the share of the width.
                DragHandle(
                    orientation = Orientation.Horizontal,
                    customising = layout.customising,
                    description = stringResource(R.string.ed_ws_resize_preview_width),
                    onDelta = { dx ->
                        val total = splitMetrics.sideRoomPx
                        if (total > 0) {
                            val current = layout.state.previewWidthFraction
                            val next = current + dx / total
                            ticker(current, next, LayoutState.DEFAULT_PREVIEW_WIDTH_FRACTION, 0.01f)
                            layout.dispatch(LayoutAction.SetPreviewWidthFraction(next), persist = false)
                        }
                    },
                    onEnd = layout::commit,
                    onReset = { layout.dispatch(LayoutAction.ResetPreviewWidthDivider) },
                    modifier = Modifier.fillMaxSize(),
                )
            },
            preview = {
                EditorPreviewArea(state, selectedClipVisible, preview, viewModel.state, fullscreen, onFullscreen, scopesOpen, viewModel::onIntent)
            },
            handle = {
                DragHandle(
                    orientation = Orientation.Vertical,
                    customising = layout.customising,
                    description = stringResource(R.string.ed_2a_resize_the_preview_and_the),
                    onDelta = { dy ->
                        val total = splitMetrics.flexiblePx
                        if (total > 0) {
                            val current = layout.state.previewFraction
                            val next = current + dy / total
                            ticker(current, next, LayoutState.defaultPreviewFraction(layout.state.workspace), 0.01f)
                            layout.dispatch(LayoutAction.SetPreviewFraction(next), persist = false)
                        }
                    },
                    onEnd = layout::commit,
                    onReset = { layout.dispatch(LayoutAction.ResetPreviewDivider) },
                    modifier = Modifier.fillMaxSize(),
                )
            },
            chrome = {
                // Scrolls when the window is too short to show every row without starving the preview and the timeline.
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // The voice-over recorder sits right above the transport it shares the play button with, in every workspace.
                VoiceOverPanel(voice, onUseOtherApp = onUseOtherApp, onUndo = { viewModel.onIntent(EditorIntent.Undo) })
                EditorTransportRow(
                    holder = holder,
                    isPlaying = state.isPlaying,
                    takePeaks = takePeaks,
                    detailZoom = layout.detailZoom,
                    onPrevious = { viewModel.onIntent(EditorIntent.SeekPrevious) },
                    onTogglePlay = { viewModel.onIntent(EditorIntent.TogglePlay) },
                    onNext = { viewModel.onIntent(EditorIntent.SeekNext) },
                    onSaveFrame = onSaveFrame,
                    onZoomDetail = {
                        engine.toggleDetailZoom(null)
                        engine.ensureVisible(viewModel.state.value.playhead.value)
                    },
                    onFit = { engine.fitToContent() },
                )

                EditorToolRow(
                    state = state,
                    toolbarOrder = layout.toolbarOrder,
                    voice = voice,
                    onIntent = viewModel::onIntent,
                    onImport = onImport,
                    onCaptions = onCaptions,
                    onOpenTray = onOpenTray,
                    onToggleScopes = { scopesOpen = !scopesOpen },
                )
                if (state.selectMode || state.isMultiSelection) SelectionBar(state, viewModel::onIntent)
                state.pasteChoice?.let { choice -> PasteChoiceDialog(choice, viewModel::onIntent) }
                if (state.canvasDialogOpen) CanvasDialog(state.canvasWidth, state.canvasHeight, state.colorSpace, state.hdr.brightness, viewModel::onIntent)

                }
            },
            timeline = {
                EditorTimelineArea(
                    state, engine, editing, dropTarget, selecting, shaping, layout.detailZoom, inspectorOverlay, titleTools,
                    trayDropBounds, viewModel::transitionLimit, viewModel::onIntent,
                )
            },
        )
        if (!fullscreen.active) bottomTray()
    }
}

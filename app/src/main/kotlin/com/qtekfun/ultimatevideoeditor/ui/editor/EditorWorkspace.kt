package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.layout.PaddingValues
import android.content.Context
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.engine.audio.PeakLevels
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.BandSlots
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.CollapsedBottomBar
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.CollapsedStrip
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.Dock
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.DockLayout
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.DragHandle
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.EditorLayoutController
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.LayoutAction
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.LayoutSheet
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.LayoutState
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.Panel
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.PrefsLayoutStore
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.Side
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.SidePanelFrame
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.SideRequest
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.WindowMetrics
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.Workspace
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.bottomTrayShown
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.handleThickness
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.labelRes
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.rememberFoldPosture
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.rememberTicker
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.sideCollapsed
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.visiblePanels
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleTools
import com.qtekfun.ultimatevideoeditor.ui.editor.voice.VoiceOverController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.asString
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.map

/**
 * The editor's body once the project is loaded: the dock layout with the side columns, the layout sheet, and [EditorMain]
 * in the middle. EditorMain is composed at the same place whatever the docks are, so the native views are never recreated.
 */
@Composable
internal fun EditorWorkspace(
    padding: PaddingValues,
    state: EditorState,
    selectedClipVisible: Boolean,
    holder: State<EditorState>,
    viewModel: EditorViewModel,
    engine: TimelineEngine,
    preview: EditorPreview,
    editing: TimelineEditing,
    dropTarget: TimelineDropTarget,
    tray: EditorTray,
    fullscreen: FullscreenState,
    onFullscreen: (FullscreenAction) -> Unit,
    titleTools: TitleTools,
    voice: VoiceOverController,
    onUseOtherApp: () -> Unit,
    onImport: () -> Unit,
    onExport: () -> Unit,
    onCaptions: () -> Unit,
    onSaveFrame: () -> Unit,
    exportBlockedBy: String?,
    takePeaks: () -> PeakLevels,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
        val posture by rememberFoldPosture()
        val window = WindowMetrics(maxWidth.value.roundToInt().toFloat(), maxHeight.value.roundToInt().toFloat(), posture)
        val layout = remember {
            val prefs = PrefsLayoutStore(context.getSharedPreferences(PrefsLayoutStore.FILE, Context.MODE_PRIVATE))
            EditorLayoutController(prefs, window, prefs, prefs, detailZoomStore = prefs, tapPreviewStore = prefs)
        }
        // Hiding the system bars changes the window by a few dp: that must not re-pick the layout, so it waits.
        LaunchedEffect(window, fullscreen.active) { if (!fullscreen.active) layout.onWindow(window) }
        // Lane heights are the native timeline's business: it scales its lanes and what is drawn in them.
        LaunchedEffect(engine, layout) {
            snapshotFlow { layout.laneHeight to layout.audioLaneHeight }.collect { (lane, audio) -> engine.setLaneScale(lane.scale, audio.factor) }
        }
        LaunchedEffect(engine, layout) { snapshotFlow { layout.waveformScale }.collect { engine.setWaveformScale(it) } }
        var layoutSheetOpen by remember { mutableStateOf(false) }
        if (layoutSheetOpen) LayoutSheet(layout) { layoutSheetOpen = false }

        // Which panels each side column holds. Derived, so composition only reacts when a dock or the
        // inspector changes, never to the steps of a divider drag.
        val inspectorOpen = state.inspectorOpen
        val leftPanels by remember(layout, inspectorOpen) { derivedStateOf { visiblePanels(layout.state, Side.LEFT, inspectorOpen) } }
        val rightPanels by remember(layout, inspectorOpen) { derivedStateOf { visiblePanels(layout.state, Side.RIGHT, inspectorOpen) } }
        val leftCollapsed by remember(layout) { derivedStateOf { sideCollapsed(layout.state, leftPanels) } }
        val rightCollapsed by remember(layout) { derivedStateOf { sideCollapsed(layout.state, rightPanels) } }

        val sideColumn: @Composable (Side, List<Panel>, Boolean) -> Unit = { side, panels, collapsed ->
            if (panels.isNotEmpty()) {
                if (collapsed) {
                    CollapsedStrip(
                        side = side,
                        label = panels.map { stringResource(it.labelRes()).lowercase() }.reduce { left, right -> stringResource(R.string.list_and, left, right) },
                        onExpand = { panels.forEach { layout.dispatch(LayoutAction.SetCollapsed(it, false)) } },
                    )
                } else {
                    Column(modifier = Modifier.fillMaxSize()) {
                        for (panel in panels) {
                            SidePanelFrame(
                                panel = panel,
                                side = side,
                                customising = layout.customising,
                                sideDocksAllowed = layout.sideDocksAllowed,
                                onCollapse = { layout.dispatch(LayoutAction.SetCollapsed(panel, true)) },
                                onDock = { layout.dispatch(LayoutAction.SetDock(panel, it)) },
                                modifier = Modifier.weight(1f),
                            ) {
                                if (panel == Panel.TRAY) {
                                    tray.panel(false, Modifier.fillMaxSize(), layout.tapPreviews)
                                } else {
                                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
                                        InspectorPanel(
                                            state = state,
                                            onIntent = viewModel::onIntent,
                                            transitionLimit = viewModel::transitionLimit,
                                            titleTools = titleTools,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        val ticker = rememberTicker()
        val sideHandle: @Composable (Side) -> Unit = { side ->
            DragHandle(
                orientation = Orientation.Horizontal,
                customising = layout.customising,
                description = stringResource(if (side == Side.LEFT) R.string.ed_2a_resize_left_panel else R.string.ed_2a_resize_right_panel),
                onDelta = { dx ->
                    val current = if (side == Side.LEFT) layout.state.leftWidthDp else layout.state.rightWidthDp
                    val next = current + (if (side == Side.LEFT) dx else -dx) / density
                    ticker(current, next, LayoutState.DEFAULT_SIDE_WIDTH_DP, 6f)
                    layout.dispatch(LayoutAction.SetSideWidth(side, next), persist = false)
                },
                onEnd = layout::commit,
                onReset = { layout.dispatch(LayoutAction.ResetSideDivider(side)) },
                modifier = Modifier.fillMaxSize(),
            )
        }

        // One layout for every window: EditorMain is composed at the same place whatever the docks are,
        // so changing them never recreates the native timeline view (a late surfaceDestroyed of an old
        // view would tear down the surface of the new one).
        // In the workbench the side panels sit in a band beside the preview instead of in full-height columns:
        // the dock layout then has none, and the same columns are handed to the editor's middle block.
        val inBand = layout.workspace == Workspace.WORKBENCH
        val noColumn = SideRequest(false, 0f, false)
        val band = BandSlots(
            left = { SideRequest(leftPanels.isNotEmpty(), layout.state.leftWidthDp, leftCollapsed) },
            right = { SideRequest(rightPanels.isNotEmpty(), layout.state.rightWidthDp, rightCollapsed) },
            leftPanel = { if (inBand) sideColumn(Side.LEFT, leftPanels, leftCollapsed) },
            leftHandle = { if (inBand) sideHandle(Side.LEFT) },
            rightPanel = { if (inBand) sideColumn(Side.RIGHT, rightPanels, rightCollapsed) },
            rightHandle = { if (inBand) sideHandle(Side.RIGHT) },
        )
        DockLayout(
            left = { if (inBand) noColumn else SideRequest(leftPanels.isNotEmpty(), layout.state.leftWidthDp, leftCollapsed) },
            right = { if (inBand) noColumn else SideRequest(rightPanels.isNotEmpty(), layout.state.rightWidthDp, rightCollapsed) },
            handleThickness = { handleThickness(layout.state.customising) },
            fullscreen = fullscreen.active,
            modifier = Modifier.fillMaxSize(),
            leftPanel = { if (!inBand) sideColumn(Side.LEFT, leftPanels, leftCollapsed) },
            leftHandle = { if (!inBand) sideHandle(Side.LEFT) },
            rightPanel = { if (!inBand) sideColumn(Side.RIGHT, rightPanels, rightCollapsed) },
            rightHandle = { if (!inBand) sideHandle(Side.RIGHT) },
        ) {
            EditorMain(
                state, selectedClipVisible, holder, viewModel, engine, preview, editing, dropTarget, onImport, onExport, onCaptions,
                onSaveFrame = onSaveFrame,
                exportBlockedBy = exportBlockedBy,
                layout = layout,
                voice = voice,
                onUseOtherApp = onUseOtherApp,
                linkedTrees = tray.linkedTrees,
                band = band,
                inspectorOverlay = layout.inspector.dock == Dock.OVERLAY,
                onOpenLayout = { layoutSheetOpen = true },
                titleTools = titleTools,
                onOpenTray = tray.openTab,
                fullscreen = fullscreen,
                onFullscreen = onFullscreen,
                bottomTray = {
                    val trayDock = layout.tray
                    if (bottomTrayShown(layout.state, inspectorOpen)) {
                        if (trayDock.collapsed) {
                            CollapsedBottomBar(stringResource(Panel.TRAY.labelRes()).lowercase(), onExpand = { layout.dispatch(LayoutAction.SetCollapsed(Panel.TRAY, false)) })
                        } else {
                            tray.panel(true, Modifier.fillMaxWidth().wrapContentHeight(), layout.tapPreviews)
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
                takePeaks = takePeaks,
                trayDropBounds = tray.trayDropBounds,
            )
        }
    }
}

/** The project is still being read. */
@Composable
internal fun EditorLoading(padding: PaddingValues) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { CircularProgressIndicator() }
}

/** The project could not be opened. */
@Composable
internal fun EditorLoadError(message: UiText, padding: PaddingValues, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = message.asString(), style = MaterialTheme.typography.bodyLarge)
        TextButton(onClick = onClose) { Text(stringResource(R.string.common_back)) }
    }
}

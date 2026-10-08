package com.qtekfun.ultimatevideoeditor.ui.editor

import android.net.Uri
import com.qtekfun.ultimatevideoeditor.proxy.MediaPurpose
import com.qtekfun.ultimatevideoeditor.proxy.ProxyManager
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.LocalProxyIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.LocalProxyUi
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyBannerHost
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxySheetHost
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyViewModel
import com.qtekfun.ultimatevideoeditor.ui.library.LibraryButton
import com.qtekfun.ultimatevideoeditor.ui.library.LibraryOverlays
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
import com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamController
import com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamSheet
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.key
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetKind
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.MediaTray
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.RootBounds
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayDragController
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayDragRoot
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayState
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayTab
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.trayItems
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.usageCounts
import com.qtekfun.ultimatevideoeditor.ui.hub.ProjectPresets
import com.qtekfun.ultimatevideoeditor.ui.hub.aspectLabelOf
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import com.qtekfun.ultimatevideoeditor.domain.ClipLinks
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.ui.preview.wantedOutputSpace
import com.qtekfun.ultimatevideoeditor.ui.preview.DisplayHdr
import com.qtekfun.ultimatevideoeditor.engine.preview.OutputSpace
import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.engine.EngineException
import com.qtekfun.ultimatevideoeditor.engine.audio.PeakLevels
import com.qtekfun.ultimatevideoeditor.domain.captions.captionCount
import com.qtekfun.ultimatevideoeditor.ui.editor.captions.CaptionsHost
import com.qtekfun.ultimatevideoeditor.ui.editor.captions.CaptionsIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.captions.CaptionsViewModel
import com.qtekfun.ultimatevideoeditor.ui.editor.captions.ContentResolverSubtitleSource
import com.qtekfun.ultimatevideoeditor.domain.DropKind
import com.qtekfun.ultimatevideoeditor.domain.DropPlan
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.engine.timeline.DropIndicator
import com.qtekfun.ultimatevideoeditor.engine.timeline.EngineStatus
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.still.AndroidStillRasterizer
import com.qtekfun.ultimatevideoeditor.engine.title.AndroidTitleRasterizer
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine
import com.qtekfun.ultimatevideoeditor.ui.theme.LocalPalette
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.engine.timeline.ThumbnailCache
import com.qtekfun.ultimatevideoeditor.engine.timeline.WaveformCache
import com.qtekfun.ultimatevideoeditor.ui.export.ContentResolverExportIO
import com.qtekfun.ultimatevideoeditor.ui.export.ExportCenter
import com.qtekfun.ultimatevideoeditor.ui.export.ExportHost
import com.qtekfun.ultimatevideoeditor.ui.export.ExportInput
import com.qtekfun.ultimatevideoeditor.ui.export.ExportIntent
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.openMediaFd
import com.qtekfun.ultimatevideoeditor.data.FontRegistry
import com.qtekfun.ultimatevideoeditor.data.LookStore
import com.qtekfun.ultimatevideoeditor.data.TitlePresetStore
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.engine.still.AndroidLayerImages
import com.qtekfun.ultimatevideoeditor.engine.title.RegistryFontResolver
import com.qtekfun.ultimatevideoeditor.ui.editor.title.ContentResolverBytesReader
import com.qtekfun.ultimatevideoeditor.ui.editor.title.ContentResolverTextWriter
import com.qtekfun.ultimatevideoeditor.ui.editor.title.LayerHandleOverlay
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleLibraryViewModel
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleTools
import com.qtekfun.ultimatevideoeditor.data.LutStore
import com.qtekfun.ultimatevideoeditor.ui.export.ExportViewModel
import com.qtekfun.ultimatevideoeditor.ui.frame.BitmapFrameEncoder
import com.qtekfun.ultimatevideoeditor.ui.frame.MediaStoreFrameSink
import com.qtekfun.ultimatevideoeditor.ui.frame.NativeFrameRenderer
import com.qtekfun.ultimatevideoeditor.ui.frame.FrameSnackbarHost
import com.qtekfun.ultimatevideoeditor.ui.frame.StillFrameEffects
import com.qtekfun.ultimatevideoeditor.ui.frame.StillFrameInput
import com.qtekfun.ultimatevideoeditor.ui.frame.StillFrameIntent
import com.qtekfun.ultimatevideoeditor.ui.frame.StillFrameViewModel
import com.qtekfun.ultimatevideoeditor.engine.export.MediaCodecHdrExportSupport
import com.qtekfun.ultimatevideoeditor.engine.export.NativeExportRunner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatevideoeditor.ui.preview.PreviewSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.gestures.Orientation
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.CollapsedBottomBar
import com.qtekfun.ultimatevideoeditor.ui.editor.toolbar.ToolbarItem
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.CollapsedStrip
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.DockLayout
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.DragHandle
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.EditorLayoutController
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.LayoutAction
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.LayoutSheet
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.LayoutState
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.Dock
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.Panel
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.PreviewTimelineLayout
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.PrefsLayoutStore
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.Side
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.SideRequest
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.SidePanelFrame
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.SplitMetrics
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.WindowMetrics
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.handleThickness
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.label
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.rememberTicker
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.sideCollapsed
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.bottomTrayShown
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.visiblePanels
import kotlin.math.roundToInt
import android.content.Context
import java.io.File
import java.io.FileNotFoundException

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    projectId: String,
    onClose: () -> Unit,
    /** Set when a notification or the project list asked for this project's export dialog; [onShowExportHandled] clears it. */
    showExport: Boolean = false,
    onShowExportHandled: () -> Unit = {},
) {
    // The playhead changes every 16 ms while playing. It is kept out of what the chrome (toolbar,
    // banners, dialogs, inspector shell) reads, so those recompose only when something they show changes;
    // the effects below and the timecode read the live state through [holder] instead.
    val holder = viewModel.state.collectAsStateWithLifecycle()
    val chrome by remember(holder) { derivedStateOf { chromeOf(holder.value) } }
    val state = chrome.state
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // The app-wide LUT library, shared by the effects section, the preview and the export.
    val lutStore = remember(context) { LutStore(File(context.applicationContext.filesDir, "luts")) }
    val lutLibrary: LutLibraryViewModel = viewModel(
        key = "luts",
        factory = viewModelFactory {
            initializer { LutLibraryViewModel(lutStore, ContentResolverLutReader(context.applicationContext)) }
        },
    )
    val lutState by lutLibrary.state.collectAsStateWithLifecycle()

    // Saved colour looks and the copy/paste clipboard of the colour section; everything stays on the device.
    val lookStore = remember(context) { LookStore(File(context.applicationContext.filesDir, "looks")) }
    val lookLibrary: LookLibraryViewModel = viewModel(
        key = "looks",
        factory = viewModelFactory { initializer { LookLibraryViewModel(lookStore) } },
    )
    val lookState by lookLibrary.state.collectAsStateWithLifecycle()
    val lookActions = remember(lookState, lookLibrary) {
        LookActions(lookState, lookLibrary::save, lookLibrary::delete, lookLibrary::copy, lookLibrary::clearError)
    }

    // Imported fonts, saved title presets and the title drawing that uses them (preview and export share it).
    // Everything stays on the device; files come only from what the user picks.
    val fontRegistry = remember(context) { FontRegistry(File(context.applicationContext.filesDir, "fonts")) }
    val fontResolver = remember(fontRegistry) { RegistryFontResolver(fontRegistry) }
    val titleRasterizer = remember(context, fontResolver) { AndroidTitleRasterizer(AndroidLayerImages(context.applicationContext), fontResolver) }
    val titleLibrary: TitleLibraryViewModel = viewModel(
        key = "title-library",
        factory = viewModelFactory {
            initializer {
                TitleLibraryViewModel(
                    fontRegistry,
                    TitlePresetStore(File(context.applicationContext.filesDir, "title-presets")),
                    ContentResolverBytesReader(context.applicationContext),
                    ContentResolverTextWriter(context.applicationContext),
                )
            }
        },
    )
    val titleLibraryState by titleLibrary.state.collectAsStateWithLifecycle()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) titleLibrary.importFont(uri.toString())
    }
    val presetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) titleLibrary.importPreset(uri.toString())
    }
    var presetToExport by remember { mutableStateOf<TextTemplate?>(null) }
    val presetSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val preset = presetToExport
        presetToExport = null
        if (uri != null && preset != null) titleLibrary.exportPreset(preset.id, uri.toString())
    }
    val titleTools = remember(titleLibraryState, titleLibrary) {
        TitleTools(
            fonts = titleLibraryState.fonts,
            presets = titleLibraryState.presets,
            message = titleLibraryState.message,
            onImportFont = { fontPicker.launch(arrayOf("*/*")) },
            onSavePreset = { name, content, intro, outro, seconds -> titleLibrary.savePreset(name, content, seconds, intro, outro) },
            onImportPreset = { presetPicker.launch(arrayOf("*/*")) },
            onExportPreset = {
                presetToExport = it
                presetSaver.launch("${it.name}.uvtitle")
            },
            onDeletePreset = { titleLibrary.deletePreset(it.id) },
            onClearMessage = titleLibrary::clearMessage,
        )
    }

    val exportViewModel: ExportViewModel = viewModel(
        key = "export-$projectId",
        factory = viewModelFactory {
            initializer {
                ExportViewModel(
                    ContentResolverExportIO(context.applicationContext),
                    NativeExportRunner(), // only the default executor of the tests uses this one; the app's runs in ExportCenter
                    titleRasterizer = titleRasterizer,
                    stillRasterizer = AndroidStillRasterizer(context.applicationContext),
                    hdrSupport = MediaCodecHdrExportSupport(),
                    lutLoader = lutStore::load,
                    executor = ExportCenter.executor(context),
                    projectId = projectId,
                )
            }
        },
    )
    ExportHost(exportViewModel) { text ->
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(text) }
    }
    val stillFrameViewModel: StillFrameViewModel = viewModel(
        key = "frame-$projectId",
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                StillFrameViewModel(
                    renderer = NativeFrameRenderer(
                        ContentResolverExportIO(app), NativeExportRunner(), titleRasterizer, AndroidStillRasterizer(app), lutStore::load,
                    ),
                    encoder = BitmapFrameEncoder(),
                    sink = MediaStoreFrameSink(app),
                    exports = ExportCenter.executor(context),
                    projectId = projectId,
                )
            }
        },
    )
    StillFrameEffects(stillFrameViewModel, snackbar)
    val exportHolder = exportViewModel.state.collectAsStateWithLifecycle()
    // Only the name is read, so the editor does not recompose on every progress report of an export.
    val exportBlockedBy by remember(exportHolder) { derivedStateOf { exportHolder.value.blockedBy } }
    LaunchedEffect(showExport) {
        if (showExport) {
            exportViewModel.onIntent(ExportIntent.ShowProgress)
            onShowExportHandled()
        }
    }
    if (state.lutPickerOpen) {
        LutPickerDialog(
            state = lutState,
            onPick = { viewModel.onIntent(EditorIntent.AddLut(it)) },
            onImport = { uri -> lutLibrary.import(uri) { viewModel.onIntent(EditorIntent.AddLut(it.key)) } },
            onPickFilter = { id -> lutLibrary.installFilter(id) { viewModel.onIntent(EditorIntent.AddLut(it.key)) } },
            onDismiss = {
                lutLibrary.clearError()
                viewModel.onIntent(EditorIntent.CloseLutPicker)
            },
        )
    }

    val captionsViewModel: CaptionsViewModel = viewModel(
        key = "captions-$projectId",
        factory = viewModelFactory {
            initializer { CaptionsViewModel(ContentResolverSubtitleSource(context.applicationContext.contentResolver)) }
        },
    )
    CaptionsHost(
        captionsViewModel,
        onClips = { clips, intoExistingTrack -> viewModel.onIntent(EditorIntent.AddCaptionClips(clips, intoExistingTrack)) },
        onRestyle = { style, canvasHeight -> viewModel.onIntent(EditorIntent.RestyleCaptions(style, canvasHeight)) },
        onMessage = { text ->
            snackbar.currentSnackbarData?.dismiss()
            scope.launch { snackbar.showSnackbar(text) }
        },
    )
    val openCaptions: () -> Unit = {
        val live = holder.value
        captionsViewModel.onIntent(
            CaptionsIntent.Open(live.fps, live.canvasHeight, live.playhead.value, live.timeline.captionCount()),
        )
    }
    val openExport = {
        // The dialog works from what the editor holds right now; the autosave is not involved.
        val live = holder.value
        exportViewModel.onIntent(
            ExportIntent.Open(
                ExportInput(
                    projectId, live.projectName, live.canvasWidth, live.canvasHeight, live.fps, live.timeline, live.assets, live.colorSpace,
                    missingAssetIds = live.missingMedia.keys,
                ),
            ),
        )
    }

    val openStillFrame = {
        // Playback stops first, so the frame is the one the playhead rests on, whatever the player was doing.
        if (viewModel.state.value.isPlaying) viewModel.onIntent(EditorIntent.TogglePlay)
        val live = viewModel.state.value
        stillFrameViewModel.onIntent(
            StillFrameIntent.Save(
                StillFrameInput(
                    projectId = projectId,
                    projectName = live.projectName,
                    projectWidth = live.canvasWidth,
                    projectHeight = live.canvasHeight,
                    fps = live.fps,
                    timeline = live.timeline,
                    assets = live.assets,
                    frame = live.playhead.value,
                    missingAssetIds = live.missingMedia.keys,
                ),
            ),
        )
    }

    val thumbnailFailures = remember(projectId) {
        val main = Handler(Looper.getMainLooper())
        ThumbnailFailures(
            projectId,
            log = { android.util.Log.w("uv_thumb", it) },
            // Called on a native worker thread. The clip stays usable without its filmstrip.
            show = { message -> main.post { viewModel.onIntent(EditorIntent.ReportError(message)) } },
        )
    }
    val engine = remember {
        val main = Handler(Looper.getMainLooper())
        TimelineEngine(
            density,
            onThumbnailError = thumbnailFailures::onFailure,
        ) { _, status ->
            // Called on a native worker thread. A file without audio is not an error worth showing.
            if (status == EngineStatus.IO_ERROR || status == EngineStatus.CODEC_ERROR) {
                main.post { viewModel.onIntent(EditorIntent.ReportError("Could not read the audio of a clip ($status)")) }
            }
        }
    }
    DisposableEffect(engine) { onDispose { engine.close() } }
    // The canvas takes its colours from the same palette as the rest of the app, so it follows the pure-black option live.
    val palette = LocalPalette.current
    LaunchedEffect(engine, palette) { engine.setPalette(palette.nativeColours()) }

    // Proxy media: small copies the preview and the thumbnails use while editing. Export never asks for them.
    val proxyManager = remember(context) { ProxyManager.of(context.applicationContext) }
    val proxyVm: ProxyViewModel = viewModel(
        key = "proxy-$projectId",
        factory = viewModelFactory { initializer { ProxyViewModel(proxyManager, projectId) } },
    )
    val proxyHolder = proxyVm.state.collectAsStateWithLifecycle()

    val preview = remember {
        EditorPreview(
            context,
            scope,
            rasterizer = titleRasterizer,
            lutLoader = lutStore::load,
            onProxyFailed = { proxyVm.onIntent(ProxyIntent.PreviewProxyFailed(it)) },
            onSoftwareDecoding = { heavy ->
                viewModel.onIntent(
                    EditorIntent.ReportError(
                        "Software decoding: this video format is not supported by the phone's decoder, so it is decoded on the CPU and may play slower" +
                            if (heavy) ". A proxy is advised." else ".",
                    ),
                )
                if (heavy) proxyVm.onIntent(ProxyIntent.SoftwareDecodeHeavy)
            },
        ) {
            viewModel.onIntent(EditorIntent.ReportError(it))
            // A few stalls while playing are the cue to suggest proxies.
            if (it.contains("stall", ignoreCase = true)) proxyVm.onIntent(ProxyIntent.ReportStall)
        }
    }
    DisposableEffect(preview) { onDispose { preview.close() } }
    // A font that was imported or removed changes how titles that name it are drawn: tell the editor which
    // fonts exist (for the missing-font notice) and draw the titles again.
    LaunchedEffect(titleLibraryState.fontIds) {
        viewModel.onIntent(EditorIntent.FontsChanged(titleLibraryState.fontIds))
        fontResolver.clear()
        preview.titlesChanged()
    }

    val audio = remember {
        EditorAudio(context, scope) { viewModel.onIntent(EditorIntent.ReportError(it)) }
    }
    DisposableEffect(audio, viewModel) {
        viewModel.playbackOutput = audio
        viewModel.audioAnalyzer = audio
        onDispose {
            viewModel.playbackOutput = null
            viewModel.audioAnalyzer = null
            audio.close()
        }
    }

    // Keep the mixer in step with the committed timeline (not with a clip drag in progress). A slider
    // of the audio tools (pan, EQ, track volume, ducking) is heard live: audioSource is its preview.
    StateEffect(holder, { listOf(it.audioSource, it.assets, it.missingMedia, it.mediaChecked, it.fps, it.isLoading) }) { s ->
        if (s.isLoading) return@StateEffect
        // Files that cannot be read are left out: the mixer would only fail on them.
        audio.update(
            audioSnapshotOf(s.audioSource, s.playableAssets, s.fps, viewModel::clipKey, viewModel::assetKey),
            s.playableAssets,
            viewModel::assetKey,
        )
    }

    // Show the composite under the playhead (every video track, bottom first). Paused, every change
    // shows a still frame. Playing, the playhead is the audio clock: the native preview runs by
    // itself and is only re-anchored when the composition changes or drifts from it, never seeked
    // per tick. It follows the visible timeline, so a transform being dragged shows live.
    StateEffect(
        holder,
        // The proxy version changes when the switch flips or a proxy finishes: the preview then opens another file.
        { listOf(it.playhead, it.isPlaying, it.visibleTimeline, it.assets, it.missingMedia, it.mediaChecked, it.fps, it.canvasWidth, it.canvasHeight, it.isLoading, proxyHolder.value.resolveVersion) },
    ) { s ->
        if (s.isLoading) return@StateEffect
        val layers = previewRequestsOnCanvas(
            s.visibleTimeline,
            s.playableAssets,
            s.fps,
            s.playhead,
            s.canvasWidth,
            s.canvasHeight,
            sourceOf = { proxyVm.resolve(it, MediaPurpose.PREVIEW) },
        ) { viewModel.assetKey(it).toInt() }
        val scene = PreviewScene(s.canvasWidth, s.canvasHeight, layers)
        when {
            s.isPlaying -> preview.follow(scene, s.playhead.value, s.fps)
            layers.isNotEmpty() -> preview.show(scene)
            // In a gap, or at the end after playing, the preview keeps its last frame.
            else -> preview.stopFollowing()
        }
    }

    val editing = remember(viewModel) {
        object : TimelineEditing {
            override fun canDrag(hit: TimelineHit) = viewModel.canDrag(hit)
            override fun onDragStart(hit: TimelineHit) = viewModel.onIntent(EditorIntent.DragStart(hit))
            override fun onDragMove(hit: TimelineHit) = onDragMove(hit, DropPlan.INSERT_RADIUS_FRAMES)
            override fun onDragMove(hit: TimelineHit, reachFrames: Long) =
                viewModel.onIntent(EditorIntent.DragMove(hit.frame, hit.trackIndex, dragZoneOf(hit), reachFrames))
            override fun onDropModeTap() = viewModel.onIntent(EditorIntent.FlipDropChoice)
            override fun onDragEnd(commit: Boolean) = viewModel.onIntent(EditorIntent.DragEnd(commit))
            override fun onScrub() = viewModel.onIntent(EditorIntent.ScrubStarted)
            override fun onLaneDragStart(hit: TimelineHit) = viewModel.onIntent(LaneDragIntent.Start(hit))
            override fun onLaneDragMove(hit: TimelineHit) = viewModel.onIntent(LaneDragIntent.Move(hit))
            override fun onLaneDragEnd(commit: Boolean) = viewModel.onIntent(LaneDragIntent.End(commit))
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onIntent(EditorIntent.ImportMedia(uris.map(Uri::toString)))
    }
    val launchImport = { importPicker.launch(arrayOf("video/*", "audio/*", "image/*")) }

    // The media tray: how it is shown is local UI state; what it lists comes from the editor state.
    var tray by remember { mutableStateOf(TrayState()) }
    val trayUsage = remember(state.timeline) { usageCounts(state.timeline) }
    val trayItems = remember(state.assets, trayUsage, state.missingMedia, tray.tab, tray.filter, tray.query) {
        trayItems(state.assets, trayUsage, state.missingMedia.keys, tray.tab, tray.filter, tray.query)
    }
    val trayImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onIntent(EditorIntent.ImportToTray(uris.map(Uri::toString)))
    }
    val launchTrayImport = { trayImportPicker.launch(arrayOf("video/*", "audio/*", "image/*")) }
    val dropTarget = remember(viewModel) {
        object : TimelineDropTarget {
            override fun onExternalEnter(kinds: List<AssetKind>) = viewModel.onIntent(EditorIntent.ExternalDragStart(kinds))
            override fun onHover(hit: TimelineHit) = viewModel.onIntent(EditorIntent.TrayDragMove(hit.frame, hit.trackIndex, dragZoneOf(hit)))
            override fun onLeave() = viewModel.onIntent(EditorIntent.TrayDragLeave)
            override fun onExternalDrop(uris: List<String>, hit: TimelineHit) =
                viewModel.onIntent(EditorIntent.ExternalDrop(uris, hit.frame, hit.trackIndex, dragZoneOf(hit)))
            override fun onEnd() = viewModel.onIntent(EditorIntent.TrayDragEnd(commit = false))
        }
    }
    // Dragging a tile onto the timeline: the tray tracks the finger, the sink turns it into timeline drops (see DECISIONS.md "Tray drag").
    val trayDropBounds = remember(viewModel, engine) { TimelineTrayDrop(engine::hitTest, engine::scrollBy, viewModel::onIntent, density) }
    val trayDrag = remember(trayDropBounds) {
        TrayDragController().also { controller ->
            controller.sink = trayDropBounds
            controller.onReorder = { id, index -> viewModel.onIntent(EditorIntent.ReorderAsset(id, index)) }
            controller.indexOf = { id -> holder.value.assets.indexOfFirst { it.id == id } }
        }
    }
    val trayPanel: @Composable (Boolean, Modifier) -> Unit = { bottom, panelModifier ->
        MediaTray(
            state = tray,
            onState = { tray = it },
            assets = state.assets,
            items = trayItems,
            isImporting = state.isImporting,
            bottomPanel = bottom,
            onImport = launchTrayImport,
            onAdd = { viewModel.onIntent(EditorIntent.AddAsset(it)) },
            drag = trayDrag,
            onExternalFiles = { viewModel.onIntent(EditorIntent.ImportToTray(it)) },
            onPickSticker = { viewModel.onIntent(EditorIntent.AddSticker(it)) },
            onApplyTemplate = { id, text -> viewModel.onIntent(EditorIntent.ApplyTextTemplate(id, text)) },
            modifier = panelModifier,
            userPresets = titleLibraryState.presets,
            onApplyPreset = { template, text -> viewModel.onIntent(EditorIntent.ApplyPreset(template, text)) },
        )
    }

    // The replacement for a missing file: which asset it is for is remembered while the picker is open.
    var relinkTarget by remember { mutableStateOf<String?>(null) }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val assetId = relinkTarget
        relinkTarget = null
        if (uri != null && assetId != null) viewModel.onIntent(EditorIntent.RelinkAsset(assetId, uri.toString()))
    }

    // The folder to look in for every missing file; the app keeps read access to it (taken by the scanner).
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onIntent(EditorIntent.RelinkFromFolder(uri.toString()))
    }

    // Where an export to another tool is written: the kind is remembered while the picker is open.
    var interchangeKind by remember { mutableStateOf<InterchangeKind?>(null) }
    val onInterchangeUri = { uri: Uri? ->
        val kind = interchangeKind
        interchangeKind = null
        if (uri != null && kind != null) viewModel.onIntent(LibraryIntent.ExportTo(kind, uri.toString()))
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME), onInterchangeUri)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(INTERCHANGE_MIME), onInterchangeUri)

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is EditorEffect.ShowMessage -> {
                    snackbar.currentSnackbarData?.dismiss()
                    scope.launch { snackbar.showSnackbar(effect.text) }
                }
                EditorEffect.Close -> onClose()
                is EditorEffect.LaunchRelinkPicker -> {
                    relinkTarget = effect.assetId
                    relinkPicker.launch(arrayOf("video/*", "audio/*", "image/*"))
                }
                EditorEffect.LaunchFolderPicker -> folderPicker.launch(null)
                is EditorEffect.LaunchInterchangePicker -> {
                    interchangeKind = effect.kind
                    if (effect.mime == ZIP_MIME) zipPicker.launch(effect.suggestedFileName) else filePicker.launch(effect.suggestedFileName)
                }
            }
        }
    }

    // Publish what the canvas should draw; drags show a provisional timeline until released.
    StateEffect(holder, { listOf(it.visibleTimeline, it.selectedClipId, it.selectedClipIds, it.missingMedia, it.fps, it.isLoading) }) { s ->
        if (s.isLoading) return@StateEffect
        try {
            engine.setSnapshot(viewModel.snapshotOf(s))
        } catch (e: EngineException) {
            viewModel.onIntent(EditorIntent.ReportError(e.message ?: "The timeline could not be drawn"))
        }
    }
    // The indicator of what releasing a dragged clip would do. After the snapshot effect, so the lane
    // index it names refers to the timeline the engine already has.
    StateEffect(holder, { listOf(it.dropHint, it.visibleTimeline) }) { s ->
        val hint = s.dropHint
        val lane = hint?.trackId?.let { id -> s.visibleTimeline.tracks.indexOfFirst { it.id == id } } ?: -1
        val indicator = when (hint?.kind) {
            DropKind.INSERT -> DropIndicator.INSERT
            DropKind.OVERWRITE -> DropIndicator.OVERWRITE
            DropKind.NEW_LANE -> DropIndicator.NEW_LANE
            DropKind.CANCEL -> DropIndicator.CANCEL
            else -> DropIndicator.NONE
        }
        try {
            engine.setDropHint(indicator, lane, hint?.startFrame ?: 0L, hint?.endFrame ?: 0L)
        } catch (e: EngineException) {
            viewModel.onIntent(EditorIntent.ReportError(e.message ?: "The timeline could not be drawn"))
        }
    }
    // The clips being dragged or trimmed (lifted with a shadow) and the snap line. After the snapshot effect so the keys
    // name clips the engine already has.
    StateEffect(holder, { listOf(it.dragOverlay, it.visibleTimeline) }) { s ->
        val overlay = s.dragOverlay
        try {
            engine.setDragOverlay(overlay?.guideFrame, overlay?.let { viewModel.dragOverlayKeys(it) } ?: LongArray(0))
        } catch (e: EngineException) {
            viewModel.onIntent(EditorIntent.ReportError(e.message ?: "The timeline could not be drawn"))
        }
    }
    // The lane being dragged by its header and where it would land. After the snapshot effect, like the drop hint,
    // so the indices refer to the timeline the engine already has.
    StateEffect(holder, { listOf(it.laneDrag, it.visibleTimeline) }) { s ->
        val drag = s.laneDrag
        try {
            engine.setLaneDrag(drag?.fromIndex ?: -1, drag?.toIndex ?: -1)
        } catch (e: EngineException) {
            viewModel.onIntent(EditorIntent.ReportError(e.message ?: "The timeline could not be drawn"))
        }
    }
    // Follow the whole project's length until the user zooms by hand. Keyed on the committed
    // timeline, so a clip being dragged does not make the zoom jump. Declared after the snapshot
    // effect so the engine already has the new timeline when it fits.
    StateEffect(holder, { listOf(it.timeline.tracks.maxOfOrNull { track -> track.end.value } ?: 0L, it.isLoading) }) { s ->
        if (!s.isLoading) engine.followContent()
    }
    StateEffect(holder, { it.playhead }) { s ->
        engine.setPlayhead(s.playhead.value)
        // Playing, or jumping to the next/previous edit, can take the playhead off screen.
        engine.ensureVisible(s.playhead.value)
    }

    val requestedWaveforms = remember { mutableSetOf<String>() }
    StateEffect(holder, { listOf(it.assets, it.missingMedia, it.mediaChecked) }) { s ->
        for (asset in s.playableAssets) {
            // Keyed by the file too, so a relinked asset is requested again from its new file.
            if (!asset.hasAudio || !requestedWaveforms.add("${asset.id}|${asset.uri}")) continue
            requestWaveform(context, engine, viewModel, projectId, asset)
        }
    }
    val requestedThumbnails = remember { mutableSetOf<String>() }
    StateEffect(holder, { listOf(it.assets, it.missingMedia, it.mediaChecked) }) { s ->
        for (asset in s.playableAssets) {
            if (!(asset.hasVideo || asset.isImage) || !requestedThumbnails.add("${asset.id}|${asset.uri}")) continue
            // Filmstrips are cheaper to decode from a ready proxy; they are cached under the original's identity.
            thumbnailFailures.register(viewModel.assetKey(asset.id), displayName(asset), asset.uri)
            requestThumbnails(context, engine, viewModel, projectId, asset.copy(uri = proxyVm.resolve(asset, MediaPurpose.THUMBNAIL).uri))
        }
    }
    // The proxy side learns the project's media (to queue, validate and suggest proxies).
    StateEffect(holder, { it.assets }) { s -> proxyVm.onIntent(ProxyIntent.SetAssets(s.assets)) }

    BackHandler { viewModel.onIntent(EditorIntent.Back) }
    // A double tap on the preview makes it fill the window. Registered after the editor's own Back, so it wins.
    var fullscreen by rememberSaveable(stateSaver = FullscreenSaver) { mutableStateOf(FullscreenState()) }
    val onFullscreen: (FullscreenAction) -> Unit = { fullscreen = fullscreen.reduce(it) }
    BackHandler(enabled = fullscreen.consumesBack) { onFullscreen(FullscreenAction.Exit) }
    // Registered last, so it wins: Back puts a tile in hand down again.
    BackHandler(enabled = trayDrag.carry?.returning == false) { trayDrag.cancel() }
    ImmersiveWhile(fullscreen.active)
    LaunchedEffect(fullscreen.overlayEpoch, fullscreen.overlayVisible) {
        if (fullscreen.overlayVisible) {
            val epoch = fullscreen.overlayEpoch
            delay(FULLSCREEN_OVERLAY_MS)
            onFullscreen(FullscreenAction.Timeout(epoch))
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Flush pauses playback; the audio device is then freed until the next play.
        viewModel.onIntent(EditorIntent.Flush)
        audio.releaseDevice()
    }

    CompositionLocalProvider(
        LocalLutNames provides lutState.names,
        LocalQualifierPick provides state.qualifierPick,
        LocalLookActions provides lookActions,
        LocalProxyUi provides proxyHolder,
        LocalProxyIntent provides proxyVm::onIntent,
    ) {
    TrayDragRoot(trayDrag, Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = {
            FrameSnackbarHost(
                snackbar,
                onShare = { stillFrameViewModel.onIntent(StillFrameIntent.Share) },
                onOpen = { stillFrameViewModel.onIntent(StillFrameIntent.Open) },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }

            state.loadError != null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(text = state.loadError.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = onClose) { Text("Back") }
            }

            else -> BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
                val window = WindowMetrics(maxWidth.value.roundToInt().toFloat(), maxHeight.value.roundToInt().toFloat())
                val layout = remember {
                    val prefs = PrefsLayoutStore(context.getSharedPreferences(PrefsLayoutStore.FILE, Context.MODE_PRIVATE))
                    EditorLayoutController(prefs, window, prefs, prefs)
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
                                label = panels.joinToString(" and ") { it.label().lowercase() },
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
                                            trayPanel(false, Modifier.fillMaxSize())
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
                        description = "Resize the ${if (side == Side.LEFT) "left" else "right"} panel. Double tap to reset.",
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
                DockLayout(
                    left = { SideRequest(leftPanels.isNotEmpty(), layout.state.leftWidthDp, leftCollapsed) },
                    right = { SideRequest(rightPanels.isNotEmpty(), layout.state.rightWidthDp, rightCollapsed) },
                    handleThickness = { handleThickness(layout.state.customising) },
                    fullscreen = fullscreen.active,
                    modifier = Modifier.fillMaxSize(),
                    leftPanel = { sideColumn(Side.LEFT, leftPanels, leftCollapsed) },
                    leftHandle = { sideHandle(Side.LEFT) },
                    rightPanel = { sideColumn(Side.RIGHT, rightPanels, rightCollapsed) },
                    rightHandle = { sideHandle(Side.RIGHT) },
                ) {
                    EditorMain(
                        state, chrome.selectedClipVisible, holder, viewModel, engine, preview, editing, dropTarget, launchImport, openExport, openCaptions,
                        onSaveFrame = openStillFrame,
                        exportBlockedBy = exportBlockedBy,
                        layout = layout,
                        inspectorOverlay = layout.inspector.dock == Dock.OVERLAY,
                        onOpenLayout = { layoutSheetOpen = true },
                        titleTools = titleTools,
                        onOpenTray = { tray = tray.open(it) },
                        fullscreen = fullscreen,
                        onFullscreen = onFullscreen,
                        bottomTray = {
                            val trayDock = layout.tray
                            if (bottomTrayShown(layout.state, inspectorOpen)) {
                                if (trayDock.collapsed) {
                                    CollapsedBottomBar("media tray", onExpand = { layout.dispatch(LayoutAction.SetCollapsed(Panel.TRAY, false)) })
                                } else {
                                    trayPanel(true, Modifier.fillMaxWidth().wrapContentHeight())
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        takePeaks = audio::takePeaks,
                        trayDropBounds = trayDropBounds,
                    )
                }
            }
        }
    }
    }
    }
}

@Composable
private fun EditorMain(
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
    val hasSelection = state.selectedClipId != null
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
    if (state.mixerOpen) MixerSheet(state) { viewModel.onIntent(it) }
    QuickEditSheets(state) { viewModel.onIntent(it) }
    if (state.multicam.open) {
        MulticamSheet(
            state = state,
            group = MulticamController.groupOf(state.timeline, state.selectedClipId),
            feedsOf = viewModel::multicamFeeds,
            onIntent = { viewModel.onIntent(EditorIntent.Multicam(it)) },
        )
    }
    LibraryOverlays(state) { viewModel.onIntent(it) }
    LocalProxyUi.current?.let { ProxySheetHost(it, LocalProxyIntent.current) }
    var scopesOpen by remember { mutableStateOf(false) }
    var guideOpen by rememberSaveable { mutableStateOf(false) }
    if (guideOpen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { guideOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                com.qtekfun.ultimatevideoeditor.ui.editor.guide.ToolbarGuideScreen(onClose = { guideOpen = false })
            }
        }
    }
    if (state.relinkOpen && (state.missingAssets.isNotEmpty() || state.folderRelink !is FolderRelinkUi.Idle)) {
        RelinkDialog(state.missingAssets, state.folderRelink) { viewModel.onIntent(it) }
    }
    if (state.leaveBlockedBySave) SaveFailedDialog(state.saveError) { viewModel.onIntent(it) }
    Column(modifier = modifier) {
        // Fullscreen: only the rows above and below the preview/timeline block go (they hold no native view).
        if (!fullscreen.active) Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(EditorIcons.Back, "Back") { viewModel.onIntent(EditorIntent.Back) }
            Text(
                text = state.projectName,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            ToolButton(EditorIcons.Help, "Toolbar guide: what every symbol and gesture does") { guideOpen = true }
            ToolButton(EditorIcons.LayoutPanes, "Layout: presets, panels, track height and dividers", onClick = onOpenLayout)
            ToolButton(EditorIcons.Undo, "Undo", enabled = state.canUndo) { viewModel.onIntent(EditorIntent.Undo) }
            ToolButton(EditorIcons.Redo, "Redo", enabled = state.canRedo) { viewModel.onIntent(EditorIntent.Redo) }
            ToolButton(
                EditorIcons.Export,
                if (exportBlockedBy != null) "Export unavailable: another export is running ($exportBlockedBy)" else "Export movie",
                enabled = !state.isPlaying,
                onClick = onExport,
            )
        }
        if (!fullscreen.active) {
            MediaBanners(state, onImportFont = titleTools.onImportFont) { viewModel.onIntent(it) }
            ProxyBannerHost()
        }

        val splitMetrics = remember { SplitMetrics() }
        val ticker = rememberTicker()
        // The preview and the timeline share what the controls leave; the share is read while measuring, so
        // dragging the handle between them re-measures the block instead of recomposing the editor.
        PreviewTimelineLayout(
            fraction = { layout.state.previewFraction },
            metrics = splitMetrics,
            handleThickness = { handleThickness(layout.state.customising) },
            fullscreen = fullscreen.active,
            modifier = Modifier.fillMaxWidth().weight(1f),
            preview = {
                // Where the fullscreen controls sit, so a tap on them is not taken for a tap on the picture.
                var controlsBounds by remember { mutableStateOf<Rect?>(null) }
                // No background here: the preview is a SurfaceView, and an opaque parent would hide it.
                Box(
                    modifier = Modifier.fillMaxSize().previewTapGestures(
                        ignoreIn = { controlsBounds },
                        onTap = { onFullscreen(FullscreenAction.Tap) },
                        onDoubleTap = { onFullscreen(FullscreenAction.DoubleTap) },
                    ),
                    contentAlignment = Alignment.Center,
                ) {
                    val previewEngine = preview.engine
                    if (previewEngine != null) {
                        // HLG project: render the preview as HDR when the screen shows it, else tone-mapped SDR.
                        val hdrContext = LocalContext.current
                        val displayHlg = remember { DisplayHdr.supportsHlg(hdrContext) }
                        val wanted = wantedOutputSpace(state.colorSpace.isHdr, displayHlg)
                        var granted by remember { mutableStateOf(OutputSpace.SDR_709) }
                        DisposableEffect(granted) {
                            DisplayHdr.setWindowHdr(hdrContext, granted == OutputSpace.HLG_2020)
                            onDispose { DisplayHdr.setWindowHdr(hdrContext, false) }
                        }
                        PreviewSurface(previewEngine, Modifier.fillMaxSize(), wanted = wanted, onOutputSpace = { granted = it })
                        if (state.colorSpace.isHdr) {
                            Text(
                                if (granted == OutputSpace.HLG_2020) "HDR HLG" else "HDR project, SDR preview",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                            )
                        }
                        state.safeZone?.let { SafeZoneOverlay(it, state.canvasWidth, state.canvasHeight) }
                        if (state.track.overlay.size >= 2) {
                            // The playhead is read here, in the overlay's own scope, so a tick redraws only the dot.
                            val trackPlayhead by remember(viewModel) { viewModel.state.map { it.playhead.value }.distinctUntilChanged() }
                                .collectAsStateWithLifecycle(initialValue = 0L)
                            TrackPathOverlay(state.track.overlay, trackPlayhead, state.canvasWidth, state.canvasHeight, Modifier.fillMaxSize())
                        }
                        // Drag, pinch and twist edit the selected clip while it is under the playhead; with a layer of a
                        // multilayer title selected they move that layer instead.
                        val layerTarget = state.selectedTitleLayer != null
                        PreviewGestureLayer(
                            enabled = selectedClipVisible && !state.track.picking,
                            canvasWidth = state.canvasWidth,
                            canvasHeight = state.canvasHeight,
                            onStep = { panX, panY, zoom, rotation ->
                                viewModel.onIntent(
                                    if (layerTarget) EditorIntent.LayerGesture(panX, panY, zoom, rotation)
                                    else EditorIntent.TransformGesture(panX, panY, zoom, rotation),
                                )
                            },
                            onEnd = { viewModel.onIntent(EditorIntent.EndAppearanceEdit(commit = true)) },
                            modifier = Modifier.fillMaxSize(),
                        )
                        // A ring and cross on the layer the gestures are moving (nothing unless a layer is selected).
                        LayerHandleOverlay(state, Modifier.fillMaxSize())
                        if (state.track.picking) {
                            TrackTargetLayer(
                                canvasWidth = state.canvasWidth,
                                canvasHeight = state.canvasHeight,
                                onPick = { x, y, w, h -> viewModel.onIntent(EditorIntent.PickTrackTarget(x, y, w, h)) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (state.qualifierPick.armed) {
                            // The eyedropper: a tap on the picture keys the HSL qualifier on the colour under it.
                            TrackTargetLayer(
                                canvasWidth = state.canvasWidth,
                                canvasHeight = state.canvasHeight,
                                onPick = { x, y, _, _ -> viewModel.onIntent(QualifierIntent.Pick(x, y)) },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        FullscreenControls(
                            visible = fullscreen.overlayVisible,
                            playing = state.isPlaying,
                            onPlayPause = {
                                onFullscreen(FullscreenAction.Interact)
                                viewModel.onIntent(EditorIntent.TogglePlay)
                            },
                            onExit = { onFullscreen(FullscreenAction.Exit) },
                            onBounds = { controlsBounds = it },
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                        if (scopesOpen) {
                            ScopesPanel(
                                engine = previewEngine,
                                colorSpace = state.colorSpace,
                                onError = { viewModel.onIntent(EditorIntent.ReportError(it)) },
                                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(SCOPES_WIDTH).fillMaxHeight(SCOPES_HEIGHT).padding(6.dp),
                            )
                        }
                    } else {
                        Text(text = "Preview unavailable", style = MaterialTheme.typography.labelLarge)
                    }
                }

            },
            handle = {
                DragHandle(
                    orientation = Orientation.Vertical,
                    customising = layout.customising,
                    description = "Resize the preview and the timeline. Double tap to reset.",
                    onDelta = { dy ->
                        val total = splitMetrics.flexiblePx
                        if (total > 0) {
                            val current = layout.state.previewFraction
                            val next = current + dy / total
                            ticker(current, next, LayoutState.DEFAULT_PREVIEW_FRACTION, 0.01f)
                            layout.dispatch(LayoutAction.SetPreviewFraction(next), persist = false)
                        }
                    },
                    onEnd = layout::commit,
                    onReset = { layout.dispatch(LayoutAction.ResetPreviewDivider) },
                    modifier = Modifier.fillMaxSize(),
                )
            },
            chrome = {
                Column(modifier = Modifier.fillMaxWidth()) {
                // Transport: timecode on the left, previous / play / next centred.
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                    Column(modifier = Modifier.align(Alignment.CenterStart)) {
                        Timecode(holder)
                        LevelMeter(takePeaks, state.isPlaying)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ToolButton(EditorIcons.SkipPrevious, "Previous clip boundary") { viewModel.onIntent(EditorIntent.SeekPrevious) }
                        ToolButton(
                            icon = if (state.isPlaying) EditorIcons.Pause else EditorIcons.Play,
                            description = if (state.isPlaying) "Pause" else "Play",
                        ) { viewModel.onIntent(EditorIntent.TogglePlay) }
                        ToolButton(EditorIcons.SkipNext, "Next clip boundary") { viewModel.onIntent(EditorIntent.SeekNext) }
                    }
                    Row(modifier = Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                        ToolButton(EditorIcons.FrameImage, "Save frame as image: the picture under the playhead as PNG or JPEG", onClick = onSaveFrame)
                        ToolButton(EditorIcons.Fit, "Fit the whole project") { engine.fitToContent() }
                    }
                }

                // The tools in the order the person chose (ToolbarOrder); hidden ones wait in the More menu at the end.
                // [after] closes that menu once a plain button has been used.
                val proxyIntent = LocalProxyIntent.current
                val toolbarItem: @Composable (ToolbarItem, () -> Unit) -> Unit = { item, after ->
                    when (item) {
                        ToolbarItem.IMPORT -> ToolButton(EditorIcons.Add, "Import media", enabled = !state.isImporting) { onImport(); after() }
                        ToolbarItem.SPLIT -> ToolButton(EditorIcons.Split, "Split at playhead", enabled = hasSelection) {
                            viewModel.onIntent(EditorIntent.SplitAtPlayhead); after()
                        }
                        ToolbarItem.DETACH_AUDIO -> ToolButton(
                            EditorIcons.DetachAudio,
                            "Detach audio: put the selected video clip's sound on an audio lane, linked to the clip",
                            enabled = state.selectedClipId?.let { id ->
                                val hasAudio = state.assets.firstOrNull { it.id == state.timeline.trackOfClip(id)?.clip(id)?.assetId }?.hasAudio == true
                                ClipLinks.infoFor(state.timeline, id, hasAudio)?.canDetach == true
                            } == true,
                        ) { viewModel.onIntent(EditorIntent.DetachAudio); after() }
                        ToolbarItem.DELETE -> ToolButton(EditorIcons.Delete, "Delete (the base track closes the gap, overlays leave one)", enabled = hasSelection) {
                            viewModel.onIntent(EditorIntent.RippleDeleteSelected); after()
                        }
                        ToolbarItem.MARKER -> MarkerMenu(state, viewModel::onIntent)
                        ToolbarItem.SELECT_MODE -> SelectModeButton(state, viewModel::onIntent)
                        ToolbarItem.CLOSE_GAP -> ToolButton(EditorIcons.CloseGap, "Close gap before clip (the base track does this by itself)", enabled = hasSelection && !state.selectedClipOnBase) {
                            viewModel.onIntent(EditorIntent.RippleAppendSelected); after()
                        }
                        ToolbarItem.TITLE -> ToolButton(EditorIcons.Title, "Add a title at the playhead") { viewModel.onIntent(EditorIntent.AddTitle); after() }
                        ToolbarItem.CAPTIONS -> ToolButton(EditorIcons.Captions, "Captions: type them or import a .srt / .vtt file") { onCaptions(); after() }
                        ToolbarItem.STICKERS -> ToolButton(EditorIcons.Sticker, "Stickers: open the media tray on the stickers tab") { onOpenTray(TrayTab.STICKERS); after() }
                        ToolbarItem.TEMPLATES -> ToolButton(EditorIcons.TextTemplate, "Titles and text templates: open the media tray on the titles tab") { onOpenTray(TrayTab.TEMPLATES); after() }
                        ToolbarItem.QUICK_EDITS -> QuickEditMenu(state, viewModel::onIntent)
                        ToolbarItem.LIBRARY -> LibraryButton(viewModel::onIntent)
                        ToolbarItem.PROXY -> ToolButton(EditorIcons.Proxy, "Proxy media: small copies for smooth editing of heavy video; export always uses the originals") {
                            proxyIntent(ProxyIntent.OpenSheet); after()
                        }
                        ToolbarItem.MIXER -> ToolButton(EditorIcons.Mixer, "Mixer: track volume, mute, solo, compressor and ducking") {
                            viewModel.onIntent(EditorIntent.ToggleMixer); after()
                        }
                        ToolbarItem.MULTICAM -> ToolButton(EditorIcons.Multicam, "Multicam: line up several cameras by their sound and cut between them") {
                            viewModel.onIntent(EditorIntent.Multicam(MulticamIntent.Open)); after()
                        }
                        ToolbarItem.SCOPES -> ToolButton(EditorIcons.Scopes, "Video scopes: waveform, RGB parade, vectorscope and histogram of the preview") {
                            scopesOpen = !scopesOpen; after()
                        }
                        ToolbarItem.TRANSITION -> ToolButton(
                            EditorIcons.Transition,
                            "Add a crossfade at the selected cut: select a clip next to another one, or put the playhead on a cut",
                            enabled = state.transitionCut != null,
                        ) { viewModel.onIntent(EditorIntent.AddTransition); after() }
                        ToolbarItem.ADJUST -> ToolButton(EditorIcons.Tune, "Adjust clip: text, position, scale, rotation, opacity, volume, crossfade", enabled = hasSelection || state.inspectorOpen) {
                            viewModel.onIntent(EditorIntent.ToggleInspector); after()
                        }
                        ToolbarItem.TRACK_CONTROLS -> TrackControls(
                            state.selectedTrackLabel,
                            onAdd = { viewModel.onIntent(EditorIntent.AddTrack(it)) },
                            onMove = { viewModel.onIntent(EditorIntent.MoveSelectedTrack(it)) },
                        ) {
                            viewModel.onIntent(EditorIntent.RemoveSelectedTrack)
                        }
                        ToolbarItem.CANVAS -> ToolButton(EditorIcons.CanvasFormat, "Change the canvas format and resolution") {
                            viewModel.onIntent(EditorIntent.ShowCanvasDialog); after()
                        }
                        ToolbarItem.SAFE_ZONE -> SafeZoneMenu(state.safeZone) { viewModel.onIntent(EditorIntent.SetSafeZone(it)) }
                    }
                }
                var moreOpen by remember { mutableStateOf(false) }
                val overflow = layout.toolbarOrder.overflow

                // Scrolls sideways when the buttons do not fit a narrow window.
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (item in layout.toolbarOrder.visible) key(item) { toolbarItem(item) {} }
                    if (overflow.isNotEmpty()) {
                        Box {
                            ToolButton(SelectionIcons.More, "More tools: ${overflow.joinToString { it.label }}") { moreOpen = true }
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
                if (state.selectMode || state.isMultiSelection) SelectionBar(state, viewModel::onIntent)
                if (state.canvasDialogOpen) CanvasDialog(state.canvasWidth, state.canvasHeight, state.colorSpace, viewModel::onIntent)

                }
            },
            timeline = {
                // The inspector is drawn over the timeline instead of replacing it, so the native timeline view
                // is never recreated (a late surfaceDestroyed of an old view would tear down the new surface).
                Box(modifier = Modifier.fillMaxSize()) {
                    TimelineHost(
                        engine = engine,
                        onTap = { viewModel.onIntent(EditorIntent.TapTimeline(it)) },
                        editing = editing,
                        dropTarget = dropTarget,
                        selecting = selecting,
                        shaping = shaping,
                        modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                            trayDropBounds?.bounds = coordinates.boundsInRoot().let { RootBounds(it.left, it.top, it.right, it.bottom) }
                        },
                    )
                    if (state.dropChoiceOffered) {
                        DropModeChip(
                            effective = state.dropHint?.kind,
                            choice = state.dropChoice,
                            onFlip = { viewModel.onIntent(EditorIntent.FlipDropChoice) },
                            modifier = Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 8.dp),
                        )
                    }
                    // Under the ruler, so the marker being edited stays in view above the popup.
                    state.markerHint?.let { MarkerHintChip(it, viewModel::onIntent, Modifier.align(Alignment.TopCenter).padding(top = 40.dp)) }
                    state.markerPopup?.let { MarkerPopupCard(it, viewModel::onIntent, Modifier.align(Alignment.TopCenter).padding(top = 36.dp, start = 8.dp, end = 8.dp)) }
                    if (state.inspectorOpen && inspectorOverlay) {
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            // Swallow touches so they never reach the timeline underneath.
                            modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } },
                        ) {
                            InspectorPanel(
                                state = state,
                                onIntent = viewModel::onIntent,
                                transitionLimit = viewModel::transitionLimit,
                                titleTools = titleTools,
                            )
                        }
                    }
                }
            },
        )
        if (!fullscreen.active) bottomTray()
    }
}

/** What the chrome reads: the editor state without its per-tick playhead, plus what the playhead decides. */
@Immutable
internal data class Chrome(val state: EditorState, val selectedClipVisible: Boolean)

/**
 * The inspector shows the pose and keyframes under the playhead, so while it is open it gets the live
 * state; otherwise the playhead is zeroed and a tick leaves the chrome equal to what it was.
 */
internal fun chromeOf(live: EditorState): Chrome =
    Chrome(if (live.inspectorOpen) live else live.copy(playhead = FrameIndex.ZERO), live.selectedClipVisible)

/** The timecode is the only chrome that follows every tick, so it reads the live state in its own scope. */
@Composable
private fun Timecode(holder: State<EditorState>, modifier: Modifier = Modifier) {
    val text by remember(holder) { derivedStateOf { holder.value.let { formatTimecode(it.playhead.value, it.fps) } } }
    Text(text = text, style = MaterialTheme.typography.labelLarge, modifier = modifier)
}

/**
 * Runs [block] with the live state each time what [key] picks from it changes, cancelling the previous
 * run, like a LaunchedEffect keyed on those values but without recomposing the screen for them.
 */
@Composable
private fun <K> StateEffect(holder: State<EditorState>, key: (EditorState) -> K, block: suspend (EditorState) -> Unit) {
    val currentKey by rememberUpdatedState(key)
    val currentBlock by rememberUpdatedState(block)
    LaunchedEffect(holder) {
        snapshotFlow { currentKey(holder.value) }.distinctUntilChanged().collectLatest { currentBlock(holder.value) }
    }
}

/** Where the finger is during a drag: over the lanes, in the room above them, or off the panel (cancel). */
internal fun dragZoneOf(hit: TimelineHit): DragZone = when (hit.kind) {
    HitKind.ABOVE_LANES, HitKind.RULER, HitKind.MARKER -> DragZone.ABOVE_LANES
    HitKind.OUTSIDE -> DragZone.OUTSIDE
    else -> DragZone.LANES
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
        ToolButton(EditorIcons.Layers, "Add track") { menuOpen = true }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(text = { Text("Video track") }, onClick = { menuOpen = false; onAdd(TrackType.VIDEO) })
            DropdownMenuItem(text = { Text("Audio track") }, onClick = { menuOpen = false; onAdd(TrackType.AUDIO) })
        }
    }
    ToolButton(EditorIcons.Minus, "Remove selected track", enabled = selectedLabel != null, onClick = onRemove)
    ToolButton(EditorIcons.LaneUp, "Move the selected lane up", enabled = selectedLabel != null) { onMove(-1) }
    ToolButton(EditorIcons.LaneDown, "Move the selected lane down", enabled = selectedLabel != null) { onMove(1) }
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
        ToolButton(EditorIcons.SafeZone, "Safe zones for TikTok, Reels and Shorts: ${current?.label ?: "off"}") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("No safe zones") }, onClick = { open = false; onSelect(null) })
            for (platform in SafeZonePlatform.entries) {
                DropdownMenuItem(
                    text = { Text(if (platform == current) "${platform.label} ✓" else platform.label) },
                    onClick = { open = false; onSelect(platform) },
                )
            }
        }
    }
}

/** Pick another canvas shape or size from the same presets as New project. The current one is marked. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CanvasDialog(width: Int, height: Int, colorSpace: ProjectColorSpace, onIntent: (EditorIntent) -> Unit) {
    AlertDialog(
        onDismissRequest = { onIntent(EditorIntent.DismissCanvasDialog) },
        title = { Text("Project ${width}×$height (${aspectLabelOf(width, height)})") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Clips keep their relative position. Changing the canvas clears the undo history.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Colour space", style = MaterialTheme.typography.labelLarge)
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
                        "HDR projects are composited in HLG. SDR clips and titles sit at reference white; " +
                            "the preview is tone-mapped to SDR on screens without HDR.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                for (group in ProjectPresets.resolutionGroups) {
                    Text(group.title, style = MaterialTheme.typography.labelLarge)
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
        dismissButton = { TextButton(onClick = { onIntent(EditorIntent.DismissCanvasDialog) }) { Text("Cancel") } },
    )
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

private fun displayName(asset: MediaAssetDto): String =
    Uri.parse(asset.uri).lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: asset.id

/**
 * Opens the asset and hands its descriptor to the native waveform worker. File access happens on
 * the IO dispatcher; the engine itself must be called from the main thread.
 */
private suspend fun requestWaveform(
    context: android.content.Context,
    engine: TimelineEngine,
    viewModel: EditorViewModel,
    projectId: String,
    asset: MediaAssetDto,
) {
    val prepared = try {
        withContext(Dispatchers.IO) {
            val descriptor = context.contentResolver.openMediaFd(asset.uri, displayName(asset))
            val cache = WaveformCache(File(context.filesDir, "projects/$projectId")).fileFor(asset.id)
            descriptor.detachFd() to cache
        }
    } catch (e: MediaImportException) {
        viewModel.onIntent(EditorIntent.ReportError(e.message ?: "A media file cannot be read: ${displayName(asset)}"))
        return
    }
    try {
        engine.requestWaveform(viewModel.assetKey(asset.id), prepared.first, prepared.second)
    } catch (e: EngineException) {
        viewModel.onIntent(EditorIntent.ReportError(e.message ?: "Waveform extraction failed"))
    }
}

/** Opens a video asset and hands its descriptor to the native thumbnail worker. */
private suspend fun requestThumbnails(
    context: android.content.Context,
    engine: TimelineEngine,
    viewModel: EditorViewModel,
    projectId: String,
    asset: MediaAssetDto,
) {
    val prepared = try {
        withContext(Dispatchers.IO) {
            val descriptor = context.contentResolver.openMediaFd(asset.uri, displayName(asset))
            val dir = ThumbnailCache(File(context.filesDir, "projects/$projectId")).dirFor(asset.id)
            descriptor.detachFd() to dir
        }
    } catch (e: MediaImportException) {
        return  // the clip keeps an empty filmstrip; the file is reported by the verification and the waveform request
    }
    try {
        engine.requestThumbnails(viewModel.assetKey(asset.id), prepared.first, prepared.second)
    } catch (e: EngineException) {
        viewModel.onIntent(EditorIntent.ReportError(e.message ?: "Thumbnail generation failed"))
    }
}

// The scopes overlay covers this share of the preview box, bottom left.
private const val SCOPES_WIDTH = 0.6f
private const val SCOPES_HEIGHT = 0.6f


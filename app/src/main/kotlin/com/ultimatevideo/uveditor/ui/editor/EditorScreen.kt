package com.ultimatevideo.uveditor.ui.editor

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import com.ultimatevideo.uveditor.ui.hub.ProjectPresets
import com.ultimatevideo.uveditor.ui.hub.aspectLabelOf
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
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.ui.preview.wantedOutputSpace
import com.ultimatevideo.uveditor.ui.preview.DisplayHdr
import com.ultimatevideo.uveditor.engine.preview.OutputSpace
import com.ultimatevideo.uveditor.domain.ProjectColorSpace
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.engine.EngineException
import com.ultimatevideo.uveditor.domain.captions.captionCount
import com.ultimatevideo.uveditor.ui.editor.captions.CaptionsHost
import com.ultimatevideo.uveditor.ui.editor.captions.CaptionsIntent
import com.ultimatevideo.uveditor.ui.editor.captions.CaptionsViewModel
import com.ultimatevideo.uveditor.ui.editor.captions.ContentResolverSubtitleSource
import com.ultimatevideo.uveditor.domain.DropKind
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.engine.timeline.DropIndicator
import com.ultimatevideo.uveditor.engine.timeline.EngineStatus
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.still.AndroidStillRasterizer
import com.ultimatevideo.uveditor.engine.title.AndroidTitleRasterizer
import com.ultimatevideo.uveditor.engine.timeline.TimelineEngine
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.engine.timeline.ThumbnailCache
import com.ultimatevideo.uveditor.engine.timeline.WaveformCache
import com.ultimatevideo.uveditor.ui.export.ContentResolverExportIO
import com.ultimatevideo.uveditor.ui.export.ExportHost
import com.ultimatevideo.uveditor.ui.export.ExportInput
import com.ultimatevideo.uveditor.ui.export.ExportIntent
import com.ultimatevideo.uveditor.data.LutStore
import com.ultimatevideo.uveditor.ui.export.ExportViewModel
import com.ultimatevideo.uveditor.engine.export.MediaCodecHdrExportSupport
import com.ultimatevideo.uveditor.engine.export.NativeExportRunner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ultimatevideo.uveditor.ui.preview.PreviewSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** Width from which the media panel is shown beside the editor instead of being left out. */
private val ExpandedWidth = 840.dp

@Composable
fun EditorScreen(viewModel: EditorViewModel, projectId: String, onClose: () -> Unit) {
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

    val exportViewModel: ExportViewModel = viewModel(
        key = "export-$projectId",
        factory = viewModelFactory {
            initializer {
                ExportViewModel(
                    ContentResolverExportIO(context.applicationContext),
                    NativeExportRunner(),
                    titleRasterizer = AndroidTitleRasterizer(),
                    stillRasterizer = AndroidStillRasterizer(context.applicationContext),
                    hdrSupport = MediaCodecHdrExportSupport(),
                    lutLoader = lutStore::load,
                )
            }
        },
    )
    ExportHost(exportViewModel)
    if (state.lutPickerOpen) {
        LutPickerDialog(
            state = lutState,
            onPick = { viewModel.onIntent(EditorIntent.AddLut(it)) },
            onImport = { uri -> lutLibrary.import(uri) { viewModel.onIntent(EditorIntent.AddLut(it.key)) } },
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
                    live.projectName, live.canvasWidth, live.canvasHeight, live.fps, live.timeline, live.assets, live.colorSpace,
                    missingAssetIds = live.missingMedia.keys,
                ),
            ),
        )
    }

    val engine = remember {
        val main = Handler(Looper.getMainLooper())
        TimelineEngine(
            density,
            onThumbnailError = { _, status ->
                // Called on a native worker thread. The clip stays usable without its filmstrip.
                main.post { viewModel.onIntent(EditorIntent.ReportError("Could not generate thumbnails ($status)")) }
            },
        ) { _, status ->
            // Called on a native worker thread. A file without audio is not an error worth showing.
            if (status == EngineStatus.IO_ERROR || status == EngineStatus.CODEC_ERROR) {
                main.post { viewModel.onIntent(EditorIntent.ReportError("Could not read the audio of a clip ($status)")) }
            }
        }
    }
    DisposableEffect(engine) { onDispose { engine.close() } }

    val preview = remember {
        EditorPreview(context, scope, lutLoader = lutStore::load) { viewModel.onIntent(EditorIntent.ReportError(it)) }
    }
    DisposableEffect(preview) { onDispose { preview.close() } }

    val audio = remember {
        EditorAudio(context, scope) { viewModel.onIntent(EditorIntent.ReportError(it)) }
    }
    DisposableEffect(audio, viewModel) {
        viewModel.playbackOutput = audio
        onDispose {
            viewModel.playbackOutput = null
            audio.close()
        }
    }

    // Keep the mixer in step with the committed timeline (not with a drag in progress).
    StateEffect(holder, { listOf(it.timeline, it.assets, it.missingMedia, it.fps, it.isLoading) }) { s ->
        if (s.isLoading) return@StateEffect
        // Files that cannot be read are left out: the mixer would only fail on them.
        audio.update(
            audioSnapshotOf(s.timeline, s.playableAssets, s.fps, viewModel::clipKey, viewModel::assetKey),
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
        { listOf(it.playhead, it.isPlaying, it.visibleTimeline, it.assets, it.missingMedia, it.fps, it.canvasWidth, it.canvasHeight, it.isLoading) },
    ) { s ->
        if (s.isLoading) return@StateEffect
        val layers = previewRequestsAt(s.visibleTimeline, s.playableAssets, s.fps, s.playhead) { viewModel.assetKey(it).toInt() }
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
            override fun onDragMove(hit: TimelineHit) = viewModel.onIntent(EditorIntent.DragMove(hit.frame, hit.trackIndex, dragZoneOf(hit)))
            override fun onDragEnd(commit: Boolean) = viewModel.onIntent(EditorIntent.DragEnd(commit))
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onIntent(EditorIntent.ImportMedia(uris.map(Uri::toString)))
    }
    val launchImport = { importPicker.launch(arrayOf("video/*", "audio/*", "image/*")) }

    // The replacement for a missing file: which asset it is for is remembered while the picker is open.
    var relinkTarget by remember { mutableStateOf<String?>(null) }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val assetId = relinkTarget
        relinkTarget = null
        if (uri != null && assetId != null) viewModel.onIntent(EditorIntent.RelinkAsset(assetId, uri.toString()))
    }

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
            }
        }
    }

    // Publish what the canvas should draw; drags show a provisional timeline until released.
    StateEffect(holder, { listOf(it.visibleTimeline, it.selectedClipId, it.missingMedia, it.fps, it.isLoading) }) { s ->
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
    // Follow the whole project's length until the user zooms by hand. Keyed on the committed
    // timeline, so a clip being dragged does not make the zoom jump. Declared after the snapshot
    // effect so the engine already has the new timeline when it fits.
    StateEffect(holder, { listOf(it.timeline.tracks.maxOfOrNull { track -> track.end.value } ?: 0L, it.isLoading) }) { s ->
        if (!s.isLoading && engine.isAutoFit()) engine.fitToContent()
    }
    StateEffect(holder, { it.playhead }) { s ->
        engine.setPlayhead(s.playhead.value)
        // Playing, or jumping to the next/previous edit, can take the playhead off screen.
        engine.ensureVisible(s.playhead.value)
    }

    val requestedWaveforms = remember { mutableSetOf<String>() }
    StateEffect(holder, { listOf(it.assets, it.missingMedia) }) { s ->
        for (asset in s.playableAssets) {
            // Keyed by the file too, so a relinked asset is requested again from its new file.
            if (!asset.hasAudio || !requestedWaveforms.add("${asset.id}|${asset.uri}")) continue
            requestWaveform(context, engine, viewModel, projectId, asset)
        }
    }
    val requestedThumbnails = remember { mutableSetOf<String>() }
    StateEffect(holder, { listOf(it.assets, it.missingMedia) }) { s ->
        for (asset in s.playableAssets) {
            if (!(asset.hasVideo || asset.isImage) || !requestedThumbnails.add("${asset.id}|${asset.uri}")) continue
            requestThumbnails(context, engine, viewModel, projectId, asset)
        }
    }

    BackHandler { viewModel.onIntent(EditorIntent.Back) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Flush pauses playback; the audio device is then freed until the next play.
        viewModel.onIntent(EditorIntent.Flush)
        audio.releaseDevice()
    }

    CompositionLocalProvider(LocalLutNames provides lutState.names) {
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
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
                // Window size classes without a new dependency: the media panel appears on wide windows.
                // One Row in both layouts, with EditorMain as a stable child: switching between
                // layouts must not recreate the native timeline view, or a late surfaceDestroyed
                // of the old view tears down the surface of the new one.
                val wide = maxWidth >= ExpandedWidth
                Row(modifier = Modifier.fillMaxSize()) {
                    if (wide) {
                        MediaPanel(
                            assets = state.assets,
                            isImporting = state.isImporting,
                            onImport = launchImport,
                            onAdd = { viewModel.onIntent(EditorIntent.AddAsset(it)) },
                            modifier = Modifier.width(280.dp).fillMaxHeight(),
                        )
                    }
                    EditorMain(state, chrome.selectedClipVisible, holder, viewModel, engine, preview, editing, launchImport, openExport, openCaptions, Modifier.weight(1f).fillMaxHeight())
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
    onImport: () -> Unit,
    onExport: () -> Unit,
    onCaptions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSelection = state.selectedClipId != null
    var stickersOpen by remember { mutableStateOf(false) }
    if (state.relinkOpen && state.missingAssets.isNotEmpty()) RelinkDialog(state.missingAssets) { viewModel.onIntent(it) }
    if (state.leaveBlockedBySave) SaveFailedDialog(state.saveError) { viewModel.onIntent(it) }
    var templatesOpen by remember { mutableStateOf(false) }
    if (templatesOpen) {
        TextTemplateSheet(
            onApply = { id, text ->
                viewModel.onIntent(EditorIntent.ApplyTextTemplate(id, text))
                templatesOpen = false
            },
            onDismiss = { templatesOpen = false },
        )
    }
    if (stickersOpen) {
        StickerSheet(
            onPick = {
                viewModel.onIntent(EditorIntent.AddSticker(it))
                stickersOpen = false
            },
            onDismiss = { stickersOpen = false },
        )
    }
    Column(modifier = modifier) {
        Row(
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
            ToolButton(EditorIcons.Undo, "Undo", enabled = state.canUndo) { viewModel.onIntent(EditorIntent.Undo) }
            ToolButton(EditorIcons.Redo, "Redo", enabled = state.canRedo) { viewModel.onIntent(EditorIntent.Redo) }
            ToolButton(EditorIcons.Export, "Export movie", enabled = !state.isPlaying, onClick = onExport)
        }
        MediaBanners(state) { viewModel.onIntent(it) }

        // No background here: the preview is a SurfaceView, and an opaque parent would hide it.
        Box(modifier = Modifier.fillMaxWidth().weight(PREVIEW_WEIGHT), contentAlignment = Alignment.Center) {
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
                // Drag, pinch and twist edit the selected clip while it is under the playhead.
                PreviewGestureLayer(
                    enabled = selectedClipVisible,
                    canvasWidth = state.canvasWidth,
                    canvasHeight = state.canvasHeight,
                    onStep = { panX, panY, zoom, rotation ->
                        viewModel.onIntent(EditorIntent.TransformGesture(panX, panY, zoom, rotation))
                    },
                    onEnd = { viewModel.onIntent(EditorIntent.EndAppearanceEdit(commit = true)) },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(text = "Preview unavailable", style = MaterialTheme.typography.labelLarge)
            }
        }

        // Transport: timecode on the left, previous / play / next centred.
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Timecode(holder, Modifier.align(Alignment.CenterStart))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolButton(EditorIcons.SkipPrevious, "Previous clip boundary") { viewModel.onIntent(EditorIntent.SeekPrevious) }
                ToolButton(
                    icon = if (state.isPlaying) EditorIcons.Pause else EditorIcons.Play,
                    description = if (state.isPlaying) "Pause" else "Play",
                ) { viewModel.onIntent(EditorIntent.TogglePlay) }
                ToolButton(EditorIcons.SkipNext, "Next clip boundary") { viewModel.onIntent(EditorIntent.SeekNext) }
            }
            ToolButton(EditorIcons.Fit, "Fit the whole project", modifier = Modifier.align(Alignment.CenterEnd)) {
                engine.fitToContent()
            }
        }

        // Scrolls sideways when the buttons do not fit a narrow window.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(EditorIcons.Add, "Import media", enabled = !state.isImporting, onClick = onImport)
            ToolButton(EditorIcons.Split, "Split at playhead", enabled = hasSelection) {
                viewModel.onIntent(EditorIntent.SplitAtPlayhead)
            }
            ToolButton(EditorIcons.Delete, "Delete (the base track closes the gap, overlays leave one)", enabled = hasSelection) {
                viewModel.onIntent(EditorIntent.RippleDeleteSelected)
            }
            ToolButton(EditorIcons.CloseGap, "Close gap before clip (the base track does this by itself)", enabled = hasSelection && !state.selectedClipOnBase) {
                viewModel.onIntent(EditorIntent.RippleAppendSelected)
            }
            ToolButton(EditorIcons.Title, "Add a title at the playhead") { viewModel.onIntent(EditorIntent.AddTitle) }
            ToolButton(EditorIcons.Captions, "Captions: type them or import a .srt / .vtt file", onClick = onCaptions)
            ToolButton(EditorIcons.Sticker, "Add a sticker at the playhead") { stickersOpen = true }
            ToolButton(EditorIcons.TextTemplate, "Add an animated text template at the playhead") { templatesOpen = true }
            MarkerMenu(state, viewModel::onIntent)
            ToolButton(
                EditorIcons.Transition,
                "Add a crossfade between the selected clip and the next",
                enabled = state.clipAfterSelected != null && state.selectedTransition == null,
            ) { viewModel.onIntent(EditorIntent.AddTransition) }
            ToolButton(EditorIcons.Tune, "Adjust clip: text, position, scale, rotation, opacity, volume, crossfade", enabled = hasSelection || state.inspectorOpen) {
                viewModel.onIntent(EditorIntent.ToggleInspector)
            }
            TrackControls(
                state.selectedTrackLabel,
                onAdd = { viewModel.onIntent(EditorIntent.AddTrack(it)) },
                onMove = { viewModel.onIntent(EditorIntent.MoveSelectedTrack(it)) },
            ) {
                viewModel.onIntent(EditorIntent.RemoveSelectedTrack)
            }
            ToolButton(EditorIcons.CanvasFormat, "Change the canvas format and resolution") {
                viewModel.onIntent(EditorIntent.ShowCanvasDialog)
            }
            SafeZoneMenu(state.safeZone) { viewModel.onIntent(EditorIntent.SetSafeZone(it)) }
        }
        if (state.canvasDialogOpen) CanvasDialog(state.canvasWidth, state.canvasHeight, state.colorSpace, viewModel::onIntent)

        // The inspector is drawn over the timeline instead of replacing it, so the native timeline view
        // is never recreated (a late surfaceDestroyed of an old view would tear down the new surface).
        Box(modifier = Modifier.fillMaxWidth().weight(TIMELINE_WEIGHT)) {
            TimelineHost(
                engine = engine,
                onTap = { viewModel.onIntent(EditorIntent.TapTimeline(it)) },
                editing = editing,
                modifier = Modifier.fillMaxSize(),
            )
            if (state.inspectorOpen) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    // Swallow touches so they never reach the timeline underneath.
                    modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } },
                ) {
                    InspectorPanel(state = state, onIntent = viewModel::onIntent, transitionLimit = viewModel::transitionLimit)
                }
            }
        }
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
private fun dragZoneOf(hit: TimelineHit): DragZone = when (hit.kind) {
    HitKind.ABOVE_LANES, HitKind.RULER -> DragZone.ABOVE_LANES
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
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(description) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(40.dp)) {
            Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun MediaPanel(
    assets: List<MediaAssetDto>,
    isImporting: Boolean,
    onImport: () -> Unit,
    onAdd: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Media", style = MaterialTheme.typography.titleMedium)
        Button(onClick = onImport, enabled = !isImporting, modifier = Modifier.fillMaxWidth()) {
            Text(if (isImporting) "Importing…" else "Import media")
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(assets, key = { it.id }) { asset ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = displayName(asset), maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = formatTimecode(asset.durationFrames, FrameRate(asset.nativeFpsNum, asset.nativeFpsDen)),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    TextButton(onClick = { onAdd(asset.id) }) { Text("Add") }
                }
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
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(asset.uri), "r")
                ?: throw FileNotFoundException(asset.uri)
            val cache = WaveformCache(File(context.filesDir, "projects/$projectId")).fileFor(asset.id)
            descriptor.detachFd() to cache
        }
    } catch (e: FileNotFoundException) {
        viewModel.onIntent(EditorIntent.ReportError("A media file is missing: ${displayName(asset)}"))
        return
    } catch (e: SecurityException) {
        viewModel.onIntent(EditorIntent.ReportError("No permission to read ${displayName(asset)}"))
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
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(asset.uri), "r")
                ?: throw FileNotFoundException(asset.uri)
            val dir = ThumbnailCache(File(context.filesDir, "projects/$projectId")).dirFor(asset.id)
            descriptor.detachFd() to dir
        }
    } catch (e: FileNotFoundException) {
        return  // the waveform request already reports a missing file; no second message
    } catch (e: SecurityException) {
        return
    }
    try {
        engine.requestThumbnails(viewModel.assetKey(asset.id), prepared.first, prepared.second)
    } catch (e: EngineException) {
        viewModel.onIntent(EditorIntent.ReportError(e.message ?: "Thumbnail generation failed"))
    }
}

private const val PREVIEW_WEIGHT = 0.4f
private const val TIMELINE_WEIGHT = 0.6f

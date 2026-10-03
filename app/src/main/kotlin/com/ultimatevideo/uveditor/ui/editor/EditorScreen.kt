package com.ultimatevideo.uveditor.ui.editor

import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.IconButton
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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import com.ultimatevideo.uveditor.domain.TrackType
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.ultimatevideo.uveditor.engine.timeline.EngineStatus
import com.ultimatevideo.uveditor.engine.timeline.TimelineEngine
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.engine.timeline.ThumbnailCache
import com.ultimatevideo.uveditor.engine.timeline.WaveformCache
import com.ultimatevideo.uveditor.ui.export.ContentResolverExportIO
import com.ultimatevideo.uveditor.ui.export.ExportHost
import com.ultimatevideo.uveditor.ui.export.ExportInput
import com.ultimatevideo.uveditor.ui.export.ExportIntent
import com.ultimatevideo.uveditor.ui.export.ExportViewModel
import com.ultimatevideo.uveditor.engine.export.NativeExportRunner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ultimatevideo.uveditor.ui.preview.PreviewSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException

/** Width from which the media panel is shown beside the editor instead of being left out. */
private val ExpandedWidth = 840.dp

@Composable
fun EditorScreen(viewModel: EditorViewModel, projectId: String, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val exportViewModel: ExportViewModel = viewModel(
        key = "export-$projectId",
        factory = viewModelFactory {
            initializer { ExportViewModel(ContentResolverExportIO(context.applicationContext), NativeExportRunner()) }
        },
    )
    ExportHost(exportViewModel)
    val openExport = {
        // The dialog works from what the editor holds right now; the autosave is not involved.
        exportViewModel.onIntent(
            ExportIntent.Open(
                ExportInput(state.projectName, state.canvasWidth, state.canvasHeight, state.fps, state.timeline, state.assets),
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
        EditorPreview(context, scope) { viewModel.onIntent(EditorIntent.ReportError(it)) }
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
    LaunchedEffect(state.timeline, state.assets, state.fps, state.isLoading) {
        if (state.isLoading) return@LaunchedEffect
        audio.update(
            audioSnapshotOf(state.timeline, state.assets, state.fps, viewModel::clipKey, viewModel::assetKey),
            state.assets,
            viewModel::assetKey,
        )
    }

    // Show the composite under the playhead (every video track, bottom first); while playing this runs
    // on every tick. It follows the visible timeline, so a transform being dragged shows live.
    LaunchedEffect(state.playhead, state.visibleTimeline, state.assets, state.fps, state.canvasWidth, state.canvasHeight, state.isLoading) {
        if (state.isLoading) return@LaunchedEffect
        val layers = previewLayersAt(state.visibleTimeline, state.playhead).mapNotNull { target ->
            val asset = state.assets.firstOrNull { it.id == target.clip.assetId } ?: return@mapNotNull null
            if (!asset.hasVideo) return@mapNotNull null
            PreviewRequest(
                assetKey = viewModel.assetKey(asset.id).toInt(),
                uri = asset.uri,
                sourceFrame = target.sourceFrame,
                fpsNum = state.fps.num,
                fpsDen = state.fps.den,
                transform = target.clip.transform,
            )
        }
        // In a gap the preview keeps its last frame.
        if (layers.isNotEmpty()) preview.show(PreviewScene(state.canvasWidth, state.canvasHeight, layers))
    }

    val editing = remember(viewModel) {
        object : TimelineEditing {
            override fun canDrag(hit: TimelineHit) = viewModel.canDrag(hit)
            override fun onDragStart(hit: TimelineHit) = viewModel.onIntent(EditorIntent.DragStart(hit))
            override fun onDragMove(hit: TimelineHit) = viewModel.onIntent(EditorIntent.DragMove(hit.frame, hit.trackIndex))
            override fun onDragEnd(commit: Boolean) = viewModel.onIntent(EditorIntent.DragEnd(commit))
        }
    }

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onIntent(EditorIntent.ImportMedia(uris.map(Uri::toString)))
    }
    val launchImport = { importPicker.launch(arrayOf("video/*", "audio/*")) }

    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is EditorEffect.ShowMessage -> {
                    snackbar.currentSnackbarData?.dismiss()
                    scope.launch { snackbar.showSnackbar(effect.text) }
                }
                EditorEffect.Close -> onClose()
            }
        }
    }

    // Publish what the canvas should draw; drags show a provisional timeline until released.
    LaunchedEffect(state.visibleTimeline, state.selectedClipId, state.fps, state.isLoading) {
        if (state.isLoading) return@LaunchedEffect
        try {
            engine.setSnapshot(viewModel.snapshotOf(state))
        } catch (e: EngineException) {
            viewModel.onIntent(EditorIntent.ReportError(e.message ?: "The timeline could not be drawn"))
        }
    }
    // Follow the whole project's length until the user zooms by hand. Keyed on the committed
    // timeline, so a clip being dragged does not make the zoom jump. Declared after the snapshot
    // effect so the engine already has the new timeline when it fits.
    val committedEnd = state.timeline.tracks.maxOfOrNull { it.end.value } ?: 0L
    LaunchedEffect(committedEnd, state.isLoading) {
        if (!state.isLoading && engine.isAutoFit()) engine.fitToContent()
    }
    LaunchedEffect(state.playhead) { engine.setPlayhead(state.playhead.value) }

    val requestedWaveforms = remember { mutableSetOf<String>() }
    LaunchedEffect(state.assets) {
        for (asset in state.assets) {
            if (!asset.hasAudio || !requestedWaveforms.add(asset.id)) continue
            requestWaveform(context, engine, viewModel, projectId, asset)
        }
    }
    val requestedThumbnails = remember { mutableSetOf<String>() }
    LaunchedEffect(state.assets) {
        for (asset in state.assets) {
            if (!asset.hasVideo || !requestedThumbnails.add(asset.id)) continue
            requestThumbnails(context, engine, viewModel, projectId, asset)
        }
    }

    BackHandler { viewModel.onIntent(EditorIntent.Back) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onIntent(EditorIntent.Flush) }

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
                    EditorMain(state, viewModel, engine, preview, editing, launchImport, openExport, Modifier.weight(1f).fillMaxHeight())
                }
            }
        }
    }
}

@Composable
private fun EditorMain(
    state: EditorState,
    viewModel: EditorViewModel,
    engine: TimelineEngine,
    preview: EditorPreview,
    editing: TimelineEditing,
    onImport: () -> Unit,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasSelection = state.selectedClipId != null
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

        // No background here: the preview is a SurfaceView, and an opaque parent would hide it.
        Box(modifier = Modifier.fillMaxWidth().weight(PREVIEW_WEIGHT), contentAlignment = Alignment.Center) {
            val previewEngine = preview.engine
            if (previewEngine != null) {
                PreviewSurface(previewEngine, Modifier.fillMaxSize())
                // Drag, pinch and twist edit the selected clip while it is under the playhead.
                PreviewGestureLayer(
                    enabled = state.selectedClipVisible,
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
            Text(
                text = formatTimecode(state.playhead.value, state.fps),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.CenterStart),
            )
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

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolButton(EditorIcons.Add, "Import media", enabled = !state.isImporting, onClick = onImport)
            ToolButton(EditorIcons.Split, "Split at playhead", enabled = hasSelection) {
                viewModel.onIntent(EditorIntent.SplitAtPlayhead)
            }
            ToolButton(EditorIcons.Delete, "Delete and close gap", enabled = hasSelection) {
                viewModel.onIntent(EditorIntent.RippleDeleteSelected)
            }
            ToolButton(EditorIcons.CloseGap, "Close gap before clip", enabled = hasSelection) {
                viewModel.onIntent(EditorIntent.RippleAppendSelected)
            }
            ToolButton(EditorIcons.Tune, "Adjust clip: position, scale, rotation, opacity, volume", enabled = hasSelection || state.inspectorOpen) {
                viewModel.onIntent(EditorIntent.ToggleInspector)
            }
            TrackControls(state.selectedTrackLabel, onAdd = { viewModel.onIntent(EditorIntent.AddTrack(it)) }) {
                viewModel.onIntent(EditorIntent.RemoveSelectedTrack)
            }
        }

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
                    InspectorPanel(state = state, onIntent = viewModel::onIntent)
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
    Text(
        text = selectedLabel ?: "",
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier.padding(start = 4.dp).width(28.dp),
    )
}

/** Small icon-only button; [description] is read by screen readers. */
@Composable
private fun ToolButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(40.dp)) {
        Icon(imageVector = icon, contentDescription = description, modifier = Modifier.size(22.dp))
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

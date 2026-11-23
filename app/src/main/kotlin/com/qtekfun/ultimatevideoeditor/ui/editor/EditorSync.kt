package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CoroutineScope
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyUiState
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MediaImportException
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.data.openMediaFd
import com.qtekfun.ultimatevideoeditor.domain.DropKind
import com.qtekfun.ultimatevideoeditor.engine.EngineException
import com.qtekfun.ultimatevideoeditor.engine.timeline.DropIndicator
import com.qtekfun.ultimatevideoeditor.engine.timeline.ThumbnailCache
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine
import com.qtekfun.ultimatevideoeditor.engine.timeline.WaveformCache
import com.qtekfun.ultimatevideoeditor.proxy.MediaPurpose
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyViewModel
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import com.qtekfun.ultimatevideoeditor.ui.text.resolve
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** The mixer that plays the timeline's sound; it follows the committed timeline and is owned by the editor. */
@Composable
internal fun rememberEditorAudio(context: Context, scope: CoroutineScope, viewModel: EditorViewModel, holder: State<EditorState>): EditorAudio {
    val audio = remember {
        EditorAudio(
            context,
            scope,
            onAssetFailure = { key, message -> viewModel.onIntent(EditorIntent.MediaFailureReported(key, message)) },
        ) { viewModel.onIntent(EditorIntent.ReportError(it)) }
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
    return audio
}

/** Shows the composite under the playhead in the native preview (and keeps the proxy label up to date). */
@Composable
internal fun PreviewSyncEffect(
    holder: State<EditorState>,
    preview: EditorPreview,
    proxyVm: ProxyViewModel,
    proxyHolder: State<ProxyUiState>,
    previewProxyNote: MutableState<PreviewProxyNote>,
    viewModel: EditorViewModel,
) {
    // Show the composite under the playhead (every video track, bottom first). Paused, every change
    // shows a still frame. Playing, the playhead is the audio clock: the native preview runs by
    // itself and is only re-anchored when the composition changes or drifts from it, never seeked
    // per tick. It follows the visible timeline, so a transform being dragged shows live.
    StateEffect(
        holder,
        // The proxy version changes when the switch flips or a proxy finishes: the preview then opens another file.
        { listOf(it.playhead, it.isPlaying, it.visibleTimeline, it.assets, it.missingMedia, it.mediaChecked, it.fps, it.canvasWidth, it.canvasHeight, it.isLoading, it.hdr, proxyHolder.value.resolveVersion) },
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
            // Clips of files that cannot be read show a "Media missing" card in the preview (never in an export).
            unreadable = s.unreadableAssets,
            hdr = s.hdr,
        ) { viewModel.assetKey(it).toInt() }
        val scene = PreviewScene(s.canvasWidth, s.canvasHeight, layers)
        previewProxyNote.value = previewProxyNote(layers, s.assets)
        when {
            s.isPlaying -> preview.follow(scene, s.playhead.value, s.fps)
            layers.isNotEmpty() -> preview.show(scene)
            // In a gap, or at the end after playing, the preview keeps its last frame.
            else -> preview.stopFollowing()
        }
    }
}

/**
 * Publishes the committed and provisional timeline to the native canvas, in a fixed order (the later effects name what the
 * earlier ones published), and asks for waveforms and thumbnails of the media.
 */
@Composable
internal fun TimelineSyncEffects(
    holder: State<EditorState>,
    engine: TimelineEngine,
    viewModel: EditorViewModel,
    projectId: String,
    proxyVm: ProxyViewModel,
    thumbnailFailures: ThumbnailFailures,
) {
    val context = LocalContext.current
    // Publish what the canvas should draw; drags show a provisional timeline until released.
    StateEffect(holder, { listOf(it.visibleTimeline, it.selectedClipId, it.selectedClipIds, it.missingMedia, it.fps, it.isLoading) }) { s ->
        if (s.isLoading) return@StateEffect
        try {
            engine.setSnapshot(viewModel.snapshotOf(s) { it.resolve(context) })
        } catch (e: EngineException) {
            viewModel.onIntent(EditorIntent.ReportText(reasonOf(e, UiText.res(R.string.ed_2a_timeline_not_drawn))))
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
            viewModel.onIntent(EditorIntent.ReportText(reasonOf(e, UiText.res(R.string.ed_2a_timeline_not_drawn))))
        }
    }
    // The clips being dragged or trimmed (lifted with a shadow) and the snap line. After the snapshot effect so the keys
    // name clips the engine already has.
    StateEffect(holder, { listOf(it.dragOverlay, it.visibleTimeline) }) { s ->
        val overlay = s.dragOverlay
        try {
            engine.setDragOverlay(overlay?.guideFrame, overlay?.let { viewModel.dragOverlayKeys(it) } ?: LongArray(0))
        } catch (e: EngineException) {
            viewModel.onIntent(EditorIntent.ReportText(reasonOf(e, UiText.res(R.string.ed_2a_timeline_not_drawn))))
        }
    }
    // The lane being dragged by its header and where it would land. After the snapshot effect, like the drop hint,
    // so the indices refer to the timeline the engine already has.
    StateEffect(holder, { listOf(it.laneDrag, it.visibleTimeline) }) { s ->
        val drag = s.laneDrag
        try {
            engine.setLaneDrag(drag?.fromIndex ?: -1, drag?.toIndex ?: -1)
        } catch (e: EngineException) {
            viewModel.onIntent(EditorIntent.ReportText(reasonOf(e, UiText.res(R.string.ed_2a_timeline_not_drawn))))
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
}

/**
 * Runs [block] with the live state each time what [key] picks from it changes, cancelling the previous
 * run, like a LaunchedEffect keyed on those values but without recomposing the screen for them.
 */
@Composable
internal fun <K> StateEffect(holder: State<EditorState>, key: (EditorState) -> K, block: suspend (EditorState) -> Unit) {
    val currentKey by rememberUpdatedState(key)
    val currentBlock by rememberUpdatedState(block)
    LaunchedEffect(holder) {
        snapshotFlow { currentKey(holder.value) }.distinctUntilChanged().collectLatest { currentBlock(holder.value) }
    }
}

internal fun displayName(asset: MediaAssetDto): String =
    Uri.parse(asset.uri).lastPathSegment?.substringAfterLast('/')?.ifBlank { null } ?: asset.id

/**
 * Opens the asset and hands its descriptor to the native waveform worker. File access happens on
 * the IO dispatcher; the engine itself must be called from the main thread.
 */
internal suspend fun requestWaveform(
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
        viewModel.onIntent(EditorIntent.ReportText(reasonOf(e, UiText.res(R.string.ed_2a_media_unreadable, displayName(asset)))))
        return
    }
    try {
        engine.requestWaveform(viewModel.assetKey(asset.id), prepared.first, prepared.second)
    } catch (e: EngineException) {
        viewModel.onIntent(EditorIntent.ReportText(reasonOf(e, UiText.res(R.string.ed_2a_waveform_failed))))
    }
}

/** Opens a video asset and hands its descriptor to the native thumbnail worker. */
internal suspend fun requestThumbnails(
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
        viewModel.onIntent(EditorIntent.ReportText(reasonOf(e, UiText.res(R.string.ed_2a_thumbnail_failed))))
    }
}

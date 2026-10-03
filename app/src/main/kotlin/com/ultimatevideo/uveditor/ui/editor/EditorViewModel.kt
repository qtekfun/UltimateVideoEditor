package com.ultimatevideo.uveditor.ui.editor

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectStore
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.EditCommand
import com.ultimatevideo.uveditor.domain.EditError
import com.ultimatevideo.uveditor.domain.EditHistory
import com.ultimatevideo.uveditor.domain.EditResult
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Snap
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.TrimEdge
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.SnapshotClip
import com.ultimatevideo.uveditor.engine.timeline.SnapshotTrackType
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.engine.timeline.TimelineSnapshot
import com.ultimatevideo.uveditor.mvi.MviViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs

/**
 * Holds the editing session of one project: the domain [Timeline] behind an undo history, the media
 * library, and the playhead/selection. Touch gestures arrive as intents already hit-tested by the
 * native canvas. Edits are autosaved (debounced) through [store].
 */
class EditorViewModel(
    private val projectId: String,
    private val store: ProjectStore,
    private val importer: MediaImporter,
    private val idGenerator: () -> String = { UUID.randomUUID().toString().take(ID_LENGTH) },
    private val saveDebounceMillis: Long = DEFAULT_SAVE_DEBOUNCE_MILLIS,
    private val nanoClock: () -> Long = System::nanoTime,
) : MviViewModel<EditorState, EditorIntent, EditorEffect>(EditorState()) {

    private enum class DragMode { MOVE, TRIM_START, TRIM_END, PLAYHEAD }

    private class DragSession(val clipId: String, val mode: DragMode, val grabOffset: Long)

    private val clipKeys = KeyRegistry()
    private val assetKeys = KeyRegistry()

    private var history = EditHistory(Timeline())
    private var baseProject: ProjectDto? = null
    private var drag: DragSession? = null
    private var pendingDragCommand: EditCommand? = null
    private var saveJob: Job? = null
    private var playJob: Job? = null

    /** Set by the screen once the audio engine is up. Until then the transport uses the system clock. */
    var playbackOutput: PlaybackOutput? = null
    private var dirty = false

    init {
        load()
    }

    override fun onIntent(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.TapTimeline -> tap(intent.hit)
            is EditorIntent.SetPlayhead -> seekTo(intent.frame)
            is EditorIntent.DragStart -> dragStart(intent.hit)
            is EditorIntent.DragMove -> dragMove(intent.frame, intent.trackIndex)
            is EditorIntent.DragEnd -> dragEnd(intent.commit)
            EditorIntent.SplitAtPlayhead -> splitAtPlayhead()
            EditorIntent.RippleDeleteSelected -> withSelection { execute(EditCommand.RippleDelete(it)) }
            EditorIntent.RippleAppendSelected -> withSelection { execute(EditCommand.RippleAppend(it)) }
            EditorIntent.TogglePlay -> togglePlay()
            is EditorIntent.AddTrack -> addTrack(intent.type)
            EditorIntent.RemoveSelectedTrack -> removeSelectedTrack()
            EditorIntent.SeekPrevious -> seekTo(previousEditPoint())
            EditorIntent.SeekNext -> seekTo(nextEditPoint())
            EditorIntent.Undo -> undo()
            EditorIntent.Redo -> redo()
            is EditorIntent.ImportMedia -> importMedia(intent.uris)
            is EditorIntent.AddAsset -> addAssetById(intent.assetId)
            EditorIntent.Flush -> flush(thenClose = false)
            EditorIntent.Back -> flush(thenClose = true)
            is EditorIntent.ReportError -> emit(EditorEffect.ShowMessage(intent.message))
        }
    }

    /** True if a drag that starts on [hit] should edit the clip instead of scrolling the timeline. */
    fun canDrag(hit: TimelineHit): Boolean {
        // The playhead (or anywhere on the ruler) scrubs; clips only drag once selected.
        if (hit.kind == HitKind.PLAYHEAD || hit.kind == HitKind.RULER) return true
        val selected = state.value.selectedClipId ?: return false
        val onClip = hit.kind == HitKind.CLIP || hit.kind == HitKind.CLIP_LEFT_EDGE || hit.kind == HitKind.CLIP_RIGHT_EDGE
        return onClip && clipKeys.idFor(hit.clipKey) == selected
    }

    /** Stable native key for [clipId]; the same key the timeline canvas uses. */
    fun clipKey(clipId: String): Long = clipKeys.keyFor(clipId)

    /** Native key for [assetId]; used to request its waveform. */
    fun assetKey(assetId: String): Long = assetKeys.keyFor(assetId)

    /** Encodes [state] for the native canvas. All keys are stable for the life of this ViewModel. */
    fun snapshotOf(state: EditorState): TimelineSnapshot {
        val timeline = state.visibleTimeline
        val tracks = timeline.tracks.map {
            when (it.type) {
                TrackType.VIDEO -> SnapshotTrackType.VIDEO
                TrackType.AUDIO -> SnapshotTrackType.AUDIO
                TrackType.TITLE -> SnapshotTrackType.TITLE
            }
        }
        val clips = timeline.tracks.flatMapIndexed { trackIndex, track ->
            track.clips.map { clip ->
                SnapshotClip(
                    clipKey = clipKeys.keyFor(clip.id),
                    trackIndex = trackIndex,
                    assetKey = clip.assetId?.let(assetKeys::keyFor) ?: NO_ASSET_KEY,
                    startFrame = clip.timelineStart.value,
                    durationFrames = clip.durationFrames,
                    sourceInFrame = clip.sourceIn.value,
                    sourceFpsNum = state.fps.num,
                    sourceFpsDen = state.fps.den,
                    selected = clip.id == state.selectedClipId,
                )
            }
        }
        return TimelineSnapshot(state.fps.num, state.fps.den, tracks, clips)
    }

    // region loading and saving

    private fun load() {
        viewModelScope.launch {
            try {
                val project = store.load(projectId)
                val timeline = withDefaultTracks(TimelineMapper.toTimeline(project))
                baseProject = project
                history = EditHistory(timeline)
                reduce {
                    copy(
                        isLoading = false,
                        projectName = project.name,
                        fps = FrameRate(project.settings.fpsNum, project.settings.fpsDen),
                        timeline = timeline,
                        selectedTrackId = timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }?.id,
                        assets = project.mediaLibrary,
                    )
                }
                refreshAssetTracks(project.mediaLibrary)
            } catch (e: ProjectError) {
                reduce { copy(isLoading = false, loadError = e.message) }
            }
        }
    }

    /**
     * Projects saved before `hasVideo`/`hasAudio` existed read as "has both", so a file without an
     * audio track would be sent to the mixer and fail. Re-probing fixes the flags once and the
     * project is saved with them. A file that cannot be probed keeps its flags: its absence is
     * reported by the waveform, audio and preview paths when they open it.
     */
    private suspend fun refreshAssetTracks(loaded: List<MediaAssetDto>) {
        val probed = HashMap<String, Pair<Boolean, Boolean>>()
        for (asset in loaded) {
            try {
                val media = importer.import(asset.uri)
                probed[asset.id] = media.hasVideo to media.hasAudio
            } catch (e: MediaImportException) {
                continue
            }
        }
        val stale = probed.filter { (id, flags) ->
            state.value.assets.firstOrNull { it.id == id }?.let { it.hasVideo to it.hasAudio != flags } == true
        }
        if (stale.isEmpty()) return
        // Apply to the current list: media may have been imported while probing.
        reduce {
            copy(
                assets = assets.map { asset ->
                    stale[asset.id]?.let { (video, audio) -> asset.copy(hasVideo = video, hasAudio = audio) } ?: asset
                },
            )
        }
        scheduleSave()
    }

    private fun scheduleSave() {
        dirty = true
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(saveDebounceMillis)
            persist()
        }
    }

    private fun flush(thenClose: Boolean) {
        pausePlayback()
        viewModelScope.launch {
            saveJob?.cancelAndJoin()
            if (dirty) persist()
            if (thenClose) emit(EditorEffect.Close)
        }
    }

    private suspend fun persist() {
        val base = baseProject ?: return
        val dto = TimelineMapper.toDto(base, history.timeline, state.value.assets)
        try {
            store.save(dto)
            baseProject = dto
            dirty = false
        } catch (e: ProjectError) {
            emit(EditorEffect.ShowMessage("Could not save the project: ${e.message}"))
        }
    }

    private fun withDefaultTracks(timeline: Timeline): Timeline {
        var tracks = timeline.tracks
        if (tracks.none { it.type == TrackType.VIDEO }) tracks = listOf(Track(uniqueTrackId(tracks, "track-v"), TrackType.VIDEO)) + tracks
        if (tracks.none { it.type == TrackType.AUDIO }) tracks = tracks + Track(uniqueTrackId(tracks, "track-a"), TrackType.AUDIO)
        return Timeline(tracks)
    }

    private fun uniqueTrackId(tracks: List<Track>, prefix: String): String =
        generateSequence(1) { it + 1 }.map { "$prefix$it" }.first { id -> tracks.none { it.id == id } }

    // endregion

    // region selection, playhead, history

    private fun tap(hit: TimelineHit) {
        when (hit.kind) {
            HitKind.RULER, HitKind.PLAYHEAD -> seekTo(hit.frame)
            HitKind.CLIP, HitKind.CLIP_LEFT_EDGE, HitKind.CLIP_RIGHT_EDGE -> {
                val clipId = clipKeys.idFor(hit.clipKey)
                reduce { copy(selectedClipId = clipId, selectedTrackId = clipId?.let { timeline.trackOfClip(it)?.id } ?: selectedTrackId) }
            }
            HitKind.EMPTY_TRACK -> reduce {
                copy(selectedClipId = null, selectedTrackId = timeline.tracks.getOrNull(hit.trackIndex)?.id ?: selectedTrackId)
            }
            HitKind.NONE -> reduce { copy(selectedClipId = null) }
        }
    }

    private fun setPlayhead(frame: Long) {
        val clamped = frame.coerceAtLeast(0)
        reduce { copy(playhead = FrameIndex(clamped)) }
        if (!state.value.isPlaying) playbackOutput?.seek(clamped)
    }

    /**
     * Moves the playhead in real time while playing. This is only the transport clock: it does not
     * drive video or audio output yet. Elapsed time is converted to frames with integer math.
     */
    private fun togglePlay() {
        if (state.value.isPlaying) pausePlayback() else startPlayback()
    }

    private fun timelineEnd(): Long = history.timeline.tracks.maxOfOrNull { it.end }?.value ?: 0

    private fun startPlayback() {
        val end = timelineEnd()
        if (end <= 0) {
            emit(EditorEffect.ShowMessage("Add a clip to play the timeline"))
            return
        }
        val fps = state.value.fps
        val from = state.value.playhead.value.takeIf { it < end } ?: 0
        val startedAt = nanoClock()
        reduce { copy(isPlaying = true, playhead = FrameIndex(from)) }
        playbackOutput?.play(from)
        playJob?.cancel()
        playJob = viewModelScope.launch {
            while (true) {
                delay(PLAY_TICK_MILLIS)
                // The audio device is the master clock; the system clock is the fallback. Never
                // go backwards: the heard position can lag the start frame by the device latency.
                val heard = playbackOutput?.heardFrame()
                val elapsedMicros = (nanoClock() - startedAt) / NANOS_PER_MICRO
                val frame = maxOf(from, heard ?: (from + fps.microsToFrames(elapsedMicros)))
                // Re-read the end each tick: the timeline can be edited while playing.
                val currentEnd = timelineEnd()
                if (frame >= currentEnd) {
                    reduce { copy(playhead = FrameIndex(currentEnd), isPlaying = false) }
                    playbackOutput?.pause()
                    return@launch
                }
                reduce { copy(playhead = FrameIndex(frame)) }
            }
        }
    }

    /** Clip starts and ends on every track, plus the timeline start. */
    private fun editPoints(): List<Long> = buildList {
        add(0L)
        for (track in history.timeline.tracks) {
            for (clip in track.clips) {
                add(clip.timelineStart.value)
                add(clip.timelineEnd.value)
            }
        }
    }

    private fun previousEditPoint(): Long = editPoints().filter { it < state.value.playhead.value }.maxOrNull() ?: 0

    private fun nextEditPoint(): Long {
        val playhead = state.value.playhead.value
        return editPoints().filter { it > playhead }.minOrNull() ?: playhead
    }

    private fun seekTo(frame: Long) {
        val wasPlaying = state.value.isPlaying
        pausePlayback()
        setPlayhead(frame)
        if (wasPlaying) startPlayback()
    }

    private fun pausePlayback() {
        playJob?.cancel()
        playJob = null
        if (state.value.isPlaying) {
            reduce { copy(isPlaying = false) }
            playbackOutput?.pause()
        }
    }

    private fun undo() {
        history = history.undo()
        syncFromHistory()
        scheduleSave()
    }

    private fun redo() {
        history = history.redo()
        syncFromHistory()
        scheduleSave()
    }

    private fun syncFromHistory() {
        val committed = history.timeline
        val canUndo = history.canUndo
        val canRedo = history.canRedo
        reduce {
            copy(
                timeline = committed,
                dragPreview = null,
                canUndo = canUndo,
                canRedo = canRedo,
                selectedClipId = selectedClipId?.takeIf { committed.trackOfClip(it) != null },
                // If the selected track vanished (undo, removal), fall back to the first video track.
                selectedTrackId = selectedTrackId?.takeIf { committed.track(it) != null }
                    ?: committed.tracks.firstOrNull { it.type == TrackType.VIDEO }?.id,
            )
        }
    }

    // endregion

    // region editing

    private inline fun withSelection(block: (String) -> Unit) {
        val selected = state.value.selectedClipId
        if (selected == null) {
            emit(EditorEffect.ShowMessage("Select a clip first"))
            return
        }
        block(selected)
    }

    /** Runs [command] through the undo history; reports the reason and returns false on failure. */
    private fun execute(command: EditCommand): Boolean = when (val result = history.execute(command)) {
        is EditResult.Success -> {
            history = result.value
            syncFromHistory()
            scheduleSave()
            true
        }
        is EditResult.Failure -> {
            emit(EditorEffect.ShowMessage(describe(result.error)))
            false
        }
    }

    /**
     * New video tracks go on top of the video stack (they overlay the ones below); new audio
     * tracks go at the bottom. Track order is display order, top to bottom.
     */
    private fun addTrack(type: TrackType) {
        if (type == TrackType.TITLE) return
        val tracks = history.timeline.tracks
        val index = if (type == TrackType.VIDEO) tracks.indexOfFirst { it.type == TrackType.VIDEO }.takeIf { it >= 0 } ?: 0 else tracks.size
        val prefix = if (type == TrackType.VIDEO) "track-v" else "track-a"
        val track = Track(uniqueTrackId(tracks, prefix), type)
        if (execute(EditCommand.AddTrack(track, index))) reduce { copy(selectedTrackId = track.id, selectedClipId = null) }
    }

    private fun removeSelectedTrack() {
        val track = history.timeline.tracks.firstOrNull { it.id == state.value.selectedTrackId }
        if (track == null) {
            emit(EditorEffect.ShowMessage("Tap a track to select it first"))
            return
        }
        if (history.timeline.tracks.count { it.type == track.type } <= 1) {
            emit(EditorEffect.ShowMessage("Keep at least one ${track.type.name.lowercase()} track"))
            return
        }
        execute(EditCommand.RemoveTrack(track.id))
    }

    private fun splitAtPlayhead() = withSelection { clipId ->
        val track = history.timeline.trackOfClip(clipId) ?: return@withSelection
        execute(EditCommand.Split(track.id, state.value.playhead, "$clipId~${idGenerator()}"))
    }

    private fun dragStart(hit: TimelineHit) {
        if (!canDrag(hit)) return
        if (hit.kind == HitKind.PLAYHEAD || hit.kind == HitKind.RULER) {
            pausePlayback()
            drag = DragSession(clipId = "", mode = DragMode.PLAYHEAD, grabOffset = 0)
            pendingDragCommand = null
            setPlayhead(hit.frame)
            return
        }
        val clipId = clipKeys.idFor(hit.clipKey) ?: return
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return
        val mode = when (hit.kind) {
            HitKind.CLIP_LEFT_EDGE -> DragMode.TRIM_START
            HitKind.CLIP_RIGHT_EDGE -> DragMode.TRIM_END
            else -> DragMode.MOVE
        }
        drag = DragSession(clipId, mode, hit.frame - clip.timelineStart.value)
        pendingDragCommand = null
    }

    private fun dragMove(frame: Long, trackIndex: Int) {
        val session = drag ?: return
        if (session.mode == DragMode.PLAYHEAD) {
            setPlayhead(frame)
            return
        }
        val base = history.timeline
        val sourceTrack = base.trackOfClip(session.clipId) ?: return
        val clip = sourceTrack.clip(session.clipId) ?: return
        val playhead = state.value.playhead
        val command = when (session.mode) {
            DragMode.MOVE -> {
                val destination = base.tracks.getOrNull(trackIndex)
                    ?.takeIf { it.type == sourceTrack.type && it.id != sourceTrack.id }
                    ?.id
                EditCommand.Move(
                    clipId = session.clipId,
                    newStart = FrameIndex((frame - session.grabOffset).coerceAtLeast(0)),
                    toTrackId = destination,
                    snap = Snap(playhead, SNAP_THRESHOLD_FRAMES),
                )
            }
            DragMode.TRIM_START -> EditCommand.Trim(
                clipId = session.clipId,
                edge = TrimEdge.START,
                frame = snapFrame(base, session.clipId, frame, playhead),
            )
            DragMode.TRIM_END -> EditCommand.Trim(
                clipId = session.clipId,
                edge = TrimEdge.END,
                frame = snapFrame(base, session.clipId, frame, playhead),
                sourceLength = assetLengthFrames(clip.assetId),
            )
            DragMode.PLAYHEAD -> return
        }
        // A rejected position (overlap, out of range) keeps the last valid preview on screen.
        val result = command.apply(base)
        if (result is EditResult.Success) {
            pendingDragCommand = if (result.value == base) null else command
            reduce { copy(dragPreview = result.value) }
        }
    }

    private fun dragEnd(commit: Boolean) {
        val command = pendingDragCommand
        drag = null
        pendingDragCommand = null
        if (commit && command != null && execute(command)) return
        reduce { copy(dragPreview = null) }
    }

    private fun snapFrame(timeline: Timeline, movingClipId: String, frame: Long, playhead: FrameIndex): FrameIndex {
        val targets = buildList {
            add(0L)
            add(playhead.value)
            for (track in timeline.tracks) {
                for (other in track.clips) {
                    if (other.id == movingClipId) continue
                    add(other.timelineStart.value)
                    add(other.timelineEnd.value)
                }
            }
        }
        val nearest = targets.minByOrNull { abs(it - frame) }
        return FrameIndex(if (nearest != null && abs(nearest - frame) <= SNAP_THRESHOLD_FRAMES) nearest else frame.coerceAtLeast(0))
    }

    // endregion

    // region media

    private fun importMedia(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            reduce { copy(isImporting = true) }
            var cursor = state.value.playhead
            for (uri in uris) {
                try {
                    val asset = assetFor(uri)
                    cursor = place(asset, cursor) ?: cursor
                } catch (e: MediaImportException) {
                    emit(EditorEffect.ShowMessage(e.message ?: "Could not import the file"))
                }
            }
            reduce { copy(isImporting = false) }
        }
    }

    private fun addAssetById(assetId: String) {
        val asset = state.value.assets.firstOrNull { it.id == assetId }
        if (asset == null) {
            emit(EditorEffect.ShowMessage("That media is no longer in the project"))
            return
        }
        place(asset, state.value.playhead)
    }

    /** Returns the library entry for [uri], importing and registering it first if it is new. */
    private suspend fun assetFor(uri: String): MediaAssetDto {
        state.value.assets.firstOrNull { it.uri == uri }?.let { return it }
        val probed = importer.import(uri)
        val project = state.value.fps
        // Audio-only files have no native frame rate; use the project's.
        val (fpsNum, fpsDen) = if (probed.hasVideo) probed.fpsNum to probed.fpsDen else project.num to project.den
        val durationFrames = FrameRate(fpsNum, fpsDen).microsToFrames(probed.durationMicros)
        if (durationFrames <= 0) throw MediaImportException("The file is too short to place on the timeline")
        val asset = MediaAssetDto(
            id = "asset-${idGenerator()}",
            uri = uri,
            durationFrames = durationFrames,
            nativeFpsNum = fpsNum,
            nativeFpsDen = fpsDen,
            colorSpace = probed.colorSpace,
            hasVideo = probed.hasVideo,
            hasAudio = probed.hasAudio,
        )
        reduce { copy(assets = assets + asset) }
        scheduleSave()
        return asset
    }

    /** Overwrites [asset] onto its track at [start]; returns the new clip's end, or null on failure. */
    private fun place(asset: MediaAssetDto, start: FrameIndex): FrameIndex? {
        val type = if (asset.hasVideo) TrackType.VIDEO else TrackType.AUDIO
        // The selected track if it fits the media, else the first track of the right type.
        val selected = history.timeline.tracks.firstOrNull { it.id == state.value.selectedTrackId }
        val track = selected?.takeIf { it.type == type } ?: history.timeline.tracks.firstOrNull { it.type == type }
        if (track == null) {
            emit(EditorEffect.ShowMessage("There is no ${type.name.lowercase()} track to place the clip on"))
            return null
        }
        val length = assetLengthFrames(asset.id) ?: return null
        val clip = Clip("clip-${idGenerator()}", asset.id, start, FrameIndex.ZERO, FrameIndex(length))
        if (!execute(EditCommand.Overwrite(track.id, clip))) return null
        reduce { copy(selectedClipId = clip.id, selectedTrackId = track.id) }
        return clip.timelineEnd
    }

    /** Length of an asset in project frames, or null if it is not in the library. */
    private fun assetLengthFrames(assetId: String?): Long? {
        val asset = state.value.assets.firstOrNull { it.id == assetId } ?: return null
        val micros = FrameRate(asset.nativeFpsNum, asset.nativeFpsDen).framesToMicros(asset.durationFrames)
        return state.value.fps.microsToFrames(micros).takeIf { it > 0 }
    }

    // endregion

    private fun describe(error: EditError): String = when (error) {
        is EditError.Overlap -> "That would overlap another clip"
        EditError.SplitOutsideClip -> "Move the playhead inside the selected clip to split it"
        EditError.NegativeStart -> "A clip cannot start before the beginning of the timeline"
        EditError.SourceOutOfRange -> "That is beyond the end of the source media"
        is EditError.InvalidTrim -> "That trim is not possible: ${error.reason}"
        is EditError.InvalidAppearance -> "That value is not allowed: ${error.reason}"
        is EditError.TrackNotFound, is EditError.ClipNotFound -> "The clip or track no longer exists"
        is EditError.TrackNotEmpty -> "Move or delete the clips on that track before removing it"
        is EditError.DuplicateClipId, is EditError.DuplicateTrackId, is EditError.InvalidClip, is EditError.TrackTypeMismatch -> "That edit is not valid"
    }

    private companion object {
        const val ID_LENGTH = 8
        const val DEFAULT_SAVE_DEBOUNCE_MILLIS = 500L
        const val SNAP_THRESHOLD_FRAMES = 8L
        const val NO_ASSET_KEY = -1L
        const val PLAY_TICK_MILLIS = 16L
        const val NANOS_PER_MICRO = 1_000L
    }
}

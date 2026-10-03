package com.ultimatevideo.uveditor.ui.editor

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectStore
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.domain.AddCaptions
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipFx
import com.ultimatevideo.uveditor.domain.ClipGain
import com.ultimatevideo.uveditor.domain.ClipMask
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.EditCommand
import com.ultimatevideo.uveditor.domain.Effect
import com.ultimatevideo.uveditor.domain.EditError
import com.ultimatevideo.uveditor.domain.EditHistory
import com.ultimatevideo.uveditor.domain.EditResult
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframe
import com.ultimatevideo.uveditor.domain.Keyframes
import com.ultimatevideo.uveditor.domain.Snap
import com.ultimatevideo.uveditor.domain.ProjectColorSpace
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.TrimEdge
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.SnapshotClip
import com.ultimatevideo.uveditor.engine.timeline.SnapshotKeyframe
import com.ultimatevideo.uveditor.engine.timeline.SnapshotTrackType
import com.ultimatevideo.uveditor.engine.timeline.SnapshotTransition
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

    /** An inspector or preview-gesture edit in progress: shown live, committed as one undo step on release. */
    private class AppearanceSession(
        val clipId: String,
        val baseTransform: ClipTransform,
        val baseGain: Double,
        var transform: ClipTransform,
        var gainDb: Double,
        /** The clip frame a pose edit is keyed at when the clip is animated; null for a fixed transform. */
        val keyFrame: Long? = null,
    )

    /** An effect or mask slider drag in progress: shown live, committed as one undo step. */
    private class FxSession(val clipId: String, val base: ClipFx, var fx: ClipFx)

    /** A title text/style edit in progress: shown live, committed as one undo step. */
    private class TitleSession(val clipId: String, val base: TitleContent, var content: TitleContent)

    private val clipKeys = KeyRegistry()
    private val assetKeys = KeyRegistry()

    private var history = EditHistory(Timeline())
    private var baseProject: ProjectDto? = null
    private var drag: DragSession? = null
    private var pendingDragCommand: EditCommand? = null
    private var appearance: AppearanceSession? = null
    private var titleEdit: TitleSession? = null
    private var fxEdit: FxSession? = null
    private var saveJob: Job? = null
    private var playJob: Job? = null

    /** Set by the screen once the audio engine is up. Until then the transport uses the system clock. */
    var playbackOutput: PlaybackOutput? = null
    private var dirty = false

    init {
        load()
    }

    override fun onIntent(intent: EditorIntent) {
        // Typing in the title field is only provisional: any other action first makes it final.
        if (intent !is EditorIntent.UpdateTitle && intent !is EditorIntent.EndTitleEdit) endTitleEdit(commit = true)
        // Same for an effect slider: it stays provisional until released or until something else happens.
        if (intent !is EditorIntent.UpdateEffect && intent !is EditorIntent.UpdateMask && intent !is EditorIntent.EndFxEdit) {
            endFxEdit(commit = true)
        }
        when (intent) {
            is EditorIntent.TapTimeline -> tap(intent.hit)
            is EditorIntent.SetPlayhead -> seekTo(intent.frame)
            is EditorIntent.DragStart -> dragStart(intent.hit)
            is EditorIntent.DragMove -> dragMove(intent.frame, intent.trackIndex)
            is EditorIntent.DragEnd -> dragEnd(intent.commit)
            EditorIntent.SplitAtPlayhead -> splitAtPlayhead()
            EditorIntent.RippleDeleteSelected -> withSelection { execute(EditCommand.DeleteClip(it)) }
            EditorIntent.RippleAppendSelected -> withSelection { execute(EditCommand.RippleAppend(it)) }
            EditorIntent.TogglePlay -> togglePlay()
            is EditorIntent.AddTrack -> addTrack(intent.type)
            EditorIntent.RemoveSelectedTrack -> removeSelectedTrack()
            EditorIntent.SeekPrevious -> seekTo(previousEditPoint())
            EditorIntent.SeekNext -> seekTo(nextEditPoint())
            EditorIntent.Undo -> undo()
            EditorIntent.Redo -> redo()
            EditorIntent.ToggleInspector -> toggleInspector()
            EditorIntent.AddTitle -> addTitle()
            is EditorIntent.AddCaptionClips -> addCaptionClips(intent.clips)
            is EditorIntent.UpdateTitle -> updateTitle(intent.content)
            is EditorIntent.EndTitleEdit -> endTitleEdit(intent.commit)
            EditorIntent.AddTransition -> addTransition()
            is EditorIntent.SetTransitionDuration -> setTransitionDuration(intent.frames)
            EditorIntent.RemoveTransition -> removeTransition()
            EditorIntent.BeginAppearanceEdit -> beginAppearance()
            is EditorIntent.UpdateTransform -> updateAppearance(transform = intent.transform)
            is EditorIntent.UpdateGain -> updateAppearance(gainDb = intent.gainDb)
            is EditorIntent.TransformGesture -> transformGesture(intent)
            is EditorIntent.EndAppearanceEdit -> endAppearance(intent.commit)
            EditorIntent.ResetAppearance -> resetAppearance()
            is EditorIntent.AddEffect -> withSelection { execute(EditCommand.AddEffect(it, Effect(idGenerator(), intent.type))) }
            is EditorIntent.RemoveEffect -> withSelection { execute(EditCommand.RemoveEffect(it, intent.effectId)) }
            is EditorIntent.MoveEffect -> withSelection { execute(EditCommand.MoveEffect(it, intent.effectId, intent.toIndex)) }
            is EditorIntent.UpdateEffect -> updateEffect(intent.effectId, intent.values)
            is EditorIntent.SetBlendMode -> withSelection { execute(EditCommand.SetBlendMode(it, intent.mode)) }
            is EditorIntent.UpdateMask -> updateMask(intent.mask)
            is EditorIntent.EndFxEdit -> endFxEdit(intent.commit)
            EditorIntent.ClearFx -> withSelection { execute(EditCommand.ClearFx(it)) }
            EditorIntent.ToggleKeyframe -> toggleKeyframe()
            is EditorIntent.JumpToKeyframe -> jumpToKeyframe(intent.forward)
            is EditorIntent.SetKeyframeInterpolation -> setKeyframeInterpolation(intent.interpolation)
            EditorIntent.ClearKeyframes -> clearKeyframes()
            is EditorIntent.SetSafeZone -> reduce { copy(safeZone = intent.platform) }
            EditorIntent.ShowCanvasDialog -> reduce { copy(canvasDialogOpen = true) }
            EditorIntent.DismissCanvasDialog -> reduce { copy(canvasDialogOpen = false) }
            is EditorIntent.ChangeCanvas -> changeCanvas(intent.width, intent.height)
            is EditorIntent.ChangeColorSpace -> changeColorSpace(intent.space)
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

    /** The longest transition that fits across [transition]'s cut now; the upper end of the duration control. */
    fun transitionLimit(transition: Transition): Long {
        val from = history.timeline.trackOfClip(transition.fromClipId)?.clip(transition.fromClipId) ?: return 0
        return TimelineOps.maxTransitionFrames(history.timeline, transition.fromClipId, transition.toClipId, assetLengthFrames(from.assetId))
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
                    hasFx = !clip.fx.isNeutral,
                )
            }
        }
        val transitions = timeline.transitions.mapNotNull { transition ->
            val trackIndex = timeline.tracks.indexOfFirst { it.clip(transition.toClipId) != null }
            val cut = timeline.cutOf(transition)
            if (trackIndex < 0 || cut == null) {
                null
            } else {
                SnapshotTransition(trackIndex, cut.value, transition.preFrames, transition.postFrames)
            }
        }
        val keyframes = timeline.tracks.flatMap { track ->
            track.clips.flatMap { clip -> clip.keyframes.map { SnapshotKeyframe(clipKeys.keyFor(clip.id), it.frame) } }
        }
        return TimelineSnapshot(state.fps.num, state.fps.den, tracks, clips, transitions, keyframes)
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
                        canvasWidth = project.settings.width,
                        canvasHeight = project.settings.height,
                        colorSpace = ProjectColorSpace.fromId(project.settings.colorSpace),
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
        return timeline.copy(tracks = tracks)
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
        appearance = null  // the timeline changed under any edit in progress
        titleEdit = null
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
        if (track.type != TrackType.TITLE && history.timeline.tracks.count { it.type == track.type } <= 1) {
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

    // region appearance (transform and gain)

    private fun toggleInspector() {
        if (state.value.inspectorOpen) endAppearance(commit = true)
        reduce { copy(inspectorOpen = !inspectorOpen) }
    }

    /** Starts an edit session on the selected clip. Returns false (with a message) if there is nothing to edit. */
    private fun beginAppearance(): Boolean {
        if (appearance != null) return true
        if (drag != null) return false
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        if (clip == null) {
            emit(EditorEffect.ShowMessage("Select a clip first"))
            return false
        }
        // An animated clip is edited at the playhead: the edit becomes a keyframe there.
        val keyFrame = if (clip.keyframes.isEmpty()) null else state.value.selectedClipFrame
        if (clip.keyframes.isNotEmpty() && keyFrame == null) {
            emit(EditorEffect.ShowMessage("Move the playhead inside the clip to edit its animation"))
            return false
        }
        val pose = if (keyFrame != null) clip.transformAt(keyFrame) else clip.transform
        appearance = AppearanceSession(clip.id, pose, clip.gainDb, pose, clip.gainDb, keyFrame)
        return true
    }

    /**
     * The edit an appearance session stands for. A fixed clip gets its transform and gain replaced;
     * an animated one gets a keyframe at the session's frame (only when the pose changed, so a gain
     * edit never adds one) and the new gain.
     */
    private fun sessionCommand(session: AppearanceSession): EditCommand {
        val frame = session.keyFrame ?: return EditCommand.SetAppearance(session.clipId, session.transform, session.gainDb)
        val clip = history.timeline.trackOfClip(session.clipId)?.clip(session.clipId)
            ?: return EditCommand.SetAppearance(session.clipId, session.transform, session.gainDb)
        val parts = ArrayList<EditCommand>()
        if (session.transform != session.baseTransform) {
            val interpolation = Keyframes.at(clip.keyframes, frame)?.interpolation ?: Interpolation.LINEAR
            parts += EditCommand.SetKeyframe(clip.id, Keyframe(frame, session.transform, interpolation))
        }
        if (session.gainDb != session.baseGain) parts += EditCommand.SetGain(clip.id, session.gainDb)
        return EditCommand.Batch(parts)
    }

    private fun updateAppearance(transform: ClipTransform? = null, gainDb: Double? = null) {
        if (!beginAppearance()) return
        val session = appearance ?: return
        val newTransform = transform ?: session.transform
        val newGain = gainDb ?: session.gainDb
        val problem = newTransform.problem() ?: ClipGain.problem(newGain)
        if (problem != null) {
            emit(EditorEffect.ShowMessage("That value is not allowed: $problem"))
            return
        }
        session.transform = newTransform
        session.gainDb = newGain
        showAppearance(session)
    }

    /** A step of a pan/pinch/rotate gesture on the preview; only meaningful while the clip is visible. */
    private fun transformGesture(step: EditorIntent.TransformGesture) {
        if (appearance == null) {
            // Gestures edit what is on screen: the selected video clip under the playhead.
            if (!state.value.selectedClipVisible) return
            if (!beginAppearance()) return
        }
        val session = appearance ?: return
        session.transform = PreviewGeometry.applyGesture(session.transform, step.panX, step.panY, step.zoom, step.rotationDegrees)
        showAppearance(session)
    }

    /** Shows the session's values on the preview without touching the undo history. */
    private fun showAppearance(session: AppearanceSession) {
        val result = sessionCommand(session).apply(history.timeline)
        if (result is EditResult.Success) reduce { copy(dragPreview = result.value) }
    }

    private fun endAppearance(commit: Boolean) {
        val session = appearance ?: return
        appearance = null
        val changed = session.transform != session.baseTransform || session.gainDb != session.baseGain
        if (commit && changed && execute(sessionCommand(session))) return
        reduce { copy(dragPreview = null) }
    }

    private fun resetAppearance() = withSelection { clipId ->
        endAppearance(commit = false)
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.transform.isIdentity && clip.gainDb == 0.0 && clip.keyframes.isEmpty()) return@withSelection
        // One undo step: the animation goes and the clip returns to its original placement.
        execute(EditCommand.Batch(listOf(EditCommand.ClearKeyframes(clipId), EditCommand.SetAppearance(clipId, ClipTransform.IDENTITY, 0.0))))
    }

    // endregion

    // region effects

    private fun beginFx(): FxSession? {
        fxEdit?.let { return it }
        if (drag != null) return null
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        if (clip == null || history.timeline.trackOfClip(clip.id)?.type == TrackType.AUDIO) {
            emit(EditorEffect.ShowMessage("Select a video clip or title first"))
            return null
        }
        return FxSession(clip.id, clip.fx, clip.fx).also { fxEdit = it }
    }

    private fun updateEffect(effectId: String, values: List<Double>) {
        val session = beginFx() ?: return
        val effect = session.fx.effect(effectId) ?: return
        val changed = effect.copy(values = values)
        changed.problem()?.let {
            emit(EditorEffect.ShowMessage("That value is not allowed: $it"))
            return
        }
        session.fx = session.fx.copy(effects = session.fx.effects.map { if (it.id == effectId) changed else it })
        showFx(session)
    }

    private fun updateMask(mask: ClipMask?) {
        val session = beginFx() ?: return
        mask?.problem()?.let {
            emit(EditorEffect.ShowMessage("That value is not allowed: $it"))
            return
        }
        session.fx = session.fx.copy(mask = mask)
        showFx(session)
    }

    private fun showFx(session: FxSession) {
        val result = EditCommand.SetFx(session.clipId, session.fx).apply(history.timeline)
        if (result is EditResult.Success) reduce { copy(dragPreview = result.value) }
    }

    private fun endFxEdit(commit: Boolean) {
        val session = fxEdit ?: return
        fxEdit = null
        if (commit && session.fx != session.base && execute(EditCommand.SetFx(session.clipId, session.fx))) return
        reduce { copy(dragPreview = null) }
    }

    // endregion

    // region keyframes

    /** Adds a keyframe at the playhead holding the pose shown there, or removes the one that is there. */
    private fun toggleKeyframe() = withSelection { clipId ->
        endAppearance(commit = true)
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (history.timeline.trackOfClip(clipId)?.type == TrackType.AUDIO) {
            emit(EditorEffect.ShowMessage("Only video clips and titles can be animated"))
            return@withSelection
        }
        val frame = state.value.selectedClipFrame
        if (frame == null) {
            emit(EditorEffect.ShowMessage("Move the playhead inside the clip to set a keyframe"))
            return@withSelection
        }
        if (Keyframes.at(clip.keyframes, frame) != null) {
            execute(EditCommand.RemoveKeyframe(clipId, frame))
        } else {
            execute(EditCommand.SetKeyframe(clipId, Keyframe(frame, clip.transformAt(frame))))
        }
    }

    private fun jumpToKeyframe(forward: Boolean) = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        val relative = state.value.playhead - clip.timelineStart
        val target = if (forward) Keyframes.nextFrame(clip.keyframes, relative) else Keyframes.previousFrame(clip.keyframes, relative)
        if (target == null) {
            emit(EditorEffect.ShowMessage(if (forward) "No later keyframe on this clip" else "No earlier keyframe on this clip"))
            return@withSelection
        }
        seekTo(clip.timelineStart.value + target)
    }

    private fun setKeyframeInterpolation(interpolation: Interpolation) = withSelection { clipId ->
        endAppearance(commit = true)
        val frame = state.value.keyframeAtPlayhead?.frame
        if (frame == null) {
            emit(EditorEffect.ShowMessage("Put the playhead on a keyframe to change how it moves on"))
            return@withSelection
        }
        execute(EditCommand.SetKeyframeInterpolation(clipId, frame, interpolation))
    }

    private fun clearKeyframes() = withSelection { clipId ->
        endAppearance(commit = false)
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.keyframes.isEmpty()) return@withSelection
        // The clip keeps the pose it has at the playhead, so the picture does not jump.
        val frame = state.value.selectedClipFrame
        val pose = if (frame != null) clip.transformAt(frame) else clip.keyframes.first().transform
        execute(EditCommand.Batch(listOf(EditCommand.ClearKeyframes(clipId), EditCommand.SetTransform(clipId, pose))))
    }

    // endregion

    // region canvas

    /**
     * Switches the project to another canvas size (a different format such as 9:16, or a different
     * resolution). The positions of clips and keyframes are rescaled; the undo history starts over.
     */
    private fun changeCanvas(width: Int, height: Int) {
        val current = state.value
        reduce { copy(canvasDialogOpen = false) }
        if (width <= 0 || height <= 0 || (width == current.canvasWidth && height == current.canvasHeight)) return
        endAppearance(commit = false)
        endTitleEdit(commit = false)
        drag = null
        pendingDragCommand = null
        val remapped = when (val result = TimelineOps.remapCanvas(history.timeline, current.canvasWidth, current.canvasHeight, width, height)) {
            is EditResult.Success -> result.value
            is EditResult.Failure -> {
                emit(EditorEffect.ShowMessage(describe(result.error)))
                return
            }
        }
        history = EditHistory(remapped)
        baseProject = baseProject?.let { it.copy(settings = it.settings.copy(width = width, height = height)) }
        reduce { copy(canvasWidth = width, canvasHeight = height) }
        syncFromHistory()
        scheduleSave()
    }

    private fun changeColorSpace(space: ProjectColorSpace) {
        if (space == state.value.colorSpace) return
        baseProject = baseProject?.let { it.copy(settings = it.settings.copy(colorSpace = space.id)) }
        reduce { copy(colorSpace = space, canvasDialogOpen = false) }
        scheduleSave()
    }

    // endregion

    // region titles and transitions

    private fun addTitle() {
        val timeline = history.timeline
        val trackId = timeline.tracks.firstOrNull { it.type == TrackType.TITLE }?.id ?: run {
            val track = Track(uniqueTrackId(timeline.tracks, "track-t"), TrackType.TITLE)
            // Titles go above everything, like the top layer of a mixer.
            if (!execute(EditCommand.AddTrack(track, 0))) return
            track.id
        }
        val fps = state.value.fps
        val frames = (fps.microsToFrames(TITLE_DEFAULT_MICROS)).coerceAtLeast(1)
        val clip = Clip(
            id = "title-${idGenerator()}",
            assetId = null,
            timelineStart = state.value.playhead,
            sourceIn = FrameIndex.ZERO,
            sourceOut = FrameIndex(frames),
            title = TitleContent(DEFAULT_TITLE_TEXT),
        )
        if (!execute(EditCommand.Overwrite(trackId, clip))) return
        reduce { copy(selectedClipId = clip.id, selectedTrackId = trackId, inspectorOpen = true) }
    }

    private fun addCaptionClips(clips: List<Clip>) {
        if (clips.isEmpty()) return
        val track = Track(uniqueTrackId(history.timeline.tracks, "track-t"), TrackType.TITLE)
        // Captions go above everything, like any title, on their own track so they never cut an existing title.
        if (!execute(AddCaptions(track, 0, clips))) return
        reduce { copy(selectedTrackId = track.id, selectedClipId = clips.first().id) }
    }

    private fun updateTitle(content: TitleContent) {
        val clipId = state.value.selectedClipId ?: return
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return
        val base = clip.title ?: return
        // An invalid value (an empty text while typing) is not shown; the last valid one stays.
        if (content.problem() != null) return
        val session = titleEdit?.takeIf { it.clipId == clipId } ?: TitleSession(clipId, base, base).also { titleEdit = it }
        session.content = content
        val result = EditCommand.SetTitle(clipId, content).apply(history.timeline)
        if (result is EditResult.Success) reduce { copy(dragPreview = result.value) }
    }

    private fun endTitleEdit(commit: Boolean) {
        val session = titleEdit ?: return
        titleEdit = null
        if (commit && session.content != session.base && execute(EditCommand.SetTitle(session.clipId, session.content))) return
        reduce { copy(dragPreview = null) }
    }

    private fun addTransition() = withSelection { clipId ->
        val timeline = history.timeline
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        val next = timeline.trackOfClip(clipId)?.clips?.firstOrNull { it.timelineStart == clip.timelineEnd }
        if (next == null) {
            emit(EditorEffect.ShowMessage("Place another clip right after this one to add a transition"))
            return@withSelection
        }
        if (timeline.transitionBetween(clip.id, next.id) != null) {
            emit(EditorEffect.ShowMessage("These clips already have a transition"))
            return@withSelection
        }
        val sourceLength = assetLengthFrames(clip.assetId)
        val room = TimelineOps.maxTransitionFrames(timeline, clip.id, next.id, sourceLength)
        if (room < Transition.MIN_DURATION_FRAMES) {
            emit(EditorEffect.ShowMessage("There is not enough extra footage around the cut for a transition"))
            return@withSelection
        }
        val wanted = state.value.fps.microsToFrames(TRANSITION_DEFAULT_MICROS).coerceAtLeast(Transition.MIN_DURATION_FRAMES)
        execute(EditCommand.AddTransition(Transition("transition-${idGenerator()}", clip.id, next.id, minOf(wanted, room)), sourceLength))
    }

    private fun setTransitionDuration(frames: Long) = withSelection { clipId ->
        val transition = history.timeline.transitions.firstOrNull { it.fromClipId == clipId }
        if (transition == null) {
            emit(EditorEffect.ShowMessage("This clip has no transition to its next clip"))
            return@withSelection
        }
        if (frames == transition.durationFrames) return@withSelection
        val from = history.timeline.trackOfClip(clipId)?.clip(clipId)
        execute(EditCommand.SetTransitionDuration(transition.id, frames, assetLengthFrames(from?.assetId)))
    }

    private fun removeTransition() = withSelection { clipId ->
        val transition = history.timeline.transitions.firstOrNull { it.fromClipId == clipId } ?: return@withSelection
        execute(EditCommand.RemoveTransition(transition.id))
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
        is EditError.InvalidTransition -> "That transition is not possible: ${error.reason}"
        is EditError.TransitionNotFound -> "The transition no longer exists"
        is EditError.NotATitle -> "That clip is not a title"
        is EditError.InvalidKeyframe -> "That keyframe is not possible: ${error.reason}"
        is EditError.KeyframeNotFound -> "There is no keyframe there"
        is EditError.InvalidEffect -> "That effect is not possible: ${error.reason}"
        is EditError.EffectNotFound -> "That effect no longer exists"
        is EditError.DuplicateClipId, is EditError.DuplicateTrackId, is EditError.DuplicateTransitionId,
        is EditError.InvalidClip, is EditError.TrackTypeMismatch -> "That edit is not valid"
    }

    private companion object {
        const val ID_LENGTH = 8
        const val DEFAULT_SAVE_DEBOUNCE_MILLIS = 500L
        const val SNAP_THRESHOLD_FRAMES = 8L
        const val NO_ASSET_KEY = -1L
        const val DEFAULT_TITLE_TEXT = "Title"
        const val TITLE_DEFAULT_MICROS = 3_000_000L
        const val TRANSITION_DEFAULT_MICROS = 1_000_000L
        const val PLAY_TICK_MILLIS = 16L
        const val NANOS_PER_MICRO = 1_000L
    }
}

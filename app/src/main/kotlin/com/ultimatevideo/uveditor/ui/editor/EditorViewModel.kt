package com.ultimatevideo.uveditor.ui.editor

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaCaches
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.MediaProblem
import com.ultimatevideo.uveditor.data.MissingMedia
import com.ultimatevideo.uveditor.data.ProbedMedia
import com.ultimatevideo.uveditor.data.RelinkCheck
import com.ultimatevideo.uveditor.data.RelinkVerdict
import com.ultimatevideo.uveditor.data.ProjectError
import com.ultimatevideo.uveditor.data.ProjectStore
import com.ultimatevideo.uveditor.data.TimelineMapper
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.data.model.ProjectDto
import com.ultimatevideo.uveditor.domain.AddCaptions
import com.ultimatevideo.uveditor.domain.AddCaptionsToTrack
import com.ultimatevideo.uveditor.domain.captions.CAPTION_ID_PREFIX
import com.ultimatevideo.uveditor.domain.AddMarker
import com.ultimatevideo.uveditor.domain.AddTextTemplate
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.CutToBeat
import com.ultimatevideo.uveditor.domain.Marker
import com.ultimatevideo.uveditor.domain.MarkerKind
import com.ultimatevideo.uveditor.domain.MarkerOps
import com.ultimatevideo.uveditor.domain.RemoveMarker
import com.ultimatevideo.uveditor.domain.SetBeatMarkers
import com.ultimatevideo.uveditor.domain.LayerPlacement
import com.ultimatevideo.uveditor.domain.MotionPreset
import com.ultimatevideo.uveditor.domain.SetTitleMotion
import com.ultimatevideo.uveditor.domain.TextTemplate
import com.ultimatevideo.uveditor.domain.TextTemplates
import com.ultimatevideo.uveditor.domain.beat.BeatMapping
import com.ultimatevideo.uveditor.engine.timeline.BeatResult
import com.ultimatevideo.uveditor.engine.timeline.BeatSource
import com.ultimatevideo.uveditor.engine.timeline.NoBeatSource
import com.ultimatevideo.uveditor.engine.timeline.SnapshotMarker
import com.ultimatevideo.uveditor.domain.DropHint
import com.ultimatevideo.uveditor.domain.DropKind
import com.ultimatevideo.uveditor.domain.DropPlan
import com.ultimatevideo.uveditor.domain.DropTarget
import com.ultimatevideo.uveditor.domain.ClipDeletion
import com.ultimatevideo.uveditor.domain.ClipFx
import com.ultimatevideo.uveditor.domain.ClipGain
import com.ultimatevideo.uveditor.domain.ClipMask
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.EditCommand
import com.ultimatevideo.uveditor.domain.Effect
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.EditError
import com.ultimatevideo.uveditor.domain.EditHistory
import com.ultimatevideo.uveditor.domain.EditResult
import com.ultimatevideo.uveditor.domain.GradeCurves
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframe
import com.ultimatevideo.uveditor.domain.Keyframes
import com.ultimatevideo.uveditor.domain.Snap
import com.ultimatevideo.uveditor.domain.SpeedRamps
import com.ultimatevideo.uveditor.domain.ProjectColorSpace
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleLayerEdit
import com.ultimatevideo.uveditor.domain.TitleMotion
import com.ultimatevideo.uveditor.domain.Track
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.engine.still.StickerIds
import com.ultimatevideo.uveditor.domain.StillKind
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.TrimEdge
import com.ultimatevideo.uveditor.domain.isFreeze
import com.ultimatevideo.uveditor.domain.isRetimed
import com.ultimatevideo.uveditor.domain.sourceSpan
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.SnapshotClip
import com.ultimatevideo.uveditor.engine.timeline.SnapshotKeyframe
import com.ultimatevideo.uveditor.engine.timeline.SnapshotRetime
import com.ultimatevideo.uveditor.engine.timeline.SnapshotTrackType
import com.ultimatevideo.uveditor.engine.timeline.SnapshotTransition
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.engine.timeline.TimelineSnapshot
import com.ultimatevideo.uveditor.mvi.MviViewModel
import com.ultimatevideo.uveditor.ui.editor.tray.AssetKind
import com.ultimatevideo.uveditor.ui.editor.tray.moveAsset
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
    /** Derived data kept per library file (waveforms, thumbnails), dropped when a file is relinked. */
    private val mediaCaches: MediaCaches = MediaCaches.None,
    private val beatSource: BeatSource = NoBeatSource,
) : MviViewModel<EditorState, EditorIntent, EditorEffect>(EditorState()) {

    private enum class DragMode { MOVE, TRIM_START, TRIM_END, PLAYHEAD }

    private class DragSession(val clipId: String, val mode: DragMode, val grabOffset: Long) {
        /** Last lane the finger was over, kept while it crosses a gap between lanes. */
        var target: DropTarget? = null
    }

    /**
     * A drag of media that is not on the timeline yet: [clip] is the clip a release would create ([type] is
     * the kind of lane it needs). [assetId] is null while files from another app are only hovering.
     */
    private class TrayDragSession(val assetId: String?, val clip: Clip, val type: TrackType) {
        var target: DropTarget? = null
        var command: EditCommand? = null
    }

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
    private var trayDrag: TrayDragSession? = null
    private var pendingDragCommand: EditCommand? = null
    private var appearance: AppearanceSession? = null
    private var titleEdit: TitleSession? = null
    private var fxEdit: FxSession? = null
    private var saveJob: Job? = null
    private var saveRetryJob: Job? = null
    private var saveRetries = 0
    private var playJob: Job? = null

    /** Set by the screen once the audio engine is up. Until then the transport uses the system clock. */
    var playbackOutput: PlaybackOutput? = null
    private var dirty = false

    init {
        load()
    }

    override fun onIntent(intent: EditorIntent) {
        // Typing in the title field is only provisional: any other action first makes it final.
        if (intent !is EditorIntent.UpdateTitle && intent !is EditorIntent.EndTitleEdit && intent !is EditorIntent.LayerGesture) endTitleEdit(commit = true)
        // Same for an effect slider: it stays provisional until released or until something else happens.
        if (intent !is EditorIntent.UpdateEffect && intent !is EditorIntent.UpdateGrade && intent !is EditorIntent.UpdateMask &&
            intent !is EditorIntent.EndFxEdit
        ) {
            endFxEdit(commit = true)
        }
        when (intent) {
            is EditorIntent.TapTimeline -> tap(intent.hit)
            is EditorIntent.SetPlayhead -> seekTo(intent.frame)
            is EditorIntent.DragStart -> dragStart(intent.hit)
            is EditorIntent.DragMove -> dragMove(intent.frame, intent.trackIndex, intent.zone)
            is EditorIntent.DragEnd -> dragEnd(intent.commit)
            EditorIntent.SplitAtPlayhead -> splitAtPlayhead()
            EditorIntent.RippleDeleteSelected -> withSelection { execute(EditCommand.DeleteClip(it)) }
            EditorIntent.RippleAppendSelected -> withSelection { execute(EditCommand.RippleAppend(it)) }
            EditorIntent.TogglePlay -> togglePlay()
            is EditorIntent.AddTrack -> addTrack(intent.type)
            EditorIntent.RemoveSelectedTrack -> removeSelectedTrack()
            is EditorIntent.MoveSelectedTrack -> moveSelectedTrack(intent.delta)
            EditorIntent.SeekPrevious -> seekTo(previousEditPoint())
            EditorIntent.SeekNext -> seekTo(nextEditPoint())
            EditorIntent.Undo -> undo()
            EditorIntent.Redo -> redo()
            EditorIntent.ToggleInspector -> toggleInspector()
            EditorIntent.AddTitle -> addTitle()
            EditorIntent.ToggleMarkerAtPlayhead -> toggleMarkerAtPlayhead()
            EditorIntent.ClearBeatMarkers -> clearBeatMarkers()
            EditorIntent.ToggleMarkerSnap -> reduce { copy(snapToMarkers = !snapToMarkers) }
            EditorIntent.AnalyzeBeats -> analyzeBeats()
            EditorIntent.CutToBeatFromSelected -> cutToBeatFromSelected()
            is EditorIntent.ApplyTextTemplate -> applyTextTemplate(intent.templateId, intent.text)
            is EditorIntent.ApplyPreset -> applyTemplate(intent.template, intent.text)
            is EditorIntent.AddSticker -> addSticker(intent.stickerId)
            is EditorIntent.AddCaptionClips -> addCaptionClips(intent.clips, intent.intoExistingTrack)
            is EditorIntent.RestyleCaptions -> execute(com.ultimatevideo.uveditor.domain.RestyleCaptions(intent.style, intent.canvasHeight))
            is EditorIntent.UpdateTitle -> updateTitle(intent.content)
            is EditorIntent.SelectTitleLayer -> selectTitleLayer(intent.index)
            is EditorIntent.LayerGesture -> layerGesture(intent)
            is EditorIntent.FontsChanged -> reduce { copy(availableFonts = intent.available) }
            is EditorIntent.ApplyTitleMotion -> applyTitleMotion(intent.intro, intent.outro)
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
            EditorIntent.OpenLutPicker -> withSelection { reduce { copy(lutPickerOpen = true) } }
            EditorIntent.CloseLutPicker -> reduce { copy(lutPickerOpen = false) }
            is EditorIntent.AddLut -> {
                reduce { copy(lutPickerOpen = false) }
                withSelection { execute(EditCommand.AddEffect(it, Effect(idGenerator(), EffectType.LUT, listOf(intent.key.toDouble(), 1.0)))) }
            }
            is EditorIntent.RemoveEffect -> withSelection { execute(EditCommand.RemoveEffect(it, intent.effectId)) }
            is EditorIntent.MoveEffect -> withSelection { execute(EditCommand.MoveEffect(it, intent.effectId, intent.toIndex)) }
            is EditorIntent.UpdateEffect -> updateEffect(intent.effectId, intent.values)
            is EditorIntent.UpdateGrade -> updateGrade(intent.effectId, intent.values, intent.curves)
            is EditorIntent.ApplyGrade -> applyGrade(intent.values, intent.curves)
            is EditorIntent.SetBlendMode -> withSelection { execute(EditCommand.SetBlendMode(it, intent.mode)) }
            is EditorIntent.SetClipColor -> withSelection { execute(EditCommand.SetColorOverride(it, intent.space)) }
            is EditorIntent.UpdateMask -> updateMask(intent.mask)
            is EditorIntent.EndFxEdit -> endFxEdit(intent.commit)
            EditorIntent.ClearFx -> withSelection { execute(EditCommand.ClearFx(it)) }
            EditorIntent.ToggleKeyframe -> toggleKeyframe()
            is EditorIntent.JumpToKeyframe -> jumpToKeyframe(intent.forward)
            is EditorIntent.SetKeyframeInterpolation -> setKeyframeInterpolation(intent.interpolation)
            EditorIntent.ClearKeyframes -> clearKeyframes()
            is EditorIntent.SetSpeed -> setSpeed(intent.num, intent.den)
            EditorIntent.ToggleReverse -> toggleReverse()
            is EditorIntent.SetSpeedRamp -> setSpeedRamp(intent.shape)
            EditorIntent.FreezeFrame -> freezeFrame()
            is EditorIntent.SetSafeZone -> reduce { copy(safeZone = intent.platform) }
            EditorIntent.ShowCanvasDialog -> reduce { copy(canvasDialogOpen = true) }
            EditorIntent.DismissCanvasDialog -> reduce { copy(canvasDialogOpen = false) }
            is EditorIntent.ChangeCanvas -> changeCanvas(intent.width, intent.height)
            is EditorIntent.ChangeColorSpace -> changeColorSpace(intent.space)
            is EditorIntent.ImportMedia -> importMedia(intent.uris)
            is EditorIntent.AddAsset -> addAssetById(intent.assetId)
            is EditorIntent.TrayDragStart -> trayDragStart(intent.assetId)
            is EditorIntent.ExternalDragStart -> externalDragStart(intent.kinds)
            is EditorIntent.TrayDragMove -> trayDragMove(intent.frame, intent.trackIndex, intent.zone)
            EditorIntent.TrayDragLeave -> reduce { copy(dragPreview = null, dropHint = null) }
            is EditorIntent.TrayDragEnd -> trayDragEnd(intent.commit)
            is EditorIntent.ExternalDrop -> externalDrop(intent.uris, intent.frame, intent.trackIndex, intent.zone)
            is EditorIntent.ImportToTray -> importToTray(intent.uris)
            is EditorIntent.ReorderAsset -> reorderAsset(intent.assetId, intent.toIndex)
            EditorIntent.ShowRelink -> reduce { copy(relinkOpen = true) }
            EditorIntent.HideRelink -> reduce { copy(relinkOpen = false) }
            is EditorIntent.RequestRelink -> emit(EditorEffect.LaunchRelinkPicker(intent.assetId))
            is EditorIntent.RelinkAsset -> relinkAsset(intent.assetId, intent.uri)
            EditorIntent.RetrySave -> retrySave()
            EditorIntent.LeaveWithoutSaving -> emit(EditorEffect.Close)
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
                    // Titles and stickers have no media to read waveforms or thumbnails from; a photo has a thumbnail.
                    assetKey = clip.assetId?.takeIf { clip.hasMedia || clip.still == StillKind.PHOTO }?.let(assetKeys::keyFor) ?: NO_ASSET_KEY,
                    startFrame = clip.timelineStart.value,
                    durationFrames = clip.durationFrames,
                    sourceInFrame = clip.sourceIn.value,
                    sourceFpsNum = state.fps.num,
                    sourceFpsDen = state.fps.den,
                    selected = clip.id == state.selectedClipId,
                    hasFx = !clip.fx.isNeutral,
                    missing = clip.hasMedia && clip.assetId in state.missingMedia,
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
        val retimes = timeline.tracks.flatMap { track ->
            track.clips.filter { it.isRetimed || it.isFreeze }.map {
                SnapshotRetime(clipKeys.keyFor(it.id), it.sourceSpan, reverse = it.reverse, freeze = it.isFreeze)
            }
        }
        val markers = timeline.markers.map { SnapshotMarker(it.frame.value, beat = it.kind == MarkerKind.BEAT) }
        return TimelineSnapshot(state.fps.num, state.fps.den, tracks, clips, transitions, keyframes, retimes, markers)
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
                verifyAssets(project.mediaLibrary)
            } catch (e: ProjectError) {
                reduce { copy(isLoading = false, loadError = e.message) }
            }
        }
    }

    /**
     * Opens every library file once. Files that cannot be read are recorded in [EditorState.missingMedia]:
     * their clips stay on the timeline, marked, while the preview, the mixer and the thumbnail workers
     * leave them alone, and the user is offered Relink. Probing also repairs two things in place:
     * projects saved before `hasVideo`/`hasAudio` existed read as "has both" (a file without an audio
     * track would reach the mixer and fail), and projects saved before names were kept lack a file name.
     * Reading a file also re-takes its persisted permission where Android allows it.
     */
    private suspend fun verifyAssets(loaded: List<MediaAssetDto>) {
        val missing = HashMap<String, MediaProblem>()
        val probed = HashMap<String, ProbedMedia>()
        for (asset in loaded) {
            try {
                probed[asset.id] = importer.verify(asset.uri)
            } catch (e: MediaImportException) {
                missing[asset.id] = e.problem
            }
        }
        val stale = probed.filter { (id, media) ->
            state.value.assets.firstOrNull { it.id == id }?.let {
                (it.hasVideo to it.hasAudio) != (media.hasVideo to media.hasAudio) || (it.displayName == null && media.displayName != null)
            } == true
        }
        // Apply to the current list: media may have been imported while probing.
        reduce {
            copy(
                missingMedia = missingMedia + missing,
                assets = if (stale.isEmpty()) assets else assets.map { asset ->
                    stale[asset.id]?.let { media ->
                        asset.copy(hasVideo = media.hasVideo, hasAudio = media.hasAudio, displayName = asset.displayName ?: media.displayName)
                    } ?: asset
                },
            )
        }
        if (stale.isNotEmpty()) scheduleSave()
    }

    /**
     * Points [assetId] at a different file. The new file must be able to stand in for the old one
     * (see [RelinkCheck]); differences that do not prevent it are reported as warnings. This is a saved
     * change of the media library, not a step of the undo history: undoing it would put back a file the
     * user just said does not exist.
     */
    private fun relinkAsset(assetId: String, uri: String) {
        val old = state.value.assets.firstOrNull { it.id == assetId }
        if (old == null) {
            emit(EditorEffect.ShowMessage("That media is no longer in the project"))
            return
        }
        viewModelScope.launch {
            val probed = try {
                importer.import(uri)
            } catch (e: MediaImportException) {
                emit(EditorEffect.ShowMessage(e.message ?: "Could not open the file"))
                return@launch
            }
            val fps = state.value.fps
            val others = state.value.assets.filter { it.id != assetId }.map { it.uri }
            val verdict = RelinkCheck.evaluate(old, probed, uri, others, MissingMedia.requiredSourceMicros(history.timeline, assetId, fps))
            val warnings = when (verdict) {
                is RelinkVerdict.Rejected -> {
                    emit(EditorEffect.ShowMessage(verdict.reason))
                    return@launch
                }
                is RelinkVerdict.Accepted -> verdict.warnings
            }
            val relinked = if (probed.isImage) {
                old.copy(uri = uri, displayName = probed.displayName ?: old.displayName)
            } else {
                // Audio-only files have no native frame rate; use the project's.
                val (num, den) = if (probed.hasVideo) probed.fpsNum to probed.fpsDen else fps.num to fps.den
                val durationFrames = FrameRate(num, den).microsToFrames(probed.durationMicros)
                if (durationFrames <= 0) {
                    emit(EditorEffect.ShowMessage("The file is too short to use"))
                    return@launch
                }
                old.copy(
                    uri = uri,
                    durationFrames = durationFrames,
                    nativeFpsNum = num,
                    nativeFpsDen = den,
                    colorSpace = probed.colorSpace,
                    hasVideo = probed.hasVideo,
                    hasAudio = probed.hasAudio,
                    displayName = probed.displayName ?: old.displayName,
                )
            }
            // Waveforms and thumbnails were made from the old file; the new key makes the native side start over.
            mediaCaches.invalidate(assetId)
            assetKeys.rekey(assetId)
            reduce {
                val stillMissing = missingMedia - assetId
                copy(
                    assets = assets.map { if (it.id == assetId) relinked else it },
                    missingMedia = stillMissing,
                    relinkOpen = relinkOpen && stillMissing.isNotEmpty(),
                )
            }
            scheduleSave()
            val name = MissingMedia.nameOf(relinked)
            emit(EditorEffect.ShowMessage(if (warnings.isEmpty()) "Relinked $name" else "Relinked $name. ${warnings.joinToString(". ")}"))
        }
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
            val saved = if (dirty) persist() else true
            if (!thenClose) return@launch
            // Closing on top of a failed save would silently throw the edits away: ask first.
            if (saved) emit(EditorEffect.Close) else reduce { copy(leaveBlockedBySave = true) }
        }
    }

    private fun retrySave() {
        reduce { copy(leaveBlockedBySave = false) }
        saveRetries = 0
        saveRetryJob?.cancel()
        saveJob?.cancel()
        saveJob = viewModelScope.launch { persist() }
    }

    /** Writes the project; returns whether it is on disk. A failure stays visible in [EditorState.saveError] and is retried. */
    private suspend fun persist(): Boolean {
        val base = baseProject ?: return true
        val dto = TimelineMapper.toDto(base, history.timeline, state.value.assets)
        return try {
            store.save(dto)
            baseProject = dto
            dirty = false
            saveRetries = 0
            saveRetryJob?.cancel()
            if (state.value.saveError != null) reduce { copy(saveError = null, leaveBlockedBySave = false) }
            true
        } catch (e: ProjectError) {
            val first = state.value.saveError == null
            reduce { copy(saveError = e.message ?: "unknown error") }
            if (first) emit(EditorEffect.ShowMessage("Could not save the project: ${e.message}"))
            saveRetryJob?.cancel()
            // A few quiet retries cover a transient failure (a full disk someone just cleared); after that the
            // banner stays and Retry is the user's call, instead of writing to a broken disk for ever.
            if (saveRetries < MAX_SAVE_RETRIES) {
                saveRetries++
                saveRetryJob = viewModelScope.launch {
                    delay(SAVE_RETRY_MILLIS)
                    persist()
                }
            }
            false
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
            // Above the lanes (room left by the bottom-anchored stack) a tap is a tap on nothing; OUTSIDE only occurs mid-drag.
            HitKind.NONE, HitKind.ABOVE_LANES, HitKind.OUTSIDE -> reduce { copy(selectedClipId = null) }
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
                dropHint = null,
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

    private fun moveSelectedTrack(delta: Int) {
        val id = state.value.selectedTrackId
        if (id == null) {
            emit(EditorEffect.ShowMessage("Tap a track to select it first"))
            return
        }
        execute(EditCommand.MoveTrack(id, delta))
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

    /**
     * The drop target under the finger. [trackIndex] indexes the timeline being shown, which during a
     * drag may contain the provisional new lane; that lane (not in the committed timeline) keeps the
     * target on 'add a lane' so the preview does not flicker as it appears.
     */
    private fun dropTarget(session: DragSession, committed: Timeline, trackIndex: Int, zone: DragZone): DropTarget? = when (zone) {
        DragZone.OUTSIDE -> DropTarget.Outside
        DragZone.ABOVE_LANES -> DropTarget.AboveLanes
        DragZone.LANES -> {
            val shown = state.value.dragPreview ?: committed
            val id = shown.tracks.getOrNull(trackIndex)?.id
            when {
                id == null -> session.target
                committed.track(id) == null -> DropTarget.AboveLanes
                else -> DropTarget.Lane(id)
            }
        }
    }

    private fun dragMove(frame: Long, trackIndex: Int, zone: DragZone) {
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
                val target = dropTarget(session, base, trackIndex, zone)
                session.target = target
                moveDrag(session, base, FrameIndex((frame - session.grabOffset).coerceAtLeast(0)), target ?: DropTarget.Lane(sourceTrack.id), playhead)
                return
            }
            DragMode.TRIM_START -> EditCommand.TrimClip(
                clipId = session.clipId,
                edge = TrimEdge.START,
                frame = snapFrame(base, session.clipId, frame, playhead),
            )
            DragMode.TRIM_END -> EditCommand.TrimClip(
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
            reduce { copy(dragPreview = result.value, dropHint = null) }
        }
    }

    /**
     * One step of a clip move: [DropPlan] decides what releasing here would do, the preview shows it
     * and the hint tells the timeline which indicator to draw. The decision's command is what a
     * release applies, so the indicator and the result always agree.
     */
    private fun moveDrag(session: DragSession, base: Timeline, start: FrameIndex, target: DropTarget, playhead: FrameIndex) {
        val decision = DropPlan.decide(base, session.clipId, start, target, snapWith(base, playhead)) ?: return
        val command = decision.command
        if (command == null) {
            // Cancel: the clip shows where it started and a release changes nothing.
            pendingDragCommand = null
            reduce { copy(dragPreview = null, dropHint = decision.hint) }
            return
        }
        val result = command.apply(base) as? EditResult.Success ?: return
        val preview = result.value
        pendingDragCommand = if (preview == base) null else command
        val hint = when (decision.kind) {
            DropKind.MOVE -> null
            // Reordering inside the base lands at a cut: show it as an insertion marker there.
            DropKind.REORDER -> preview.trackOfClip(session.clipId)?.clip(session.clipId)?.let {
                DropHint(DropKind.INSERT, preview.trackOfClip(session.clipId)?.id, it.timelineStart.value, it.timelineStart.value)
            }
            // The new lane exists only in the preview: point at the lane that is not in the committed timeline.
            DropKind.NEW_LANE -> DropHint(DropKind.NEW_LANE, preview.tracks.firstOrNull { base.track(it.id) == null }?.id, decision.hint.startFrame, decision.hint.endFrame)
            else -> decision.hint
        }
        reduce { copy(dragPreview = preview, dropHint = hint) }
    }

    private fun dragEnd(commit: Boolean) {
        val command = pendingDragCommand
        drag = null
        pendingDragCommand = null
        if (commit && command != null && execute(command)) return
        reduce { copy(dragPreview = null, dropHint = null) }
    }

    private fun snapFrame(timeline: Timeline, movingClipId: String, frame: Long, playhead: FrameIndex): FrameIndex {
        val targets = buildList {
            add(0L)
            add(playhead.value)
            if (state.value.snapToMarkers) timeline.markers.forEach { add(it.frame.value) }
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

    /** Snapping for a drag: clip edges and the playhead, plus the ruler markers while marker snapping is on. */
    private fun snapWith(timeline: Timeline, playhead: FrameIndex): Snap =
        Snap(playhead, SNAP_THRESHOLD_FRAMES, if (state.value.snapToMarkers) timeline.markers.map { it.frame } else emptyList())

    // region markers, beats and text templates

    private fun toggleMarkerAtPlayhead() {
        val playhead = state.value.playhead
        val existing = MarkerOps.nearest(history.timeline.markers, playhead, MARKER_TOGGLE_RADIUS_FRAMES)
        if (existing != null) {
            execute(RemoveMarker(existing.id))
        } else {
            execute(AddMarker(Marker("marker-${idGenerator()}", playhead, MarkerKind.MANUAL)))
        }
    }

    private fun clearBeatMarkers() {
        if (history.timeline.markers.none { it.kind == MarkerKind.BEAT }) {
            emit(EditorEffect.ShowMessage("There are no beat markers to clear"))
            return
        }
        execute(SetBeatMarkers(emptyList()))
    }

    private fun analyzeBeats() {
        if (state.value.isAnalyzingBeats) return
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        if (clip == null || !clip.hasMedia || asset == null || !asset.hasAudio || clip.isFreeze) {
            emit(EditorEffect.ShowMessage("Select a clip with audio to find its beats"))
            return
        }
        val fps = state.value.fps
        // Look a few seconds beyond the clip so a short clip still shows the song's repeating pulse.
        val startMicros = (fps.framesToMicros(clip.sourceIn.value) - BEAT_WINDOW_PADDING_MICROS).coerceAtLeast(0)
        val endMicros = fps.framesToMicros(clip.sourceOut.value) + BEAT_WINDOW_PADDING_MICROS
        reduce { copy(isAnalyzingBeats = true) }
        viewModelScope.launch {
            try {
                when (val result = beatSource.analyze(asset.id, startMicros, endMicros)) {
                    BeatResult.NoWaveform -> emit(EditorEffect.ShowMessage("The waveform is still being prepared. Try again in a moment."))
                    BeatResult.NoBeat -> emit(EditorEffect.ShowMessage("No clear beat found in this audio"))
                    is BeatResult.Found -> placeBeats(clip.id, result)
                }
            } finally {
                reduce { copy(isAnalyzingBeats = false) }
            }
        }
    }

    private fun placeBeats(clipId: String, found: BeatResult.Found) {
        // The clip may have been edited or removed while the analysis ran: work from where it is now.
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return
        val run = idGenerator()
        val absolute = found.grid.beatsMicros.map { it + found.windowStartMicros }
        val markers = BeatMapping.markersFor(clip, absolute, state.value.fps) { "beat-$run-$it" }
        if (markers.isEmpty()) {
            emit(EditorEffect.ShowMessage("No beats fall inside this clip"))
            return
        }
        if (execute(SetBeatMarkers(markers, from = clip.timelineStart, until = clip.timelineEnd))) {
            emit(EditorEffect.ShowMessage("Marked ${markers.size} beats at about ${found.grid.bpm.toInt()} BPM"))
        }
    }

    private fun cutToBeatFromSelected() {
        val timeline = history.timeline
        val base = ClipDeletion.baseTrack(timeline)
        val selected = state.value.selectedClipId?.let { id -> base?.clip(id) }
        if (base == null || selected == null) {
            emit(EditorEffect.ShowMessage("Select a clip on the base track first"))
            return
        }
        val ids = base.clips.filter { it.timelineStart >= selected.timelineStart }.map { it.id }
        val lengths = base.clips.associate { it.id to assetLengthFrames(it.assetId) }
        execute(CutToBeat(ids, timeline.markers.map { it.frame.value }, lengths))
    }

    private fun applyTextTemplate(templateId: String, text: String) = applyTemplate(TextTemplates.find(templateId), text)

    /** Puts [template] (a built-in or a saved preset) on the timeline at the playhead as one undo step and selects it. */
    private fun applyTemplate(template: TextTemplate?, text: String) {
        if (template == null) {
            emit(EditorEffect.ShowMessage("That text template is not available"))
            return
        }
        val fps = state.value.fps
        val frames = fps.microsToFrames((template.defaultSeconds * MICROS_PER_SECOND).toLong()).coerceAtLeast(2)
        val token = idGenerator()
        val ids = List(TextTemplates.idCount(template)) { "tpl-$token-$it" }
        val command = AddTextTemplate(
            templateId = template.id,
            template = template,
            text = text.ifBlank { template.defaultText },
            start = state.value.playhead,
            durationFrames = frames,
            canvasWidth = state.value.canvasWidth,
            canvasHeight = state.value.canvasHeight,
            fps = fps,
            ids = ids,
        )
        if (!execute(command)) return
        // A template is one title clip: select it so the inspector shows what the user will want to change.
        val clipId = ids[0]
        val trackId = history.timeline.trackOfClip(clipId)?.id
        reduce { copy(selectedClipId = clipId, selectedTrackId = trackId, inspectorOpen = true) }
    }

    // endregion

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

    private fun updateGrade(effectId: String, values: List<Double>, curves: GradeCurves?) {
        val session = beginFx() ?: return
        val effect = session.fx.effect(effectId) ?: return
        val changed = effect.copy(values = values, curves = curves?.takeUnless { it.isIdentity })
        if (effect.type != EffectType.COLOR_GRADE) return
        changed.problem()?.let {
            emit(EditorEffect.ShowMessage("That value is not allowed: $it"))
            return
        }
        session.fx = session.fx.copy(effects = session.fx.effects.map { if (it.id == effectId) changed else it })
        showFx(session)
    }

    /** A look or a pasted grade: replaces the clip's first colour grade, or adds one, as one undo step. */
    private fun applyGrade(values: List<Double>, curves: GradeCurves?) = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        val existing = clip.fx.effects.firstOrNull { it.type == EffectType.COLOR_GRADE }
        if (existing == null && clip.fx.effects.size >= ClipFx.MAX_EFFECTS) {
            emit(EditorEffect.ShowMessage("A clip can have at most ${ClipFx.MAX_EFFECTS} effects"))
            return@withSelection
        }
        execute(EditCommand.SetGrade(clipId, existing?.id ?: idGenerator(), values, curves))
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

    private fun addSticker(stickerId: String) {
        if (!StickerIds.isKnown(stickerId)) {
            emit(EditorEffect.ShowMessage("That sticker is not available"))
            return
        }
        val timeline = history.timeline
        val base = ClipDeletion.baseTrack(timeline)
        val selected = timeline.tracks.firstOrNull { it.id == state.value.selectedTrackId }
        val overlay = selected?.takeIf { it.type == TrackType.VIDEO && it.id != base?.id }
            ?: timeline.tracks.firstOrNull { it.type == TrackType.VIDEO && it.id != base?.id }
        val trackId = overlay?.id ?: run {
            // Only the base exists: stickers get a lane above it (its own undo step).
            val track = Track(uniqueTrackId(timeline.tracks, "track-v"), TrackType.VIDEO)
            val index = timeline.tracks.indexOfFirst { it.type == TrackType.VIDEO }.coerceAtLeast(0)
            if (!execute(EditCommand.AddTrack(track, index))) return
            track.id
        }
        val frames = state.value.fps.microsToFrames(STICKER_DEFAULT_MICROS).coerceAtLeast(1)
        val clip = Clip(
            id = "sticker-${idGenerator()}",
            assetId = stickerId,
            timelineStart = state.value.playhead,
            sourceIn = FrameIndex.ZERO,
            sourceOut = FrameIndex(frames),
            still = StillKind.STICKER,
        )
        if (!execute(EditCommand.Overwrite(trackId, clip))) return
        reduce { copy(selectedClipId = clip.id, selectedTrackId = trackId, inspectorOpen = true) }
    }

    private fun addCaptionClips(clips: List<Clip>, intoExistingTrack: Boolean) {
        if (clips.isEmpty()) return
        // Typed captions share one caption track; an imported file gets a track of its own (one per language).
        val existing = history.timeline.tracks.firstOrNull { track ->
            track.type == TrackType.TITLE && track.clips.isNotEmpty() && track.clips.all { it.id.startsWith(CAPTION_ID_PREFIX) }
        }
        if (intoExistingTrack && existing != null) {
            if (!execute(AddCaptionsToTrack(existing.id, clips))) return
            reduce { copy(selectedTrackId = existing.id, selectedClipId = clips.first().id) }
            return
        }
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

    private fun selectTitleLayer(index: Int) {
        val clipId = state.value.selectedClipId
        reduce { copy(titleLayerRef = if (clipId != null && index >= 0) TitleLayerRef(clipId, index) else null) }
    }

    /** A step of a preview gesture on the selected layer of a multilayer title: moves, scales and turns just that layer. */
    private fun layerGesture(step: EditorIntent.LayerGesture) {
        val ref = state.value.titleLayerRef ?: return
        val clipId = state.value.selectedClipId?.takeIf { it == ref.clipId } ?: return
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return
        val base = titleEdit?.takeIf { it.clipId == clipId }?.content ?: clip.title ?: return
        if (!base.isLayered || ref.index !in base.layers.indices) return
        val layer = base.layers[ref.index]
        val p = layer.placement
        // Gestures are in canvas pixels; offsets are canvas fractions inside a title that the clip's own scale then enlarges.
        val clipScale = clip.transform.scaleX.coerceAtLeast(MIN_CLIP_SCALE)
        val moved = p.copy(
            offsetX = (p.offsetX + step.panX / (state.value.canvasWidth * clipScale)).coerceIn(-LayerPlacement.MAX_OFFSET, LayerPlacement.MAX_OFFSET),
            offsetY = (p.offsetY + step.panY / (state.value.canvasHeight * clipScale)).coerceIn(-LayerPlacement.MAX_OFFSET, LayerPlacement.MAX_OFFSET),
            scale = (p.scale * step.zoom).coerceIn(LayerPlacement.MIN_SCALE, LayerPlacement.MAX_SCALE),
            rotationDegrees = p.rotationDegrees + step.rotationDegrees,
        )
        updateTitle(TitleLayerEdit.replace(base, ref.index, layer.withPlacement(moved)))
    }

    /** In and out animation of the selected title as keyframes, replacing the clip's own. */
    private fun applyTitleMotion(intro: MotionPreset, outro: MotionPreset) = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId)
        if (clip?.title == null) {
            emit(EditorEffect.ShowMessage("Select a title first"))
            return@withSelection
        }
        val edge = state.value.fps.microsToFrames((TitleMotion.DEFAULT_EDGE_SECONDS * MICROS_PER_SECOND).toLong()).coerceAtLeast(1)
        execute(SetTitleMotion(clipId, intro, outro, edge, state.value.canvasWidth, state.value.canvasHeight))
    }

    // region speed

    /** Slowing down pushes the clips after this one later and speeding up pulls them earlier, so a slow-motion clip never fails on its neighbour. */
    private fun setSpeed(num: Long, den: Long) = withSelection { clipId ->
        execute(EditCommand.SetSpeed(clipId, num, den, ripple = true))
    }

    private fun toggleReverse() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        execute(EditCommand.SetReverse(clipId, !clip.reverse))
    }

    private fun setSpeedRamp(shape: SpeedRampShape) = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        val ramp = when (shape) {
            SpeedRampShape.NONE -> emptyList()
            SpeedRampShape.EASE_IN -> SpeedRamps.easeIn(clip.durationFrames)
            SpeedRampShape.EASE_OUT -> SpeedRamps.easeOut(clip.durationFrames)
            SpeedRampShape.BELL -> SpeedRamps.bell(clip.durationFrames)
        }
        if (shape != SpeedRampShape.NONE && ramp.isEmpty()) {
            emit(EditorEffect.ShowMessage("This clip is too short for a speed ramp"))
            return@withSelection
        }
        execute(EditCommand.SetSpeedRamp(clipId, ramp))
    }

    private fun freezeFrame() = withSelection { clipId ->
        val track = history.timeline.trackOfClip(clipId) ?: return@withSelection
        val clip = track.clip(clipId) ?: return@withSelection
        val playhead = state.value.playhead
        if (track.type != TrackType.VIDEO || playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            emit(EditorEffect.ShowMessage("Move the playhead inside the selected video clip to freeze a frame"))
            return@withSelection
        }
        val frames = state.value.fps.microsToFrames(FREEZE_DEFAULT_MICROS).coerceAtLeast(1)
        val still = "freeze-${idGenerator()}"
        if (execute(EditCommand.FreezeFrame(track.id, playhead, frames, still, "$clipId~${idGenerator()}"))) {
            reduce { copy(selectedClipId = still) }
        }
    }

    // endregion

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

    // region media tray

    /** The clip that placing [asset] creates and the kind of lane it needs, or null if it has no usable length. */
    private fun newClipFor(asset: MediaAssetDto): Pair<Clip, TrackType>? {
        val type = if (asset.hasVideo || asset.isImage) TrackType.VIDEO else TrackType.AUDIO
        val length = (if (asset.isImage) stillLengthFrames() else assetLengthFrames(asset.id)) ?: return null
        val clip = Clip(
            "clip-${idGenerator()}", asset.id, FrameIndex.ZERO, FrameIndex.ZERO, FrameIndex(length),
            still = StillKind.PHOTO.takeIf { asset.isImage },
        )
        return clip to type
    }

    private fun trayDragStart(assetId: String) {
        if (drag != null || trayDrag != null) return
        val asset = state.value.assets.firstOrNull { it.id == assetId }
        if (asset == null) {
            emit(EditorEffect.ShowMessage("That media is no longer in the project"))
            return
        }
        if (asset.id in state.value.missingMedia) {
            emit(EditorEffect.ShowMessage("${MissingMedia.nameOf(asset)} is missing: relink it first"))
            return
        }
        val (clip, type) = newClipFor(asset) ?: return
        trayDrag = TrayDragSession(asset.id, clip, type)
    }

    /** Hovering files are only known by kind: a stand-in clip of a plausible length lets the indicator show where they would land. */
    private fun externalDragStart(kinds: List<AssetKind>) {
        if (drag != null || trayDrag != null) return
        val kind = kinds.firstOrNull() ?: return
        val type = if (kind == AssetKind.AUDIO) TrackType.AUDIO else TrackType.VIDEO
        val length = if (kind == AssetKind.PHOTO) stillLengthFrames() else state.value.fps.microsToFrames(HOVER_MICROS).coerceAtLeast(1)
        trayDrag = TrayDragSession(null, Clip("clip-hover", null, FrameIndex.ZERO, FrameIndex.ZERO, FrameIndex(length)), type)
    }

    /** The lane under the finger; across a gap between lanes the last one stays, and with none yet the drop cancels. */
    private fun trayTarget(committed: Timeline, trackIndex: Int, zone: DragZone, previous: DropTarget?): DropTarget = when (zone) {
        DragZone.OUTSIDE -> DropTarget.Outside
        DragZone.ABOVE_LANES -> DropTarget.AboveLanes
        DragZone.LANES -> committed.tracks.getOrNull(trackIndex)?.let { DropTarget.Lane(it.id) } ?: previous ?: DropTarget.Outside
    }

    private fun trayDragMove(frame: Long, trackIndex: Int, zone: DragZone) {
        val session = trayDrag ?: return
        val base = history.timeline
        val target = trayTarget(base, trackIndex, zone, session.target)
        session.target = target
        val decision = DropPlan.decideNew(base, session.clip, session.type, FrameIndex(frame), target, snapWith(base, state.value.playhead))
        session.command = decision.command
        if (decision.kind == DropKind.NEW_LANE) {
            // The canvas draws the new-lane placeholder on a lane of the timeline it shows, so show one (empty).
            val top = base.tracks.indexOfFirst { it.type == TrackType.VIDEO }.coerceAtLeast(0)
            val preview = (TimelineOps.addTrack(base, Track(DROP_LANE_ID, TrackType.VIDEO), top) as? EditResult.Success)?.value
            reduce { copy(dragPreview = preview, dropHint = decision.hint.copy(trackId = DROP_LANE_ID)) }
            return
        }
        // Free space is shown like an overwrite: the tinted range is where the clip will land.
        val hint = if (decision.kind == DropKind.MOVE) decision.hint.copy(kind = DropKind.OVERWRITE) else decision.hint
        reduce { copy(dragPreview = null, dropHint = hint) }
    }

    private fun trayDragEnd(commit: Boolean) {
        val session = trayDrag
        trayDrag = null
        reduce { copy(dragPreview = null, dropHint = null) }
        if (!commit || session == null || session.assetId == null) return
        val command = session.command ?: return
        if (execute(command)) selectPlaced(session.clip.id)
    }

    private fun selectPlaced(clipId: String) {
        val track = history.timeline.trackOfClip(clipId) ?: return
        reduce { copy(selectedClipId = clipId, selectedTrackId = track.id) }
    }

    /** Files dropped from another app: imported first (their length is unknown until probed), then placed where they landed. */
    private fun externalDrop(uris: List<String>, frame: Long, trackIndex: Int, zone: DragZone) {
        val previous = trayDrag?.target
        trayDrag = null
        reduce { copy(dragPreview = null, dropHint = null) }
        if (uris.isEmpty()) return
        val target = trayTarget(history.timeline, trackIndex, zone, previous)
        viewModelScope.launch {
            reduce { copy(isImporting = true) }
            var cursor: FrameIndex? = null
            for (uri in uris) {
                try {
                    val asset = assetFor(uri)
                    cursor = if (cursor == null) placeDropped(asset, frame, target) else place(asset, cursor)
                } catch (e: MediaImportException) {
                    emit(EditorEffect.ShowMessage(e.message ?: "Could not import the file"))
                }
            }
            reduce { copy(isImporting = false) }
        }
    }

    /** Places [asset] the way a drop at [frame] over [target] would; returns where the clip ends, or null if nothing was placed. */
    private fun placeDropped(asset: MediaAssetDto, frame: Long, target: DropTarget): FrameIndex? {
        val (clip, type) = newClipFor(asset) ?: return null
        val decision = DropPlan.decideNew(history.timeline, clip, type, FrameIndex(frame), target, snapWith(history.timeline, state.value.playhead))
        val command = decision.command
        if (command == null) {
            emit(EditorEffect.ShowMessage("Drop ${MissingMedia.nameOf(asset)} on a ${type.name.lowercase()} lane"))
            return null
        }
        if (!execute(command)) return null
        selectPlaced(clip.id)
        return history.timeline.trackOfClip(clip.id)?.clip(clip.id)?.timelineEnd
    }

    private fun importToTray(uris: List<String>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            reduce { copy(isImporting = true) }
            for (uri in uris) {
                try {
                    assetFor(uri)
                } catch (e: MediaImportException) {
                    emit(EditorEffect.ShowMessage(e.message ?: "Could not import the file"))
                }
            }
            reduce { copy(isImporting = false) }
        }
    }

    /** The library order is the order of `mediaLibrary` in the project file, so it saves with the project. */
    private fun reorderAsset(assetId: String, toIndex: Int) {
        val moved = moveAsset(state.value.assets, assetId, toIndex)
        if (moved === state.value.assets) return
        reduce { copy(assets = moved) }
        scheduleSave()
    }

    // endregion

    private fun addAssetById(assetId: String) {
        val asset = state.value.assets.firstOrNull { it.id == assetId }
        if (asset == null) {
            emit(EditorEffect.ShowMessage("That media is no longer in the project"))
            return
        }
        if (asset.id in state.value.missingMedia) {
            emit(EditorEffect.ShowMessage("${MissingMedia.nameOf(asset)} is missing: relink it first"))
            return
        }
        place(asset, state.value.playhead)
    }

    /** Returns the library entry for [uri], importing and registering it first if it is new. */
    private suspend fun assetFor(uri: String): MediaAssetDto {
        state.value.assets.firstOrNull { it.uri == uri }?.let { return it }
        val probed = importer.import(uri)
        val project = state.value.fps
        if (probed.isImage) {
            // A picture has no length of its own; the asset's "duration" is the default length of a clip of it.
            val image = MediaAssetDto(
                id = "asset-${idGenerator()}",
                uri = uri,
                durationFrames = project.microsToFrames(PHOTO_DEFAULT_MICROS).coerceAtLeast(1),
                nativeFpsNum = project.num,
                nativeFpsDen = project.den,
                colorSpace = probed.colorSpace,
                hasVideo = false,
                hasAudio = false,
                isImage = true,
            )
            reduce { copy(assets = assets + image) }
            scheduleSave()
            return image
        }
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
        val type = if (asset.hasVideo || asset.isImage) TrackType.VIDEO else TrackType.AUDIO
        // The selected track if it fits the media, else the first track of the right type.
        val selected = history.timeline.tracks.firstOrNull { it.id == state.value.selectedTrackId }
        val track = selected?.takeIf { it.type == type } ?: history.timeline.tracks.firstOrNull { it.type == type }
        if (track == null) {
            emit(EditorEffect.ShowMessage("There is no ${type.name.lowercase()} track to place the clip on"))
            return null
        }
        val length = (if (asset.isImage) stillLengthFrames() else assetLengthFrames(asset.id)) ?: return null
        val clip = Clip(
            "clip-${idGenerator()}", asset.id, start, FrameIndex.ZERO, FrameIndex(length),
            still = StillKind.PHOTO.takeIf { asset.isImage },
        )
        // On the base track new media is inserted (everything after ripples); elsewhere it overwrites.
        val onBase = track.id == ClipDeletion.baseTrack(history.timeline)?.id
        val command = if (onBase) EditCommand.InsertBase(clip, start) else EditCommand.Overwrite(track.id, clip)
        if (!execute(command)) return null
        reduce { copy(selectedClipId = clip.id, selectedTrackId = track.id) }
        // The base inserts at a clip boundary, which may differ from [start]: continue from where the clip landed.
        return history.timeline.trackOfClip(clip.id)?.clip(clip.id)?.timelineEnd ?: clip.timelineEnd
    }

    /** Default length of a new photo clip, in project frames. */
    private fun stillLengthFrames(): Long = state.value.fps.microsToFrames(PHOTO_DEFAULT_MICROS).coerceAtLeast(1)

    /** Length of an asset in project frames, or null if it is not in the library (or has no length of its own). */
    private fun assetLengthFrames(assetId: String?): Long? {
        val asset = state.value.assets.firstOrNull { it.id == assetId } ?: return null
        // A picture has no length to run out of.
        if (asset.isImage) return null
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
        EditError.NoBaseTrack -> "Add a video track first"
        is EditError.BaseClipCannotLeave -> "Only a base clip can be lifted off the base"
        is EditError.BaseTrackCannotMove -> "The base track stays at the bottom of the video lanes"
        is EditError.TrackCannotMove -> "That lane cannot move further: ${error.reason}"
        is EditError.NotATitle -> "That clip is not a title"
        is EditError.InvalidKeyframe -> "That keyframe is not possible: ${error.reason}"
        is EditError.KeyframeNotFound -> "There is no keyframe there"
        is EditError.InvalidSpeed -> "That speed is not possible: ${error.reason}"
        is EditError.InvalidEffect -> "That effect is not possible: ${error.reason}"
        is EditError.EffectNotFound -> "That effect no longer exists"
        is EditError.InvalidMarker -> "That marker is not possible: ${error.reason}"
        is EditError.MarkerNotFound -> "The marker no longer exists"
        is EditError.InvalidTemplate -> "That text template cannot be placed: ${error.reason}"
        is EditError.CutToBeatUnavailable -> "Cut to beat is not possible: ${error.reason}"
        is EditError.DuplicateClipId, is EditError.DuplicateTrackId, is EditError.DuplicateTransitionId,
        is EditError.InvalidClip, is EditError.TrackTypeMismatch -> "That edit is not valid"
    }

    private companion object {
        const val ID_LENGTH = 8
        const val DEFAULT_SAVE_DEBOUNCE_MILLIS = 500L
        const val SAVE_RETRY_MILLIS = 5_000L
        const val MAX_SAVE_RETRIES = 3
        const val SNAP_THRESHOLD_FRAMES = 8L
        const val NO_ASSET_KEY = -1L
        const val DEFAULT_TITLE_TEXT = "Title"
        const val TITLE_DEFAULT_MICROS = 3_000_000L
        const val STICKER_DEFAULT_MICROS = 3_000_000L
        const val PHOTO_DEFAULT_MICROS = 5_000_000L
        private const val HOVER_MICROS = 3_000_000L
        private const val DROP_LANE_ID = "track-v-new"
        const val TRANSITION_DEFAULT_MICROS = 1_000_000L
        const val FREEZE_DEFAULT_MICROS = 2_000_000L
        const val PLAY_TICK_MILLIS = 16L
        const val NANOS_PER_MICRO = 1_000L
        const val MICROS_PER_SECOND = 1_000_000.0
        private const val MIN_CLIP_SCALE = 0.05
        const val MARKER_TOGGLE_RADIUS_FRAMES = 2L
        const val BEAT_WINDOW_PADDING_MICROS = 8_000_000L
    }
}

package com.ultimatevideo.uveditor.ui.editor

import androidx.lifecycle.viewModelScope
import com.ultimatevideo.uveditor.data.MediaImportException
import com.ultimatevideo.uveditor.data.MediaCaches
import com.ultimatevideo.uveditor.data.MediaImporter
import com.ultimatevideo.uveditor.data.MediaProblem
import com.ultimatevideo.uveditor.data.MissingMedia
import com.ultimatevideo.uveditor.data.interchange.Edl
import com.ultimatevideo.uveditor.data.interchange.Fcpxml
import com.ultimatevideo.uveditor.data.interchange.InterchangeExporter
import com.ultimatevideo.uveditor.domain.AnimationTiming
import com.ultimatevideo.uveditor.domain.AnnotateMarker
import com.ultimatevideo.uveditor.ui.editor.tray.usageCounts
import com.ultimatevideo.uveditor.ui.library.Library
import com.ultimatevideo.uveditor.ui.library.LibraryQuery
import java.io.IOException
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
import com.ultimatevideo.uveditor.domain.AutoCut
import com.ultimatevideo.uveditor.domain.AutoCutPlanner
import com.ultimatevideo.uveditor.domain.ReframeClip
import com.ultimatevideo.uveditor.domain.ReframePoint
import com.ultimatevideo.uveditor.domain.SilenceDetector
import com.ultimatevideo.uveditor.engine.timeline.EnvelopeResult
import com.ultimatevideo.uveditor.engine.timeline.EnvelopeSource
import com.ultimatevideo.uveditor.engine.timeline.NoEnvelopeSource
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipAudio
import com.ultimatevideo.uveditor.domain.Denoise
import com.ultimatevideo.uveditor.domain.retime
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
import com.ultimatevideo.uveditor.domain.GroupEditUnavailable
import com.ultimatevideo.uveditor.domain.GroupTransitions
import com.ultimatevideo.uveditor.domain.GroupSetSpeed
import com.ultimatevideo.uveditor.domain.GroupSetOpacity
import com.ultimatevideo.uveditor.domain.GroupSetGain
import com.ultimatevideo.uveditor.domain.GroupPasteAttributes
import com.ultimatevideo.uveditor.domain.GroupPaste
import com.ultimatevideo.uveditor.domain.GroupOps
import com.ultimatevideo.uveditor.domain.GroupMove
import com.ultimatevideo.uveditor.domain.GroupDuplicate
import com.ultimatevideo.uveditor.domain.GroupDelete
import com.ultimatevideo.uveditor.domain.GroupAlign
import com.ultimatevideo.uveditor.domain.ClipSelection
import com.ultimatevideo.uveditor.domain.ClipAttributes
import com.ultimatevideo.uveditor.domain.Clipboard
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframe
import com.ultimatevideo.uveditor.domain.Keyframes
import com.ultimatevideo.uveditor.domain.Qualifier
import com.ultimatevideo.uveditor.domain.LaneOps
import com.ultimatevideo.uveditor.domain.BezierHandle
import com.ultimatevideo.uveditor.domain.ParamIds
import com.ultimatevideo.uveditor.domain.ParamKey
import com.ultimatevideo.uveditor.domain.ParamTracks
import com.ultimatevideo.uveditor.domain.displayedAt
import com.ultimatevideo.uveditor.domain.fxAt
import com.ultimatevideo.uveditor.domain.paramKeys
import com.ultimatevideo.uveditor.domain.paramSpec
import com.ultimatevideo.uveditor.domain.paramValueAt
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
import com.ultimatevideo.uveditor.engine.timeline.SnapshotLabel
import com.ultimatevideo.uveditor.engine.timeline.SnapshotRetime
import com.ultimatevideo.uveditor.engine.timeline.SnapshotTrackType
import com.ultimatevideo.uveditor.engine.timeline.SnapshotTransition
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.engine.timeline.TimelineSnapshot
import com.ultimatevideo.uveditor.mvi.MviViewModel
import com.ultimatevideo.uveditor.domain.MotionTrack
import com.ultimatevideo.uveditor.domain.sourceFrameAtProjectFrame
import kotlinx.coroutines.withContext
import com.ultimatevideo.uveditor.domain.TrackMath
import com.ultimatevideo.uveditor.domain.TrackSeed
import com.ultimatevideo.uveditor.engine.sample.FrameSampler
import com.ultimatevideo.uveditor.engine.sample.NoFrameSampler
import com.ultimatevideo.uveditor.engine.stabilise.NoStabiliser
import com.ultimatevideo.uveditor.engine.track.MotionTracker
import com.ultimatevideo.uveditor.engine.multicam.MulticamServices
import com.ultimatevideo.uveditor.domain.multicam.AngleFeed
import com.ultimatevideo.uveditor.domain.multicam.MulticamClip
import com.ultimatevideo.uveditor.ui.editor.multicam.MulticamController
import com.ultimatevideo.uveditor.ui.editor.multicam.MulticamUiState
import com.ultimatevideo.uveditor.engine.track.NoMotionTracker
import com.ultimatevideo.uveditor.engine.track.TrackOutcome
import com.ultimatevideo.uveditor.engine.track.TrackStatus
import com.ultimatevideo.uveditor.engine.stabilise.StabOutcome
import com.ultimatevideo.uveditor.engine.stabilise.StabStatus
import com.ultimatevideo.uveditor.engine.stabilise.Stabiliser
import com.ultimatevideo.uveditor.ui.editor.tray.AssetKind
import com.ultimatevideo.uveditor.ui.editor.tray.moveAsset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
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
    /** Loudness measurements kept per file and range (normalising the same clip again does not decode it). */
    private val loudnessCache: LoudnessCache = LoudnessCache.None,
    /** Camera-shake analysis and the correction tables the preview and the exporter read. */
    private val stabiliser: Stabiliser = NoStabiliser,
    /** Where stabiliser bookkeeping (reading cache headers, building tables) runs. */
    private val stabDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Reads a colour out of a clip's picture for the HSL qualifier's eyedropper. */
    private val frameSampler: FrameSampler = NoFrameSampler,
    /** Where the eyedropper reads pictures (it decodes a frame). */
    private val sampleDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Motion tracking of a point or box through a video clip (SPECS.md 9.15). */
    private val motionTracker: MotionTracker = NoMotionTracker,
    /** Where motion-track bookkeeping (reading cache files, building the overlay) runs. */
    private val trackDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /** Writes bundles, EDLs and FCPXML files; the default cannot write anything. */
    private val interchange: InterchangeExporter = InterchangeExporter.None,
    /** The loudness of a clip's audio, read from the waveform cache (silence detection). */
    private val envelopeSource: EnvelopeSource = NoEnvelopeSource,
    /** What the multicam editor needs from the engine: waveform envelopes to sync angles, proxy readiness, the decoder limit. */
    private val multicamServices: MulticamServices = MulticamServices.None,
) : MviViewModel<EditorState, EditorIntent, EditorEffect>(EditorState()) {

    private enum class DragMode { MOVE, TRIM_START, TRIM_END, PLAYHEAD }

    private class DragSession(val clipId: String, val mode: DragMode, val grabOffset: Long, val group: List<String>? = null) {
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
    private class FxSession(val clipId: String, val base: ClipFx, var fx: ClipFx, val frame: Long? = null)

    /** A title text/style edit in progress: shown live, committed as one undo step. */
    private class TitleSession(val clipId: String, val base: TitleContent, var content: TitleContent)

    private val clipKeys = KeyRegistry()
    private val assetKeys = KeyRegistry()

    private var history = EditHistory(Timeline())

    /** Picking, syncing and cutting multicam angles; it borrows this model's state, undo history and messages. */
    private val multicamController = MulticamController(
        object : MulticamController.Host {
            override val editor: EditorState get() = state.value
            override fun update(change: (MulticamUiState) -> MulticamUiState) = reduce { copy(multicam = change(multicam)) }
            override fun execute(command: EditCommand): Boolean = this@EditorViewModel.execute(command)
            override fun message(text: String) = emit(EditorEffect.ShowMessage(text))
            override fun newId(): String = idGenerator()
            override fun assetLengthFrames(assetId: String): Long? = this@EditorViewModel.assetLengthFrames(assetId)
        },
        multicamServices,
        viewModelScope,
    )

    /** Where each angle of [group] is shown from while [active] is on screen (the viewer's decoder budget). */
    fun multicamFeeds(group: MulticamClip, active: Int): List<AngleFeed> = multicamController.feeds(group, active)
    private var baseProject: ProjectDto? = null
    private var drag: DragSession? = null
    private var trayDrag: TrayDragSession? = null
    private var pendingDragCommand: EditCommand? = null
    private var appearance: AppearanceSession? = null
    private var titleEdit: TitleSession? = null
    private var fxEdit: FxSession? = null
    private var clipboard: Clipboard? = null
    private var saveJob: Job? = null
    private var saveRetryJob: Job? = null
    private var saveRetries = 0
    private var playJob: Job? = null

    /** Set by the screen once the audio engine is up. Until then the transport uses the system clock. */
    var playbackOutput: PlaybackOutput? = null

    /** Measures loudness and noise profiles for the audio tools; null where the engine is unavailable. */
    var audioAnalyzer: AudioAnalyzer? = null
    private var dirty = false

    init {
        load()
    }

    override fun onIntent(intent: EditorIntent) {
        // Typing in the title field is only provisional: any other action first makes it final.
        if (intent !is EditorIntent.UpdateTitle && intent !is EditorIntent.EndTitleEdit && intent !is EditorIntent.LayerGesture) endTitleEdit(commit = true)
        // A key drag in the keyframe lane is provisional until released.
        if (intent !is EditorIntent.UpdateParamKey && intent !is EditorIntent.EndParamKeyEdit) endParamKeyEdit(commit = true)
        // Same for an effect slider: it stays provisional until released or until something else happens.
        if (intent !is EditorIntent.UpdateEffect && intent !is EditorIntent.UpdateGrade && intent !is EditorIntent.UpdateMask &&
            intent !is EditorIntent.EndFxEdit
        ) {
            endFxEdit(commit = true)
        }
        // And for an audio slider (pan, EQ, track volume, ducking).
        if (intent !is EditorIntent.UpdateClipAudio && intent !is EditorIntent.UpdateTrackAudio &&
            intent !is EditorIntent.UpdateDucking && intent !is EditorIntent.EndAudioEdit
        ) {
            endAudioEdit(commit = true)
        }
        when (intent) {
            is EditorIntent.UpdateClipAudio -> withSelection { id ->
                // The controls show pan and EQ gains as they are at the playhead; keyframed ones that changed become keys.
                updateAudioEdit("clip:$id", EditCommand.SetClipAudioAt(id, intent.audio, state.value.selectedFrame))
            }
            is EditorIntent.UpdateTrackAudio -> updateAudioEdit("track:${intent.trackId}", EditCommand.SetTrackAudio(intent.trackId, intent.audio))
            is EditorIntent.UpdateDucking -> updateAudioEdit("ducking", EditCommand.SetDucking(intent.ducking))
            is EditorIntent.EndAudioEdit -> endAudioEdit(intent.commit)
            EditorIntent.ResetClipAudio -> resetClipAudio()
            is EditorIntent.NormalizeLoudness -> normalizeLoudness(intent.targetLufs)
            EditorIntent.ClearNormalize -> clearNormalize()
            is EditorIntent.MarkNoiseRegion -> markNoiseRegion(intent.atStart)
            EditorIntent.ClearNoiseRegion -> reduce { copy(noiseRegion = null) }
            is EditorIntent.AnalyzeNoise -> analyzeNoise(intent.strength)
            EditorIntent.RemoveNoiseSuppression -> removeNoiseSuppression()
            EditorIntent.CancelAudioAnalysis -> audioAnalyzer?.cancel()
            EditorIntent.ToggleMixer -> reduce { copy(mixerOpen = !mixerOpen) }
            is EditorIntent.Multicam -> multicamController.handle(intent.intent)
            is EditorIntent.TapTimeline -> tap(intent.hit)
            is EditorIntent.SetPlayhead -> seekTo(intent.frame)
            is EditorIntent.DragStart -> dragStart(intent.hit)
            is EditorIntent.DragMove -> dragMove(intent.frame, intent.trackIndex, intent.zone)
            is EditorIntent.DragEnd -> dragEnd(intent.commit)
            EditorIntent.SplitAtPlayhead -> splitAtPlayhead()
            EditorIntent.RippleDeleteSelected -> {
                val group = state.value.selection
                if (group.size > 1) deleteSelection() else withSelection { execute(EditCommand.DeleteClip(it)) }
            }
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
            is EditorIntent.SetTransitionStyle -> setTransitionStyle(intent.type, intent.direction)
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
            is EditorIntent.SetStabilise -> withSelection { execute(EditCommand.SetStabilise(it, intent.stabilise)) }
            EditorIntent.AnalyseStabilise -> analyseStabilise()
            EditorIntent.CancelStabilise -> stabiliser.cancel()
            EditorIntent.RefreshStabilise -> refreshStabilise()
            EditorIntent.RefreshTrack -> refreshTrack()
            EditorIntent.BeginTrackPick -> beginTrackPick()
            EditorIntent.CancelTrackPick -> reduce { copy(track = track.copy(picking = false)) }
            is EditorIntent.SetTrackBox -> reduce { copy(track = track.copy(boxSide = intent.side.coerceIn(TrackSeed.MIN_SIZE, 0.5))) }
            is EditorIntent.PickTrackTarget -> pickTrackTarget(intent.x, intent.y, intent.w, intent.h)
            EditorIntent.CancelTrack -> motionTracker.cancel()
            is EditorIntent.ReanalyseTrack -> startTrackAnalysis(intent.trackId)
            is EditorIntent.RemoveMotionTrack -> removeMotionTrack(intent.trackId)
            is EditorIntent.ShowTrack -> {
                reduce { copy(track = track.copy(activeId = intent.trackId, overlay = if (intent.trackId == null) emptyList() else track.overlay)) }
                refreshTrack()
            }
            is EditorIntent.FollowTrack -> followTrack(intent.trackId)
            is EditorIntent.UpdateMask -> updateMask(intent.mask)
            is EditorIntent.EndFxEdit -> endFxEdit(intent.commit)
            EditorIntent.ClearFx -> withSelection { execute(EditCommand.ClearFx(it)) }
            EditorIntent.ToggleKeyframe -> toggleKeyframe()
            is EditorIntent.JumpToKeyframe -> jumpToKeyframe(intent.forward)
            is EditorIntent.SetKeyframeInterpolation -> setKeyframeInterpolation(intent.interpolation)
            EditorIntent.ClearKeyframes -> clearKeyframes()
            is EditorIntent.ToggleParamKey -> toggleParamKey(intent.paramId)
            is EditorIntent.JumpToParamKey -> jumpToParamKey(intent.paramId, intent.forward)
            is EditorIntent.ClearParamTrack -> withSelection { execute(EditCommand.ClearParamTrack(it, intent.paramId)) }
            is EditorIntent.CopyParamKeys -> copyParamKeys(intent.paramId)
            is EditorIntent.PasteParamKeys -> pasteParamKeys(intent.paramId)
            is EditorIntent.SetParamKeyShape ->
                withSelection { execute(EditCommand.SetParamKeyShape(it, intent.paramId, intent.frame, intent.interpolation, intent.out, intent.inn)) }
            is EditorIntent.UpdateParamKey -> updateParamKey(intent.paramId, intent.fromFrame, intent.toFrame, intent.value)
            is EditorIntent.EndParamKeyEdit -> endParamKeyEdit(intent.commit)
            is EditorIntent.SelectParamKey -> reduce { copy(selectedParamKey = intent.paramId?.let { it to (intent.frame ?: 0L) }) }
            is EditorIntent.SetSpeed -> setSpeed(intent.num, intent.den)
            EditorIntent.ToggleReverse -> toggleReverse()
            is EditorIntent.SetSpeedRamp -> setSpeedRamp(intent.shape)
            is EditorIntent.SetSpeedKeys -> withSelection { clipId -> execute(EditCommand.SetSpeedRamp(clipId, intent.keys)) }
            EditorIntent.ToggleSmoothSlowMo -> toggleSmoothSlowMo()
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
            is SelectionIntent -> selectionIntent(intent)
            is LaneDragIntent -> laneDragIntent(intent)
            is QualifierIntent -> qualifierIntent(intent)
            is LibraryIntent -> libraryIntent(intent)
            is QuickEditIntent -> quickEditIntent(intent)
            is EditorIntent.ReportError -> emit(EditorEffect.ShowMessage(intent.message))
        }
    }

    /** True if a drag that starts on [hit] should edit the clip instead of scrolling the timeline. */
    fun canDrag(hit: TimelineHit): Boolean {
        // The playhead (or anywhere on the ruler) scrubs; clips only drag once selected.
        if (hit.kind == HitKind.PLAYHEAD || hit.kind == HitKind.RULER) return true
        if (state.value.selectedClipId == null) return false
        val onClip = hit.kind == HitKind.CLIP || hit.kind == HitKind.CLIP_LEFT_EDGE || hit.kind == HitKind.CLIP_RIGHT_EDGE
        return onClip && clipKeys.idFor(hit.clipKey) in state.value.selection
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
        val selection = state.selection
        val tracks = timeline.tracks.map {
            when (it.type) {
                TrackType.VIDEO -> SnapshotTrackType.VIDEO
                TrackType.AUDIO -> SnapshotTrackType.AUDIO
                TrackType.TITLE -> SnapshotTrackType.TITLE
            }
        }
        // Mute and solo marks for the lane headers of audio lanes.
        val trackFlags = timeline.tracks.map {
            if (it.type == TrackType.AUDIO) {
                (if (it.audio.mute) TimelineSnapshot.TRACK_MUTED else 0) or (if (it.audio.solo) TimelineSnapshot.TRACK_SOLO else 0)
            } else {
                0
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
                    selected = clip.id in selection,
                    primary = clip.id == state.selectedClipId,
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
            // One diamond per frame that carries a key of any kind: the pose and every animated parameter.
            track.clips.flatMap { clip ->
                val frames = (clip.keyframes.map { it.frame } + clip.params.flatMap { t -> t.keys.map { it.frame } }).distinct().sorted()
                frames.map { SnapshotKeyframe(clipKeys.keyFor(clip.id), it) }
            }
        }
        val retimes = timeline.tracks.flatMap { track ->
            track.clips.filter { it.isRetimed || it.isFreeze }.map {
                SnapshotRetime(clipKeys.keyFor(it.id), it.sourceSpan, reverse = it.reverse, freeze = it.isFreeze)
            }
        }
        val markers = timeline.markers.map {
            SnapshotMarker(
                it.frame.value,
                beat = it.kind == MarkerKind.BEAT,
                colorCode = it.color?.let { color -> color.ordinal + 1 } ?: 0,
                hasNote = !it.note.isNullOrBlank(),
            )
        }
        val labels = timeline.tracks.flatMap { track ->
            track.clips.mapNotNull { clip -> ClipLabels.of(clip)?.let { SnapshotLabel(clipKeys.keyFor(clip.id), it) } }
        }
        return TimelineSnapshot(state.fps.num, state.fps.den, tracks, clips, transitions, keyframes, retimes, markers, labels, trackFlags)
    }

    // region loading and saving

    private fun load() {
        stabiliser.releaseAll()  // tables of an earlier project must never be read by this one
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
                refreshStabilise()  // a reopened project's stabilised clips need their tables again
                refreshTrack()
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
                if (state.value.selectMode && clipId != null) {
                    toggleInSelection(clipId)
                } else {
                    // A plain tap goes back to a single selection, even on a clip that was in the group.
                    reduce {
                        copy(
                            selectedClipId = clipId,
                            selectedClipIds = emptySet(),
                            selectedTrackId = clipId?.let { timeline.trackOfClip(it)?.id } ?: selectedTrackId,
                        )
                    }
                }
            }
            // In select mode a tap on empty space keeps the selection (the Clear button drops it).
            HitKind.EMPTY_TRACK -> if (state.value.selectMode) {
                reduce { copy(selectedTrackId = timeline.tracks.getOrNull(hit.trackIndex)?.id ?: selectedTrackId) }
            } else {
                reduce {
                    copy(
                        selectedClipId = null,
                        selectedClipIds = emptySet(),
                        selectedTrackId = timeline.tracks.getOrNull(hit.trackIndex)?.id ?: selectedTrackId,
                    )
                }
            }
            // A tap on a lane header selects the lane (the up/down and remove buttons then act on it); a long press drags it.
            HitKind.LANE_HEADER -> reduce { copy(selectedTrackId = timeline.tracks.getOrNull(hit.trackIndex)?.id ?: selectedTrackId) }
            // Above the lanes (room left by the bottom-anchored stack) a tap is a tap on nothing; OUTSIDE only occurs mid-drag.
            HitKind.NONE, HitKind.ABOVE_LANES, HitKind.OUTSIDE ->
                if (!state.value.selectMode) reduce { copy(selectedClipId = null, selectedClipIds = emptySet()) }
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
        audioEdit = null
        audioEditKey = null
        val committed = history.timeline
        val canUndo = history.canUndo
        val canRedo = history.canRedo
        reduce {
            copy(
                timeline = committed,
                dragPreview = null,
                audioSessionActive = false,
                noiseRegion = noiseRegion?.takeIf { committed.trackOfClip(it.clipId) != null },
                dropHint = null,
                canUndo = canUndo,
                canRedo = canRedo,
                selectedClipId = selectedClipId?.takeIf { committed.trackOfClip(it) != null },
                selectedClipIds = selectedClipIds.filterTo(LinkedHashSet()) { committed.trackOfClip(it) != null },
                // If the selected track vanished (undo, removal), fall back to the first video track.
                selectedTrackId = selectedTrackId?.takeIf { committed.track(it) != null }
                    ?: committed.tracks.firstOrNull { it.type == TrackType.VIDEO }?.id,
            )
        }
        refreshStabilise()
        refreshTrack()
    }

    // region stabiliser

    private var stabJob: Job? = null

    /**
     * Makes the correction tables of every stabilised clip available to the preview and the exporter, and refreshes the
     * selected clip's status for the inspector. Runs off the main thread (it reads cache files).
     */
    private fun refreshStabilise() {
        val timeline = history.timeline
        val assets = state.value.assets
        val fps = state.value.fps
        val selected = state.value.selectedClipId?.let { timeline.trackOfClip(it)?.clip(it) }
        val stabilised = timeline.tracks.flatMap { it.clips }.filter { it.stabilise != null }
        if (stabilised.isEmpty() && selected?.stabilise == null) {
            if (state.value.stab.status != StabStatus.Off || state.value.stab.clipId != null) {
                reduce { copy(stab = StabUiState(progress = stab.progress)) }
            }
            return
        }
        viewModelScope.launch(stabDispatcher) {
            for (clip in stabilised) assets.firstOrNull { it.id == clip.assetId }?.let { stabiliser.register(it, clip, fps) }
            val asset = selected?.let { clip -> assets.firstOrNull { it.id == clip.assetId } }
            val status = if (selected != null && asset != null) stabiliser.statusOf(asset, selected, fps) else StabStatus.Off
            reduce { copy(stab = StabUiState(clipId = selected?.id, status = status, progress = stab.progress)) }
        }
    }

    private fun analyseStabilise() {
        if (stabJob?.isActive == true) return
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        if (clip == null || clip.stabilise == null || asset == null) {
            emit(EditorEffect.ShowMessage("Turn the stabiliser on for a video clip first"))
            return
        }
        val fps = state.value.fps
        reduce { copy(stab = StabUiState(clipId = clip.id, status = stab.status.takeIf { it != StabStatus.Off } ?: StabStatus.NotAnalysed, progress = 0f)) }
        stabJob = viewModelScope.launch {
            val outcome = stabiliser.analyse(asset, clip, fps) { progress -> reduce { copy(stab = stab.copy(progress = progress)) } }
            if (outcome is StabOutcome.Failed) emit(EditorEffect.ShowMessage(outcome.message))
            reduce { copy(stab = stab.copy(progress = null)) }
            refreshStabilise()
        }
    }

    // endregion

    // endregion

    // region motion tracking

    private var trackJob: Job? = null

    private fun clipWithAsset(clipId: String?): Pair<Clip, MediaAssetDto>? {
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) } ?: return null
        val asset = state.value.assets.firstOrNull { it.id == clip.assetId } ?: return null
        return clip to asset
    }

    /** The selected clip when it is a video clip that plays a video file (the only kind that can be tracked). */
    private fun trackableSelection(): Pair<Clip, MediaAssetDto>? {
        val id = state.value.selectedClipId ?: return null
        if (history.timeline.trackOfClip(id)?.type != TrackType.VIDEO) return null
        val found = clipWithAsset(id) ?: return null
        return found.takeIf { it.first.hasMedia && it.first.still == null && it.second.hasVideo && !it.second.isImage }
    }

    /**
     * Re-reads the selected clip's tracks and their analysis status, the tracks of other clips it could follow, and the
     * path of the track shown on the preview. Runs off the main thread (it reads cache files).
     */
    private fun refreshTrack() {
        val timeline = history.timeline
        val s = state.value
        val selectedId = s.selectedClipId?.takeIf { timeline.trackOfClip(it) != null }
        val trackable = trackableSelection()
        val selectedType = selectedId?.let { timeline.trackOfClip(it)?.type }
        val canFollow = selectedId != null && selectedType != null && selectedType != TrackType.AUDIO
        if (timeline.motionTracks.isEmpty() && s.track.items.isEmpty() && s.track.followable.isEmpty() && s.track.overlay.isEmpty() && s.track.activeId == null) {
            if (s.track.clipId != selectedId || s.track.canTrack != (trackable != null) || (s.track.picking && trackable == null)) {
                reduce { copy(track = track.copy(clipId = selectedId, canTrack = trackable != null, picking = track.picking && trackable != null)) }
            }
            return
        }
        val fps = s.fps
        val assets = s.assets
        val canvasW = s.canvasWidth
        val canvasH = s.canvasHeight
        val activeWanted = s.track.activeId?.takeIf { timeline.motionTrack(it) != null }
        viewModelScope.launch(trackDispatcher) {
            val items = if (trackable != null) {
                timeline.motionTracks.filter { it.clipId == trackable.first.id }.map { TrackItem(it, motionTracker.statusOf(trackable.second, trackable.first, it, fps)) }
            } else {
                emptyList()
            }
            val follow = if (canFollow) {
                timeline.motionTracks.filter { it.clipId != selectedId }.mapNotNull { t ->
                    val clip = timeline.trackOfClip(t.clipId)?.clip(t.clipId) ?: return@mapNotNull null
                    val asset = assets.firstOrNull { it.id == clip.assetId } ?: return@mapNotNull null
                    FollowItem(t, motionTracker.statusOf(asset, clip, t, fps) is TrackStatus.Ready)
                }
            } else {
                emptyList()
            }
            val overlay = activeWanted?.let { id ->
                val t = timeline.motionTrack(id) ?: return@let emptyList()
                val clip = timeline.trackOfClip(t.clipId)?.clip(t.clipId) ?: return@let emptyList()
                val asset = assets.firstOrNull { it.id == clip.assetId } ?: return@let emptyList()
                motionTracker.load(asset, t, fps)?.let { TrackMath.canvasPath(it, clip, canvasW, canvasH) } ?: emptyList()
            } ?: emptyList()
            reduce {
                copy(
                    track = track.copy(
                        clipId = selectedId, canTrack = trackable != null, items = items, followable = follow,
                        activeId = activeWanted, overlay = overlay, picking = track.picking && trackable != null,
                    ),
                )
            }
        }
    }

    private fun beginTrackPick() {
        val found = trackableSelection()
        if (found == null) {
            emit(EditorEffect.ShowMessage("Select a video clip to track"))
            return
        }
        val clip = found.first
        val playhead = state.value.playhead
        if (playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            emit(EditorEffect.ShowMessage("Move the playhead onto the clip first"))
            return
        }
        pausePlayback()
        reduce { copy(track = track.copy(picking = true)) }
    }

    /**
     * The pick on the preview ([x], [y] in canvas pixels from the centre, a box when [w] and [h] are given) becomes a
     * seed in the clip's own picture at the playhead's source frame, is remembered as a motion track (one undo step)
     * and analysed in the background.
     */
    private fun pickTrackTarget(x: Double, y: Double, w: Double?, h: Double?) {
        val found = trackableSelection()
        if (found == null) {
            emit(EditorEffect.ShowMessage("Select a video clip to track"))
            return
        }
        val (clip, asset) = found
        val s = state.value
        val playhead = s.playhead
        if (playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            emit(EditorEffect.ShowMessage("Move the playhead onto the clip first"))
            return
        }
        viewModelScope.launch {
            val aspect = withContext(trackDispatcher) { motionTracker.frameAspect(asset) } ?: (s.canvasWidth.toDouble() / s.canvasHeight)
            val pose = Keyframes.evaluate(clip.keyframes, playhead.value - clip.timelineStart.value, clip.transform)
            val (u, v) = TrackMath.fromCanvas(x, y, aspect, s.canvasWidth, s.canvasHeight, pose)
            if (u !in 0.0..1.0 || v !in 0.0..1.0) {
                emit(EditorEffect.ShowMessage("Tap on the picture of the clip"))
                return@launch
            }
            val (fitW, fitH) = TrackMath.fitSize(aspect, s.canvasWidth, s.canvasHeight)
            val boxW = if (w != null) w / (fitW * pose.scaleX) else s.track.boxSide / aspect
            val boxH = if (h != null) h / (fitH * pose.scaleY) else s.track.boxSide
            val seed = TrackSeed(
                sourceFrame = clip.sourceFrameAtProjectFrame(playhead),
                cx = u.coerceIn(0.0, 1.0),
                cy = v.coerceIn(0.0, 1.0),
                w = boxW.coerceIn(TrackSeed.MIN_SIZE, TrackSeed.MAX_SIZE),
                h = boxH.coerceIn(TrackSeed.MIN_SIZE, TrackSeed.MAX_SIZE),
            )
            val existing = history.timeline.motionTracks.count { it.clipId == clip.id }
            val motion = MotionTrack("mt-${idGenerator()}", clip.id, "Track ${existing + 1}", seed)
            if (!execute(EditCommand.AddMotionTrack(motion))) return@launch
            reduce { copy(track = track.copy(picking = false, activeId = motion.id)) }
            startTrackAnalysis(motion.id)
        }
    }

    private fun startTrackAnalysis(trackId: String) {
        if (trackJob?.isActive == true) {
            emit(EditorEffect.ShowMessage("Another tracking analysis is running"))
            return
        }
        val motion = history.timeline.motionTrack(trackId) ?: return
        val (clip, asset) = clipWithAsset(motion.clipId) ?: return
        val fps = state.value.fps
        reduce { copy(track = track.copy(progress = 0f, analysingId = trackId)) }
        trackJob = viewModelScope.launch {
            val outcome = motionTracker.analyse(asset, clip, motion, fps) { progress -> reduce { copy(track = track.copy(progress = progress)) } }
            when (outcome) {
                is TrackOutcome.Failed -> emit(EditorEffect.ShowMessage(outcome.message))
                TrackOutcome.Cancelled -> emit(EditorEffect.ShowMessage("Tracking cancelled"))
                TrackOutcome.Done -> Unit
            }
            reduce { copy(track = track.copy(progress = null, analysingId = null, activeId = if (outcome == TrackOutcome.Done) trackId else track.activeId)) }
            refreshTrack()
        }
    }

    private fun removeMotionTrack(trackId: String) {
        val motion = history.timeline.motionTrack(trackId) ?: return
        val owner = clipWithAsset(motion.clipId)
        if (!execute(EditCommand.RemoveMotionTrack(trackId))) return
        if (owner != null) viewModelScope.launch(trackDispatcher) { motionTracker.forget(owner.second, motion) }
        if (state.value.track.activeId == trackId) reduce { copy(track = track.copy(activeId = null, overlay = emptyList())) }
        refreshTrack()
    }

    /** Position keyframes along the tracked path for the selected clip, replacing its keyframes in one undo step. */
    private fun followTrack(trackId: String) {
        val selectedId = state.value.selectedClipId
        val attached = selectedId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        val motion = history.timeline.motionTrack(trackId)
        if (attached == null || motion == null) {
            emit(EditorEffect.ShowMessage("Select the clip that should follow the track"))
            return
        }
        if (attached.id == motion.clipId) {
            emit(EditorEffect.ShowMessage("A clip cannot follow its own track: select an overlay"))
            return
        }
        val (trackedClip, asset) = clipWithAsset(motion.clipId) ?: return
        val s = state.value
        viewModelScope.launch {
            val path = withContext(trackDispatcher) { motionTracker.load(asset, motion, s.fps) }
            if (path == null) {
                emit(EditorEffect.ShowMessage("Analyse ${motion.name} first"))
                return@launch
            }
            val keys = TrackMath.attachKeyframes(path, trackedClip, attached, s.canvasWidth, s.canvasHeight)
            if (execute(EditCommand.AttachToMotionTrack(attached.id, keys))) {
                val lost = if (path.lostCount > 0) " (lost in ${path.lostCount} frames, it holds the last position there)" else ""
                emit(EditorEffect.ShowMessage("Now follows ${motion.name}: ${keys.size} keyframes$lost"))
            }
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

    // region qualifier eyedropper

    private fun qualifierIntent(intent: QualifierIntent) {
        when (intent) {
            is QualifierIntent.Arm -> armQualifierPick(intent.effectId)
            QualifierIntent.Cancel -> reduce { copy(qualifierPick = QualifierPickState()) }
            is QualifierIntent.Pick -> pickQualifierColor(intent.x, intent.y)
        }
    }

    /** The selected clip when its picture can be read for a colour: a video file or a photo, not a title or a sticker. */
    private fun samplableSelection(): Pair<Clip, MediaAssetDto>? {
        val found = clipWithAsset(state.value.selectedClipId) ?: return null
        val (clip, asset) = found
        val isPhoto = clip.still == StillKind.PHOTO
        return found.takeIf { clip.title == null && ((clip.hasMedia && asset.hasVideo) || isPhoto) }
    }

    private fun armQualifierPick(effectId: String) {
        val found = samplableSelection()
        if (found == null) {
            emit(EditorEffect.ShowMessage("Select a video or photo clip to pick a colour from"))
            return
        }
        val (clip, _) = found
        if (clip.fx.effect(effectId)?.type != EffectType.QUALIFIER) return
        val playhead = state.value.playhead
        if (playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            emit(EditorEffect.ShowMessage("Move the playhead onto the clip first"))
            return
        }
        pausePlayback()
        reduce { copy(qualifierPick = QualifierPickState(effectId)) }
    }

    private fun pickQualifierColor(x: Double, y: Double) {
        val pick = state.value.qualifierPick
        val effectId = pick.effectId ?: return
        if (pick.busy) return
        val found = samplableSelection()
        if (found == null) {
            reduce { copy(qualifierPick = QualifierPickState()) }
            emit(EditorEffect.ShowMessage("Select a video or photo clip to pick a colour from"))
            return
        }
        val (clip, asset) = found
        val playhead = state.value.playhead
        if (playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            reduce { copy(qualifierPick = QualifierPickState()) }
            emit(EditorEffect.ShowMessage("Move the playhead onto the clip first"))
            return
        }
        reduce { copy(qualifierPick = qualifierPick.copy(busy = true)) }
        viewModelScope.launch {
            val outcome = withContext(sampleDispatcher) { sampleAndKey(clip, asset, effectId, x, y, playhead) }
            // The eyedropper ends after a pick, with a message when it found nothing, except for a tap beside the
            // picture, which keeps waiting for a better one.
            reduce { copy(qualifierPick = if (outcome.keepArmed) QualifierPickState(effectId) else QualifierPickState()) }
            outcome.message?.let { emit(EditorEffect.ShowMessage(it)) }
        }
    }

    /** What an eyedropper tap came to: [message] is what to tell the user (null when the key was set). */
    private class PickOutcome(val message: String?, val keepArmed: Boolean = false)

    /** Reads the colour under the tap and keys the effect on it. */
    private suspend fun sampleAndKey(clip: Clip, asset: MediaAssetDto, effectId: String, x: Double, y: Double, playhead: FrameIndex): PickOutcome {
        val s = state.value
        val aspect = frameSampler.aspect(asset) ?: return PickOutcome("The picture of this clip could not be read")
        val pose = Keyframes.evaluate(clip.keyframes, playhead.value - clip.timelineStart.value, clip.transform)
        val (u, v) = TrackMath.fromCanvas(x, y, aspect, s.canvasWidth, s.canvasHeight, pose)
        if (u !in 0.0..1.0 || v !in 0.0..1.0) return PickOutcome("Tap on the picture of the clip", keepArmed = true)
        val micros = if (asset.isImage) 0L else s.fps.framesToMicros(clip.sourceFrameAtProjectFrame(playhead))
        val colour = frameSampler.colorAt(asset, micros, u, v) ?: return PickOutcome("The colour could not be read from the picture")
        // The clip may have been edited while the frame was read.
        val current = history.timeline.trackOfClip(clip.id)?.clip(clip.id)?.fx?.effect(effectId)
        if (current == null || current.type != EffectType.QUALIFIER) return PickOutcome("That effect is no longer on the clip")
        val keyed = Qualifier.keyedOn(current.values, colour.r, colour.g, colour.b)
        return PickOutcome(if (execute(EditCommand.SetEffectValues(clip.id, effectId, keyed))) null else "The key could not be set")
    }

    // endregion

    /** Lane header drag: pick a lane up, choose where it lands among the lanes of its kind, apply on release. */
    private fun laneDragIntent(intent: LaneDragIntent) {
        val timeline = history.timeline
        when (intent) {
            is LaneDragIntent.Start -> {
                val index = intent.hit.trackIndex
                val track = timeline.tracks.getOrNull(index) ?: return
                if (intent.hit.kind != HitKind.LANE_HEADER) return
                reduce { copy(selectedTrackId = track.id) }
                if (LaneOps.laneDropTarget(timeline, track.id, index) == null) {
                    val isBase = ClipDeletion.baseTrack(timeline)?.id == track.id
                    emit(
                        EditorEffect.ShowMessage(
                            if (isBase) "The base track stays at the bottom of the video lanes" else "This is the only lane of its kind",
                        ),
                    )
                    return
                }
                reduce { copy(laneDrag = LaneDrag(track.id, index, index)) }
            }
            is LaneDragIntent.Move -> {
                val drag = state.value.laneDrag ?: return
                val hover = when (intent.hit.kind) {
                    // Above the lanes or in the ruler: the top of the stack.
                    HitKind.ABOVE_LANES, HitKind.RULER, HitKind.PLAYHEAD -> 0
                    // Outside the panel or in the gap between lanes: keep the last target.
                    HitKind.OUTSIDE, HitKind.NONE -> return
                    else -> intent.hit.trackIndex
                }
                if (hover < 0) return
                val target = LaneOps.laneDropTarget(timeline, drag.trackId, hover) ?: return
                if (target != drag.toIndex) reduce { copy(laneDrag = drag.copy(toIndex = target)) }
            }
            is LaneDragIntent.End -> {
                val drag = state.value.laneDrag ?: return
                reduce { copy(laneDrag = null) }
                if (intent.commit && drag.toIndex != drag.fromIndex) execute(EditCommand.MoveTrackTo(drag.trackId, drag.toIndex))
            }
        }
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
        val group = state.value.selection.takeIf { mode == DragMode.MOVE && it.size > 1 && clipId in it }?.toList()
        drag = DragSession(clipId, mode, hit.frame - clip.timelineStart.value, group)
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
                if (session.group != null) {
                    groupDrag(session, base, frame, trackIndex, zone, playhead)
                    return
                }
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

    // region multi-selection and group edits

    /** Adds [clipId] to the selection, or removes it when it is already in; the clip toggled on becomes the primary one. */
    private fun toggleInSelection(clipId: String) {
        val chosen = LinkedHashSet(state.value.selection)
        if (!chosen.remove(clipId)) chosen += clipId
        setSelection(chosen, primary = clipId)
    }

    /** Makes [ids] the selection; [primary] (when among them, else the last one) is the clip the inspector edits. */
    private fun setSelection(ids: Set<String>, primary: String? = null) {
        val picked = primary?.takeIf { it in ids } ?: ids.lastOrNull()
        reduce {
            copy(
                selectedClipIds = if (ids.size > 1) LinkedHashSet(ids) else emptySet(),
                selectedClipId = picked,
                selectedTrackId = picked?.let { history.timeline.trackOfClip(it)?.id } ?: selectedTrackId,
            )
        }
    }

    private fun selectionIntent(intent: SelectionIntent) {
        val timeline = history.timeline
        when (intent) {
            SelectionIntent.ToggleSelectMode -> reduce { copy(selectMode = !selectMode) }
            is SelectionIntent.LongPress -> {
                val onClip = intent.hit.kind == HitKind.CLIP || intent.hit.kind == HitKind.CLIP_LEFT_EDGE || intent.hit.kind == HitKind.CLIP_RIGHT_EDGE
                clipKeys.idFor(intent.hit.clipKey)?.takeIf { onClip }?.let(::toggleInSelection)
            }
            is SelectionIntent.Marquee -> {
                val ids = intent.clipKeys.mapNotNull { clipKeys.idFor(it) }.filter { timeline.trackOfClip(it) != null }
                if (ids.isNotEmpty()) setSelection(LinkedHashSet(state.value.selection + ids), primary = ids.last())
            }
            SelectionIntent.SelectLane -> {
                val lane = state.value.selectedTrackId
                val ids = lane?.let { ClipSelection.allInLane(timeline, it) }.orEmpty()
                if (ids.isEmpty()) emit(EditorEffect.ShowMessage("Tap a lane that has clips first")) else setSelection(ids, state.value.selectedClipId)
            }
            SelectionIntent.SelectFromPlayhead -> {
                val ids = ClipSelection.fromPlayhead(timeline, state.value.playhead)
                if (ids.isEmpty()) emit(EditorEffect.ShowMessage("No clips after the playhead")) else setSelection(ids)
            }
            SelectionIntent.SelectAll -> {
                val ids = timeline.tracks.flatMapTo(LinkedHashSet()) { track -> track.clips.map { it.id } }
                if (ids.isEmpty()) emit(EditorEffect.ShowMessage("There are no clips to select")) else setSelection(ids, state.value.selectedClipId)
            }
            SelectionIntent.ClearSelection -> reduce { copy(selectedClipId = null, selectedClipIds = emptySet()) }
            SelectionIntent.Copy -> copySelection(announce = true)
            SelectionIntent.Cut -> if (copySelection(announce = false)) deleteSelection()
            SelectionIntent.Paste -> pasteClipboard()
            SelectionIntent.Duplicate -> withGroup { runGroupCommand(GroupDuplicate(it), selectNew = true) }
            SelectionIntent.DeleteSelection -> deleteSelection()
            SelectionIntent.PasteAttributes -> {
                val source = clipboard?.primary
                if (source == null) emit(EditorEffect.ShowMessage("Copy a clip first, then paste its attributes"))
                else withGroup { execute(GroupPasteAttributes(ClipAttributes.of(source), it)) }
            }
            is SelectionIntent.SetGroupSpeed -> withGroup { execute(GroupSetSpeed(it, intent.num, intent.den)) }
            is SelectionIntent.SetGroupGain -> withGroup { execute(GroupSetGain(it, intent.gainDb)) }
            is SelectionIntent.SetGroupOpacity -> withGroup { execute(GroupSetOpacity(it, intent.opacity)) }
            is SelectionIntent.Align -> withGroup { execute(GroupAlign(it, intent.edge)) }
            is SelectionIntent.ApplyTransitions -> withGroup { ids ->
                val frames = state.value.fps.microsToFrames(TRANSITION_DEFAULT_MICROS).coerceAtLeast(Transition.MIN_DURATION_FRAMES)
                val lengths = ids.mapNotNull { id ->
                    val clip = timeline.trackOfClip(id)?.clip(id) ?: return@mapNotNull null
                    assetLengthFrames(clip.assetId)?.let { id to it }
                }.toMap()
                execute(GroupTransitions(ids, frames, intent.mode, lengths))
            }
        }
    }

    /** Runs [block] with the selected clip ids, or says that nothing is selected. */
    private inline fun withGroup(block: (List<String>) -> Unit) {
        val ids = state.value.selection.toList()
        if (ids.isEmpty()) emit(EditorEffect.ShowMessage("Select a clip first")) else block(ids)
    }

    private fun copySelection(announce: Boolean): Boolean {
        val board = Clipboard.capture(history.timeline, state.value.selection)
        if (board == null) {
            emit(EditorEffect.ShowMessage("Select clips to copy first"))
            return false
        }
        clipboard = board
        val count = board.entries.size
        reduce { copy(clipboardCount = count) }
        if (announce) emit(EditorEffect.ShowMessage(if (count == 1) "Copied 1 clip" else "Copied $count clips"))
        return true
    }

    private fun pasteClipboard() {
        val board = clipboard
        if (board == null) {
            emit(EditorEffect.ShowMessage("Nothing to paste: copy some clips first"))
            return
        }
        runGroupCommand(GroupPaste(board, state.value.playhead), selectNew = true)
    }

    private fun deleteSelection() = withGroup { execute(GroupDelete(it)) }

    /** Runs a group [command] as one undo step; with [selectNew] the clips it created become the selection. */
    private fun runGroupCommand(command: EditCommand, selectNew: Boolean = false): Boolean {
        val before = history.timeline.tracks.flatMapTo(HashSet()) { track -> track.clips.map { it.id } }
        if (!execute(command)) return false
        if (selectNew) {
            val created = history.timeline.tracks.flatMap { track -> track.clips.map { it.id } }.filter { it !in before }
            if (created.isNotEmpty()) setSelection(LinkedHashSet(created), primary = created.first())
        }
        return true
    }

    /**
     * One step of dragging a selected clip with several selected: the whole group moves by the same
     * frames (snapped as a block) and, when the finger is over another lane of the same kind, by the same
     * number of lanes. A position that would put a clip on another one keeps the last valid preview.
     */
    private fun groupDrag(session: DragSession, base: Timeline, frame: Long, trackIndex: Int, zone: DragZone, playhead: FrameIndex) {
        val ids = session.group ?: return
        if (zone == DragZone.OUTSIDE) {
            pendingDragCommand = null
            reduce { copy(dragPreview = null, dropHint = DropHint(DropKind.CANCEL, null, 0, 0)) }
            return
        }
        val anchorTrack = base.trackOfClip(session.clipId) ?: return
        val anchor = anchorTrack.clip(session.clipId) ?: return
        val earliest = ids.mapNotNull { id -> base.trackOfClip(id)?.clip(id)?.timelineStart?.value }.minOrNull() ?: return
        val requested = ((frame - session.grabOffset) - anchor.timelineStart.value).coerceAtLeast(-earliest)
        val delta = GroupOps.snappedDelta(base, ids, requested, snapWith(base, playhead)).coerceAtLeast(-earliest)
        val command = GroupMove(ids, delta, laneDeltaFor(base, anchorTrack, trackIndex, zone))
        val result = command.apply(base) as? EditResult.Success ?: return
        pendingDragCommand = if (result.value == base) null else command
        reduce { copy(dragPreview = result.value, dropHint = null) }
    }

    /** Lanes of the same kind between the lane of the grabbed clip and the one under the finger (0 on the base or another kind). */
    private fun laneDeltaFor(base: Timeline, source: Track, trackIndex: Int, zone: DragZone): Int {
        if (zone != DragZone.LANES) return 0
        val baseTrack = ClipDeletion.baseTrack(base)
        if (baseTrack != null && source.id == baseTrack.id) return 0
        val under = base.tracks.getOrNull(trackIndex) ?: return 0
        if (under.type != source.type || under.id == baseTrack?.id) return 0
        val lanes = base.tracks.filter { it.type == source.type && it.id != baseTrack?.id }
        return lanes.indexOfFirst { it.id == under.id } - lanes.indexOfFirst { it.id == source.id }
    }

    // endregion

    // region audio tools

    /** The audio edit in progress (a slider drag), shown and heard live and committed as one undo step. */
    private var audioEdit: EditCommand? = null
    private var audioEditKey: String? = null

    private fun updateAudioEdit(key: String, command: EditCommand) {
        if (drag != null) return
        // A slider of another clip, track or the ducking: the previous one is final first.
        if (audioEdit != null && audioEditKey != key) endAudioEdit(commit = true)
        when (val result = command.apply(history.timeline)) {
            is EditResult.Success -> {
                audioEdit = command
                audioEditKey = key
                reduce { copy(dragPreview = result.value, audioSessionActive = true) }
            }
            is EditResult.Failure -> emit(EditorEffect.ShowMessage(describe(result.error)))
        }
    }

    private fun endAudioEdit(commit: Boolean) {
        val command = audioEdit ?: return
        audioEdit = null
        audioEditKey = null
        val result = command.apply(history.timeline)
        val changed = result is EditResult.Success && result.value != history.timeline
        if (commit && changed && execute(command)) return
        reduce { copy(dragPreview = null, audioSessionActive = false) }
    }

    private fun resetClipAudio() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.audio.isNeutral) {
            emit(EditorEffect.ShowMessage("This clip has no audio changes to reset"))
            return@withSelection
        }
        execute(EditCommand.SetClipAudio(clipId, ClipAudio.NONE))
    }

    /** The selected clip and its library file when both can be analysed for sound, else a message. */
    private fun analysableSelection(what: String): Pair<Clip, MediaAssetDto>? {
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        if (clip == null || !clip.hasMedia || asset == null || !asset.hasAudio || clip.isFreeze) {
            emit(EditorEffect.ShowMessage("Select a clip with audio to $what"))
            return null
        }
        if (audioAnalyzer == null) {
            emit(EditorEffect.ShowMessage("Audio measurements are not available right now"))
            return null
        }
        if (state.value.audioBusy != null) return null
        return clip to asset
    }

    private fun normalizeLoudness(targetLufs: Double) {
        if (!targetLufs.isFinite() || targetLufs !in MIN_TARGET_LUFS..MAX_TARGET_LUFS) {
            emit(EditorEffect.ShowMessage("The loudness target must be between $MIN_TARGET_LUFS and $MAX_TARGET_LUFS LUFS"))
            return
        }
        val (clip, asset) = analysableSelection("normalise its loudness") ?: return
        val analyzer = checkNotNull(audioAnalyzer)
        val fps = state.value.fps
        val key = LoudnessCache.keyOf(asset, clip.sourceIn.value, clip.sourceOut.value)
        reduce { copy(audioBusy = "Measuring loudness…") }
        viewModelScope.launch {
            try {
                val cached = loudnessCache.get(key)
                val lufs = cached ?: analyzer.loudness(
                    asset, assetKeys.keyFor(asset.id),
                    fps.framesToMicros(clip.sourceIn.value), fps.framesToMicros(clip.sourceOut.value),
                ).lufs?.also { loudnessCache.put(key, it) }
                if (lufs == null) {
                    emit(EditorEffect.ShowMessage("This clip is silent, so there is nothing to normalise"))
                    return@launch
                }
                // The clip may have been edited while it was measured: act on what is there now.
                val now = history.timeline.trackOfClip(clip.id)?.clip(clip.id) ?: return@launch
                if (now.sourceIn != clip.sourceIn || now.sourceOut != clip.sourceOut) {
                    emit(EditorEffect.ShowMessage("The clip changed while it was measured; try again"))
                    return@launch
                }
                val gain = (targetLufs - lufs).coerceIn(-ClipAudio.MAX_NORMALIZE_DB, ClipAudio.MAX_NORMALIZE_DB)
                if (execute(EditCommand.SetClipAudio(now.id, now.audio.copy(normalizeDb = gain, targetLufs = targetLufs)))) {
                    // The app's text is English, so numbers use a point whatever the phone's region is.
                    val measured = "%.1f".format(Locale.US, lufs)
                    emit(EditorEffect.ShowMessage("Measured $measured LUFS; ${"%+.1f".format(Locale.US, gain)} dB to reach ${"%.0f".format(Locale.US, targetLufs)}"))
                }
            } catch (e: AudioAnalysisException) {
                emit(EditorEffect.ShowMessage(e.message ?: "The loudness could not be measured"))
            } finally {
                reduce { copy(audioBusy = null) }
            }
        }
    }

    private fun clearNormalize() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.audio.targetLufs == null && clip.audio.normalizeDb == 0.0) {
            emit(EditorEffect.ShowMessage("This clip is not normalised"))
            return@withSelection
        }
        execute(EditCommand.SetClipAudio(clipId, clip.audio.copy(normalizeDb = 0.0, targetLufs = null)))
    }

    private fun markNoiseRegion(atStart: Boolean) {
        val clipId = state.value.selectedClipId
        val clip = clipId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        if (clip == null || !clip.hasMedia) {
            emit(EditorEffect.ShowMessage("Select a clip with audio first"))
            return
        }
        val offset = state.value.playhead - clip.timelineStart
        if (offset < 0 || offset > clip.durationFrames) {
            emit(EditorEffect.ShowMessage("Move the playhead inside the clip to mark the quiet stretch"))
            return
        }
        val current = state.value.noiseRegion?.takeIf { it.clipId == clip.id } ?: NoiseRegion(clip.id, null, null)
        var next = if (atStart) current.copy(startFrame = offset) else current.copy(endFrame = offset)
        // A start after the end (or the reverse) means the user is picking a new stretch.
        val s = next.startFrame
        val e = next.endFrame
        if (s != null && e != null && s >= e) {
            next = if (atStart) next.copy(endFrame = null) else next.copy(startFrame = null)
        }
        reduce { copy(noiseRegion = next) }
    }

    private fun analyzeNoise(strength: Double) {
        if (!strength.isFinite() || strength <= 0.0 || strength > 1.0) {
            emit(EditorEffect.ShowMessage("Noise suppression strength must be above 0 and at most 1"))
            return
        }
        val (clip, asset) = analysableSelection("remove noise") ?: return
        val region = state.value.noiseRegion?.takeIf { it.clipId == clip.id && it.isComplete }
        if (region == null) {
            emit(EditorEffect.ShowMessage("Mark a quiet stretch first: put the playhead at its start and tap Mark start, then at its end and tap Mark end"))
            return
        }
        val fps = state.value.fps
        val retime = clip.retime
        val a = retime.sourceFrameAt(checkNotNull(region.startFrame))
        val b = retime.sourceFrameAt(checkNotNull(region.endFrame))
        val startMicros = fps.framesToMicros(minOf(a, b))
        val endMicros = fps.framesToMicros(maxOf(a, b))
        if (endMicros - startMicros < MIN_NOISE_SAMPLE_MICROS) {
            emit(EditorEffect.ShowMessage("The quiet stretch must be at least 0.1 s long"))
            return
        }
        val analyzer = checkNotNull(audioAnalyzer)
        reduce { copy(audioBusy = "Listening to the noise…") }
        viewModelScope.launch {
            try {
                val profile = analyzer.noiseProfile(asset, assetKeys.keyFor(asset.id), startMicros, endMicros)
                val now = history.timeline.trackOfClip(clip.id)?.clip(clip.id) ?: return@launch
                if (execute(EditCommand.SetClipAudio(now.id, now.audio.copy(denoise = Denoise(strength, profile.toList()))))) {
                    emit(EditorEffect.ShowMessage("Noise suppression is on"))
                }
            } catch (e: AudioAnalysisException) {
                emit(EditorEffect.ShowMessage(e.message ?: "The noise could not be measured"))
            } finally {
                reduce { copy(audioBusy = null) }
            }
        }
    }

    private fun removeNoiseSuppression() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        if (clip.audio.denoise == null) {
            emit(EditorEffect.ShowMessage("Noise suppression is not on for this clip"))
            return@withSelection
        }
        execute(EditCommand.SetClipAudio(clipId, clip.audio.copy(denoise = null)))
    }

    // endregion

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

    // region quick edits: silence auto cut and manual reframe (SPECS.md 9.16)

    private fun quickEditIntent(intent: QuickEditIntent) {
        when (intent) {
            QuickEditIntent.OpenAutoCut -> openAutoCut()
            QuickEditIntent.CloseAutoCut -> reduce { copy(quickEdits = quickEdits.copy(autoCut = AutoCutUiState())) }
            is QuickEditIntent.SetSilenceSettings -> reduce {
                copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(settings = intent.settings, cuts = emptyList(), excluded = emptySet(), message = null)))
            }
            QuickEditIntent.FindSilences -> findSilences()
            is QuickEditIntent.ToggleCut -> reduce {
                val auto = quickEdits.autoCut
                val excluded = if (intent.index in auto.excluded) auto.excluded - intent.index else auto.excluded + intent.index
                copy(quickEdits = quickEdits.copy(autoCut = auto.copy(excluded = excluded)))
            }
            QuickEditIntent.ApplyAutoCut -> applyAutoCut()
            QuickEditIntent.OpenReframe -> openReframe()
            QuickEditIntent.CloseReframe -> reduce { copy(quickEdits = quickEdits.copy(reframe = ReframeUiState())) }
            is QuickEditIntent.SetReframe -> reduce {
                copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(u = intent.u.coerceIn(0.0, 1.0), v = intent.v.coerceIn(0.0, 1.0), zoom = intent.zoom, message = null)))
            }
            QuickEditIntent.MarkReframePoint -> markReframePoint()
            QuickEditIntent.ClearReframePoints -> reduce { copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(points = emptyList(), message = null))) }
            QuickEditIntent.ApplyReframe -> applyReframe()
        }
    }

    private fun openAutoCut() {
        val base = ClipDeletion.baseTrack(history.timeline)
        val clip = state.value.selectedClipId?.let { id -> base?.clip(id) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        when {
            clip == null -> emit(EditorEffect.ShowMessage("Select a clip on the base track first"))
            !clip.hasMedia || asset == null || !asset.hasAudio -> emit(EditorEffect.ShowMessage("Select a clip with audio to find its silences"))
            !AutoCutPlanner.supports(clip) -> emit(EditorEffect.ShowMessage("Cutting silences does not work on a clip with changed speed or played backwards"))
            else -> reduce { copy(quickEdits = quickEdits.copy(autoCut = AutoCutUiState(open = true, clipId = clip.id))) }
        }
    }

    private fun findSilences() {
        val auto = state.value.quickEdits.autoCut
        val clip = auto.clipId?.let { id -> history.timeline.trackOfClip(id)?.clip(id) }
        val asset = clip?.assetId?.let { id -> state.value.assets.firstOrNull { it.id == id } }
        if (clip == null || asset == null || auto.analyzing) return
        auto.settings.problem()?.let { problem ->
            reduce { copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(message = problem))) }
            return
        }
        val fps = state.value.fps
        val startMicros = fps.framesToMicros(clip.sourceIn.value)
        val endMicros = fps.framesToMicros(clip.sourceOut.value)
        reduce { copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(analyzing = true, message = null))) }
        viewModelScope.launch {
            var cuts: List<com.ultimatevideo.uveditor.domain.CutSpan> = emptyList()
            val message = try {
                when (val found = envelopeSource.envelope(asset.id, startMicros, endMicros)) {
                    EnvelopeResult.NoWaveform -> "The waveform is still being prepared. Try again in a moment."
                    is EnvelopeResult.Found -> {
                        // The clip may have been edited while the analysis ran: map the silences onto where it is now.
                        val now = history.timeline.trackOfClip(clip.id)?.clip(clip.id)
                        if (now == null) {
                            "The clip is no longer on the timeline"
                        } else {
                            val spans = SilenceDetector.detect(found.envelope, auto.settings)
                            cuts = AutoCutPlanner.cuts(now, fps, found.windowStartMicros, spans)
                            if (cuts.isEmpty()) "No silences of that length and level in this clip" else null
                        }
                    }
                }
            } finally {
                reduce { copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(analyzing = false))) }
            }
            reduce { copy(quickEdits = quickEdits.copy(autoCut = quickEdits.autoCut.copy(cuts = cuts, excluded = emptySet(), message = message))) }
        }
    }

    private fun applyAutoCut() {
        val cuts = state.value.quickEdits.autoCut.chosen
        if (cuts.isEmpty()) return
        if (execute(AutoCut(cuts))) {
            val fps = state.value.fps
            val seconds = cuts.sumOf { it.length } * fps.den.toDouble() / fps.num
            reduce { copy(quickEdits = quickEdits.copy(autoCut = AutoCutUiState())) }
            emit(EditorEffect.ShowMessage("Removed ${cuts.size} silences, ${"%.1f".format(seconds)} s shorter"))
        }
    }

    private fun openReframe() {
        val clip = state.value.selectedClipId?.let { id -> history.timeline.trackOfClip(id)?.clip(id) }
        val onVideoTrack = clip?.let { history.timeline.trackOfClip(it.id)?.type == TrackType.VIDEO } == true
        if (clip == null || !onVideoTrack || !clip.hasMedia) {
            emit(EditorEffect.ShowMessage("Select a video or photo clip to reframe"))
            return
        }
        reduce { copy(quickEdits = quickEdits.copy(reframe = ReframeUiState(open = true, clipId = clip.id))) }
    }

    private fun markReframePoint() {
        val frame = state.value.selectedClipFrame
        val reframe = state.value.quickEdits.reframe
        if (frame == null) {
            reduce { copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(message = "Move the playhead onto the clip first"))) }
            return
        }
        val points = (reframe.points.filter { it.frame != frame } + ReframePoint(frame, reframe.u, reframe.v)).sortedBy { it.frame }
        reduce { copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(points = points, message = null))) }
    }

    private fun applyReframe() {
        val s = state.value
        val reframe = s.quickEdits.reframe
        val clip = reframe.clipId?.let { id -> history.timeline.trackOfClip(id)?.clip(id) } ?: return
        val asset = clip.assetId?.let { id -> s.assets.firstOrNull { it.id == id } } ?: return
        val points = reframe.points.ifEmpty { listOf(ReframePoint(0, reframe.u, reframe.v)) }
        viewModelScope.launch {
            val aspect = withContext(trackDispatcher) { motionTracker.frameAspect(asset) }
            if (aspect == null) {
                reduce { copy(quickEdits = quickEdits.copy(reframe = quickEdits.reframe.copy(message = "Could not read the picture's shape"))) }
                return@launch
            }
            if (execute(ReframeClip(clip.id, points, aspect, s.canvasWidth, s.canvasHeight, reframe.zoom))) {
                reduce { copy(quickEdits = quickEdits.copy(reframe = ReframeUiState())) }
                emit(EditorEffect.ShowMessage(if (points.size > 1) "Reframed with ${points.size} keyframes" else "Reframed"))
            }
        }
    }

    // endregion

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
        // The volume slider shows the keyframed volume at the playhead when there is one.
        val gain = clip.displayedAt(state.value.selectedFrame).gainDb
        appearance = AppearanceSession(clip.id, pose, gain, pose, gain, keyFrame)
        return true
    }

    /** The command that sets the volume: a key at the playhead when the volume is keyframed, else the fixed gain. */
    private fun gainCommand(clip: Clip, gainDb: Double): EditCommand =
        if (ParamTracks.track(clip.params, ParamIds.GAIN_DB) != null) {
            EditCommand.SetGainAt(clip.id, gainDb, state.value.selectedFrame)
        } else {
            EditCommand.SetGain(clip.id, gainDb)
        }

    /**
     * The edit an appearance session stands for. A fixed clip gets its transform and gain replaced;
     * an animated one gets a keyframe at the session's frame (only when the pose changed, so a gain
     * edit never adds one) and the new gain.
     */
    private fun sessionCommand(session: AppearanceSession): EditCommand {
        val clip = history.timeline.trackOfClip(session.clipId)?.clip(session.clipId)
            ?: return EditCommand.SetAppearance(session.clipId, session.transform, session.gainDb)
        val frame = session.keyFrame
            ?: return if (ParamTracks.track(clip.params, ParamIds.GAIN_DB) == null) {
                EditCommand.SetAppearance(session.clipId, session.transform, session.gainDb)
            } else {
                // A keyframed volume: the transform is replaced, the volume change becomes a key at the playhead.
                val parts = ArrayList<EditCommand>()
                parts += EditCommand.SetTransform(clip.id, session.transform)
                if (session.gainDb != session.baseGain) parts += gainCommand(clip, session.gainDb)
                EditCommand.Batch(parts)
            }
        val parts = ArrayList<EditCommand>()
        if (session.transform != session.baseTransform) {
            val interpolation = Keyframes.at(clip.keyframes, frame)?.interpolation ?: Interpolation.LINEAR
            parts += EditCommand.SetKeyframe(clip.id, Keyframe(frame, session.transform, interpolation))
        }
        if (session.gainDb != session.baseGain) parts += gainCommand(clip, session.gainDb)
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
        // The controls show the effects as they are at the playhead; keyframed values changed here become keys there.
        val frame = state.value.selectedFrame
        val shown = if (frame != null) clip.fxAt(frame) else clip.fx
        return FxSession(clip.id, shown, shown, frame).also { fxEdit = it }
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
        val result = EditCommand.SetFxAt(session.clipId, session.fx, session.frame).apply(history.timeline)
        if (result is EditResult.Success) reduce { copy(dragPreview = result.value) }
    }

    private fun endFxEdit(commit: Boolean) {
        val session = fxEdit ?: return
        fxEdit = null
        if (commit && session.fx != session.base && execute(EditCommand.SetFxAt(session.clipId, session.fx, session.frame))) return
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

    // region parameter keyframes

    /** The key drag in progress in the keyframe lane, shown live and committed as one undo step on release. */
    private var paramEdit: EditCommand? = null

    private fun selectedParamClip(): Clip? = state.value.selectedClipId?.let { history.timeline.trackOfClip(it)?.clip(it) }

    /** Adds a key holding the value shown at the playhead, or removes the one that is there. */
    private fun toggleParamKey(paramId: String) = withSelection { clipId ->
        val clip = selectedParamClip() ?: return@withSelection
        val frame = state.value.selectedFrame
        if (frame == null) {
            emit(EditorEffect.ShowMessage("Move the playhead inside the clip to set a keyframe"))
            return@withSelection
        }
        val spec = clip.paramSpec(paramId)
        if (spec == null) {
            emit(EditorEffect.ShowMessage("That value cannot be animated"))
            return@withSelection
        }
        if (ParamTracks.at(clip.paramKeys(paramId), frame) != null) {
            execute(EditCommand.RemoveParamKey(clipId, paramId, frame))
        } else {
            val value = (clip.paramValueAt(paramId, frame) ?: spec.min).coerceIn(spec.min, spec.max)
            execute(EditCommand.SetParamKey(clipId, paramId, ParamKey(frame, value)))
        }
    }

    private fun jumpToParamKey(paramId: String, forward: Boolean) = withSelection {
        val clip = selectedParamClip() ?: return@withSelection
        val relative = state.value.playhead - clip.timelineStart
        val keys = clip.paramKeys(paramId)
        val target = if (forward) ParamTracks.nextFrame(keys, relative) else ParamTracks.previousFrame(keys, relative)
        if (target == null) {
            emit(EditorEffect.ShowMessage(if (forward) "No later keyframe on this value" else "No earlier keyframe on this value"))
            return@withSelection
        }
        seekTo(clip.timelineStart.value + target)
    }

    private fun copyParamKeys(paramId: String) {
        val clip = selectedParamClip() ?: return
        val keys = clip.paramKeys(paramId)
        if (keys.isEmpty()) {
            emit(EditorEffect.ShowMessage("This value has no keyframes to copy"))
            return
        }
        val first = keys.first().frame
        reduce { copy(paramClipboard = ParamClipboard(paramId, keys.map { it.copy(frame = it.frame - first) })) }
        emit(EditorEffect.ShowMessage("Copied ${keys.size} keyframes"))
    }

    /** Pastes the copied keys so the first lands on the playhead; values are clamped to this value's range. */
    private fun pasteParamKeys(paramId: String) = withSelection { clipId ->
        val clip = selectedParamClip() ?: return@withSelection
        val clipboard = state.value.paramClipboard
        if (clipboard == null) {
            emit(EditorEffect.ShowMessage("Copy keyframes first"))
            return@withSelection
        }
        val frame = state.value.selectedFrame
        if (frame == null) {
            emit(EditorEffect.ShowMessage("Move the playhead inside the clip to paste keyframes"))
            return@withSelection
        }
        val keys = ParamTracks.shiftedTo(clipboard.keys, frame, clip.durationFrames)
        if (keys.isEmpty() || clip.paramSpec(paramId) == null) {
            emit(EditorEffect.ShowMessage("The copied keyframes do not fit here"))
            return@withSelection
        }
        execute(EditCommand.PasteParamKeys(clipId, paramId, keys))
    }

    /** Drags the key at [from] to [to] with [value]: shown live, kept provisional until [endParamKeyEdit]. */
    private fun updateParamKey(paramId: String, from: Long, to: Long, value: Double) {
        if (drag != null) return
        val clipId = state.value.selectedClipId ?: return
        val clip = selectedParamClip() ?: return
        val existing = ParamTracks.at(clip.paramKeys(paramId), from) ?: return
        val spec = clip.paramSpec(paramId) ?: return
        val moved = existing.copy(frame = to.coerceIn(0L, clip.durationFrames - 1), value = value.coerceIn(spec.min, spec.max))
        val parts = ArrayList<EditCommand>(2)
        if (moved.frame != from) parts += EditCommand.MoveParamKey(clipId, paramId, from, moved.frame)
        parts += EditCommand.SetParamKey(clipId, paramId, moved)
        val command = EditCommand.Batch(parts)
        when (val result = command.apply(history.timeline)) {
            is EditResult.Success -> {
                paramEdit = command
                reduce { copy(dragPreview = result.value, selectedParamKey = paramId to moved.frame) }
            }
            is EditResult.Failure -> Unit // a position that is not allowed keeps the last valid preview
        }
    }

    private fun endParamKeyEdit(commit: Boolean) {
        val command = paramEdit ?: return
        paramEdit = null
        val result = command.apply(history.timeline)
        val changed = result is EditResult.Success && result.value != history.timeline
        if (commit && changed && execute(command)) return
        reduce { copy(dragPreview = null) }
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

    private fun toggleSmoothSlowMo() = withSelection { clipId ->
        val clip = history.timeline.trackOfClip(clipId)?.clip(clipId) ?: return@withSelection
        execute(EditCommand.SetSmoothSlowMo(clipId, !clip.smoothSlowMo))
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
            SpeedRampShape.EASE_IN_SMOOTH -> SpeedRamps.easeInSmooth(clip.durationFrames)
            SpeedRampShape.EASE_OUT_SMOOTH -> SpeedRamps.easeOutSmooth(clip.durationFrames)
            SpeedRampShape.MONTAGE -> SpeedRamps.montage(clip.durationFrames)
            SpeedRampShape.HERO -> SpeedRamps.hero(clip.durationFrames)
            SpeedRampShape.BULLET -> SpeedRamps.bullet(clip.durationFrames)
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

    private fun setTransitionStyle(
        type: com.ultimatevideo.uveditor.domain.TransitionType,
        direction: com.ultimatevideo.uveditor.domain.TransitionDirection,
    ) = withSelection { clipId ->
        val transition = history.timeline.transitions.firstOrNull { it.fromClipId == clipId }
        if (transition == null) {
            emit(EditorEffect.ShowMessage("Add a transition to the next clip first"))
            return@withSelection
        }
        execute(EditCommand.SetTransitionStyle(transition.id, type, direction))
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
                // An animated picture starts at one pass of its animation; a photo at the default still length.
                durationFrames = project.microsToFrames(
                    probed.animationDelaysMs?.let { AnimationTiming(it).periodMicros } ?: PHOTO_DEFAULT_MICROS,
                ).coerceAtLeast(1),
                nativeFpsNum = project.num,
                nativeFpsDen = project.den,
                colorSpace = probed.colorSpace,
                hasVideo = false,
                hasAudio = false,
                isImage = true,
                animationDelaysMs = probed.animationDelaysMs,
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
        is EditError.InvalidAudio -> "That audio setting is not allowed: ${error.reason}"
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
        is GroupEditUnavailable -> error.reason
        is EditError.DuplicateClipId, is EditError.DuplicateTrackId, is EditError.DuplicateTransitionId,
        is EditError.InvalidClip, is EditError.TrackTypeMismatch -> "That edit is not valid"
    }

    // region media library, marker notes and exports to other tools

    private fun libraryIntent(intent: LibraryIntent) {
        when (intent) {
            is LibraryIntent.Open -> reduce { copy(library = library.copy(open = true, highlightAssetId = intent.assetId)) }
            LibraryIntent.Close -> reduce { copy(library = library.copy(open = false, highlightAssetId = null)) }
            LibraryIntent.RevealSelectedInLibrary -> {
                val clipId = state.value.selectedClipId
                val assetId = clipId?.let { Library.assetOfClip(history.timeline, it) }
                if (clipId != null && assetId == null) emit(EditorEffect.ShowMessage("A title or sticker has no file in the library"))
                reduce { copy(library = library.copy(open = true, highlightAssetId = assetId, query = if (assetId != null) LibraryQuery() else library.query)) }
            }
            is LibraryIntent.QueryChanged -> reduce { copy(library = library.copy(query = library.query.copy(text = intent.text))) }
            is LibraryIntent.FilterSelected -> reduce { copy(library = library.copy(query = library.query.copy(filter = intent.filter))) }
            is LibraryIntent.TagSelected -> reduce { copy(library = library.copy(query = library.query.copy(tag = intent.tag))) }
            is LibraryIntent.EditAsset -> {
                val asset = state.value.assets.firstOrNull { it.id == intent.assetId } ?: return
                val draft = AssetEditDraft(asset.id, MissingMedia.nameOf(asset), asset.tags.joinToString(", "), asset.note.orEmpty())
                reduce { copy(library = library.copy(editing = draft)) }
            }
            is LibraryIntent.TagsChanged -> reduce { copy(library = library.copy(editing = library.editing?.copy(tags = intent.text))) }
            is LibraryIntent.NoteChanged -> reduce { copy(library = library.copy(editing = library.editing?.copy(note = intent.text.take(Library.MAX_NOTE_LENGTH)))) }
            LibraryIntent.ConfirmAssetEdit -> confirmAssetEdit()
            LibraryIntent.DismissAssetEdit -> reduce { copy(library = library.copy(editing = null)) }
            LibraryIntent.AskDeleteUnused -> askDeleteUnused()
            LibraryIntent.ConfirmDeleteUnused -> confirmDeleteUnused()
            LibraryIntent.DismissDeleteUnused -> reduce { copy(library = library.copy(confirmDeleteUnused = null)) }
            is LibraryIntent.FindInTimeline -> findInTimeline(intent.assetId)
            is LibraryIntent.RequestExport -> requestExport(intent.kind)
            is LibraryIntent.ExportTo -> exportTo(intent.kind, intent.uri)
            LibraryIntent.OpenMarkerEdit -> openMarkerEdit()
            is LibraryIntent.MarkerNoteChanged -> reduce { copy(markerEdit = markerEdit?.copy(note = intent.text.take(MarkerOps.MAX_NOTE_LENGTH))) }
            is LibraryIntent.MarkerColorSelected -> reduce { copy(markerEdit = markerEdit?.copy(color = intent.color)) }
            LibraryIntent.ConfirmMarkerEdit -> confirmMarkerEdit()
            LibraryIntent.DismissMarkerEdit -> reduce { copy(markerEdit = null) }
        }
    }

    /** Tags and notes belong to the library, like its order: saved with the project, not part of the undo history. */
    private fun confirmAssetEdit() {
        val draft = state.value.library.editing ?: return
        var assets = Library.withTags(state.value.assets, draft.assetId, Library.parseTags(draft.tags))
        assets = Library.withNote(assets, draft.assetId, draft.note)
        reduce { copy(assets = assets, library = library.copy(editing = null)) }
        scheduleSave()
    }

    /**
     * How many uses each file has anywhere the user could still get back to: the timeline, every state in the
     * undo and redo history, and the clipboard. Cleaning up must not remove a file that an undo would need.
     */
    private fun protectedUsage(): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (timeline in history.reachableTimelines()) {
            for ((id, n) in usageCounts(timeline)) counts[id] = maxOf(counts[id] ?: 0, n)
        }
        clipboard?.entries?.forEach { entry -> entry.clip.assetId?.let { counts[it] = maxOf(counts[it] ?: 0, 1) } }
        return counts
    }

    private fun askDeleteUnused() {
        val removable = Library.unused(state.value.assets, protectedUsage())
        if (removable.isEmpty()) {
            emit(EditorEffect.ShowMessage(if (Library.unused(state.value.assets, usageCounts(history.timeline)).isEmpty()) "Every file in the library is used" else "Nothing can be removed while undo could still bring those clips back"))
            return
        }
        reduce { copy(library = library.copy(confirmDeleteUnused = removable.size)) }
    }

    private fun confirmDeleteUnused() {
        val usage = protectedUsage()
        val removable = Library.unused(state.value.assets, usage).map { it.id }.toSet()
        reduce { copy(assets = Library.withoutUnused(assets, usage), library = library.copy(confirmDeleteUnused = null)) }
        if (removable.isEmpty()) return
        removable.forEach { mediaCaches.invalidate(it) }
        scheduleSave()
        emit(EditorEffect.ShowMessage("Removed ${removable.size} unused file${if (removable.size == 1) "" else "s"} from the library (the files themselves are not touched)"))
    }

    private fun findInTimeline(assetId: String) {
        val timeline = history.timeline
        val uses = Library.uses(timeline, assetId, state.value.fps)
        val use = Library.nextUse(uses, state.value.playhead.value)
        if (use == null) {
            emit(EditorEffect.ShowMessage("That file is not used on the timeline"))
            return
        }
        val trackId = timeline.trackOfClip(use.clipId)?.id
        seekTo(use.startFrame)
        reduce {
            copy(
                selectedClipId = use.clipId,
                selectedClipIds = emptySet(),
                selectedTrackId = trackId ?: selectedTrackId,
                library = library.copy(open = false),
            )
        }
        val position = uses.indexOf(use) + 1
        emit(EditorEffect.ShowMessage("Use $position of ${uses.size} (${use.trackLabel}). Choose Find in timeline again for the next one"))
    }

    private fun openMarkerEdit() {
        val marker = MarkerOps.nearest(history.timeline.markers, state.value.playhead, MARKER_EDIT_RADIUS_FRAMES)
        if (marker == null) {
            emit(EditorEffect.ShowMessage("Put the playhead on a marker first"))
            return
        }
        reduce { copy(markerEdit = MarkerEditDraft(marker.id, marker.frame.value, marker.note.orEmpty(), marker.color)) }
    }

    private fun confirmMarkerEdit() {
        val draft = state.value.markerEdit ?: return
        reduce { copy(markerEdit = null) }
        execute(AnnotateMarker(draft.markerId, draft.note, draft.color))
    }

    private fun requestExport(kind: InterchangeKind) {
        val project = currentProjectDto() ?: return
        val base = project.name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifEmpty { "project" }
        val (extension, mime) = when (kind) {
            InterchangeKind.EDL -> {
                val files = Edl.export(project).files
                if (files.isEmpty()) {
                    emit(EditorEffect.ShowMessage("There are no video or audio clips to put in an EDL"))
                    return
                }
                if (files.size == 1) "edl" to INTERCHANGE_MIME else "zip" to ZIP_MIME
            }
            else -> kind.extension to kind.mime
        }
        emit(EditorEffect.LaunchInterchangePicker(kind, "$base.$extension", mime))
    }

    private fun currentProjectDto(): ProjectDto? {
        val base = baseProject ?: return null
        return TimelineMapper.toDto(base, history.timeline, state.value.assets)
    }

    private fun exportTo(kind: InterchangeKind, uri: String) {
        val project = currentProjectDto() ?: return
        if (state.value.library.busy != null) return
        reduce { copy(library = library.copy(busy = "Writing ${kind.label.substringBefore(" (")}…")) }
        viewModelScope.launch {
            try {
                emit(EditorEffect.ShowMessage(writeExport(kind, uri, project)))
            } catch (e: ProjectError) {
                emit(EditorEffect.ShowMessage("Export failed: ${e.message}"))
            } catch (e: IOException) {
                emit(EditorEffect.ShowMessage("Export failed: ${e.message ?: "could not write the file"}"))
            } finally {
                reduce { copy(library = library.copy(busy = null)) }
            }
        }
    }

    /** Writes the export and returns the message to show: what was written and what the format left out. */
    private suspend fun writeExport(kind: InterchangeKind, uri: String, project: ProjectDto): String {
        when (kind) {
            InterchangeKind.BUNDLE, InterchangeKind.BUNDLE_WITH_MEDIA -> {
                // The bundle is made from the project file, so what is on screen has to be saved first.
                saveJob?.cancelAndJoin()
                if (dirty && !persist()) throw IOException("the project could not be saved first")
                val result = interchange.exportBundle(projectId, uri, includeMedia = kind == InterchangeKind.BUNDLE_WITH_MEDIA)
                return buildString {
                    append("Bundle written")
                    if (kind == InterchangeKind.BUNDLE_WITH_MEDIA) append(" with ${result.mediaCopied} media file${if (result.mediaCopied == 1) "" else "s"}")
                    if (result.mediaSkipped.isNotEmpty()) append(". Not copied (cannot be read): ${result.mediaSkipped.take(3).joinToString()}${if (result.mediaSkipped.size > 3) "…" else ""}")
                }
            }
            InterchangeKind.EDL -> {
                val export = Edl.export(project)
                if (export.files.isEmpty()) throw IOException("there are no video or audio clips to put in an EDL")
                val bytes = if (export.files.size == 1) export.files.single().text.toByteArray(Charsets.UTF_8) else Edl.zip(export.files)
                interchange.writeDocument(uri, bytes)
                return "EDL written (${export.files.size} track${if (export.files.size == 1) "" else "s"})" + leftOut(export.notes)
            }
            InterchangeKind.FCPXML -> {
                val export = Fcpxml.export(project)
                interchange.writeDocument(uri, export.xml.toByteArray(Charsets.UTF_8))
                return "FCPXML written" + leftOut(export.notes)
            }
        }
    }

    private fun leftOut(notes: List<String>): String = if (notes.isEmpty()) "" else ". Not carried over: ${notes.first().trimEnd('.')}${if (notes.size > 1) " (and ${notes.size - 1} more)" else ""}"

    // endregion

    private companion object {
        const val MARKER_EDIT_RADIUS_FRAMES = 6L
        const val MIN_TARGET_LUFS = -40.0
        const val MAX_TARGET_LUFS = -5.0
        const val MIN_NOISE_SAMPLE_MICROS = 100_000L
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

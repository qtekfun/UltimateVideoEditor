package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.MediaCaches
import com.qtekfun.ultimatevideoeditor.data.MediaImporter
import com.qtekfun.ultimatevideoeditor.data.ProjectError
import com.qtekfun.ultimatevideoeditor.data.ProjectStore
import com.qtekfun.ultimatevideoeditor.data.TimelineMapper
import com.qtekfun.ultimatevideoeditor.data.interchange.InterchangeExporter
import com.qtekfun.ultimatevideoeditor.data.relink.FolderScanner
import com.qtekfun.ultimatevideoeditor.data.relink.NoFolderScanner
import com.qtekfun.ultimatevideoeditor.domain.DropChoice
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.EditHistory
import com.qtekfun.ultimatevideoeditor.domain.EditResult
import com.qtekfun.ultimatevideoeditor.domain.Effect
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.HdrBrightness
import com.qtekfun.ultimatevideoeditor.domain.HdrLevels
import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TimelineOps
import com.qtekfun.ultimatevideoeditor.domain.TrackSeed
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.Transition
import com.qtekfun.ultimatevideoeditor.domain.UnlinkClip
import com.qtekfun.ultimatevideoeditor.domain.multicam.AngleFeed
import com.qtekfun.ultimatevideoeditor.domain.multicam.MulticamClip
import com.qtekfun.ultimatevideoeditor.domain.paramKeys
import com.qtekfun.ultimatevideoeditor.domain.retime
import com.qtekfun.ultimatevideoeditor.engine.multicam.MulticamServices
import com.qtekfun.ultimatevideoeditor.engine.sample.FrameSampler
import com.qtekfun.ultimatevideoeditor.engine.sample.NoFrameSampler
import com.qtekfun.ultimatevideoeditor.engine.stabilise.NoStabiliser
import com.qtekfun.ultimatevideoeditor.engine.stabilise.Stabiliser
import com.qtekfun.ultimatevideoeditor.engine.timeline.BeatSource
import com.qtekfun.ultimatevideoeditor.engine.timeline.EnvelopeSource
import com.qtekfun.ultimatevideoeditor.engine.timeline.NoBeatSource
import com.qtekfun.ultimatevideoeditor.engine.timeline.NoEnvelopeSource
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineSnapshot
import com.qtekfun.ultimatevideoeditor.engine.track.MotionTracker
import com.qtekfun.ultimatevideoeditor.engine.track.NoMotionTracker
import com.qtekfun.ultimatevideoeditor.mvi.MviViewModel
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.AppearanceHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.FxHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.HdrLevelHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.KeyframeHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.MotionTrackHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.ParamKeyHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.QualifierHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.appearance.StabiliseHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.audio.AudioAnalysisHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.audio.AudioEditHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.audio.AudioLinkHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.interchange.InterchangeHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.NoVideoAudioPlacementStore
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.VideoAudioPlacementStore
import com.qtekfun.ultimatevideoeditor.ui.editor.library.LibraryHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.media.MediaHealthHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamController
import com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamUiState
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.editor.project.ProjectPersistence
import com.qtekfun.ultimatevideoeditor.ui.editor.project.syncedTo
import com.qtekfun.ultimatevideoeditor.ui.editor.quickedit.QuickEditHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.relink.RelinkHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.BeatHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.LaneHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.MarkerHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.RetimeHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.SelectionHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.SplitHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.TimelineDragHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.timeline.TransitionHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.MediaImportHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.PlacementHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayDragHandler
import com.qtekfun.ultimatevideoeditor.ui.editor.voice.VoiceOverPlacementHandler
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.text.reasonOf
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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
    /** The "Put video audio on an audio track" preference, read each time media is placed (SPECS 5.38). */
    private val videoAudioPlacement: VideoAudioPlacementStore = NoVideoAudioPlacementStore,
    /** Lists the files of a folder the user picked, to relink every missing file in one step (SPECS 5.40). */
    private val folderScanner: FolderScanner = NoFolderScanner,
    /** Finds how bright the diffuse content of an HDR file is, to dim it to reference white (the "HDR level"). */
    private val hdrAnalyzer: HdrLevelAnalyzer = HdrLevelAnalyzer.None,
    /** Diffuse levels kept per file and colour space, shared by every project. */
    private val hdrLevelCache: HdrLevelCache = HdrLevelCache.None,
    /** Where the cache is read and the analysis waits; the decoding itself runs in the analyser. */
    private val hdrDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The claim on the audio output while the preview plays (other apps' music stops, a call pauses us). */
    private val audioFocus: PlaybackAudioFocus = NoPlaybackAudioFocus,
) : MviViewModel<EditorState, EditorIntent, EditorEffect>(EditorState()) {

    private val clipKeys = KeyRegistry()
    private val assetKeys = KeyRegistry()

    private var history = EditHistory(Timeline())

    /** What the feature handlers see of this model (see [EditorHost]). */
    private val host: EditorHost = object : EditorHost {
        override val state get() = this@EditorViewModel.state
        override val scope get() = viewModelScope
        override val history get() = this@EditorViewModel.history
        override val clipKeys get() = this@EditorViewModel.clipKeys
        override val assetKeys get() = this@EditorViewModel.assetKeys
        override val idGenerator get() = this@EditorViewModel.idGenerator
        override fun reduce(reducer: EditorState.() -> EditorState) = this@EditorViewModel.reduce(reducer)
        override fun emit(effect: EditorEffect) = this@EditorViewModel.emit(effect)
        override fun execute(command: EditCommand): Boolean = this@EditorViewModel.execute(command)
        override fun scheduleSave() = persistence.scheduleSave()
    }

    // region feature handlers (order matters: a handler is built after the ones it uses)
    private val playback = PlaybackController(host, nanoClock, audioFocus)
    private val persistence = ProjectPersistence(host, store, saveDebounceMillis, playback)
    private val interchangeExport = InterchangeHandler(host, projectId, interchange, persistence)
    private val hdr = HdrLevelHandler(host, hdrAnalyzer, hdrLevelCache, hdrDispatcher, persistence)
    private val stabilise = StabiliseHandler(host, stabiliser, stabDispatcher)
    private val motionTrack = MotionTrackHandler(host, motionTracker, trackDispatcher, playback)
    private val qualifier = QualifierHandler(host, frameSampler, sampleDispatcher, playback)
    private val lanes = LaneHandler(host)
    private val split = SplitHandler(host, playback)
    private val markers = MarkerHandler(host, playback)
    private val timelineDrag = TimelineDragHandler(host, playback, lanes, markers)
    private val beats = BeatHandler(host, beatSource, markers)
    private val quickEdit = QuickEditHandler(host, motionTracker, trackDispatcher, envelopeSource)
    private val selection = SelectionHandler(host, playback, markers)
    private val library = LibraryHandler(host, mediaCaches, playback, interchangeExport, selection)
    private val appearance = AppearanceHandler(host, timelineDrag)
    private val fx = FxHandler(host, timelineDrag)
    private val keyframes = KeyframeHandler(host, playback, appearance)
    private val paramKeys = ParamKeyHandler(host, playback, timelineDrag)
    private val audioEdit = AudioEditHandler(host, timelineDrag, paramKeys)
    private val audioAnalysis = AudioAnalysisHandler(host, loudnessCache)
    private val titles = TitleHandler(host)
    private val retime = RetimeHandler(host)
    private val audioLink = AudioLinkHandler(host)
    private val transitions = TransitionHandler(host)
    private val placement = PlacementHandler(host, videoAudioPlacement, playback)
    private val mediaImport = MediaImportHandler(host, importer, placement)
    private val voiceOver = VoiceOverPlacementHandler(host, mediaImport)
    private val trayDragging = TrayDragHandler(host, timelineDrag, placement, mediaImport)
    private val mediaHealth = MediaHealthHandler(host, projectId, store, importer, mediaCaches, playback, hdr)
    private val relink = RelinkHandler(host, importer, mediaCaches, folderScanner)
    // endregion

    private fun scheduleSave() = persistence.scheduleSave()

    /** Picking, syncing and cutting multicam angles; it borrows this model's state, undo history and messages. */
    private val multicamController = MulticamController(
        object : MulticamController.Host {
            override val editor: EditorState get() = state.value
            override fun update(change: (MulticamUiState) -> MulticamUiState) = reduce { copy(multicam = change(multicam)) }
            override fun execute(command: EditCommand): Boolean = this@EditorViewModel.execute(command)
            override fun message(text: UiText) = emit(EditorEffect.ShowMessage(text))
            override fun newId(): String = idGenerator()
            override fun assetLengthFrames(assetId: String): Long? = host.assetLengthFrames(assetId)
        },
        multicamServices,
        viewModelScope,
    )

    /** Where each angle of [group] is shown from while [active] is on screen (the viewer's decoder budget). */
    fun multicamFeeds(group: MulticamClip, active: Int): List<AngleFeed> = multicamController.feeds(group, active)
    /** Set by the screen once the audio engine is up. Until then the transport uses the system clock. */
    var playbackOutput: PlaybackOutput? by playback::playbackOutput

    /** Measures loudness and noise profiles for the audio tools; null where the engine is unavailable. */
    var audioAnalyzer: AudioAnalyzer? by audioAnalysis::audioAnalyzer

    init {
        load()
    }

    override fun onIntent(intent: EditorIntent) {
        // Typing in the title field is only provisional: any other action first makes it final.
        if (intent !is EditorIntent.UpdateTitle && intent !is EditorIntent.EndTitleEdit && intent !is EditorIntent.LayerGesture) titles.endTitleEdit(commit = true)
        // A key drag in the keyframe lane is provisional until released.
        if (intent !is EditorIntent.UpdateParamKey && intent !is EditorIntent.EndParamKeyEdit && !audioEdit.isShapeDragStep(intent)) paramKeys.endParamKeyEdit(commit = true)
        // Same for an effect slider: it stays provisional until released or until something else happens.
        if (intent !is EditorIntent.UpdateEffect && intent !is EditorIntent.UpdateGrade && intent !is EditorIntent.UpdateMask &&
            intent !is EditorIntent.EndFxEdit
        ) {
            fx.endFxEdit(commit = true)
        }
        // And for an audio slider (pan, EQ, track volume, ducking).
        if (intent !is EditorIntent.UpdateClipAudio && intent !is EditorIntent.UpdateTrackAudio &&
            intent !is EditorIntent.UpdateDucking && intent !is EditorIntent.EndAudioEdit && !audioEdit.isShapeDragStep(intent)
        ) {
            audioEdit.endAudioEdit(commit = true)
        }
        when (intent) {
            is EditorIntent.UpdateClipAudio -> host.withSelection { id ->
                // The controls show pan and EQ gains as they are at the playhead; keyframed ones that changed become keys.
                audioEdit.updateAudioEdit("clip:$id", EditCommand.SetClipAudioAt(id, intent.audio, state.value.selectedFrame))
            }
            is EditorIntent.UpdateTrackAudio -> audioEdit.updateAudioEdit("track:${intent.trackId}", EditCommand.SetTrackAudio(intent.trackId, intent.audio))
            is EditorIntent.UpdateDucking -> audioEdit.updateAudioEdit("ducking", EditCommand.SetDucking(intent.ducking))
            is EditorIntent.EndAudioEdit -> audioEdit.endAudioEdit(intent.commit)
            EditorIntent.ResetClipAudio -> audioEdit.resetClipAudio()
            is EditorIntent.NormalizeLoudness -> audioAnalysis.normalizeLoudness(intent.targetLufs)
            EditorIntent.ClearNormalize -> audioAnalysis.clearNormalize()
            is EditorIntent.MarkNoiseRegion -> audioAnalysis.markNoiseRegion(intent.atStart)
            EditorIntent.ClearNoiseRegion -> reduce { copy(noiseRegion = null) }
            is EditorIntent.AnalyzeNoise -> audioAnalysis.analyzeNoise(intent.strength)
            EditorIntent.RemoveNoiseSuppression -> audioAnalysis.removeNoiseSuppression()
            EditorIntent.CancelAudioAnalysis -> audioAnalysis.audioAnalyzer?.cancel()
            EditorIntent.ToggleMixer -> reduce { copy(mixerOpen = !mixerOpen) }
            is EditorIntent.Multicam -> multicamController.handle(intent.intent)
            is EditorIntent.TapTimeline -> selection.tap(intent.hit)
            is EditorIntent.SetPlayhead -> playback.seekTo(intent.frame)
            EditorIntent.ScrubStarted -> playback.scrubStarted()
            is EditorIntent.DragStart -> timelineDrag.dragStart(intent.hit)
            is EditorIntent.DragMove -> timelineDrag.dragMove(intent.frame, intent.trackIndex, intent.zone, intent.reachFrames)
            EditorIntent.FlipDropChoice -> timelineDrag.flipDropChoice()
            EditorIntent.CycleDropMode -> reduce { copy(dropMode = DropChoice.entries[(dropMode.ordinal + 1) % DropChoice.entries.size]) }
            is EditorIntent.DragEnd -> timelineDrag.dragEnd(intent.commit)
            EditorIntent.SplitAtPlayhead -> split.splitAtPlayhead()
            EditorIntent.RippleDeleteSelected -> split.deleteSelectedAtCut()
            EditorIntent.RippleAppendSelected -> host.withSelection { execute(EditCommand.RippleAppend(it)) }
            EditorIntent.TogglePlay -> playback.togglePlay()
            is EditorIntent.AddTrack -> lanes.addTrack(intent.type)
            EditorIntent.RemoveSelectedTrack -> lanes.removeSelectedTrack()
            is EditorIntent.MoveSelectedTrack -> lanes.moveSelectedTrack(intent.delta)
            EditorIntent.SeekPrevious -> playback.jumpTo(playback.previousEditPoint())
            EditorIntent.SeekNext -> playback.jumpTo(playback.nextEditPoint())
            EditorIntent.Undo -> undo()
            EditorIntent.Redo -> redo()
            EditorIntent.ToggleInspector -> appearance.toggleInspector()
            EditorIntent.AddTitle -> titles.addTitle()
            EditorIntent.ClearBeatMarkers -> beats.clearBeatMarkers()
            EditorIntent.ToggleMarkerSnap -> reduce { copy(snapToMarkers = !snapToMarkers) }
            EditorIntent.AnalyzeBeats -> beats.analyzeBeats()
            EditorIntent.CutToBeatFromSelected -> beats.cutToBeatFromSelected()
            is EditorIntent.ApplyTextTemplate -> titles.applyTextTemplate(intent.templateId, intent.text)
            is EditorIntent.ApplyPreset -> titles.applyTemplate(intent.template, intent.text)
            is EditorIntent.AddSticker -> titles.addSticker(intent.stickerId)
            is EditorIntent.AddCaptionClips -> titles.addCaptionClips(intent.clips, intent.intoExistingTrack)
            is EditorIntent.RestyleCaptions -> execute(com.qtekfun.ultimatevideoeditor.domain.RestyleCaptions(intent.style, intent.canvasHeight))
            is EditorIntent.UpdateTitle -> titles.updateTitle(intent.content)
            is EditorIntent.SelectTitleLayer -> titles.selectTitleLayer(intent.index)
            is EditorIntent.LayerGesture -> titles.layerGesture(intent)
            is EditorIntent.FontsChanged -> reduce { copy(availableFonts = intent.available) }
            is EditorIntent.ApplyTitleMotion -> titles.applyTitleMotion(intent.intro, intent.outro)
            is EditorIntent.EndTitleEdit -> titles.endTitleEdit(intent.commit)
            EditorIntent.AddTransition -> transitions.addTransition()
            is EditorIntent.SetTransitionDuration -> transitions.setTransitionDuration(intent.frames)
            EditorIntent.RemoveTransition -> transitions.removeTransition()
            is EditorIntent.SetTransitionStyle -> transitions.setTransitionStyle(intent.type, intent.direction)
            EditorIntent.BeginAppearanceEdit -> appearance.beginAppearance()
            is EditorIntent.UpdateTransform -> appearance.updateAppearance(transform = intent.transform)
            is EditorIntent.UpdateGain -> appearance.updateAppearance(gainDb = intent.gainDb)
            is EditorIntent.TransformGesture -> appearance.transformGesture(intent)
            is EditorIntent.EndAppearanceEdit -> appearance.endAppearance(intent.commit)
            EditorIntent.ResetAppearance -> appearance.resetAppearance()
            is EditorIntent.AddEffect -> host.withSelection { execute(EditCommand.AddEffect(it, Effect(idGenerator(), intent.type))) }
            EditorIntent.OpenLutPicker -> host.withSelection { reduce { copy(lutPickerOpen = true) } }
            EditorIntent.CloseLutPicker -> reduce { copy(lutPickerOpen = false) }
            is EditorIntent.AddLut -> {
                reduce { copy(lutPickerOpen = false) }
                host.withSelection { execute(EditCommand.AddEffect(it, Effect(idGenerator(), EffectType.LUT, listOf(intent.key.toDouble(), 1.0)))) }
            }
            is EditorIntent.RemoveEffect -> host.withSelection { execute(EditCommand.RemoveEffect(it, intent.effectId)) }
            is EditorIntent.MoveEffect -> host.withSelection { execute(EditCommand.MoveEffect(it, intent.effectId, intent.toIndex)) }
            is EditorIntent.UpdateEffect -> fx.updateEffect(intent.effectId, intent.values)
            is EditorIntent.UpdateGrade -> fx.updateGrade(intent.effectId, intent.values, intent.curves)
            is EditorIntent.ApplyGrade -> fx.applyGrade(intent.values, intent.curves)
            is EditorIntent.SetBlendMode -> host.withSelection { execute(EditCommand.SetBlendMode(it, intent.mode)) }
            is EditorIntent.SetClipColor -> host.withSelection { execute(EditCommand.SetColorOverride(it, intent.space)) }
            is EditorIntent.SetHdrBrightness -> hdr.changeHdrBrightness(intent.brightness)
            is EditorIntent.SetClipHdrLevel -> host.withSelection { execute(EditCommand.SetHdrAutoLevel(it, intent.on)) }
            is EditorIntent.SetStabilise -> host.withSelection { execute(EditCommand.SetStabilise(it, intent.stabilise)) }
            EditorIntent.AnalyseStabilise -> stabilise.analyseStabilise()
            EditorIntent.CancelStabilise -> stabiliser.cancel()
            EditorIntent.RefreshStabilise -> stabilise.refreshStabilise()
            EditorIntent.RefreshTrack -> motionTrack.refreshTrack()
            EditorIntent.BeginTrackPick -> motionTrack.beginTrackPick()
            EditorIntent.CancelTrackPick -> reduce { copy(track = track.copy(picking = false)) }
            is EditorIntent.SetTrackBox -> reduce { copy(track = track.copy(boxSide = intent.side.coerceIn(TrackSeed.MIN_SIZE, 0.5))) }
            is EditorIntent.PickTrackTarget -> motionTrack.pickTrackTarget(intent.x, intent.y, intent.w, intent.h)
            EditorIntent.CancelTrack -> motionTracker.cancel()
            is EditorIntent.ReanalyseTrack -> motionTrack.startTrackAnalysis(intent.trackId)
            is EditorIntent.RemoveMotionTrack -> motionTrack.removeMotionTrack(intent.trackId)
            is EditorIntent.ShowTrack -> {
                reduce { copy(track = track.copy(activeId = intent.trackId, overlay = if (intent.trackId == null) emptyList() else track.overlay)) }
                motionTrack.refreshTrack()
            }
            is EditorIntent.FollowTrack -> motionTrack.followTrack(intent.trackId)
            is EditorIntent.UpdateMask -> fx.updateMask(intent.mask)
            is EditorIntent.EndFxEdit -> fx.endFxEdit(intent.commit)
            EditorIntent.ClearFx -> host.withSelection { execute(EditCommand.ClearFx(it)) }
            EditorIntent.ToggleKeyframe -> keyframes.toggleKeyframe()
            is EditorIntent.JumpToKeyframe -> keyframes.jumpToKeyframe(intent.forward)
            is EditorIntent.SetKeyframeInterpolation -> keyframes.setKeyframeInterpolation(intent.interpolation)
            EditorIntent.ClearKeyframes -> keyframes.clearKeyframes()
            is EditorIntent.ToggleParamKey -> paramKeys.toggleParamKey(intent.paramId)
            is EditorIntent.JumpToParamKey -> paramKeys.jumpToParamKey(intent.paramId, intent.forward)
            is EditorIntent.ClearParamTrack -> host.withSelection { execute(EditCommand.ClearParamTrack(it, intent.paramId)) }
            is EditorIntent.CopyParamKeys -> paramKeys.copyParamKeys(intent.paramId)
            is EditorIntent.PasteParamKeys -> paramKeys.pasteParamKeys(intent.paramId)
            is EditorIntent.SetParamKeyShape ->
                host.withSelection { execute(EditCommand.SetParamKeyShape(it, intent.paramId, intent.frame, intent.interpolation, intent.out, intent.inn)) }
            is EditorIntent.UpdateParamKey -> paramKeys.updateParamKey(intent.paramId, intent.fromFrame, intent.toFrame, intent.value)
            is EditorIntent.EndParamKeyEdit -> paramKeys.endParamKeyEdit(intent.commit)
            is EditorIntent.SelectParamKey -> reduce { copy(selectedParamKey = intent.paramId?.let { it to (intent.frame ?: 0L) }) }
            is EditorIntent.SetSpeed -> retime.setSpeed(intent.num, intent.den)
            EditorIntent.ToggleReverse -> retime.toggleReverse()
            is EditorIntent.SetSpeedRamp -> retime.setSpeedRamp(intent.shape)
            is EditorIntent.SetSpeedKeys -> host.withSelection { clipId -> execute(EditCommand.SetSpeedRamp(clipId, intent.keys)) }
            EditorIntent.ToggleSmoothSlowMo -> retime.toggleSmoothSlowMo()
            EditorIntent.FreezeFrame -> retime.freezeFrame()
            EditorIntent.DetachAudio -> audioLink.detachAudio()
            EditorIntent.UnlinkAudio -> host.withSelection { execute(UnlinkClip(it)) }
            is EditorIntent.RelinkAudio -> audioLink.relinkAudio(intent.realign)
            EditorIntent.RestoreEmbeddedAudio -> audioLink.restoreEmbeddedAudio()
            is EditorIntent.SetSafeZone -> reduce { copy(safeZone = intent.platform) }
            EditorIntent.ShowCanvasDialog -> reduce { copy(canvasDialogOpen = true) }
            EditorIntent.DismissCanvasDialog -> reduce { copy(canvasDialogOpen = false) }
            is EditorIntent.ChangeCanvas -> changeCanvas(intent.width, intent.height)
            is EditorIntent.ChangeColorSpace -> changeColorSpace(intent.space)
            is EditorIntent.ImportMedia -> mediaImport.importMedia(intent.uris, intent.atEnd)
            is EditorIntent.PlaceVoiceOver -> voiceOver.placeVoiceOver(intent.uri, intent.startFrame, intent.trimMs)
            is EditorIntent.AddAsset -> placement.addAssetById(intent.assetId)
            is EditorIntent.AddAssets -> placement.addAssetsById(intent.assetIds)
            is EditorIntent.TrayDragStart -> trayDragging.trayDragStart(intent.assetId)
            is EditorIntent.ExternalDragStart -> trayDragging.externalDragStart(intent.kinds)
            is EditorIntent.TrayDragMove -> trayDragging.trayDragMove(intent.frame, intent.trackIndex, intent.zone)
            EditorIntent.TrayDragLeave -> reduce { copy(dragPreview = null, dropHint = null, dragOverlay = null) }
            is EditorIntent.TrayDragEnd -> trayDragging.trayDragEnd(intent.commit)
            is EditorIntent.ExternalDrop -> trayDragging.externalDrop(intent.uris, intent.frame, intent.trackIndex, intent.zone)
            is EditorIntent.ImportToTray -> mediaImport.importToTray(intent.uris)
            is EditorIntent.ReorderAsset -> mediaImport.reorderAsset(intent.assetId, intent.toIndex)
            EditorIntent.ShowRelink -> reduce { copy(relinkOpen = true) }
            EditorIntent.HideRelink -> reduce { copy(relinkOpen = false, folderRelink = if (folderRelink is FolderRelinkUi.Running) folderRelink else FolderRelinkUi.Idle) }
            EditorIntent.RequestFolderRelink -> emit(EditorEffect.LaunchFolderPicker)
            is EditorIntent.RelinkFromFolders -> relink.relinkFromFolders(intent.treeUris)
            is EditorIntent.RelinkFromFolder -> relink.relinkFromFolder(intent.treeUri)
            EditorIntent.CancelFolderRelink -> relink.cancelFolderRelink()
            EditorIntent.DismissFolderRelink -> reduce {
                // Back to the list keeps the session (its results and counters); only Close ends it.
                val session = folderRelink as? FolderRelinkUi.Done
                copy(folderRelink = session?.copy(showList = true) ?: FolderRelinkUi.Idle, relinkOpen = relinkOpen && missingMedia.isNotEmpty())
            }
            is EditorIntent.RelinkFromCandidate -> relink.relinkAsset(intent.assetId, intent.uri, fromFolder = true)
            is EditorIntent.RequestRelink -> emit(EditorEffect.LaunchRelinkPicker(intent.assetId))
            is EditorIntent.RelinkAsset -> relink.relinkAsset(intent.assetId, intent.uri)
            EditorIntent.RetrySave -> persistence.retrySave()
            EditorIntent.LeaveWithoutSaving -> emit(EditorEffect.Close)
            EditorIntent.Flush -> persistence.flush(thenClose = false)
            EditorIntent.Back -> persistence.flush(thenClose = true)
            is SelectionIntent -> selection.selectionIntent(intent)
            is LaneDragIntent -> lanes.laneDragIntent(intent)
            is AudioShapeIntent -> audioEdit.audioShapeIntent(intent)
            is QualifierIntent -> qualifier.qualifierIntent(intent)
            is LibraryIntent -> library.libraryIntent(intent)
            is QuickEditIntent -> quickEdit.quickEditIntent(intent)
            is MarkerIntent -> markers.markerIntent(intent)
            is EditorIntent.ReportError -> emit(EditorEffect.ShowMessage(intent.message))
            is EditorIntent.ReportText -> emit(EditorEffect.ShowMessage(intent.text))
            is EditorIntent.MediaFailureReported -> mediaHealth.mediaFailureReported(intent.assetKey, intent.detail)
            EditorIntent.RecheckMissingMedia -> mediaHealth.recheckMissingMedia()
        }
    }

    /** True if a drag that starts on [hit] should edit the clip instead of scrolling the timeline. */
    fun canDrag(hit: TimelineHit): Boolean = timelineDrag.canDrag(hit)

    /** Snapshot keys of the dragged clips, for the canvas. */
    fun dragOverlayKeys(overlay: DragOverlay): LongArray = timelineDrag.dragOverlayKeys(overlay)

    /** The longest transition that fits across [transition]'s cut now; the upper end of the duration control. */
    fun transitionLimit(transition: Transition): Long {
        val from = history.timeline.trackOfClip(transition.fromClipId)?.clip(transition.fromClipId) ?: return 0
        return TimelineOps.maxTransitionFrames(history.timeline, transition.fromClipId, transition.toClipId, host.assetLengthFrames(from.assetId))
    }

    /** Stable native key for [clipId]; the same key the timeline canvas uses. */
    fun clipKey(clipId: String): Long = clipKeys.keyFor(clipId)

    /** Native key for [assetId]; used to request its waveform. */
    fun assetKey(assetId: String): Long = assetKeys.keyFor(assetId)

    /**
     * Encodes [state] for the native canvas. All keys are stable for the life of this ViewModel.
     * [words] gives the text of a resource in the language in use (the screen passes its context); without it only data text is labelled.
     */
    fun snapshotOf(state: EditorState, words: (UiText) -> String = ClipLabels.RAW_ONLY): TimelineSnapshot =
        buildTimelineSnapshot(state, clipKeys, assetKeys, words)

    // region loading and saving

    private fun load() {
        stabiliser.releaseAll()  // tables of an earlier project must never be read by this one
        viewModelScope.launch {
            try {
                val project = store.load(projectId)
                val timeline = withDefaultTracks(TimelineMapper.toTimeline(project))
                persistence.baseProject = project
                history = EditHistory(timeline)
                reduce {
                    copy(
                        isLoading = false,
                        projectName = project.name,
                        fps = FrameRate(project.settings.fpsNum, project.settings.fpsDen),
                        canvasWidth = project.settings.width,
                        canvasHeight = project.settings.height,
                        colorSpace = ProjectColorSpace.fromId(project.settings.colorSpace),
                        hdr = HdrLevels(HdrBrightness.fromId(project.settings.hdrBrightness), hdrProject = ProjectColorSpace.fromId(project.settings.colorSpace).isHdr),
                        timeline = timeline,
                        selectedTrackId = timeline.tracks.firstOrNull { it.type == TrackType.VIDEO }?.id,
                        assets = project.mediaLibrary,
                        // Until verifyAssets has said which files are readable, nothing opens a file (see playableAssets).
                        mediaChecked = project.mediaLibrary.isEmpty(),
                    )
                }
                stabilise.refreshStabilise()  // a reopened project's stabilised clips need their tables again
                motionTrack.refreshTrack()
                mediaHealth.verifyAssets(project.mediaLibrary)
            } catch (e: ProjectError) {
                reduce { copy(isLoading = false, loadError = reasonOf(e, UiText.res(R.string.s5_project_not_opened))) }
            }
        }
    }

    // endregion

    // region history and project settings

    override fun onCleared() {
        audioFocus.abandon()
        super.onCleared()
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
        appearance.discard()  // the timeline changed under any edit in progress
        titles.discard()
        audioEdit.discard()
        val committed = history.timeline
        val canUndo = history.canUndo
        val canRedo = history.canRedo
        reduce { syncedTo(committed, canUndo, canRedo) }
        stabilise.refreshStabilise()
        motionTrack.refreshTrack()
        hdr.refreshHdrLevels()
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
     * Switches the project to another canvas size (a different format such as 9:16, or a different
     * resolution). The positions of clips and keyframes are rescaled; the undo history starts over.
     */
    private fun changeCanvas(width: Int, height: Int) {
        val current = state.value
        reduce { copy(canvasDialogOpen = false) }
        if (width <= 0 || height <= 0 || (width == current.canvasWidth && height == current.canvasHeight)) return
        appearance.endAppearance(commit = false)
        titles.endTitleEdit(commit = false)
        timelineDrag.cancel()
        val remapped = when (val result = TimelineOps.remapCanvas(history.timeline, current.canvasWidth, current.canvasHeight, width, height)) {
            is EditResult.Success -> result.value
            is EditResult.Failure -> {
                emit(EditorEffect.ShowMessage(describe(result.error)))
                return
            }
        }
        history = EditHistory(remapped)
        persistence.baseProject = persistence.baseProject?.let { it.copy(settings = it.settings.copy(width = width, height = height)) }
        reduce { copy(canvasWidth = width, canvasHeight = height) }
        syncFromHistory()
        scheduleSave()
    }

    private fun changeColorSpace(space: ProjectColorSpace) {
        if (space == state.value.colorSpace) return
        persistence.baseProject = persistence.baseProject?.let { it.copy(settings = it.settings.copy(colorSpace = space.id)) }
        reduce { copy(colorSpace = space, hdr = hdr.copy(hdrProject = space.isHdr), canvasDialogOpen = false) }
        scheduleSave()
    }

    // endregion

}

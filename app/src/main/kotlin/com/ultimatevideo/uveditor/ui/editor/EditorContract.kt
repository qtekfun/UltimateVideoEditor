package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.MediaProblem
import com.ultimatevideo.uveditor.data.MissingAsset
import com.ultimatevideo.uveditor.data.MissingMedia
import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.BezierHandle
import com.ultimatevideo.uveditor.domain.BlendMode
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipAudio
import com.ultimatevideo.uveditor.domain.Ducking
import com.ultimatevideo.uveditor.domain.TrackAudio
import com.ultimatevideo.uveditor.domain.DropHint
import com.ultimatevideo.uveditor.ui.editor.tray.AssetKind
import com.ultimatevideo.uveditor.domain.ClipDeletion
import com.ultimatevideo.uveditor.domain.ClipMask
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.GradeCurves
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframe
import com.ultimatevideo.uveditor.domain.Keyframes
import com.ultimatevideo.uveditor.domain.MotionPreset
import com.ultimatevideo.uveditor.domain.ParamKey
import com.ultimatevideo.uveditor.domain.displayedAt
import com.ultimatevideo.uveditor.domain.ProjectColorSpace
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleLayerEdit
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState

/** A stretch of clip [clipId], in clip frames [start, end), marked as the noise sample. */
data class NoiseRegion(val clipId: String, val startFrame: Long?, val endFrame: Long?) {
    val isComplete: Boolean get() = startFrame != null && endFrame != null && endFrame > startFrame
}

/** Keys copied from the track of [paramId], with frames relative to the first key; pasted at the playhead. */
data class ParamClipboard(val paramId: String, val keys: List<ParamKey>)

data class EditorState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val projectName: String = "",
    val fps: FrameRate = FrameRate(30, 1),
    /** Project resolution: the canvas the preview composites on and clip positions are measured in. */
    val canvasWidth: Int = 1920,
    val canvasHeight: Int = 1080,
    /** Colour space the project is composited and exported in; HDR (HLG) or SDR. */
    val colorSpace: ProjectColorSpace = ProjectColorSpace.REC709_SDR,
    /** Committed timeline; only changes through the undo history. */
    val timeline: Timeline = Timeline(),
    /** Provisional timeline while a clip is being dragged; discarded or committed on release. */
    val dragPreview: Timeline? = null,
    /**
     * What releasing the dragged clip would do, drawn over the timeline during the drag. Decided by
     * [com.ultimatevideo.uveditor.domain.DropPlan], the same function that runs the drop; its lane id
     * refers to a lane of [visibleTimeline].
     */
    val dropHint: DropHint? = null,
    val assets: List<MediaAssetDto> = emptyList(),
    val playhead: FrameIndex = FrameIndex.ZERO,
    val selectedClipId: String? = null,
    /** Where imports land and what Remove track acts on. Kept in step with clip selection. */
    val selectedTrackId: String? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isImporting: Boolean = false,
    val isPlaying: Boolean = false,
    /** The appearance inspector (transform and gain of the selected clip) replaces the timeline while open. */
    val inspectorOpen: Boolean = false,
    /** The layer of a multilayer title that the title editor is working on (and that preview gestures move); null for none. */
    val titleLayerRef: TitleLayerRef? = null,
    /** Ids of the imported fonts that exist on this device; titles that name others show the default font. */
    val availableFonts: Set<String> = emptySet(),
    /** Which app's safe zones are outlined over the preview, or null for none. */
    val safeZone: SafeZonePlatform? = null,
    /** The "change canvas" dialog is open. */
    val canvasDialogOpen: Boolean = false,
    /** The LUT picker (library list and import) is open for the selected clip. */
    val lutPickerOpen: Boolean = false,
    /** Library files that cannot be read right now, by asset id. Their clips stay on the timeline, marked. */
    val missingMedia: Map<String, MediaProblem> = emptyMap(),
    /** The relink list is open. */
    val relinkOpen: Boolean = false,
    /** The last autosave failed with this message; the project on disk is older than what is on screen. */
    val saveError: String? = null,
    /** Leaving was refused because the last save failed; the user chooses between retrying and discarding. */
    val leaveBlockedBySave: Boolean = false,
    /** Moving, trimming and dropping clips snaps to ruler markers (manual and beat) as well as to clip edges. */
    val snapToMarkers: Boolean = true,
    /** Beat detection is running for the selected clip. */
    val isAnalyzingBeats: Boolean = false,
    /** The stabiliser's status for the selected clip (see [StabUiState]). */
    val stab: StabUiState = StabUiState(),
    /** Motion tracking for the selected clip: its targets, the analysis progress, target picking and the path overlay. */
    val track: TrackUiState = TrackUiState(),
    /** Select mode: a tap toggles clips in the selection and dragging empty space draws a marquee. */
    val selectMode: Boolean = false,
    /** A lane picked up by its header and where it would land; null when no lane header drag is running. */
    val laneDrag: LaneDrag? = null,
    /** The selected clips when more than one is selected (includes [selectedClipId]); read them through [selection]. */
    val selectedClipIds: Set<String> = emptySet(),
    /** How many clips the clipboard holds. */
    val clipboardCount: Int = 0,
    /** A slider drag on an audio tool is in progress: [dragPreview] holds it and the mixer plays it live. */
    val audioSessionActive: Boolean = false,
    /** What the audio analysis in progress is doing ("Measuring loudness…"), or null when idle. */
    val audioBusy: String? = null,
    /** The quiet region (clip frames) marked on the selected clip as the noise sample, if any. */
    val noiseRegion: NoiseRegion? = null,
    /** The track mixer sheet (volume, mute, solo, role, compressor, ducking) is open. */
    val mixerOpen: Boolean = false,
    /** The media library sheet (tags, notes, usage, cleanup, exports to other tools). */
    val library: LibraryUiState = LibraryUiState(),
    /** The note and colour dialog of a marker, or null when closed. */
    val markerEdit: MarkerEditDraft? = null,
    /** Keys copied from a parameter's track (frames relative to the first key), ready to paste at the playhead. */
    val paramClipboard: ParamClipboard? = null,
    /** The key of the keyframe lane whose curve controls are shown: parameter and clip frame. */
    val selectedParamKey: Pair<String, Long>? = null,
    /** Silence auto cut and manual reframe sheets (SPECS.md 9.16). */
    val quickEdits: QuickEditsUiState = QuickEditsUiState(),
    /** The multicam sheet: angles being picked and synced, and live cutting between the angles of a multicam clip. */
    val multicam: com.ultimatevideo.uveditor.ui.editor.multicam.MulticamUiState = com.ultimatevideo.uveditor.ui.editor.multicam.MulticamUiState(),
) : UiState {
    /** The timeline the mixer plays: the committed one, or the live audio edit while a slider is dragged. */
    val audioSource: Timeline get() = if (audioSessionActive) visibleTimeline else timeline

    /** The unreadable files, with how many clips depend on each. */
    val missingAssets: List<MissingAsset> get() = MissingMedia.summarize(timeline, assets, missingMedia)

    /** The library without the unreadable files: what the preview, the mixer and the thumbnails may open. */
    val playableAssets: List<MediaAssetDto> get() = if (missingMedia.isEmpty()) assets else assets.filter { it.id !in missingMedia }

    /**
     * The selected clip's own frame under the playhead (0 is its first frame), or null when the
     * playhead is outside it. Keyframes are placed and read at this frame.
     */
    val selectedClipFrame: Long?
        get() {
            val clip = selectedVisualClip ?: return null
            return if (playhead >= clip.timelineStart && playhead < clip.timelineEnd) playhead - clip.timelineStart else null
        }

    /** The keyframe of the selected clip exactly at the playhead, if there is one. */
    val keyframeAtPlayhead: Keyframe?
        get() {
            val frame = selectedClipFrame ?: return null
            return Keyframes.at(selectedVisualClip?.keyframes.orEmpty(), frame)
        }

    /**
     * The pose the inspector shows for the selected clip: the animated pose at the playhead when
     * the clip has keyframes and the playhead is inside it, else its fixed transform.
     */
    val selectedPose: ClipTransform?
        get() {
            val clip = selectedVisualClip ?: return null
            val frame = selectedClipFrame ?: return clip.transform
            return clip.transformAt(frame)
        }

    /** What the canvas should draw right now. */
    val visibleTimeline: Timeline get() = dragPreview ?: timeline

    /** The selected clip as currently shown (including an edit in progress), if it is a video clip. */
    val selectedVideoClip: Clip?
        get() {
            val id = selectedClipId ?: return null
            val track = visibleTimeline.trackOfClip(id)?.takeIf { it.type == TrackType.VIDEO } ?: return null
            return track.clip(id)
        }

    /** The selected clip as currently shown, whatever its track type. */
    val selectedClip: Clip? get() = selectedClipId?.let { visibleTimeline.trackOfClip(it)?.clip(it) }

    /** Like [selectedClipFrame] for any selected clip (audio clips too): its own frame under the playhead, or null outside it. */
    val selectedFrame: Long?
        get() {
            val clip = selectedClip ?: return null
            return if (playhead >= clip.timelineStart && playhead < clip.timelineEnd) playhead - clip.timelineStart else null
        }

    /**
     * The selected clip as its controls show it: keyframed effect values, volume, pan and EQ gains evaluated at the
     * playhead. Edits made from this view write keys at the playhead for the keyframed ones (see `ParamOps.setFxAt`).
     */
    val displayedClip: Clip? get() = selectedClip?.displayedAt(selectedFrame)

    /** The selected clip if it is something drawn on the canvas: a video clip or a title. */
    val selectedVisualClip: Clip?
        get() {
            val id = selectedClipId ?: return null
            val track = visibleTimeline.trackOfClip(id)?.takeIf { it.type != TrackType.AUDIO } ?: return null
            return track.clip(id)
        }

    /** The text and style of the selected clip when it is a title. */
    val selectedTitle: TitleContent? get() = selectedClip?.title

    /** The layer of the selected multilayer title that the title editor works on, when that selection is still valid. */
    val selectedTitleLayer: Int?
        get() {
            val ref = titleLayerRef?.takeIf { it.clipId == selectedClipId } ?: return null
            return ref.index.takeIf { selectedTitle?.layers?.indices?.contains(it) == true }
        }

    /** Ids of imported fonts that titles of this project name but this device does not have (they show the default font). */
    val missingFonts: Set<String>
        get() = timeline.tracks.flatMapTo(LinkedHashSet()) { track ->
            track.clips.flatMap { clip -> clip.title?.let { TitleLayerEdit.missingFonts(it, availableFonts) }.orEmpty() }
        }

    /** The clip that starts exactly where the selected one ends on its track: the other side of a cut. */
    val clipAfterSelected: Clip?
        get() {
            val clip = selectedClip ?: return null
            return visibleTimeline.trackOfClip(clip.id)?.clips?.firstOrNull { it.timelineStart == clip.timelineEnd }
        }

    /** True when the selected clip is on the base track, where gaps are closed automatically. */
    val selectedClipOnBase: Boolean
        get() {
            val id = selectedClipId ?: return false
            val base = ClipDeletion.baseTrack(visibleTimeline) ?: return false
            return base.clip(id) != null
        }

    /** The transition from the selected clip into the next one, if there is one. */
    val selectedTransition: Transition?
        get() {
            val id = selectedClipId ?: return null
            return visibleTimeline.transitions.firstOrNull { it.fromClipId == id }
        }

    /** True when the selected clip is under the playhead, so a gesture on the preview edits what is visible. */
    val selectedClipVisible: Boolean
        get() = selectedVisualClip?.let { playhead >= it.timelineStart && playhead < it.timelineEnd } ?: false

    /** "V1", "A2": the position of the selected track among tracks of its type, top to bottom. */
    val selectedTrackLabel: String?
        get() {
            val track = timeline.tracks.firstOrNull { it.id == selectedTrackId } ?: return null
            val ofType = timeline.tracks.filter { it.type == track.type }
            val prefix = when (track.type) {
                TrackType.VIDEO -> "V"
                TrackType.AUDIO -> "A"
                TrackType.TITLE -> "T"
            }
            // Video stacks upward like a mixer: the top lane is the highest number.
            val number = if (track.type == TrackType.VIDEO) ofType.size - ofType.indexOf(track) else ofType.indexOf(track) + 1
            return "$prefix$number"
        }
}

/** A layer of a multilayer title clip, as the title editor and the preview gestures see it. */
data class TitleLayerRef(val clipId: String, val index: Int)

/** How the speed is spread over the selected clip; [NONE] is a constant speed. */
enum class SpeedRampShape { NONE, EASE_IN, EASE_OUT, BELL, EASE_IN_SMOOTH, EASE_OUT_SMOOTH, MONTAGE, HERO, BULLET }

/** Where the finger is relative to the lanes during a drag. */
enum class DragZone { LANES, ABOVE_LANES, OUTSIDE }

sealed interface EditorIntent : UiIntent {
    data class TapTimeline(val hit: TimelineHit) : EditorIntent
    data class SetPlayhead(val frame: Long) : EditorIntent

    data class DragStart(val hit: TimelineHit) : EditorIntent
    /** [trackIndex] is the lane under the finger in the timeline being shown (-1 over a gap or nothing). */
    data class DragMove(val frame: Long, val trackIndex: Int, val zone: DragZone = DragZone.LANES) : EditorIntent
    data class DragEnd(val commit: Boolean) : EditorIntent

    /** A drag of an asset from the media tray started; a clip for it is prepared and nothing is placed yet. */
    data class TrayDragStart(val assetId: String) : EditorIntent
    /** Files dragged in from another app are hovering; [kinds] come from their MIME types (the media is not probed yet). */
    data class ExternalDragStart(val kinds: List<AssetKind>) : EditorIntent
    /** The dragged asset is over the timeline; [trackIndex] and [zone] are as in [DragMove]. */
    data class TrayDragMove(val frame: Long, val trackIndex: Int, val zone: DragZone = DragZone.LANES) : EditorIntent
    /** The dragged media left the timeline canvas; the drag goes on and the indicator is hidden until it returns. */
    data object TrayDragLeave : EditorIntent
    /** Released ([commit] true) or left the timeline / was cancelled ([commit] false). */
    data class TrayDragEnd(val commit: Boolean) : EditorIntent
    /** Files from another app were dropped on the timeline: they are imported, then the first is placed where it was dropped. */
    data class ExternalDrop(val uris: List<String>, val frame: Long, val trackIndex: Int, val zone: DragZone = DragZone.LANES) : EditorIntent
    /** Adds files to the library (the tray) without putting them on the timeline. */
    data class ImportToTray(val uris: List<String>) : EditorIntent
    /** Moves an asset within the library (the order of the tray). */
    data class ReorderAsset(val assetId: String, val toIndex: Int) : EditorIntent

    data object SplitAtPlayhead : EditorIntent
    data object RippleDeleteSelected : EditorIntent
    data object RippleAppendSelected : EditorIntent
    data object TogglePlay : EditorIntent

    data class AddTrack(val type: TrackType) : EditorIntent
    data object RemoveSelectedTrack : EditorIntent

    /** Moves the selected lane up (-1) or down (+1) among the lanes of its kind; the base never moves. */
    data class MoveSelectedTrack(val delta: Int) : EditorIntent

    /** Jump to the previous / next clip boundary (start or end of a clip), or the timeline start. */
    data object SeekPrevious : EditorIntent
    data object SeekNext : EditorIntent
    data object Undo : EditorIntent
    data object Redo : EditorIntent

    data object ToggleInspector : EditorIntent

    /**
     * Puts the built-in sticker [stickerId] on an overlay lane at the playhead (the selected overlay
     * lane, else the top one, making one above the base if there is none), selects it and opens the inspector.
     */
    data class AddSticker(val stickerId: String) : EditorIntent

    /** Puts a new title on the title track (made if needed) at the playhead, selects it and opens the inspector. */
    data object AddTitle : EditorIntent

    /** Adds a marker at the playhead, or removes the one already there (within a couple of frames). */
    data object ToggleMarkerAtPlayhead : EditorIntent

    /** Removes every detected beat marker; markers placed by hand stay. */
    data object ClearBeatMarkers : EditorIntent

    data object ToggleMarkerSnap : EditorIntent

    /**
     * Finds the beats in the selected clip's audio and marks them on the ruler, replacing the beats
     * that were over that clip. One undo step.
     */
    data object AnalyzeBeats : EditorIntent

    /**
     * Ends the selected base-track clip and every clip after it on the nearest ruler marker, one
     * undo step (see `domain.CutToBeat`).
     */
    data object CutToBeatFromSelected : EditorIntent

    /**
     * Puts the text template [templateId] with [text] at the playhead as one undo step, selects its
     * text and opens the inspector so the text can be changed.
     */
    data class ApplyTextTemplate(val templateId: String, val text: String) : EditorIntent

    /** Applies a text template that may be a saved preset rather than a built-in. */
    data class ApplyPreset(val template: com.ultimatevideo.uveditor.domain.TextTemplate, val text: String) : EditorIntent

    /**
     * Edits of the selected title's text and style: shown live, committed as one undo step by
     * [EndTitleEdit] (or by the next intent of any other kind).
     */
    data class UpdateTitle(val content: TitleContent) : EditorIntent
    data class EndTitleEdit(val commit: Boolean) : EditorIntent

    /** The title editor selected layer [index] of the selected title (-1 for none). */
    data class SelectTitleLayer(val index: Int) : EditorIntent

    /**
     * A step of a pan/pinch/twist on the preview while a layer of a multilayer title is selected: it moves,
     * scales and turns that layer instead of the whole clip. Part of the title edit session, so it ends with
     * [EndAppearanceEdit] like any preview gesture.
     */
    data class LayerGesture(val panX: Double, val panY: Double, val zoom: Double, val rotationDegrees: Double) : EditorIntent

    /** The imported fonts available on this device changed; titles are drawn again. */
    data class FontsChanged(val available: Set<String>) : EditorIntent

    /** Gives the selected title the in and out animation [intro]/[outro] (one undo step; it replaces the clip's keyframes). */
    data class ApplyTitleMotion(val intro: MotionPreset, val outro: MotionPreset) : EditorIntent

    /**
     * Generated captions (title clips) go on a new title track on top, as one undo step. The clips are
     * built elsewhere (see the captions sheet); this only places them.
     */
    data class AddCaptionClips(val clips: List<Clip>, val intoExistingTrack: Boolean = false) : EditorIntent

    /** Puts every generated caption on the timeline in [style], as one undo step. */
    data class RestyleCaptions(val style: com.ultimatevideo.uveditor.domain.captions.CaptionStyle, val canvasHeight: Int) : EditorIntent

    /** A crossfade across the cut between the selected clip and the one right after it. */
    data object AddTransition : EditorIntent
    data class SetTransitionDuration(val frames: Long) : EditorIntent
    data object RemoveTransition : EditorIntent

    /** Changes the look (crossfade, slide, push, zoom, spin, glitch, wipe, whip pan, light leak) of the selected clip's transition. */
    data class SetTransitionStyle(
        val type: com.ultimatevideo.uveditor.domain.TransitionType,
        val direction: com.ultimatevideo.uveditor.domain.TransitionDirection,
    ) : EditorIntent

    /**
     * Edits of the selected clip's look and sound. A session is Begin, any number of Update/Gesture
     * steps (shown live but not yet in the undo history) and End: with `commit` the result becomes
     * one undo step. A [TransformGesture] outside a session starts one by itself.
     */
    data object BeginAppearanceEdit : EditorIntent
    data class UpdateTransform(val transform: ClipTransform) : EditorIntent
    data class UpdateGain(val gainDb: Double) : EditorIntent

    /** One step of a touch gesture on the preview: pan in project canvas pixels, zoom factor, clockwise degrees. */
    data class TransformGesture(val panX: Double, val panY: Double, val zoom: Double, val rotationDegrees: Double) : EditorIntent
    data class EndAppearanceEdit(val commit: Boolean) : EditorIntent

    /** Back to the original placement and unity gain, as one undo step. */
    data object ResetAppearance : EditorIntent

    // Audio tools. The Update* intents are shown and heard live and committed as one undo step by
    // EndAudioEdit (a slider drag sends many of them); toggles send an Update and an End together.
    data class UpdateClipAudio(val audio: ClipAudio) : EditorIntent
    data class UpdateTrackAudio(val trackId: String, val audio: TrackAudio) : EditorIntent
    data class UpdateDucking(val ducking: Ducking?) : EditorIntent
    data class EndAudioEdit(val commit: Boolean = true) : EditorIntent
    data object ResetClipAudio : EditorIntent

    /** Measures the selected clip's loudness and stores the gain that brings it to [targetLufs]. */
    data class NormalizeLoudness(val targetLufs: Double) : EditorIntent
    data object ClearNormalize : EditorIntent

    /** Marks the start or the end of the noise sample at the playhead (inside the selected clip). */
    data class MarkNoiseRegion(val atStart: Boolean) : EditorIntent
    data object ClearNoiseRegion : EditorIntent

    /** Measures the marked region and turns noise suppression on at [strength] (0..1). */
    data class AnalyzeNoise(val strength: Double) : EditorIntent
    data object RemoveNoiseSuppression : EditorIntent
    data object CancelAudioAnalysis : EditorIntent
    data object ToggleMixer : EditorIntent

    /** Anything on the multicam sheet (SPECS.md 9.9); handled by `MulticamController`. */
    data class Multicam(val intent: com.ultimatevideo.uveditor.ui.editor.multicam.MulticamIntent) : EditorIntent

    /**
     * Keyframes of the selected clip, placed at the playhead: add one holding the pose shown there
     * or remove the one that is there; jump to the previous / next one; choose how the animation
     * leaves the keyframe at the playhead; drop the whole animation.
     */
    data object ToggleKeyframe : EditorIntent
    data class JumpToKeyframe(val forward: Boolean) : EditorIntent
    data class SetKeyframeInterpolation(val interpolation: Interpolation) : EditorIntent
    data object ClearKeyframes : EditorIntent

    /**
     * Keyframes of single parameters of the selected clip (effect values, volume, pan, EQ gains, pose
     * components; ids in `domain/ParamIds`). The diamond next to a control toggles a key at the playhead;
     * the keyframe lane drags keys (provisionally, then [EndParamKeyEdit]), changes their curve and copies
     * and pastes them.
     */
    data class ToggleParamKey(val paramId: String) : EditorIntent
    data class JumpToParamKey(val paramId: String, val forward: Boolean) : EditorIntent
    data class ClearParamTrack(val paramId: String) : EditorIntent
    data class CopyParamKeys(val paramId: String) : EditorIntent
    data class PasteParamKeys(val paramId: String) : EditorIntent
    data class SetParamKeyShape(
        val paramId: String,
        val frame: Long,
        val interpolation: Interpolation,
        val out: BezierHandle? = null,
        val inn: BezierHandle? = null,
    ) : EditorIntent

    /** Moves the key at [fromFrame] to [toFrame] with [value]; shown live until [EndParamKeyEdit]. */
    data class UpdateParamKey(val paramId: String, val fromFrame: Long, val toFrame: Long, val value: Double) : EditorIntent
    data class EndParamKeyEdit(val commit: Boolean) : EditorIntent

    /** Which parameter row of the keyframe lane has the key whose curve is being shaped. */
    data class SelectParamKey(val paramId: String?, val frame: Long?) : EditorIntent

    /**
     * Plays the selected clip at [num]/[den] times normal speed (0.1x to 100x). The clip keeps its source
     * range and changes length; later clips on its track follow (slowing down pushes them later,
     * speeding up pulls them earlier).
     */
    data class SetSpeed(val num: Long, val den: Long) : EditorIntent
    data object ToggleReverse : EditorIntent
    data class SetSpeedRamp(val shape: SpeedRampShape) : EditorIntent

    /** Sets the selected clip's speed curve to exactly these keys (the graphical editor); empty removes the curve. One undo step. */
    data class SetSpeedKeys(val keys: List<com.ultimatevideo.uveditor.domain.SpeedKey>) : EditorIntent

    /** Turns smooth slow motion (optical-flow interpolation of the frames of a slowed clip) on or off for the selected clip. */
    data object ToggleSmoothSlowMo : EditorIntent

    /** Holds the frame under the playhead of the selected video clip for a couple of seconds, splitting the clip there. */
    data object FreezeFrame : EditorIntent

    /**
     * Effects, blend mode and mask of the selected clip. Add, remove, reorder, blend and clear are one
     * undo step each. [UpdateEffect] and [UpdateMask] are sliders: shown live, committed as one step
     * by [EndFxEdit] (or by the next intent of any other kind).
     */
    data class AddEffect(val type: EffectType) : EditorIntent
    data class RemoveEffect(val effectId: String) : EditorIntent
    data class MoveEffect(val effectId: String, val toIndex: Int) : EditorIntent
    data class UpdateEffect(val effectId: String, val values: List<Double>) : EditorIntent
    data class SetBlendMode(val mode: BlendMode) : EditorIntent

    /**
     * A drag on a colour grade wheel, slider or curve point: the new values and curves of grade effect
     * [effectId], shown live and committed by [EndFxEdit] like [UpdateEffect].
     */
    data class UpdateGrade(val effectId: String, val values: List<Double>, val curves: GradeCurves?) : EditorIntent

    /** Applies a saved look or a pasted grade to the selected clip in one undo step (replaces its grade, or adds one). */
    data class ApplyGrade(val values: List<Double>, val curves: GradeCurves?) : EditorIntent

    /** Reads the selected video clip's source as this colour space; null goes back to what its file says. */
    data class SetClipColor(val space: com.ultimatevideo.uveditor.domain.SourceColorSpace?) : EditorIntent
    data class UpdateMask(val mask: ClipMask?) : EditorIntent

    /** Turns the stabiliser on the selected video clip on with these settings, or off with null; one undo step. */
    data class SetStabilise(val stabilise: com.ultimatevideo.uveditor.domain.Stabilise?) : EditorIntent

    /** Analyses the selected clip's camera motion (background, cancellable); needed once per file. */
    data object AnalyseStabilise : EditorIntent
    data object CancelStabilise : EditorIntent

    /** The inspector shows a video clip: reads its analysis status and makes its correction table available. */
    data object RefreshStabilise : EditorIntent

    /** Re-reads the selected clip's motion tracks and their analysis status for the inspector and the preview overlay. */
    data object RefreshTrack : EditorIntent

    /** Waits for the user to tap a point or drag a box on the preview to track on the selected video clip. */
    data object BeginTrackPick : EditorIntent
    data object CancelTrackPick : EditorIntent

    /** Side of the box a tap makes, as a fraction of the frame height. */
    data class SetTrackBox(val side: Double) : EditorIntent

    /**
     * The user's pick on the preview, in canvas pixels from the canvas centre: a tap ([w] and [h] null) or the centre and
     * size of a dragged box. Adds a motion track at the playhead's frame and starts its analysis.
     */
    data class PickTrackTarget(val x: Double, val y: Double, val w: Double?, val h: Double?) : EditorIntent

    data object CancelTrack : EditorIntent
    data class ReanalyseTrack(val trackId: String) : EditorIntent
    data class RemoveMotionTrack(val trackId: String) : EditorIntent

    /** Shows the path of [trackId] over the preview, or hides it with null. */
    data class ShowTrack(val trackId: String?) : EditorIntent

    /** Makes the selected clip follow [trackId]: position keyframes along the path, one undo step. */
    data class FollowTrack(val trackId: String) : EditorIntent
    data class EndFxEdit(val commit: Boolean) : EditorIntent
    data object ClearFx : EditorIntent

    /** Opens the LUT picker for the selected clip. */
    data object OpenLutPicker : EditorIntent
    data object CloseLutPicker : EditorIntent

    /** Adds the library LUT [key] to the selected clip as an effect. */
    data class AddLut(val key: Int) : EditorIntent

    /** Outline the safe zones of an app over the preview; null turns the overlay off. */
    data class SetSafeZone(val platform: SafeZonePlatform?) : EditorIntent

    data object ShowCanvasDialog : EditorIntent
    data object DismissCanvasDialog : EditorIntent

    /**
     * Changes the project canvas (resolution and aspect). Positions are rescaled so clips keep their
     * relative place. The undo history is cleared, because earlier steps were made on another canvas.
     */
    data class ChangeCanvas(val width: Int, val height: Int) : EditorIntent

    /**
     * Switches the project between SDR and HDR (HLG). Nothing in the timeline changes: media are
     * converted to the project colour space when they are drawn, so this is saved and undone by
     * switching back, not through the edit history.
     */
    data class ChangeColorSpace(val space: ProjectColorSpace) : EditorIntent

    data class ImportMedia(val uris: List<String>) : EditorIntent
    data class AddAsset(val assetId: String) : EditorIntent

    /** Show or hide the list of unreadable media with a Relink button for each. */
    data object ShowRelink : EditorIntent
    data object HideRelink : EditorIntent

    /** The user chose Relink for [assetId]: ask the screen to open the file picker. */
    data class RequestRelink(val assetId: String) : EditorIntent

    /** The picker returned [uri] as the replacement for [assetId]. */
    data class RelinkAsset(val assetId: String, val uri: String) : EditorIntent

    /** Try saving again after a failed autosave. */
    data object RetrySave : EditorIntent

    /** Leave the editor even though the last save failed; the unsaved changes are lost. */
    data object LeaveWithoutSaving : EditorIntent

    /** Save now (app going to background). */
    data object Flush : EditorIntent

    /** Save, then close the editor. */
    data object Back : EditorIntent

    data class ReportError(val message: String) : EditorIntent
}

sealed interface EditorEffect : UiEffect {
    data class ShowMessage(val text: String) : EditorEffect
    data object Close : EditorEffect

    /** Open the document picker to choose a replacement for [assetId]. */
    data class LaunchRelinkPicker(val assetId: String) : EditorEffect

    /** Open the "create document" picker to choose where the [kind] export is written; the answer is [LibraryIntent.ExportTo]. */
    data class LaunchInterchangePicker(val kind: InterchangeKind, val suggestedFileName: String, val mime: String) : EditorEffect
}

/**
 * The stabiliser's state for the selected clip: whether its file has been analysed ([status]) and, while the
 * analysis runs, how far it is ([progress], 0..1; null when nothing runs).
 */
data class StabUiState(
    val clipId: String? = null,
    val status: com.ultimatevideo.uveditor.engine.stabilise.StabStatus = com.ultimatevideo.uveditor.engine.stabilise.StabStatus.Off,
    val progress: Float? = null,
)

/** A motion track of the selected clip and where its analysis stands. */
data class TrackItem(val track: com.ultimatevideo.uveditor.domain.MotionTrack, val status: com.ultimatevideo.uveditor.engine.track.TrackStatus)

/** A motion track of another clip that the selected clip can follow; [ready] when its analysis covers that clip. */
data class FollowItem(val track: com.ultimatevideo.uveditor.domain.MotionTrack, val ready: Boolean)

/**
 * Motion tracking (SPECS.md 9.15) for the selected clip: [canTrack] when it is a video clip, its [items], the tracks of
 * other clips it can [followable] follow, whether the preview is waiting for a pick ([picking], with the tap's box
 * [boxSide]), the analysis [progress] (null when none runs, [analysingId] says which), the [activeId] track drawn on
 * the preview and its canvas-space [overlay].
 */
data class TrackUiState(
    val clipId: String? = null,
    val canTrack: Boolean = false,
    val items: List<TrackItem> = emptyList(),
    val followable: List<FollowItem> = emptyList(),
    val picking: Boolean = false,
    val boxSide: Double = com.ultimatevideo.uveditor.domain.TrackSeed.DEFAULT_SIDE,
    val progress: Float? = null,
    val analysingId: String? = null,
    val activeId: String? = null,
    val overlay: List<com.ultimatevideo.uveditor.domain.TrackMath.CanvasPoint> = emptyList(),
)

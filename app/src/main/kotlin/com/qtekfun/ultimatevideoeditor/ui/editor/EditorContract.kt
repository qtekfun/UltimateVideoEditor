package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.BezierHandle
import com.qtekfun.ultimatevideoeditor.domain.BlendMode
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipAudio
import com.qtekfun.ultimatevideoeditor.domain.ClipMask
import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import com.qtekfun.ultimatevideoeditor.domain.DropPlan
import com.qtekfun.ultimatevideoeditor.domain.Ducking
import com.qtekfun.ultimatevideoeditor.domain.EffectType
import com.qtekfun.ultimatevideoeditor.domain.GradeCurves
import com.qtekfun.ultimatevideoeditor.domain.Interpolation
import com.qtekfun.ultimatevideoeditor.domain.Keyframes
import com.qtekfun.ultimatevideoeditor.domain.MotionPreset
import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TrackAudio
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.mvi.UiEffect
import com.qtekfun.ultimatevideoeditor.mvi.UiIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetKind
import com.qtekfun.ultimatevideoeditor.ui.text.UiText

sealed interface EditorIntent : UiIntent {
    data class TapTimeline(val hit: TimelineHit) : EditorIntent
    data class SetPlayhead(val frame: Long) : EditorIntent

    /** A swipe along the time axis began on the timeline content: playback, if running, stops where it is (see [ScrubGate]). */
    data object ScrubStarted : EditorIntent

    data class DragStart(val hit: TimelineHit) : EditorIntent
    /** [trackIndex] is the lane under the finger in the timeline being shown (-1 over a gap or nothing). */
    data class DragMove(
        val frame: Long,
        val trackIndex: Int,
        val zone: DragZone = DragZone.LANES,
        /** How many frames a fingertip covers at the current zoom: how close to a cut still counts as at the cut. */
        val reachFrames: Long = DropPlan.INSERT_RADIUS_FRAMES,
    ) : EditorIntent
    /** Switches the dragged clip's drop between insert and overwrite (the mode chip, or a tap with a second finger). */
    data object FlipDropChoice : EditorIntent

    /** The toolbar button: Auto, then Insert, then Overwrite, then Auto again. */
    data object CycleDropMode : EditorIntent
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
    data class ApplyPreset(val template: com.qtekfun.ultimatevideoeditor.domain.TextTemplate, val text: String) : EditorIntent

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
    data class RestyleCaptions(val style: com.qtekfun.ultimatevideoeditor.domain.captions.CaptionStyle, val canvasHeight: Int) : EditorIntent

    /** A crossfade across the cut between the selected clip and the one right after it. */
    data object AddTransition : EditorIntent
    data class SetTransitionDuration(val frames: Long) : EditorIntent
    data object RemoveTransition : EditorIntent

    /** Changes the look (crossfade, slide, push, zoom, spin, glitch, wipe, whip pan, light leak) of the selected clip's transition. */
    data class SetTransitionStyle(
        val type: com.qtekfun.ultimatevideoeditor.domain.TransitionType,
        val direction: com.qtekfun.ultimatevideoeditor.domain.TransitionDirection,
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
    data class Multicam(val intent: com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamIntent) : EditorIntent

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
    data class SetSpeedKeys(val keys: List<com.qtekfun.ultimatevideoeditor.domain.SpeedKey>) : EditorIntent

    /** Turns smooth slow motion (optical-flow interpolation of the frames of a slowed clip) on or off for the selected clip. */
    data object ToggleSmoothSlowMo : EditorIntent

    /** Holds the frame under the playhead of the selected video clip for a couple of seconds, splitting the clip there. */
    data object FreezeFrame : EditorIntent

    /**
     * Detaches the sound of the selected video clip onto an audio lane (the first lane with room, else a new one), linked to the clip
     * so they move, trim, split and delete together. The video clip goes silent. One undo step.
     */
    data object DetachAudio : EditorIntent

    /** Unlinks the selected clip from its detached audio (or video): from then on they are edited independently. */
    data object UnlinkAudio : EditorIntent

    /**
     * Links the selected clip with the unlinked clip of the same source ([com.qtekfun.ultimatevideoeditor.domain.ClipLinks.LinkInfo.relinkCandidateId]).
     * With [realign] the audio clip first moves back to where it plays in sync with the video.
     */
    data class RelinkAudio(val realign: Boolean) : EditorIntent

    /** Gives the selected video clip (or the video clip linked to the selected audio) its own sound back; the linked audio clip is removed. */
    data object RestoreEmbeddedAudio : EditorIntent

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
    /** The project's HDR brightness: dim HDR footage whose diffuse level is above reference white (Comfortable) or keep it as recorded. */
    data class SetHdrBrightness(val brightness: com.qtekfun.ultimatevideoeditor.domain.HdrBrightness) : EditorIntent

    /** Turns the selected clips' automatic HDR level on or off; null goes back to the project's setting. */
    data class SetClipHdrLevel(val on: Boolean?) : EditorIntent

    data class SetClipColor(val space: com.qtekfun.ultimatevideoeditor.domain.SourceColorSpace?) : EditorIntent
    data class UpdateMask(val mask: ClipMask?) : EditorIntent

    /** Turns the stabiliser on the selected video clip on with these settings, or off with null; one undo step. */
    data class SetStabilise(val stabilise: com.qtekfun.ultimatevideoeditor.domain.Stabilise?) : EditorIntent

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

    /** Adds the files, each starting where the last ended: from the playhead, or with [atEnd] from the end of the timeline. */
    data class ImportMedia(val uris: List<String>, val atEnd: Boolean = false) : EditorIntent
    data class AddAsset(val assetId: String) : EditorIntent

    /**
     * A voice-over take recorded in the app: the file at [uri] goes on the first audio lane that is free for its whole length (or a new
     * one), starting at [startFrame], with its first [trimMs] milliseconds cut off.
     */
    data class PlaceVoiceOver(val uri: String, val startFrame: Long, val trimMs: Long) : EditorIntent

    /** Adds the assets one after the other at the playhead, in this order, as one undo step (the tray's numbered selection). */
    data class AddAssets(val assetIds: List<String>) : EditorIntent

    /** Show or hide the list of unreadable media with a Relink button for each. */
    data object ShowRelink : EditorIntent
    data object HideRelink : EditorIntent

    /** The user chose Relink for [assetId]: ask the screen to open the file picker. */
    data class RequestRelink(val assetId: String) : EditorIntent

    /** The picker returned [uri] as the replacement for [assetId]. */
    data class RelinkAsset(val assetId: String, val uri: String) : EditorIntent

    /** The user chose "Scan a folder": ask the screen to open the folder picker. */
    data object RequestFolderRelink : EditorIntent

    /** The folder picker returned [treeUri]: look in it for every missing file and relink what is found. */
    data class RelinkFromFolder(val treeUri: String) : EditorIntent

    /** Looks in each of these folders, one after the other, for what is still missing (the folders the user linked), and reports once. */
    data class RelinkFromFolders(val treeUris: List<String>) : EditorIntent

    /** Stop a running folder scan; nothing is changed. */
    data object CancelFolderRelink : EditorIntent

    /** Close the results of a folder scan and go back to the list of what is still missing. */
    data object DismissFolderRelink : EditorIntent

    /** The user chose [uri], one of the files the folder scan found for [assetId], among several of the same name. */
    data class RelinkFromCandidate(val assetId: String, val uri: String) : EditorIntent

    /**
     * A decoder or the mixer failed on the file with native key [assetKey] while the project was open ([detail] says how). The
     * editor asks the file system whether it is still readable: if not (a drive was pulled) the asset becomes missing at once,
     * once per loss; if it is, [detail] is shown as an ordinary error.
     */
    data class MediaFailureReported(val assetKey: Long, val detail: String) : EditorIntent

    /** "Check again" in the Missing media dialog: verify the unreadable files and clear the flag of those that can be read now. */
    data object RecheckMissingMedia : EditorIntent

    /** Try saving again after a failed autosave. */
    data object RetrySave : EditorIntent

    /** Leave the editor even though the last save failed; the unsaved changes are lost. */
    data object LeaveWithoutSaving : EditorIntent

    /** Save now (app going to background). */
    data object Flush : EditorIntent

    /** Save, then close the editor. */
    data object Back : EditorIntent

    data class ReportError(val message: String) : EditorIntent

    /** A message already in resource form, shown like [ReportError] in the language in use. */
    data class ReportText(val text: UiText) : EditorIntent
}

sealed interface EditorEffect : UiEffect {
    data class ShowMessage(val text: UiText) : EditorEffect {
        /** Text that is data or comes from a lower layer in English (shown as it is). */
        constructor(text: String) : this(UiText.Raw(text))
    }
    data object Close : EditorEffect

    /** A voice-over take is on the timeline: the recorder panel offers undo and another take. */
    data object VoiceOverPlaced : EditorEffect

    /** Open the document picker to choose a replacement for [assetId]. */
    data class LaunchRelinkPicker(val assetId: String) : EditorEffect

    /** The file with native key [assetKey] just became unreadable: the screen closes its decoders so nothing holds the vanished file. */
    data class AssetUnavailable(val assetKey: Long) : EditorEffect

    /** Open the folder picker to choose where to look for the missing media. */
    data object LaunchFolderPicker : EditorEffect

    /** Open the "create document" picker to choose where the [kind] export is written; the answer is [LibraryIntent.ExportTo]. */
    data class LaunchInterchangePicker(val kind: InterchangeKind, val suggestedFileName: String, val mime: String) : EditorEffect
}

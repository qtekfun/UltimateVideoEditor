package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.data.model.MediaAssetDto
import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframe
import com.ultimatevideo.uveditor.domain.Keyframes
import com.ultimatevideo.uveditor.domain.Timeline
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.mvi.UiEffect
import com.ultimatevideo.uveditor.mvi.UiIntent
import com.ultimatevideo.uveditor.mvi.UiState

data class EditorState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    val projectName: String = "",
    val fps: FrameRate = FrameRate(30, 1),
    /** Project resolution: the canvas the preview composites on and clip positions are measured in. */
    val canvasWidth: Int = 1920,
    val canvasHeight: Int = 1080,
    /** Committed timeline; only changes through the undo history. */
    val timeline: Timeline = Timeline(),
    /** Provisional timeline while a clip is being dragged; discarded or committed on release. */
    val dragPreview: Timeline? = null,
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
    /** Which app's safe zones are outlined over the preview, or null for none. */
    val safeZone: SafeZonePlatform? = null,
    /** The "change canvas" dialog is open. */
    val canvasDialogOpen: Boolean = false,
) : UiState {
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

    /** The selected clip if it is something drawn on the canvas: a video clip or a title. */
    val selectedVisualClip: Clip?
        get() {
            val id = selectedClipId ?: return null
            val track = visibleTimeline.trackOfClip(id)?.takeIf { it.type != TrackType.AUDIO } ?: return null
            return track.clip(id)
        }

    /** The text and style of the selected clip when it is a title. */
    val selectedTitle: TitleContent? get() = selectedClip?.title

    /** The clip that starts exactly where the selected one ends on its track: the other side of a cut. */
    val clipAfterSelected: Clip?
        get() {
            val clip = selectedClip ?: return null
            return visibleTimeline.trackOfClip(clip.id)?.clips?.firstOrNull { it.timelineStart == clip.timelineEnd }
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

sealed interface EditorIntent : UiIntent {
    data class TapTimeline(val hit: TimelineHit) : EditorIntent
    data class SetPlayhead(val frame: Long) : EditorIntent

    data class DragStart(val hit: TimelineHit) : EditorIntent
    data class DragMove(val frame: Long, val trackIndex: Int) : EditorIntent
    data class DragEnd(val commit: Boolean) : EditorIntent

    data object SplitAtPlayhead : EditorIntent
    data object RippleDeleteSelected : EditorIntent
    data object RippleAppendSelected : EditorIntent
    data object TogglePlay : EditorIntent

    data class AddTrack(val type: TrackType) : EditorIntent
    data object RemoveSelectedTrack : EditorIntent

    /** Jump to the previous / next clip boundary (start or end of a clip), or the timeline start. */
    data object SeekPrevious : EditorIntent
    data object SeekNext : EditorIntent
    data object Undo : EditorIntent
    data object Redo : EditorIntent

    data object ToggleInspector : EditorIntent

    /** Puts a new title on the title track (made if needed) at the playhead, selects it and opens the inspector. */
    data object AddTitle : EditorIntent

    /**
     * Edits of the selected title's text and style: shown live, committed as one undo step by
     * [EndTitleEdit] (or by the next intent of any other kind).
     */
    data class UpdateTitle(val content: TitleContent) : EditorIntent
    data class EndTitleEdit(val commit: Boolean) : EditorIntent

    /** A crossfade across the cut between the selected clip and the one right after it. */
    data object AddTransition : EditorIntent
    data class SetTransitionDuration(val frames: Long) : EditorIntent
    data object RemoveTransition : EditorIntent

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

    /**
     * Keyframes of the selected clip, placed at the playhead: add one holding the pose shown there
     * or remove the one that is there; jump to the previous / next one; choose how the animation
     * leaves the keyframe at the playhead; drop the whole animation.
     */
    data object ToggleKeyframe : EditorIntent
    data class JumpToKeyframe(val forward: Boolean) : EditorIntent
    data class SetKeyframeInterpolation(val interpolation: Interpolation) : EditorIntent
    data object ClearKeyframes : EditorIntent

    /** Outline the safe zones of an app over the preview; null turns the overlay off. */
    data class SetSafeZone(val platform: SafeZonePlatform?) : EditorIntent

    data object ShowCanvasDialog : EditorIntent
    data object DismissCanvasDialog : EditorIntent

    /**
     * Changes the project canvas (resolution and aspect). Positions are rescaled so clips keep their
     * relative place. The undo history is cleared, because earlier steps were made on another canvas.
     */
    data class ChangeCanvas(val width: Int, val height: Int) : EditorIntent

    data class ImportMedia(val uris: List<String>) : EditorIntent
    data class AddAsset(val assetId: String) : EditorIntent

    /** Save now (app going to background). */
    data object Flush : EditorIntent

    /** Save, then close the editor. */
    data object Back : EditorIntent

    data class ReportError(val message: String) : EditorIntent
}

sealed interface EditorEffect : UiEffect {
    data class ShowMessage(val text: String) : EditorEffect
    data object Close : EditorEffect
}

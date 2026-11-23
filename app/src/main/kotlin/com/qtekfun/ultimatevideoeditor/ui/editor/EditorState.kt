package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.data.MediaProblem
import com.qtekfun.ultimatevideoeditor.data.MissingAsset
import com.qtekfun.ultimatevideoeditor.data.MissingMedia
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.ClipDeletion
import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import com.qtekfun.ultimatevideoeditor.domain.DropChoice
import com.qtekfun.ultimatevideoeditor.domain.DropHint
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.HdrLevels
import com.qtekfun.ultimatevideoeditor.domain.Keyframe
import com.qtekfun.ultimatevideoeditor.domain.Keyframes
import com.qtekfun.ultimatevideoeditor.domain.ParamKey
import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import com.qtekfun.ultimatevideoeditor.domain.Timeline
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.displayedAt
import com.qtekfun.ultimatevideoeditor.domain.TitleLayerEdit
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.Transition
import com.qtekfun.ultimatevideoeditor.mvi.UiState
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlin.math.abs

/** A stretch of clip [clipId], in clip frames [start, end), marked as the noise sample. */
data class NoiseRegion(val clipId: String, val startFrame: Long?, val endFrame: Long?) {
    val isComplete: Boolean get() = startFrame != null && endFrame != null && endFrame > startFrame
}

/** Keys copied from the track of [paramId], with frames relative to the first key; pasted at the playhead. */
data class ParamClipboard(val paramId: String, val keys: List<ParamKey>)

/** The two clips either side of a cut that can take a transition. */
data class TransitionCut(val from: Clip, val to: Clip)

/** How far from a cut the playhead may be and still count as "on" it for the transition button. */
const val TRANSITION_NEAR_FRAMES = 6L

data class EditorState(
    val isLoading: Boolean = true,
    val loadError: UiText? = null,
    val projectName: String = "",
    val fps: FrameRate = FrameRate(30, 1),
    /** Project resolution: the canvas the preview composites on and clip positions are measured in. */
    val canvasWidth: Int = 1920,
    val canvasHeight: Int = 1080,
    /** Colour space the project is composited and exported in; HDR (HLG) or SDR. */
    val colorSpace: ProjectColorSpace = ProjectColorSpace.REC709_SDR,
    /** The project's HDR brightness setting and the diffuse levels measured so far; the preview and the exporter read the gain from it. */
    val hdr: HdrLevels = HdrLevels(),
    /** Committed timeline; only changes through the undo history. */
    val timeline: Timeline = Timeline(),
    /** Provisional timeline while a clip is being dragged; discarded or committed on release. */
    val dragPreview: Timeline? = null,
    /**
     * What releasing the dragged clip would do, drawn over the timeline during the drag. Decided by
     * [com.qtekfun.ultimatevideoeditor.domain.DropPlan], the same function that runs the drop; its lane id
     * refers to a lane of [visibleTimeline].
     */
    val dropHint: DropHint? = null,
    /** The user's override of the drop action while a clip is dragged (the mode chip); [DropChoice.AUTO] again after every drag. */
    val dropChoice: DropChoice = DropChoice.AUTO,
    /** The standing drop mode of the toolbar button: [DropChoice.AUTO] decides by position; INSERT or OVERWRITE force it for every drag. */
    val dropMode: DropChoice = DropChoice.AUTO,
    /** True while the drop is an insert or an overwrite the user may switch, so the timeline shows the mode chip. */
    val dropChoiceOffered: Boolean = false,
    /** The clips being dragged or trimmed and the frame an edge snapped to, drawn by the timeline while the drag lasts. */
    val dragOverlay: DragOverlay? = null,
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
    /** False from the moment a project opens until every library file has been probed once; see [playableAssets]. */
    val mediaChecked: Boolean = true,
    /** The relink list is open. */
    val relinkOpen: Boolean = false,
    /** The "scan a folder" relink: idle, running (with progress) or finished (with its results). */
    val folderRelink: FolderRelinkUi = FolderRelinkUi.Idle,
    /** The last autosave failed with this message; the project on disk is older than what is on screen. */
    val saveError: UiText? = null,
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
    /** The HSL qualifier's eyedropper: armed on an effect while the preview waits for a tap. */
    val qualifierPick: QualifierPickState = QualifierPickState(),
    /** A lane picked up by its header and where it would land; null when no lane header drag is running. */
    val laneDrag: LaneDrag? = null,
    /** The selected clips when more than one is selected (includes [selectedClipId]); read them through [selection]. */
    val selectedClipIds: Set<String> = emptySet(),
    /** How many clips the clipboard holds. */
    val clipboardCount: Int = 0,
    /** The question asked by Paste when another clip is selected: content or style; null when none is open. */
    val pasteChoice: PasteChoice? = null,
    /** A slider drag on an audio tool is in progress: [dragPreview] holds it and the mixer plays it live. */
    val audioSessionActive: Boolean = false,
    /** What the audio analysis in progress is doing ("Measuring loudness…"), or null when idle. */
    val audioBusy: UiText? = null,
    /** The quiet region (clip frames) marked on the selected clip as the noise sample, if any. */
    val noiseRegion: NoiseRegion? = null,
    /** The track mixer sheet (volume, mute, solo, role, compressor, ducking) is open. */
    val mixerOpen: Boolean = false,
    /** The media library sheet (tags, notes, usage, cleanup, exports to other tools). */
    val library: LibraryUiState = LibraryUiState(),
    /** The marker popup, or null when closed. */
    val markerPopup: MarkerPopup? = null,
    /** The flag list (shot list) sheet is open. */
    val flagListOpen: Boolean = false,
    /** "Marker added" confirmation shown for a moment after a one-tap marker. */
    val markerHint: MarkerHint? = null,
    /** Keys copied from a parameter's track (frames relative to the first key), ready to paste at the playhead. */
    val paramClipboard: ParamClipboard? = null,
    /** The key of the keyframe lane whose curve controls are shown: parameter and clip frame. */
    val selectedParamKey: Pair<String, Long>? = null,
    /** Silence auto cut and manual reframe sheets (SPECS.md 9.16). */
    val quickEdits: QuickEditsUiState = QuickEditsUiState(),
    /** The multicam sheet: angles being picked and synced, and live cutting between the angles of a multicam clip. */
    val multicam: com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamUiState = com.qtekfun.ultimatevideoeditor.ui.editor.multicam.MulticamUiState(),
) : UiState {
    /** The timeline the mixer plays: the committed one, or the live audio edit while a slider is dragged. */
    val audioSource: Timeline get() = if (audioSessionActive) visibleTimeline else timeline

    /** The unreadable files, with how many clips depend on each. */
    val missingAssets: List<MissingAsset> get() = MissingMedia.summarize(timeline, assets, missingMedia)

    /** The unreadable files themselves: what the preview draws "Media missing" cards for. */
    val unreadableAssets: List<MediaAssetDto> get() = if (missingMedia.isEmpty()) emptyList() else assets.filter { it.id in missingMedia }

    /** The library without the unreadable files: what the preview, the mixer and the thumbnails may open. */
    val playableAssets: List<MediaAssetDto> get() = when {
        !mediaChecked -> emptyList()  // nothing is opened before the verification has said which files are readable
        missingMedia.isEmpty() -> assets
        else -> assets.filter { it.id !in missingMedia }
    }

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

    /**
     * The cut the toolbar's transition button would add a transition to, or null when there is none (the button is then
     * disabled). A cut is two clips on the same lane where one ends exactly where the next starts, and it must not have a
     * transition yet. Rule, in order:
     * 1. a selected clip: the cut after it, otherwise the cut before it (so selecting either side of a cut works);
     * 2. nothing usable selected: a cut on a picture lane (video or title, not audio) within [TRANSITION_NEAR_FRAMES] of
     *    the playhead, the nearest first.
     */
    val transitionCut: TransitionCut?
        get() {
            val timeline = visibleTimeline
            fun free(from: Clip, to: Clip) = timeline.transitionBetween(from.id, to.id) == null
            selectedClip?.let { clip ->
                val clips = timeline.trackOfClip(clip.id)?.clips.orEmpty()
                val after = clips.firstOrNull { it.timelineStart == clip.timelineEnd }?.takeIf { free(clip, it) }
                if (after != null) return TransitionCut(clip, after)
                val before = clips.firstOrNull { it.timelineEnd == clip.timelineStart }?.takeIf { free(it, clip) }
                if (before != null) return TransitionCut(before, clip)
            }
            return timeline.tracks.asSequence()
                .filter { it.type != TrackType.AUDIO }
                .flatMap { track ->
                    track.clips.asSequence().mapNotNull { from ->
                        val to = track.clips.firstOrNull { it.timelineStart == from.timelineEnd } ?: return@mapNotNull null
                        TransitionCut(from, to).takeIf { free(from, to) && abs(playhead - to.timelineStart) <= TRANSITION_NEAR_FRAMES }
                    }
                }
                .minByOrNull { abs(playhead - it.to.timelineStart) }
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

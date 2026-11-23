package com.qtekfun.ultimatevideoeditor.ui.editor.appearance

import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.data.model.MediaAssetDto
import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.EditCommand
import com.qtekfun.ultimatevideoeditor.domain.Keyframes
import com.qtekfun.ultimatevideoeditor.domain.MotionTrack
import com.qtekfun.ultimatevideoeditor.domain.Track
import com.qtekfun.ultimatevideoeditor.domain.TrackMath
import com.qtekfun.ultimatevideoeditor.domain.TrackSeed
import com.qtekfun.ultimatevideoeditor.domain.TrackType
import com.qtekfun.ultimatevideoeditor.domain.sourceFrameAtProjectFrame
import com.qtekfun.ultimatevideoeditor.engine.track.MotionTracker
import com.qtekfun.ultimatevideoeditor.engine.track.TrackOutcome
import com.qtekfun.ultimatevideoeditor.engine.track.TrackStatus
import com.qtekfun.ultimatevideoeditor.ui.editor.*
import com.qtekfun.ultimatevideoeditor.ui.editor.playback.PlaybackController
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Motion tracking: picking a point or box on a video clip, running the tracker, showing the result as an overlay and making a title or
 * sticker follow a track (SPECS.md 9.15).
 */
internal class MotionTrackHandler(
    host: EditorHost,
    private val motionTracker: MotionTracker,
    private val trackDispatcher: CoroutineDispatcher,
    private val playback: PlaybackController,
) : EditorHost by host {

    private var trackJob: Job? = null

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
    fun refreshTrack() {
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
        scope.launch(trackDispatcher) {
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

    fun beginTrackPick() {
        val found = trackableSelection()
        if (found == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_video_clip_to)))
            return
        }
        val clip = found.first
        val playhead = state.value.playhead
        if (playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_onto_the)))
            return
        }
        playback.pausePlayback()
        reduce { copy(track = track.copy(picking = true)) }
    }

    /**
     * The pick on the preview ([x], [y] in canvas pixels from the centre, a box when [w] and [h] are given) becomes a
     * seed in the clip's own picture at the playhead's source frame, is remembered as a motion track (one undo step)
     * and analysed in the background.
     */
    fun pickTrackTarget(x: Double, y: Double, w: Double?, h: Double?) {
        val found = trackableSelection()
        if (found == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_a_video_clip_to)))
            return
        }
        val (clip, asset) = found
        val s = state.value
        val playhead = s.playhead
        if (playhead < clip.timelineStart || playhead >= clip.timelineEnd) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_move_the_playhead_onto_the)))
            return
        }
        scope.launch {
            val aspect = withContext(trackDispatcher) { motionTracker.frameAspect(asset) } ?: (s.canvasWidth.toDouble() / s.canvasHeight)
            val pose = Keyframes.evaluate(clip.keyframes, playhead.value - clip.timelineStart.value, clip.transform)
            val (u, v) = TrackMath.fromCanvas(x, y, aspect, s.canvasWidth, s.canvasHeight, pose)
            if (u !in 0.0..1.0 || v !in 0.0..1.0) {
                emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_tap_on_the_picture_of)))
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
            val motion = MotionTrack("mt-${idGenerator()}", clip.id, "Track ${existing + 1}", seed) // i18n-ok: a stored default name, worded when shown
            if (!execute(EditCommand.AddMotionTrack(motion))) return@launch
            reduce { copy(track = track.copy(picking = false, activeId = motion.id)) }
            startTrackAnalysis(motion.id)
        }
    }

    fun startTrackAnalysis(trackId: String) {
        if (trackJob?.isActive == true) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_another_tracking_analysis_is_running)))
            return
        }
        val motion = history.timeline.motionTrack(trackId) ?: return
        val (clip, asset) = clipWithAsset(motion.clipId) ?: return
        val fps = state.value.fps
        reduce { copy(track = track.copy(progress = 0f, analysingId = trackId)) }
        trackJob = scope.launch {
            val outcome = motionTracker.analyse(asset, clip, motion, fps) { progress -> reduce { copy(track = track.copy(progress = progress)) } }
            when (outcome) {
                is TrackOutcome.Failed -> emit(EditorEffect.ShowMessage(outcome.message))
                TrackOutcome.Cancelled -> emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_tracking_cancelled)))
                TrackOutcome.Done -> Unit
            }
            reduce { copy(track = track.copy(progress = null, analysingId = null, activeId = if (outcome == TrackOutcome.Done) trackId else track.activeId)) }
            refreshTrack()
        }
    }

    fun removeMotionTrack(trackId: String) {
        val motion = history.timeline.motionTrack(trackId) ?: return
        val owner = clipWithAsset(motion.clipId)
        if (!execute(EditCommand.RemoveMotionTrack(trackId))) return
        if (owner != null) scope.launch(trackDispatcher) { motionTracker.forget(owner.second, motion) }
        if (state.value.track.activeId == trackId) reduce { copy(track = track.copy(activeId = null, overlay = emptyList())) }
        refreshTrack()
    }

    /** Position keyframes along the tracked path for the selected clip, replacing its keyframes in one undo step. */
    fun followTrack(trackId: String) {
        val selectedId = state.value.selectedClipId
        val attached = selectedId?.let { history.timeline.trackOfClip(it)?.clip(it) }
        val motion = history.timeline.motionTrack(trackId)
        if (attached == null || motion == null) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_select_the_clip_that_should)))
            return
        }
        if (attached.id == motion.clipId) {
            emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_a_clip_cannot_follow_its)))
            return
        }
        val (trackedClip, asset) = clipWithAsset(motion.clipId) ?: return
        val s = state.value
        scope.launch {
            val path = withContext(trackDispatcher) { motionTracker.load(asset, motion, s.fps) }
            if (path == null) {
                emit(EditorEffect.ShowMessage(UiText.res(R.string.ed_vm_analyse_first, trackNameText(motion.name))))
                return@launch
            }
            val keys = TrackMath.attachKeyframes(path, trackedClip, attached, s.canvasWidth, s.canvasHeight)
            if (execute(EditCommand.AttachToMotionTrack(attached.id, keys))) {
                emit(
                    EditorEffect.ShowMessage(
                        if (path.lostCount > 0) UiText.res(R.string.ed_vm_now_follows_lost, trackNameText(motion.name), keys.size, path.lostCount)
                        else UiText.res(R.string.ed_vm_now_follows_keyframes, trackNameText(motion.name), keys.size),
                    ),
                )
            }
        }
    }
}

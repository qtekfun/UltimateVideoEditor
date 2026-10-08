package com.qtekfun.ultimatevideoeditor.domain

import kotlin.math.abs
import kotlin.math.max

/**
 * Manual reframing for a canvas whose shape differs from the footage (16:9 footage on a 9:16 canvas):
 * the user says which point of the picture matters and the clip is scaled to fill the canvas and moved so
 * that point sits in the middle, as far as the picture allows. It writes ordinary position and scale
 * keyframes, so the result is editable afterwards and preview and export draw it identically.
 * There is no subject detection: the point comes from the user, one per moment they want to follow.
 */
object Reframe {
    const val MIN_ZOOM = 1.0
    const val MAX_ZOOM = 4.0

    /**
     * The pose that fills the canvas with the clip's frame (cover, times [zoom]) and centres the point
     * ([u], [v]) of the frame (fractions, 0.5 is the middle) on the canvas. The offset is limited so the
     * picture never leaves an edge uncovered. Opacity and the other fields come from [base].
     */
    fun poseFor(
        u: Double,
        v: Double,
        aspect: Double,
        canvasWidth: Int,
        canvasHeight: Int,
        zoom: Double,
        base: ClipTransform,
    ): ClipTransform {
        val (fitW, fitH) = TrackMath.fitSize(aspect, canvasWidth, canvasHeight)
        val cover = max(canvasWidth / fitW, canvasHeight / fitH)
        val scale = cover * zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val shownW = fitW * scale
        val shownH = fitH * scale
        val maxX = max(0.0, (shownW - canvasWidth) / 2.0)
        val maxY = max(0.0, (shownH - canvasHeight) / 2.0)
        val x = (-(u.coerceIn(0.0, 1.0) - 0.5) * shownW).coerceIn(-maxX, maxX)
        val y = (-(v.coerceIn(0.0, 1.0) - 0.5) * shownH).coerceIn(-maxY, maxY)
        return base.copy(positionX = x, positionY = y, scaleX = scale, scaleY = scale, rotationDegrees = 0.0)
    }

    /** True when the pose [poseFor] gives for the same inputs covers the whole canvas. */
    fun covers(pose: ClipTransform, aspect: Double, canvasWidth: Int, canvasHeight: Int): Boolean {
        val (fitW, fitH) = TrackMath.fitSize(aspect, canvasWidth, canvasHeight)
        val halfW = fitW * pose.scaleX / 2.0
        val halfH = fitH * pose.scaleY / 2.0
        val eps = 1e-6
        return abs(pose.positionX) + canvasWidth / 2.0 <= halfW + eps && abs(pose.positionY) + canvasHeight / 2.0 <= halfH + eps
    }
}

/** A moment of a clip and the point of its picture to keep in the middle then. [frame] is relative to the clip's start. */
data class ReframePoint(val frame: Long, val u: Double, val v: Double)

/**
 * Reframes clip [clipId] for a [canvasWidth] x [canvasHeight] canvas. One point sets a fixed pose for the whole clip
 * (no keyframes); several points write one keyframe each, easing between them. Any previous keyframes of the clip
 * are replaced. One undo step.
 */
data class ReframeClip(
    val clipId: String,
    val points: List<ReframePoint>,
    val aspect: Double,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val zoom: Double = 1.0,
) : EditCommand {
    override fun apply(timeline: Timeline): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return EditResult.Failure(EditError.ClipNotFound(clipId))
        if (points.isEmpty()) return EditResult.Failure(EditError.InvalidClip("pick a point of interest first"))
        if (!(aspect.isFinite() && aspect > 0.0)) return EditResult.Failure(EditError.InvalidClip("the picture's shape is unknown"))
        val ordered = points.distinctBy { it.frame }.sortedBy { it.frame }
        fun pose(point: ReframePoint) = Reframe.poseFor(point.u, point.v, aspect, canvasWidth, canvasHeight, zoom, clip.transform)
        var current = when (val cleared = TimelineOps.clearKeyframes(timeline, clipId)) {
            is EditResult.Success -> cleared.value
            is EditResult.Failure -> return cleared
        }
        if (ordered.size == 1) return TimelineOps.setTransform(current, clipId, pose(ordered.single()))
        current = when (val fixed = TimelineOps.setTransform(current, clipId, pose(ordered.first()))) {
            is EditResult.Success -> fixed.value
            is EditResult.Failure -> return fixed
        }
        for (point in ordered) {
            val frame = point.frame.coerceIn(0L, (clip.durationFrames - 1).coerceAtLeast(0L))
            current = when (val set = TimelineOps.setKeyframe(current, clipId, Keyframe(frame, pose(point), Interpolation.EASE))) {
                is EditResult.Success -> set.value
                is EditResult.Failure -> return set
            }
        }
        return EditResult.Success(current)
    }
}

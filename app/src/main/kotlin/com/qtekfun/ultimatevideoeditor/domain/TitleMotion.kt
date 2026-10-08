package com.qtekfun.ultimatevideoeditor.domain

/** How a title comes in or goes out: a preset that [TitleMotion] turns into keyframes of the clip. */
enum class MotionPreset(val label: String) {
    NONE("None"),
    FADE("Fade"),
    SLIDE_LEFT("Slide from left"),
    SLIDE_RIGHT("Slide from right"),
    SLIDE_UP("Slide from bottom"),
    SLIDE_DOWN("Slide from top"),
    POP("Pop"),
}

/**
 * In and out animations of a title, built on the clip's keyframes so preview and export evaluate
 * them the same way as any other animation. The title rests at [resting] between the two edges; at
 * the first frame (for the intro) and the last frame (for the outro) it sits in the preset's start
 * pose. Applying a motion replaces the clip's keyframes.
 */
object TitleMotion {
    /** Length of each edge in seconds when the editor does not choose one. */
    const val DEFAULT_EDGE_SECONDS = 0.4

    /** Slide distance, as a fraction of the canvas. */
    private const val SLIDE_DISTANCE = 0.6
    private const val POP_SCALE = 0.3

    /**
     * Keyframes for a clip of [durationFrames] frames resting at [resting]: [intro] over the first
     * [edgeFrames] frames and [outro] over the last [edgeFrames]. Empty when both are [MotionPreset.NONE]
     * or the clip is too short to animate (under 4 frames). The edge is shortened so the two never meet.
     */
    fun keyframes(
        resting: ClipTransform,
        durationFrames: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        intro: MotionPreset,
        outro: MotionPreset,
        edgeFrames: Long,
    ): List<Keyframe> {
        if (durationFrames < MIN_FRAMES || (intro == MotionPreset.NONE && outro == MotionPreset.NONE)) return emptyList()
        val edge = edgeFrames.coerceIn(1L, (durationFrames - 1) / 2)
        val last = durationFrames - 1
        val result = ArrayList<Keyframe>(4)
        if (intro != MotionPreset.NONE) {
            result += Keyframe(0, startPose(resting, intro, canvasWidth, canvasHeight), Interpolation.EASE)
            result += Keyframe(edge, resting, Interpolation.EASE)
        }
        if (outro != MotionPreset.NONE) {
            // The outro starts from rest; with no intro the title is at rest from the first frame.
            if (intro == MotionPreset.NONE) result += Keyframe(0, resting, Interpolation.EASE)
            val outStart = last - edge
            if (result.isEmpty() || result.last().frame < outStart) result += Keyframe(outStart, resting, Interpolation.EASE)
            result += Keyframe(last, startPose(resting, outro, canvasWidth, canvasHeight), Interpolation.EASE)
        }
        return result
    }

    /** The pose a preset starts from (and ends at, for an outro): off-screen, transparent or tiny. */
    internal fun startPose(resting: ClipTransform, preset: MotionPreset, canvasWidth: Int, canvasHeight: Int): ClipTransform = when (preset) {
        MotionPreset.NONE -> resting
        MotionPreset.FADE -> resting.copy(opacity = 0.0)
        MotionPreset.SLIDE_LEFT -> resting.copy(positionX = resting.positionX - SLIDE_DISTANCE * canvasWidth, opacity = 0.0)
        MotionPreset.SLIDE_RIGHT -> resting.copy(positionX = resting.positionX + SLIDE_DISTANCE * canvasWidth, opacity = 0.0)
        MotionPreset.SLIDE_UP -> resting.copy(positionY = resting.positionY + SLIDE_DISTANCE * canvasHeight, opacity = 0.0)
        MotionPreset.SLIDE_DOWN -> resting.copy(positionY = resting.positionY - SLIDE_DISTANCE * canvasHeight, opacity = 0.0)
        MotionPreset.POP -> resting.copy(scaleX = resting.scaleX * POP_SCALE, scaleY = resting.scaleY * POP_SCALE, opacity = 0.0)
    }

    private const val MIN_FRAMES = 4L
}

/** Gives a clip the in and out animation [intro] / [outro] (replacing its keyframes), as one undo step. */
data class SetTitleMotion(
    val clipId: String,
    val intro: MotionPreset,
    val outro: MotionPreset,
    val edgeFrames: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
) : EditCommand {
    override fun apply(timeline: Timeline): EditResult<Timeline> {
        val clip = timeline.trackOfClip(clipId)?.clip(clipId) ?: return EditResult.Failure(EditError.ClipNotFound(clipId))
        if (clip.title == null) return EditResult.Failure(EditError.NotATitle(clipId))
        if (canvasWidth <= 0 || canvasHeight <= 0) return EditResult.Failure(EditError.InvalidKeyframe("the canvas has no size"))
        val keys = TitleMotion.keyframes(clip.transform, clip.durationFrames, canvasWidth, canvasHeight, intro, outro, edgeFrames)
        Keyframes.problem(keys, clip.durationFrames)?.let { return EditResult.Failure(EditError.InvalidKeyframe(it)) }
        return TimelineOps.setKeyframes(timeline, clipId, keys)
    }
}

package com.ultimatevideo.uveditor.domain

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Where the user pointed: a point or a box on the clip's picture at one frame, in fractions of the upright source
 * frame (0..1 across and down). [sourceFrame] is the clip's source frame, in project frames like every source
 * range, so it stays right when the clip is retimed, trimmed or moved.
 */
data class TrackSeed(val sourceFrame: Long, val cx: Double, val cy: Double, val w: Double, val h: Double) {
    fun problem(): String? = when {
        sourceFrame < 0 -> "the seed frame is before the start of the media"
        !(cx.isFinite() && cy.isFinite() && w.isFinite() && h.isFinite()) -> "the seed must be finite"
        cx !in 0.0..1.0 || cy !in 0.0..1.0 -> "the seed is outside the picture"
        w !in MIN_SIZE..MAX_SIZE || h !in MIN_SIZE..MAX_SIZE -> "the seed box is too small or too large"
        else -> null
    }

    companion object {
        const val MIN_SIZE = 0.01
        const val MAX_SIZE = 1.0

        /** Side of the box a plain tap makes, as a fraction of the frame height. */
        const val DEFAULT_SIDE = 0.12
    }
}

/**
 * A tracked target of one video clip. The path itself is analysed on the device and kept in a cache file named from
 * the media and [seed] (engine/track); the project only remembers what was asked, so a lost cache is a re-analysis.
 */
data class MotionTrack(val id: String, val clipId: String, val name: String, val seed: TrackSeed) {
    fun problem(): String? = when {
        id.isBlank() -> "a motion track needs an id"
        name.isBlank() -> "a motion track needs a name"
        else -> seed.problem()
    }
}

/** The tracked box at one source frame: fractions of the upright frame; [lost] frames hold the last believable position. */
data class TrackFrame(
    val sourceFrame: Long,
    val cx: Double,
    val cy: Double,
    val w: Double,
    val h: Double,
    val confidence: Double,
    val lost: Boolean,
)

/** A whole analysed path, sorted by source frame. [aspect] is width / height of the upright frame. */
class TrackPath(val aspect: Double, frames: List<TrackFrame>) {
    val frames: List<TrackFrame> = frames.sortedBy { it.sourceFrame }

    val isEmpty: Boolean get() = frames.isEmpty()
    val firstFrame: Long get() = frames.first().sourceFrame
    val lastFrame: Long get() = frames.last().sourceFrame
    val lostCount: Int get() = frames.count { it.lost }

    /** The sample at [sourceFrame]; before the first or after the last frame the end sample holds. Null for an empty path. */
    fun at(sourceFrame: Long): TrackFrame? {
        if (frames.isEmpty()) return null
        if (sourceFrame <= frames.first().sourceFrame) return frames.first()
        if (sourceFrame >= frames.last().sourceFrame) return frames.last()
        var lo = 0
        var hi = frames.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (frames[mid].sourceFrame <= sourceFrame) lo = mid else hi = mid - 1
        }
        return frames[lo]
    }

    /** Whether the path covers the source frames [from, to) (a frame of tolerance at each end). */
    fun covers(from: Long, to: Long): Boolean = !isEmpty && firstFrame <= from + 1 && lastFrame >= to - 2
}

/**
 * Maths between a point of a clip's picture and the project canvas, and the keyframes that make another clip follow
 * it. Pure and on integer project frames, so a tracked overlay looks the same in preview and export (it is plain
 * position keyframes, evaluated by the same code as any other animation).
 */
object TrackMath {

    /** The size the clip's frame takes on the canvas before scale and rotation: "contain", the same fit as the compositor. */
    fun fitSize(aspect: Double, canvasWidth: Int, canvasHeight: Int): Pair<Double, Double> {
        val a = if (aspect.isFinite() && aspect > 0.0) aspect else canvasWidth.toDouble() / canvasHeight
        val canvasAspect = canvasWidth.toDouble() / canvasHeight
        return if (a >= canvasAspect) canvasWidth.toDouble() to canvasWidth / a else canvasHeight * a to canvasHeight.toDouble()
    }

    /**
     * The point (u, v) of the clip's frame (fractions, 0.5 is the middle) as canvas pixels measured from the canvas
     * centre (+x right, +y down), after the clip's scale, clockwise rotation and position in [pose].
     */
    fun toCanvas(u: Double, v: Double, aspect: Double, canvasWidth: Int, canvasHeight: Int, pose: ClipTransform): Pair<Double, Double> {
        val (fitW, fitH) = fitSize(aspect, canvasWidth, canvasHeight)
        val lx = (u - 0.5) * fitW * pose.scaleX
        val ly = (v - 0.5) * fitH * pose.scaleY
        val r = Math.toRadians(pose.rotationDegrees)
        val c = cos(r)
        val s = sin(r)
        return (lx * c - ly * s + pose.positionX) to (lx * s + ly * c + pose.positionY)
    }

    /** The inverse of [toCanvas]: which point of the clip's frame is under canvas point ([x], [y]). May be outside 0..1. */
    fun fromCanvas(x: Double, y: Double, aspect: Double, canvasWidth: Int, canvasHeight: Int, pose: ClipTransform): Pair<Double, Double> {
        val (fitW, fitH) = fitSize(aspect, canvasWidth, canvasHeight)
        val dx = x - pose.positionX
        val dy = y - pose.positionY
        val r = Math.toRadians(pose.rotationDegrees)
        val c = cos(r)
        val s = sin(r)
        val lx = dx * c + dy * s
        val ly = -dx * s + dy * c
        val sx = if (pose.scaleX > 0.0) pose.scaleX else 1.0
        val sy = if (pose.scaleY > 0.0) pose.scaleY else 1.0
        return (lx / (fitW * sx) + 0.5) to (ly / (fitH * sy) + 0.5)
    }

    /** A canvas-space point of the tracked target at a project frame. */
    data class CanvasPoint(val frame: Long, val x: Double, val y: Double, val lost: Boolean)

    /**
     * The target's canvas position at every project frame the [tracked] clip occupies (from its start, [from]..[to]
     * exclusive, clipped to the clip), following its own retiming and pose animation. Used for the overlay on the
     * preview and as the base of [attachKeyframes].
     */
    fun canvasPath(path: TrackPath, tracked: Clip, canvasWidth: Int, canvasHeight: Int, from: Long = tracked.timelineStart.value, to: Long = tracked.timelineEnd.value): List<CanvasPoint> {
        if (path.isEmpty) return emptyList()
        val start = maxOf(from, tracked.timelineStart.value)
        val end = minOf(to, tracked.timelineEnd.value)
        val out = ArrayList<CanvasPoint>(maxOf(0, (end - start).toInt()))
        for (frame in start until end) {
            val source = tracked.sourceFrameAtProjectFrame(FrameIndex(frame))
            val sample = path.at(source) ?: continue
            val pose = Keyframes.evaluate(tracked.keyframes, frame - tracked.timelineStart.value, tracked.transform)
            val (x, y) = toCanvas(sample.cx, sample.cy, path.aspect, canvasWidth, canvasHeight, pose)
            out += CanvasPoint(frame, x, y, sample.lost)
        }
        return out
    }

    /**
     * Indices (ascending, always including the first and last) of the samples to keep so that straight lines between
     * the kept ones stay within [tolerance] of every dropped sample, measured at the dropped sample's own frame
     * (Douglas-Peucker with the frame axis as the interpolation parameter). A path of hundreds of frames becomes a few
     * keyframes that stay editable.
     */
    fun decimate(frames: LongArray, xs: DoubleArray, ys: DoubleArray, tolerance: Double): List<Int> {
        val n = frames.size
        if (n <= 2) return (0 until n).toList()
        val keep = BooleanArray(n)
        keep[0] = true
        keep[n - 1] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to n - 1)
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast()
            if (b - a < 2) continue
            var worst = -1.0
            var worstIndex = -1
            val span = (frames[b] - frames[a]).toDouble()
            for (i in a + 1 until b) {
                val t = if (span > 0.0) (frames[i] - frames[a]) / span else 0.0
                val px = xs[a] + (xs[b] - xs[a]) * t
                val py = ys[a] + (ys[b] - ys[a]) * t
                val error = hypot(xs[i] - px, ys[i] - py)
                if (error > worst) {
                    worst = error
                    worstIndex = i
                }
            }
            if (worst > tolerance && worstIndex > 0) {
                keep[worstIndex] = true
                stack.addLast(a to worstIndex)
                stack.addLast(worstIndex to b)
            }
        }
        return (0 until n).filter { keep[it] }
    }

    /**
     * Position keyframes (in the attached clip's own frames) that make [attached] sit on the tracked target for as
     * long as the two clips overlap on the timeline, plus [offsetX]/[offsetY] canvas pixels. Outside the tracked clip
     * the first or last position holds. The attached clip keeps its scale, rotation and opacity (and any keyframes
     * it already had keep their frames, interpolation and handles): only the position follows the path.
     */
    fun attachKeyframes(
        path: TrackPath,
        tracked: Clip,
        attached: Clip,
        canvasWidth: Int,
        canvasHeight: Int,
        offsetX: Double = 0.0,
        offsetY: Double = 0.0,
        tolerancePx: Double = 1.0,
    ): List<Keyframe> {
        val length = attached.durationFrames
        if (length <= 0 || path.isEmpty || tracked.durationFrames <= 0) return emptyList()
        val frames = LongArray(length.toInt())
        val xs = DoubleArray(length.toInt())
        val ys = DoubleArray(length.toInt())
        for (k in 0 until length.toInt()) {
            val project = attached.timelineStart.value + k
            val trackedFrame = (project - tracked.timelineStart.value).coerceIn(0L, tracked.durationFrames - 1)
            val source = tracked.sourceFrameAtProjectFrame(FrameIndex(tracked.timelineStart.value + trackedFrame))
            val sample = checkNotNull(path.at(source))
            val pose = Keyframes.evaluate(tracked.keyframes, trackedFrame, tracked.transform)
            val (x, y) = toCanvas(sample.cx, sample.cy, path.aspect, canvasWidth, canvasHeight, pose)
            frames[k] = k.toLong()
            xs[k] = x + offsetX
            ys[k] = y + offsetY
        }
        val chosen = decimate(frames, xs, ys, tolerancePx).map { frames[it] }.toMutableSet()
        attached.keyframes.forEach { if (it.frame in 0 until length) chosen += it.frame }
        return chosen.sorted().map { frame ->
            val existing = Keyframes.at(attached.keyframes, frame)
            val pose = Keyframes.evaluate(attached.keyframes, frame, attached.transform).copy(positionX = xs[frame.toInt()], positionY = ys[frame.toInt()])
            existing?.copy(transform = pose) ?: Keyframe(frame, pose)
        }
    }
}

/** Timeline edits for motion tracks. Pure; [EditHistory] makes them undoable. */
object MotionTrackOps {
    private fun failure(reason: String): EditResult<Timeline> = EditResult.Failure(EditError.InvalidClip(reason))

    fun add(timeline: Timeline, track: MotionTrack): EditResult<Timeline> {
        track.problem()?.let { return failure(it) }
        val track0 = timeline.trackOfClip(track.clipId) ?: return EditResult.Failure(EditError.ClipNotFound(track.clipId))
        val clip = checkNotNull(track0.clip(track.clipId))
        if (track0.type != TrackType.VIDEO || !clip.hasMedia || clip.still != null) return failure("only a video clip can be tracked")
        if (timeline.motionTracks.any { it.id == track.id }) return failure("a motion track with this id already exists")
        return EditResult.Success(timeline.copy(motionTracks = timeline.motionTracks + track))
    }

    fun remove(timeline: Timeline, trackId: String): EditResult<Timeline> {
        if (timeline.motionTracks.none { it.id == trackId }) return failure("motion track $trackId not found")
        return EditResult.Success(timeline.copy(motionTracks = timeline.motionTracks.filter { it.id != trackId }))
    }

    /** Makes [clipId] follow a path: replaces its keyframes with [keyframes] in one step (the caller built them with [TrackMath.attachKeyframes]). */
    fun attach(timeline: Timeline, clipId: String, keyframes: List<Keyframe>): EditResult<Timeline> {
        val track = timeline.trackOfClip(clipId) ?: return EditResult.Failure(EditError.ClipNotFound(clipId))
        if (track.type == TrackType.AUDIO) return EditResult.Failure(EditError.InvalidKeyframe("audio clips have nothing to animate"))
        if (keyframes.isEmpty()) return EditResult.Failure(EditError.InvalidKeyframe("there is no path to follow"))
        val clip = checkNotNull(track.clip(clipId))
        Keyframes.problem(keyframes, clip.durationFrames)?.let { return EditResult.Failure(EditError.InvalidKeyframe(it)) }
        return EditResult.Success(timeline.withTrack(track.withClips(track.clips.map { if (it.id == clipId) it.copy(keyframes = keyframes) else it })).pruned())
    }
}

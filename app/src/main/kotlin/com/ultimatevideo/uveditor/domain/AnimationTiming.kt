package com.ultimatevideo.uveditor.domain

/**
 * The timing of an animated picture (GIF or animated WebP): how long each of its frames is shown, in
 * milliseconds. A clip of such a picture loops it for as long as the clip lasts, and which animation frame
 * shows at a project frame is decided here, with exact integer microsecond arithmetic, so the preview and the
 * exporter pick the same one.
 *
 * The animation starts at the clip's own first frame ([RenderClip.keyframeOriginFrame]); trimming the clip's
 * start does not move it, because a picture has no source position to trim into.
 */
data class AnimationTiming(val delaysMs: List<Int>) {
    init {
        require(delaysMs.isNotEmpty()) { "an animation has at least one frame" }
        require(delaysMs.all { it in 1..MAX_DELAY_MS }) { "frame delays must be 1..$MAX_DELAY_MS ms" }
    }

    val frameCount: Int get() = delaysMs.size

    /** One pass of the animation, in microseconds. */
    val periodMicros: Long = delaysMs.sumOf { it.toLong() } * MICROS_PER_MS

    // End of frame i, in microseconds from the start of a pass.
    private val ends: LongArray = LongArray(delaysMs.size).also { out ->
        var t = 0L
        for ((i, d) in delaysMs.withIndex()) {
            t += d.toLong() * MICROS_PER_MS
            out[i] = t
        }
    }

    /** True for a picture that actually moves: a single frame is just a photo. */
    val isAnimated: Boolean get() = frameCount > 1

    /** The animation frame shown at [clipFrame] frames after the clip's start (looping), at project rate [fps]. */
    fun frameIndexAt(clipFrame: Long, fps: FrameRate): Int {
        require(clipFrame >= 0) { "clip frame must not be negative: $clipFrame" }
        return indexAtMicros(fps.framesToMicros(clipFrame) % periodMicros)
    }

    /** Index of the frame whose interval contains [micros] (0 until [periodMicros]). */
    internal fun indexAtMicros(micros: Long): Int {
        var lo = 0
        var hi = ends.size - 1
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (micros < ends[mid]) hi = mid else lo = mid + 1
        }
        return lo
    }

    /**
     * The stretches of clip frames [from, to) over which one animation frame shows, in order and without gaps:
     * consecutive project frames that map to the same animation frame are merged.
     */
    fun segments(from: Long, to: Long, fps: FrameRate): List<AnimationSegment> {
        require(from in 0..to) { "bad range $from..$to" }
        if (from == to) return emptyList()
        val out = ArrayList<AnimationSegment>()
        var start = from
        var index = frameIndexAt(from, fps)
        var frame = from + 1
        while (frame < to) {
            val next = frameIndexAt(frame, fps)
            if (next != index) {
                out += AnimationSegment(start, frame, index)
                start = frame
                index = next
            }
            frame++
        }
        out += AnimationSegment(start, to, index)
        return out
    }

    companion object {
        const val MAX_DELAY_MS = 600_000
        private const val MICROS_PER_MS = 1000L

        /**
         * Browsers show a GIF or WebP frame with a delay of 10 ms or less for 100 ms (the file format allows 0, and
         * many writers mean "as fast as possible"); the same rule is applied when files are read.
         */
        const val MIN_EFFECTIVE_DELAY_MS = 10
        const val DEFAULT_DELAY_MS = 100

        /** The delay a frame is shown for given the [rawMs] its file says. */
        fun effectiveDelay(rawMs: Int): Int = if (rawMs <= MIN_EFFECTIVE_DELAY_MS) DEFAULT_DELAY_MS else rawMs.coerceAtMost(MAX_DELAY_MS)

        /** The timing of a picture whose frames are shown for [rawDelaysMs], or null for fewer than two frames. */
        fun ofRaw(rawDelaysMs: List<Int>): AnimationTiming? =
            if (rawDelaysMs.size < 2) null else AnimationTiming(rawDelaysMs.map(::effectiveDelay))
    }
}

/** Clip frames [startFrame, endFrame) that all show animation frame [index]. */
data class AnimationSegment(val startFrame: Long, val endFrame: Long, val index: Int)

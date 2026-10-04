package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.domain.beat.PeakEnvelope
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * Silence-based auto cut (SPECS.md 9.16): finds the stretches of a clip's audio that stay below a level
 * for a while, proposes to remove them, and removes the accepted ones from the base track as one edit.
 * Everything here is classical signal handling on the loudness envelope the waveform cache already holds;
 * no audio is decoded again and nothing leaves the device.
 */
data class SilenceSettings(
    /** Level in dBFS under which a stretch counts as silent. */
    val thresholdDb: Double = DEFAULT_THRESHOLD_DB,
    /** A silence must last at least this long to be proposed. */
    val minSilenceSeconds: Double = DEFAULT_MIN_SILENCE_SECONDS,
    /** Kept at each end of a removed silence, so words are not clipped. */
    val paddingSeconds: Double = DEFAULT_PADDING_SECONDS,
) {
    fun problem(): String? = when {
        !(thresholdDb.isFinite() && thresholdDb in MIN_THRESHOLD_DB..MAX_THRESHOLD_DB) ->
            "threshold must be between ${MIN_THRESHOLD_DB.toInt()} and ${MAX_THRESHOLD_DB.toInt()} dB"
        !(minSilenceSeconds.isFinite() && minSilenceSeconds in MIN_SILENCE_RANGE) ->
            "minimum silence must be between ${MIN_SILENCE_RANGE.start} and ${MIN_SILENCE_RANGE.endInclusive} s"
        !(paddingSeconds.isFinite() && paddingSeconds in PADDING_RANGE) ->
            "padding must be between ${PADDING_RANGE.start} and ${PADDING_RANGE.endInclusive} s"
        else -> null
    }

    /** The threshold as a linear amplitude (0..1) comparable to the envelope's values. */
    val thresholdLevel: Double get() = 10.0.pow(thresholdDb / 20.0)

    companion object {
        const val DEFAULT_THRESHOLD_DB = -40.0
        const val DEFAULT_MIN_SILENCE_SECONDS = 0.5
        const val DEFAULT_PADDING_SECONDS = 0.1
        const val MIN_THRESHOLD_DB = -80.0
        const val MAX_THRESHOLD_DB = -10.0
        val MIN_SILENCE_RANGE = 0.1..10.0
        val PADDING_RANGE = 0.0..2.0
    }
}

/** A silent stretch of the analysed audio, in microseconds from the start of the analysed window. */
data class SilentSpan(val startMicros: Long, val endMicros: Long)

/** A stretch of the timeline to remove, in project frames: [startFrame] inclusive, [endFrame] exclusive. */
data class CutSpan(val startFrame: Long, val endFrame: Long) {
    init {
        require(endFrame > startFrame) { "a cut must cover at least one frame" }
    }

    val length: Long get() = endFrame - startFrame
}

object SilenceDetector {
    /**
     * The silences of [envelope] under [settings]: runs of bins below the threshold that last at least the
     * minimum, shortened by the padding at both ends (a run too short to survive the padding is dropped).
     */
    fun detect(envelope: PeakEnvelope, settings: SilenceSettings): List<SilentSpan> {
        require(settings.problem() == null) { settings.problem().orEmpty() }
        val level = settings.thresholdLevel.toFloat()
        val perSecond = envelope.binsPerSecond
        val values = envelope.values
        val result = ArrayList<SilentSpan>()
        var runStart = -1
        fun close(endBin: Int) {
            if (runStart < 0) return
            val seconds = (endBin - runStart) / perSecond
            if (seconds >= settings.minSilenceSeconds) {
                val start = runStart / perSecond + settings.paddingSeconds
                val end = endBin / perSecond - settings.paddingSeconds
                if (end > start) result += SilentSpan((start * MICROS).roundToLong(), (end * MICROS).roundToLong())
            }
            runStart = -1
        }
        for (i in values.indices) {
            if (values[i] < level) {
                if (runStart < 0) runStart = i
            } else {
                close(i)
            }
        }
        close(values.size)
        return result
    }

    private const val MICROS = 1_000_000.0
}

object AutoCutPlanner {
    /** True when [clip] maps frame for frame onto its source, which is what the cut positions assume. */
    fun supports(clip: Clip): Boolean = clip.hasMedia && !clip.isRetimed && !clip.reverse

    /**
     * Maps [spans] (relative to [windowStartMicros] of the clip's source) onto the timeline: each span is
     * shrunk to whole frames inside the clip's source range. Spans that no longer cover a frame are dropped.
     */
    fun cuts(clip: Clip, fps: FrameRate, windowStartMicros: Long, spans: List<SilentSpan>): List<CutSpan> {
        if (!supports(clip)) return emptyList()
        val first = clip.sourceIn.value
        val last = clip.sourceOut.value
        val result = ArrayList<CutSpan>()
        for (span in spans) {
            val fromMicros = windowStartMicros + span.startMicros
            val toMicros = windowStartMicros + span.endMicros
            var startFrame = fps.microsToFrames(fromMicros)
            if (fps.framesToMicros(startFrame) < fromMicros) startFrame++
            val endFrame = fps.microsToFrames(toMicros)
            val from = maxOf(startFrame, first)
            val to = minOf(endFrame, last)
            if (to <= from) continue
            result += CutSpan(clip.timelineStart.value + (from - first), clip.timelineStart.value + (to - first))
        }
        return result.sortedBy { it.startFrame }
    }

    fun savedFrames(cuts: List<CutSpan>): Long = cuts.sumOf { it.length }
}

/**
 * Removes [spans] from the base track, one undo step. Each span must lie inside one base clip; it is
 * cut out and the gap is closed by the base-track delete, so later clips ripple and overlays follow
 * exactly as for a manual delete (SPECS.md 6.1). Spans are removed from the end backwards so the frames of
 * the others stay where they were proposed.
 */
data class AutoCut(val spans: List<CutSpan>) : EditCommand {
    override fun apply(timeline: Timeline): EditResult<Timeline> {
        val base = ClipDeletion.baseTrack(timeline) ?: return EditResult.Failure(EditError.InvalidClip("there is no base track to cut"))
        if (spans.isEmpty()) return EditResult.Failure(EditError.InvalidClip("there is nothing to cut"))
        var current = timeline
        for (span in spans.sortedByDescending { it.startFrame }) {
            current = when (val next = removeSpan(current, base.id, span)) {
                is EditResult.Success -> next.value
                is EditResult.Failure -> return next
            }
        }
        return EditResult.Success(current)
    }

    private fun removeSpan(timeline: Timeline, baseId: String, span: CutSpan): EditResult<Timeline> {
        val track = checkNotNull(timeline.track(baseId))
        val clip = track.clips.firstOrNull { it.timelineStart.value <= span.startFrame && it.timelineEnd.value >= span.endFrame }
            ?: return EditResult.Failure(EditError.InvalidClip("a cut must lie inside one base clip"))
        var current = timeline
        var middleId = clip.id
        if (span.endFrame < clip.timelineEnd.value) {
            current = when (val s = TimelineOps.split(current, baseId, FrameIndex(span.endFrame), freshId(current, "${clip.id}~after"))) {
                is EditResult.Success -> s.value
                is EditResult.Failure -> return s
            }
        }
        if (span.startFrame > clip.timelineStart.value) {
            val id = freshId(current, "${clip.id}~cut")
            current = when (val s = TimelineOps.split(current, baseId, FrameIndex(span.startFrame), id)) {
                is EditResult.Success -> s.value
                is EditResult.Failure -> return s
            }
            middleId = id
        }
        return ClipDeletion.delete(current, middleId)
    }

    private fun freshId(timeline: Timeline, base: String): String {
        var candidate = base
        var n = 1
        while (timeline.trackOfClip(candidate) != null) candidate = "$base${n++}"
        return candidate
    }
}

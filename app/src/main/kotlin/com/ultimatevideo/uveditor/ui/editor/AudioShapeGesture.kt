package com.ultimatevideo.uveditor.ui.editor

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipGain
import com.ultimatevideo.uveditor.domain.ParamIds
import com.ultimatevideo.uveditor.domain.ParamKey
import com.ultimatevideo.uveditor.domain.TrackType
import com.ultimatevideo.uveditor.domain.paramKeys
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.SnapshotCurvePoint
import com.ultimatevideo.uveditor.engine.timeline.SnapshotShaping
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit

/**
 * The sound shaping of a clip as the timeline canvas draws it, and what a drag of its handles means. Everything here is pure
 * so the decisions are tested on the JVM; the canvas (C++, `timeline_view/audio_shaping.h`) only places the circles and
 * dots and reports which one is under the finger. Time is integer frames throughout (SPECS 5.37).
 */
object AudioShapeGesture {
    /** The lowest value a point is shown at; a point dragged to the bottom edge becomes silence ([SILENT_DB]). */
    const val SCALE_MIN_DB = -48.0

    /** The highest value a point can be dragged to. */
    const val SCALE_MAX_DB = 12.0

    /** What a point dragged to the bottom edge is set to: effectively silent, the quietest value a clip can have. */
    const val SILENT_DB = ClipGain.MIN_DB

    /** Within this distance of 0 dB a dragged point sticks to unity gain, so "no change" is easy to hit. */
    const val UNITY_DETENT_DB = 0.8

    /** True when [kind] is one of the grabbable parts of the sound shaping (handles and curve points). */
    fun isShapeHit(kind: HitKind): Boolean =
        kind == HitKind.FADE_IN_HANDLE || kind == HitKind.FADE_OUT_HANDLE || kind == HitKind.VOLUME_POINT

    /** The canvas entry of [clip]: its fades and volume curve, or null when it has none to show and is not [editable]. */
    fun snapshotOf(clip: Clip, clipKey: Long, editable: Boolean): SnapshotShaping? {
        if (!clip.hasMedia) return null
        val keys = clip.paramKeys(ParamIds.GAIN_DB)
        val audio = clip.audio
        if (!editable && audio.fadeInFrames == 0L && audio.fadeOutFrames == 0L && keys.isEmpty()) return null
        return SnapshotShaping(
            clipKey = clipKey,
            fadeInFrames = audio.fadeInFrames.coerceIn(0, clip.durationFrames),
            fadeOutFrames = audio.fadeOutFrames.coerceIn(0, clip.durationFrames),
            fadeShape = audio.fadeShape.code,
            editable = editable,
            baseDb = clip.gainDb.toFloat(),
            points = keys.filter { it.frame in 0 until clip.durationFrames }.map { SnapshotCurvePoint(it.frame, it.value.toFloat()) },
        )
    }

    /** True when the sound shaping of [clip], which sits on a lane of [laneType], can be edited on the canvas. */
    fun isEditable(clip: Clip, laneType: TrackType, isPrimary: Boolean): Boolean =
        isPrimary && laneType == TrackType.AUDIO && clip.hasMedia

    /**
     * The value for a point dragged to [rawDb]: kept on the scale, stuck to 0 dB near it, one tenth of a dB fine, and silence
     * at the bottom edge.
     */
    fun snapDb(rawDb: Double): Double {
        if (!rawDb.isFinite()) return 0.0
        if (rawDb <= SCALE_MIN_DB) return SILENT_DB
        val clamped = rawDb.coerceAtMost(SCALE_MAX_DB)
        if (kotlin.math.abs(clamped) <= UNITY_DETENT_DB) return 0.0
        return Math.round(clamped * 10.0) / 10.0
    }

    /**
     * The keys to paste to add a volume point of [db] at [frame] (clip frames) to [clip]. A clip whose volume is not
     * animated yet gets its fixed volume pinned at both ends first, so the new point only changes the part around it.
     */
    fun keysForNewPoint(clip: Clip, frame: Long, db: Double, snap: Boolean = true): List<ParamKey> {
        val last = clip.durationFrames - 1
        val at = frame.coerceIn(0, last)
        val point = ParamKey(at, if (snap) snapDb(db) else db.coerceIn(ClipGain.MIN_DB, ClipGain.MAX_DB))
        if (clip.paramKeys(ParamIds.GAIN_DB).isNotEmpty()) return listOf(point)
        val base = clip.gainDb.coerceIn(ClipGain.MIN_DB, ClipGain.MAX_DB)
        // A point on an end replaces that end's pin rather than leaving two keys on one frame.
        val pins = listOf(ParamKey(0, base), ParamKey(last, base)).filter { it.frame != point.frame }.distinctBy { it.frame }
        return (pins + point).sortedBy { it.frame }
    }

    /**
     * The fade lengths after the fade-in handle was dragged to [pointerFrame] (project frames): the handle follows the finger
     * (less the [grabOffset] it was grabbed with), never past the clip's other fade or its ends.
     */
    fun fadeIn(clipStart: Long, duration: Long, otherFade: Long, pointerFrame: Long, grabOffset: Long): Long =
        (pointerFrame - grabOffset - clipStart).coerceIn(0, (duration - otherFade).coerceAtLeast(0))

    /** Like [fadeIn] for the fade-out handle, measured back from the clip's end. */
    fun fadeOut(clipStart: Long, duration: Long, otherFade: Long, pointerFrame: Long, grabOffset: Long): Long =
        (clipStart + duration - (pointerFrame - grabOffset)).coerceIn(0, (duration - otherFade).coerceAtLeast(0))

    /** What the gesture asks the editor to do. */
    sealed interface Action {
        /** Provisional new fade lengths of the clip (clip frames), final when the drag ends. */
        data class SetFades(val fadeIn: Long, val fadeOut: Long) : Action

        /** Provisional move of the volume point at [fromFrame] to [toFrame] / [db]. */
        data class MovePoint(val fromFrame: Long, val toFrame: Long, val db: Double) : Action
    }

    /**
     * A drag of one handle or point in progress. [begin] decides from the hit what is grabbed; [move] turns each new hit into
     * the action to apply. The first [move] after the finger crossed the slop is the one that shows a change.
     */
    class Drag private constructor(
        private val kind: HitKind,
        private val clipStart: Long,
        private val duration: Long,
        private val fadeIn: Long,
        private val fadeOut: Long,
        private val grabOffset: Long,
        private val pointFrame: Long,
        private val minFrame: Long,
        private val maxFrame: Long,
        private var lastDb: Double,
    ) {
        fun move(hit: TimelineHit): Action? = when (kind) {
            HitKind.FADE_IN_HANDLE -> Action.SetFades(fadeIn(clipStart, duration, fadeOut, hit.frame, grabOffset), fadeOut)
            HitKind.FADE_OUT_HANDLE -> Action.SetFades(fadeIn, fadeOut(clipStart, duration, fadeIn, hit.frame, grabOffset))
            HitKind.VOLUME_POINT -> {
                hit.dbTenths?.let { lastDb = it / 10.0 }
                val frame = (hit.frame - clipStart - grabOffset).coerceIn(minFrame, maxFrame)
                Action.MovePoint(pointFrame, frame, snapDb(lastDb))
            }
            else -> null
        }

        companion object {
            /**
             * Starts a drag at [hit] on [clip] (placed at [clip]'s own start), or null when the hit is not a grabbable part.
             * The point being grabbed may only move between its neighbours.
             */
            fun begin(hit: TimelineHit, clip: Clip): Drag? {
                val start = clip.timelineStart.value
                val audio = clip.audio
                return when (hit.kind) {
                    HitKind.FADE_IN_HANDLE -> Drag(
                        hit.kind, start, clip.durationFrames, audio.fadeInFrames, audio.fadeOutFrames,
                        grabOffset = if (audio.fadeInFrames > 0) hit.frame - (start + audio.fadeInFrames) else 0L,
                        pointFrame = 0, minFrame = 0, maxFrame = 0, lastDb = 0.0,
                    )
                    HitKind.FADE_OUT_HANDLE -> Drag(
                        hit.kind, start, clip.durationFrames, audio.fadeInFrames, audio.fadeOutFrames,
                        grabOffset = if (audio.fadeOutFrames > 0) hit.frame - (start + clip.durationFrames - audio.fadeOutFrames) else 0L,
                        pointFrame = 0, minFrame = 0, maxFrame = 0, lastDb = 0.0,
                    )
                    HitKind.VOLUME_POINT -> {
                        val keys = clip.paramKeys(ParamIds.GAIN_DB)
                        val key = keys.getOrNull(hit.index) ?: return null
                        Drag(
                            hit.kind, start, clip.durationFrames, audio.fadeInFrames, audio.fadeOutFrames,
                            grabOffset = hit.frame - (start + key.frame),
                            pointFrame = key.frame,
                            minFrame = keys.getOrNull(hit.index - 1)?.let { it.frame + 1 } ?: 0L,
                            maxFrame = keys.getOrNull(hit.index + 1)?.let { it.frame - 1 } ?: (clip.durationFrames - 1),
                            lastDb = key.value,
                        )
                    }
                    else -> null
                }
            }
        }
    }
}

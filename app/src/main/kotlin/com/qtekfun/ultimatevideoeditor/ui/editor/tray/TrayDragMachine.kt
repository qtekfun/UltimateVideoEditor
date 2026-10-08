package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import kotlin.math.hypot

/** Where a tray drag is: nothing, a finger down on a tile that has not been held long enough, or a tile picked up. */
enum class TrayDragPhase { IDLE, ARMED, CARRYING }

/** Why a tray drag ended without placing anything. */
enum class TrayDragCancel {
    /** The finger moved before the hold finished: the touch is a scroll or a swipe, not a pick up. */
    MOVED_BEFORE_HOLD,

    /** A second finger touched down. */
    SECOND_POINTER,

    /** Back, the tile left the screen, or the system cancelled the gesture. */
    INTERRUPTED,
}

/** What an input did to a [TrayDragMachine]. */
sealed interface TrayDragStep {
    /** Nothing to do. */
    data object None : TrayDragStep

    /** The hold finished: the tile is picked up at [x], [y] (root coordinates). */
    data class PickedUp(val x: Float, val y: Float) : TrayDragStep

    /** The finger moved with the tile picked up. */
    data class Moved(val x: Float, val y: Float) : TrayDragStep

    /** The finger lifted with the tile picked up: place it where [x], [y] is. */
    data class Dropped(val x: Float, val y: Float) : TrayDragStep

    /** The drag is over and nothing is placed. */
    data class Cancelled(val reason: TrayDragCancel) : TrayDragStep

    /** The finger lifted before the hold finished: a tap, which the tile's click handles. */
    data object Tapped : TrayDragStep
}

/**
 * The life of one drag of a media tray tile, as pure state so the rules are unit-tested without a device.
 *
 * `IDLE -> ARMED` when a finger lands on a tile. From there a hold of [holdMs] without moving more than [slopPx] picks the
 * tile up (`ARMED -> CARRYING`); moving first hands the touch back to scrolling, lifting first is a tap. While `CARRYING`
 * every move is reported (outside the tile too), a lift drops, a second finger cancels. The machine has no clock: the
 * caller passes the time of each event and times out the hold with [remainingHoldMs] and [holdElapsed].
 */
class TrayDragMachine(private val holdMs: Long = DEFAULT_HOLD_MS, private val slopPx: Float) {
    var phase: TrayDragPhase = TrayDragPhase.IDLE
        private set
    private var pointer = -1L
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L

    /** A finger landed on a tile. Ignored unless the machine is idle (one drag at a time). */
    fun down(pointerId: Long, x: Float, y: Float, nowMs: Long): TrayDragStep {
        if (phase != TrayDragPhase.IDLE) return TrayDragStep.None
        phase = TrayDragPhase.ARMED
        pointer = pointerId
        downX = x
        downY = y
        downAt = nowMs
        return TrayDragStep.None
    }

    /** Milliseconds of hold left at [nowMs], zero once the hold is over (and for a machine that is not armed). */
    fun remainingHoldMs(nowMs: Long): Long =
        if (phase == TrayDragPhase.ARMED) (downAt + holdMs - nowMs).coerceAtLeast(0) else 0

    /** The hold timer ran out with the finger still. */
    fun holdElapsed(): TrayDragStep {
        if (phase != TrayDragPhase.ARMED) return TrayDragStep.None
        phase = TrayDragPhase.CARRYING
        return TrayDragStep.PickedUp(downX, downY)
    }

    /** The tracked finger moved to [x], [y] (root coordinates) at [nowMs]. */
    fun move(pointerId: Long, x: Float, y: Float, nowMs: Long): TrayDragStep {
        if (pointerId != pointer) return TrayDragStep.None
        return when (phase) {
            TrayDragPhase.IDLE -> TrayDragStep.None
            TrayDragPhase.ARMED -> when {
                hypot(x - downX, y - downY) > slopPx -> end(TrayDragCancel.MOVED_BEFORE_HOLD)
                nowMs - downAt >= holdMs -> {
                    phase = TrayDragPhase.CARRYING
                    TrayDragStep.PickedUp(x, y)
                }
                else -> TrayDragStep.None
            }
            TrayDragPhase.CARRYING -> TrayDragStep.Moved(x, y)
        }
    }

    /** The tracked finger lifted at [x], [y]. */
    fun up(pointerId: Long, x: Float, y: Float): TrayDragStep {
        if (pointerId != pointer) return TrayDragStep.None
        val was = phase
        reset()
        return when (was) {
            TrayDragPhase.IDLE -> TrayDragStep.None
            TrayDragPhase.ARMED -> TrayDragStep.Tapped
            TrayDragPhase.CARRYING -> TrayDragStep.Dropped(x, y)
        }
    }

    /** Another finger touched down. */
    fun secondPointer(): TrayDragStep = if (phase == TrayDragPhase.IDLE) TrayDragStep.None else end(TrayDragCancel.SECOND_POINTER)

    /** Back, the system or the tile going away ended the drag. */
    fun interrupt(): TrayDragStep = if (phase == TrayDragPhase.IDLE) TrayDragStep.None else end(TrayDragCancel.INTERRUPTED)

    fun reset() {
        phase = TrayDragPhase.IDLE
        pointer = -1L
    }

    private fun end(reason: TrayDragCancel): TrayDragStep {
        reset()
        return TrayDragStep.Cancelled(reason)
    }

    companion object {
        /** The hold that picks a tile up. Shorter than the system long press (400 ms), which felt slow for a drag. */
        const val DEFAULT_HOLD_MS = 300L
    }
}

/** A rectangle in root coordinates (the Compose root view). */
data class RootBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** A point in the timeline view's own pixels, the space the native timeline hit-tests in. */
data class ViewPoint(val x: Float, val y: Float)

/** The timeline view point under a root coordinate, or null when the finger is outside the view ([bounds] null: not laid out yet). */
fun toTimelinePoint(rootX: Float, rootY: Float, bounds: RootBounds?): ViewPoint? =
    if (bounds != null && bounds.contains(rootX, rootY)) ViewPoint(rootX - bounds.left, rootY - bounds.top) else null

/** What the finger is over on the timeline, as the native hit-test says (it applies the zoom and both scroll offsets). */
data class TimelineSample(val point: ViewPoint, val hit: TimelineHit)

/** The hit-test of the timeline under the finger, or null if the finger is off the timeline. [hitTest] is the engine's. */
fun sampleTimeline(rootX: Float, rootY: Float, bounds: RootBounds?, hitTest: (Float, Float) -> TimelineHit): TimelineSample? {
    val point = toTimelinePoint(rootX, rootY, bounds) ?: return null
    return TimelineSample(point, hitTest(point.x, point.y))
}

/**
 * Scrolls the timeline while a tile is carried near its edges: horizontally near the left and right sides, vertically near the top
 * and bottom. Each axis only starts after the finger has stayed in its edge zone for [dwellMs], so crossing the left edge of the
 * timeline on the way in from a tray docked beside it does not make it jump. The speed per frame is the same ramp a clip drag uses.
 */
class TrayAutoScroll(private val dwellMs: Long = DEFAULT_DWELL_MS) {
    private var xSince = NONE
    private var ySince = NONE

    fun reset() {
        xSince = NONE
        ySince = NONE
    }

    /** Pixels to scroll this frame (x, y), for a finger at [p] in a timeline view of [width] x [height] pixels. */
    fun step(p: ViewPoint, width: Float, height: Float, density: Float, nowMs: Long): Pair<Float, Float> {
        val dx = edgeScroll(p.x, width, density, EDGE_ZONE_X_DP, EDGE_MAX_X_DP)
        val dy = edgeScroll(p.y, height, density, EDGE_ZONE_Y_DP, EDGE_MAX_Y_DP)
        xSince = dwell(dx, xSince, nowMs)
        ySince = dwell(dy, ySince, nowMs)
        return (if (ready(xSince, nowMs)) dx else 0f) to (if (ready(ySince, nowMs)) dy else 0f)
    }

    private fun dwell(speed: Float, since: Long, nowMs: Long): Long = when {
        speed == 0f -> NONE
        since == NONE -> nowMs
        else -> since
    }

    private fun ready(since: Long, nowMs: Long) = since != NONE && nowMs - since >= dwellMs

    companion object {
        const val DEFAULT_DWELL_MS = 250L
        private const val NONE = Long.MIN_VALUE
        const val EDGE_ZONE_X_DP = 56f
        const val EDGE_MAX_X_DP = 14f
        const val EDGE_ZONE_Y_DP = 36f
        const val EDGE_MAX_Y_DP = 10f
    }
}

/**
 * Pixels to scroll per frame for a finger at [pos] along an axis [extent] pixels long: zero in the middle, ramping up to
 * [maxDp] at the very end of either [zoneDp] strip, negative towards the start. An axis too short for two zones never scrolls.
 */
internal fun edgeScroll(pos: Float, extent: Float, density: Float, zoneDp: Float, maxDp: Float): Float {
    val zone = zoneDp * density
    if (extent <= 2 * zone) return 0f
    val maxSpeed = maxDp * density
    return when {
        pos < zone -> -maxSpeed * ((zone - pos) / zone).coerceIn(0f, 1f)
        pos > extent - zone -> maxSpeed * ((pos - (extent - zone)) / zone).coerceIn(0f, 1f)
        else -> 0f
    }
}

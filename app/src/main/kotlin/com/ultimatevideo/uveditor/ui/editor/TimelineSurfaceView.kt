package com.ultimatevideo.uveditor.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.DragEvent
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ultimatevideo.uveditor.engine.timeline.HitKind
import com.ultimatevideo.uveditor.engine.timeline.TimelineEngine
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit
import com.ultimatevideo.uveditor.ui.editor.tray.AssetKind
import com.ultimatevideo.uveditor.ui.editor.tray.findActivity
import com.ultimatevideo.uveditor.ui.editor.tray.isTrayAsset
import com.ultimatevideo.uveditor.ui.editor.tray.kindsOfMimes
import com.ultimatevideo.uveditor.ui.editor.tray.mimes
import com.ultimatevideo.uveditor.ui.editor.tray.persistReadAccess
import com.ultimatevideo.uveditor.ui.editor.tray.uris

/**
 * Receives clip-editing drags from the timeline canvas. A drag only reaches these callbacks when
 * [canDrag] accepts the clip under the finger; otherwise the same gesture scrolls the timeline.
 */
interface TimelineEditing {
    fun canDrag(hit: TimelineHit): Boolean
    fun onDragStart(hit: TimelineHit)

    /** [hit] is the hit-test at the current finger position; only its frame and track are meaningful. */
    fun onDragMove(hit: TimelineHit)
    fun onDragEnd(commit: Boolean)

    /** A lane header was long-pressed: the lane is picked up. [hit] is a `LANE_HEADER` hit-test. */
    fun onLaneDragStart(hit: TimelineHit) {}

    /** The finger moved with a lane picked up; [hit] is the hit-test at its position. */
    fun onLaneDragMove(hit: TimelineHit) {}

    /** The lane was released ([commit]) or the gesture was cancelled. */
    fun onLaneDragEnd(commit: Boolean) {}
}

/**
 * Receives media dragged over the canvas with the platform drag-and-drop: assets from the media tray, or
 * files from another app. The canvas turns the finger position into a hit-test; what a release would do is
 * decided by the editor (see `DropPlan.decideNew`).
 */
interface TimelineDropTarget {
    /** Files from another app entered the canvas; [kinds] come from their MIME types (they are not probed yet). */
    fun onExternalEnter(kinds: List<AssetKind>)

    /** The dragged media is over [hit] (also re-sent while the canvas auto-scrolls). */
    fun onHover(hit: TimelineHit)

    /** The dragged media left the canvas; it may come back. */
    fun onLeave()

    /** An asset from the tray was released over [hit]. */
    fun onTrayDrop(hit: TimelineHit)

    /** Files from another app were released over [hit]. */
    fun onExternalDrop(uris: List<String>, hit: TimelineHit)

    /** The drag is over for good (dropped elsewhere or cancelled): forget it. */
    fun onEnd()
}

/**
 * Surface the native renderer draws into. This view only forwards touch gestures to the engine;
 * all drawing and hit-testing happen in C++ so scrolling never triggers Compose recomposition.
 */
@SuppressLint("ViewConstructor")
class TimelineSurfaceView(
    context: Context,
    private val engine: TimelineEngine,
    private val onTap: (TimelineHit) -> Unit,
    private val editing: () -> TimelineEditing? = { null },
    private val dropTarget: () -> TimelineDropTarget? = { null },
    private val selecting: () -> TimelineSelecting? = { null },
) : SurfaceView(context), SurfaceHolder.Callback {

    private var hovering = false
    private var hoverIsTray = false

    // Media dragged in from outside keeps the timeline scrolling near its side edges, like a clip drag.
    private val hoverScroll = object : Runnable {
        override fun run() {
            if (!hovering) return
            val dx = edgeScrollSpeed(dragX, width.toFloat(), resources.displayMetrics.density)
            if (dx != 0f) {
                engine.scrollBy(dx, 0f)
                dropTarget()?.onHover(engine.hitTest(dragX, dragY))
            }
            postOnAnimation(this)
        }
    }

    private fun stopHover() {
        hovering = false
        removeCallbacks(hoverScroll)
    }

    private fun handleDrag(event: DragEvent): Boolean {
        val target = dropTarget() ?: return false
        val description = event.clipDescription
        return when (event.action) {
            DragEvent.ACTION_DRAG_STARTED ->
                description != null && (description.isTrayAsset() || kindsOfMimes(description.mimes()).isNotEmpty())
            DragEvent.ACTION_DRAG_ENTERED -> {
                hoverIsTray = description?.isTrayAsset() == true
                if (!hoverIsTray && description != null) target.onExternalEnter(kindsOfMimes(description.mimes()))
                hovering = true
                postOnAnimation(hoverScroll)
                true
            }
            DragEvent.ACTION_DRAG_LOCATION -> {
                dragX = event.x
                dragY = event.y
                target.onHover(engine.hitTest(event.x, event.y))
                true
            }
            DragEvent.ACTION_DRAG_EXITED -> {
                stopHover()
                target.onLeave()
                true
            }
            DragEvent.ACTION_DROP -> {
                stopHover()
                val hit = engine.hitTest(event.x, event.y)
                if (hoverIsTray) {
                    target.onTrayDrop(hit)
                } else {
                    val data = event.clipData
                    val uris = data?.uris().orEmpty()
                    if (uris.isEmpty()) {
                        target.onEnd()
                    } else {
                        // Access to the files lasts as long as the activity; keep it for later sessions when offered.
                        context.findActivity()?.requestDragAndDropPermissions(event)
                        uris.forEach { persistReadAccess(context, it) }
                        target.onExternalDrop(uris, hit)
                    }
                }
                true
            }
            DragEvent.ACTION_DRAG_ENDED -> {
                stopHover()
                target.onEnd()
                true
            }
            else -> false
        }
    }

    private var downHit: TimelineHit? = null
    private var downX = 0f
    private var downY = 0f

    // A rectangle dragged over empty space in select mode: the clips inside it join the selection on release.
    private var marquee = false
    private var dragging = false

    // A lane picked up by its header (long press): the finger then moves the lane instead of scrolling. The platform
    // gesture detector stops reporting scrolls after a long press, so the moves are read from the touch events.
    private var laneDragging = false
    private var dragX = 0f
    private var dragY = 0f

    // While a clip is dragged with the finger near a side edge the timeline keeps scrolling, so a
    // clip can be carried beyond what is on screen. Runs once per frame and moves the clip with it.
    private val edgeScroll = object : Runnable {
        override fun run() {
            if (!dragging) return
            val dx = edgeScrollSpeed(dragX, width.toFloat(), resources.displayMetrics.density)
            if (dx != 0f) {
                engine.scrollBy(dx, 0f)
                editing()?.onDragMove(engine.hitTest(dragX, dragY))
            }
            postOnAnimation(this)
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                engine.zoomBy(detector.scaleFactor, detector.focusX)
                return true
            }
        },
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                downHit = engine.hitTest(e.x, e.y)
                downX = e.x
                downY = e.y
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (dragging || marquee || laneDragging) return
                val hit = engine.hitTest(e.x, e.y)
                if (hit.kind == HitKind.LANE_HEADER) {
                    val lanes = editing() ?: return
                    laneDragging = true
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    lanes.onLaneDragStart(hit)
                    return
                }
                val target = selecting() ?: return
                if (hit.kind == HitKind.CLIP || hit.kind == HitKind.CLIP_LEFT_EDGE || hit.kind == HitKind.CLIP_RIGHT_EDGE) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    target.onLongPress(hit)
                }
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaleDetector.isInProgress || laneDragging) return true
                if (!dragging && !marquee) {
                    tryStartDrag()
                    if (!dragging) tryStartMarquee()
                }
                if (dragging) {
                    dragX = e2.x
                    dragY = e2.y
                    editing()?.onDragMove(engine.hitTest(e2.x, e2.y))
                } else if (marquee) {
                    engine.setMarquee(downX, downY, e2.x, e2.y)
                } else {
                    engine.scrollBy(distanceX, distanceY)
                }
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                // Finger velocity is opposite to the scroll offset direction.
                if (!scaleDetector.isInProgress && !dragging) engine.fling(-velocityX)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                onTap(engine.hitTest(e.x, e.y))
                return true
            }
        },
    )

    init {
        holder.addCallback(this)
        setOnDragListener { _, event -> handleDrag(event) }
    }

    /**
     * A playhead or clip near the screen edge would otherwise start the system back gesture.
     * Android honours at most [MAX_EXCLUSION_DP] of height per edge, so the strip reserved here
     * covers the ruler and the first track lanes, which is where grabs happen.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        val limit = (MAX_EXCLUSION_DP * resources.displayMetrics.density).toInt()
        systemGestureExclusionRects = listOf(Rect(0, 0, right - left, minOf(bottom - top, limit)))
    }

    private fun tryStartDrag() {
        val hit = downHit ?: return
        val handler = editing() ?: return
        if (!handler.canDrag(hit)) return
        dragging = true
        handler.onDragStart(hit)
        postOnAnimation(edgeScroll)
    }

    /** In select mode a drag that starts on empty lane space draws a selection rectangle instead of scrolling. */
    private fun tryStartMarquee() {
        val target = selecting() ?: return
        if (!target.selectMode) return
        val kind = downHit?.kind ?: return
        if (kind == HitKind.EMPTY_TRACK || kind == HitKind.ABOVE_LANES || kind == HitKind.NONE) marquee = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) = engine.surfaceCreated(holder.surface)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        engine.surfaceChanged(width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.surfaceDestroyed()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        if (laneDragging) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> editing()?.onLaneDragMove(engine.hitTest(event.x, event.y))
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    laneDragging = false
                    editing()?.onLaneDragEnd(commit = event.actionMasked == MotionEvent.ACTION_UP)
                }
            }
            return true
        }
        if (marquee && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            marquee = false
            if (event.actionMasked == MotionEvent.ACTION_UP) {
                val keys = engine.clipsInRect(downX, downY, event.x, event.y)
                if (keys.isNotEmpty()) selecting()?.onMarquee(keys)
            }
            engine.clearMarquee()
        }
        if (dragging && (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL)) {
            dragging = false
            editing()?.onDragEnd(commit = event.actionMasked == MotionEvent.ACTION_UP)
        }
        return true
    }
}

private const val MAX_EXCLUSION_DP = 200
private const val EDGE_ZONE_DP = 56f
private const val EDGE_MAX_SPEED_DP = 14f

/**
 * Pixels to scroll this frame for a finger at [x] in a view [width] pixels wide: zero away from
 * the sides, then growing to [EDGE_MAX_SPEED_DP] dp per frame at the very edge. Negative scrolls
 * back in time. Right of the view centre only the right zone counts and vice versa.
 */
internal fun edgeScrollSpeed(x: Float, width: Float, density: Float): Float {
    val zone = EDGE_ZONE_DP * density
    if (width <= 2 * zone) return 0f
    val maxSpeed = EDGE_MAX_SPEED_DP * density
    return when {
        x < zone -> -maxSpeed * ((zone - x) / zone).coerceIn(0f, 1f)
        x > width - zone -> maxSpeed * ((x - (width - zone)) / zone).coerceIn(0f, 1f)
        else -> 0f
    }
}

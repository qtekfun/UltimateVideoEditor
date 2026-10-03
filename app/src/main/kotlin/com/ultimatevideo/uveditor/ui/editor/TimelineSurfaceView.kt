package com.ultimatevideo.uveditor.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.ultimatevideo.uveditor.engine.timeline.TimelineEngine
import com.ultimatevideo.uveditor.engine.timeline.TimelineHit

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
) : SurfaceView(context), SurfaceHolder.Callback {

    private var downHit: TimelineHit? = null
    private var dragging = false
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
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (scaleDetector.isInProgress) return true
                if (!dragging) tryStartDrag()
                if (dragging) {
                    dragX = e2.x
                    dragY = e2.y
                    editing()?.onDragMove(engine.hitTest(e2.x, e2.y))
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

    override fun surfaceCreated(holder: SurfaceHolder) = engine.surfaceCreated(holder.surface)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        engine.surfaceChanged(width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.surfaceDestroyed()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
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

package com.ultimatevideo.uveditor.ui.editor

import android.annotation.SuppressLint
import android.content.Context
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

    private fun tryStartDrag() {
        val hit = downHit ?: return
        val handler = editing() ?: return
        if (!handler.canDrag(hit)) return
        dragging = true
        handler.onDragStart(hit)
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

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
 * Surface the native renderer draws into. This view only forwards touch gestures to the engine;
 * all drawing and hit-testing happen in C++ so scrolling never triggers Compose recomposition.
 */
@SuppressLint("ViewConstructor")
class TimelineSurfaceView(
    context: Context,
    private val engine: TimelineEngine,
    private val onTap: (TimelineHit) -> Unit,
) : SurfaceView(context), SurfaceHolder.Callback {

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
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (!scaleDetector.isInProgress) engine.scrollBy(distanceX, distanceY)
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                // Finger velocity is opposite to the scroll offset direction.
                if (!scaleDetector.isInProgress) engine.fling(-velocityX)
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

    override fun surfaceCreated(holder: SurfaceHolder) = engine.surfaceCreated(holder.surface)

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
        engine.surfaceChanged(width, height)

    override fun surfaceDestroyed(holder: SurfaceHolder) = engine.surfaceDestroyed()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}

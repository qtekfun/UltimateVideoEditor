package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.ClipTransform
import kotlin.math.IEEErem
import kotlin.math.min

/** Where the project canvas sits inside the preview view, in view pixels. */
data class CanvasRect(val x: Float, val y: Float, val width: Float, val height: Float) {
    val isEmpty: Boolean get() = width <= 0f || height <= 0f
}

/**
 * Maps touch input on the preview view to clip transforms. Pure maths so it can be unit tested.
 * The canvas is letterboxed into the view exactly like the native compositor does
 * (`letterbox()` in render/layout_math.h).
 */
object PreviewGeometry {

    /** Largest centred rectangle with the canvas aspect that fits a [boxWidth] x [boxHeight] view. */
    fun canvasRect(canvasWidth: Int, canvasHeight: Int, boxWidth: Float, boxHeight: Float): CanvasRect {
        if (canvasWidth <= 0 || canvasHeight <= 0 || boxWidth <= 0f || boxHeight <= 0f) return CanvasRect(0f, 0f, 0f, 0f)
        val scale = min(boxWidth / canvasWidth, boxHeight / canvasHeight)
        val width = canvasWidth * scale
        val height = canvasHeight * scale
        return CanvasRect((boxWidth - width) / 2f, (boxHeight - height) / 2f, width, height)
    }

    /** View pixels per canvas pixel. */
    fun viewScale(rect: CanvasRect, canvasWidth: Int): Float = if (canvasWidth > 0) rect.width / canvasWidth else 0f

    /**
     * Applies one gesture step to [base]: [panX]/[panY] are in canvas pixels, [zoom] multiplies both
     * scales, [rotationDegrees] is added (clockwise). Scale is kept within the allowed range and
     * rotation within (-180, 180].
     */
    fun applyGesture(base: ClipTransform, panX: Double, panY: Double, zoom: Double, rotationDegrees: Double): ClipTransform {
        val safeZoom = if (zoom.isFinite() && zoom > 0.0) zoom else 1.0
        val safeRotation = if (rotationDegrees.isFinite()) rotationDegrees else 0.0
        val safePanX = if (panX.isFinite()) panX else 0.0
        val safePanY = if (panY.isFinite()) panY else 0.0
        return base.copy(
            positionX = base.positionX + safePanX,
            positionY = base.positionY + safePanY,
            scaleX = (base.scaleX * safeZoom).coerceIn(ClipTransform.MIN_SCALE, ClipTransform.MAX_SCALE),
            scaleY = (base.scaleY * safeZoom).coerceIn(ClipTransform.MIN_SCALE, ClipTransform.MAX_SCALE),
            rotationDegrees = normalizeDegrees(base.rotationDegrees + safeRotation),
        )
    }

    /** Wraps an angle into (-180, 180]. */
    /** A quarter turn on top of the current rotation: [delta] is +90 (clockwise) or -90, the result stays in (-180, 180]. */
    fun turnBy(degrees: Double, delta: Double): Double = normalizeDegrees(degrees + delta)

    fun normalizeDegrees(degrees: Double): Double {
        val wrapped = degrees.IEEErem(360.0)
        return if (wrapped <= -180.0) wrapped + 360.0 else wrapped
    }
}

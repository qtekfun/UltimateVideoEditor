package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Parts of a vertical frame that each app covers with its own interface (title bar, caption,
 * buttons). The fractions are approximate and measured on a 9:16 canvas: [top] and [bottom] of the
 * canvas height, [left] and [right] of its width. Text and faces should stay inside what is left.
 */
enum class SafeZonePlatform(val label: String, val top: Double, val bottom: Double, val left: Double, val right: Double) {
    TIKTOK("TikTok", top = 0.07, bottom = 0.23, left = 0.06, right = 0.12),
    REELS("Instagram Reels", top = 0.13, bottom = 0.18, left = 0.06, right = 0.12),
    SHORTS("YouTube Shorts", top = 0.07, bottom = 0.25, left = 0.06, right = 0.13),
}

/** The safe rectangle inside a canvas of [rect]'s size, in the same coordinates as [rect]. */
fun SafeZonePlatform.safeRect(rect: CanvasRect): CanvasRect = CanvasRect(
    x = rect.x + rect.width * left.toFloat(),
    y = rect.y + rect.height * top.toFloat(),
    width = rect.width * (1.0 - left - right).toFloat(),
    height = rect.height * (1.0 - top - bottom).toFloat(),
)

/**
 * Dims the covered edges and outlines the safe area over the preview. It does not take touches, so
 * gestures still reach the preview underneath. The canvas is letterboxed into the view the same way
 * the compositor does it.
 */
@Composable
fun SafeZoneOverlay(platform: SafeZonePlatform, canvasWidth: Int, canvasHeight: Int, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val canvas = PreviewGeometry.canvasRect(canvasWidth, canvasHeight, size.width, size.height)
        if (canvas.isEmpty) return@Canvas
        val safe = platform.safeRect(canvas)
        val dim = Color(0x66FF3B30)
        // Four bands around the safe area, drawn so they do not overlap each other.
        drawRect(dim, Offset(canvas.x, canvas.y), Size(canvas.width, safe.y - canvas.y))
        drawRect(dim, Offset(canvas.x, safe.y + safe.height), Size(canvas.width, canvas.y + canvas.height - safe.y - safe.height))
        drawRect(dim, Offset(canvas.x, safe.y), Size(safe.x - canvas.x, safe.height))
        drawRect(dim, Offset(safe.x + safe.width, safe.y), Size(canvas.x + canvas.width - safe.x - safe.width, safe.height))
        drawRect(Color.White, Offset(safe.x, safe.y), Size(safe.width, safe.height), style = Stroke(width = 2f))
    }
}

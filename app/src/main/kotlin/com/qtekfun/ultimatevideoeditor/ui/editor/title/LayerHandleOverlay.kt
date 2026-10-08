package com.qtekfun.ultimatevideoeditor.ui.editor.title

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.domain.ShapeLayer
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorState
import com.qtekfun.ultimatevideoeditor.ui.editor.PreviewGeometry

/**
 * Marks the selected layer of a multilayer title on the preview: a ring and cross at its centre, plus
 * its outline when it is a shape. The preview's drag, pinch and twist gestures move that layer while it
 * is selected, so this is what the user is holding. Draws nothing when no layer is selected.
 */
@Composable
fun LayerHandleOverlay(state: EditorState, modifier: Modifier = Modifier) {
    val index = state.selectedTitleLayer ?: return
    val clip = state.selectedClip ?: return
    val layer = clip.title?.layers?.getOrNull(index) ?: return
    val pose = state.selectedPose ?: clip.transform
    val canvasWidth = state.canvasWidth
    val canvasHeight = state.canvasHeight
    Canvas(modifier = modifier) {
        val rect = PreviewGeometry.canvasRect(canvasWidth, canvasHeight, size.width, size.height)
        if (rect.isEmpty) return@Canvas
        val viewScale = PreviewGeometry.viewScale(rect, canvasWidth)
        // The title is centred on the canvas, then moved and scaled by the clip's pose; the layer sits inside it.
        val cx = rect.x + rect.width / 2f + ((pose.positionX + layer.placement.offsetX * canvasWidth * pose.scaleX) * viewScale).toFloat()
        val cy = rect.y + rect.height / 2f + ((pose.positionY + layer.placement.offsetY * canvasHeight * pose.scaleY) * viewScale).toFloat()
        val center = Offset(cx, cy)
        val ring = 14.dp.toPx()
        val stroke = Stroke(width = 2.dp.toPx())
        val color = Color(HANDLE_COLOR)
        drawCircle(color = color, radius = ring, center = center, style = stroke)
        drawLine(color, Offset(cx - ring * 1.6f, cy), Offset(cx + ring * 1.6f, cy), strokeWidth = stroke.width)
        drawLine(color, Offset(cx, cy - ring * 1.6f), Offset(cx, cy + ring * 1.6f), strokeWidth = stroke.width)
        if (layer is ShapeLayer) {
            val w = (layer.widthFraction * canvasWidth * layer.placement.scale * pose.scaleX * viewScale).toFloat()
            val h = (layer.heightFraction * canvasHeight * layer.placement.scale * pose.scaleY * viewScale).toFloat()
            rotate(degrees = (layer.placement.rotationDegrees + pose.rotationDegrees).toFloat(), pivot = center) {
                drawRect(color = color, topLeft = Offset(cx - w / 2f, cy - h / 2f), size = Size(w, h), style = stroke)
            }
        }
    }
}

private const val HANDLE_COLOR = 0xFF00E5FF

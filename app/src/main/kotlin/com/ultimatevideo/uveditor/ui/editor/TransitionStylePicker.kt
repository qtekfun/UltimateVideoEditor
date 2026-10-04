package com.ultimatevideo.uveditor.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.ultimatevideo.uveditor.domain.CrossfadeCurve
import com.ultimatevideo.uveditor.domain.Transition
import com.ultimatevideo.uveditor.domain.TransitionDirection
import com.ultimatevideo.uveditor.domain.TransitionLook
import com.ultimatevideo.uveditor.domain.TransitionLooks
import com.ultimatevideo.uveditor.domain.TransitionType

/**
 * The look of the selected transition: one chip per type, the direction chips for the directional ones, and a
 * three-frame preview of what the chosen look does (an outgoing blue picture and an incoming orange one drawn with
 * the same function the preview and the exporter use).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TransitionStylePicker(transition: Transition, onIntent: (EditorIntent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(text = "Look", style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (type in TransitionType.entries) {
                FilterChip(
                    selected = transition.type == type,
                    onClick = { onIntent(EditorIntent.SetTransitionStyle(type, transition.direction)) },
                    label = { Text(type.label) },
                    modifier = Modifier.semantics { contentDescription = "Transition look ${type.label}" },
                )
            }
        }
        if (transition.type.hasDirection) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = "Direction", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
                for (direction in TransitionDirection.entries) {
                    FilterChip(
                        selected = transition.direction == direction,
                        onClick = { onIntent(EditorIntent.SetTransitionStyle(transition.type, direction)) },
                        label = { Text(direction.label) },
                        modifier = Modifier.semantics { contentDescription = "Transition direction ${direction.label}" },
                    )
                }
            }
        }
        TransitionPreview(transition.type, transition.direction)
    }
}

private val OUTGOING = Color(0xFF2E6FBF)
private val INCOMING = Color(0xFFE8892B)

/** Three moments (a quarter, half and three quarters of the way) of the transition, drawn on a 16:9 canvas. */
@Composable
private fun TransitionPreview(type: TransitionType, direction: TransitionDirection) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp).semantics { contentDescription = "Preview of the ${type.label} transition" },
    ) {
        for (fraction in listOf(0.25, 0.5, 0.75)) {
            Canvas(modifier = Modifier.weight(1f).height(44.dp)) {
                val look = TransitionLook(type, direction, PREVIEW_FRAMES)
                val k = (fraction * PREVIEW_FRAMES).toLong().coerceIn(0L, PREVIEW_FRAMES - 1)
                drawPicture(OUTGOING, look, incoming = false, k = k)
                drawPicture(INCOMING, look, incoming = true, k = k)
            }
        }
    }
}

private const val PREVIEW_FRAMES = 20L
private const val PREVIEW_CANVAS_WIDTH = 1920
private const val PREVIEW_CANVAS_HEIGHT = 1080

/** One picture of the preview with the transition's pose change applied: the wipe mask is shown as a clip rectangle. */
private fun DrawScope.drawPicture(colour: Color, look: TransitionLook, incoming: Boolean, k: Long) {
    val mod = TransitionLooks.modAt(look, incoming, k, PREVIEW_CANVAS_WIDTH, PREVIEW_CANVAS_HEIGHT, k)
    val fade = if (incoming && look.type.fadesVideo) CrossfadeCurve.progress(k, look.frames) else 1.0
    val alpha = (fade * mod.opacity).toFloat().coerceIn(0f, 1f)
    if (alpha <= 0f) return
    val pixelsPerCanvas = size.width / PREVIEW_CANVAS_WIDTH
    val mask = mod.mask
    val box = Size(size.width, size.height)
    fun drawBox() {
        translate(left = (mod.offsetX * pixelsPerCanvas).toFloat(), top = (mod.offsetY * pixelsPerCanvas).toFloat()) {
            rotate(mod.rotationDegrees.toFloat(), pivot = Offset(size.width / 2f, size.height / 2f)) {
                scale(mod.scale.toFloat(), pivot = Offset(size.width / 2f, size.height / 2f)) {
                    drawRect(colour.copy(alpha = alpha), size = box)
                }
            }
        }
    }
    if (mask == null) {
        drawBox()
    } else {
        // The mask is a fraction of the picture's box, -0.5..0.5 on both axes.
        val left = ((mask.centerX - mask.width / 2 + 0.5) * size.width).toFloat().coerceIn(0f, size.width)
        val right = ((mask.centerX + mask.width / 2 + 0.5) * size.width).toFloat().coerceIn(0f, size.width)
        val top = ((mask.centerY - mask.height / 2 + 0.5) * size.height).toFloat().coerceIn(0f, size.height)
        val bottom = ((mask.centerY + mask.height / 2 + 0.5) * size.height).toFloat().coerceIn(0f, size.height)
        clipRect(left, top, right, bottom) { drawBox() }
    }
}

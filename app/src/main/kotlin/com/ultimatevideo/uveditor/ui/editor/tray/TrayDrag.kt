package com.ultimatevideo.uveditor.ui.editor.tray

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** What the editor does with a carried tile: it knows the timeline, the tray does not. All positions are root coordinates. */
interface TrayDragSink {
    /** A tile was picked up. */
    fun begin(assetId: String)

    /** The finger moved (also called when the timeline scrolled under a still finger). */
    fun move(x: Float, y: Float)

    /** The finger lifted at [x], [y]; returns true when the asset was released over the timeline and is placed. */
    fun drop(x: Float, y: Float): Boolean

    /** The drag ended without placing anything. */
    fun cancel()

    /** One animation frame while a tile is carried; the timeline scrolls here when the finger is near its edge. */
    fun frame(nowMs: Long)
}

/** The tile being carried, for the ghost. */
@Stable
class TrayCarry(
    val assetId: String,
    val name: String,
    val duration: String,
    val kind: AssetKind,
    val thumbnail: ImageBitmap?,
    /** Root position of the finger; the ghost follows it. */
    val position: Offset,
    /** Root position of the tile's centre, where the ghost flies back to on a cancel. */
    val home: Offset,
    val returning: Boolean = false,
)

/**
 * Own pointer tracking for dragging a media tray tile onto the timeline (LumaFusion style), instead of the platform drag
 * and drop: see DECISIONS.md "Tray drag". The tile's gesture ([detectTrayDrag]) keeps receiving the finger's events after it
 * leaves the tile, so the drag can run anywhere on screen; [TrayDragGhost] draws what is carried above everything; the
 * [TrayDragSink] (the editor) does the timeline side. Created once per editor screen.
 */
@Stable
class TrayDragController {
    /** The tile in hand, or null. Read by the ghost and by the tray (which fades while a tile is carried). */
    var carry: TrayCarry? by mutableStateOf(null)
        private set

    /** The drag is active: a tile is picked up and not yet dropped or cancelled. */
    val isCarrying: Boolean get() = carry?.returning == false

    /** Set by the editor. */
    var sink: TrayDragSink? = null

    /** Moves an asset to the place of another in the library (a drop on a tile of the tray). */
    var onReorder: (assetId: String, toIndex: Int) -> Unit = { _, _ -> }

    /** Index of an asset in the library, for [onReorder]. */
    var indexOf: (assetId: String) -> Int = { -1 }

    private val tiles = HashMap<String, LayoutCoordinates>()

    /** Registers where a tile is (for drops on the tray) and returns the unregister function. */
    fun registerTile(assetId: String, coordinates: LayoutCoordinates) {
        tiles[assetId] = coordinates
    }

    fun unregisterTile(assetId: String) {
        tiles.remove(assetId)
    }

    internal fun pickUp(carry: TrayCarry) {
        this.carry = carry
        sink?.begin(carry.assetId)
    }

    internal fun move(x: Float, y: Float) {
        val current = carry ?: return
        if (current.returning) return
        carry = TrayCarry(current.assetId, current.name, current.duration, current.kind, current.thumbnail, Offset(x, y), current.home)
        sink?.move(x, y)
    }

    /** The finger lifted at [x], [y]: place on the timeline, or reorder over another tile, else fly back. */
    internal fun drop(x: Float, y: Float) {
        val current = carry ?: return
        if (current.returning) return
        if (sink?.drop(x, y) == true) {
            carry = null
            return
        }
        val target = tileAt(x, y)?.takeIf { it != current.assetId }
        if (target != null) {
            onReorder(current.assetId, indexOf(target))
            carry = null
        } else {
            flyBack(current, notifySink = false)
        }
    }

    /** Back, a second finger, or the tile going away: forget the drag; the ghost returns to the tile. */
    fun cancel() {
        val current = carry ?: return
        if (current.returning) return
        flyBack(current, notifySink = true)
    }

    /** The ghost finished flying back. */
    internal fun finishReturn() {
        if (carry?.returning == true) carry = null
    }

    private fun flyBack(current: TrayCarry, notifySink: Boolean) {
        carry = TrayCarry(current.assetId, current.name, current.duration, current.kind, current.thumbnail, current.position, current.home, returning = true)
        if (notifySink) sink?.cancel()
    }

    private fun tileAt(x: Float, y: Float): String? = tiles.entries.firstOrNull { (_, c) ->
        c.isAttached && c.boundsInRoot().let { x >= it.left && x < it.right && y >= it.top && y < it.bottom }
    }?.key
}

/**
 * The gesture of a tile: a press held for [TrayDragMachine.DEFAULT_HOLD_MS] picks the tile up (with [onPickedUp] for the haptic
 * tick), then every move of that finger is reported wherever it goes, and the lift drops. Before the hold nothing is consumed,
 * so a tap still clicks and a swipe still scrolls the tray. The caller adds this modifier after `clickable`, so the lift of a
 * drag is consumed before the click sees it.
 */
internal suspend fun PointerInputScope.detectTrayDrag(
    controller: TrayDragController,
    enabled: Boolean,
    carryAt: (Offset) -> TrayCarry,
    rootOf: (Offset) -> Offset,
    onPickedUp: () -> Unit,
) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!enabled || controller.carry != null) return@awaitEachGesture
        val machine = TrayDragMachine(slopPx = slop)
        val id = down.id.value
        val start = rootOf(down.position)
        machine.down(id, start.x, start.y, SystemClock.uptimeMillis())
        var carrying = false
        try {
            // Armed: watch only. The hold may run out while the finger rests, so each wait is capped by what is left of it.
            while (machine.phase == TrayDragPhase.ARMED) {
                val event = withTimeoutOrNull(machine.remainingHoldMs(SystemClock.uptimeMillis()).coerceAtLeast(1)) { awaitPointerEvent() }
                val step = if (event == null) {
                    machine.holdElapsed()
                } else {
                    if (event.changes.any { it.id != down.id && it.changedToDown() }) {
                        machine.secondPointer()
                    } else {
                        val change = event.changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                        val p = rootOf(change.position)
                        when {
                            change.isConsumed -> machine.interrupt()
                            change.changedToUp() -> machine.up(id, p.x, p.y)
                            else -> machine.move(id, p.x, p.y, SystemClock.uptimeMillis())
                        }
                    }
                }
                when (step) {
                    is TrayDragStep.PickedUp -> {
                        carrying = true
                        onPickedUp()
                        controller.pickUp(carryAt(Offset(step.x, step.y)))
                    }
                    is TrayDragStep.Cancelled, TrayDragStep.Tapped -> return@awaitEachGesture
                    else -> Unit
                }
            }
            // Carrying: the finger belongs to the drag until it lifts. Consuming keeps the tray from scrolling and the click from firing.
            while (carrying) {
                val event = awaitPointerEvent()
                if (!controller.isCarrying) break
                val second = event.changes.any { it.id != down.id && it.changedToDown() }
                val change = event.changes.firstOrNull { it.id == down.id }
                event.changes.forEach { it.consume() }
                if (second) {
                    machine.secondPointer()
                    controller.cancel()
                    carrying = false
                    break
                }
                if (change == null) break
                val p = rootOf(change.position)
                when (if (change.changedToUp()) machine.up(id, p.x, p.y) else machine.move(id, p.x, p.y, SystemClock.uptimeMillis())) {
                    is TrayDragStep.Dropped -> {
                        carrying = false
                        controller.drop(p.x, p.y)
                    }
                    is TrayDragStep.Moved -> controller.move(p.x, p.y)
                    else -> Unit
                }
            }
        } finally {
            // The tile left composition, the system cancelled the touch, or the loop ended early: never leave a drag half done.
            if (carrying) controller.cancel()
        }
    }
}

private val GhostSize = 96.dp
private val GhostLift = 56.dp

/**
 * The tile in hand, drawn above the whole editor at the finger: its picture with name and duration, a little larger than the tile,
 * with a soft shadow. It takes no touches. After a cancel it flies back to its tile and fades.
 */
@Composable
fun TrayDragGhost(controller: TrayDragController, modifier: Modifier = Modifier) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        val carry = controller.carry ?: return@Box
        val density = LocalDensity.current
        val sizePx = with(density) { GhostSize.toPx() }
        val liftPx = with(density) { GhostLift.toPx() }
        val target = if (carry.returning) carry.home else Offset(carry.position.x, carry.position.y - liftPx)
        val centre by animateOffsetAsState(target, if (carry.returning) tween(220) else snap(), label = "ghost")
        val alpha by animateFloatAsState(if (carry.returning) 0f else 1f, tween(220), label = "ghostAlpha")
        val scale by animateFloatAsState(if (carry.returning) 1f else 1.08f, tween(120), label = "ghostScale")
        LaunchedEffect(carry.returning) {
            if (carry.returning) {
                kotlinx.coroutines.delay(240)
                controller.finishReturn()
            }
        }
        // Frames drive the timeline's edge scrolling while the tile is carried.
        LaunchedEffect(carry.assetId, carry.returning) {
            if (!carry.returning) {
                while (true) withFrameMillis { controller.sink?.frame(SystemClock.uptimeMillis()) }
            }
        }
        Box(
            Modifier
                .offset { IntOffset((centre.x - origin.x - sizePx / 2).roundToInt(), (centre.y - origin.y - sizePx / 2).roundToInt()) }
                .size(GhostSize)
                .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                .shadow(12.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF1B1F2A))
                .clearAndSetSemantics { },
        ) {
            if (carry.thumbnail != null) {
                Image(carry.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    when (carry.kind) { AssetKind.AUDIO -> "♪"; AssetKind.PHOTO -> "▣"; AssetKind.VIDEO -> "▶" },
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Text(
                carry.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 10.sp,
                color = Color.White,
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 4.dp),
            )
            if (carry.duration.isNotEmpty()) {
                Text(
                    carry.duration,
                    fontSize = 10.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).background(Color(0xCC000000), RoundedCornerShape(3.dp)).padding(horizontal = 3.dp),
                )
            }
        }
    }
}

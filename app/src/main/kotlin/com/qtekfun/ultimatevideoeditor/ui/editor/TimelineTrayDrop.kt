package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.RootBounds
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayAutoScroll
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayDragSink
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.ViewPoint
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.sampleTimeline

/**
 * The timeline's side of a media tray drag. The tray reports the finger in root coordinates; this turns them into the timeline
 * view's pixels, asks the native timeline what is there (frame and lane, with its zoom and both scroll offsets applied) and feeds
 * the editor's tray drag intents, which show the same lane highlight, insert/overwrite indicator and snap line as moving a clip
 * and commit the drop as one undo step. Near the timeline's edges it scrolls it, like a clip drag does.
 */
internal class TimelineTrayDrop(
    private val hitTest: (Float, Float) -> TimelineHit,
    private val scrollBy: (Float, Float) -> Unit,
    private val onIntent: (EditorIntent) -> Unit,
    private val density: Float,
) : TrayDragSink {
    /** Where the timeline view is, in root coordinates; set by the layout. */
    var bounds: RootBounds? = null

    private val autoScroll = TrayAutoScroll()
    private var lastX = 0f
    private var lastY = 0f
    private var over = false

    override fun begin(assetId: String) {
        autoScroll.reset()
        over = false
        onIntent(EditorIntent.TrayDragStart(assetId))
    }

    override fun move(x: Float, y: Float) {
        lastX = x
        lastY = y
        hover()
    }

    override fun drop(x: Float, y: Float): Boolean {
        lastX = x
        lastY = y
        hover()
        val placed = over
        over = false
        onIntent(EditorIntent.TrayDragEnd(commit = placed))
        return placed
    }

    override fun cancel() {
        over = false
        onIntent(EditorIntent.TrayDragEnd(commit = false))
    }

    override fun frame(nowMs: Long) {
        val b = bounds ?: return
        if (!over) {
            autoScroll.reset()
            return
        }
        val (dx, dy) = autoScroll.step(
            ViewPoint(lastX - b.left, lastY - b.top),
            b.width,
            b.height,
            density,
            nowMs,
        )
        if (dx == 0f && dy == 0f) return
        scrollBy(dx, dy)
        // The content moved under a still finger: the frame and lane under it changed.
        hover()
    }

    private fun hover() {
        val sample = sampleTimeline(lastX, lastY, bounds, hitTest)
        if (sample == null) {
            if (over) onIntent(EditorIntent.TrayDragLeave)
            over = false
            return
        }
        over = true
        onIntent(EditorIntent.TrayDragMove(sample.hit.frame, sample.hit.trackIndex, dragZoneOf(sample.hit)))
    }
}

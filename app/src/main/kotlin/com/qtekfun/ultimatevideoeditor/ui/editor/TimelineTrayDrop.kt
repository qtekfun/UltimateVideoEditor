package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetKind
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

    /** The file of a linked folder being carried, or null while an asset of the library is. */
    private var fileUri: String? = null
    private var lastHit: TimelineHit? = null

    override fun begin(assetId: String) {
        autoScroll.reset()
        over = false
        fileUri = null
        onIntent(EditorIntent.TrayDragStart(assetId))
    }

    override fun beginFile(uri: String, kind: AssetKind) {
        autoScroll.reset()
        over = false
        fileUri = uri
        // Only its kind is known until it is added, so the indicator uses a stand-in clip, as for a file dragged in from another app.
        onIntent(EditorIntent.ExternalDragStart(listOf(kind)))
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
        val file = fileUri
        val hit = lastHit
        over = false
        fileUri = null
        if (file != null) {
            // Released over the timeline the file is added where it was dropped; anywhere else nothing happens.
            if (placed && hit != null) onIntent(EditorIntent.ExternalDrop(listOf(file), hit.frame, hit.trackIndex, dragZoneOf(hit)))
            else onIntent(EditorIntent.TrayDragEnd(commit = false))
            return placed && hit != null
        }
        onIntent(EditorIntent.TrayDragEnd(commit = placed))
        return placed
    }

    override fun cancel() {
        over = false
        fileUri = null
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
        lastHit = sample.hit
        onIntent(EditorIntent.TrayDragMove(sample.hit.frame, sample.hit.trackIndex, dragZoneOf(sample.hit)))
    }
}

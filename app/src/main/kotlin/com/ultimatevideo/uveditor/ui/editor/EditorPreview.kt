package com.ultimatevideo.uveditor.ui.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.engine.preview.DecoderLimits
import com.ultimatevideo.uveditor.engine.preview.DecoderPlanner
import com.ultimatevideo.uveditor.engine.preview.LayerPlacement
import com.ultimatevideo.uveditor.engine.preview.PreviewEngine
import com.ultimatevideo.uveditor.engine.preview.PreviewException
import com.ultimatevideo.uveditor.engine.preview.PreviewLayer
import com.ultimatevideo.uveditor.engine.title.AndroidTitleRasterizer
import com.ultimatevideo.uveditor.engine.title.TitleKeyCache
import com.ultimatevideo.uveditor.engine.title.TitleRasterException
import com.ultimatevideo.uveditor.engine.title.TitleRasterizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import com.ultimatevideo.uveditor.domain.ClipFx

/**
 * One layer of what the preview should show. Frames are in [fpsNum]/[fpsDen] units: the project
 * rate, since source ranges are kept in project frames. A layer with a [title] is text drawn on
 * the canvas and has no media ([assetKey], [uri] and [sourceFrame] are unused).
 */
data class PreviewRequest(
    val assetKey: Int,
    val uri: String,
    val sourceFrame: Long,
    val fpsNum: Int,
    val fpsDen: Int,
    val transform: ClipTransform = ClipTransform.IDENTITY,
    /** Exclusive source frame where the clip ends; playback holds its last frame there. */
    val endFrame: Long? = null,
    val title: TitleContent? = null,
    /** The clip plays its source backwards: the decoder keeps its window behind the frame, not ahead. */
    val reverse: Boolean = false,
    /** Effects, blend mode and mask of the layer. */
    val fx: ClipFx = ClipFx.NONE,
)

/** The whole composite: the project canvas and its layers, bottom layer first. */
data class PreviewScene(val canvasWidth: Int, val canvasHeight: Int, val layers: List<PreviewRequest>)

internal fun ClipTransform.toPlacement() = LayerPlacement(
    positionX = positionX.toFloat(),
    positionY = positionY.toFloat(),
    scaleX = scaleX.toFloat(),
    scaleY = scaleY.toFloat(),
    rotationDegrees = rotationDegrees.toFloat(),
    opacity = opacity.toFloat(),
)

/**
 * Connects the editing session to the native preview: opens each asset lazily (off the main
 * thread, since opening does blocking I/O), keeps no more decoders open than the device allows,
 * and sends the composite to the native compositor. All methods except the internal open must be
 * called on the main thread.
 *
 * Failures are reported through [onError] and never swallowed. The preview keeps its last frame
 * when there is nothing to show. When a stack has more layers than the device has hardware
 * decoders, the top layers are shown and the lower ones are left out, with a message.
 */
class EditorPreview(
    private val context: Context,
    private val scope: CoroutineScope,
    private val maxDecoders: Int = DecoderLimits.maxPreviewDecoders(),
    private val rasterizer: TitleRasterizer = AndroidTitleRasterizer(),
    private val onError: (String) -> Unit,
) : AutoCloseable {

    private val main = Handler(Looper.getMainLooper())

    /** Null if the native preview could not start; the screen then shows a placeholder. */
    val engine: PreviewEngine? = try {
        PreviewEngine.create { main.post { onError("Preview error: ${it.message}") } }
    } catch (e: PreviewException) {
        onError("The preview could not start: ${e.message}")
        null
    }

    /** Open assets, least recently shown first. */
    private val open = LinkedHashSet<Int>()
    private val opening = HashSet<Int>()
    private val failed = HashSet<Int>()
    private var latest: PreviewScene? = null
    private val anchor = PreviewAnchor(DRIFT_THRESHOLD_FRAMES)

    // `adb shell setprop log.tag.UVSync DEBUG` before opening the editor prints the drift between
    // the audio clock and the native preview clock every few seconds (see scripts/av-drift-test.sh).
    private val syncLogging = Log.isLoggable(SYNC_TAG, Log.DEBUG)
    private val syncStats = SyncStats()
    // True while [follow] drives the preview; the screen's next tick then picks up newly opened assets.
    private var following = false
    private var reportedSkipped: Set<Int> = emptySet()
    private val titleKeys = TitleKeyCache()
    private val brokenTitles = HashSet<Int>()

    /** Shows [scene] as a still frame (paused, scrubbing, editing). Stops any native playback. */
    fun show(scene: PreviewScene) {
        val engine = engine ?: return
        following = false
        anchor.reset()
        val ready = prepare(engine, scene)
        engine.setScene(
            scene.canvasWidth,
            scene.canvasHeight,
            ready.map { it.toLayer(withEnd = false) },
        )
    }

    /**
     * Keeps the preview in step with playback. [scene] is the composite at [heardFrame], the
     * project frame the audio device is playing. The native clock runs by itself between calls; it
     * is re-anchored only when the composition changes or it drifts from the audio by more than
     * [DRIFT_THRESHOLD_FRAMES]. Where no layer has a clip under the playhead (a gap) playback of
     * the preview stops and the last frame stays up.
     */
    fun follow(scene: PreviewScene, heardFrame: Long, fps: FrameRate, nowNanos: Long = System.nanoTime()) {
        val engine = engine ?: return
        following = true
        val ready = prepare(engine, scene)
        if (ready.isEmpty()) {
            if (anchor.isActive) engine.pause()
            anchor.reset()
            return
        }
        // The composition is the same while each clip's source range maps linearly at 1x to the
        // timeline, which is what the offset (source frame minus playhead) captures. A retimed clip's
        // offset changes every frame, so it re-anchors every tick, like an animated one.
        val composition = ready.map {
            val request = it.request
            FollowedLayer(
                assetKey = request.assetKey,
                titleKey = it.titleKey,
                offset = if (it.titleKey != 0) 0 else request.sourceFrame - heardFrame,
                reverse = request.reverse,
                endFrame = request.endFrame,
                transform = request.transform,
            )
        }
        val reanchor = anchor.needsReanchor(composition, heardFrame, nowNanos, fps)
        if (syncLogging) syncStats.record(heardFrame, anchor.expectedFrame(nowNanos, fps), reanchor, nowNanos)
        if (!reanchor) return
        engine.playScene(
            scene.canvasWidth,
            scene.canvasHeight,
            ready.map { it.toLayer(withEnd = true) },
            fps.num,
            fps.den,
        )
        anchor.anchor(composition, heardFrame, nowNanos)
    }

    /** Stops the native clock after [follow] without touching what is on screen. */
    fun stopFollowing() {
        if (!following) return
        following = false
        anchor.reset()
        engine?.pause()
    }

    private data class FollowedLayer(
        val assetKey: Int,
        val titleKey: Int,
        val offset: Long,
        val reverse: Boolean,
        val endFrame: Long?,
        val transform: ClipTransform,
    )

    /** Plans decoders for [scene], opens what is missing and returns the layers that can be drawn now. */
    private fun prepare(engine: PreviewEngine, scene: PreviewScene): List<Ready> {
        latest = scene
        val media = scene.layers.filter { it.title == null }
        val neededTopFirst = media.asReversed().map { it.assetKey }.filter { it !in failed }
        val plan = DecoderPlanner.plan(maxDecoders, neededTopFirst, open.toList())

        for (key in plan.toClose) {
            open -= key
            engine.closeAsset(key)
        }
        reportSkipped(plan.skipped)
        for (layer in media) {
            val key = layer.assetKey
            if (key in plan.render && key !in open && key !in opening) openThen(engine, layer)
        }
        // Layers whose asset is still opening are left out for now; the scene is resent when they are ready.
        val readyRequests = scene.layers.filter { it.title != null || (it.assetKey in open && it.assetKey in plan.render) }
        for (layer in readyRequests) if (layer.title == null) touch(layer.assetKey)
        val ready = readyRequests.mapNotNull { layer ->
            val title = layer.title
            if (title == null) {
                Ready(layer, titleKey = 0)
            } else {
                titleKeyFor(engine, scene, title)?.let { Ready(layer, titleKey = it) }
            }
        }
        for (key in titleKeys.drain()) engine.releaseTitle(key)
        return ready
    }

    /** A layer that can be drawn now; [titleKey] is the uploaded raster of a title layer, else 0. */
    private data class Ready(val request: PreviewRequest, val titleKey: Int) {
        fun toLayer(withEnd: Boolean): PreviewLayer =
            if (titleKey != 0) {
                PreviewLayer(0, 0, request.transform.toPlacement(), titleKey = titleKey, fx = request.fx)
            } else {
                PreviewLayer(
                    request.assetKey,
                    request.sourceFrame,
                    request.transform.toPlacement(),
                    if (withEnd) request.endFrame else null,
                    reverse = request.reverse,
                    fx = request.fx,
                )
            }
    }

    /** Key of the uploaded raster of [title] on this canvas, drawing and uploading it the first time. */
    private fun titleKeyFor(engine: PreviewEngine, scene: PreviewScene, title: TitleContent): Int? {
        val (key, fresh) = titleKeys.keyFor(title, scene.canvasWidth, scene.canvasHeight)
        if (fresh) {
            try {
                val bitmap = rasterizer.rasterize(title, scene.canvasWidth, scene.canvasHeight)
                engine.uploadTitle(key, bitmap.width, bitmap.height, bitmap.pixels)
            } catch (e: TitleRasterException) {
                brokenTitles += key
                onError("A title could not be drawn: ${e.message}")
            } catch (e: PreviewException) {
                brokenTitles += key
                onError("A title could not be shown: ${e.message}")
            }
        }
        return key.takeIf { it !in brokenTitles }
    }

    /** Marks [key] as the most recently shown asset. */
    private fun touch(key: Int) {
        open -= key
        open += key
    }

    private fun reportSkipped(skipped: List<Int>) {
        val set = skipped.toSet()
        if (set == reportedSkipped) return
        reportedSkipped = set
        if (set.isNotEmpty()) {
            onError("This device can preview $maxDecoders video layers at once; the lower ${set.size} are hidden in the preview")
        }
    }

    private fun openThen(engine: PreviewEngine, request: PreviewRequest) {
        val key = request.assetKey
        opening += key
        scope.launch {
            val error = try {
                withContext(Dispatchers.IO) {
                    val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(request.uri), "r")
                        ?: throw FileNotFoundException(request.uri)
                    engine.openAsset(key, descriptor, request.fpsNum, request.fpsDen)
                }
                null
            } catch (e: FileNotFoundException) {
                "A media file is missing, so it cannot be previewed"
            } catch (e: SecurityException) {
                "No permission to read a media file for the preview"
            } catch (e: PreviewException) {
                "This clip cannot be previewed: ${e.message}"
            }
            opening -= key
            if (error != null) {
                failed += key
                onError(error)
                if (!following) latest?.let(::show)  // lower layers may now be shown without it
                return@launch
            }
            open += key
            // The playhead may have moved while the asset was opening. While following, the next
            // tick sees the changed set of ready layers and re-anchors by itself.
            if (!following) latest?.let(::show)
        }
    }

    override fun close() {
        engine?.close()
    }

    /** Running drift statistics (heard frame minus where the native clock is), logged every few seconds. */
    private class SyncStats {
        private var samples = 0L
        private var sum = 0L
        private var maxAbs = 0L
        private var reanchors = 0L
        private var windowStart = 0L

        fun record(heardFrame: Long, expected: Long?, reanchor: Boolean, nowNanos: Long) {
            if (windowStart == 0L) windowStart = nowNanos
            if (reanchor) reanchors++
            if (expected != null) {
                val drift = heardFrame - expected
                samples++
                sum += drift
                maxAbs = maxOf(maxAbs, kotlin.math.abs(drift))
            }
            if (nowNanos - windowStart >= LOG_INTERVAL_NANOS) {
                val mean = if (samples > 0) sum.toDouble() / samples else 0.0
                Log.d(SYNC_TAG, "t=${nowNanos / 1_000_000} samples=$samples meanDriftFrames=$mean maxAbsDriftFrames=$maxAbs reanchors=$reanchors")
                samples = 0
                sum = 0
                maxAbs = 0
                reanchors = 0
                windowStart = nowNanos
            }
        }
    }

    private companion object {
        /** The native and audio clocks agree to well under this, so a re-anchor means a real discontinuity. */
        const val DRIFT_THRESHOLD_FRAMES = 2L
        const val SYNC_TAG = "UVSync"
        const val LOG_INTERVAL_NANOS = 5_000_000_000L
    }
}

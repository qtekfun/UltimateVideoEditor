package com.ultimatevideo.uveditor.ui.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.engine.preview.DecoderLimits
import com.ultimatevideo.uveditor.engine.preview.DecoderPlanner
import com.ultimatevideo.uveditor.engine.preview.LayerPlacement
import com.ultimatevideo.uveditor.engine.preview.PreviewEngine
import com.ultimatevideo.uveditor.engine.preview.PreviewException
import com.ultimatevideo.uveditor.engine.preview.PreviewLayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/**
 * One layer of what the preview should show. Frames are in [fpsNum]/[fpsDen] units: the project
 * rate, since source ranges are kept in project frames.
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
    // True while [follow] drives the preview; the screen's next tick then picks up newly opened assets.
    private var following = false
    private var reportedSkipped: Set<Int> = emptySet()

    /** Shows [scene] as a still frame (paused, scrubbing, editing). Stops any native playback. */
    fun show(scene: PreviewScene) {
        val engine = engine ?: return
        following = false
        anchor.reset()
        val ready = prepare(engine, scene)
        engine.setScene(
            scene.canvasWidth,
            scene.canvasHeight,
            ready.map { PreviewLayer(it.assetKey, it.sourceFrame, it.transform.toPlacement()) },
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
        // The composition is the same while each clip's source range maps linearly to the timeline,
        // which is what the offset (source frame minus playhead) captures.
        val composition = ready.map { FollowedLayer(it.assetKey, it.sourceFrame - heardFrame, it.endFrame, it.transform) }
        if (!anchor.needsReanchor(composition, heardFrame, nowNanos, fps)) return
        engine.playScene(
            scene.canvasWidth,
            scene.canvasHeight,
            ready.map { PreviewLayer(it.assetKey, it.sourceFrame, it.transform.toPlacement(), it.endFrame) },
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

    private data class FollowedLayer(val assetKey: Int, val offset: Long, val endFrame: Long?, val transform: ClipTransform)

    /** Plans decoders for [scene], opens what is missing and returns the layers that can be drawn now. */
    private fun prepare(engine: PreviewEngine, scene: PreviewScene): List<PreviewRequest> {
        latest = scene
        val neededTopFirst = scene.layers.asReversed().map { it.assetKey }.filter { it !in failed }
        val plan = DecoderPlanner.plan(maxDecoders, neededTopFirst, open.toList())

        for (key in plan.toClose) {
            open -= key
            engine.closeAsset(key)
        }
        reportSkipped(plan.skipped)
        for (layer in scene.layers) {
            val key = layer.assetKey
            if (key in plan.render && key !in open && key !in opening) openThen(engine, layer)
        }
        // Layers whose asset is still opening are left out for now; the scene is resent when they are ready.
        val ready = scene.layers.filter { it.assetKey in open && it.assetKey in plan.render }
        for (layer in ready) touch(layer.assetKey)
        return ready
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

    private companion object {
        /** The native and audio clocks agree to well under this, so a re-anchor means a real discontinuity. */
        const val DRIFT_THRESHOLD_FRAMES = 2L
    }
}

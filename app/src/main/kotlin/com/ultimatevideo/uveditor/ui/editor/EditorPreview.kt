package com.ultimatevideo.uveditor.ui.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.ultimatevideo.uveditor.domain.ClipTransform
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
    private val onError: (String) -> Unit,
    private val maxDecoders: Int = DecoderLimits.maxPreviewDecoders(),
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
    private var reportedSkipped: Set<Int> = emptySet()

    fun show(scene: PreviewScene) {
        val engine = engine ?: return
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
        engine.setScene(
            scene.canvasWidth,
            scene.canvasHeight,
            ready.map { PreviewLayer(it.assetKey, it.sourceFrame, it.transform.toPlacement()) },
        )
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
                latest?.let(::show)  // lower layers may now be shown without it
                return@launch
            }
            open += key
            // The playhead may have moved while the asset was opening.
            latest?.let(::show)
        }
    }

    override fun close() {
        engine?.close()
    }
}

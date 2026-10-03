package com.ultimatevideo.uveditor.ui.editor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.ultimatevideo.uveditor.engine.preview.PreviewEngine
import com.ultimatevideo.uveditor.engine.preview.PreviewException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException

/** What the preview should show right now. Frames are in [fpsNum]/[fpsDen] units. */
data class PreviewRequest(
    val assetKey: Int,
    val uri: String,
    val sourceFrame: Long,
    val fpsNum: Int,
    val fpsDen: Int,
)

/**
 * Connects the editing session to the native preview: opens each asset lazily (off the main
 * thread, since opening does blocking I/O) and seeks it to the requested frame. All methods
 * except the internal open must be called on the main thread.
 *
 * Failures are reported through [onError] and never swallowed. The preview keeps its last frame
 * when there is nothing to show.
 */
class EditorPreview(
    private val context: Context,
    private val scope: CoroutineScope,
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

    private val open = HashSet<Int>()
    private val opening = HashSet<Int>()
    private val failed = HashSet<Int>()
    private var latest: PreviewRequest? = null

    fun show(request: PreviewRequest) {
        val engine = engine ?: return
        latest = request
        val key = request.assetKey
        when {
            key in open -> engine.seek(key, request.sourceFrame)
            key in failed || key in opening -> Unit
            else -> openThenShow(engine, request)
        }
    }

    private fun openThenShow(engine: PreviewEngine, request: PreviewRequest) {
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
                return@launch
            }
            open += key
            // The playhead may have moved while the asset was opening.
            latest?.takeIf { it.assetKey == key }?.let { engine.seek(key, it.sourceFrame) }
        }
    }

    override fun close() {
        engine?.close()
    }
}

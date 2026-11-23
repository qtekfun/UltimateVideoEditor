package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlinx.coroutines.CoroutineScope
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyUiState
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.engine.timeline.EngineStatus
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine
import com.qtekfun.ultimatevideoeditor.proxy.ProxyManager
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.ProxyViewModel
import com.qtekfun.ultimatevideoeditor.ui.export.ExportCenter
import com.qtekfun.ultimatevideoeditor.ui.export.ExportJobState
import com.qtekfun.ultimatevideoeditor.ui.text.UiText
import com.qtekfun.ultimatevideoeditor.ui.theme.LocalPalette
import kotlinx.coroutines.flow.map

/** The native engines of the editor and what feeds them: the timeline canvas, the preview, and the proxy queue. */
internal class EditorEngines(
    val engine: TimelineEngine,
    val thumbnailFailures: ThumbnailFailures,
    val preview: EditorPreview,
    val proxyManager: ProxyManager,
    val proxyVm: ProxyViewModel,
    val proxyHolder: State<ProxyUiState>,
    val exportRunning: Boolean,
)

@Composable
internal fun rememberEditorEngines(
    context: Context,
    projectId: String,
    density: Float,
    scope: CoroutineScope,
    viewModel: EditorViewModel,
    lut: LutSupport,
    titles: TitleSupport,
): EditorEngines {
    val thumbnailFailures = remember(projectId) {
        val main = Handler(Looper.getMainLooper())
        ThumbnailFailures(
            projectId,
            log = { android.util.Log.w("uv_thumb", it) },
            // Called on a native worker thread. The clip stays usable without its filmstrip.
            show = { message -> main.post { viewModel.onIntent(EditorIntent.ReportText(message)) } },
        )
    }
    val engine = remember {
        val main = Handler(Looper.getMainLooper())
        TimelineEngine(
            density,
            onThumbnailError = thumbnailFailures::onFailure,
        ) { _, status ->
            // Called on a native worker thread. A file without audio is not an error worth showing.
            if (status == EngineStatus.IO_ERROR || status == EngineStatus.CODEC_ERROR) {
                main.post { viewModel.onIntent(EditorIntent.ReportText(UiText.res(R.string.ed_2a_audio_unreadable, status))) }
            }
        }
    }
    DisposableEffect(engine) { onDispose { engine.close() } }
    // The canvas takes its colours from the same palette as the rest of the app, so it follows the pure-black option live.
    val palette = LocalPalette.current
    LaunchedEffect(engine, palette) { engine.setPalette(palette.nativeColours()) }

    // Proxy media: small copies the preview and the thumbnails use while editing. Export never asks for them.
    val proxyManager = remember(context) { ProxyManager.of(context.applicationContext) }
    val proxyVm: ProxyViewModel = viewModel(
        key = "proxy-$projectId",
        factory = viewModelFactory { initializer { ProxyViewModel(proxyManager, projectId) } },
    )
    val proxyHolder = proxyVm.state.collectAsStateWithLifecycle()

    val preview = remember {
        EditorPreview(
            context,
            scope,
            rasterizer = titles.rasterizer,
            lutLoader = lut.store::load,
            onProxyFailed = { proxyVm.onIntent(ProxyIntent.PreviewProxyFailed(it)) },
            // A decoder failing mid-way may mean the drive was pulled: the editor checks and marks the file missing.
            onAssetFailure = { key, message -> viewModel.onIntent(EditorIntent.MediaFailureReported(key, message)) },
            onSoftwareDecoding = { heavy ->
                viewModel.onIntent(
                    EditorIntent.ReportText(
                        UiText.res(
                            if (heavy) R.string.ed_2a_software_decoding_heavy else R.string.ed_2a_software_decoding,
                        ),
                    ),
                )
                if (heavy) proxyVm.onIntent(ProxyIntent.SoftwareDecodeHeavy)
            },
        ) {
            viewModel.onIntent(EditorIntent.ReportError(it))
            // A few stalls while playing are the cue to suggest proxies.
            if (it.contains("stall", ignoreCase = true)) proxyVm.onIntent(ProxyIntent.ReportStall)
        }
    }
    DisposableEffect(preview) { onDispose { preview.close() } }
    // A running export gets the hardware decoders: the preview lets go of its own until it ends (DECISIONS 2026-10-29).
    val movieExport = remember(context) { ExportCenter.executor(context) }
    val exportRunning by remember(movieExport) { movieExport.state.map { it is ExportJobState.Running } }.collectAsStateWithLifecycle(initialValue = false)
    LaunchedEffect(preview, exportRunning) { preview.setDecodersSuspended(exportRunning) }
    // A font that was imported or removed changes how titles that name it are drawn: tell the editor which
    // fonts exist (for the missing-font notice) and draw the titles again.
    LaunchedEffect(titles.state.fontIds) {
        viewModel.onIntent(EditorIntent.FontsChanged(titles.state.fontIds))
        titles.fontResolver.clear()
        preview.titlesChanged()
    }
    return EditorEngines(engine, thumbnailFailures, preview, proxyManager, proxyVm, proxyHolder, exportRunning)
}

package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatevideoeditor.engine.export.MediaCodecHdrExportSupport
import com.qtekfun.ultimatevideoeditor.engine.export.NativeExportRunner
import com.qtekfun.ultimatevideoeditor.engine.still.AndroidStillRasterizer
import com.qtekfun.ultimatevideoeditor.ui.export.ContentResolverExportIO
import com.qtekfun.ultimatevideoeditor.ui.export.ExportCenter
import com.qtekfun.ultimatevideoeditor.ui.export.ExportHost
import com.qtekfun.ultimatevideoeditor.ui.export.ExportInput
import com.qtekfun.ultimatevideoeditor.ui.export.ExportIntent
import com.qtekfun.ultimatevideoeditor.ui.export.ExportViewModel
import com.qtekfun.ultimatevideoeditor.ui.frame.BitmapFrameEncoder
import com.qtekfun.ultimatevideoeditor.ui.frame.MediaStoreFrameSink
import com.qtekfun.ultimatevideoeditor.ui.frame.NativeFrameRenderer
import com.qtekfun.ultimatevideoeditor.ui.frame.StillFrameEffects
import com.qtekfun.ultimatevideoeditor.ui.frame.StillFrameInput
import com.qtekfun.ultimatevideoeditor.ui.frame.StillFrameIntent
import com.qtekfun.ultimatevideoeditor.ui.frame.StillFrameViewModel
import kotlinx.coroutines.launch

/** What the top bar and the transport need from the export dialog and from "save frame as image". */
internal class ExportSupport(
    val blockedBy: String?,
    val onOpen: () -> Unit,
    val onSaveFrame: () -> Unit,
    val onShareFrame: () -> Unit,
    val onOpenFrame: () -> Unit,
)

@Composable
internal fun rememberExportSupport(
    projectId: String,
    context: Context,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    lut: LutSupport,
    titles: TitleSupport,
    holder: State<EditorState>,
    live: StateFlow<EditorState>,
    onIntent: (EditorIntent) -> Unit,
    showExport: Boolean,
    onShowExportHandled: () -> Unit,
): ExportSupport {
    val exportViewModel: ExportViewModel = viewModel(
        key = "export-$projectId",
        factory = viewModelFactory {
            initializer {
                ExportViewModel(
                    ContentResolverExportIO(context.applicationContext),
                    NativeExportRunner(), // only the default executor of the tests uses this one; the app's runs in ExportCenter
                    titleRasterizer = titles.rasterizer,
                    stillRasterizer = AndroidStillRasterizer(context.applicationContext),
                    hdrSupport = MediaCodecHdrExportSupport(),
                    lutLoader = lut.store::load,
                    executor = ExportCenter.executor(context),
                    projectId = projectId,
                )
            }
        },
    )
    ExportHost(exportViewModel) { text ->
        snackbar.currentSnackbarData?.dismiss()
        scope.launch { snackbar.showSnackbar(text) }
    }
    val stillFrameViewModel: StillFrameViewModel = viewModel(
        key = "frame-$projectId",
        factory = viewModelFactory {
            initializer {
                val app = context.applicationContext
                StillFrameViewModel(
                    renderer = NativeFrameRenderer(
                        ContentResolverExportIO(app), NativeExportRunner(), titles.rasterizer, AndroidStillRasterizer(app), lut.store::load,
                    ),
                    encoder = BitmapFrameEncoder(),
                    sink = MediaStoreFrameSink(app),
                    exports = ExportCenter.executor(context),
                    projectId = projectId,
                )
            }
        },
    )
    StillFrameEffects(stillFrameViewModel, snackbar)
    val exportHolder = exportViewModel.state.collectAsStateWithLifecycle()
    // Only the name is read, so the editor does not recompose on every progress report of an export.
    val exportBlockedBy by remember(exportHolder) { derivedStateOf { exportHolder.value.blockedBy } }
    LaunchedEffect(showExport) {
        if (showExport) {
            exportViewModel.onIntent(ExportIntent.ShowProgress)
            onShowExportHandled()
        }
    }

    val openExport = {
        // The dialog works from what the editor holds right now; the autosave is not involved.
        val live = holder.value
        exportViewModel.onIntent(
            ExportIntent.Open(
                ExportInput(
                    projectId, live.projectName, live.canvasWidth, live.canvasHeight, live.fps, live.timeline, live.assets, live.colorSpace,
                    missingAssetIds = live.missingMedia.keys,
                    hdr = live.hdr,
                ),
            ),
        )
    }

    val openStillFrame = {
        // Playback stops first, so the frame is the one the playhead rests on, whatever the player was doing.
        if (live.value.isPlaying) onIntent(EditorIntent.TogglePlay)
        val live = live.value
        stillFrameViewModel.onIntent(
            StillFrameIntent.Save(
                StillFrameInput(
                    projectId = projectId,
                    projectName = live.projectName,
                    projectWidth = live.canvasWidth,
                    projectHeight = live.canvasHeight,
                    fps = live.fps,
                    timeline = live.timeline,
                    assets = live.assets,
                    frame = live.playhead.value,
                    missingAssetIds = live.missingMedia.keys,
                    hdr = live.hdr,
                ),
            ),
        )
    }
    return ExportSupport(
        exportBlockedBy,
        openExport,
        openStillFrame,
        { stillFrameViewModel.onIntent(StillFrameIntent.Share) },
        { stillFrameViewModel.onIntent(StillFrameIntent.Open) },
    )
}

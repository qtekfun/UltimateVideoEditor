package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import android.net.Uri
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.LocalPreviewProxyNote
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.LocalProxyIntent
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.LocalProxyUi
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayDragRoot
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatevideoeditor.ui.frame.FrameSnackbarHost
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
fun EditorScreen(
    viewModel: EditorViewModel,
    projectId: String,
    onClose: () -> Unit,
    /** Set when a notification or the project list asked for this project's export dialog; [onShowExportHandled] clears it. */
    showExport: Boolean = false,
    onShowExportHandled: () -> Unit = {},
) {
    // The playhead changes every 16 ms while playing. It is kept out of what the chrome (toolbar,
    // banners, dialogs, inspector shell) reads, so those recompose only when something they show changes;
    // the effects below and the timecode read the live state through [holder] instead.
    val holder = viewModel.state.collectAsStateWithLifecycle()
    val chrome by remember(holder) { derivedStateOf { chromeOf(holder.value) } }
    val state = chrome.state
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val lut = rememberLutSupport(context)

    val lookActions = rememberLookActions(context)

    val titles = rememberTitleSupport(context)
    val fontResolver = titles.fontResolver
    val titleRasterizer = titles.rasterizer
    val titleLibraryState = titles.state
    val titleTools = titles.tools

    val exportSupport = rememberExportSupport(
        projectId, context, snackbar, scope, lut, titles, holder, viewModel.state, viewModel::onIntent, showExport, onShowExportHandled,
    )
    LutPickerHost(state.lutPickerOpen, lut, viewModel::onIntent)

    val openCaptions = rememberOpenCaptions(projectId, context, snackbar, scope, holder, viewModel::onIntent)


    val engines = rememberEditorEngines(context, projectId, density, scope, viewModel, lut, titles)
    val engine = engines.engine
    val preview = engines.preview
    val proxyManager = engines.proxyManager
    val proxyVm = engines.proxyVm
    val proxyHolder = engines.proxyHolder
    val exportRunning = engines.exportRunning

    val audio = rememberEditorAudio(context, scope, viewModel, holder)

    // Whether the preview shows a proxy and whether its colours differ from the original: the label over the preview says so.
    val previewProxyNote = remember { mutableStateOf(PreviewProxyNote.NONE) }

    PreviewSyncEffect(holder, preview, proxyVm, proxyHolder, previewProxyNote, viewModel)

    val editing = rememberTimelineEditing(viewModel)

    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onIntent(EditorIntent.ImportMedia(uris.map(Uri::toString)))
    }
    val launchImport = { importPicker.launch(arrayOf("video/*", "audio/*", "image/*")) }


    val folderScope = rememberCoroutineScope()
    val voiceSupport = rememberVoiceSupport(context, projectId, folderScope, holder, viewModel::onIntent)
    val voice = voiceSupport.voice
    LaunchedEffect(voice, audio) { snapshotFlow { voice.timelineSilenced }.collect { audio.setSilenced(it) } }
    val useSystemRecorder = voiceSupport.useSystemRecorder
    val tray = rememberEditorTray(context, state, holder, viewModel, engine, density, folderScope, exportRunning, titleLibraryState.presets)
    val dropTarget = rememberTimelineDropTarget(viewModel)


    EditorEffectHandler(viewModel.effects, viewModel::onIntent, snackbar, scope, voice, preview, onClose)

    TimelineSyncEffects(holder, engine, viewModel, projectId, proxyVm, engines.thumbnailFailures)

    BackHandler { viewModel.onIntent(EditorIntent.Back) }
    // A double tap on the preview makes it fill the window. Registered after the editor's own Back, so it wins.
    var fullscreen by rememberSaveable(stateSaver = FullscreenSaver) { mutableStateOf(FullscreenState()) }
    val onFullscreen: (FullscreenAction) -> Unit = { fullscreen = fullscreen.reduce(it) }
    EditorSystemEffects(holder, viewModel::onIntent, fullscreen, onFullscreen, tray.trayDrag, proxyManager, audio)

    CompositionLocalProvider(
        LocalLutNames provides lut.state.names,
        LocalQualifierPick provides state.qualifierPick,
        LocalLookActions provides lookActions,
        LocalProxyUi provides proxyHolder,
        LocalPreviewProxyNote provides previewProxyNote,
        LocalProxyIntent provides proxyVm::onIntent,
    ) {
    TrayDragRoot(tray.trayDrag, Modifier.fillMaxSize()) {
    Scaffold(
        snackbarHost = {
            FrameSnackbarHost(
                snackbar,
                onShare = exportSupport.onShareFrame,
                onOpen = exportSupport.onOpenFrame,
            )
        },
    ) { padding ->
        when {
            state.isLoading -> EditorLoading(padding)

            state.loadError != null -> EditorLoadError(state.loadError, padding, onClose)

            else -> EditorWorkspace(
                padding, state, chrome.selectedClipVisible, holder, viewModel, engine, preview, editing, dropTarget, tray,
                fullscreen, onFullscreen, titleTools, voice, useSystemRecorder, launchImport, exportSupport.onOpen, openCaptions,
                exportSupport.onSaveFrame, exportSupport.blockedBy, audio::takePeaks,
            )
        }
    }
    }
    }
}

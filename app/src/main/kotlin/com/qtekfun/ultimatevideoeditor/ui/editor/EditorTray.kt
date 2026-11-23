package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlinx.coroutines.CoroutineScope
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtekfun.ultimatevideoeditor.data.folders.DocumentKey
import com.qtekfun.ultimatevideoeditor.data.folders.PrefsBrowsePositionStore
import com.qtekfun.ultimatevideoeditor.data.folders.PrefsLinkedFoldersStore
import com.qtekfun.ultimatevideoeditor.data.folders.TreeFolderBrowser
import com.qtekfun.ultimatevideoeditor.data.relink.TreeFolderScanner
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AndroidAuditionPlayer
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AndroidSourceInfoReader
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.LinkedFoldersController
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.MediaTray
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.SourcePreviewController
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.SourcePreviewSheet
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayDragController
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayState
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayTab
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.trayItems
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.usageCounts
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** The media tray, the folders it links, the clip preview, and the drag of a tile onto the timeline. */
internal class EditorTray(
    val linkedTrees: List<String>,
    val trayDrag: TrayDragController,
    val trayDropBounds: TimelineTrayDrop,
    val openTab: (TrayTab) -> Unit,
    /** The tray itself: (bottom panel, modifier, tap previews). Composed in a side column or under the timeline. */
    val panel: @Composable (Boolean, Modifier, Boolean) -> Unit,
)

@Composable
internal fun rememberEditorTray(
    context: Context,
    state: EditorState,
    holder: State<EditorState>,
    viewModel: EditorViewModel,
    engine: TimelineEngine,
    density: Float,
    folderScope: CoroutineScope,
    exportRunning: Boolean,
    userPresets: List<TextTemplate>,
): EditorTray {
    // The media tray: how it is shown is local UI state; what it lists comes from the editor state.
    var tray by remember { mutableStateOf(TrayState()) }
    val trayUsage = remember(state.timeline) { usageCounts(state.timeline) }
    val trayItems = remember(state.assets, trayUsage, state.missingMedia, tray.tab, tray.filter, tray.query) {
        trayItems(state.assets, trayUsage, state.missingMedia.keys, tray.tab, tray.filter, tray.query)
    }
    val trayImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.onIntent(EditorIntent.ImportToTray(uris.map(Uri::toString)))
    }
    val launchTrayImport = { trayImportPicker.launch(arrayOf("video/*", "audio/*", "image/*")) }
    // The folders the user linked: one list for the whole app, read lazily from the document tree.
    val linkedFolders = remember {
        LinkedFoldersController(
            store = PrefsLinkedFoldersStore(context.getSharedPreferences(PrefsLinkedFoldersStore.FILE, Context.MODE_PRIVATE)),
            browser = TreeFolderBrowser(context.applicationContext),
            scope = folderScope,
            newId = { "lf-" + java.util.UUID.randomUUID().toString().take(8) },
            retainAccess = TreeFolderScanner(context.applicationContext)::retainAccess,
            positions = PrefsBrowsePositionStore(context.getSharedPreferences(PrefsBrowsePositionStore.FILE, Context.MODE_PRIVATE)),
        )
    }
    val linkedTrees = linkedFolders.folders.map { it.treeUri }
    val linkFolderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) linkedFolders.link(uri.toString())
    }
    // Looking at a file before using it: its own player, never the timeline's. It lets go of its decoder when closed, and does not start while a movie is exported.
    val sourcePreview = remember {
        SourcePreviewController(
            scope = folderScope,
            newPlayer = { listener -> AndroidAuditionPlayer(context.applicationContext, listener) },
            infoReader = AndroidSourceInfoReader(context.applicationContext),
        )
    }
    sourcePreview.busy = exportRunning
    LaunchedEffect(exportRunning) { if (exportRunning) sourcePreview.close() }
    DisposableEffect(sourcePreview) { onDispose { sourcePreview.close() } }
    SourcePreviewSheet(
        controller = sourcePreview,
        onAddAtPlayhead = { viewModel.onIntent(EditorIntent.ImportMedia(listOf(it.uri))) },
        onAddAtEnd = { viewModel.onIntent(EditorIntent.ImportMedia(listOf(it.uri), atEnd = true)) },
    )
    val inProject = remember(state.assets) { state.assets.mapNotNullTo(HashSet()) { DocumentKey.of(it.uri) } }
    // Dragging a tile onto the timeline: the tray tracks the finger, the sink turns it into timeline drops (see DECISIONS.md "Tray drag").
    val trayDropBounds = remember(viewModel, engine) { TimelineTrayDrop(engine::hitTest, engine::scrollBy, viewModel::onIntent, density) }
    val trayDrag = remember(trayDropBounds) {
        TrayDragController().also { controller ->
            controller.sink = trayDropBounds
            controller.onReorder = { id, index -> viewModel.onIntent(EditorIntent.ReorderAsset(id, index)) }
            controller.indexOf = { id -> holder.value.assets.indexOfFirst { it.id == id } }
        }
    }
    val trayPanel: @Composable (Boolean, Modifier, Boolean) -> Unit = { bottom, panelModifier, tapPreviews ->
        MediaTray(
            state = tray,
            onState = { tray = it },
            assets = state.assets,
            items = trayItems,
            isImporting = state.isImporting,
            bottomPanel = bottom,
            onImport = launchTrayImport,
            onAdd = { viewModel.onIntent(EditorIntent.AddAsset(it)) },
            onAddMany = { viewModel.onIntent(EditorIntent.AddAssets(it)) },
            drag = trayDrag,
            onExternalFiles = { viewModel.onIntent(EditorIntent.ImportToTray(it)) },
            onPickSticker = { viewModel.onIntent(EditorIntent.AddSticker(it)) },
            onApplyTemplate = { id, text -> viewModel.onIntent(EditorIntent.ApplyTextTemplate(id, text)) },
            folders = linkedFolders,
            inProject = inProject,
            onLinkFolder = { linkFolderPicker.launch(null) },
            onAddFiles = { viewModel.onIntent(EditorIntent.ImportMedia(it)) },
            onPreview = { item ->
                // The timeline stops while a file is looked at, so two sounds never play at once.
                if (holder.value.isPlaying) viewModel.onIntent(EditorIntent.TogglePlay)
                sourcePreview.open(item)
            },
            tapPreviews = tapPreviews,
            modifier = panelModifier,
            userPresets = userPresets,
            onApplyPreset = { template, text -> viewModel.onIntent(EditorIntent.ApplyPreset(template, text)) },
        )
    }
    return EditorTray(linkedTrees, trayDrag, trayDropBounds, { tray = tray.open(it) }, trayPanel)
}

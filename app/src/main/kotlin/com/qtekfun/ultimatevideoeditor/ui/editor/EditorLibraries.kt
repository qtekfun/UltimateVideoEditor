package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleLibraryState
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.qtekfun.ultimatevideoeditor.data.FontRegistry
import com.qtekfun.ultimatevideoeditor.data.LookStore
import com.qtekfun.ultimatevideoeditor.data.LutStore
import com.qtekfun.ultimatevideoeditor.data.TitlePresetStore
import com.qtekfun.ultimatevideoeditor.domain.TextTemplate
import com.qtekfun.ultimatevideoeditor.engine.still.AndroidLayerImages
import com.qtekfun.ultimatevideoeditor.engine.title.AndroidTitleRasterizer
import com.qtekfun.ultimatevideoeditor.engine.title.RegistryFontResolver
import com.qtekfun.ultimatevideoeditor.ui.editor.title.ContentResolverBytesReader
import com.qtekfun.ultimatevideoeditor.ui.editor.title.ContentResolverTextWriter
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleLibraryViewModel
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleTools
import java.io.File
import kotlinx.coroutines.launch

/** The app-wide LUT library, shared by the effects section, the preview and the export. */
internal class LutSupport(val store: LutStore, val library: LutLibraryViewModel, val state: LutLibraryState)

@Composable
internal fun rememberLutSupport(context: Context): LutSupport {
    val lutStore = remember(context) { LutStore(File(context.applicationContext.filesDir, "luts")) }
    val lutLibrary: LutLibraryViewModel = viewModel(
        key = "luts",
        factory = viewModelFactory {
            initializer { LutLibraryViewModel(lutStore, ContentResolverLutReader(context.applicationContext)) }
        },
    )
    val lutState by lutLibrary.state.collectAsStateWithLifecycle()
    return LutSupport(lutStore, lutLibrary, lutState)
}

/** Picking a LUT for the selected clip: a file, or one of the built-in filters. */
@Composable
internal fun LutPickerHost(open: Boolean, lut: LutSupport, onIntent: (EditorIntent) -> Unit) {
    if (open) {
        LutPickerDialog(
            state = lut.state,
            onPick = { onIntent(EditorIntent.AddLut(it)) },
            onImport = { uri -> lut.library.import(uri) { onIntent(EditorIntent.AddLut(it.key)) } },
            onPickFilter = { id, name -> lut.library.installFilter(id, name) { onIntent(EditorIntent.AddLut(it.key)) } },
            onDismiss = {
                lut.library.clearError()
                onIntent(EditorIntent.CloseLutPicker)
            },
        )
    }
}

/** Saved colour looks and the copy/paste clipboard of the colour section; everything stays on the device. */
@Composable
internal fun rememberLookActions(context: Context): LookActions {
    val lookStore = remember(context) { LookStore(File(context.applicationContext.filesDir, "looks")) }
    val lookLibrary: LookLibraryViewModel = viewModel(
        key = "looks",
        factory = viewModelFactory { initializer { LookLibraryViewModel(lookStore) } },
    )
    val lookState by lookLibrary.state.collectAsStateWithLifecycle()
    val lookActions = remember(lookState, lookLibrary) {
        LookActions(lookState, lookLibrary::save, lookLibrary::delete, lookLibrary::copy, lookLibrary::clearError)
    }
    return lookActions
}

/** What the title editor and the preview need from the app-wide font and title-preset libraries. */
internal class TitleSupport(
    val fontResolver: RegistryFontResolver,
    val rasterizer: AndroidTitleRasterizer,
    val state: TitleLibraryState,
    val tools: TitleTools,
)

@Composable
internal fun rememberTitleSupport(context: Context): TitleSupport {
    // Imported fonts, saved title presets and the title drawing that uses them (preview and export share it).
    // Everything stays on the device; files come only from what the user picks.
    val fontRegistry = remember(context) { FontRegistry(File(context.applicationContext.filesDir, "fonts")) }
    val fontResolver = remember(fontRegistry) { RegistryFontResolver(fontRegistry) }
    val titleRasterizer = remember(context, fontResolver) { AndroidTitleRasterizer(AndroidLayerImages(context.applicationContext), fontResolver) }
    val titleLibrary: TitleLibraryViewModel = viewModel(
        key = "title-library",
        factory = viewModelFactory {
            initializer {
                TitleLibraryViewModel(
                    fontRegistry,
                    TitlePresetStore(File(context.applicationContext.filesDir, "title-presets")),
                    ContentResolverBytesReader(context.applicationContext),
                    ContentResolverTextWriter(context.applicationContext),
                )
            }
        },
    )
    val titleLibraryState by titleLibrary.state.collectAsStateWithLifecycle()
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) titleLibrary.importFont(uri.toString())
    }
    val presetPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) titleLibrary.importPreset(uri.toString())
    }
    var presetToExport by remember { mutableStateOf<TextTemplate?>(null) }
    val presetSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val preset = presetToExport
        presetToExport = null
        if (uri != null && preset != null) titleLibrary.exportPreset(preset.id, uri.toString())
    }
    val titleTools = remember(titleLibraryState, titleLibrary) {
        TitleTools(
            fonts = titleLibraryState.fonts,
            presets = titleLibraryState.presets,
            message = titleLibraryState.message,
            onImportFont = { fontPicker.launch(arrayOf("*/*")) },
            onSavePreset = { name, content, intro, outro, seconds -> titleLibrary.savePreset(name, content, seconds, intro, outro) },
            onImportPreset = { presetPicker.launch(arrayOf("*/*")) },
            onExportPreset = {
                presetToExport = it
                presetSaver.launch("${it.name}.uvtitle")
            },
            onDeletePreset = { titleLibrary.deletePreset(it.id) },
            onClearMessage = titleLibrary::clearMessage,
        )
    }
    return TitleSupport(fontResolver, titleRasterizer, titleLibraryState, titleTools)
}

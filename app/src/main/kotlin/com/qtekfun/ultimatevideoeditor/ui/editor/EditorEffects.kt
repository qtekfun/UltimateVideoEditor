package com.qtekfun.ultimatevideoeditor.ui.editor


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.qtekfun.ultimatevideoeditor.proxy.ProxyManager
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.TrayDragController
import com.qtekfun.ultimatevideoeditor.ui.editor.voice.VoiceOverController
import com.qtekfun.ultimatevideoeditor.ui.text.resolve
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The platform side of the screen: Back for fullscreen and for a tile in hand (registered after the editor's own Back, so
 * they win), the immersive window, the screen that stays on while playing, proxies that yield to playback, and the flush when
 * the app stops.
 */
@Composable
internal fun EditorSystemEffects(
    holder: State<EditorState>,
    onIntent: (EditorIntent) -> Unit,
    fullscreen: FullscreenState,
    onFullscreen: (FullscreenAction) -> Unit,
    trayDrag: TrayDragController,
    proxyManager: ProxyManager,
    audio: EditorAudio,
) {
    BackHandler(enabled = fullscreen.consumesBack) { onFullscreen(FullscreenAction.Exit) }
    // Registered last, so it wins: Back puts a tile in hand down again.
    BackHandler(enabled = trayDrag.carry?.returning == false) { trayDrag.cancel() }
    ImmersiveWhile(fullscreen.active)
    LaunchedEffect(fullscreen.overlayEpoch, fullscreen.overlayVisible) {
        if (fullscreen.overlayVisible) {
            val epoch = fullscreen.overlayEpoch
            delay(FULLSCREEN_OVERLAY_MS)
            onFullscreen(FullscreenAction.Timeout(epoch))
        }
    }
    // The screen stays on while the preview plays (paused or idle times out as usual). Cleared when playback stops, when the
    // editor leaves the composition and, through ON_STOP below pausing playback, when the app goes to the background.
    val view = LocalView.current
    val keepAwake = holder.value.isPlaying
    DisposableEffect(view, keepAwake) {
        view.keepScreenOn = keepAwake
        onDispose { view.keepScreenOn = false }
    }
    // No new proxy starts while the preview plays (DECISIONS "Proxies yield to playback and to low memory").
    DisposableEffect(proxyManager, keepAwake) {
        proxyManager.setPlaying(keepAwake)
        onDispose { proxyManager.setPlaying(false) }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Flush pauses playback; the audio device is then freed until the next play.
        onIntent(EditorIntent.Flush)
        audio.releaseDevice()
    }
}

/**
 * Handles the one-shot effects of the editor (messages, closing, the pickers the view model asks for). The pickers are launched
 * from here because only a composable can register them; the view model never sees an activity.
 */
@Composable
internal fun EditorEffectHandler(
    effects: Flow<EditorEffect>,
    onIntent: (EditorIntent) -> Unit,
    snackbar: SnackbarHostState,
    scope: CoroutineScope,
    voice: VoiceOverController,
    preview: EditorPreview,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    // The replacement for a missing file: which asset it is for is remembered while the picker is open.
    var relinkTarget by remember { mutableStateOf<String?>(null) }
    val relinkPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val assetId = relinkTarget
        relinkTarget = null
        if (uri != null && assetId != null) onIntent(EditorIntent.RelinkAsset(assetId, uri.toString()))
    }

    // The folder to look in for every missing file; the app keeps read access to it (taken by the scanner).
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) onIntent(EditorIntent.RelinkFromFolder(uri.toString()))
    }

    // Where an export to another tool is written: the kind is remembered while the picker is open.
    var interchangeKind by remember { mutableStateOf<InterchangeKind?>(null) }
    val onInterchangeUri = { uri: Uri? ->
        val kind = interchangeKind
        interchangeKind = null
        if (uri != null && kind != null) onIntent(LibraryIntent.ExportTo(kind, uri.toString()))
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ZIP_MIME), onInterchangeUri)
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(INTERCHANGE_MIME), onInterchangeUri)

    LaunchedEffect(effects) {
        effects.collect { effect ->
            when (effect) {
                is EditorEffect.ShowMessage -> {
                    snackbar.currentSnackbarData?.dismiss()
                    val text = effect.text.resolve(context)
                    scope.launch { snackbar.showSnackbar(text) }
                }
                EditorEffect.Close -> onClose()
                EditorEffect.VoiceOverPlaced -> voice.onPlaced()
                is EditorEffect.AssetUnavailable -> preview.release(effect.assetKey)
                is EditorEffect.LaunchRelinkPicker -> {
                    relinkTarget = effect.assetId
                    relinkPicker.launch(arrayOf("video/*", "audio/*", "image/*"))
                }
                EditorEffect.LaunchFolderPicker -> folderPicker.launch(null)
                is EditorEffect.LaunchInterchangePicker -> {
                    interchangeKind = effect.kind
                    if (effect.mime == ZIP_MIME) zipPicker.launch(effect.suggestedFileName) else filePicker.launch(effect.suggestedFileName)
                }
            }
        }
    }
}

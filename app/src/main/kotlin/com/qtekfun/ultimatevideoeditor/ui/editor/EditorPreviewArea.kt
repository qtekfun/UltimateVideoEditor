package com.qtekfun.ultimatevideoeditor.ui.editor

import kotlinx.coroutines.flow.StateFlow
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.engine.preview.OutputSpace
import com.qtekfun.ultimatevideoeditor.ui.editor.proxy.LocalPreviewProxyNote
import com.qtekfun.ultimatevideoeditor.ui.editor.title.LayerHandleOverlay
import com.qtekfun.ultimatevideoeditor.ui.preview.DisplayHdr
import com.qtekfun.ultimatevideoeditor.ui.preview.PreviewSurface
import com.qtekfun.ultimatevideoeditor.ui.preview.wantedOutputSpace
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The picture: the native preview surface and everything drawn or gestured over it (HDR/proxy label, safe zones, track path,
 * transform gestures, pickers, fullscreen controls, scopes). It sits in the same composition place as before, so the surface is
 * never recreated by the split.
 */
@Composable
internal fun EditorPreviewArea(
    state: EditorState,
    selectedClipVisible: Boolean,
    preview: EditorPreview,
    liveState: StateFlow<EditorState>,
    fullscreen: FullscreenState,
    onFullscreen: (FullscreenAction) -> Unit,
    scopesOpen: Boolean,
    onIntent: (EditorIntent) -> Unit,
) {
    // Where the fullscreen controls sit, so a tap on them is not taken for a tap on the picture.
    var controlsBounds by remember { mutableStateOf<Rect?>(null) }
    // No background here: the preview is a SurfaceView, and an opaque parent would hide it.
    Box(
        modifier = Modifier.fillMaxSize().previewTapGestures(
            ignoreIn = { controlsBounds },
            onTap = { onFullscreen(FullscreenAction.Tap) },
            onDoubleTap = { onFullscreen(FullscreenAction.DoubleTap) },
        ),
        contentAlignment = Alignment.Center,
    ) {
        val previewEngine = preview.engine
        if (previewEngine != null) {
            // HLG project: render the preview as HDR when the screen shows it, else tone-mapped SDR.
            val hdrContext = LocalContext.current
            val displayHlg = remember { DisplayHdr.supportsHlg(hdrContext) }
            val wanted = wantedOutputSpace(state.colorSpace.isHdr, displayHlg)
            var granted by remember { mutableStateOf(OutputSpace.SDR_709) }
            DisposableEffect(granted) {
                DisplayHdr.setWindowHdr(hdrContext, granted == OutputSpace.HLG_2020)
                onDispose { DisplayHdr.setWindowHdr(hdrContext, false) }
            }
            PreviewSurface(previewEngine, Modifier.fillMaxSize(), wanted = wanted, onOutputSpace = { granted = it })
            val proxyNote = LocalPreviewProxyNote.current?.value ?: PreviewProxyNote.NONE
            if (state.colorSpace.isHdr || proxyNote != PreviewProxyNote.NONE) {
                val modeText = when {
                    !state.colorSpace.isHdr -> null
                    granted == OutputSpace.HLG_2020 -> stringResource(R.string.ed_2a_hdr_hlg)
                    else -> stringResource(R.string.ed_2a_hdr_project_sdr_preview)
                }
                val proxyText = when (proxyNote) {
                    PreviewProxyNote.NONE -> null
                    PreviewProxyNote.PROXY -> stringResource(R.string.ed_proxy_label)
                    PreviewProxyNote.PROXY_SDR_OF_HDR -> stringResource(R.string.ed_proxy_sdr_of_hdr_label)
                }
                Text(
                    listOfNotNull(modeText, proxyText).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (proxyNote == PreviewProxyNote.PROXY_SDR_OF_HDR) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                )
            }
            state.safeZone?.let { SafeZoneOverlay(it, state.canvasWidth, state.canvasHeight) }
            if (state.track.overlay.size >= 2) {
                // The playhead is read here, in the overlay's own scope, so a tick redraws only the dot.
                val trackPlayhead by remember(liveState) { liveState.map { it.playhead.value }.distinctUntilChanged() }
                    .collectAsStateWithLifecycle(initialValue = 0L)
                TrackPathOverlay(state.track.overlay, trackPlayhead, state.canvasWidth, state.canvasHeight, Modifier.fillMaxSize())
            }
            // Drag, pinch and twist edit the selected clip while it is under the playhead; with a layer of a
            // multilayer title selected they move that layer instead.
            val layerTarget = state.selectedTitleLayer != null
            PreviewGestureLayer(
                enabled = selectedClipVisible && !state.track.picking,
                canvasWidth = state.canvasWidth,
                canvasHeight = state.canvasHeight,
                onStep = { panX, panY, zoom, rotation ->
                    onIntent(
                        if (layerTarget) EditorIntent.LayerGesture(panX, panY, zoom, rotation)
                        else EditorIntent.TransformGesture(panX, panY, zoom, rotation),
                    )
                },
                onEnd = { onIntent(EditorIntent.EndAppearanceEdit(commit = true)) },
                modifier = Modifier.fillMaxSize(),
            )
            // A ring and cross on the layer the gestures are moving (nothing unless a layer is selected).
            LayerHandleOverlay(state, Modifier.fillMaxSize())
            if (state.track.picking) {
                TrackTargetLayer(
                    canvasWidth = state.canvasWidth,
                    canvasHeight = state.canvasHeight,
                    onPick = { x, y, w, h -> onIntent(EditorIntent.PickTrackTarget(x, y, w, h)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (state.qualifierPick.armed) {
                // The eyedropper: a tap on the picture keys the HSL qualifier on the colour under it.
                TrackTargetLayer(
                    canvasWidth = state.canvasWidth,
                    canvasHeight = state.canvasHeight,
                    onPick = { x, y, _, _ -> onIntent(QualifierIntent.Pick(x, y)) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            FullscreenControls(
                visible = fullscreen.overlayVisible,
                playing = state.isPlaying,
                onPlayPause = {
                    onFullscreen(FullscreenAction.Interact)
                    onIntent(EditorIntent.TogglePlay)
                },
                onExit = { onFullscreen(FullscreenAction.Exit) },
                onBounds = { controlsBounds = it },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
            if (scopesOpen) {
                ScopesPanel(
                    engine = previewEngine,
                    colorSpace = state.colorSpace,
                    onError = { onIntent(EditorIntent.ReportText(it)) },
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(SCOPES_WIDTH).fillMaxHeight(SCOPES_HEIGHT).padding(6.dp),
                )
            }
        } else {
            Text(text = stringResource(R.string.ed_2a_preview_unavailable), style = MaterialTheme.typography.labelLarge)
        }
    }
}

// The scopes overlay covers this share of the preview box, bottom left.
private const val SCOPES_WIDTH = 0.6f
private const val SCOPES_HEIGHT = 0.6f

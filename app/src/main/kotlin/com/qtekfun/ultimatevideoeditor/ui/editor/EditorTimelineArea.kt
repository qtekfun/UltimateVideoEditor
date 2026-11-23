package com.qtekfun.ultimatevideoeditor.ui.editor

import com.qtekfun.ultimatevideoeditor.domain.Transition
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineEngine
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.overlayChipLayout
import com.qtekfun.ultimatevideoeditor.ui.editor.title.TitleTools
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.RootBounds

/**
 * The timeline: the native timeline view with the chips, flag list, marker card and the inspector overlay drawn over it.
 * The inspector covers the timeline instead of replacing it, so the native view is never recreated.
 */
@Composable
internal fun EditorTimelineArea(
    state: EditorState,
    engine: TimelineEngine,
    editing: TimelineEditing,
    dropTarget: TimelineDropTarget,
    selecting: TimelineSelecting,
    shaping: TimelineShaping,
    detailZoom: Boolean,
    inspectorOverlay: Boolean,
    titleTools: TitleTools,
    trayDropBounds: TimelineTrayDrop?,
    transitionLimit: (Transition) -> Long,
    onIntent: (EditorIntent) -> Unit,
) {
    // The inspector is drawn over the timeline instead of replacing it, so the native timeline view
    // is never recreated (a late surfaceDestroyed of an old view would tear down the new surface).
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        TimelineHost(
            engine = engine,
            onTap = { onIntent(EditorIntent.TapTimeline(it)) },
            editing = editing,
            dropTarget = dropTarget,
            selecting = selecting,
            shaping = shaping,
            detailZoom = detailZoom,
            modifier = Modifier.fillMaxSize().onGloballyPositioned { coordinates ->
                trayDropBounds?.bounds = coordinates.boundsInRoot().let { RootBounds(it.left, it.top, it.right, it.bottom) }
            },
        )
        // The two chips sit under the ruler: side by side when the timeline is wide, stacked when it is not,
        // and over the ruler when the timeline is too short for them to sit under it.
        val chips = overlayChipLayout(maxWidth.value, maxHeight.value, LocalDensity.current.fontScale)
        @Composable fun dropChip() {
            if (state.dropChoiceOffered) DropModeChip(
                effective = state.dropHint?.kind,
                choice = state.dropChoice,
                onFlip = { onIntent(EditorIntent.FlipDropChoice) },
            )
        }
        @Composable fun markerChip() {
            state.markerHint?.let { MarkerHintChip(it, onIntent) }
        }
        if (chips.stacked) {
            Column(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = chips.topDp.dp, start = 8.dp, end = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                dropChip()
                markerChip()
            }
        } else {
            Box(Modifier.align(Alignment.TopEnd).padding(top = chips.topDp.dp, end = 8.dp)) { dropChip() }
            Box(Modifier.align(Alignment.TopCenter).padding(top = chips.topDp.dp)) { markerChip() }
        }
        if (state.flagListOpen) FlagListSheet(state, onIntent)
        state.markerPopup?.let {
            val top = chips.topDp.dp - 4.dp
            // Taller than a short timeline: the card scrolls inside what is left under the ruler.
            MarkerPopupCard(it, onIntent, Modifier.align(Alignment.TopCenter).padding(top = top, start = 8.dp, end = 8.dp).heightIn(max = (maxHeight - top).coerceAtLeast(0.dp)))
        }
        if (state.inspectorOpen && inspectorOverlay) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                // Swallow touches so they never reach the timeline underneath.
                modifier = Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } },
            ) {
                InspectorPanel(
                    state = state,
                    onIntent = onIntent,
                    transitionLimit = transitionLimit,
                    titleTools = titleTools,
                )
            }
        }
    }
}

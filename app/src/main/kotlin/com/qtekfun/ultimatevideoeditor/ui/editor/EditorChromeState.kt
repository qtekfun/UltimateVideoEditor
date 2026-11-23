package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.engine.timeline.HitKind
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.editor.layout.TIMECODE_MAX_FONT_SCALE

/** What the chrome reads: the editor state without its per-tick playhead, plus what the playhead decides. */
@Immutable
internal data class Chrome(val state: EditorState, val selectedClipVisible: Boolean)

/**
 * The inspector shows the pose and keyframes under the playhead, so while it is open it gets the live
 * state; otherwise the playhead is zeroed and a tick leaves the chrome equal to what it was.
 */
internal fun chromeOf(live: EditorState): Chrome =
    Chrome(if (live.inspectorOpen) live else live.copy(playhead = FrameIndex.ZERO), live.selectedClipVisible)

/** The timecode is the only chrome that follows every tick, so it reads the live state in its own scope. */
@Composable
internal fun Timecode(holder: State<EditorState>, compact: Boolean = false, modifier: Modifier = Modifier) {
    val text by remember(holder, compact) {
        derivedStateOf { holder.value.let { if (compact) formatTimecodeCompact(it.playhead.value, it.fps) else formatTimecode(it.playhead.value, it.fps) } }
    }
    // A readout whose width the transport row plans for: it follows the system font only up to a point.
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale.coerceAtMost(TIMECODE_MAX_FONT_SCALE))) {
        Text(text = text, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false, modifier = modifier)
    }
}

/** Where the finger is during a drag: over the lanes, in the room above them, or off the panel (cancel). */
internal fun dragZoneOf(hit: TimelineHit): DragZone = when (hit.kind) {
    HitKind.ABOVE_LANES, HitKind.RULER, HitKind.MARKER -> DragZone.ABOVE_LANES
    HitKind.OUTSIDE -> DragZone.OUTSIDE
    else -> DragZone.LANES
}

package com.qtekfun.ultimatevideoeditor.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qtekfun.ultimatevideoeditor.domain.DropPlan
import com.qtekfun.ultimatevideoeditor.engine.timeline.TimelineHit
import com.qtekfun.ultimatevideoeditor.ui.editor.tray.AssetKind

/** The timeline's drag, lane-drag and scrub gestures, turned into intents. */
@Composable
internal fun rememberTimelineEditing(viewModel: EditorViewModel): TimelineEditing =
    remember(viewModel) {
        object : TimelineEditing {
            override fun canDrag(hit: TimelineHit) = viewModel.canDrag(hit)
            override fun onDragStart(hit: TimelineHit) = viewModel.onIntent(EditorIntent.DragStart(hit))
            override fun onDragMove(hit: TimelineHit) = onDragMove(hit, DropPlan.INSERT_RADIUS_FRAMES)
            override fun onDragMove(hit: TimelineHit, reachFrames: Long) =
                viewModel.onIntent(EditorIntent.DragMove(hit.frame, hit.trackIndex, dragZoneOf(hit), reachFrames))
            override fun onDropModeTap() = viewModel.onIntent(EditorIntent.FlipDropChoice)
            override fun onDragEnd(commit: Boolean) = viewModel.onIntent(EditorIntent.DragEnd(commit))
            override fun onScrub() = viewModel.onIntent(EditorIntent.ScrubStarted)
            override fun onLaneDragStart(hit: TimelineHit) = viewModel.onIntent(LaneDragIntent.Start(hit))
            override fun onLaneDragMove(hit: TimelineHit) = viewModel.onIntent(LaneDragIntent.Move(hit))
            override fun onLaneDragEnd(commit: Boolean) = viewModel.onIntent(LaneDragIntent.End(commit))
        }
    }

/** What the timeline does with a file or a media tile dragged over it. */
@Composable
internal fun rememberTimelineDropTarget(viewModel: EditorViewModel): TimelineDropTarget =
    remember(viewModel) {
        object : TimelineDropTarget {
            override fun onExternalEnter(kinds: List<AssetKind>) = viewModel.onIntent(EditorIntent.ExternalDragStart(kinds))
            override fun onHover(hit: TimelineHit) = viewModel.onIntent(EditorIntent.TrayDragMove(hit.frame, hit.trackIndex, dragZoneOf(hit)))
            override fun onLeave() = viewModel.onIntent(EditorIntent.TrayDragLeave)
            override fun onExternalDrop(uris: List<String>, hit: TimelineHit) =
                viewModel.onIntent(EditorIntent.ExternalDrop(uris, hit.frame, hit.trackIndex, dragZoneOf(hit)))
            override fun onEnd() = viewModel.onIntent(EditorIntent.TrayDragEnd(commit = false))
        }
    }

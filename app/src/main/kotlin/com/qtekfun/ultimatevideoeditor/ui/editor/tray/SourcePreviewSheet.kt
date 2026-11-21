package com.qtekfun.ultimatevideoeditor.ui.editor.tray

import android.text.format.Formatter
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import com.qtekfun.ultimatevideoeditor.ui.editor.ToolButton

private const val PHOTO_PX = 1280

/**
 * The preview of one file before it is used: its picture (or the sound's bar), play and pause, a position slider, mute and repeat,
 * what the file says about itself, and the buttons that add it to the timeline. Closing the sheet lets go of the player.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourcePreviewSheet(
    controller: SourcePreviewController,
    onAddAtPlayhead: (SourceItem) -> Unit,
    onAddAtEnd: (SourceItem) -> Unit,
) {
    val item = controller.item ?: return
    ModalBottomSheet(
        onDismissRequest = controller::close,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = controller::close) { Text(stringResource(R.string.common_close)) }
            }
            Picture(controller, item)
            when (val phase = controller.phase) {
                SourcePhase.Loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                is SourcePhase.Failed -> Text(
                    stringResource(
                        when (phase.problem) {
                            SourceProblem.UNREADABLE -> R.string.ed_sp_unreadable
                            SourceProblem.UNSUPPORTED -> R.string.ed_sp_unsupported
                            SourceProblem.BUSY -> R.string.ed_sp_busy
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
                SourcePhase.Ready -> if (item.kind != AssetKind.PHOTO) Transport(controller)
            }
            Facts(controller, item)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = { onAddAtPlayhead(item); controller.close() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ed_sp_add_playhead), maxLines = 1)
                }
                OutlinedButton(onClick = { onAddAtEnd(item); controller.close() }, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.ed_sp_add_end), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Picture(controller: SourcePreviewController, item: SourceItem) {
    val shape = controller.videoSize?.let { (w, h) -> w.toFloat() / h } ?: (16f / 9f)
    val frame = Modifier.fillMaxWidth().heightIn(max = 360.dp).aspectRatio(shape).background(Color.Black)
    when (item.kind) {
        AssetKind.VIDEO -> Box(frame) {
            // The player draws straight into this surface; it is handed over when it exists and taken back when it goes.
            AndroidView(
                factory = { context ->
                    SurfaceView(context).also { view ->
                        view.holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) = controller.setSurface(holder.surface)

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) = controller.setSurface(null)
                        })
                    }
                },
                modifier = Modifier.matchParentSize(),
            )
        }
        AssetKind.PHOTO -> {
            val context = LocalContext.current
            val bitmap by produceState<ImageBitmap?>(null, item.uri) {
                value = AssetThumbnails.load(context, item.uri, AssetKind.PHOTO, PHOTO_PX)?.asImageBitmap()
            }
            Box(frame, contentAlignment = Alignment.Center) {
                bitmap?.let { Image(it, contentDescription = item.name, contentScale = ContentScale.Fit, modifier = Modifier.matchParentSize()) }
            }
        }
        AssetKind.AUDIO -> Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.ed_fl_f_audio), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    DisposableEffect(controller) { onDispose { controller.setSurface(null) } }
}

@Composable
private fun Transport(controller: SourcePreviewController) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    val fraction = if (dragging) dragFraction else SourcePreviewMath.fractionOf(controller.positionMs, controller.durationMs)
    val shown = if (dragging) SourcePreviewMath.positionAt(dragFraction, controller.durationMs) else controller.positionMs
    Column {
        Slider(
            value = fraction,
            onValueChange = {
                dragging = true
                dragFraction = it
                controller.seekToFraction(it)
            },
            onValueChangeFinished = { dragging = false },
            modifier = Modifier.semantics { contentDescription = "" },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clockText(shown), style = MaterialTheme.typography.labelMedium)
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                ToolButton(
                    icon = if (controller.playing) EditorIcons.Pause else EditorIcons.Play,
                    description = stringResource(if (controller.playing) R.string.ed_2a_pause else R.string.ed_2a_play),
                    onClick = controller::togglePlay,
                )
            }
            Text(clockText(controller.durationMs), style = MaterialTheme.typography.labelMedium)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = controller.muted, onClick = controller::toggleMute, label = { Text(stringResource(R.string.ed_sp_mute)) })
            FilterChip(selected = controller.looping, onClick = controller::toggleLooping, label = { Text(stringResource(R.string.ed_sp_repeat)) })
        }
    }
}

@Composable
private fun Facts(controller: SourcePreviewController, item: SourceItem) {
    val context = LocalContext.current
    val size = item.sizeBytes?.let { Formatter.formatShortFileSize(context, it) }
    val line = (SourcePreviewMath.facts(controller.info) + listOfNotNull(size)).joinToString(" · ")
    if (line.isNotEmpty()) {
        Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

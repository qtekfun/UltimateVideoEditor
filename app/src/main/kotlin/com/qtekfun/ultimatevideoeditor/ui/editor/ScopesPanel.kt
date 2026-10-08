package com.qtekfun.ultimatevideoeditor.ui.editor

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.qtekfun.ultimatevideoeditor.domain.ProjectColorSpace
import com.qtekfun.ultimatevideoeditor.engine.preview.PreviewEngine
import com.qtekfun.ultimatevideoeditor.engine.preview.PreviewException
import com.qtekfun.ultimatevideoeditor.engine.preview.ScopeMode

/**
 * The video scopes: a second native surface that shows the waveform, RGB parade, vectorscope or histogram
 * of the preview. The picture and its graticule are drawn on the GPU by the engine at up to 30 Hz and
 * only while this panel is on screen; Compose lays the view out and draws the scale labels on top.
 */
@Composable
internal fun ScopesPanel(
    engine: PreviewEngine,
    colorSpace: ProjectColorSpace,
    onError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var modeCode by rememberSaveable { mutableIntStateOf(ScopeMode.WAVEFORM.code) }
    val mode = ScopeMode.fromCode(modeCode) ?: ScopeMode.WAVEFORM
    val report = rememberUpdatedState(onError)
    LaunchedEffect(engine, mode) {
        try {
            engine.setScopeMode(mode)
        } catch (e: IllegalStateException) {
            report.value("The scopes could not change mode: ${e.message}")
        }
    }
    Column(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(Color(0xCC000000)).padding(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (candidate in ScopeMode.entries) {
                FilterChip(
                    selected = candidate == mode,
                    onClick = { modeCode = candidate.code },
                    label = { Text(candidate.label, fontSize = 11.sp) },
                    modifier = Modifier.semantics { contentDescription = "Show the ${candidate.label} scope" },
                )
            }
        }
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    SurfaceView(context).apply {
                        // Above the preview's own surface, which is also a media-layer surface.
                        setZOrderMediaOverlay(true)
                        holder.addCallback(
                            object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    try {
                                        engine.attachScopeSurface(holder.surface)
                                    } catch (e: PreviewException) {
                                        report.value("The scopes could not start: ${e.message}")
                                    }
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    try {
                                        engine.scopeSurfaceChanged()
                                    } catch (e: IllegalStateException) {
                                        report.value("The scopes stopped: ${e.message}")
                                    }
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) = engine.detachScopeSurface()
                            },
                        )
                    }
                },
            )
            val vertical = ScopeScale.verticalLabels(mode, colorSpace)
            if (vertical.isNotEmpty()) {
                Column(
                    modifier = Modifier.fillMaxHeight().padding(start = 2.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    for (label in vertical) ScaleLabel(label)
                }
            }
            val horizontal = ScopeScale.horizontalLabels(mode)
            if (horizontal.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    for (label in horizontal) ScaleLabel(label)
                }
            }
        }
        Text(
            text = ScopeScale.caption(mode, colorSpace),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFBBBBBB),
            maxLines = 1,
        )
    }
}

@Composable
private fun ScaleLabel(text: String) {
    Text(text = text, fontSize = 9.sp, color = Color(0xFFDDDDDD))
}

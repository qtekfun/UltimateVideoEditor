package com.qtekfun.ultimatevideoeditor.ui.preview

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.qtekfun.ultimatevideoeditor.engine.preview.OutputSpace
import com.qtekfun.ultimatevideoeditor.engine.preview.PreviewEngine

/**
 * Hosts the preview [SurfaceView]. Compose only lays the view out; every frame is drawn by the
 * native compositor straight onto the surface, so scrubbing never recomposes.
 *
 * [wanted] is the colour space the preview should be rendered in; [onOutputSpace] reports the one
 * the device actually granted (an HLG request is refused without a ten-bit surface).
 */
@Composable
fun PreviewSurface(
    engine: PreviewEngine,
    modifier: Modifier = Modifier,
    wanted: OutputSpace = OutputSpace.SDR_709,
    onOutputSpace: (OutputSpace) -> Unit = {},
) {
    val currentWanted = rememberUpdatedState(wanted)
    val report = rememberUpdatedState(onOutputSpace)
    LaunchedEffect(engine, wanted) { report.value(engine.setOutputSpace(wanted)) }
    val view = remember { arrayOfNulls<SurfaceView>(1) }
    RecreateSurfaceAfterRotation { view[0] }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).also { view[0] = it }.apply {
                var lastWidth = 0
                var lastHeight = 0
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // The request is remembered natively and applied when the surface is attached.
                            engine.setOutputSpace(currentWanted.value)
                            engine.attachSurface(holder.surface)
                            report.value(engine.outputSpace())
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            // After a rotation the EGL window surface keeps the buffer geometry it had and the picture stays
                            // cropped at the old size, so a resize takes the surface again (the timeline does the same).
                            if (lastWidth > 0 && (width != lastWidth || height != lastHeight)) {
                                engine.detachSurface()
                                engine.attachSurface(holder.surface)
                            }
                            lastWidth = width
                            lastHeight = height
                            engine.surfaceChanged()
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) = engine.detachSurface()
                    },
                )
            }
        },
    )
}

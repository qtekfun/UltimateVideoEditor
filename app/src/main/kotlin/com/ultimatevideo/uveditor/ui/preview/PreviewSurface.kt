package com.ultimatevideo.uveditor.ui.preview

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ultimatevideo.uveditor.engine.preview.OutputSpace
import com.ultimatevideo.uveditor.engine.preview.PreviewEngine

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
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            // The request is remembered natively and applied when the surface is attached.
                            engine.setOutputSpace(currentWanted.value)
                            engine.attachSurface(holder.surface)
                            report.value(engine.outputSpace())
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                            engine.surfaceChanged()

                        override fun surfaceDestroyed(holder: SurfaceHolder) = engine.detachSurface()
                    },
                )
            }
        },
    )
}

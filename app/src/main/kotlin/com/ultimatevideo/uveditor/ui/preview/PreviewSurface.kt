package com.ultimatevideo.uveditor.ui.preview

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.ultimatevideo.uveditor.engine.preview.PreviewEngine

/**
 * Hosts the preview [SurfaceView]. Compose only lays the view out; every frame is drawn by the
 * native compositor straight onto the surface, so scrubbing never recomposes.
 */
@Composable
fun PreviewSurface(engine: PreviewEngine, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = engine.attachSurface(holder.surface)

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
                            engine.surfaceChanged()

                        override fun surfaceDestroyed(holder: SurfaceHolder) = engine.detachSurface()
                    },
                )
            }
        },
    )
}

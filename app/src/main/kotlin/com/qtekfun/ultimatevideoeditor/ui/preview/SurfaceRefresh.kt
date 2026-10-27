package com.qtekfun.ultimatevideoeditor.ui.preview

import android.content.res.Configuration
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.withFrameNanos

/**
 * After a rotation the window animates between the two sizes and a `SurfaceView` can keep the size of one of the frames in between
 * (the system logs "rejecting buffer" and the picture stays cropped, even though the view itself is laid out at the right size).
 * Hiding and showing the view once the turn has settled makes the platform build its surface again at the final size.
 */
@Composable
internal fun RecreateSurfaceAfterRotation(view: () -> View?) {
    val orientation = LocalConfiguration.current.orientation
    var seen by remember { mutableStateOf(orientation) }
    LaunchedEffect(orientation) {
        if (orientation == seen) return@LaunchedEffect
        seen = orientation
        if (orientation == Configuration.ORIENTATION_UNDEFINED) return@LaunchedEffect
        val target = view() ?: return@LaunchedEffect
        // Rebuild as soon as the view has kept one size for a few frames: the turn animation is over then, and waiting longer only
        // leaves the cropped picture on screen.
        var width = -1
        var height = -1
        var steady = 0
        val start = withFrameNanos { it }
        while (steady < STEADY_FRAMES) {
            val now = withFrameNanos { it }
            if (now - start > TIMEOUT_NS) break
            val root = target.rootView
            val turned = (root.width > root.height) == (orientation == Configuration.ORIENTATION_LANDSCAPE)
            steady = if (turned && target.width == width && target.height == height) steady + 1 else 0
            width = target.width
            height = target.height
        }
        target.visibility = View.INVISIBLE
        withFrameNanos { }
        withFrameNanos { }
        target.visibility = View.VISIBLE
    }
}

private const val STEADY_FRAMES = 6
private const val TIMEOUT_NS = 1_200_000_000L

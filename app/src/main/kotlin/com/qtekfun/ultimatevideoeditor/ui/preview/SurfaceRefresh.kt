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
import kotlinx.coroutines.delay

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
        delay(SETTLE_MS)
        val target = view() ?: return@LaunchedEffect
        target.visibility = View.INVISIBLE
        withFrameNanos { }
        withFrameNanos { }
        target.visibility = View.VISIBLE
    }
}

private const val SETTLE_MS = 450L

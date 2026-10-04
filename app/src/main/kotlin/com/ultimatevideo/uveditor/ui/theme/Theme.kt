package com.ultimatevideo.uveditor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

@Composable
fun UVEditorTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // minSdk 31 guarantees dynamic colour is available (it starts at Android 12).
    val colorScheme = if (isSystemInDarkTheme()) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Editors are dark by design; kept for the timeline/preview surfaces later. */
internal val EditorFallbackScheme = darkColorScheme()

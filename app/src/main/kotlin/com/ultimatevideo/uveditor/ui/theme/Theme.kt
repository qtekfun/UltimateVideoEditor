package com.ultimatevideo.uveditor.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** What the user chose about appearance. The app is dark only; the one choice is pure black backgrounds. */
interface AppearanceStore {
    var amoled: Boolean
}

/** Keeps the choice on this device only. Writing it updates [UVEditorTheme] at once, no restart of the activity. */
class PreferencesAppearanceStore(context: Context) : AppearanceStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private var live by mutableStateOf(prefs.getBoolean(KEY_AMOLED, false))

    override var amoled: Boolean
        get() = live
        set(value) {
            live = value
            prefs.edit().putBoolean(KEY_AMOLED, value).apply()
        }

    companion object {
        const val FILE = "appearance"
        private const val KEY_AMOLED = "amoled"
    }
}

/** The palette in use, for code that draws with its own colours (the native renderers get the same tokens). */
val LocalPalette = staticCompositionLocalOf { Palette.Dark }

private fun Int.c() = Color(this)

fun paletteColorScheme(p: Palette) = darkColorScheme(
    primary = p.primary.c(), onPrimary = p.onPrimary.c(),
    primaryContainer = p.primaryContainer.c(), onPrimaryContainer = p.onPrimaryContainer.c(),
    secondary = p.secondary.c(), onSecondary = p.onSecondary.c(),
    secondaryContainer = p.secondaryContainer.c(), onSecondaryContainer = p.onSecondaryContainer.c(),
    tertiary = p.tertiary.c(), onTertiary = p.onTertiary.c(),
    tertiaryContainer = p.tertiaryContainer.c(), onTertiaryContainer = p.onTertiaryContainer.c(),
    error = p.error.c(), onError = p.onError.c(),
    errorContainer = p.errorContainer.c(), onErrorContainer = p.onErrorContainer.c(),
    background = p.background.c(), onBackground = p.onSurface.c(),
    surface = p.surface.c(), onSurface = p.onSurface.c(),
    surfaceVariant = p.surfaceHighest.c(), onSurfaceVariant = p.onSurfaceVariant.c(),
    surfaceTint = p.primary.c(),
    inverseSurface = p.onSurface.c(), inverseOnSurface = p.background.c(), inversePrimary = p.primaryContainer.c(),
    outline = p.outline.c(), outlineVariant = p.outlineVariant.c(),
    scrim = Color.Black,
    surfaceBright = p.surfaceHighest.c(), surfaceDim = p.background.c(),
    surfaceContainerLowest = p.background.c(), surfaceContainerLow = p.surfaceLow.c(),
    surfaceContainer = p.surfaceContainer.c(), surfaceContainerHigh = p.surfaceHigh.c(),
    surfaceContainerHighest = p.surfaceHighest.c(),
)

/** Dark only, from our own tokens (no dynamic colour). [amoled] turns the backgrounds pure black. */
@Composable
fun UVEditorTheme(amoled: Boolean = false, content: @Composable () -> Unit) {
    val palette = Palette.of(amoled)
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = paletteColorScheme(palette), content = content)
    }
}

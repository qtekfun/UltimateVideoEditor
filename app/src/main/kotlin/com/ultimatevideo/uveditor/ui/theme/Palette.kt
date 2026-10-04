package com.ultimatevideo.uveditor.ui.theme

import kotlin.math.pow

/**
 * The one source of colour for the app: the Compose scheme and the native renderers (timeline, scopes) are both built
 * from these tokens. Plain ARGB ints, no Android or Compose types, so JVM tests can check the contrast of every pair.
 * The app is dark only; [amoled] swaps the background steps for pure black.
 */
data class Palette(
    val amoled: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceLow: Int,
    val surfaceContainer: Int,
    val surfaceHigh: Int,
    val surfaceHighest: Int,
    val onSurface: Int,
    val onSurfaceVariant: Int,
    val outline: Int,
    val outlineVariant: Int,
    val primary: Int,
    val onPrimary: Int,
    val primaryContainer: Int,
    val onPrimaryContainer: Int,
    val secondary: Int,
    val onSecondary: Int,
    val secondaryContainer: Int,
    val onSecondaryContainer: Int,
    val tertiary: Int,
    val onTertiary: Int,
    val tertiaryContainer: Int,
    val onTertiaryContainer: Int,
    val error: Int,
    val onError: Int,
    val errorContainer: Int,
    val onErrorContainer: Int,
    // Timeline: lane bands, ruler, block colours by kind and the marks drawn over them.
    val laneA: Int,
    val laneB: Int,
    val ruler: Int,
    val tick: Int,
    val rulerText: Int,
    val clipVideo: Int,
    val clipAudio: Int,
    val clipTitle: Int,
    val clipImage: Int,
    val clipSticker: Int,
    val clipMulticam: Int,
    val onClip: Int,
    val playhead: Int,
    val selection: Int,
    val keyframe: Int,
    val marker: Int,
) {
    /**
     * Flat list handed to the native renderers: every entry is one ARGB colour, in the order the C++ side reads them
     * (`timeline_theme.h`). Append only; the native side checks the count.
     */
    fun nativeColours(): IntArray = intArrayOf(
        background, laneA, laneB, ruler, tick, rulerText,
        clipVideo, clipAudio, clipTitle, clipImage, clipSticker, clipMulticam, onClip,
        playhead, selection, keyframe, marker,
        surfaceHigh, onSurface, onSurfaceVariant, primary, error,
    )

    companion object {
        const val NATIVE_COLOUR_COUNT = 22

        val Dark = Palette(
            amoled = false,
            background = 0xFF0F1115.toInt(), surface = 0xFF14171D.toInt(), surfaceLow = 0xFF1A1D26.toInt(),
            surfaceContainer = 0xFF1F232E.toInt(), surfaceHigh = 0xFF272B38.toInt(), surfaceHighest = 0xFF303544.toInt(),
            onSurface = 0xFFE6E8EE.toInt(), onSurfaceVariant = 0xFFAAB0C0.toInt(),
            outline = 0xFF7C8396.toInt(), outlineVariant = 0xFF3A4050.toInt(),
            primary = 0xFF8AB4FF.toInt(), onPrimary = 0xFF0A2A5E.toInt(),
            primaryContainer = 0xFF1F4A8F.toInt(), onPrimaryContainer = 0xFFD6E4FF.toInt(),
            secondary = 0xFF7DD3C0.toInt(), onSecondary = 0xFF00382F.toInt(),
            secondaryContainer = 0xFF1B4D44.toInt(), onSecondaryContainer = 0xFFC2F2E7.toInt(),
            tertiary = 0xFFF5B971.toInt(), onTertiary = 0xFF442A00.toInt(),
            tertiaryContainer = 0xFF664000.toInt(), onTertiaryContainer = 0xFFFFDDB8.toInt(),
            error = 0xFFFF8A80.toInt(), onError = 0xFF5A0A05.toInt(),
            errorContainer = 0xFF8C1D18.toInt(), onErrorContainer = 0xFFFFDAD6.toInt(),
            laneA = 0xFF161920.toInt(), laneB = 0xFF1B1F28.toInt(), ruler = 0xFF222633.toInt(),
            tick = 0xFF8C93A6.toInt(), rulerText = 0xFFC4C9D6.toInt(),
            clipVideo = 0xFF2F6FD6.toInt(), clipAudio = 0xFF167A62.toInt(), clipTitle = 0xFF8A5CD6.toInt(),
            clipImage = 0xFFA85F1F.toInt(), clipSticker = 0xFFC03A7A.toInt(), clipMulticam = 0xFF1F7F90.toInt(),
            onClip = 0xFFFFFFFF.toInt(),
            playhead = 0xFFFF5A52.toInt(), selection = 0xFFFFD94A.toInt(), keyframe = 0xFFFFC21A.toInt(),
            marker = 0xFFFF73CC.toInt(),
        )

        /** Pure black backgrounds; the surface steps move to the next dark values so cards still separate from it. */
        val Amoled = Dark.copy(
            amoled = true,
            background = 0xFF000000.toInt(), surface = 0xFF000000.toInt(), surfaceLow = 0xFF0B0C10.toInt(),
            surfaceContainer = 0xFF12141A.toInt(), surfaceHigh = 0xFF1A1D25.toInt(), surfaceHighest = 0xFF23262F.toInt(),
            outlineVariant = 0xFF30353F.toInt(),
            laneA = 0xFF07080B.toInt(), laneB = 0xFF0E1015.toInt(), ruler = 0xFF14161D.toInt(),
        )

        fun of(amoled: Boolean): Palette = if (amoled) Amoled else Dark
    }
}

/** WCAG 2.x contrast maths over ARGB ints (alpha ignored: palette entries are opaque). */
object Contrast {
    fun luminance(argb: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}

package com.ultimatevideo.uveditor.ui.hub

import java.util.Locale

data class ResolutionPreset(val label: String, val width: Int, val height: Int)

data class FpsPreset(val label: String, val num: Int, val den: Int)

data class ColorSpacePreset(val label: String, val id: String)

object ProjectPresets {
    val resolutions = listOf(
        ResolutionPreset("720p", 1280, 720),
        ResolutionPreset("1080p", 1920, 1080),
        ResolutionPreset("1440p", 2560, 1440),
        ResolutionPreset("4K", 3840, 2160),
        ResolutionPreset("1080×1920 (9:16)", 1080, 1920),
        ResolutionPreset("1080×1080 (1:1)", 1080, 1080),
    )

    val fps = listOf(
        FpsPreset("23.976", 24000, 1001),
        FpsPreset("24", 24, 1),
        FpsPreset("25", 25, 1),
        FpsPreset("29.97", 30000, 1001),
        FpsPreset("30", 30, 1),
        FpsPreset("50", 50, 1),
        FpsPreset("59.94", 60000, 1001),
        FpsPreset("60", 60, 1),
    )

    val colorSpaces = listOf(
        ColorSpacePreset("SDR Rec.709", "Rec709-SDR"),
        ColorSpacePreset("HLG Rec.2020", "Rec2020-HLG"),
    )

    val defaultResolution = resolutions[1]
    val defaultFps = fps[4]
    val defaultColorSpace = colorSpaces[0]
}

/** Human-readable frame rate from a rational, e.g. 30000/1001 -> "29.97". */
fun formatFps(num: Int, den: Int): String {
    if (den == 1) return num.toString()
    val rounded = String.format(Locale.ROOT, "%.3f", num.toDouble() / den)
    return rounded.trimEnd('0').trimEnd('.')
}

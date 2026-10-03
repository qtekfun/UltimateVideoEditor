package com.ultimatevideo.uveditor.ui.hub

import java.util.Locale

data class ResolutionPreset(val label: String, val width: Int, val height: Int) {
    /** The reduced aspect ratio, e.g. "16:9" or "9:16". */
    val aspectLabel: String get() = aspectLabelOf(width, height)
}

/** A titled run of presets that share an aspect ratio, shown together in the New project dialog. */
data class ResolutionGroup(val title: String, val presets: List<ResolutionPreset>)

private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

/** "16:9" for 1920x1080; sizes such as 1080x1350 reduce to "4:5". */
fun aspectLabelOf(width: Int, height: Int): String {
    require(width > 0 && height > 0) { "invalid size ${width}x$height" }
    val divisor = gcd(width, height)
    return "${width / divisor}:${height / divisor}"
}

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
        ResolutionPreset("720×1280 (9:16)", 720, 1280),
        ResolutionPreset("2160×3840 (9:16)", 2160, 3840),
        ResolutionPreset("1080×1350 (4:5)", 1080, 1350),
    )

    /** The same presets by shape, with the platforms each one is made for. */
    val resolutionGroups = listOf(
        ResolutionGroup("Landscape 16:9 (YouTube)", resolutions.filter { it.aspectLabel == "16:9" }),
        ResolutionGroup("Vertical 9:16 (TikTok, Shorts, Reels)", resolutions.filter { it.aspectLabel == "9:16" }),
        ResolutionGroup("Square 1:1", resolutions.filter { it.aspectLabel == "1:1" }),
        ResolutionGroup("Portrait 4:5 (Instagram feed)", resolutions.filter { it.aspectLabel == "4:5" }),
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

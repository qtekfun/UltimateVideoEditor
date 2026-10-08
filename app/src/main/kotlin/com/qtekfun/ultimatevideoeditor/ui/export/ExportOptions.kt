package com.qtekfun.ultimatevideoeditor.ui.export

import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.engine.export.ExportCodec
import kotlin.math.roundToInt

/** An output size. [shortSide] is the label ("1080p"); the aspect ratio follows the project. */
data class ResolutionOption(val width: Int, val height: Int, val shortSide: Int) {
    val label: String get() = "${shortSide}p"
}

private val StandardShortSides = listOf(2160, 1440, 1080, 720, 480)
private val StandardRates = listOf(FrameRate(60, 1), FrameRate(50, 1), FrameRate(30, 1), FrameRate(25, 1), FrameRate(24, 1))
private val BitrateChoicesMbps = listOf(4, 8, 12, 20, 35, 50, 80)

private fun even(value: Double): Int = ((value / 2.0).roundToInt() * 2).coerceAtLeast(2)

/** Sizes at or below the project's, largest first; the project's own size is always offered. */
fun resolutionOptions(projectWidth: Int, projectHeight: Int): List<ResolutionOption> {
    require(projectWidth > 0 && projectHeight > 0) { "invalid project size ${projectWidth}x$projectHeight" }
    val projectShort = minOf(projectWidth, projectHeight)
    val sides = (StandardShortSides.filter { it < projectShort } + projectShort).sortedDescending()
    return sides.map { side ->
        if (side == projectShort) {
            ResolutionOption(projectWidth - projectWidth % 2, projectHeight - projectHeight % 2, side)
        } else {
            val scale = side.toDouble() / projectShort
            ResolutionOption(even(projectWidth * scale), even(projectHeight * scale), side)
        }
    }
}

/** The project's own rate first, then the standard rates that are not faster than it. */
fun frameRateOptions(project: FrameRate): List<FrameRate> {
    val projectValue = project.num.toDouble() / project.den
    val slower = StandardRates.filter { it.num.toDouble() / it.den < projectValue - 0.01 }
    return listOf(project) + slower
}

/** A starting bitrate for the chosen settings: bits per pixel per frame, lower for HEVC. */
fun suggestedBitrateMbps(width: Int, height: Int, fps: FrameRate, codec: ExportCodec): Int {
    val bitsPerPixel = if (codec == ExportCodec.HEVC) 0.045 else 0.07
    val mbps = width.toDouble() * height * (fps.num.toDouble() / fps.den) * bitsPerPixel / 1_000_000.0
    return BitrateChoicesMbps.firstOrNull { it >= mbps } ?: BitrateChoicesMbps.last()
}

fun bitrateChoicesMbps(): List<Int> = BitrateChoicesMbps

/** A file name for the movie: the project name without characters that file systems reject. */
fun suggestedFileName(projectName: String): String {
    val cleaned = projectName.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ')
    return (cleaned.ifEmpty { "ultimateVE" }).take(80) + ".mp4"
}

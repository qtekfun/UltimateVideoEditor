package com.ultimatevideo.uveditor.proxy

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a probe of a video file reports. [rotationDegrees] is the container's display rotation. */
data class SourceInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0,
    val bitrateBps: Long = 0,
    val sizeBytes: Long = 0,
) {
    private val swapped get() = rotationDegrees == 90 || rotationDegrees == 270
    val displayWidth: Int get() = if (swapped) height else width
    val displayHeight: Int get() = if (swapped) width else height
    val shortSide: Int get() = min(displayWidth, displayHeight)
}

/** Output size (even, in display orientation) and bitrate of a proxy. */
data class ProxyTarget(val width: Int, val height: Int, val bitrate: Int)

object ProxyTargets {
    const val SHORT_SIDE_720 = 720
    const val SHORT_SIDE_1080 = 1080
    val choices = listOf(SHORT_SIDE_720, SHORT_SIDE_1080)

    private const val BITS_PER_PIXEL = 0.15
    private const val MIN_BITRATE = 2_000_000L
    private const val MAX_BITRATE = 40_000_000L

    /**
     * The proxy for [info] at [targetShortSide] on the short side, same aspect ratio, never larger than the
     * source; null when the source's short side is already at or under it (a proxy would not be lighter).
     */
    fun plan(info: SourceInfo, fpsNum: Int, fpsDen: Int, targetShortSide: Int): ProxyTarget? {
        require(targetShortSide > 0 && fpsNum > 0 && fpsDen > 0) { "invalid proxy target" }
        if (info.displayWidth <= 0 || info.displayHeight <= 0) return null
        if (info.shortSide <= targetShortSide) return null
        val scale = targetShortSide.toDouble() / info.shortSide
        val width = even(info.displayWidth * scale)
        val height = even(info.displayHeight * scale)
        val fps = fpsNum.toDouble() / fpsDen
        val bitrate = (width.toLong() * height * fps * BITS_PER_PIXEL).toLong().coerceIn(MIN_BITRATE, MAX_BITRATE).toInt()
        return ProxyTarget(width, height, bitrate)
    }

    private fun even(value: Double): Int = max(2, (value / 2).roundToInt() * 2)
}

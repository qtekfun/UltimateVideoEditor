package com.qtekfun.ultimatevideoeditor.engine.still

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * The parameters of an Ultra HDR gain map (the fields of `android.graphics.Gainmap`, copied so the maths is plain Kotlin and
 * tested on the JVM). Each array holds the red, green and blue value; a map with one channel repeats it.
 */
class GainmapParams(
    val ratioMin: FloatArray,
    val ratioMax: FloatArray,
    val gamma: FloatArray,
    val epsilonSdr: FloatArray,
    val epsilonHdr: FloatArray,
    /** How much brighter than the SDR rendition the picture is at full HDR (the headroom at which weight 1 applies). */
    val displayRatioForFull: Float,
) {
    init {
        require(ratioMin.size == 3 && ratioMax.size == 3 && gamma.size == 3 && epsilonSdr.size == 3 && epsilonHdr.size == 3) { "three channels" }
    }
}

/**
 * Turns an SDR picture and its gain map into the HDR picture in the project's HLG signal. The HDR rendition is
 * `(sdr + epsilonSdr) * exp(logBoost) - epsilonHdr` in linear light, with `logBoost` interpolated between the map's
 * log(ratioMin) and log(ratioMax) by the (gamma corrected) map value, as the Ultra HDR format and `android.graphics.Gainmap` define it. The picture is
 * made for full HDR (weight 1): the project's HLG signal is the target, whatever the screen can show. Linear value 1 is SDR white,
 * which the project places at 203 nit like every other SDR source; the result is encoded as HLG on a 1000 nit system.
 */
object GainmapMath {
    private const val REFERENCE_WHITE_NITS = 203.0
    private const val PEAK_NITS = 1000.0
    private const val SYSTEM_GAMMA = 1.2

    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073

    /** sRGB code 0..255 to linear light, 0..1. */
    val SRGB_TO_LINEAR: FloatArray = FloatArray(256) { code ->
        val v = code / 255.0
        (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
    }

    /** The linear HDR value of one channel (1.0 = SDR white) from the SDR [linear] value and the gain map value [map], 0..1. */
    fun hdrLinear(linear: Float, map: Float, params: GainmapParams, channel: Int): Float {
        val recovery = map.coerceIn(0f, 1f).toDouble().pow(1.0 / params.gamma[channel])
        val logBoost = ln(params.ratioMin[channel].toDouble()) * (1.0 - recovery) + ln(params.ratioMax[channel].toDouble()) * recovery
        return (((linear + params.epsilonSdr[channel]) * exp(logBoost)) - params.epsilonHdr[channel]).toFloat().coerceAtLeast(0f)
    }

    /**
     * The HLG signal (0..1 per channel, BT.2020 primaries) of a linear Rec.709 / sRGB colour whose values are relative to SDR white.
     * Light above the 1000 nit peak clips.
     */
    fun hlgSignal(rLinear: Float, gLinear: Float, bLinear: Float, out: FloatArray) {
        // Rec.709 -> Rec.2020 primaries (BT.2087), then SDR white (1.0) -> 203 nit as a fraction of the 1000 nit system.
        val scale = REFERENCE_WHITE_NITS / PEAK_NITS
        val r = (0.6274 * rLinear + 0.3293 * gLinear + 0.0433 * bLinear) * scale
        val g = (0.0691 * rLinear + 0.9195 * gLinear + 0.0114 * bLinear) * scale
        val b = (0.0164 * rLinear + 0.0880 * gLinear + 0.8956 * bLinear) * scale
        val dr = r.coerceIn(0.0, 1.0)
        val dg = g.coerceIn(0.0, 1.0)
        val db = b.coerceIn(0.0, 1.0)
        // Display light -> scene light (inverse HLG OOTF), then the HLG OETF.
        val yd = 0.2627 * dr + 0.6780 * dg + 0.0593 * db
        val k = if (yd > 0.0) yd.pow((1.0 - SYSTEM_GAMMA) / SYSTEM_GAMMA) else 0.0
        out[0] = hlgOetf(dr * k)
        out[1] = hlgOetf(dg * k)
        out[2] = hlgOetf(db * k)
    }

    private fun hlgOetf(e: Double): Float {
        val x = e.coerceIn(0.0, 1.0)
        return (if (x <= 1.0 / 12.0) kotlin.math.sqrt(3.0 * x) else HLG_A * ln(12.0 * x - HLG_B) + HLG_C).toFloat()
    }

    /**
     * An 8-bit code for the signal value [v], dithered with an ordered 4x4 pattern at pixel ([x], [y]): the HLG signal in eight bits
     * bands in the dark gradients of a sky, and a fixed pattern (not random) keeps the preview and the export identical.
     */
    fun quantise(v: Float, x: Int, y: Int): Int {
        val threshold = (BAYER_4[(y and 3) * 4 + (x and 3)] + 0.5f) / 16f - 0.5f
        return (v * 255f + threshold + 0.5f).toInt().coerceIn(0, 255)
    }

    private val BAYER_4 = intArrayOf(0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5)
}

/** A gain map as three planes of 0..1 values, sampled with bilinear filtering at normalised coordinates. */
class GainmapPlanes(val width: Int, val height: Int, private val planes: Array<FloatArray>) {
    init {
        require(width > 0 && height > 0 && planes.size == 3 && planes.all { it.size == width * height }) { "bad gain map planes" }
    }

    /** The value of [channel] at ([u], [v]) in 0..1 of the picture (pixel centres at half steps). */
    fun at(u: Float, v: Float, channel: Int): Float {
        val x = (u * width - 0.5f).coerceIn(0f, (width - 1).toFloat())
        val y = (v * height - 0.5f).coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = y.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val fx = x - x0
        val fy = y - y0
        val p = planes[channel]
        val top = p[y0 * width + x0] * (1 - fx) + p[y0 * width + x1] * fx
        val bottom = p[y1 * width + x0] * (1 - fx) + p[y1 * width + x1] * fx
        return top * (1 - fy) + bottom * fy
    }

    companion object {
        /** A single-channel map ([values] 0..1) used for all three channels. */
        fun gray(width: Int, height: Int, values: FloatArray) = GainmapPlanes(width, height, arrayOf(values, values, values))
    }
}

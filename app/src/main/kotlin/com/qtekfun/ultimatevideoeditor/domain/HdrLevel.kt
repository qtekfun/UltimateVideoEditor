package com.qtekfun.ultimatevideoeditor.domain

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * How bright the HDR footage of a project is shown. [COMFORTABLE] dims HLG and PQ clips whose diffuse content is brighter than
 * reference white (the "HDR level", see [HdrLevel]); [ORIGINAL] leaves the footage as recorded. [id] is what `project.json`
 * stores (`settings.hdrBrightness`).
 */
enum class HdrBrightness(val id: String) {
    COMFORTABLE("comfortable"),
    ORIGINAL("original"),
    ;

    companion object {
        /** Unknown or missing values read as [COMFORTABLE], which is the default of every project. */
        fun fromId(id: String?): HdrBrightness = entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: COMFORTABLE
    }
}

/**
 * The automatic HDR level of a clip: phone HLG footage is recorded with its diffuse content (skin, walls) well above the
 * 203 nit reference white that SDR material and graphics sit at, so it dazzles next to them. The level is a display-light
 * gain that only ever dims, found from a handful of frames of the file. The maths here is pure; the frames are read by the
 * analyser (`engine/hdr`), the gain is applied in the shader (`render/shaders.h`, `uLevel`).
 */
object HdrLevel {
    const val REFERENCE_WHITE_NITS = 203.0
    private const val PEAK_NITS = 1000.0
    private const val SYSTEM_GAMMA = 1.2

    /** The most a clip is dimmed, in stops (a factor of about 0.354 of the light). */
    const val MAX_STEPS = 1.5

    /** Frames looked at, spread over the file. */
    const val SAMPLE_FRAMES = 12

    /** The share of the picture at or below the diffuse level: skin and walls are in it, the bright sky and lamps are not. */
    const val DIFFUSE_PERCENTILE = 0.90

    /** The brightest share of all measured cells that is ignored before the percentile (specular highlights, light sources). */
    const val DISCARD_TOP = 0.02

    /** A gain that changes less than this is not worth showing or applying (a level of 1.0 on screen). */
    private const val NEGLIGIBLE = 0.005

    /**
     * The diffuse level of the measured cells, relative to 203 nit: [cellNits] holds the luminance in nit of every cell of every
     * sampled frame. The brightest [DISCARD_TOP] of them are dropped, then the [DIFFUSE_PERCENTILE] of the rest is taken.
     * Null when nothing was measured.
     */
    fun diffuseLevel(cellNits: DoubleArray): Double? {
        if (cellNits.isEmpty()) return null
        val sorted = cellNits.sortedArray()
        val kept = maxOf(1, (sorted.size * (1.0 - DISCARD_TOP)).toInt())
        val index = (kotlin.math.ceil(kept * DIFFUSE_PERCENTILE).toInt() - 1).coerceIn(0, kept - 1)
        return sorted[index] / REFERENCE_WHITE_NITS
    }

    /**
     * The display-light gain for a clip whose diffuse content is at [diffuseLevel] times 203 nit: only dims, never more than
     * [MAX_STEPS] stops, and 1 for content at or below reference white.
     */
    fun gainFor(diffuseLevel: Double): Double {
        if (!diffuseLevel.isFinite() || diffuseLevel <= 1.0) return 1.0
        val gain = (1.0 / diffuseLevel).coerceAtLeast(2.0.pow(-MAX_STEPS))
        return if (1.0 - gain < NEGLIGIBLE) 1.0 else gain
    }

    /** [gain] in stops, 0 or negative ("-0.8" for a gain of 0.574). */
    fun stepsOf(gain: Double): Double = if (gain >= 1.0) 0.0 else ln(gain) / ln(2.0)

    /**
     * The luminance in nit of every cell of a signed frame (see `engine/verify/FrameSignature`): the grid holds mean limited-range
     * Y'CbCr of the stream, BT.2020 non-constant luminance. [transfer] says how the signal encodes light.
     */
    fun cellNits(y: IntArray, cb: IntArray, cr: IntArray, scale: Int, transfer: SourceColorSpace): DoubleArray {
        require(y.size == cb.size && y.size == cr.size) { "planes differ in size" }
        return DoubleArray(y.size) { i ->
            val luma = y[i].toDouble() / scale
            val u = cb[i].toDouble() / scale - 0.5
            val v = cr[i].toDouble() / scale - 0.5
            val r = (luma + 1.4746 * v).coerceIn(0.0, 1.0)
            val b = (luma + 1.8814 * u).coerceIn(0.0, 1.0)
            val g = ((luma - 0.2627 * r - 0.0593 * b) / 0.6780).coerceIn(0.0, 1.0)
            nitsOf(r, g, b, transfer)
        }
    }

    /** Luminance in nit of a nonlinear BT.2020 pixel of [transfer] (HLG on a 1000 nit system, or PQ); SDR is not an HDR source. */
    fun nitsOf(r: Double, g: Double, b: Double, transfer: SourceColorSpace): Double = when (transfer) {
        SourceColorSpace.HLG -> {
            val scene = LUMA_R * hlgInverseOetf(r) + LUMA_G * hlgInverseOetf(g) + LUMA_B * hlgInverseOetf(b)
            PEAK_NITS * scene.pow(SYSTEM_GAMMA)
        }
        SourceColorSpace.PQ -> LUMA_R * pqEotfNits(r) + LUMA_G * pqEotfNits(g) + LUMA_B * pqEotfNits(b)
        SourceColorSpace.SDR -> 0.0
    }

    /**
     * The gain to draw a clip with, from what is known about it: 1 (no change) when the project shows the footage as it was recorded,
     * the clip opted out ([clipSetting] false), the source is not HDR, or its analysis has not finished. [clipSetting] null follows
     * [project].
     */
    fun gain(project: HdrBrightness, clipSetting: Boolean?, source: SourceColorSpace, diffuseLevel: Double?): Double {
        if (source == SourceColorSpace.SDR || diffuseLevel == null) return 1.0
        val on = clipSetting ?: (project == HdrBrightness.COMFORTABLE)
        return if (on) gainFor(diffuseLevel) else 1.0
    }

    private const val LUMA_R = 0.2627
    private const val LUMA_G = 0.6780
    private const val LUMA_B = 0.0593

    private const val HLG_A = 0.17883277
    private const val HLG_B = 0.28466892
    private const val HLG_C = 0.55991073

    private fun hlgInverseOetf(e: Double): Double {
        val x = e.coerceIn(0.0, 1.0)
        return if (x <= 0.5) x * x / 3.0 else (exp((x - HLG_C) / HLG_A) + HLG_B) / 12.0
    }

    private const val PQ_M1 = 2610.0 / 16384.0
    private const val PQ_M2 = 2523.0 / 4096.0 * 128.0
    private const val PQ_C1 = 3424.0 / 4096.0
    private const val PQ_C2 = 2413.0 / 4096.0 * 32.0
    private const val PQ_C3 = 2392.0 / 4096.0 * 32.0

    private fun pqEotfNits(e: Double): Double {
        val p = e.coerceIn(0.0, 1.0).pow(1.0 / PQ_M2)
        return 10_000.0 * (maxOf(p - PQ_C1, 0.0) / (PQ_C2 - PQ_C3 * p)).pow(1.0 / PQ_M1)
    }
}

/**
 * What is known about the HDR level of a project's footage: the project's setting and the diffuse level measured for each
 * (file, colour space) pair, relative to 203 nit. The preview, the exporter and the inspector all ask it for the gain of a clip,
 * so they cannot disagree. A file whose analysis has not finished has no entry and is drawn without a gain.
 */
data class HdrLevels(
    val brightness: HdrBrightness = HdrBrightness.COMFORTABLE,
    val levels: Map<String, Double> = emptyMap(),
    /** The project is an HDR (HLG) project: its photos keep their gain map. */
    val hdrProject: Boolean = false,
) {
    /** The diffuse level of a file read as [space], or null when it has not been measured. */
    fun levelOf(uri: String, durationFrames: Long, space: SourceColorSpace): Double? = levels[keyOf(uri, durationFrames, space)]

    /**
     * The display-light gain of a clip over a file with a [detected] colour space, read as [override] when set. 1 when nothing
     * applies; see [HdrLevel.gain].
     */
    fun gainOf(clipSetting: Boolean?, uri: String, durationFrames: Long, detected: SourceColorSpace, override: SourceColorSpace?): Double {
        val space = override ?: detected
        return HdrLevel.gain(brightness, clipSetting, space, levelOf(uri, durationFrames, space))
    }

    /** True when a clip with this [clipSetting] would be dimmed once its file is measured: the analysis is worth running. */
    fun wantsAnalysis(clipSetting: Boolean?): Boolean = clipSetting ?: (brightness == HdrBrightness.COMFORTABLE)

    companion object {
        /** Footage as recorded: no gain for any clip. */
        val NONE = HdrLevels(HdrBrightness.ORIGINAL)

        /** The file's own identity plus how it is read: the same file read as another space has another level. */
        fun keyOf(uri: String, durationFrames: Long, space: SourceColorSpace): String = "$uri|$durationFrames|${space.id}"
    }
}

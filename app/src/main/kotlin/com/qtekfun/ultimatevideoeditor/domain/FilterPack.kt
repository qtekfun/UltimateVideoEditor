package com.qtekfun.ultimatevideoeditor.domain

import kotlin.math.pow

/**
 * The built-in filter pack (SPECS.md 9.19): about twenty creative looks that are written by us as a few simple
 * colour operations, not copied from anywhere, so they ship under the project's licence. Each look is a function
 * on gamma-encoded RGB (0..1) that is baked into an ordinary `.cube` 3D LUT when the user first picks it and
 * stored in the LUT library, from where it behaves like any imported LUT (LUT effect, intensity slider, preview
 * and export parity). Nothing is downloaded; the files are generated here.
 */
data class LookParams(
    /** Gain in stops applied first (0.5 brightens, -0.5 darkens). */
    val exposure: Double = 0.0,
    /** Mid-tone gamma: above 1 brightens the mid-tones. */
    val gamma: Double = 1.0,
    /** Contrast about middle grey (1 = unchanged). */
    val contrast: Double = 1.0,
    /** Positive adds red and removes blue, negative the opposite. */
    val warmth: Double = 0.0,
    /** Positive pushes towards magenta, negative towards green. */
    val tint: Double = 0.0,
    /** Colour pushed into the shadows (red, green, blue offsets; keep each within about 0.1). */
    val shadows: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    /** Colour pushed into the highlights. */
    val highlights: Triple<Double, Double, Double> = Triple(0.0, 0.0, 0.0),
    val saturation: Double = 1.0,
    /** 0 = colour, 1 = black and white. */
    val mono: Double = 0.0,
    /** 0 = none, 1 = full sepia tone. */
    val sepia: Double = 0.0,
    /** Raises the black point (a matte look): 0 = none. */
    val fade: Double = 0.0,
) {
    fun apply(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val gain = 2.0.pow(exposure)
        val inv = if (gamma > 0.0) 1.0 / gamma else 1.0
        fun base(x: Double) = ((x * gain).coerceAtLeast(0.0).pow(inv) - 0.5) * contrast + 0.5
        var cr = base(r) + warmth * WARM_SHIFT
        var cg = base(g) - tint * TINT_SHIFT
        var cb = base(b) - warmth * WARM_SHIFT
        var y = luma(cr, cg, cb)
        cr = y + (cr - y) * saturation
        cg = y + (cg - y) * saturation
        cb = y + (cb - y) * saturation
        if (mono > 0.0) {
            y = luma(cr, cg, cb)
            cr += (y - cr) * mono
            cg += (y - cg) * mono
            cb += (y - cb) * mono
        }
        if (sepia > 0.0) {
            y = luma(cr, cg, cb)
            cr += (y * SEPIA_RED - cr) * sepia
            cg += (y * SEPIA_GREEN - cg) * sepia
            cb += (y * SEPIA_BLUE - cb) * sepia
        }
        val l = luma(cr, cg, cb).coerceIn(0.0, 1.0)
        val low = (1.0 - l) * (1.0 - l)
        val high = l * l
        cr += shadows.first * low + highlights.first * high
        cg += shadows.second * low + highlights.second * high
        cb += shadows.third * low + highlights.third * high
        if (fade > 0.0) {
            cr = fade + cr * (1.0 - fade)
            cg = fade + cg * (1.0 - fade)
            cb = fade + cb * (1.0 - fade)
        }
        return Triple(cr.coerceIn(0.0, 1.0), cg.coerceIn(0.0, 1.0), cb.coerceIn(0.0, 1.0))
    }

    companion object {
        val NEUTRAL = LookParams()
        private const val WARM_SHIFT = 0.08
        private const val TINT_SHIFT = 0.06
        private const val SEPIA_RED = 1.07
        private const val SEPIA_GREEN = 0.74
        private const val SEPIA_BLUE = 0.43

        fun luma(r: Double, g: Double, b: Double): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}

/** A named look of the pack. [id] is stable across versions and is what the LUT picker passes around. */
data class FilterLook(val id: String, val name: String, val description: String, val params: LookParams)

object FilterPack {
    /** Points per side of the generated cubes: the usual 17, small enough to generate and store quickly. */
    const val CUBE_SIZE = 17

    val looks: List<FilterLook> = listOf(
        FilterLook("original", "Original", "No change; a neutral reference.", LookParams.NEUTRAL),
        FilterLook(
            "cinematic", "Cinematic", "Cool shadows, warm highlights, a little more contrast.",
            LookParams(contrast = 1.12, saturation = 0.95, shadows = Triple(-0.02, 0.01, 0.05), highlights = Triple(0.05, 0.02, -0.03)),
        ),
        FilterLook(
            "teal-orange", "Teal and orange", "The blockbuster split: teal shadows, orange skin tones.",
            LookParams(contrast = 1.1, saturation = 1.08, shadows = Triple(-0.05, 0.02, 0.06), highlights = Triple(0.08, 0.03, -0.06)),
        ),
        FilterLook("warm-glow", "Warm glow", "Soft golden warmth.", LookParams(exposure = 0.1, warmth = 0.8, saturation = 1.05, highlights = Triple(0.03, 0.01, -0.02))),
        FilterLook("cool-breeze", "Cool breeze", "Fresh, slightly blue.", LookParams(warmth = -0.7, saturation = 0.98, tint = -0.1)),
        FilterLook("faded-film", "Faded film", "Lifted blacks and gentle colour, like an old print.", LookParams(contrast = 0.9, saturation = 0.85, warmth = 0.2, fade = 0.07)),
        FilterLook("vintage", "Vintage", "Warm, washed and slightly sepia.", LookParams(contrast = 0.95, saturation = 0.8, warmth = 0.4, sepia = 0.25, fade = 0.05)),
        FilterLook("golden-hour", "Golden hour", "Low-sun orange with soft contrast.", LookParams(exposure = 0.08, gamma = 1.05, warmth = 1.0, saturation = 1.1, highlights = Triple(0.06, 0.02, -0.04))),
        FilterLook("noir", "Noir", "High-contrast black and white.", LookParams(contrast = 1.35, mono = 1.0, gamma = 0.95)),
        FilterLook("silver", "Silver", "Soft black and white with open shadows.", LookParams(contrast = 1.05, mono = 1.0, fade = 0.03, gamma = 1.05)),
        FilterLook("bleach-bypass", "Bleach bypass", "Desaturated and punchy.", LookParams(contrast = 1.25, saturation = 0.55, gamma = 0.95)),
        FilterLook("vivid", "Vivid", "Rich, saturated colour.", LookParams(contrast = 1.1, saturation = 1.35)),
        FilterLook("muted", "Muted", "Calm, low-saturation colour.", LookParams(contrast = 0.97, saturation = 0.65)),
        FilterLook("moody-blue", "Moody blue", "Dark, cold and heavy.", LookParams(exposure = -0.15, contrast = 1.1, warmth = -0.9, saturation = 0.9, shadows = Triple(-0.02, 0.0, 0.05))),
        FilterLook("sunset-pink", "Sunset pink", "Pink-magenta dusk.", LookParams(exposure = 0.05, warmth = 0.5, tint = 0.6, saturation = 1.1, highlights = Triple(0.04, -0.01, 0.03))),
        FilterLook("matte", "Matte", "Flat, modern grade with raised blacks.", LookParams(contrast = 0.92, saturation = 0.95, fade = 0.09)),
        FilterLook(
            "cross-process", "Cross process", "Green shadows and magenta highlights.",
            LookParams(contrast = 1.18, saturation = 1.1, shadows = Triple(-0.03, 0.05, 0.0), highlights = Triple(0.06, -0.03, 0.05)),
        ),
        FilterLook("forest", "Forest", "Deep greens and earthy mid-tones.", LookParams(contrast = 1.05, warmth = -0.1, tint = -0.5, saturation = 1.1, shadows = Triple(-0.02, 0.03, -0.01))),
        FilterLook("day-for-night", "Day for night", "Dark and blue, as if shot at night.", LookParams(exposure = -0.5, contrast = 1.15, warmth = -1.0, saturation = 0.7, shadows = Triple(-0.02, 0.0, 0.06))),
        FilterLook("pastel", "Pastel", "Light, airy and soft.", LookParams(exposure = 0.15, gamma = 1.1, contrast = 0.88, saturation = 0.8, fade = 0.05)),
    )

    fun find(id: String): FilterLook? = looks.firstOrNull { it.id == id }

    /** The look baked into a `.cube` file of [size] points per side (red varies fastest, as the format wants). */
    fun cube(look: FilterLook, size: Int = CUBE_SIZE): String {
        require(size in CubeLut.MIN_SIZE..CubeLut.MAX_SIZE) { "LUT size must be ${CubeLut.MIN_SIZE}..${CubeLut.MAX_SIZE}" }
        val out = StringBuilder(size * size * size * 26 + 128)
        out.append("TITLE \"").append(look.name).append("\"\n")
        out.append("# Generated by ultimateVE, filter pack: ").append(look.id).append('\n')
        out.append("LUT_3D_SIZE ").append(size).append('\n')
        val max = (size - 1).toDouble()
        for (bi in 0 until size) {
            for (gi in 0 until size) {
                for (ri in 0 until size) {
                    val (r, g, b) = look.params.apply(ri / max, gi / max, bi / max)
                    out.append(format(r)).append(' ').append(format(g)).append(' ').append(format(b)).append('\n')
                }
            }
        }
        return out.toString()
    }

    /** The look as a parsed LUT, the same object the shader's CPU reference samples. */
    fun lut(look: FilterLook, size: Int = CUBE_SIZE): CubeLut = CubeParser.parse(cube(look, size))

    private fun format(v: Double): String = String.format(java.util.Locale.ROOT, "%.6f", v)
}

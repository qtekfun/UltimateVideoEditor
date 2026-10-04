package com.ultimatevideo.uveditor.domain

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Helpers for the HSL qualifier effect ([EffectType.QUALIFIER]): reading a picked colour into a key. The matte maths
 * itself runs natively (`render/qualifier_math.h`); this mirrors only the colour conversion the eyedropper needs.
 */
object Qualifier {
    // Indices into the effect's values; the wire order, see `EffectType.QUALIFIER`.
    const val HUE = 0
    const val HUE_WIDTH = 1
    const val HUE_SOFT = 2
    const val SAT_MIN = 3
    const val SAT_MAX = 4
    const val SAT_SOFT = 5
    const val LUMA_MIN = 6
    const val LUMA_MAX = 7
    const val LUMA_SOFT = 8
    const val INVERT = 9
    const val SHOW_MATTE = 10
    const val HUE_SHIFT = 11
    const val SAT_GAIN = 12
    const val LIGHTNESS = 13

    /** How far (in saturation and luma) around a picked colour the key reaches, and the hue half width it starts with. */
    const val PICK_SAT_REACH = 0.3
    const val PICK_LUMA_REACH = 0.35
    const val PICK_HUE_WIDTH = 0.06

    /** Below this HSL saturation a picked colour is a grey: its hue means nothing, so the key takes every hue. */
    const val GREY_SATURATION = 0.06

    /** HSL of straight RGB in 0..1: hue on the wheel in 0..1 (0 = red), saturation and lightness in 0..1. */
    fun hsl(r: Double, g: Double, b: Double): Triple<Double, Double, Double> {
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val l = 0.5 * (mx + mn)
        if (d <= 0.00001) return Triple(0.0, 0.0, l)
        val s = d / max(1.0 - abs(2.0 * l - 1.0), 0.00001)
        val hue = when (mx) {
            r -> (g - b) / d + if (g < b) 6.0 else 0.0
            g -> (b - r) / d + 2.0
            else -> (r - g) / d + 4.0
        }
        return Triple(hue / 6.0, s, l)
    }

    /** Rec.709 luma of straight RGB, the weights the matte uses. */
    fun luma(r: Double, g: Double, b: Double): Double = 0.2126 * r + 0.7152 * g + 0.0722 * b

    /**
     * Returns [values] with the key set to the picked colour: the hue centre on its hue with a modest width, the
     * saturation and luma ranges around its saturation and luma (clamped to 0..1), softness and the correction
     * untouched. A grey picks every hue and a low saturation band instead.
     */
    fun keyedOn(values: List<Double>, r: Double, g: Double, b: Double): List<Double> {
        require(values.size == EffectType.QUALIFIER.params.size) { "a qualifier takes ${EffectType.QUALIFIER.params.size} values" }
        val (h, s, _) = hsl(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))
        val y = luma(r.coerceIn(0.0, 1.0), g.coerceIn(0.0, 1.0), b.coerceIn(0.0, 1.0))
        val out = values.toMutableList()
        if (s < GREY_SATURATION) {
            out[HUE] = 0.0
            out[HUE_WIDTH] = 0.5
            out[SAT_MIN] = 0.0
            out[SAT_MAX] = (s + PICK_SAT_REACH / 2).coerceIn(0.0, 1.0)
        } else {
            out[HUE] = h
            out[HUE_WIDTH] = PICK_HUE_WIDTH
            out[SAT_MIN] = (s - PICK_SAT_REACH).coerceIn(0.0, 1.0)
            out[SAT_MAX] = (s + PICK_SAT_REACH).coerceIn(0.0, 1.0)
        }
        out[LUMA_MIN] = (y - PICK_LUMA_REACH).coerceIn(0.0, 1.0)
        out[LUMA_MAX] = (y + PICK_LUMA_REACH).coerceIn(0.0, 1.0)
        return out
    }
}

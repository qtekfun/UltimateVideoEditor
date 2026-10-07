package com.ultimatevideo.uveditor.domain

import kotlin.math.pow
import kotlin.math.sin

/**
 * How a clip's own fade handles ramp the sound. The wire value is [code] (2 bits of the audio snapshot's clip block);
 * an unknown code reads as [EQUAL_POWER].
 */
enum class FadeShape(val code: Int, val label: String, val id: String) {
    /** sin(p * pi / 2): keeps the power constant against a fade of the same shape on a neighbour. The default. */
    EQUAL_POWER(0, "Equal power", "equal-power"),

    /** A straight ramp of the amplitude. */
    LINEAR(1, "Linear", "linear"),

    /** From -60 dB to 0 dB in equal dB steps: sounds even to the ear, slow to start. */
    LOGARITHMIC(2, "Logarithmic", "logarithmic"),
    ;

    companion object {
        fun fromCode(code: Int): FadeShape = entries.firstOrNull { it.code == code } ?: EQUAL_POWER

        /** The shape stored in a project file under [id]; anything unknown (a newer build's shape) reads as the default. */
        fun fromId(id: String?): FadeShape = entries.firstOrNull { it.id == id } ?: EQUAL_POWER
    }
}

/**
 * The gain a clip's own fade handles apply at a sample. This mirrors `core/fade_math.h`, which the audio mixer (preview,
 * export and offline renders) uses; both are checked against the same values (`FadeCurveTest`, `audio_host_tests.cpp`),
 * so change them together. Steps are integers (samples or frames); the progress of step k of d is (k + 0.5) / d.
 */
object FadeCurve {
    private const val LOG_FLOOR = 0.001

    private fun progress(k: Long, d: Long): Double = if (d <= 0L || k >= d) 1.0 else (k.coerceAtLeast(0L) + 0.5) / d

    /** The gain of a fade-in at progress [p] in 0..1. */
    fun shapeGain(shape: FadeShape, p: Double): Double = when {
        p <= 0.0 -> 0.0
        p >= 1.0 -> 1.0
        else -> when (shape) {
            FadeShape.LINEAR -> p
            FadeShape.LOGARITHMIC -> (10.0.pow(-3.0 * (1.0 - p)) - LOG_FLOOR) / (1.0 - LOG_FLOOR)
            FadeShape.EQUAL_POWER -> sin(p * Math.PI / 2.0)
        }
    }

    fun fadeInGain(shape: FadeShape, k: Long, d: Long): Double = if (d <= 0L || k >= d) 1.0 else shapeGain(shape, progress(k, d))

    /** [k] counts from the start of the fade-out; past the end it is silent. */
    fun fadeOutGain(shape: FadeShape, k: Long, d: Long): Double = when {
        d <= 0L -> 1.0
        k >= d -> 0.0
        else -> shapeGain(shape, 1.0 - progress(k, d))
    }

    /**
     * The factor the two fade handles of a clip [length] steps long apply to its step [s] (0 = the first): [fadeIn] and
     * [fadeOut] are their lengths in steps, 0 for none. Where they overlap the gains multiply.
     */
    fun gainAt(shape: FadeShape, fadeIn: Long, fadeOut: Long, length: Long, s: Long): Double {
        var gain = 1.0
        if (fadeIn > 0 && s < fadeIn) gain *= fadeInGain(shape, s, fadeIn)
        if (fadeOut > 0 && s >= length - fadeOut) gain *= fadeOutGain(shape, s - (length - fadeOut), fadeOut)
        return gain
    }
}

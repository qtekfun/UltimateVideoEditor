package com.ultimatevideo.uveditor.domain

import kotlin.math.roundToInt

/**
 * How much of the picture the stabiliser gives up to hide the moving frame edges (SPECS.md 9.6).
 * [code] is the wire value of the native `CropLevel`.
 */
enum class StabCrop(val code: Int, val label: String, val id: String) {
    /** Zooms in enough that no frame edge is ever visible. */
    TIGHT(0, "Tight", "tight"),

    /** Half of the tight zoom; a little edge fill may show on the shakiest frames. */
    MEDIUM(1, "Medium", "medium"),

    /** No zoom: the picture keeps its whole frame and the border pixels are repeated where it moves away. */
    FULL(2, "Full", "full"),
    ;

    companion object {
        fun fromId(id: String?): StabCrop? = entries.firstOrNull { it.id == id }
    }
}

/**
 * Camera-shake correction of a video clip. [strength] (0..1) is how steady the result is: the clip's
 * camera path is low-pass filtered over a window that grows with it (about 0.1 s at 0, 2.5 s at 1); 0 leaves
 * the picture as it is. The analysis itself (frame-to-frame motion, done once per file and cached) does not
 * depend on either value, so changing them is instant.
 */
data class Stabilise(
    val strength: Double = DEFAULT_STRENGTH,
    val crop: StabCrop = StabCrop.MEDIUM,
) {
    fun problem(): String? = when {
        !(strength.isFinite() && strength in 0.0..1.0) -> "stabilise strength must be between 0 and 1"
        else -> null
    }

    companion object {
        const val DEFAULT_STRENGTH = 0.3
    }
}

/**
 * The 24-bit key under which the native side registers the correction table for a clip's stabiliser settings
 * (it travels as a float, so it stays below 2^24, and 0 is reserved). It depends only on the asset and the
 * settings, never on the clip, so every clip of the file with the same settings shares one table; the
 * preview and the exporter derive it the same way (through [Timeline.renderClips]) and look the table up by
 * the source frame they are drawing.
 */
object StabKey {
    fun of(assetId: String, stabilise: Stabilise): Int {
        var hash = 0x811C9DC5.toInt()
        for (ch in "$assetId|${(stabilise.strength * 100).roundToInt()}|${stabilise.crop.code}") {
            hash = (hash xor ch.code) * 0x01000193
        }
        val key = (hash xor (hash ushr 24)) and 0xFFFFFF
        return if (key == 0) 1 else key
    }
}

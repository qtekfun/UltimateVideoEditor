package com.ultimatevideo.uveditor.engine.fx

import com.ultimatevideo.uveditor.domain.ClipFx
import com.ultimatevideo.uveditor.domain.ClipMask

/**
 * Wire form of per-layer effects, blend mode and mask for the native renderer (`core/layer_fx.h`
 * parses it; change both together). One blob per layer, concatenated, in the order of the layers
 * handed to the preview scene or the exporter:
 *
 *     blend, maskShape (0 none), maskCx, maskCy, maskW, maskH, maskFeather, maskInvert, effectCount,
 *     then per effect: type, valueCount, values...
 */
object FxWire {
    const val HEADER_DOUBLES = 9

    /** Empty when every layer is plain, which the native side reads as "no effects anywhere". */
    fun encode(layers: List<ClipFx>): DoubleArray {
        if (layers.all { it.isNeutral }) return DoubleArray(0)
        val out = ArrayList<Double>(layers.size * HEADER_DOUBLES)
        for (fx in layers) encodeLayer(fx, out)
        return out.toDoubleArray()
    }

    private fun encodeLayer(fx: ClipFx, out: MutableList<Double>) {
        val mask: ClipMask? = fx.mask
        out += fx.blendMode.code.toDouble()
        out += (mask?.shape?.code ?: 0).toDouble()
        out += mask?.centerX ?: 0.0
        out += mask?.centerY ?: 0.0
        out += mask?.width ?: 1.0
        out += mask?.height ?: 1.0
        out += mask?.feather ?: 0.0
        out += if (mask?.invert == true) 1.0 else 0.0
        out += fx.effects.size.toDouble()
        for (effect in fx.effects) {
            out += effect.type.code.toDouble()
            out += effect.values.size.toDouble()
            out += effect.values
        }
    }
}

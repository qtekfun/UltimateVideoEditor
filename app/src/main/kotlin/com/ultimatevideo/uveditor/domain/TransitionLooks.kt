package com.ultimatevideo.uveditor.domain

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** A transition as one of its two clips sees it: its [type] and [direction] and its length in project frames. */
data class TransitionLook(val type: TransitionType, val direction: TransitionDirection, val frames: Long)

/**
 * What a transition does to one picture at one frame, on top of the clip's own pose: an offset in canvas
 * pixels, a scale and rotation, an opacity factor, and optionally effects and a mask for the compositor.
 * [NONE] changes nothing.
 */
data class TransitionMod(
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val scale: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val opacity: Double = 1.0,
    val effects: List<Effect> = emptyList(),
    val mask: ClipMask? = null,
) {
    val isNone: Boolean get() = this == NONE

    /** Applies this and then [other] (a clip that is both the incoming and the outgoing one of overlapping transitions). */
    fun then(other: TransitionMod): TransitionMod = when {
        isNone -> other
        other.isNone -> this
        else -> TransitionMod(
            offsetX + other.offsetX,
            offsetY + other.offsetY,
            scale * other.scale,
            rotationDegrees + other.rotationDegrees,
            opacity * other.opacity,
            effects + other.effects,
            mask ?: other.mask,
        )
    }

    companion object {
        val NONE = TransitionMod()
    }
}

/**
 * The picture side of the transition pack (SPECS.md 9.19). Pure functions of the transition's look, the frame
 * within it and the canvas size, so the preview and the exporter, which both call them with the same project
 * frames, draw the same picture. Everything is classical geometry and simple effects built from the existing
 * compositor (pose, mask, effect chain); there is no new shader and nothing random: the glitch uses a fixed hash.
 *
 * Progress through a transition of `d` frames is `(k + 0.5) / d` like the crossfade, eased with a smoothstep for
 * the geometric looks. The incoming picture is always above the outgoing one.
 */
object TransitionLooks {

    /** What [look] does to a clip at frame [k] of the transition (0 is its first frame); [incoming] says which side this clip is. */
    fun modAt(look: TransitionLook, incoming: Boolean, k: Long, canvasWidth: Int, canvasHeight: Int, projectFrame: Long): TransitionMod {
        if (look.frames <= 0L) return TransitionMod.NONE
        val p = CrossfadeCurve.progress(k, look.frames)
        val e = smooth(p)
        val w = canvasWidth.toDouble()
        val h = canvasHeight.toDouble()
        val d = look.direction
        val bell = sin(PI * p)
        return when (look.type) {
            TransitionType.CROSSFADE -> TransitionMod.NONE
            TransitionType.SLIDE ->
                if (incoming) TransitionMod(offsetX = -d.dx * (1 - e) * w, offsetY = -d.dy * (1 - e) * h) else TransitionMod.NONE
            TransitionType.PUSH ->
                if (incoming) {
                    TransitionMod(offsetX = -d.dx * (1 - e) * w, offsetY = -d.dy * (1 - e) * h)
                } else {
                    TransitionMod(offsetX = d.dx * e * w, offsetY = d.dy * e * h)
                }
            TransitionType.ZOOM ->
                if (incoming) TransitionMod(scale = 0.8 + 0.2 * e, opacity = e) else TransitionMod(scale = 1.0 + 0.5 * e)
            TransitionType.SPIN -> {
                val sign = if (d.dx + d.dy > 0) 1.0 else -1.0
                if (incoming) {
                    TransitionMod(scale = 0.3 + 0.7 * e, rotationDegrees = -sign * (1 - e) * 180.0, opacity = e)
                } else {
                    TransitionMod(scale = 1.0 + 0.4 * e, rotationDegrees = sign * e * 45.0)
                }
            }
            TransitionType.GLITCH -> {
                val amplitude = bell * 0.05 * w
                val shown = hash01((projectFrame shr 1) * 7L + 1L) < p
                TransitionMod(
                    offsetX = noise(projectFrame * 5L + 2L) * amplitude,
                    offsetY = noise(projectFrame * 11L + 3L) * amplitude * 0.3,
                    opacity = if (incoming && !shown) 0.0 else 1.0,
                    effects = listOf(
                        Effect("transition-contrast", EffectType.CONTRAST, listOf(1.0 + 0.5 * bell)),
                        Effect("transition-saturation", EffectType.SATURATION, listOf(1.0 + 0.6 * bell)),
                    ),
                )
            }
            TransitionType.WIPE -> if (incoming) wipe(d, e) else TransitionMod.NONE
            TransitionType.WHIP_PAN -> {
                val q = smoother(p)
                val blur = Effect("transition-blur", EffectType.BLUR, listOf((0.9 * sin(PI * p)).coerceIn(0.0, 1.0)))
                if (incoming) {
                    TransitionMod(offsetX = -d.dx * (1 - q) * w, offsetY = -d.dy * (1 - q) * h, effects = listOf(blur))
                } else {
                    TransitionMod(offsetX = d.dx * q * w, offsetY = d.dy * q * h, effects = listOf(blur))
                }
            }
            TransitionType.LIGHT_LEAK -> {
                val glow = bell * bell
                TransitionMod(
                    effects = listOf(
                        Effect("transition-exposure", EffectType.EXPOSURE, listOf((1.5 * glow).coerceIn(0.0, 3.0))),
                        Effect("transition-warmth", EffectType.TEMPERATURE, listOf((0.7 * glow).coerceIn(0.0, 1.0))),
                    ),
                )
            }
        }
    }

    /** The incoming picture revealed by a soft-edged rectangle that grows from the edge it arrives from. */
    private fun wipe(d: TransitionDirection, e: Double): TransitionMod {
        if (e < MIN_WIPE) return TransitionMod(opacity = 0.0)
        val size = (e * 1.06).coerceIn(ClipMask.MIN_SIZE, 1.1)
        val centre = (0.5 - size / 2.0 + WIPE_FEATHER).coerceIn(-0.5, 0.5)
        val mask = when {
            d.dx < 0 -> ClipMask(MaskShape.RECTANGLE, centerX = centre, centerY = 0.0, width = size, height = 1.1, feather = WIPE_FEATHER)
            d.dx > 0 -> ClipMask(MaskShape.RECTANGLE, centerX = -centre, centerY = 0.0, width = size, height = 1.1, feather = WIPE_FEATHER)
            d.dy < 0 -> ClipMask(MaskShape.RECTANGLE, centerX = 0.0, centerY = centre, width = 1.1, height = size, feather = WIPE_FEATHER)
            else -> ClipMask(MaskShape.RECTANGLE, centerX = 0.0, centerY = -centre, width = 1.1, height = size, feather = WIPE_FEATHER)
        }
        return TransitionMod(mask = mask)
    }

    /** Smoothstep. */
    fun smooth(p: Double): Double {
        val x = p.coerceIn(0.0, 1.0)
        return x * x * (3.0 - 2.0 * x)
    }

    /** Smootherstep: a longer, harder ease for the whip pan. */
    fun smoother(p: Double): Double {
        val x = p.coerceIn(0.0, 1.0)
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0)
    }

    /** A fixed hash of [n] in 0..1: the same everywhere, so a glitch looks the same in preview and export. */
    fun hash01(n: Long): Double {
        var x = n * -7046029254386353131L
        x = x xor (x ushr 32)
        x *= -4658895280553007687L
        x = x xor (x ushr 29)
        return ((x ushr 11) and 0x1FFFFFFFFFFFFFL).toDouble() / 9.007199254740992E15
    }

    private fun noise(n: Long): Double = hash01(n) * 2.0 - 1.0

    private const val MIN_WIPE = 0.01
    private const val WIPE_FEATHER = 0.03
}

/** [fx] with the effects and the mask a transition adds; the clip's own mask wins, and extra effects past the limit are dropped. */
fun ClipFx.withTransition(mod: TransitionMod): ClipFx {
    if (mod.effects.isEmpty() && mod.mask == null) return this
    val room = max(0, ClipFx.MAX_EFFECTS - effects.size)
    return copy(effects = effects + mod.effects.take(room), mask = mask ?: mod.mask)
}

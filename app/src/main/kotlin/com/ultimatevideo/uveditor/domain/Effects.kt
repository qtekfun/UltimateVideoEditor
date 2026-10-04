package com.ultimatevideo.uveditor.domain

/** The largest LUT library key, so that it is exact as a float on the native side. */
const val MAX_LUT_KEY = 16_777_215.0

/** One tunable value of an [EffectType]. [min]/[max] bound the slider and validation. */
data class EffectParam(val name: String, val min: Double, val max: Double, val default: Double)

/**
 * Shader effects a clip can carry, applied in list order to the clip's own pixels before its
 * transform, mask and blend. [code] and the parameter order are the wire format to the native
 * renderer (`render/effect_math.h` and the effect shader mirror them); change all together.
 *
 * Colour effects work on display-referred RGB (what the preview shows, after HLG conversion).
 * Blur radius is a fraction of 0.02 x the layer height, so it looks the same at any resolution.
 */
enum class EffectType(val code: Int, val label: String, val params: List<EffectParam>) {
    BRIGHTNESS(1, "Brightness", listOf(EffectParam("Amount", -1.0, 1.0, 0.0))),
    CONTRAST(2, "Contrast", listOf(EffectParam("Amount", 0.0, 2.0, 1.0))),
    SATURATION(3, "Saturation", listOf(EffectParam("Amount", 0.0, 2.0, 1.0))),
    EXPOSURE(4, "Exposure", listOf(EffectParam("Stops", -3.0, 3.0, 0.0))),
    TEMPERATURE(5, "Temperature", listOf(EffectParam("Warmth", -1.0, 1.0, 0.0))),
    TINT(6, "Tint", listOf(EffectParam("Amount", -1.0, 1.0, 0.0))),
    BLUR(7, "Blur", listOf(EffectParam("Radius", 0.0, 1.0, 0.25))),
    SHARPEN(8, "Sharpen", listOf(EffectParam("Amount", 0.0, 2.0, 1.0))),
    VIGNETTE(9, "Vignette", listOf(EffectParam("Amount", 0.0, 1.0, 0.5), EffectParam("Softness", 0.0, 1.0, 0.5))),
    GRAYSCALE(10, "Grayscale", listOf(EffectParam("Amount", 0.0, 1.0, 1.0))),
    SEPIA(11, "Sepia", listOf(EffectParam("Amount", 0.0, 1.0, 1.0))),

    /** Makes the pixels near a key colour transparent; the key colour is red, green, blue in 0..1. */
    CHROMA_KEY(
        12,
        "Chroma key",
        listOf(
            EffectParam("Key red", 0.0, 1.0, 0.0),
            EffectParam("Key green", 0.0, 1.0, 1.0),
            EffectParam("Key blue", 0.0, 1.0, 0.0),
            EffectParam("Similarity", 0.0, 1.0, 0.4),
            EffectParam("Smoothness", 0.0, 1.0, 0.1),
            EffectParam("Spill", 0.0, 1.0, 0.1),
        ),
    ),

    /**
     * A 3D lookup table from the LUT library applied to the clip, blended in by Intensity. The first value
     * is the library key (see data/LutStore), chosen by the LUT picker, never by a slider. It runs on the
     * clip's pixels in the project's working space after the source-to-project colour conversion, i.e.
     * Rec.709 gamma in an SDR project and the HLG signal in an HLG project (LUTs made for other spaces
     * will look off; this is by design, the same assumption editors make for a creative LUT).
     */
    LUT(13, "LUT", listOf(EffectParam("LUT", 1.0, MAX_LUT_KEY, 1.0), EffectParam("Intensity", 0.0, 1.0, 1.0))),

    /**
     * The colour grade: lift / gamma / gain wheels (red, green, blue and master each), offset, contrast
     * about a pivot, saturation, vibrance, temperature and tint, then the tone curves in [Effect.curves].
     * The value order is the wire format (`render/grade_math.h` documents each index and the maths).
     */
    COLOR_GRADE(
        14,
        "Colour grade",
        listOf(
            EffectParam("Lift red", -1.0, 1.0, 0.0),
            EffectParam("Lift green", -1.0, 1.0, 0.0),
            EffectParam("Lift blue", -1.0, 1.0, 0.0),
            EffectParam("Lift master", -1.0, 1.0, 0.0),
            EffectParam("Gamma red", -1.0, 1.0, 0.0),
            EffectParam("Gamma green", -1.0, 1.0, 0.0),
            EffectParam("Gamma blue", -1.0, 1.0, 0.0),
            EffectParam("Gamma master", -1.0, 1.0, 0.0),
            EffectParam("Gain red", -1.0, 1.0, 0.0),
            EffectParam("Gain green", -1.0, 1.0, 0.0),
            EffectParam("Gain blue", -1.0, 1.0, 0.0),
            EffectParam("Gain master", -1.0, 1.0, 0.0),
            EffectParam("Offset red", -0.5, 0.5, 0.0),
            EffectParam("Offset green", -0.5, 0.5, 0.0),
            EffectParam("Offset blue", -0.5, 0.5, 0.0),
            EffectParam("Contrast", 0.0, 2.0, 1.0),
            EffectParam("Pivot", 0.0, 1.0, 0.5),
            EffectParam("Saturation", 0.0, 2.0, 1.0),
            EffectParam("Vibrance", -1.0, 1.0, 0.0),
            EffectParam("Temperature", -1.0, 1.0, 0.0),
            EffectParam("Tint", -1.0, 1.0, 0.0),
        ),
    ),
    ;

    val defaults: List<Double> get() = params.map { it.default }

    companion object {
        fun fromCode(code: Int): EffectType? = entries.firstOrNull { it.code == code }
    }
}

/**
 * A configured effect. [values] follows `type.params`. Values are static for the whole clip. [curves] only
 * belongs to [EffectType.COLOR_GRADE] (null means identity curves).
 */
data class Effect(
    val id: String,
    val type: EffectType,
    val values: List<Double> = type.defaults,
    val curves: GradeCurves? = null,
) {
    /** Reason this effect cannot be rendered, or null when it is valid. */
    fun problem(): String? {
        if (id.isBlank()) return "effect id must not be blank"
        if (curves != null) {
            if (type != EffectType.COLOR_GRADE) return "only a colour grade has curves"
            curves.problem()?.let { return it }
        }
        if (values.size != type.params.size) return "${type.label} takes ${type.params.size} values, got ${values.size}"
        for ((index, param) in type.params.withIndex()) {
            val v = values[index]
            if (!(v.isFinite() && v in param.min..param.max)) {
                return "${type.label} ${param.name.lowercase()} must be between ${param.min} and ${param.max}"
            }
        }
        return null
    }
}

/** How a layer combines with what is already below it. Order is the wire format. */
enum class BlendMode(val code: Int, val label: String) {
    NORMAL(0, "Normal"),
    ADD(1, "Add"),
    MULTIPLY(2, "Multiply"),
    SCREEN(3, "Screen"),
    OVERLAY(4, "Overlay"),
    ;

    companion object {
        fun fromCode(code: Int): BlendMode? = entries.firstOrNull { it.code == code }
    }
}

enum class MaskShape(val code: Int) {
    RECTANGLE(1),
    ELLIPSE(2),
}

/**
 * Limits what part of a layer shows. Everything is a fraction of the layer's own box, which spans
 * -0.5..0.5 on both axes (+x right, +y down), so the mask follows the layer's transform:
 * - [centerX]/[centerY]: the shape's centre in that box.
 * - [width]/[height]: the shape's full size, 1 = the whole layer.
 * - [feather]: half-width of the soft edge, as a fraction of the box; 0 is a hard edge.
 * - [invert]: shows the outside instead of the inside.
 */
data class ClipMask(
    val shape: MaskShape = MaskShape.RECTANGLE,
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val width: Double = 0.6,
    val height: Double = 0.6,
    val feather: Double = 0.02,
    val invert: Boolean = false,
) {
    fun problem(): String? = when {
        !(centerX.isFinite() && centerX in -0.5..0.5 && centerY.isFinite() && centerY in -0.5..0.5) ->
            "mask centre must stay inside the layer"
        !(width.isFinite() && width in MIN_SIZE..MAX_SIZE && height.isFinite() && height in MIN_SIZE..MAX_SIZE) ->
            "mask size must be between $MIN_SIZE and $MAX_SIZE"
        !(feather.isFinite() && feather in 0.0..MAX_FEATHER) -> "mask feather must be between 0 and $MAX_FEATHER"
        else -> null
    }

    companion object {
        const val MIN_SIZE = 0.02
        const val MAX_SIZE = 2.0
        const val MAX_FEATHER = 0.5
    }
}

/** Everything that changes how a clip's pixels look beyond its pose: effects, blend mode and mask. */
data class ClipFx(
    val effects: List<Effect> = emptyList(),
    val blendMode: BlendMode = BlendMode.NORMAL,
    val mask: ClipMask? = null,
) {
    /** True when rendering the clip needs nothing beyond the plain draw. */
    val isNeutral: Boolean get() = effects.isEmpty() && blendMode == BlendMode.NORMAL && mask == null

    fun effect(id: String): Effect? = effects.firstOrNull { it.id == id }

    fun problem(): String? {
        val seen = HashSet<String>()
        for (effect in effects) {
            effect.problem()?.let { return it }
            if (!seen.add(effect.id)) return "duplicate effect id ${effect.id}"
        }
        if (effects.size > MAX_EFFECTS) return "a clip can have at most $MAX_EFFECTS effects"
        return mask?.problem()
    }

    companion object {
        val NONE = ClipFx()
        const val MAX_EFFECTS = 8
    }
}

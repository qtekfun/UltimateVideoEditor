package com.qtekfun.ultimatevideoeditor.domain

/**
 * Where a layer sits inside its title. [offsetX] and [offsetY] are fractions of the canvas width and
 * height from the canvas centre (+y down), so a title looks the same at any resolution; [scale]
 * multiplies the layer's size, [rotationDegrees] turns it clockwise around its own centre and
 * [opacity] fades it. The whole title is then placed by its clip's transform.
 */
data class LayerPlacement(
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val scale: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val opacity: Double = 1.0,
) {
    fun problem(): String? = when {
        !(offsetX.isFinite() && offsetY.isFinite() && offsetX in -MAX_OFFSET..MAX_OFFSET && offsetY in -MAX_OFFSET..MAX_OFFSET) ->
            "layer offset must stay within $MAX_OFFSET canvas sizes of the centre"
        !(scale.isFinite() && scale in MIN_SCALE..MAX_SCALE) -> "layer scale must be between $MIN_SCALE and $MAX_SCALE"
        !rotationDegrees.isFinite() -> "layer rotation must be finite"
        !(opacity.isFinite() && opacity in 0.0..1.0) -> "layer opacity must be between 0 and 1"
        else -> null
    }

    companion object {
        const val MAX_OFFSET = 2.0
        const val MIN_SCALE = 0.05
        const val MAX_SCALE = 20.0
    }
}

/** An outline: [widthFraction] is a fraction of the canvas height. */
data class LayerStroke(val colorArgb: Int, val widthFraction: Double) {
    fun problem(): String? =
        if (widthFraction.isFinite() && widthFraction in MIN_WIDTH..MAX_WIDTH) null else "stroke width must be between $MIN_WIDTH and $MAX_WIDTH of the canvas height"

    companion object {
        const val MIN_WIDTH = 0.0005
        const val MAX_WIDTH = 0.05
        const val DEFAULT_WIDTH = 0.004
    }
}

/** A soft shadow; the offsets and blur radius are fractions of the canvas height. */
data class LayerShadow(val colorArgb: Int, val dxFraction: Double, val dyFraction: Double, val blurFraction: Double) {
    fun problem(): String? = when {
        !(dxFraction.isFinite() && dyFraction.isFinite() && dxFraction in -MAX_REACH..MAX_REACH && dyFraction in -MAX_REACH..MAX_REACH) ->
            "shadow offset must be within $MAX_REACH of the canvas height"
        !(blurFraction.isFinite() && blurFraction in 0.0..MAX_REACH) -> "shadow blur must be between 0 and $MAX_REACH of the canvas height"
        else -> null
    }

    companion object {
        const val MAX_REACH = 0.1
        val DEFAULT = LayerShadow(0xAA000000.toInt(), 0.004, 0.006, 0.008)
    }
}

/** A filled box drawn behind a text layer; the padding and corner radius are fractions of the canvas height. */
data class LayerBox(val colorArgb: Int, val paddingFraction: Double, val cornerRadiusFraction: Double) {
    fun problem(): String? = when {
        !(paddingFraction.isFinite() && paddingFraction in 0.0..MAX_PADDING) -> "box padding must be between 0 and $MAX_PADDING of the canvas height"
        !(cornerRadiusFraction.isFinite() && cornerRadiusFraction in 0.0..MAX_PADDING) -> "box corner radius must be between 0 and $MAX_PADDING of the canvas height"
        else -> null
    }

    companion object {
        const val MAX_PADDING = 0.25
        val DEFAULT = LayerBox(0xCC000000.toInt(), 0.012, 0.008)
    }
}

enum class ShapeKind { RECT, ROUNDED_RECT, ELLIPSE, LINE }

/**
 * One layer of a multilayer title. A title draws its layers in list order, so the first layer is at the
 * bottom and the last on top (the title editor lists them top first). Every layer has the same
 * [placement]; sizes are fractions of the canvas so a title is resolution independent.
 */
sealed interface TitleLayer {
    val placement: LayerPlacement

    fun withPlacement(placement: LayerPlacement): TitleLayer

    /** Reason this layer cannot be drawn, or null when it can. */
    fun problem(): String?

    /** A short name for lists ("Text: Hello", "Rounded rectangle", "Sticker"). */
    val label: String
}

/** A block of text with its own font, style, border, shadow and background box. */
data class TextLayer(
    val text: String,
    /** Id of an imported font (see `FontRegistry`), or null for the system default. A missing font falls back to the default. */
    val fontId: String? = null,
    val sizeFraction: Double = TitleContent.DEFAULT_SIZE_FRACTION,
    val colorArgb: Int = TitleContent.DEFAULT_COLOR_ARGB,
    val alignment: TitleAlignment = TitleAlignment.CENTER,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Extra space between letters in ems (0 is normal). */
    val letterSpacing: Double = 0.0,
    /** Line height as a multiple of the font's natural one. */
    val lineHeight: Double = 1.0,
    val border: LayerStroke? = null,
    val shadow: LayerShadow? = null,
    val box: LayerBox? = null,
    override val placement: LayerPlacement = LayerPlacement(),
) : TitleLayer {
    override fun withPlacement(placement: LayerPlacement): TextLayer = copy(placement = placement)

    override val label: String get() = "Text: " + text.lineSequence().firstOrNull().orEmpty().take(LABEL_CHARS).ifBlank { "(empty)" }

    override fun problem(): String? = when {
        !(sizeFraction.isFinite() && sizeFraction in TitleContent.MIN_SIZE_FRACTION..TitleContent.MAX_SIZE_FRACTION) ->
            "text size must be between ${TitleContent.MIN_SIZE_FRACTION} and ${TitleContent.MAX_SIZE_FRACTION} of the canvas height"
        !(letterSpacing.isFinite() && letterSpacing in MIN_SPACING..MAX_SPACING) -> "letter spacing must be between $MIN_SPACING and $MAX_SPACING"
        !(lineHeight.isFinite() && lineHeight in MIN_LINE_HEIGHT..MAX_LINE_HEIGHT) -> "line height must be between $MIN_LINE_HEIGHT and $MAX_LINE_HEIGHT"
        text.length > MAX_CHARS -> "text is longer than $MAX_CHARS characters"
        else -> border?.problem() ?: shadow?.problem() ?: box?.problem() ?: placement.problem()
    }

    companion object {
        const val MIN_SPACING = -0.2
        const val MAX_SPACING = 1.0
        const val MIN_LINE_HEIGHT = 0.7
        const val MAX_LINE_HEIGHT = 3.0
        const val MAX_CHARS = 2000
        private const val LABEL_CHARS = 24
    }
}

/**
 * A shape. [widthFraction] is a fraction of the canvas width and [heightFraction] of its height; a
 * [ShapeKind.LINE] is a horizontal line of that width whose thickness is [heightFraction] of the height.
 * [cornerRadiusFraction] rounds a [ShapeKind.ROUNDED_RECT] (a fraction of the shorter side, up to a pill).
 */
data class ShapeLayer(
    val kind: ShapeKind = ShapeKind.ROUNDED_RECT,
    val widthFraction: Double = 0.5,
    val heightFraction: Double = 0.1,
    val fillArgb: Int = DEFAULT_FILL,
    val stroke: LayerStroke? = null,
    val shadow: LayerShadow? = null,
    val cornerRadiusFraction: Double = 0.25,
    override val placement: LayerPlacement = LayerPlacement(),
) : TitleLayer {
    override fun withPlacement(placement: LayerPlacement): ShapeLayer = copy(placement = placement)

    override val label: String
        get() = when (kind) {
            ShapeKind.RECT -> "Rectangle"
            ShapeKind.ROUNDED_RECT -> "Rounded rectangle"
            ShapeKind.ELLIPSE -> "Ellipse"
            ShapeKind.LINE -> "Line"
        }

    override fun problem(): String? = when {
        !(widthFraction.isFinite() && widthFraction in MIN_SIDE..MAX_SIDE && heightFraction.isFinite() && heightFraction in MIN_SIDE..MAX_SIDE) ->
            "a shape side must be between $MIN_SIDE and $MAX_SIDE of the canvas"
        !(cornerRadiusFraction.isFinite() && cornerRadiusFraction in 0.0..0.5) -> "corner radius must be between 0 and 0.5"
        else -> stroke?.problem() ?: shadow?.problem() ?: placement.problem()
    }

    companion object {
        const val MIN_SIDE = 0.002
        const val MAX_SIDE = 2.0
        const val DEFAULT_FILL = 0xFF1E88E5.toInt()
    }
}

/**
 * A picture: a photo of the project's media library ([kind] is [StillKind.PHOTO], [id] the asset id) or
 * a built-in sticker ([StillKind.STICKER], [id] its id). [sizeFraction] is the picture's longer side as a
 * fraction of the canvas' shorter side. [resolvedUri] is where a renderer finds the photo's file; it is
 * filled in at render time from the media library and never stored in a project.
 */
data class ImageLayer(
    val kind: StillKind,
    val id: String,
    val sizeFraction: Double = 0.3,
    val shadow: LayerShadow? = null,
    override val placement: LayerPlacement = LayerPlacement(),
    val resolvedUri: String? = null,
) : TitleLayer {
    override fun withPlacement(placement: LayerPlacement): ImageLayer = copy(placement = placement)

    override val label: String get() = if (kind == StillKind.PHOTO) "Photo" else "Sticker"

    override fun problem(): String? = when {
        id.isBlank() -> "an image layer needs a picture"
        !(sizeFraction.isFinite() && sizeFraction in MIN_SIZE..MAX_SIZE) -> "picture size must be between $MIN_SIZE and $MAX_SIZE of the canvas"
        else -> shadow?.problem() ?: placement.problem()
    }

    companion object {
        const val MIN_SIZE = 0.01
        const val MAX_SIZE = 2.0
    }
}

/** Limits and helpers for multilayer titles. */
object TitleLayers {
    const val MAX_LAYERS = 16

    /** The layers of [content] in painter's order (bottom first); a plain title is one text layer. */
    fun of(content: TitleContent): List<TitleLayer> = if (content.layers.isNotEmpty()) content.layers else listOf(content.asTextLayer())

    /** Ids of the imported fonts [content] uses. */
    fun fontIds(content: TitleContent): Set<String> = content.layers.filterIsInstance<TextLayer>().mapNotNullTo(LinkedHashSet()) { it.fontId }

    /** [content] with the photo layers pointed at the files [uriOf] finds for their asset ids (null leaves them unresolved). */
    fun resolved(content: TitleContent, uriOf: (String) -> String?): TitleContent {
        if (content.layers.none { it is ImageLayer && it.kind == StillKind.PHOTO }) return content
        return content.copy(
            layers = content.layers.map { layer ->
                if (layer is ImageLayer && layer.kind == StillKind.PHOTO) layer.copy(resolvedUri = uriOf(layer.id)) else layer
            },
        )
    }
}

/** The single text layer a plain (non-layered) title is drawn as. */
fun TitleContent.asTextLayer(): TextLayer = TextLayer(
    text = text,
    sizeFraction = sizeFraction,
    colorArgb = colorArgb,
    alignment = alignment,
    bold = bold,
    border = if (outline) LayerStroke(0xFF000000.toInt(), TitleLayerEdit.OUTLINE_WIDTH) else null,
)

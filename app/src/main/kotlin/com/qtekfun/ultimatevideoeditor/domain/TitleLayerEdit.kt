package com.qtekfun.ultimatevideoeditor.domain

/**
 * Pure edits of a title's layers. Each returns a new [TitleContent]; the editor commits the result
 * with `EditCommand.SetTitle`, so every change is one undo step. Layers are in painter's order
 * (index 0 at the bottom); the editor lists them top first, so "up" means towards the end of the list.
 * Layered content keeps [TitleContent.text] equal to its first text layer's text.
 */
object TitleLayerEdit {
    /** Outline width of the "outline" flag of a plain title, as a stroke of a text layer. */
    const val OUTLINE_WIDTH = 0.006

    /** A plain title that can become layers without losing anything (no caption animation or word timing). */
    fun canConvert(content: TitleContent): Boolean = !content.isLayered && content.words.isEmpty() && content.animation == TitleAnimation.NONE

    /** [content] as a layered title: a plain one becomes a single text layer with the same look. */
    fun toLayered(content: TitleContent): TitleContent {
        if (content.isLayered) return content
        return synced(content.copy(layers = listOf(content.asTextLayer()), words = emptyList(), animation = TitleAnimation.NONE))
    }

    /** A new layered title holding [layers]. */
    fun of(layers: List<TitleLayer>): TitleContent = synced(TitleContent(text = "", layers = layers))

    /** [layer] added on top. A plain title is converted first. Ignored past [TitleLayers.MAX_LAYERS]. */
    fun add(content: TitleContent, layer: TitleLayer): TitleContent {
        val layered = toLayered(content)
        if (layered.layers.size >= TitleLayers.MAX_LAYERS) return layered
        return synced(layered.copy(layers = layered.layers + layer))
    }

    /** The layer at [index] removed; the last layer cannot be removed (delete the clip instead). */
    fun remove(content: TitleContent, index: Int): TitleContent {
        if (!content.isLayered || index !in content.layers.indices || content.layers.size == 1) return content
        return synced(content.copy(layers = content.layers.filterIndexed { i, _ -> i != index }))
    }

    /** The layer at [index] swapped for [layer]. */
    fun replace(content: TitleContent, index: Int, layer: TitleLayer): TitleContent {
        if (!content.isLayered || index !in content.layers.indices) return content
        return synced(content.copy(layers = content.layers.mapIndexed { i, old -> if (i == index) layer else old }))
    }

    /** The layer at [index] moved [delta] places towards the top (positive) or bottom (negative), clamped. */
    fun move(content: TitleContent, index: Int, delta: Int): TitleContent {
        if (!content.isLayered || index !in content.layers.indices) return content
        val target = (index + delta).coerceIn(0, content.layers.lastIndex)
        if (target == index) return content
        val reordered = content.layers.toMutableList()
        reordered.add(target, reordered.removeAt(index))
        return synced(content.copy(layers = reordered))
    }

    /** A copy of the layer at [index] right above it, nudged so it does not hide the original. */
    fun duplicate(content: TitleContent, index: Int): TitleContent {
        if (!content.isLayered || index !in content.layers.indices || content.layers.size >= TitleLayers.MAX_LAYERS) return content
        val source = content.layers[index]
        val nudged = source.withPlacement(source.placement.copy(offsetX = (source.placement.offsetX + DUPLICATE_NUDGE).coerceAtMost(LayerPlacement.MAX_OFFSET), offsetY = (source.placement.offsetY + DUPLICATE_NUDGE).coerceAtMost(LayerPlacement.MAX_OFFSET)))
        val list = content.layers.toMutableList().apply { add(index + 1, nudged) }
        return synced(content.copy(layers = list))
    }

    /** Fonts a title uses that are not in [available]. */
    fun missingFonts(content: TitleContent, available: Set<String>): Set<String> = TitleLayers.fontIds(content) - available

    /** [content] with [TitleContent.text] mirroring its first text layer. */
    fun synced(content: TitleContent): TitleContent {
        if (!content.isLayered) return content
        val first = content.layers.filterIsInstance<TextLayer>().firstOrNull()?.text.orEmpty()
        return if (first == content.text) content else content.copy(text = first)
    }

    /** A fresh text layer with readable defaults (white, bold, a soft shadow). */
    fun newText(text: String = "Text"): TextLayer = TextLayer(text = text, bold = true, shadow = LayerShadow.DEFAULT)

    /** A fresh shape layer of [kind] with a visible default size. */
    fun newShape(kind: ShapeKind): ShapeLayer = when (kind) {
        ShapeKind.RECT -> ShapeLayer(kind, widthFraction = 0.4, heightFraction = 0.2, cornerRadiusFraction = 0.0)
        ShapeKind.ROUNDED_RECT -> ShapeLayer(kind, widthFraction = 0.5, heightFraction = 0.1, cornerRadiusFraction = 0.35)
        ShapeKind.ELLIPSE -> ShapeLayer(kind, widthFraction = 0.25, heightFraction = 0.25, cornerRadiusFraction = 0.5)
        ShapeKind.LINE -> ShapeLayer(kind, widthFraction = 0.4, heightFraction = 0.006, fillArgb = 0xFFFFFFFF.toInt(), cornerRadiusFraction = 0.0)
    }

    /** A fresh picture layer. */
    fun newImage(kind: StillKind, id: String): ImageLayer = ImageLayer(kind = kind, id = id)

    private const val DUPLICATE_NUDGE = 0.03
}

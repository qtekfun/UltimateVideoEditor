package com.ultimatevideo.uveditor.data.model

import kotlinx.serialization.Serializable

/**
 * One layer of a multilayer title. [type] is `text`, `shape` or `image`; the fields of the other
 * types keep their defaults and are ignored. Offsets are fractions of the canvas width and height
 * from its centre, sizes fractions of the canvas, colours `#AARRGGBB`. Everything is optional so the
 * format can grow and a title with no layers is an ordinary single-text title.
 */
@Serializable
data class TitleLayerDto(
    val type: String,
    val offsetX: Double = 0.0,
    val offsetY: Double = 0.0,
    val scale: Double = 1.0,
    val rotation: Double = 0.0,
    val opacity: Double = 1.0,
    val shadow: LayerShadowDto? = null,
    // text
    val text: String = "",
    /** Id of an imported font; absent for the system font. */
    val font: String? = null,
    val size: Double = 0.08,
    val color: String = "#FFFFFFFF",
    val alignment: String = "center",
    val bold: Boolean = false,
    val italic: Boolean = false,
    val letterSpacing: Double = 0.0,
    val lineHeight: Double = 1.0,
    val border: LayerStrokeDto? = null,
    val box: LayerBoxDto? = null,
    // shape
    /** `rect`, `rounded_rect`, `ellipse` or `line`. */
    val shape: String = "rounded_rect",
    val width: Double = 0.5,
    val height: Double = 0.1,
    val fill: String = "#FF1E88E5",
    val cornerRadius: Double = 0.25,
    val stroke: LayerStrokeDto? = null,
    // image
    /** `photo` (source is a media-library asset id) or `sticker` (a built-in sticker id). */
    val image: String = "photo",
    val source: String = "",
)

@Serializable
data class LayerStrokeDto(val color: String, val width: Double)

@Serializable
data class LayerShadowDto(val color: String, val dx: Double, val dy: Double, val blur: Double)

@Serializable
data class LayerBoxDto(val color: String, val padding: Double, val cornerRadius: Double)

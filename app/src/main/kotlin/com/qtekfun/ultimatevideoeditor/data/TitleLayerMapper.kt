package com.qtekfun.ultimatevideoeditor.data

import com.qtekfun.ultimatevideoeditor.data.model.LayerBoxDto
import com.qtekfun.ultimatevideoeditor.data.model.LayerShadowDto
import com.qtekfun.ultimatevideoeditor.data.model.LayerStrokeDto
import com.qtekfun.ultimatevideoeditor.data.model.TitleLayerDto
import com.qtekfun.ultimatevideoeditor.domain.ImageLayer
import com.qtekfun.ultimatevideoeditor.domain.LayerBox
import com.qtekfun.ultimatevideoeditor.domain.LayerPlacement
import com.qtekfun.ultimatevideoeditor.domain.LayerShadow
import com.qtekfun.ultimatevideoeditor.domain.LayerStroke
import com.qtekfun.ultimatevideoeditor.domain.ShapeKind
import com.qtekfun.ultimatevideoeditor.domain.ShapeLayer
import com.qtekfun.ultimatevideoeditor.domain.StillKind
import com.qtekfun.ultimatevideoeditor.domain.TextLayer
import com.qtekfun.ultimatevideoeditor.domain.TitleAlignment
import com.qtekfun.ultimatevideoeditor.domain.TitleLayer

/** Converts multilayer titles to and from their JSON form. Unknown values are reported as corrupt data, never guessed. */
internal object TitleLayerMapper {

    fun toLayer(owner: String, dto: TitleLayerDto): TitleLayer {
        val placement = LayerPlacement(dto.offsetX, dto.offsetY, dto.scale, dto.rotation, dto.opacity)
        return when (dto.type) {
            "text" -> TextLayer(
                text = dto.text,
                fontId = dto.font,
                sizeFraction = dto.size,
                colorArgb = parseColor(owner, dto.color),
                alignment = when (dto.alignment) {
                    "left" -> TitleAlignment.LEFT
                    "center" -> TitleAlignment.CENTER
                    "right" -> TitleAlignment.RIGHT
                    else -> throw ProjectError.Corrupt("$owner has an unknown text alignment '${dto.alignment}'")
                },
                bold = dto.bold,
                italic = dto.italic,
                letterSpacing = dto.letterSpacing,
                lineHeight = dto.lineHeight,
                border = dto.border?.let { LayerStroke(parseColor(owner, it.color), it.width) },
                shadow = dto.shadow?.let { toShadow(owner, it) },
                box = dto.box?.let { LayerBox(parseColor(owner, it.color), it.padding, it.cornerRadius) },
                placement = placement,
            )
            "shape" -> ShapeLayer(
                kind = when (dto.shape) {
                    "rect" -> ShapeKind.RECT
                    "rounded_rect" -> ShapeKind.ROUNDED_RECT
                    "ellipse" -> ShapeKind.ELLIPSE
                    "line" -> ShapeKind.LINE
                    else -> throw ProjectError.Corrupt("$owner has an unknown shape '${dto.shape}'")
                },
                widthFraction = dto.width,
                heightFraction = dto.height,
                fillArgb = parseColor(owner, dto.fill),
                stroke = dto.stroke?.let { LayerStroke(parseColor(owner, it.color), it.width) },
                shadow = dto.shadow?.let { toShadow(owner, it) },
                cornerRadiusFraction = dto.cornerRadius,
                placement = placement,
            )
            "image" -> ImageLayer(
                kind = when (dto.image) {
                    "photo" -> StillKind.PHOTO
                    "sticker" -> StillKind.STICKER
                    else -> throw ProjectError.Corrupt("$owner has an unknown picture kind '${dto.image}'")
                },
                id = dto.source,
                sizeFraction = dto.size,
                shadow = dto.shadow?.let { toShadow(owner, it) },
                placement = placement,
            )
            else -> throw ProjectError.Corrupt("$owner has an unknown title layer type '${dto.type}'")
        }
    }

    fun toDto(layer: TitleLayer): TitleLayerDto {
        val p = layer.placement
        val base = TitleLayerDto(type = "", offsetX = p.offsetX, offsetY = p.offsetY, scale = p.scale, rotation = p.rotationDegrees, opacity = p.opacity)
        return when (layer) {
            is TextLayer -> base.copy(
                type = "text",
                text = layer.text,
                font = layer.fontId,
                size = layer.sizeFraction,
                color = hex(layer.colorArgb),
                alignment = layer.alignment.name.lowercase(),
                bold = layer.bold,
                italic = layer.italic,
                letterSpacing = layer.letterSpacing,
                lineHeight = layer.lineHeight,
                border = layer.border?.let { LayerStrokeDto(hex(it.colorArgb), it.widthFraction) },
                shadow = layer.shadow?.let(::toShadowDto),
                box = layer.box?.let { LayerBoxDto(hex(it.colorArgb), it.paddingFraction, it.cornerRadiusFraction) },
            )
            is ShapeLayer -> base.copy(
                type = "shape",
                shape = when (layer.kind) {
                    ShapeKind.RECT -> "rect"
                    ShapeKind.ROUNDED_RECT -> "rounded_rect"
                    ShapeKind.ELLIPSE -> "ellipse"
                    ShapeKind.LINE -> "line"
                },
                width = layer.widthFraction,
                height = layer.heightFraction,
                fill = hex(layer.fillArgb),
                cornerRadius = layer.cornerRadiusFraction,
                stroke = layer.stroke?.let { LayerStrokeDto(hex(it.colorArgb), it.widthFraction) },
                shadow = layer.shadow?.let(::toShadowDto),
            )
            is ImageLayer -> base.copy(
                type = "image",
                image = layer.kind.name.lowercase(),
                source = layer.id,
                size = layer.sizeFraction,
                shadow = layer.shadow?.let(::toShadowDto),
            )
        }
    }

    private fun toShadow(owner: String, dto: LayerShadowDto) = LayerShadow(parseColor(owner, dto.color), dto.dx, dto.dy, dto.blur)

    private fun toShadowDto(shadow: LayerShadow) = LayerShadowDto(hex(shadow.colorArgb), shadow.dxFraction, shadow.dyFraction, shadow.blurFraction)

    fun hex(argb: Int): String = "#%08X".format(argb)

    fun parseColor(owner: String, value: String): Int {
        val digits = value.removePrefix("#")
        val parsed = if (digits.length == COLOR_HEX_LENGTH) digits.toLongOrNull(HEX_RADIX) else null
        return parsed?.toInt() ?: throw ProjectError.Corrupt("$owner has a malformed colour '$value'")
    }

    private const val COLOR_HEX_LENGTH = 8
    private const val HEX_RADIX = 16
}

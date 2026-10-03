package com.ultimatevideo.uveditor.domain

/**
 * One keyframe of a template layer. [seconds] counts from the clip's start, or back from its end when
 * [fromEnd]. [dx]/[dy] move the layer by that fraction of the canvas width/height from its resting
 * place, [scale] multiplies its resting scale and [opacity] its resting opacity.
 */
data class TemplateKey(
    val seconds: Double,
    val fromEnd: Boolean = false,
    val dx: Double = 0.0,
    val dy: Double = 0.0,
    val scale: Double = 1.0,
    val opacity: Double = 1.0,
    val interpolation: Interpolation = Interpolation.EASE,
)

/** What a layer is made of: text on the title track, or a solid bar sticker on an overlay lane. */
enum class TemplateLayerKind { TEXT, BAR }

/**
 * A layer's resting pose is its centre ([centerX]/[centerY], fractions of the canvas from its middle,
 * +y down), animated by [keys]. A bar is [barWidth] x [barHeight] of the canvas, in [barSticker]'s colour.
 */
data class TemplateLayer(
    val kind: TemplateLayerKind,
    val centerX: Double = 0.0,
    val centerY: Double = 0.0,
    val opacity: Double = 1.0,
    val keys: List<TemplateKey> = emptyList(),
    val sizeFraction: Double = TitleContent.DEFAULT_SIZE_FRACTION,
    val colorArgb: Int = TitleContent.DEFAULT_COLOR_ARGB,
    val bold: Boolean = false,
    val outline: Boolean = false,
    val barSticker: String = "",
    val barWidth: Double = 0.0,
    val barHeight: Double = 0.0,
)

/** A ready-made animated text, defined entirely as data; see [TextTemplates.all]. */
data class TextTemplate(
    val id: String,
    val name: String,
    val defaultText: String,
    val defaultSeconds: Double,
    val layers: List<TemplateLayer>,
)

/** Persistent ids of the plain bars the templates use; they are drawn by the sticker art. */
object TemplateBars {
    const val DARK = "shape:bar-dark"
    const val ACCENT = "shape:bar-accent"

    /**
     * A sticker is drawn as a square of this fraction of the canvas' shorter side (and at least
     * [MIN_STICKER_SIDE] pixels), so stretching a bar to a canvas fraction needs this to compute its scale.
     * Mirrors `StillFit.stickerSide`; a test keeps the two equal.
     */
    const val STICKER_SIDE_FRACTION = 0.35
    const val MIN_STICKER_SIDE = 64
}

/** The built-in templates. Ids are stored nowhere (a template becomes plain clips), but keep them stable. */
object TextTemplates {

    private const val ACCENT_TEXT = 0xFF1B1F2A.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    val all: List<TextTemplate> = listOf(
        TextTemplate(
            id = "lower-third",
            name = "Lower third",
            defaultText = "Your name",
            defaultSeconds = 4.0,
            layers = listOf(
                TemplateLayer(
                    kind = TemplateLayerKind.BAR,
                    centerX = -0.22,
                    centerY = 0.30,
                    barSticker = TemplateBars.ACCENT,
                    barWidth = 0.52,
                    barHeight = 0.11,
                    keys = listOf(
                        TemplateKey(0.0, dx = -0.7, opacity = 0.0),
                        TemplateKey(0.4, dx = 0.0),
                        TemplateKey(0.4, fromEnd = true, dx = 0.0),
                        TemplateKey(0.0, fromEnd = true, dx = -0.7, opacity = 0.0),
                    ),
                ),
                TemplateLayer(
                    kind = TemplateLayerKind.TEXT,
                    centerX = -0.22,
                    centerY = 0.30,
                    sizeFraction = 0.05,
                    colorArgb = ACCENT_TEXT,
                    bold = true,
                    keys = listOf(
                        TemplateKey(0.0, dx = -0.7, opacity = 0.0),
                        TemplateKey(0.15, dx = -0.7, opacity = 0.0),
                        TemplateKey(0.55, dx = 0.0),
                        TemplateKey(0.4, fromEnd = true, dx = 0.0),
                        TemplateKey(0.0, fromEnd = true, dx = -0.7, opacity = 0.0),
                    ),
                ),
            ),
        ),
        TextTemplate(
            id = "pop-title",
            name = "Pop title",
            defaultText = "Big idea",
            defaultSeconds = 3.0,
            layers = listOf(
                TemplateLayer(
                    kind = TemplateLayerKind.TEXT,
                    sizeFraction = 0.14,
                    bold = true,
                    outline = true,
                    keys = listOf(
                        TemplateKey(0.0, scale = 0.3, opacity = 0.0),
                        TemplateKey(0.18, scale = 1.18),
                        TemplateKey(0.32, scale = 1.0),
                        TemplateKey(0.3, fromEnd = true, scale = 1.0),
                        TemplateKey(0.0, fromEnd = true, scale = 1.0, opacity = 0.0),
                    ),
                ),
            ),
        ),
        TextTemplate(
            id = "slide-headline",
            name = "Slide-in headline",
            defaultText = "Breaking news",
            defaultSeconds = 4.0,
            layers = listOf(
                TemplateLayer(
                    kind = TemplateLayerKind.TEXT,
                    centerY = -0.05,
                    sizeFraction = 0.09,
                    bold = true,
                    outline = true,
                    keys = listOf(
                        TemplateKey(0.0, dx = 0.8, opacity = 0.0),
                        TemplateKey(0.5, dx = 0.0),
                        TemplateKey(0.35, fromEnd = true, dx = 0.0),
                        TemplateKey(0.0, fromEnd = true, dx = -0.8, opacity = 0.0),
                    ),
                ),
            ),
        ),
        TextTemplate(
            id = "subtitle-bar",
            name = "Subtitle bar",
            defaultText = "Say something here",
            defaultSeconds = 4.0,
            layers = listOf(
                TemplateLayer(
                    kind = TemplateLayerKind.BAR,
                    centerY = 0.36,
                    opacity = 0.65,
                    barSticker = TemplateBars.DARK,
                    barWidth = 0.92,
                    barHeight = 0.11,
                    keys = listOf(
                        TemplateKey(0.0, opacity = 0.0),
                        TemplateKey(0.2),
                        TemplateKey(0.2, fromEnd = true),
                        TemplateKey(0.0, fromEnd = true, opacity = 0.0),
                    ),
                ),
                TemplateLayer(
                    kind = TemplateLayerKind.TEXT,
                    centerY = 0.36,
                    sizeFraction = 0.045,
                    colorArgb = WHITE,
                    keys = listOf(
                        TemplateKey(0.0, opacity = 0.0),
                        TemplateKey(0.2),
                        TemplateKey(0.2, fromEnd = true),
                        TemplateKey(0.0, fromEnd = true, opacity = 0.0),
                    ),
                ),
            ),
        ),
    )

    fun find(id: String): TextTemplate? = all.firstOrNull { it.id == id }

    /** Ids a template needs: one per layer plus up to two new lanes (title and overlay). */
    fun idCount(template: TextTemplate): Int = template.layers.size + 2

    /**
     * The clips of [template] for a canvas, as (kind, clip) pairs in layer order, with keyframes in
     * clip frames. [clipIds] must hold one id per layer.
     */
    internal fun clipsFor(
        template: TextTemplate,
        text: String,
        start: FrameIndex,
        durationFrames: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        fps: FrameRate,
        clipIds: List<String>,
    ): List<Pair<TemplateLayerKind, Clip>> = template.layers.mapIndexed { index, layer ->
        val side = maxOf(TemplateBars.MIN_STICKER_SIDE, Math.round(minOf(canvasWidth, canvasHeight) * TemplateBars.STICKER_SIDE_FRACTION).toInt()).toDouble()
        val scaleX = if (layer.kind == TemplateLayerKind.BAR) layer.barWidth * canvasWidth / side else 1.0
        val scaleY = if (layer.kind == TemplateLayerKind.BAR) layer.barHeight * canvasHeight / side else 1.0
        val resting = ClipTransform(
            positionX = layer.centerX * canvasWidth,
            positionY = layer.centerY * canvasHeight,
            scaleX = scaleX,
            scaleY = scaleY,
            opacity = layer.opacity,
        )
        val keys = keyframes(layer, resting, durationFrames, canvasWidth, canvasHeight, fps)
        val clip = when (layer.kind) {
            TemplateLayerKind.TEXT -> Clip(
                id = clipIds[index],
                assetId = null,
                timelineStart = start,
                sourceIn = FrameIndex.ZERO,
                sourceOut = FrameIndex(durationFrames),
                transform = resting,
                title = TitleContent(
                    text = text,
                    sizeFraction = layer.sizeFraction,
                    colorArgb = layer.colorArgb,
                    bold = layer.bold,
                    outline = layer.outline,
                ),
                keyframes = keys,
            )
            TemplateLayerKind.BAR -> Clip(
                id = clipIds[index],
                assetId = layer.barSticker,
                timelineStart = start,
                sourceIn = FrameIndex.ZERO,
                sourceOut = FrameIndex(durationFrames),
                transform = resting,
                still = StillKind.STICKER,
                keyframes = keys,
            )
        }
        layer.kind to clip
    }

    private fun keyframes(
        layer: TemplateLayer,
        resting: ClipTransform,
        durationFrames: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        fps: FrameRate,
    ): List<Keyframe> {
        val last = durationFrames - 1
        val placed = layer.keys.map { key ->
            val frames = fps.microsToFrames((key.seconds * 1_000_000.0).toLong())
            val frame = (if (key.fromEnd) last - frames else frames).coerceIn(0L, last)
            frame to key
        }.sortedBy { it.first }
        val result = mutableListOf<Keyframe>()
        for ((frame, key) in placed) {
            // Keys squeezed onto the same frame by a short clip: the earlier one wins.
            if (result.isNotEmpty() && result.last().frame >= frame) continue
            result += Keyframe(
                frame = frame,
                transform = resting.copy(
                    positionX = resting.positionX + key.dx * canvasWidth,
                    positionY = resting.positionY + key.dy * canvasHeight,
                    scaleX = resting.scaleX * key.scale,
                    scaleY = resting.scaleY * key.scale,
                    opacity = (layer.opacity * key.opacity).coerceIn(0.0, 1.0),
                ),
                interpolation = key.interpolation,
            )
        }
        return result
    }
}

/**
 * Puts a text template on the timeline as one edit. Text goes on a title lane and bars on an overlay
 * lane; a lane is reused only when it is free over the template's range, otherwise a new one is added
 * above, so nothing the user already placed is overwritten. [ids] come from the caller (one per layer,
 * then two spare lane ids) so applying is deterministic.
 */
data class AddTextTemplate(
    val templateId: String,
    val text: String,
    val start: FrameIndex,
    val durationFrames: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val fps: FrameRate,
    val ids: List<String>,
) : EditCommand {

    override fun apply(timeline: Timeline): EditResult<Timeline> {
        val template = TextTemplates.find(templateId)
            ?: return EditResult.Failure(EditError.InvalidTemplate("unknown template $templateId"))
        if (durationFrames < 2) return EditResult.Failure(EditError.InvalidTemplate("a template needs at least two frames"))
        if (canvasWidth <= 0 || canvasHeight <= 0) return EditResult.Failure(EditError.InvalidTemplate("the canvas has no size"))
        if (start < FrameIndex.ZERO) return EditResult.Failure(EditError.NegativeStart)
        if (ids.size < TextTemplates.idCount(template) || ids.distinct().size != ids.size) {
            return EditResult.Failure(EditError.InvalidTemplate("not enough distinct ids"))
        }
        val layerIds = ids.take(template.layers.size)
        val titleLaneId = ids[template.layers.size]
        val overlayLaneId = ids[template.layers.size + 1]
        val clips = TextTemplates.clipsFor(template, text, start, durationFrames, canvasWidth, canvasHeight, fps, layerIds)
        val end = start + durationFrames

        var current = timeline
        val base = ClipDeletion.baseTrack(timeline)
        for ((kind, clip) in clips) {
            val laneId = when (kind) {
                TemplateLayerKind.TEXT -> freeLane(current, TrackType.TITLE, base?.id, start, end)
                TemplateLayerKind.BAR -> freeLane(current, TrackType.VIDEO, base?.id, start, end)
            } ?: when (kind) {
                TemplateLayerKind.TEXT -> {
                    val added = TimelineOps.addTrack(current, Track(titleLaneId, TrackType.TITLE), 0)
                    current = (added as? EditResult.Success)?.value ?: return added
                    titleLaneId
                }
                TemplateLayerKind.BAR -> {
                    val index = current.tracks.indexOfFirst { it.type == TrackType.VIDEO }.coerceAtLeast(0)
                    val added = TimelineOps.addTrack(current, Track(overlayLaneId, TrackType.VIDEO), index)
                    current = (added as? EditResult.Success)?.value ?: return added
                    overlayLaneId
                }
            }
            current = when (val placed = TimelineOps.overwrite(current, laneId, clip)) {
                is EditResult.Success -> placed.value
                is EditResult.Failure -> return placed
            }
        }
        return EditResult.Success(current)
    }

    /** The first lane of [type] (never the base) with nothing in [start, end), or null. */
    private fun freeLane(timeline: Timeline, type: TrackType, baseId: String?, start: FrameIndex, end: FrameIndex): String? =
        timeline.tracks.firstOrNull { track ->
            track.type == type && track.id != baseId && track.clips.none { it.timelineStart < end && it.timelineEnd > start }
        }?.id
}

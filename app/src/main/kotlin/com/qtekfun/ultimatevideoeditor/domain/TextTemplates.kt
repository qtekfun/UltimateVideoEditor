package com.qtekfun.ultimatevideoeditor.domain

/** A font a preset needs: its registry id and the family name to show when it is missing. */
data class PresetFont(val id: String, val family: String)

/**
 * A ready-made animated title, defined entirely as data and stored in the `.uvtitle` format when it
 * is shared: a multilayer title ([layers], bottom first), the in and out animation ([intro], [outro],
 * each [edgeSeconds] long) and a default length. The built-ins are in [TextTemplates.all]; presets
 * a user saves are the same type, with [builtIn] false.
 *
 * Applying one puts a single title clip on a title lane. Its first text layer shows the text the user
 * typed (or [defaultText]); the other layers are drawn as they are.
 */
data class TextTemplate(
    val id: String,
    val name: String,
    val defaultText: String,
    val defaultSeconds: Double,
    val layers: List<TitleLayer>,
    val intro: MotionPreset = MotionPreset.NONE,
    val outro: MotionPreset = MotionPreset.NONE,
    val edgeSeconds: Double = TitleMotion.DEFAULT_EDGE_SECONDS,
    val builtIn: Boolean = true,
    /** Fonts the preset uses, so a device without them can say which ones are missing. */
    val fonts: List<PresetFont> = emptyList(),
) {
    /** Reason this preset cannot be used, or null when it can. */
    fun problem(): String? = when {
        id.isBlank() || name.isBlank() -> "a preset needs an id and a name"
        layers.isEmpty() -> "a preset needs at least one layer"
        !(defaultSeconds.isFinite() && defaultSeconds in MIN_SECONDS..MAX_SECONDS) -> "a preset must last between $MIN_SECONDS and $MAX_SECONDS seconds"
        !(edgeSeconds.isFinite() && edgeSeconds in 0.0..MAX_EDGE_SECONDS) -> "the animation edge must be between 0 and $MAX_EDGE_SECONDS seconds"
        else -> TitleLayerEdit.of(layers).problem()
    }

    /** The title this preset puts on the timeline, with [text] in its first text layer (when [text] is not blank). */
    fun content(text: String): TitleContent {
        val typed = text.takeIf { it.isNotBlank() } ?: defaultText
        var replaced = false
        val filled = layers.map { layer ->
            if (!replaced && layer is TextLayer) {
                replaced = true
                layer.copy(text = typed)
            } else {
                layer
            }
        }
        return TitleLayerEdit.of(filled)
    }

    companion object {
        const val MIN_SECONDS = 0.2
        const val MAX_SECONDS = 600.0
        const val MAX_EDGE_SECONDS = 5.0
    }
}

/** The built-in templates. Ids are stored nowhere (a template becomes a plain title clip), but keep them stable. */
object TextTemplates {

    private const val ACCENT_TEXT = 0xFF1B1F2A.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()
    private const val BAR_ACCENT = 0xFFFFB300.toInt()
    private const val BAR_DARK = 0xFF111418.toInt()

    val all: List<TextTemplate> = listOf(
        TextTemplate(
            id = "lower-third",
            name = "Lower third",
            defaultText = "Your name",
            defaultSeconds = 4.0,
            layers = listOf(
                ShapeLayer(
                    kind = ShapeKind.RECT,
                    widthFraction = 0.52,
                    heightFraction = 0.11,
                    fillArgb = BAR_ACCENT,
                    cornerRadiusFraction = 0.0,
                    placement = LayerPlacement(offsetX = -0.22, offsetY = 0.30),
                ),
                TextLayer(
                    text = "Your name",
                    sizeFraction = 0.05,
                    colorArgb = ACCENT_TEXT,
                    bold = true,
                    placement = LayerPlacement(offsetX = -0.22, offsetY = 0.30),
                ),
            ),
            intro = MotionPreset.SLIDE_LEFT,
            outro = MotionPreset.SLIDE_LEFT,
            edgeSeconds = 0.5,
        ),
        TextTemplate(
            id = "pop-title",
            name = "Pop title",
            defaultText = "Big idea",
            defaultSeconds = 3.0,
            layers = listOf(
                TextLayer(
                    text = "Big idea",
                    sizeFraction = 0.14,
                    bold = true,
                    border = LayerStroke(BLACK, 0.008),
                ),
            ),
            intro = MotionPreset.POP,
            outro = MotionPreset.FADE,
            edgeSeconds = 0.3,
        ),
        TextTemplate(
            id = "slide-headline",
            name = "Slide-in headline",
            defaultText = "Breaking news",
            defaultSeconds = 4.0,
            layers = listOf(
                TextLayer(
                    text = "Breaking news",
                    sizeFraction = 0.09,
                    bold = true,
                    border = LayerStroke(BLACK, 0.006),
                    placement = LayerPlacement(offsetY = -0.05),
                ),
            ),
            intro = MotionPreset.SLIDE_RIGHT,
            outro = MotionPreset.SLIDE_LEFT,
            edgeSeconds = 0.5,
        ),
        TextTemplate(
            id = "subtitle-bar",
            name = "Subtitle bar",
            defaultText = "Say something here",
            defaultSeconds = 4.0,
            layers = listOf(
                ShapeLayer(
                    kind = ShapeKind.RECT,
                    widthFraction = 0.92,
                    heightFraction = 0.11,
                    fillArgb = BAR_DARK,
                    cornerRadiusFraction = 0.0,
                    placement = LayerPlacement(offsetY = 0.36, opacity = 0.65),
                ),
                TextLayer(
                    text = "Say something here",
                    sizeFraction = 0.045,
                    colorArgb = WHITE,
                    placement = LayerPlacement(offsetY = 0.36),
                ),
            ),
            intro = MotionPreset.FADE,
            outro = MotionPreset.FADE,
            edgeSeconds = 0.2,
        ),
    )

    fun find(id: String): TextTemplate? = all.firstOrNull { it.id == id }

    /** Ids a template needs: its clip and one title lane, used when no free lane exists. */
    fun idCount(@Suppress("UNUSED_PARAMETER") template: TextTemplate): Int = 2
}

/**
 * Puts a text template on the timeline as one edit: a single title clip on a title lane. A lane is
 * reused only when it is free over the template's range, otherwise a new one is added above, so
 * nothing the user already placed is overwritten. [ids] come from the caller (the clip, then a spare
 * lane id) so applying is deterministic.
 */
data class AddTextTemplate(
    val templateId: String,
    val template: TextTemplate?,
    val text: String,
    val start: FrameIndex,
    val durationFrames: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val fps: FrameRate,
    val ids: List<String>,
) : EditCommand {

    /** A built-in template by id. */
    constructor(
        templateId: String,
        text: String,
        start: FrameIndex,
        durationFrames: Long,
        canvasWidth: Int,
        canvasHeight: Int,
        fps: FrameRate,
        ids: List<String>,
    ) : this(templateId, TextTemplates.find(templateId), text, start, durationFrames, canvasWidth, canvasHeight, fps, ids)

    override fun apply(timeline: Timeline): EditResult<Timeline> {
        val template = template ?: return EditResult.Failure(EditError.InvalidTemplate("unknown template $templateId"))
        template.problem()?.let { return EditResult.Failure(EditError.InvalidTemplate(it)) }
        if (durationFrames < 2) return EditResult.Failure(EditError.InvalidTemplate("a template needs at least two frames"))
        if (canvasWidth <= 0 || canvasHeight <= 0) return EditResult.Failure(EditError.InvalidTemplate("the canvas has no size"))
        if (start < FrameIndex.ZERO) return EditResult.Failure(EditError.NegativeStart)
        if (ids.size < TextTemplates.idCount(template) || ids.distinct().size != ids.size) {
            return EditResult.Failure(EditError.InvalidTemplate("not enough distinct ids"))
        }
        val clipId = ids[0]
        val laneId = ids[1]
        val end = start + durationFrames
        val edgeFrames = fps.microsToFrames((template.edgeSeconds * 1_000_000.0).toLong()).coerceAtLeast(1)
        val clip = Clip(
            id = clipId,
            assetId = null,
            timelineStart = start,
            sourceIn = FrameIndex.ZERO,
            sourceOut = FrameIndex(durationFrames),
            title = template.content(text),
            keyframes = TitleMotion.keyframes(ClipTransform.IDENTITY, durationFrames, canvasWidth, canvasHeight, template.intro, template.outro, edgeFrames),
        )
        var current = timeline
        val base = ClipDeletion.baseTrack(timeline)
        val target = timeline.tracks.firstOrNull { track ->
            track.type == TrackType.TITLE && track.id != base?.id && track.clips.none { it.timelineStart < end && it.timelineEnd > start }
        }?.id ?: run {
            val added = TimelineOps.addTrack(current, Track(laneId, TrackType.TITLE), 0)
            current = (added as? EditResult.Success)?.value ?: return added
            laneId
        }
        return TimelineOps.overwrite(current, target, clip)
    }
}

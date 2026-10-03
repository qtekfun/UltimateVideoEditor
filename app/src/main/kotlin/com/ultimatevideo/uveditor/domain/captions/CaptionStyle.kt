package com.ultimatevideo.uveditor.domain.captions

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.TitleContent

/**
 * A look for generated captions. Captions are ordinary title clips, so a style is just the title
 * text style, where it sits on the canvas, and how the words are chunked into cues.
 *
 * [anchorY] is the vertical position of the text block's centre as a fraction of the canvas
 * height measured down from the centre (0 = middle, 0.5 = bottom edge).
 */
data class CaptionStyle(
    val id: String,
    val label: String,
    val sizeFraction: Double,
    val colorArgb: Int,
    val bold: Boolean,
    val anchorY: Double,
    val options: CaptionOptions,
) {
    fun titleFor(text: String) = TitleContent(
        text = text,
        sizeFraction = sizeFraction,
        colorArgb = colorArgb,
        alignment = TitleAlignment.CENTER,
        bold = bold,
        outline = true,
    )

    fun transformFor(canvasHeight: Int) = ClipTransform(positionY = anchorY * canvasHeight)

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val YELLOW = 0xFFFFE600.toInt()

        /** Lower-third subtitles for long-form video: whole phrases, modest size. */
        val CLASSIC = CaptionStyle("classic", "Classic", 0.05, WHITE, false, 0.36, CaptionOptions(maxChars = 42, maxDurationMs = 4_500))

        /** Heavier white text for readability on busy footage. */
        val BOLD = CaptionStyle("bold", "Bold", 0.065, WHITE, true, 0.34, CaptionOptions(maxChars = 32))

        /** Short, punchy yellow lines of the kind used in vertical short-form video. */
        val POP = CaptionStyle("pop", "Pop", 0.075, YELLOW, true, 0.27, CaptionOptions(maxChars = 22, maxDurationMs = 2_500, pauseBreakMs = 400, minDurationMs = 500, lingerMs = 150))

        /** One or two big words at a time in the middle of the frame. */
        val IMPACT = CaptionStyle("impact", "Impact", 0.10, WHITE, true, 0.0, CaptionOptions(maxChars = 14, maxDurationMs = 1_800, pauseBreakMs = 350, minDurationMs = 400, lingerMs = 100))

        val ALL: List<CaptionStyle> = listOf(CLASSIC, BOLD, POP, IMPACT)

        fun byId(id: String): CaptionStyle = ALL.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/** Turns cues into title clips (ids from [newId]) on the canvas of the given height. */
fun CaptionStyle.clipsFor(cues: List<CaptionCue>, canvasHeight: Int, newId: () -> String): List<Clip> {
    val transform = transformFor(canvasHeight)
    return cues.map { cue ->
        Clip(
            id = "caption-${newId()}",
            assetId = null,
            timelineStart = cue.start,
            sourceIn = FrameIndex.ZERO,
            sourceOut = FrameIndex(cue.durationFrames),
            transform = transform,
            title = titleFor(cue.text),
        )
    }
}

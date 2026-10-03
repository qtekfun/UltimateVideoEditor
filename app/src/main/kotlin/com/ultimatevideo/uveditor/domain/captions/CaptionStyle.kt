package com.ultimatevideo.uveditor.domain.captions

import com.ultimatevideo.uveditor.domain.Clip
import com.ultimatevideo.uveditor.domain.ClipTransform
import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.Interpolation
import com.ultimatevideo.uveditor.domain.Keyframe
import com.ultimatevideo.uveditor.domain.TitleAlignment
import com.ultimatevideo.uveditor.domain.TitleAnimation
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleWord

/** How a whole caption phrase comes onto the screen. It is made of ordinary keyframes on the clip. */
enum class CaptionEntrance { NONE, SCALE_IN, BOUNCE }

/** Id prefix of clips made by the caption generator; the restyle action finds captions by it. */
const val CAPTION_ID_PREFIX = "caption-"

/**
 * A look for generated captions. Captions are ordinary title clips, so a style is just the title
 * text style, where it sits on the canvas, how the words are chunked into cues, how the words
 * animate inside a phrase ([animation]) and how the phrase enters ([entrance]).
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
    val animation: TitleAnimation = TitleAnimation.NONE,
    val highlightArgb: Int = TitleContent.DEFAULT_HIGHLIGHT_ARGB,
    val entrance: CaptionEntrance = CaptionEntrance.NONE,
) {
    /** The title for [text]; [words] (clip frames) drive the animation and are ignored by static styles. */
    fun titleFor(text: String, words: List<TitleWord> = emptyList()) = TitleContent(
        text = text,
        sizeFraction = sizeFraction,
        colorArgb = colorArgb,
        alignment = TitleAlignment.CENTER,
        bold = bold,
        outline = true,
        words = if (animation == TitleAnimation.NONE) emptyList() else words,
        animation = animation,
        highlightArgb = highlightArgb,
    )

    fun transformFor(canvasHeight: Int) = ClipTransform(positionY = anchorY * canvasHeight)

    /** The entrance keyframes for a clip of [durationFrames] frames at [base]; empty without an entrance. */
    fun entranceKeys(base: ClipTransform, durationFrames: Long): List<Keyframe> = entrance.keyframes(base, durationFrames)

    /** This style with other colours: the text colour and the emphasis colour. */
    fun withColors(text: Int = colorArgb, highlight: Int = highlightArgb) = copy(colorArgb = text, highlightArgb = highlight)

    /** True when the style has a word animation, so the highlight colour matters. */
    val usesHighlight: Boolean get() = animation == TitleAnimation.KARAOKE || animation == TitleAnimation.POP_IN

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val YELLOW = 0xFFFFE600.toInt()
        private const val CYAN = 0xFF00E5FF.toInt()

        /** Lower-third subtitles for long-form video: whole phrases, modest size. */
        val CLASSIC = CaptionStyle("classic", "Classic", 0.05, WHITE, false, 0.36, CaptionOptions(maxChars = 42, maxDurationMs = 4_500))

        /** Heavier white text for readability on busy footage. */
        val BOLD = CaptionStyle("bold", "Bold", 0.065, WHITE, true, 0.34, CaptionOptions(maxChars = 32))

        /** Short, punchy yellow lines of the kind used in vertical short-form video. */
        val POP = CaptionStyle("pop", "Pop", 0.075, YELLOW, true, 0.27, CaptionOptions(maxChars = 22, maxDurationMs = 2_500, pauseBreakMs = 400, minDurationMs = 500, lingerMs = 150))

        /** One or two big words at a time in the middle of the frame. */
        val IMPACT = CaptionStyle("impact", "Impact", 0.10, WHITE, true, 0.0, CaptionOptions(maxChars = 14, maxDurationMs = 1_800, pauseBreakMs = 350, minDurationMs = 400, lingerMs = 100))

        /** The phrase stays up and the word being spoken lights up and swells, as in karaoke. */
        val KARAOKE = CaptionStyle(
            "karaoke", "Karaoke", 0.07, WHITE, true, 0.27,
            CaptionOptions(maxChars = 24, maxDurationMs = 3_000, pauseBreakMs = 400, minDurationMs = 600, lingerMs = 150),
            animation = TitleAnimation.KARAOKE, highlightArgb = YELLOW,
        )

        /** Words pop in one at a time as they are spoken. */
        val WORD_POP = CaptionStyle(
            "wordpop", "Word pop", 0.09, WHITE, true, 0.0,
            CaptionOptions(maxChars = 18, maxDurationMs = 2_200, pauseBreakMs = 350, minDurationMs = 500, lingerMs = 120),
            animation = TitleAnimation.POP_IN, highlightArgb = CYAN,
        )

        /** Letters type in across each word. */
        val TYPEWRITER = CaptionStyle(
            "typewriter", "Typewriter", 0.06, WHITE, false, 0.30,
            CaptionOptions(maxChars = 36, maxDurationMs = 4_000),
            animation = TitleAnimation.TYPEWRITER,
        )

        /** Whole phrases that bounce onto the screen. */
        val BOUNCE = CaptionStyle(
            "bounce", "Bounce", 0.08, YELLOW, true, 0.25,
            CaptionOptions(maxChars = 22, maxDurationMs = 2_500, pauseBreakMs = 400, minDurationMs = 500, lingerMs = 150),
            entrance = CaptionEntrance.BOUNCE,
        )

        val ALL: List<CaptionStyle> = listOf(CLASSIC, BOLD, POP, IMPACT, KARAOKE, WORD_POP, TYPEWRITER, BOUNCE)

        fun byId(id: String): CaptionStyle = ALL.firstOrNull { it.id == id } ?: CLASSIC
    }
}

private class EntranceStep(val frame: Long, val scale: Double, val opacity: Double)

/** Keyframes that bring a clip with pose [base] in over its first frames; they fit short clips by dropping late steps. */
fun CaptionEntrance.keyframes(base: ClipTransform, durationFrames: Long): List<Keyframe> {
    val steps = when (this) {
        CaptionEntrance.NONE -> return emptyList()
        CaptionEntrance.SCALE_IN -> listOf(EntranceStep(0, 0.6, 0.0), EntranceStep(6, 1.0, 1.0))
        CaptionEntrance.BOUNCE -> listOf(EntranceStep(0, 0.5, 0.0), EntranceStep(4, 1.15, 1.0), EntranceStep(7, 0.94, 1.0), EntranceStep(9, 1.0, 1.0))
    }
    if (durationFrames < 2) return emptyList()
    val kept = steps.filter { it.frame < durationFrames }
    val keys = kept.map { step ->
        Keyframe(step.frame, base.copy(scaleX = base.scaleX * step.scale, scaleY = base.scaleY * step.scale, opacity = base.opacity * step.opacity), Interpolation.EASE)
    }.toMutableList()
    // A clip too short for every step still ends at its normal pose, on its last frame.
    if (kept.size < steps.size) {
        val settled = Keyframe(durationFrames - 1, base.copy(opacity = base.opacity * steps.last().opacity), Interpolation.EASE)
        if (keys.last().frame < settled.frame) keys += settled else keys[keys.lastIndex] = settled
    }
    return keys
}

/** How many generated captions are on the timeline. */
fun com.ultimatevideo.uveditor.domain.Timeline.captionCount(): Int =
    tracks.filter { it.type == com.ultimatevideo.uveditor.domain.TrackType.TITLE }
        .sumOf { track -> track.clips.count { it.id.startsWith(CAPTION_ID_PREFIX) && it.title != null } }

/** Turns cues into title clips (ids from [newId]) on the canvas of the given height. */
fun CaptionStyle.clipsFor(cues: List<CaptionCue>, canvasHeight: Int, newId: () -> String): List<Clip> {
    val transform = transformFor(canvasHeight)
    return cues.map { cue ->
        Clip(
            id = "$CAPTION_ID_PREFIX${newId()}",
            assetId = null,
            timelineStart = cue.start,
            sourceIn = FrameIndex.ZERO,
            sourceOut = FrameIndex(cue.durationFrames),
            transform = transform,
            title = titleFor(cue.text, cue.words),
            keyframes = entranceKeys(transform, cue.durationFrames),
        )
    }
}

/**
 * [clip] (a caption) in this style: text style, animation, position and entrance change; its
 * timing, text and place on the timeline stay. A caption without word timing gets evenly spaced
 * words so an animated style still has something to animate.
 */
fun CaptionStyle.restyle(clip: Clip, canvasHeight: Int): Clip {
    val old = clip.title ?: return clip
    val words = old.words.takeIf { it.isNotEmpty() && CaptionAnimator.wordRanges(old.text, it) != null }
        ?: CaptionAnimator.synthesizeWords(old.text, clip.durationFrames)
    val transform = transformFor(canvasHeight)
    return clip.copy(
        title = titleFor(old.text, words),
        transform = transform,
        keyframes = entranceKeys(transform, clip.durationFrames),
    )
}

package com.ultimatevideo.uveditor.domain.captions

import com.ultimatevideo.uveditor.domain.TitleAnimation
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleLook
import com.ultimatevideo.uveditor.domain.TitleWord

/** A stretch of a caption clip, in clip frames, over which its [look] does not change. */
data class LookSegment(val startFrame: Long, val endFrame: Long, val look: TitleLook)

/**
 * Which part of an animated caption shows at a given clip frame. Pure integer maths on clip
 * frames, shared by the preview (one look per tick) and the exporter (one image per [LookSegment])
 * so both draw the same thing.
 */
object CaptionAnimator {

    /** Frames a newly spoken word spends at each pop size before settling, and the sizes in percent. */
    const val POP_STEP_FRAMES = 2L
    private val POP_PERCENT = intArrayOf(135, 118, 106)

    /** Size of the word being spoken in the karaoke style. */
    const val KARAOKE_PERCENT = 110

    /**
     * Where each of [words] sits in [text] (character ranges, in order), or null when they do not
     * all appear in order, in which case the caption is drawn without animation.
     */
    fun wordRanges(text: String, words: List<TitleWord>): List<IntRange>? {
        if (words.isEmpty()) return null
        val ranges = ArrayList<IntRange>(words.size)
        var cursor = 0
        for (word in words) {
            val at = text.indexOf(word.text, cursor)
            if (at < 0) return null
            ranges += at until at + word.text.length
            cursor = at + word.text.length
        }
        return ranges
    }

    /** True when [title] has an animation that can be drawn (timed words that match its text). */
    fun isAnimated(title: TitleContent): Boolean =
        title.animation != TitleAnimation.NONE && wordRanges(title.text, title.words) != null

    /** [title] ready to draw at [frame] (clip frames): itself when it has no drawable animation, else with its look set. */
    fun contentAt(title: TitleContent, frame: Long): TitleContent =
        if (isAnimated(title)) title.copy(look = lookAt(title, frame)) else title

    /** The look of [title] at [frame] (clip frames; before the clip's start counts as its first frame). */
    fun lookAt(title: TitleContent, frame: Long): TitleLook {
        if (title.animation == TitleAnimation.NONE) return TitleLook.FULL
        val ranges = wordRanges(title.text, title.words) ?: return TitleLook.FULL
        val words = title.words
        val at = frame.coerceAtLeast(0L)
        // Words are in order, so the started ones are a prefix.
        var started = 0
        while (started < words.size && words[started].startFrame <= at) started++
        val active = started - 1
        return when (title.animation) {
            TitleAnimation.NONE -> TitleLook.FULL
            TitleAnimation.KARAOKE ->
                if (active < 0) TitleLook.FULL else TitleLook(activeWord = active, activePercent = KARAOKE_PERCENT)
            TitleAnimation.POP_IN -> {
                val hiddenFrom = if (started >= words.size) TitleLook.ALL else started
                if (active < 0) {
                    TitleLook(visibleWords = 0)
                } else {
                    val age = at - words[active].startFrame
                    val step = (age / POP_STEP_FRAMES).toInt()
                    TitleLook(visibleWords = hiddenFrom, activeWord = active, activePercent = POP_PERCENT.getOrElse(step) { 100 })
                }
            }
            TitleAnimation.TYPEWRITER -> TitleLook(visibleChars = typedChars(words, ranges, at))
        }
    }

    /** How many characters of the text show at [at]: words fill in letter by letter while spoken. */
    private fun typedChars(words: List<TitleWord>, ranges: List<IntRange>, at: Long): Int {
        var shown = 0
        for ((i, word) in words.withIndex()) {
            val range = ranges[i]
            when {
                at >= word.endFrame && at > word.startFrame -> shown = range.last + 1
                at >= word.startFrame -> {
                    val span = (word.endFrame - word.startFrame).coerceAtLeast(1L)
                    val letters = (range.last - range.first + 1).toLong()
                    val revealed = ((at - word.startFrame + 1) * letters / span).coerceIn(1L, letters).toInt()
                    return range.first + revealed
                }
                else -> return shown
            }
        }
        // Every word is complete; anything after the last one (stray punctuation) shows with it.
        return TitleLook.ALL
    }

    /**
     * The looks of [title] over clip frames [fromFrame, toFrame), as runs of equal look. A title
     * without a drawable animation is a single run.
     */
    fun segments(title: TitleContent, fromFrame: Long, toFrame: Long): List<LookSegment> {
        if (toFrame <= fromFrame) return emptyList()
        if (!isAnimated(title)) return listOf(LookSegment(fromFrame, toFrame, TitleLook.FULL))
        val result = ArrayList<LookSegment>()
        var runStart = fromFrame
        var runLook = lookAt(title, fromFrame)
        for (frame in fromFrame + 1 until toFrame) {
            val look = lookAt(title, frame)
            if (look != runLook) {
                result += LookSegment(runStart, frame, runLook)
                runStart = frame
                runLook = look
            }
        }
        result += LookSegment(runStart, toFrame, runLook)
        return result
    }

    /**
     * Evenly timed words for [text] across [durationFrames], for a caption that has none (a title
     * typed by hand, or a caption made before words were kept).
     */
    fun synthesizeWords(text: String, durationFrames: Long): List<TitleWord> {
        val pieces = text.split(' ', '\n').filter { it.isNotBlank() }
        if (pieces.isEmpty() || durationFrames <= 0) return emptyList()
        val total = pieces.sumOf { it.length }.toLong()
        var cursor = 0L
        var used = 0L
        return pieces.map { piece ->
            used += piece.length
            val start = cursor
            val end = (durationFrames * used / total).coerceAtLeast(start + 1)
            cursor = end
            TitleWord(piece, start, end)
        }
    }
}

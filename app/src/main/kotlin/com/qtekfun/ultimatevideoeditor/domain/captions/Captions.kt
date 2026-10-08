package com.qtekfun.ultimatevideoeditor.domain.captions

import com.qtekfun.ultimatevideoeditor.domain.Clip
import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import com.qtekfun.ultimatevideoeditor.domain.TitleWord

/** One recognised word, in milliseconds on the source media's own timeline. */
data class TranscriptWord(val text: String, val startMs: Long, val endMs: Long)

/** What the speech model heard: its words in order and the language it settled on. */
data class Transcript(val language: String, val words: List<TranscriptWord>) {
    companion object {
        /**
         * Builds words from the model's raw pieces. A piece with no letters or digits (",", "?!",
         * "...") belongs to the word before it, so it never becomes a caption of its own.
         */
        fun fromRaw(language: String, pieces: List<TranscriptWord>): Transcript {
            val words = ArrayList<TranscriptWord>(pieces.size)
            for (piece in pieces) {
                val text = piece.text.trim()
                if (text.isEmpty()) continue
                val previous = words.lastOrNull()
                if (previous != null && text.none { it.isLetterOrDigit() }) {
                    words[words.lastIndex] = previous.copy(text = previous.text + text, endMs = maxOf(previous.endMs, piece.endMs))
                } else {
                    words += TranscriptWord(text, piece.startMs, maxOf(piece.endMs, piece.startMs))
                }
            }
            return Transcript(language, words)
        }
    }
}

/** A caption to show from [start] (inclusive) to [end] (exclusive) on the timeline. */
data class CaptionCue(
    val start: FrameIndex,
    val end: FrameIndex,
    val text: String,
    /** The cue's words with their timing in clip frames (0 is [start]), for the animated styles. */
    val words: List<TitleWord> = emptyList(),
) {
    val durationFrames: Long get() = end - start
}

/** How words are grouped into cues. Times are milliseconds so the rules do not depend on the frame rate. */
data class CaptionOptions(
    /** A cue never grows past this many characters (a single longer word still gets its own cue). */
    val maxChars: Int = 32,
    val maxDurationMs: Long = 4_000,
    /** Silence at least this long between two words starts a new cue. */
    val pauseBreakMs: Long = 600,
    /** A cue is held at least this long so it can be read, as far as the next cue allows. */
    val minDurationMs: Long = 700,
    /** A cue stays up this long after its last word, as far as the next cue allows. */
    val lingerMs: Long = 300,
) {
    init {
        require(maxChars > 0) { "maxChars must be positive" }
        require(maxDurationMs > 0 && pauseBreakMs >= 0 && minDurationMs >= 0 && lingerMs >= 0) { "durations must not be negative" }
    }
}

object CaptionPlanner {

    /**
     * Turns [words] (source time, milliseconds) into caption cues on the timeline for [clip].
     *
     * The clip shows source frames [sourceIn, sourceOut) of the project frame rate starting at
     * its timeline start, so a word at source frame `s` lands at `timelineStart + s - sourceIn`.
     * Words outside the clip are dropped, words that straddle its edges are clipped to them, and
     * the cues never overlap each other or leave the clip. All frame maths is integer.
     */
    fun plan(words: List<TranscriptWord>, clip: Clip, fps: FrameRate, options: CaptionOptions = CaptionOptions()): List<CaptionCue> {
        val clipStart = clip.timelineStart.value
        val clipEnd = clip.timelineEnd.value

        data class Placed(val text: String, val start: Long, val end: Long)

        // Word starts are made strictly increasing so no two cues can start on the same frame.
        var lastStart = clipStart - 1
        val placed = ArrayList<Placed>()
        for (word in words.filter { it.text.isNotBlank() }.sortedBy { it.startMs }) {
            val rawStart = clipStart + fps.microsToFrames(word.startMs * MICROS_PER_MILLI) - clip.sourceIn.value
            val rawEnd = clipStart + fps.microsToFrames(word.endMs * MICROS_PER_MILLI) - clip.sourceIn.value
            val start = maxOf(rawStart, clipStart, lastStart + 1)
            val end = minOf(maxOf(rawEnd, start + 1), clipEnd)
            // Entirely before the clip, or no room left inside it.
            if (rawEnd <= clipStart || start >= clipEnd) continue
            placed += Placed(word.text.trim(), start, end)
            lastStart = start
        }
        if (placed.isEmpty()) return emptyList()

        val maxDuration = fps.microsToFrames(options.maxDurationMs * MICROS_PER_MILLI).coerceAtLeast(1)
        val pauseBreak = fps.microsToFrames(options.pauseBreakMs * MICROS_PER_MILLI)

        // Group into phrases.
        val groups = ArrayList<List<Placed>>()
        var current = ArrayList<Placed>()
        var currentLength = 0
        for (word in placed) {
            val last = current.lastOrNull()
            if (last != null) {
                val joined = currentLength + separatorLength(last.text, word.text) + word.text.length
                val breakHere = joined > options.maxChars ||
                    word.start - last.end >= pauseBreak && pauseBreak > 0 ||
                    word.end - current.first().start > maxDuration ||
                    endsSentence(last.text)
                if (breakHere) {
                    groups += current
                    current = ArrayList()
                    currentLength = 0
                }
            }
            currentLength += if (current.isEmpty()) word.text.length else separatorLength(current.last().text, word.text) + word.text.length
            current += word
        }
        if (current.isNotEmpty()) groups += current

        // Time the cues: spoken span, then linger/min-duration, never crossing the next cue or the clip.
        val linger = fps.microsToFrames(options.lingerMs * MICROS_PER_MILLI)
        val minDuration = fps.microsToFrames(options.minDurationMs * MICROS_PER_MILLI)
        val cues = ArrayList<CaptionCue>(groups.size)
        for ((index, group) in groups.withIndex()) {
            val start = group.first().start
            val spokenEnd = group.last().end
            val limit = groups.getOrNull(index + 1)?.first()?.start ?: clipEnd
            val wanted = maxOf(spokenEnd + linger, start + minDuration)
            val end = minOf(limit, clipEnd, wanted).coerceAtLeast(start + 1)
            cues += CaptionCue(
                FrameIndex(start),
                FrameIndex(end),
                join(group.map { it.text }),
                group.map { TitleWord(it.text, it.start - start, it.end - start) },
            )
        }
        return cues
    }

    /** Words joined with spaces, except between characters of scripts written without them. */
    fun join(words: List<String>): String {
        val out = StringBuilder()
        for ((i, word) in words.withIndex()) {
            if (i > 0 && separatorLength(words[i - 1], word) > 0) out.append(' ')
            out.append(word)
        }
        return out.toString()
    }

    private fun separatorLength(previous: String, next: String): Int =
        if (previous.isNotEmpty() && next.isNotEmpty() && isUnspaced(previous.last()) && isUnspaced(next.first())) 0 else 1

    private fun endsSentence(word: String): Boolean = word.lastOrNull()?.let { it in SENTENCE_ENDINGS } == true

    /** Han ideographs and kana are written without spaces between words. */
    private fun isUnspaced(c: Char): Boolean = UNSPACED_BLOCKS.contains(Character.UnicodeBlock.of(c))

    private val UNSPACED_BLOCKS: Set<Character.UnicodeBlock?> = setOf(
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A,
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION,
        Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
    )

    private const val MICROS_PER_MILLI = 1_000L
    private const val SENTENCE_ENDINGS = ".!?…。！？"
}

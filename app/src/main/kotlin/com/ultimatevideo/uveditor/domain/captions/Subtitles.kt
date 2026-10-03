package com.ultimatevideo.uveditor.domain.captions

import com.ultimatevideo.uveditor.domain.FrameIndex
import com.ultimatevideo.uveditor.domain.FrameRate
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** One subtitle as written in a file: milliseconds from the start of the movie and the text (one line). */
data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** What a subtitle file contained: the usable [cues] and how many blocks had to be [skipped]. */
data class SubtitleFile(val cues: List<SubtitleCue>, val skipped: Int)

/**
 * Reads `.srt` and `.vtt` subtitle files, entirely on the device. The text encoding is detected
 * (byte-order mark, then strict UTF-8, then UTF-16 without a mark, then Latin-1), the format is
 * detected from the content, and blocks that cannot be used are counted instead of failing the file.
 */
object Subtitles {
    /** Larger files are not subtitle files; the editor refuses them before decoding. */
    const val MAX_BYTES = 5L * 1024 * 1024

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        strict(bytes, Charsets.UTF_8)?.let { return it }
        looksLikeUtf16WithoutMark(bytes)?.let { return String(bytes, it) }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun strict(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** Mostly-ASCII text in UTF-16 has a zero in every other byte; which side says the byte order. */
    private fun looksLikeUtf16WithoutMark(bytes: ByteArray): Charset? {
        if (bytes.size < 4) return null
        val pairs = bytes.size / 2
        var zeroEven = 0
        var zeroOdd = 0
        for (i in 0 until pairs) {
            if (bytes[2 * i] == 0.toByte()) zeroEven++
            if (bytes[2 * i + 1] == 0.toByte()) zeroOdd++
        }
        return when {
            zeroOdd * 10 >= pairs * 3 && zeroEven * 10 < pairs -> Charsets.UTF_16LE
            zeroEven * 10 >= pairs * 3 && zeroOdd * 10 < pairs -> Charsets.UTF_16BE
            else -> null
        }
    }

    fun parse(bytes: ByteArray): SubtitleFile = parse(decode(bytes))

    fun parse(text: String): SubtitleFile {
        val blocks = text.replace("\r\n", "\n").replace('\r', '\n').trimStart('﻿').split(Regex("\n{2,}"))
        val cues = ArrayList<SubtitleCue>()
        var skipped = 0
        for ((index, rawBlock) in blocks.withIndex()) {
            val lines = rawBlock.split('\n').map { it.trimEnd() }.dropWhile { it.isBlank() }
            if (lines.isEmpty()) continue
            val first = lines.first()
            // The WebVTT header and its NOTE / STYLE / REGION blocks carry no cues.
            if (index == 0 && first.startsWith("WEBVTT")) continue
            if (first.startsWith("NOTE") || first.startsWith("STYLE") || first.startsWith("REGION")) continue
            val arrow = lines.indexOfFirst { it.contains("-->") }
            if (arrow < 0) {
                skipped++
                continue
            }
            val range = parseRange(lines[arrow])
            val body = lines.drop(arrow + 1).map(::cleanLine).filter { it.isNotBlank() }.joinToString(" ")
            if (range == null || body.isBlank() || range.second <= range.first) {
                skipped++
                continue
            }
            cues += SubtitleCue(range.first, range.second, body)
        }
        return SubtitleFile(cues.sortedBy { it.startMs }, skipped)
    }

    private val TIME = Regex("""(?:(\d{1,3}):)?(\d{1,2}):(\d{1,2})[.,](\d{1,3})""")

    private fun parseRange(line: String): Pair<Long, Long>? {
        val parts = line.split("-->")
        if (parts.size != 2) return null
        // After the end time a WebVTT line may carry cue settings ("align:start position:10%").
        val start = parseTime(parts[0].trim()) ?: return null
        val end = parseTime(parts[1].trim().substringBefore(' ')) ?: return null
        return start to end
    }

    private fun parseTime(text: String): Long? {
        val m = TIME.matchEntire(text) ?: return null
        val hours = m.groupValues[1].ifEmpty { "0" }.toLong()
        val minutes = m.groupValues[2].toLong()
        val seconds = m.groupValues[3].toLong()
        if (minutes > 59 || seconds > 59) return null
        // "5" after the separator is 500 ms, "05" is 50 ms, "005" is 5 ms.
        val fraction = m.groupValues[4].padEnd(3, '0').toLong()
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + fraction
    }

    private val TAG = Regex("""<[^>]*>|\{\\[^}]*\}""")

    private fun cleanLine(line: String): String = line
        .replace(TAG, "")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
        .trim()

    /**
     * Cues on the timeline for [file], starting [offset] frames in. Frames are rounded to the nearest
     * whole frame, a cue is at least one frame long, and cues never overlap: a cue that starts before
     * the previous one ended cuts the previous one short, and two cues starting on the same frame
     * are joined into one.
     */
    fun toCues(file: SubtitleFile, fps: FrameRate, offset: FrameIndex = FrameIndex.ZERO): List<CaptionCue> {
        data class Slot(var start: Long, var end: Long, var text: String)

        val slots = ArrayList<Slot>()
        for (cue in file.cues) {
            val start = offset.value + fps.microsToFrames(cue.startMs * MICROS_PER_MILLI)
            val end = maxOf(offset.value + fps.microsToFrames(cue.endMs * MICROS_PER_MILLI), start + 1)
            val previous = slots.lastOrNull()
            if (previous != null && previous.start == start) {
                previous.text = CaptionPlanner.join(listOf(previous.text, cue.text))
                previous.end = maxOf(previous.end, end)
                continue
            }
            if (previous != null && previous.end > start) previous.end = start
            slots += Slot(start, end, cue.text)
        }
        return slots.map { slot ->
            val duration = slot.end - slot.start
            CaptionCue(FrameIndex(slot.start), FrameIndex(slot.end), slot.text, CaptionAnimator.synthesizeWords(slot.text, duration))
        }
    }

    private const val MICROS_PER_MILLI = 1_000L
}

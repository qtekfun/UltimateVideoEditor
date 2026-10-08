package com.qtekfun.ultimatevideoeditor.domain.captions

import com.qtekfun.ultimatevideoeditor.domain.FrameIndex
import com.qtekfun.ultimatevideoeditor.domain.FrameRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitlesTest {

    private val fps = FrameRate(30, 1)

    @Test
    fun `parses a srt file with ids, tags and multi-line text`() {
        val file = Subtitles.parse(
            """
            1
            00:00:01,000 --> 00:00:02,500
            <b>Hello</b> there,
            {\an8}friend

            2
            00:01:02,5 --> 00:01:03,25
            Second &amp; last
            """.trimIndent(),
        )
        assertEquals(0, file.skipped)
        assertEquals(
            listOf(
                SubtitleCue(1_000, 2_500, "Hello there, friend"),
                SubtitleCue(62_500, 63_250, "Second & last"),
            ),
            file.cues,
        )
    }

    @Test
    fun `parses a vtt file, ignoring the header, notes, styles and cue settings`() {
        val file = Subtitles.parse(
            """
            WEBVTT - a title

            NOTE this is a comment

            STYLE
            ::cue { color: red }

            intro
            00:01.000 --> 00:03.000 align:start position:10%
            First <c.yellow>line</c>

            01:00:00.000 --> 01:00:01.500
            An hour in
            """.trimIndent(),
        )
        assertEquals(0, file.skipped)
        assertEquals(
            listOf(SubtitleCue(1_000, 3_000, "First line"), SubtitleCue(3_600_000, 3_601_500, "An hour in")),
            file.cues,
        )
    }

    @Test
    fun `windows line endings and a byte order mark are handled`() {
        val text = "﻿1\r\n00:00:00,500 --> 00:00:01,000\r\nHi\r\n\r\n2\r\n00:00:02,000 --> 00:00:03,000\r\nBye\r\n"
        val file = Subtitles.parse(text)
        assertEquals(listOf("Hi", "Bye"), file.cues.map { it.text })
    }

    @Test
    fun `unusable blocks are counted and skipped, and cues are sorted`() {
        val file = Subtitles.parse(
            """
            1
            00:00:05,000 --> 00:00:06,000
            Late

            2
            00:00:03,000 --> 00:00:02,000
            Backwards

            3
            not a time --> nope
            Broken

            4
            00:00:01,000 --> 00:00:02,000

            5
            00:00:00,000 --> 00:00:01,000
            Early

            just some text
            """.trimIndent(),
        )
        assertEquals(listOf("Early", "Late"), file.cues.map { it.text })
        assertEquals(4, file.skipped)
    }

    @Test
    fun `a file with no subtitles gives no cues`() {
        assertTrue(Subtitles.parse("hello world").cues.isEmpty())
        assertTrue(Subtitles.parse("").cues.isEmpty())
    }

    @Test
    fun `decodes utf-8, utf-8 with a mark, utf-16 with and without a mark and falls back to latin-1`() {
        val line = "1\n00:00:01,000 --> 00:00:02,000\nCafé niño\n"
        assertEquals(line, Subtitles.decode(line.toByteArray(Charsets.UTF_8)))
        assertEquals(line, Subtitles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + line.toByteArray(Charsets.UTF_8)))
        assertEquals(line, Subtitles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + line.toByteArray(Charsets.UTF_16LE)))
        assertEquals(line, Subtitles.decode(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + line.toByteArray(Charsets.UTF_16BE)))
        assertEquals(line, Subtitles.decode(line.toByteArray(Charsets.UTF_16LE)))
        assertEquals(line, Subtitles.decode(line.toByteArray(Charsets.UTF_16BE)))
        // Latin-1 bytes are not valid UTF-8.
        assertEquals(line, Subtitles.decode(line.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `cues land on whole frames from the offset, at least one frame long`() {
        val file = SubtitleFile(listOf(SubtitleCue(1_000, 2_500, "A"), SubtitleCue(3_000, 3_001, "B")), 0)
        val cues = Subtitles.toCues(file, fps, FrameIndex(100))
        assertEquals(listOf(130L to 175L, 190L to 191L), cues.map { it.start.value to it.end.value })
    }

    @Test
    fun `an overlapping cue cuts the previous one short and cues on the same frame are joined`() {
        val file = SubtitleFile(
            listOf(
                SubtitleCue(0, 2_000, "One"),
                SubtitleCue(1_000, 3_000, "Two"),
                SubtitleCue(1_000, 1_500, "Three"),
            ),
            0,
        )
        val cues = Subtitles.toCues(file, fps)
        assertEquals(listOf(0L to 30L, 30L to 90L), cues.map { it.start.value to it.end.value })
        assertEquals(listOf("One", "Two Three"), cues.map { it.text })
        assertEquals(cues[0].end, cues[1].start)
    }

    @Test
    fun `each cue carries evenly timed words inside its own duration`() {
        val file = SubtitleFile(listOf(SubtitleCue(0, 1_000, "one two three")), 0)
        val cue = Subtitles.toCues(file, fps).single()
        assertEquals(listOf("one", "two", "three"), cue.words.map { it.text })
        assertEquals(0L, cue.words.first().startFrame)
        assertEquals(cue.durationFrames, cue.words.last().endFrame)
    }

    @Test
    fun `fractional frame rates round down to whole frames`() {
        val ntsc = FrameRate(30000, 1001)
        val cues = Subtitles.toCues(SubtitleFile(listOf(SubtitleCue(1_000, 2_000, "x")), 0), ntsc)
        assertEquals(29L, cues.single().start.value)
        assertEquals(59L, cues.single().end.value)
    }
}

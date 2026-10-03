package com.ultimatevideo.uveditor.domain.captions

import com.ultimatevideo.uveditor.domain.FrameRate
import com.ultimatevideo.uveditor.domain.clip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class CaptionPlannerTest {

    private val fps30 = FrameRate(30, 1)

    private fun word(text: String, startMs: Long, endMs: Long) = TranscriptWord(text, startMs, endMs)

    private fun spans(cues: List<CaptionCue>) = cues.map { Triple(it.text, it.start.value, it.end.value) }

    @Test
    fun `words of one sentence form one cue timed from the first word`() {
        val clip = clip("c", start = 0, len = 600)
        val cues = CaptionPlanner.plan(listOf(word("Hello", 1000, 1400), word("world.", 1500, 2000)), clip, fps30)

        assertEquals(1, cues.size)
        assertEquals("Hello world.", cues[0].text)
        assertEquals(30L, cues[0].start.value) // 1.0 s at 30 fps
        // Spoken until 2.0 s (frame 60), lingering 300 ms (9 frames) but at least 700 ms (21 frames) from the start.
        assertEquals(69L, cues[0].end.value)
    }

    @Test
    fun `a sentence ending starts a new cue`() {
        val clip = clip("c", start = 0, len = 600)
        val cues = CaptionPlanner.plan(listOf(word("Hi.", 0, 300), word("There", 400, 800)), clip, fps30)

        assertEquals(listOf("Hi.", "There"), cues.map { it.text })
    }

    @Test
    fun `a long pause starts a new cue`() {
        val clip = clip("c", start = 0, len = 900)
        val cues = CaptionPlanner.plan(listOf(word("one", 0, 300), word("two", 3000, 3300)), clip, fps30)

        assertEquals(listOf("one", "two"), cues.map { it.text })
    }

    @Test
    fun `cues respect the character limit and never split a word`() {
        val clip = clip("c", start = 0, len = 900)
        val options = CaptionOptions(maxChars = 11)
        val words = listOf(word("alpha", 0, 200), word("beta", 250, 450), word("gamma", 500, 700), word("extraordinarily", 750, 1000))
        val cues = CaptionPlanner.plan(words, clip, fps30, options)

        assertEquals(listOf("alpha beta", "gamma", "extraordinarily"), cues.map { it.text })
    }

    @Test
    fun `cues respect the maximum duration`() {
        val clip = clip("c", start = 0, len = 1800)
        val words = (0 until 20).map { word("w$it", it * 400L, it * 400L + 350) }
        val cues = CaptionPlanner.plan(words, clip, fps30, CaptionOptions(maxChars = 200, maxDurationMs = 2_000))

        assertTrue(cues.size > 1)
        // A word may finish just past the limit measured from the first word's start, never by more than itself.
        for (cue in cues) assertTrue("cue too long: $cue", cue.durationFrames <= fps30.microsToFrames(2_000_000) + 30)
    }

    @Test
    fun `source offsets map onto the timeline and words outside the clip are dropped`() {
        // The clip shows source frames 300..600 (10 s to 20 s) at timeline frame 90.
        val clip = clip("c", start = 90, len = 300, srcIn = 300)
        val words = listOf(
            word("before", 8_000, 8_400), // source frame 240: outside
            word("inside", 10_500, 11_000), // source frame 315 -> timeline 105
            word("after", 25_000, 25_500), // outside
        )
        val cues = CaptionPlanner.plan(words, clip, fps30)

        assertEquals(listOf("inside"), cues.map { it.text })
        assertEquals(105L, cues[0].start.value)
    }

    @Test
    fun `a word straddling the clip edges is clipped to them`() {
        val clip = clip("c", start = 0, len = 60, srcIn = 30)
        // Source 0.5 s..2.5 s; the clip shows 1 s..3 s of source.
        val cues = CaptionPlanner.plan(listOf(word("edge", 500, 2_500)), clip, fps30)

        assertEquals(1, cues.size)
        assertEquals(0L, cues[0].start.value)
        assertTrue(cues[0].end.value <= 60)
    }

    @Test
    fun `a cue does not run into the next one`() {
        val clip = clip("c", start = 0, len = 600)
        val words = listOf(word("One.", 0, 300), word("Two", 350, 600))
        val cues = CaptionPlanner.plan(words, clip, fps30)

        assertEquals(2, cues.size)
        assertTrue(cues[0].end <= cues[1].start)
        // The first cue wants to linger but is held back by the second starting at 350 ms (frame 10).
        assertEquals(10L, cues[0].end.value)
    }

    @Test
    fun `the last cue stays inside the clip`() {
        val clip = clip("c", start = 0, len = 40)
        val cues = CaptionPlanner.plan(listOf(word("late", 1_000, 1_300)), clip, fps30)

        assertEquals(1, cues.size)
        assertEquals(30L, cues[0].start.value)
        assertEquals(40L, cues[0].end.value)
    }

    @Test
    fun `no words give no cues and blank words are ignored`() {
        val clip = clip("c", start = 0, len = 100)
        assertTrue(CaptionPlanner.plan(emptyList(), clip, fps30).isEmpty())
        assertTrue(CaptionPlanner.plan(listOf(word("  ", 0, 100)), clip, fps30).isEmpty())
    }

    @Test
    fun `words starting on the same frame still get distinct cues`() {
        val clip = clip("c", start = 0, len = 300)
        val cues = CaptionPlanner.plan(listOf(word("a.", 1_000, 1_005), word("b.", 1_005, 1_010)), clip, fps30)

        assertEquals(2, cues.size)
        assertTrue(cues[0].start < cues[1].start)
        assertTrue(cues[0].end <= cues[1].start)
    }

    @Test
    fun `fractional frame rates use exact integer frames`() {
        val ntsc = FrameRate(30_000, 1_001)
        val clip = clip("c", start = 0, len = 600)
        val cues = CaptionPlanner.plan(listOf(word("ten", 10_000, 10_400)), clip, ntsc)

        // 10 s * 29.97 = 299.7 -> the frame that contains the instant.
        assertEquals(299L, cues[0].start.value)
    }

    @Test
    fun `random transcripts always give ordered non-overlapping cues inside the clip`() {
        val random = Random(42)
        repeat(200) {
            val clip = clip("c", start = random.nextInt(200).toLong(), len = 100L + random.nextInt(900), srcIn = random.nextInt(300).toLong())
            var t = random.nextInt(2_000).toLong()
            val words = ArrayList<TranscriptWord>()
            repeat(random.nextInt(60)) {
                val length = 50L + random.nextInt(600)
                val texts = listOf("a", "bb", "word.", "longer,", "x?", "ok")
                val text = texts[random.nextInt(texts.size)]
                words += TranscriptWord(text, t, t + length)
                t += random.nextInt(900).toLong() - 100 // sometimes overlapping, sometimes a gap
                if (t < 0) t = 0
            }
            val cues = CaptionPlanner.plan(words, clip, fps30, CaptionOptions(maxChars = 1 + random.nextInt(40)))

            var previousEnd = clip.timelineStart.value
            for (cue in cues) {
                assertTrue("starts before clip: $cue", cue.start.value >= clip.timelineStart.value)
                assertTrue("ends after clip: $cue", cue.end.value <= clip.timelineEnd.value)
                assertTrue("empty cue: $cue", cue.end > cue.start)
                assertTrue("overlap: $cue after $previousEnd", cue.start.value >= previousEnd)
                previousEnd = cue.end.value
            }
        }
    }

    @Test
    fun `scripts written without spaces are joined without them`() {
        assertEquals("你好世界", CaptionPlanner.join(listOf("你好", "世界")))
        assertEquals("hello 你好", CaptionPlanner.join(listOf("hello", "你好")))
        assertEquals("one two", CaptionPlanner.join(listOf("one", "two")))
    }

    @Test
    fun `punctuation pieces join the word before them`() {
        val pieces = listOf(word(" Hello", 0, 300), word(",", 300, 310), word(" world", 400, 700), word("?!", 700, 720), word("  ", 800, 900))
        val transcript = Transcript.fromRaw("en", pieces)

        assertEquals(listOf("Hello,", "world?!"), transcript.words.map { it.text })
        assertEquals(310L, transcript.words[0].endMs)
        assertEquals(720L, transcript.words[1].endMs)
        assertEquals("en", transcript.language)
    }

    @Test
    fun `spans helper shows the timing for a simple pair`() {
        val clip = clip("c", start = 0, len = 300)
        val cues = CaptionPlanner.plan(listOf(word("Go.", 0, 200)), clip, fps30)
        assertEquals(listOf(Triple("Go.", 0L, 21L)), spans(cues))
    }
}

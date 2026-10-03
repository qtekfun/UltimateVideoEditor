package com.ultimatevideo.uveditor.domain.captions

import com.ultimatevideo.uveditor.domain.TitleAnimation
import com.ultimatevideo.uveditor.domain.TitleContent
import com.ultimatevideo.uveditor.domain.TitleLook
import com.ultimatevideo.uveditor.domain.TitleWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionAnimatorTest {

    // "Say it loud now": Say 0-10, it 10-20, loud 20-35, now 35-50, in clip frames.
    private val words = listOf(TitleWord("Say", 0, 10), TitleWord("it", 10, 20), TitleWord("loud", 20, 35), TitleWord("now", 35, 50))
    private fun title(animation: TitleAnimation) = TitleContent("Say it loud now", words = words, animation = animation)

    @Test
    fun `word ranges follow the words in order and fail when the text no longer matches`() {
        assertEquals(listOf(0..2, 4..5, 7..10, 12..14), CaptionAnimator.wordRanges("Say it loud now", words))
        assertNull(CaptionAnimator.wordRanges("Say it loudly", words))
        assertNull(CaptionAnimator.wordRanges("now loud it Say", words))
        assertNull(CaptionAnimator.wordRanges("anything", emptyList()))
    }

    @Test
    fun `a title without animation or with mismatched words is always the full look`() {
        assertEquals(TitleLook.FULL, CaptionAnimator.lookAt(title(TitleAnimation.NONE), 12))
        val edited = title(TitleAnimation.KARAOKE).copy(text = "Totally different")
        assertFalse(CaptionAnimator.isAnimated(edited))
        assertEquals(TitleLook.FULL, CaptionAnimator.lookAt(edited, 12))
        assertEquals(edited, CaptionAnimator.contentAt(edited, 12))
    }

    @Test
    fun `karaoke highlights the word being spoken and holds it until the next starts`() {
        val karaoke = title(TitleAnimation.KARAOKE)
        fun active(frame: Long) = CaptionAnimator.lookAt(karaoke, frame).activeWord

        assertEquals(0, active(0))
        assertEquals(0, active(9))
        assertEquals(1, active(10))
        assertEquals(2, active(20))
        assertEquals(2, active(34))
        assertEquals(3, active(35))
        assertEquals(3, active(500)) // after the last word it stays on it
        assertEquals(0, active(-7)) // before the clip counts as its first frame
        assertEquals(CaptionAnimator.KARAOKE_PERCENT, CaptionAnimator.lookAt(karaoke, 0).activePercent)
        assertEquals(TitleLook.ALL, CaptionAnimator.lookAt(karaoke, 0).visibleWords)
    }

    @Test
    fun `word pop reveals words as they start and settles each pop over a few frames`() {
        val pop = title(TitleAnimation.POP_IN)

        val first = CaptionAnimator.lookAt(pop, 0)
        assertEquals(TitleLook(visibleWords = 1, activeWord = 0, activePercent = 135), first)
        assertEquals(118, CaptionAnimator.lookAt(pop, 2).activePercent)
        assertEquals(118, CaptionAnimator.lookAt(pop, 3).activePercent)
        assertEquals(106, CaptionAnimator.lookAt(pop, 4).activePercent)
        assertEquals(100, CaptionAnimator.lookAt(pop, 6).activePercent)

        // The next word starts at frame 10: two words show and the newest pops again.
        assertEquals(TitleLook(visibleWords = 2, activeWord = 1, activePercent = 135), CaptionAnimator.lookAt(pop, 10))
        // Once every word has started the rest of the phrase is simply visible.
        assertEquals(TitleLook(visibleWords = TitleLook.ALL, activeWord = 3, activePercent = 135), CaptionAnimator.lookAt(pop, 35))
    }

    @Test
    fun `typewriter reveals letters across each word and the whole text when done`() {
        val typed = title(TitleAnimation.TYPEWRITER)
        fun chars(frame: Long) = CaptionAnimator.lookAt(typed, frame).visibleChars

        assertEquals(1, chars(0)) // the first letter shows as the word starts
        assertEquals(1, chars(5))
        assertEquals(2, chars(6))
        assertEquals(3, chars(9)) // "Say" complete
        assertEquals(5, chars(10)) // " i" of the next word: the space comes with its first letter
        assertEquals(5, chars(15))
        assertEquals(6, chars(19)) // "it" complete
        assertEquals(8, chars(20)) // " l" of "loud"
        assertEquals(15, chars(49)) // last letter of the last word: everything shows, still mid-word
        assertEquals(TitleLook.ALL, chars(50)) // every word complete
        assertEquals(TitleLook.ALL, chars(500))
    }

    @Test
    fun `a word that ends where it starts is shown in full at once`() {
        val quick = TitleContent("Hi there", words = listOf(TitleWord("Hi", 4, 4), TitleWord("there", 6, 6)), animation = TitleAnimation.TYPEWRITER)

        assertEquals(0, CaptionAnimator.lookAt(quick, 3).visibleChars)
        assertEquals(2, CaptionAnimator.lookAt(quick, 4).visibleChars)
        assertEquals(8, CaptionAnimator.lookAt(quick, 6).visibleChars)
        assertEquals(TitleLook.ALL, CaptionAnimator.lookAt(quick, 7).visibleChars)
    }

    @Test
    fun `segments cover the range exactly with runs of equal looks`() {
        val karaoke = title(TitleAnimation.KARAOKE)

        val all = CaptionAnimator.segments(karaoke, 0, 60)
        assertEquals(listOf(0L, 10L, 20L, 35L), all.map { it.startFrame })
        assertEquals(listOf(10L, 20L, 35L, 60L), all.map { it.endFrame })
        assertEquals(listOf(0, 1, 2, 3), all.map { it.look.activeWord })

        val middle = CaptionAnimator.segments(karaoke, 5, 25)
        assertEquals(listOf(5L to 10L, 10L to 20L, 20L to 25L), middle.map { it.startFrame to it.endFrame })
    }

    @Test
    fun `segments of a plain title and of an empty range`() {
        val plain = TitleContent("Hello")
        assertEquals(listOf(LookSegment(0, 40, TitleLook.FULL)), CaptionAnimator.segments(plain, 0, 40))
        assertTrue(CaptionAnimator.segments(plain, 10, 10).isEmpty())
        assertTrue(CaptionAnimator.segments(plain, 10, 3).isEmpty())
    }

    @Test
    fun `every frame of the segments has the look the animator gives for it`() {
        for (animation in listOf(TitleAnimation.KARAOKE, TitleAnimation.POP_IN, TitleAnimation.TYPEWRITER)) {
            val t = title(animation)
            for (segment in CaptionAnimator.segments(t, -5, 70)) {
                for (frame in segment.startFrame until segment.endFrame) {
                    assertEquals("$animation at $frame", CaptionAnimator.lookAt(t, frame), segment.look)
                }
            }
        }
    }

    @Test
    fun `trimming the start of a clip moves the words and keeps the animation in step`() {
        val karaoke = title(TitleAnimation.KARAOKE)
        val trimmed = karaoke.shiftedBy(15) // the clip now starts 15 frames into the old one

        assertEquals(listOf(-15L, -5L, 5L, 20L), trimmed.words.map { it.startFrame })
        // Frame 0 of the trimmed clip is frame 15 of the old one, where "it" is being spoken.
        assertEquals(CaptionAnimator.lookAt(karaoke, 15), CaptionAnimator.lookAt(trimmed, 0))
        assertEquals(CaptionAnimator.lookAt(karaoke, 30), CaptionAnimator.lookAt(trimmed, 15))
    }

    @Test
    fun `timing does not matter to a picture but the look does`() {
        val a = title(TitleAnimation.KARAOKE).withoutTiming()
        val b = title(TitleAnimation.KARAOKE).shiftedBy(7).withoutTiming()
        assertEquals(a, b)
        assertFalse(a == a.copy(look = TitleLook(activeWord = 1)))
    }

    @Test
    fun `synthesized words fill the clip in proportion to their length`() {
        val synthesized = CaptionAnimator.synthesizeWords("a bb", 30)

        assertEquals(listOf(TitleWord("a", 0, 10), TitleWord("bb", 10, 30)), synthesized)
        assertTrue(CaptionAnimator.synthesizeWords("   ", 30).isEmpty())
        assertTrue(CaptionAnimator.synthesizeWords("hello", 0).isEmpty())
        val longer = CaptionAnimator.synthesizeWords("one two three", 90)
        assertEquals(longer.last().endFrame, 90L)
        assertTrue(longer.zipWithNext().all { (x, y) -> x.endFrame == y.startFrame })
        assertEquals(longer.map { it.text }, CaptionAnimator.wordRanges("one two three", longer)!!.map { "one two three".substring(it) })
    }
}

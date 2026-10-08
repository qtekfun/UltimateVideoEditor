package com.qtekfun.ultimatevideoeditor.engine.title

import com.qtekfun.ultimatevideoeditor.domain.TitleAnimation
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import com.qtekfun.ultimatevideoeditor.domain.TitleLook
import com.qtekfun.ultimatevideoeditor.domain.TitleWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleKeyCacheLooksTest {

    private val words = listOf(TitleWord("Say", 0, 10), TitleWord("it", 10, 20))
    private val base = TitleContent("Say it", words = words, animation = TitleAnimation.KARAOKE)

    @Test
    fun `each look of a caption is its own picture`() {
        val cache = TitleKeyCache()

        val first = cache.keyFor(base.copy(look = TitleLook(activeWord = 0, activePercent = 110)), 1080, 1920)
        val second = cache.keyFor(base.copy(look = TitleLook(activeWord = 1, activePercent = 110)), 1080, 1920)

        assertTrue(first.second && second.second)
        assertFalse(first.first == second.first)
    }

    @Test
    fun `the same look reached with different word timing reuses the picture`() {
        val cache = TitleKeyCache()
        val look = TitleLook(activeWord = 1, activePercent = 110)

        val a = cache.keyFor(base.copy(look = look), 1080, 1920)
        // Another phrase with the same words spoken faster, or the clip after a trim: same pixels.
        val shifted = base.shiftedBy(40).copy(words = words.map { it.copy(endFrame = it.endFrame / 2) }, look = look)
        val b = cache.keyFor(shifted, 1080, 1920)

        assertEquals(a.first, b.first)
        assertFalse(b.second)
    }

    @Test
    fun `a typewriter caption fits in the cache and evicted looks are reported`() {
        val cache = TitleKeyCache(capacity = 3)
        val keys = (1..5).map { cache.keyFor(base.copy(look = TitleLook(visibleChars = it)), 1080, 1920).first }

        assertEquals(5, keys.toSet().size)
        assertEquals(keys.take(2), cache.drain())
        assertTrue(cache.drain().isEmpty())
    }

    @Test
    fun `the default capacity holds the pictures of several animated phrases`() {
        assertTrue(TitleKeyCache.DEFAULT_CAPACITY >= 64)
    }
}

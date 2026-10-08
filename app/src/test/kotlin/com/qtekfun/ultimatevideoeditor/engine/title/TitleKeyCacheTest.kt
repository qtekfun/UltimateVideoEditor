package com.qtekfun.ultimatevideoeditor.engine.title

import com.qtekfun.ultimatevideoeditor.domain.TitleAlignment
import com.qtekfun.ultimatevideoeditor.domain.TitleContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleKeyCacheTest {

    private val hello = TitleContent("Hello")

    @Test
    fun `the first sight of a title needs an upload and the second does not`() {
        val cache = TitleKeyCache()

        val first = cache.keyFor(hello, 1920, 1080)
        val second = cache.keyFor(hello, 1920, 1080)

        assertTrue(first.second)
        assertFalse(second.second)
        assertEquals(first.first, second.first)
        assertTrue(first.first > 0)
    }

    @Test
    fun `different text style or canvas get different keys`() {
        val cache = TitleKeyCache()

        val keys = listOf(
            cache.keyFor(hello, 1920, 1080).first,
            cache.keyFor(hello.copy(text = "Bye"), 1920, 1080).first,
            cache.keyFor(hello.copy(bold = true), 1920, 1080).first,
            cache.keyFor(hello.copy(alignment = TitleAlignment.LEFT), 1920, 1080).first,
            cache.keyFor(hello.copy(colorArgb = 0xFF00FF00.toInt()), 1920, 1080).first,
            cache.keyFor(hello.copy(sizeFraction = 0.2), 1920, 1080).first,
            cache.keyFor(hello, 1280, 720).first,
        )

        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun `the least recently used titles are dropped and reported for release`() {
        val cache = TitleKeyCache(capacity = 2)
        val a = cache.keyFor(hello.copy(text = "a"), 100, 100).first
        val b = cache.keyFor(hello.copy(text = "b"), 100, 100).first
        cache.keyFor(hello.copy(text = "a"), 100, 100) // a is now newer than b

        val c = cache.keyFor(hello.copy(text = "c"), 100, 100).first

        assertEquals(listOf(b), cache.drain())
        assertEquals(emptyList<Int>(), cache.drain())
        // b is new again, with a fresh key that is never reused.
        val again = cache.keyFor(hello.copy(text = "b"), 100, 100)
        assertTrue(again.second)
        assertTrue(again.first !in setOf(a, b, c))
    }

    @Test
    fun `capacity must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { TitleKeyCache(0) }
    }
}

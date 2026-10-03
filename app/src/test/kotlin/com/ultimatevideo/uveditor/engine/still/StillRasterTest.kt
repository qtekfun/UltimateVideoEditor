package com.ultimatevideo.uveditor.engine.still

import com.ultimatevideo.uveditor.domain.StillKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StillRasterTest {

    @Test
    fun `contain fits a wide photo by width and a tall one by height`() {
        assertEquals(1920 to 1080, StillFit.contain(4000, 2250, 1920, 1080))
        assertEquals(1440 to 1080, StillFit.contain(4000, 3000, 1920, 1080))
        assertEquals(608 to 1080, StillFit.contain(3000, 5333, 1920, 1080))
    }

    @Test
    fun `contain scales small pictures up to fill the canvas like video does`() {
        assertEquals(1920 to 1080, StillFit.contain(192, 108, 1920, 1080))
        assertEquals(1080 to 1080, StillFit.contain(10, 10, 1920, 1080))
    }

    @Test
    fun `contain never leaves the canvas nor collapses to nothing`() {
        for (w in listOf(1, 7, 640, 4000, 12000)) for (h in listOf(1, 9, 480, 3000, 9000)) {
            val (cw, ch) = StillFit.contain(w, h, 1280, 720)
            assertTrue("${w}x$h -> ${cw}x$ch", cw in 1..1280 && ch in 1..720)
        }
        assertEquals(1 to 720, StillFit.contain(1, 100_000, 1280, 720))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `contain rejects an empty picture`() {
        StillFit.contain(0, 10, 100, 100)
    }

    @Test
    fun `stickers are a fraction of the shorter canvas side with a floor`() {
        assertEquals(378, StillFit.stickerSide(1920, 1080))
        assertEquals(378, StillFit.stickerSide(1080, 1920))
        assertEquals(64, StillFit.stickerSide(100, 100))
    }

    @Test
    fun `sample size only reduces while the picture stays at least canvas sized`() {
        assertEquals(1, StillFit.sampleSize(1920, 1080, 1920, 1080))
        assertEquals(1, StillFit.sampleSize(3000, 2000, 1920, 1080))
        assertEquals(2, StillFit.sampleSize(4000, 3000, 1920, 1080))
        assertEquals(4, StillFit.sampleSize(8000, 6000, 1920, 1080))
        // Orientation does not matter: only the long side counts.
        assertEquals(StillFit.sampleSize(8000, 6000, 1920, 1080), StillFit.sampleSize(6000, 8000, 1080, 1920))
    }

    private val photoA = StillRef(StillKind.PHOTO, "content://a")
    private val photoB = StillRef(StillKind.PHOTO, "content://b")

    @Test
    fun `keys are stable per picture and canvas and start above the title keys`() {
        val cache = StillKeyCache()
        val (a, fresh) = cache.keyFor(photoA, 1920, 1080)
        assertTrue(fresh)
        assertTrue(a >= StillKeyCache.FIRST_KEY)
        assertEquals(a to false, cache.keyFor(photoA, 1920, 1080))
        val (b, freshB) = cache.keyFor(photoB, 1920, 1080)
        assertTrue(freshB)
        assertNotEquals(a, b)
        // The same picture on another canvas is another texture.
        assertNotEquals(a, cache.keyFor(photoA, 1280, 720).first)
        // A sticker and a photo with the same id string are different stills.
        assertNotEquals(a, cache.keyFor(StillRef(StillKind.STICKER, "content://a"), 1920, 1080).first)
    }

    @Test
    fun `the least recently used picture is evicted when the budget is exceeded`() {
        val oneFrame = 100L * 100 * 4
        val cache = StillKeyCache(budgetBytes = oneFrame * 2)
        val (a, _) = cache.keyFor(photoA, 100, 100)
        val (b, _) = cache.keyFor(photoB, 100, 100)
        cache.keyFor(photoA, 100, 100) // touch A: B is now the oldest
        val (c, _) = cache.keyFor(StillRef(StillKind.PHOTO, "content://c"), 100, 100)

        assertEquals(listOf(b), cache.drain())
        assertTrue(cache.contains(a) && cache.contains(c))
        assertFalse(cache.contains(b))
        assertEquals(emptyList<Int>(), cache.drain())
        // B comes back as a new key that must be uploaded again.
        val (b2, fresh) = cache.keyFor(photoB, 100, 100)
        assertTrue(fresh)
        assertNotEquals(b, b2)
    }

    @Test
    fun `one picture larger than the budget is still kept`() {
        val cache = StillKeyCache(budgetBytes = 10)
        val (a, _) = cache.keyFor(photoA, 100, 100)
        assertTrue(cache.contains(a))
        assertEquals(emptyList<Int>(), cache.drain())
    }

    @Test
    fun `every listed sticker has a unique id and a recognised kind`() {
        val ids = StickerIds.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(ids.all { it.startsWith(StickerIds.SHAPE_PREFIX) || it.startsWith(StickerIds.EMOJI_PREFIX) })
        assertTrue(StickerIds.all.all { it.label.isNotBlank() })
        assertTrue(StickerIds.isKnown("shape:heart"))
        assertFalse(StickerIds.isKnown("shape:nope"))
        assertFalse(StickerIds.isKnown("heart"))
        assertTrue(StickerIds.all.size >= 12)
    }
}

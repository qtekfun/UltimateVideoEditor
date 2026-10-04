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
    fun `a picture is stored at its native size and drawn at the same contain fit as before`() {
        // A small GIF frame on a 4K canvas: stored as 480x270, drawn at 3840x2160.
        val small = StillFit.plan(480, 270, 3840, 2160)
        assertEquals(StillFit.Plan(480, 270, 3840, 2160), small)
        assertEquals(480L * 270 * 4, small.textureBytes)
        // A photo larger than its fit is reduced to the fit, like the old canvas-size path.
        assertEquals(StillFit.Plan(1920, 1080, 1920, 1080), StillFit.plan(4000, 2250, 1920, 1080))
        // Taller than the canvas: the display is the contain fit, the texture follows it when reduced.
        assertEquals(StillFit.Plan(608, 1080, 608, 1080), StillFit.plan(3000, 5333, 1920, 1080))
        // Equal to the fit: nothing to reduce.
        assertEquals(StillFit.Plan(1920, 1080, 1920, 1080), StillFit.plan(1920, 1080, 1920, 1080))
    }

    @Test
    fun `the display size always equals the old contain fit and the texture never exceeds it`() {
        val canvases = listOf(1280 to 720, 1920 to 1080, 3840 to 2160, 1080 to 1920)
        val sources = listOf(1 to 1, 7 to 3, 100 to 100, 480 to 270, 1000 to 4000, 4096 to 2304, 8000 to 6000, 12000 to 9000)
        for ((cw, ch) in canvases) for ((w, h) in sources) {
            val plan = StillFit.plan(w, h, cw, ch)
            val old = StillFit.contain(w, h, cw, ch)
            assertEquals("display of ${w}x$h on ${cw}x$ch", old, plan.displayWidth to plan.displayHeight)
            assertTrue(plan.textureWidth.toLong() * plan.textureHeight <= maxOf(w.toLong() * h, 1L))
            assertTrue(plan.textureWidth.toLong() * plan.textureHeight <= plan.displayWidth.toLong() * plan.displayHeight || plan.textureWidth <= w)
            // The stored picture has the displayed aspect ratio to within the rounding of one pixel.
            val aspectDrift = kotlin.math.abs(plan.textureWidth.toDouble() / plan.textureHeight - plan.displayWidth.toDouble() / plan.displayHeight)
            val tolerance = 1.0 / minOf(plan.textureHeight, plan.displayHeight) * (plan.displayWidth.toDouble() / plan.displayHeight) + 1e-9
            assertTrue("aspect drift $aspectDrift for ${w}x$h on ${cw}x$ch", aspectDrift <= tolerance + 0.05)
        }
    }

    @Test
    fun `real sizes replace the estimate and the budget is never exceeded`() {
        val frame = 480L * 270 * 4
        val cache = StillKeyCache()
        var maxUsed = 0L
        var evictions = 0
        for (i in 0 until 600) {
            val (key, fresh) = cache.keyFor(StillRef(StillKind.PHOTO, "content://gif", i), 3840, 2160)
            assertTrue(fresh)
            cache.resize(key, frame)
            evictions += cache.drain().size
            maxUsed = maxOf(maxUsed, cache.usedBytes)
        }
        assertTrue("used $maxUsed of ${PictureBudget.DEFAULT_BYTES}", maxUsed <= PictureBudget.DEFAULT_BYTES)
        // 128 MB holds about 258 frames of 0.5 MB; the old canvas-size estimate (33 MB each) held three.
        val held = 600 - evictions
        assertTrue("kept $held frames", held in 200..270)
    }

    @Test
    fun `a resize evicts older pictures but never the one just sized`() {
        val cache = StillKeyCache(budgetBytes = 1000)
        val (a, _) = cache.keyFor(photoA, 10, 10)
        val (b, _) = cache.keyFor(photoB, 10, 10)
        cache.resize(a, 400)
        cache.resize(b, 700) // 1100 > 1000: A is older, so A goes
        assertEquals(listOf(a), cache.drain())
        assertTrue(cache.contains(b))
        cache.resize(b, 5000) // alone and over budget: it stays
        assertTrue(cache.contains(b))
        assertEquals(5000L, cache.usedBytes)
        cache.resize(12345, 1) // an unknown key is ignored
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

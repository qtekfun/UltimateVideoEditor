package com.qtekfun.ultimatevideoeditor.engine.still

import java.io.ByteArrayOutputStream
import java.util.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A small GIF writer for the tests: a real LZW encoder, so the decoder is checked against compressed data. */
private object GifWriter {
    class Frame(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        /** Palette indices in row-major order of the picture (the writer reorders them when [interlaced]). */
        val pixels: ByteArray,
        val delayCs: Int = 10,
        val disposal: Int = 0,
        val transparent: Int = -1,
        val interlaced: Boolean = false,
        val localPalette: IntArray? = null,
    )

    fun write(
        width: Int,
        height: Int,
        palette: IntArray,
        frames: List<Frame>,
        minCode: Int = 8,
        loopExtension: Boolean = true,
        repeatCount: Int = 0,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("GIF89a".toByteArray(Charsets.ISO_8859_1))
        out.u16(width)
        out.u16(height)
        out.write(0x80 or (7 shl 4) or (tableBits(palette.size) - 1)) // global table present
        out.write(0)
        out.write(0)
        writePalette(out, palette, 1 shl tableBits(palette.size))
        if (loopExtension) {
            out.write(byteArrayOf(0x21, 0xFF.toByte(), 11))
            out.write("NETSCAPE2.0".toByteArray(Charsets.ISO_8859_1))
            out.write(byteArrayOf(3, 1, repeatCount.toByte(), (repeatCount shr 8).toByte(), 0))
        }
        for (f in frames) {
            out.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
            out.write((f.disposal shl 2) or (if (f.transparent >= 0) 1 else 0))
            out.u16(f.delayCs)
            out.write(if (f.transparent >= 0) f.transparent else 0)
            out.write(0)
            out.write(0x2C)
            out.u16(f.left)
            out.u16(f.top)
            out.u16(f.width)
            out.u16(f.height)
            val local = f.localPalette
            out.write((if (local != null) 0x80 or (tableBits(local.size) - 1) else 0) or (if (f.interlaced) 0x40 else 0))
            if (local != null) writePalette(out, local, 1 shl tableBits(local.size))
            out.write(minCode)
            var stored = f.pixels
            if (f.interlaced) {
                val rows = GifAnimation.interlacedRows(f.height)
                stored = ByteArray(f.pixels.size)
                for (n in 0 until f.height) {
                    System.arraycopy(f.pixels, rows[n] * f.width, stored, n * f.width, f.width)
                }
            }
            val data = lzw(minCode, stored)
            var at = 0
            while (at < data.size) {
                val n = minOf(255, data.size - at)
                out.write(n)
                out.write(data, at, n)
                at += n
            }
            out.write(0)
        }
        out.write(0x3B)
        return out.toByteArray()
    }

    private fun tableBits(size: Int): Int {
        var bits = 1
        while ((1 shl bits) < size) bits++
        return bits
    }

    private fun writePalette(out: ByteArrayOutputStream, palette: IntArray, size: Int) {
        for (i in 0 until size) {
            val c = palette.getOrElse(i) { 0 }
            out.write((c shr 16) and 0xFF)
            out.write((c shr 8) and 0xFF)
            out.write(c and 0xFF)
        }
    }

    private fun ByteArrayOutputStream.u16(v: Int) {
        write(v and 0xFF)
        write((v shr 8) and 0xFF)
    }

    /** Standard GIF LZW: codes grow in width as the table fills, a clear code restarts a full table. */
    fun lzw(minCode: Int, pixels: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        var bits = 0
        var bitCount = 0
        var codeSize = minCode + 1
        fun put(code: Int) {
            bits = bits or (code shl bitCount)
            bitCount += codeSize
            while (bitCount >= 8) {
                out.write(bits and 0xFF)
                bits = bits ushr 8
                bitCount -= 8
            }
        }
        val clear = 1 shl minCode
        val eoi = clear + 1
        var dict = HashMap<Int, Int>()
        var next = eoi + 1
        put(clear)
        var cur = pixels[0].toInt() and 0xFF
        for (i in 1 until pixels.size) {
            val p = pixels[i].toInt() and 0xFF
            val key = (cur shl 8) or p
            val known = dict[key]
            if (known != null) {
                cur = known
                continue
            }
            put(cur)
            if (next < 4096) {
                dict[key] = next
                if (next == (1 shl codeSize) && codeSize < 12) codeSize++
                next++
            } else {
                put(clear)
                codeSize = minCode + 1
                dict = HashMap()
                next = eoi + 1
            }
            cur = p
        }
        put(cur)
        put(eoi)
        if (bitCount > 0) out.write(bits and 0xFF)
        return out.toByteArray()
    }
}

class GifTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val palette = intArrayOf(red, green, blue, white)

    private fun solid(w: Int, h: Int, index: Int) = ByteArray(w * h) { index.toByte() }

    @Test
    fun `lzw round trips random pictures through table growth and clear codes`() {
        val rng = Random(11)
        for ((minCode, colours) in listOf(2 to 4, 4 to 16, 8 to 256)) {
            for (size in listOf(1, 2, 7, 100, 4097, 20000)) {
                val pixels = ByteArray(size) { rng.nextInt(colours).toByte() }
                val decoded = GifLzw.decode(minCode, GifWriter.lzw(minCode, pixels), size)
                assertArrayEquals("min code $minCode, $size pixels", pixels, decoded)
            }
        }
    }

    @Test
    fun `lzw round trips repetitive data that fills the table with long strings`() {
        val pixels = ByteArray(60000) { ((it / 7) % 5).toByte() }
        assertArrayEquals(pixels, GifLzw.decode(3, GifWriter.lzw(3, pixels), pixels.size))
        val flat = ByteArray(30000)
        assertArrayEquals(flat, GifLzw.decode(2, GifWriter.lzw(2, flat), flat.size))
    }

    @Test
    fun `a known one pixel GIF decodes to its colour`() {
        // 1x1 GIF89a with a global table (white, black) and one pixel of index 0: an opaque white pixel.
        val bytes = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00,
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
            0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        )
        val gif = GifAnimation.parse(bytes)
        assertEquals(1, gif.width)
        assertEquals(1, gif.height)
        assertEquals(1, gif.frameCount)
        assertEquals(white, gif.render(0)[0])
    }

    @Test
    fun `frames keep the file's size, order and delays`() {
        val frames = listOf(
            GifWriter.Frame(0, 0, 3, 2, solid(3, 2, 0), delayCs = 10),
            GifWriter.Frame(0, 0, 3, 2, solid(3, 2, 1), delayCs = 25),
            GifWriter.Frame(0, 0, 3, 2, solid(3, 2, 2), delayCs = 0),
        )
        val gif = GifAnimation.parse(GifWriter.write(3, 2, palette, frames))
        assertEquals(3, gif.width)
        assertEquals(2, gif.height)
        assertEquals(3, gif.frameCount)
        assertEquals(listOf(100, 250, 0), gif.rawDelaysMs)
        assertArrayEquals(IntArray(6) { red }, gif.render(0))
        assertArrayEquals(IntArray(6) { green }, gif.render(1))
        assertArrayEquals(IntArray(6) { blue }, gif.render(2))
    }

    @Test
    fun `a smaller frame is drawn over the canvas at its offset and keeps what is under it`() {
        val gif = GifAnimation.parse(
            GifWriter.write(
                4, 4, palette,
                listOf(
                    GifWriter.Frame(0, 0, 4, 4, solid(4, 4, 0)),
                    GifWriter.Frame(1, 1, 2, 2, solid(2, 2, 1)),
                ),
            ),
        )
        val second = gif.render(1)
        for (y in 0 until 4) {
            for (x in 0 until 4) {
                val inside = x in 1..2 && y in 1..2
                assertEquals("($x,$y)", if (inside) green else red, second[y * 4 + x])
            }
        }
    }

    @Test
    fun `transparent pixels let the canvas show through and an empty canvas stays transparent`() {
        val pixels = byteArrayOf(0, 3, 3, 1) // 3 is the transparent index
        val gif = GifAnimation.parse(
            GifWriter.write(
                2, 2, palette,
                listOf(GifWriter.Frame(0, 0, 2, 2, pixels, transparent = 3)),
            ),
        )
        assertArrayEquals(intArrayOf(red, 0, 0, green), gif.render(0))
    }

    @Test
    fun `disposal to background clears the frame's rectangle before the next one`() {
        val gif = GifAnimation.parse(
            GifWriter.write(
                4, 4, palette,
                listOf(
                    GifWriter.Frame(0, 0, 4, 4, solid(4, 4, 0)),
                    GifWriter.Frame(1, 1, 2, 2, solid(2, 2, 1), disposal = 2),
                    GifWriter.Frame(0, 0, 1, 1, solid(1, 1, 2)),
                ),
            ),
        )
        val third = gif.render(2)
        assertEquals(blue, third[0])
        assertEquals(red, third[3])                // outside the cleared rectangle: still the first frame
        assertEquals(0, third[1 * 4 + 1])          // the second frame's rectangle was cleared to transparent
        assertEquals(0, third[2 * 4 + 2])
    }

    @Test
    fun `disposal to previous restores the canvas as it was before that frame`() {
        val gif = GifAnimation.parse(
            GifWriter.write(
                4, 4, palette,
                listOf(
                    GifWriter.Frame(0, 0, 4, 4, solid(4, 4, 0)),
                    GifWriter.Frame(1, 1, 2, 2, solid(2, 2, 1), disposal = 3),
                    GifWriter.Frame(0, 0, 1, 1, solid(1, 1, 2)),
                ),
            ),
        )
        val third = gif.render(2)
        assertEquals(blue, third[0])
        assertEquals(red, third[1 * 4 + 1]) // the green square was undone: the first frame shows again
        assertEquals(red, third[2 * 4 + 2])
    }

    @Test
    fun `disposal to nothing and unspecified leave the frame in place`() {
        for (disposal in listOf(0, 1)) {
            val gif = GifAnimation.parse(
                GifWriter.write(
                    2, 2, palette,
                    listOf(
                        GifWriter.Frame(0, 0, 2, 2, solid(2, 2, 0), disposal = disposal),
                        GifWriter.Frame(0, 0, 1, 1, solid(1, 1, 1)),
                    ),
                ),
            )
            assertArrayEquals(intArrayOf(green, red, red, red), gif.render(1))
        }
    }

    @Test
    fun `interlaced frames come out in picture order`() {
        val height = 11
        val pixels = ByteArray(height * 2) { (it / 2 % 4).toByte() } // row r has colour r % 4
        val gif = GifAnimation.parse(
            GifWriter.write(2, height, palette, listOf(GifWriter.Frame(0, 0, 2, height, pixels, interlaced = true))),
        )
        val out = gif.render(0)
        for (y in 0 until height) assertEquals("row $y", palette[y % 4], out[y * 2])
        assertEquals(listOf(0, 8, 4, 2, 6, 10, 1, 3, 5, 7, 9), GifAnimation.interlacedRows(11).toList())
    }

    @Test
    fun `a local colour table replaces the global one for its frame`() {
        val local = intArrayOf(blue, red)
        val gif = GifAnimation.parse(
            GifWriter.write(
                1, 1, palette,
                listOf(GifWriter.Frame(0, 0, 1, 1, byteArrayOf(0), localPalette = local)),
            ),
        )
        assertEquals(blue, gif.render(0)[0])
    }

    @Test
    fun `rendering an earlier frame again replays from the start and gives the same picture`() {
        val frames = (0 until 5).map { GifWriter.Frame((it % 3), 0, 2, 2, solid(2, 2, it % 4), disposal = it % 4) }
        val gif = GifAnimation.parse(GifWriter.write(5, 4, palette, frames))
        val first = (0 until 5).map { gif.render(it) }
        val backwards = (4 downTo 0).map { gif.render(it) }.reversed()
        for (i in 0 until 5) assertArrayEquals("frame $i", first[i], backwards[i])
        // The returned array is a copy: changing it does not touch the animation.
        gif.render(2)[0] = 12345
        assertArrayEquals(first[2], gif.render(2))
        assertThrows(IllegalArgumentException::class.java) { gif.render(5) }
        assertThrows(IllegalArgumentException::class.java) { gif.render(-1) }
    }

    @Test
    fun `random access matches linear playback and a seek back replays only up to one snapshot interval`() {
        val n = 90
        val frames = (0 until n).map { GifWriter.Frame((it % 3), (it % 2), 3, 2, solid(3, 2, it % 4), disposal = it % 4) }
        val bytes = GifWriter.write(8, 6, palette, frames)
        val truth = GifAnimation.parse(bytes).let { g -> (0 until n).map { g.render(it) } }
        val gif = GifAnimation.parse(bytes)
        gif.render(n - 1) // forward once: snapshots are taken on the way
        assertEquals(n.toLong(), gif.framesDrawn)
        val interval = CanvasSnapshots(n, 8 * 6).interval
        for (target in listOf(70, 33, 32, 31, 8, 0, 89, 5)) {
            val before = gif.framesDrawn
            val got = gif.render(target)
            assertArrayEquals("frame $target", truth[target], got)
            val cost = gif.framesDrawn - before
            // Forward jumps cost the distance; backward jumps cost less than one interval.
            assertTrue("seek to $target drew $cost frames (interval $interval)", cost <= maxOf(interval.toLong(), 0L) || target > 70)
        }
    }

    @Test
    fun `delays can be read without decoding any picture and a broken file reads as empty`() {
        val bytes = GifWriter.write(
            2, 2, palette,
            listOf(GifWriter.Frame(0, 0, 2, 2, solid(2, 2, 0), delayCs = 7), GifWriter.Frame(0, 0, 2, 2, solid(2, 2, 1), delayCs = 30)),
        )
        assertEquals(listOf(70, 300), GifDelayScan.delaysMs(bytes))
        assertEquals(emptyList<Int>(), GifDelayScan.delaysMs(byteArrayOf(1, 2, 3)))
        assertEquals(emptyList<Int>(), GifDelayScan.delaysMs(bytes.copyOf(20)))
    }

    @Test
    fun `the NETSCAPE repeat count becomes the number of plays and a missing one means once`() {
        val frames = listOf(GifWriter.Frame(0, 0, 2, 2, solid(2, 2, 0)), GifWriter.Frame(0, 0, 2, 2, solid(2, 2, 1)))
        assertEquals(0, GifDelayScan.plays(GifWriter.write(2, 2, palette, frames)))
        assertEquals(2, GifDelayScan.plays(GifWriter.write(2, 2, palette, frames, repeatCount = 1)))
        assertEquals(301, GifDelayScan.plays(GifWriter.write(2, 2, palette, frames, repeatCount = 300)))
        assertEquals(1, GifDelayScan.plays(GifWriter.write(2, 2, palette, frames, loopExtension = false)))
        assertEquals(0, GifDelayScan.plays(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `an application extension of another name is skipped and does not set the count`() {
        val good = GifWriter.write(
            2, 2, palette, listOf(GifWriter.Frame(0, 0, 2, 2, solid(2, 2, 0)), GifWriter.Frame(0, 0, 2, 2, solid(2, 2, 1))),
            loopExtension = false,
        )
        // Header (6) + screen descriptor (7) + global table, then an unrelated application block before the frames.
        val tableBytes = 3 * (1 shl (((good[10].toInt() and 7)) + 1))
        val at = 13 + tableBytes
        val other = byteArrayOf(0x21, 0xFF.toByte(), 11) + "XMP DataXMP".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(3, 1, 9, 0, 0)
        val bytes = good.copyOfRange(0, at) + other + good.copyOfRange(at, good.size)
        assertEquals(1, GifDelayScan.plays(bytes))
        assertEquals(2, GifDelayScan.delaysMs(bytes).size)
    }

    @Test
    fun `files that are not readable GIFs are rejected with a message`() {
        assertThrows(GifFormatException::class.java) { GifAnimation.parse(ByteArray(0)) }
        assertThrows(GifFormatException::class.java) { GifAnimation.parse("PNG....".toByteArray()) }
        val ok = GifWriter.write(2, 2, palette, listOf(GifWriter.Frame(0, 0, 2, 2, solid(2, 2, 0))))
        assertThrows(GifFormatException::class.java) { GifAnimation.parse(ok.copyOf(ok.size - 12)) }
        // A header with no frames at all.
        assertThrows(GifFormatException::class.java) {
            GifAnimation.parse(GifWriter.write(2, 2, palette, emptyList()))
        }
        // An absurd size is refused before anything is allocated.
        val huge = ok.copyOf()
        huge[6] = 0xFF.toByte(); huge[7] = 0xFF.toByte(); huge[8] = 0xFF.toByte(); huge[9] = 0xFF.toByte()
        assertThrows(GifFormatException::class.java) { GifAnimation.parse(huge) }
    }

    @Test
    fun `a truncated frame leaves zeros instead of failing`() {
        val full = GifWriter.lzw(8, ByteArray(500) { (it % 200).toByte() })
        val decoded = GifLzw.decode(8, full.copyOf(full.size / 2), 500)
        assertEquals(500, decoded.size)
        assertTrue(decoded.take(50).withIndex().all { (i, b) -> b.toInt() == i % 200 })
    }
}

class WebpAnimationScanTest {
    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())

    private fun chunk(tag: String, body: ByteArray): ByteArray {
        val pad = if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf()
        return tag.toByteArray(Charsets.ISO_8859_1) + le32(body.size) + body + pad
    }

    private fun anmf(durationMs: Int): ByteArray {
        // x(3) y(3) width-1(3) height-1(3) duration(3) flags(1), then a frame payload we do not read.
        val header = ByteArray(16)
        header[12] = durationMs.toByte()
        header[13] = (durationMs shr 8).toByte()
        header[14] = (durationMs shr 16).toByte()
        return chunk("ANMF", header + chunk("VP8 ", byteArrayOf(1, 2, 3)))
    }

    private fun webp(flags: Int, vararg frames: ByteArray, tail: ByteArray = byteArrayOf()): ByteArray {
        val vp8x = chunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0, 9, 0, 0, 9, 0, 0))
        val body = "WEBP".toByteArray(Charsets.ISO_8859_1) + vp8x + frames.fold(byteArrayOf()) { a, b -> a + b } + tail
        return "RIFF".toByteArray(Charsets.ISO_8859_1) + le32(body.size) + body
    }

    @Test
    fun `an animated WebP gives one delay per frame, odd chunks included`() {
        // An odd-sized chunk between frames is padded to an even length and must be stepped over exactly.
        val bytes = webp(0x02, anmf(40), chunk("EXIF", byteArrayOf(1, 2, 3)), anmf(120), anmf(0), anmf(70000))
        assertEquals(listOf(40, 120, 0, 70000), WebpAnimationScan.delaysMs(bytes))
    }

    @Test
    fun `a WebP without the animation flag, a plain file and garbage have no frames`() {
        assertEquals(emptyList<Int>(), WebpAnimationScan.delaysMs(webp(0x00, anmf(40), anmf(40))))
        assertEquals(emptyList<Int>(), WebpAnimationScan.delaysMs("not an image at all".toByteArray()))
        assertEquals(emptyList<Int>(), WebpAnimationScan.delaysMs(ByteArray(0)))
        val notWebp = webp(0x02, anmf(40)).also { it[8] = 'X'.code.toByte() }
        assertEquals(emptyList<Int>(), WebpAnimationScan.delaysMs(notWebp))
    }

    @Test
    fun `a cut off file keeps the frames that are complete`() {
        val full = webp(0x02, anmf(40), anmf(50), anmf(60))
        val cut = full.copyOf(full.size - 10)
        assertEquals(listOf(40, 50), WebpAnimationScan.delaysMs(cut))
    }
}

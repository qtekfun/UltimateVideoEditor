package com.ultimatevideo.uveditor.engine.still

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Builds animated WebP files by hand: the chunks are real, the "pictures" are four bytes of colour. */
internal class WebpBuilder(private val canvasW: Int, private val canvasH: Int) {
    private val body = ByteArrayOutputStream()
    var animationFlag = true
    var withVp8x = true
    var loop = 0
    var background = 0

    private fun u24(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte())
    private fun u32(v: Int) = u24(v) + byteArrayOf((v shr 24).toByte())

    fun chunk(kind: String, payload: ByteArray, out: ByteArrayOutputStream = body): WebpBuilder {
        out.write(kind.toByteArray(Charsets.ISO_8859_1))
        out.write(u32(payload.size))
        out.write(payload)
        if (payload.size % 2 == 1) out.write(0)
        return this
    }

    /** A picture chunk whose first four bytes are the ARGB colour the fake decoder paints (padded to 6 bytes). */
    fun picture(argb: Int, vp8l: Boolean = false, alphaBit: Boolean = false, odd: Boolean = false): Pair<String, ByteArray> {
        val colour = byteArrayOf((argb ushr 24).toByte(), (argb ushr 16).toByte(), (argb ushr 8).toByte(), argb.toByte())
        val payload = if (vp8l) {
            // 0x2F, then 14+14 bits of size, the alpha bit (bit 28) and a version; the fake decoder reads bytes 5..8.
            val header = 0x2F.toByte() to (if (alphaBit) 1 shl 28 else 0)
            byteArrayOf(header.first) + u32(header.second) + colour + (if (odd) byteArrayOf(1) else byteArrayOf())
        } else {
            colour + (if (odd) byteArrayOf(1) else byteArrayOf())
        }
        return (if (vp8l) "VP8L" else "VP8 ") to payload
    }

    fun frame(
        x: Int, y: Int, w: Int, h: Int, durationMs: Int, argb: Int,
        blend: Boolean = true, disposeBg: Boolean = false, alpha: ByteArray? = null,
        vp8l: Boolean = false, alphaBit: Boolean = false, odd: Boolean = false,
    ): WebpBuilder {
        val inner = ByteArrayOutputStream()
        inner.write(u24(x / 2)); inner.write(u24(y / 2)); inner.write(u24(w - 1)); inner.write(u24(h - 1)); inner.write(u24(durationMs))
        inner.write((if (blend) 0 else 2) or (if (disposeBg) 1 else 0))
        if (alpha != null) chunk("ALPH", alpha, inner)
        val (kind, payload) = picture(argb, vp8l, alphaBit, odd)
        chunk(kind, payload, inner)
        return chunk("ANMF", inner.toByteArray())
    }

    fun build(): ByteArray {
        val all = ByteArrayOutputStream()
        if (withVp8x) {
            val flags = if (animationFlag) 0x02 else 0
            chunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0) + u24(canvasW - 1) + u24(canvasH - 1), all)
            chunk("ANIM", u32(background) + byteArrayOf(loop.toByte(), (loop shr 8).toByte()), all)
        }
        all.write(body.toByteArray())
        val payload = byteArrayOf('W'.code.toByte(), 'E'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte()) + all.toByteArray()
        return "RIFF".toByteArray(Charsets.ISO_8859_1) + u32(payload.size) + payload
    }
}

/** Fake picture decoder: size from the standalone file's VP8X, colour from the first four picture bytes. */
internal object FakeWebpDecoder : WebpStillDecoder {
    val decoded = ArrayList<ByteArray>()

    override fun decode(webp: ByteArray): WebpPixels {
        decoded += webp
        val container = String(webp, 12, 4, Charsets.ISO_8859_1)
        if (container != "VP8X") throw WebpFormatException("standalone file has no VP8X")
        fun u24(at: Int) = (webp[at].toInt() and 0xFF) or ((webp[at + 1].toInt() and 0xFF) shl 8) or ((webp[at + 2].toInt() and 0xFF) shl 16)
        val w = 1 + u24(24 + 0)
        val h = 1 + u24(24 + 3)
        // After VP8X (8 + 10 bytes) come the optional ALPH chunk and the picture chunk.
        var at = 12 + 18
        var kind = String(webp, at, 4, Charsets.ISO_8859_1)
        var size = (webp[at + 4].toInt() and 0xFF) or ((webp[at + 5].toInt() and 0xFF) shl 8)
        if (kind == "ALPH") {
            at += 8 + size + (size and 1)
            kind = String(webp, at, 4, Charsets.ISO_8859_1)
            size = (webp[at + 4].toInt() and 0xFF) or ((webp[at + 5].toInt() and 0xFF) shl 8)
        }
        val start = at + 8 + if (kind == "VP8L") 5 else 0
        val argb = ((webp[start].toInt() and 0xFF) shl 24) or ((webp[start + 1].toInt() and 0xFF) shl 16) or
            ((webp[start + 2].toInt() and 0xFF) shl 8) or (webp[start + 3].toInt() and 0xFF)
        return WebpPixels(w, h, IntArray(w * h) { argb })
    }
}

class WebpTest {
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val halfGreen = 0x8000FF00.toInt()

    private fun file(f: WebpBuilder.() -> Unit): ByteArray = WebpBuilder(8, 6).apply(f).build()

    @Test
    fun `container fields and frames are read`() {
        val bytes = WebpBuilder(8, 6).apply {
            loop = 3
            background = 0x80112233.toInt()
            frame(0, 0, 8, 6, 100, red)
            frame(2, 4, 4, 2, 250, blue, blend = false, disposeBg = true)
        }.build()
        val c = WebpContainerParser.parse(bytes)
        assertEquals(8, c.canvasWidth); assertEquals(6, c.canvasHeight)
        assertEquals(3, c.loopCount)
        assertEquals(0x80112233.toInt(), c.backgroundArgb)
        assertEquals(2, c.frames.size)
        val f = c.frames[1]
        assertEquals(listOf(2, 4, 4, 2, 250), listOf(f.x, f.y, f.width, f.height, f.durationMs))
        assertFalse(f.blend); assertTrue(f.disposeToBackground)
        assertTrue(c.frames[0].blend); assertFalse(c.frames[0].disposeToBackground)
        assertEquals(listOf(100, 250), WebpAnimation.parse(bytes, FakeWebpDecoder).rawDelaysMs)
    }

    @Test
    fun `the existing delay scan agrees with the container parser`() {
        val bytes = file { frame(0, 0, 8, 6, 40, red); frame(0, 0, 8, 6, 60, blue) }
        assertEquals(listOf(40, 60), WebpAnimationScan.delaysMs(bytes))
        assertEquals(listOf(40, 60), WebpAnimation.parse(bytes, FakeWebpDecoder).rawDelaysMs)
    }

    @Test
    fun `a standalone frame is byte exact with padding and the alpha flag`() {
        val alph = byteArrayOf(0, 7, 7) // odd length: padded
        val bytes = file { frame(0, 0, 4, 2, 10, red, alpha = alph, odd = true) }
        val c = WebpContainerParser.parse(bytes)
        val out = WebpContainerParser.standalone(bytes, c.frames[0])
        // RIFF size = everything after the first 8 bytes, and the file is an even length.
        assertEquals(out.size - 8, (out[4].toInt() and 0xFF) or ((out[5].toInt() and 0xFF) shl 8) or ((out[6].toInt() and 0xFF) shl 16))
        assertEquals(0, out.size % 2)
        assertEquals("WEBP", String(out, 8, 4))
        assertEquals("VP8X", String(out, 12, 4))
        assertEquals(10, out[16].toInt())
        assertEquals(0x10, out[20].toInt() and 0x10) // alpha flag
        assertEquals(0, out[20].toInt() and 0x02) // not animated
        assertEquals(3, out[24].toInt()); assertEquals(1, out[27].toInt()) // 4-1 and 2-1
        assertEquals("ALPH", String(out, 30, 4))
        assertEquals(3, out[34].toInt())
        assertArrayEquals(alph, out.copyOfRange(38, 41))
        assertEquals(0, out[41].toInt()) // pad
        assertEquals("VP8 ", String(out, 42, 4))
        val pictureSize = out[46].toInt() and 0xFF
        assertEquals(5, pictureSize) // four colour bytes and one odd byte
        assertEquals(0, out[out.size - 1].toInt()) // pad of the odd picture chunk
    }

    @Test
    fun `alpha flag follows ALPH or the VP8L alpha bit`() {
        val lossyNoAlpha = file { frame(0, 0, 2, 2, 10, red) }
        val lossless = file { frame(0, 0, 2, 2, 10, red, vp8l = true, alphaBit = true) }
        val losslessOpaque = file { frame(0, 0, 2, 2, 10, red, vp8l = true, alphaBit = false) }
        fun flags(b: ByteArray) = WebpContainerParser.standalone(b, WebpContainerParser.parse(b).frames[0])[20].toInt() and 0x10
        assertEquals(0, flags(lossyNoAlpha))
        assertEquals(0x10, flags(lossless))
        assertEquals(0, flags(losslessOpaque))
    }

    @Test
    fun `frames are composited with blend and dispose bits`() {
        val bytes = file {
            frame(0, 0, 8, 6, 10, red)                                   // 0: full red
            frame(2, 2, 2, 2, 10, halfGreen, blend = true)               // 1: half green blended over red
            frame(2, 2, 2, 2, 10, blue, blend = false, disposeBg = true) // 2: overwrites, then cleared after
            frame(6, 4, 2, 2, 10, blue)                                  // 3: a corner on a cleared hole
        }
        val a = WebpAnimation.parse(bytes, FakeWebpDecoder)
        val w = 8
        val f0 = a.render(0)
        assertTrue(f0.all { it == red })
        val f1 = a.render(1)
        val mixed = f1[2 * w + 2]
        assertEquals(0xFF, mixed ushr 24)
        assertTrue("green over red keeps both: ${mixed.toString(16)}", ((mixed shr 16) and 0xFF) in 100..140 && ((mixed shr 8) and 0xFF) in 100..140)
        assertEquals(red, f1[0])
        val f2 = a.render(2)
        assertEquals(blue, f2[2 * w + 2])
        val f3 = a.render(3)
        assertEquals("the disposed rectangle is transparent again", 0, f3[2 * w + 2])
        assertEquals(blue, f3[4 * w + 6])
        assertEquals(red, f3[0])
    }

    @Test
    fun `rendering an earlier frame replays from the start and returns a copy`() {
        val bytes = file { frame(0, 0, 8, 6, 10, red); frame(0, 0, 8, 6, 10, blue, blend = false) }
        val a = WebpAnimation.parse(bytes, FakeWebpDecoder)
        val forward = a.render(1)
        assertTrue(forward.all { it == blue })
        val back = a.render(0)
        assertTrue(back.all { it == red })
        back[0] = 0
        assertTrue(a.render(0).all { it == red })
    }

    @Test
    fun `source-over maths on straight alpha`() {
        assertEquals(blue, WebpAnimation.over(red, blue))
        assertEquals(red, WebpAnimation.over(red, 0))
        assertEquals(blue, WebpAnimation.over(0, blue))
        val mid = WebpAnimation.over(0xFF000000.toInt(), 0x80FFFFFF.toInt())
        assertEquals(0xFF, mid ushr 24)
        assertEquals(0x80, (mid shr 16) and 0xFF)
        // Two half-transparent layers: alpha accumulates to 3/4.
        val both = WebpAnimation.over(0x80FF0000.toInt(), 0x800000FF.toInt())
        assertTrue((both ushr 24) in 190..194)
    }

    @Test
    fun `malformed files fail cleanly`() {
        fun bad(b: ByteArray, what: String) {
            try {
                WebpContainerParser.parse(b)
                fail("expected a failure for $what")
            } catch (e: WebpFormatException) {
                // expected
            }
        }
        val good = file { frame(0, 0, 8, 6, 10, red) }
        bad(ByteArray(0), "empty")
        bad("RIFF....WAVE".toByteArray(), "not WebP")
        bad(good.copyOf(40), "truncated inside a chunk")
        bad(good.copyOf(good.size - 3), "cut-off picture chunk")
        bad(WebpBuilder(8, 6).apply { animationFlag = false; frame(0, 0, 8, 6, 10, red) }.build(), "not animated")
        bad(WebpBuilder(8, 6).apply { withVp8x = false; frame(0, 0, 8, 6, 10, red) }.build(), "no VP8X")
        bad(WebpBuilder(8, 6).build(), "no frames")
        bad(file { frame(4, 0, 8, 6, 10, red) }, "frame outside the canvas")
        bad(WebpBuilder(16384 * 8, 16384 * 8).apply { frame(0, 0, 2, 2, 10, red) }.build(), "huge canvas")
        // A chunk size that claims more than the file holds.
        val lie = good.copyOf()
        lie[16] = 0x7F; lie[17] = 0x7F; lie[18] = 0x7F; lie[19] = 0x7F // the VP8X size field
        bad(lie, "oversized chunk")
        // A frame without a picture, and one with two.
        val noPicture = ByteArrayOutputStream().also { out ->
            val inner = ByteArray(16)
            inner[6] = 7; inner[9] = 5
            WebpBuilder(8, 6).chunk("ANMF", inner, out)
        }
        val header = WebpBuilder(8, 6).build()
        val spliced = header.copyOf(header.size) + noPicture.toByteArray()
        val patched = spliced.copyOf()
        val size = patched.size - 8
        patched[4] = size.toByte(); patched[5] = (size shr 8).toByte(); patched[6] = (size shr 16).toByte()
        bad(patched, "frame with no picture")
    }

    @Test
    fun `too many frames are refused`() {
        val b = WebpBuilder(2, 2)
        repeat(WebpContainerParser.MAX_FRAMES + 1) { b.frame(0, 0, 2, 2, 10, red) }
        try {
            WebpContainerParser.parse(b.build())
            fail("expected too many frames to fail")
        } catch (e: WebpFormatException) {
            assertTrue(e.message!!.contains("frames"))
        }
    }

    @Test
    fun `a decoder that returns the wrong size is reported`() {
        val bytes = file { frame(0, 0, 8, 6, 10, red) }
        val a = WebpAnimation.parse(bytes) { WebpPixels(2, 2, IntArray(4)) }
        try {
            a.render(0)
            fail("expected a size mismatch")
        } catch (e: WebpFormatException) {
            assertTrue(e.message!!.contains("expected"))
        }
    }

    @Test
    fun `trailing bytes after the RIFF are ignored`() {
        val bytes = file { frame(0, 0, 8, 6, 10, red) } + byteArrayOf(1, 2, 3, 4, 5)
        assertEquals(1, WebpContainerParser.parse(bytes).frames.size)
    }
}

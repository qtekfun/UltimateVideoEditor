package com.qtekfun.ultimatevideoeditor.engine.verify

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameSignerTest {
    private fun plane8(w: Int, h: Int, rowStride: Int = w, value: (Int, Int) -> Int): SamplePlane {
        val b = ByteBuffer.allocate(rowStride * h)
        for (y in 0 until h) for (x in 0 until w) b.put(y * rowStride + x, value(x, y).toByte())
        return SamplePlane(b, rowStride, 1, wide = false)
    }

    private fun plane10(w: Int, h: Int, value: (Int, Int) -> Int): SamplePlane {
        val b = ByteBuffer.allocate(w * h * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until h) for (x in 0 until w) b.putShort((y * w + x) * 2, (value(x, y) shl 6).toShort())
        return SamplePlane(b, w * 2, 2, wide = true)
    }

    @Test
    fun `a limited range grey frame gives mid luma and neutral chroma`() {
        val sig = FrameSigner.sign(5, 0, 640, 360, 8, plane8(640, 360) { _, _ -> 126 }, plane8(320, 180) { _, _ -> 128 }, plane8(320, 180) { _, _ -> 128 })

        // (126 - 16) / 219 = 0.502
        assertEquals(0.502, sig.y[100].toDouble() / SignatureFormat.SCALE, 0.001)
        assertEquals(0.5, sig.cb[100].toDouble() / SignatureFormat.SCALE, 0.001)
        assertTrue(SignatureMath.isFlat(sig))
    }

    @Test
    fun `black and white map to the ends of the range`() {
        val black = FrameSigner.sign(0, 0, 64, 36, 8, plane8(64, 36) { _, _ -> 16 }, plane8(32, 18) { _, _ -> 128 }, plane8(32, 18) { _, _ -> 128 })
        val white = FrameSigner.sign(0, 0, 64, 36, 8, plane8(64, 36) { _, _ -> 235 }, plane8(32, 18) { _, _ -> 128 }, plane8(32, 18) { _, _ -> 128 })

        assertEquals(0, black.y[0])
        assertEquals(SignatureFormat.SCALE, white.y[0])
    }

    @Test
    fun `ten bit planes read the same scale as eight bit ones`() {
        val eight = FrameSigner.sign(0, 0, 128, 72, 8, plane8(128, 72) { x, _ -> 16 + x * 219 / 127 }, plane8(64, 36) { _, _ -> 128 }, plane8(64, 36) { _, _ -> 128 })
        val ten = FrameSigner.sign(0, 0, 128, 72, 10, plane10(128, 72) { x, _ -> 64 + x * 876 / 127 }, plane10(64, 36) { _, _ -> 512 }, plane10(64, 36) { _, _ -> 512 })

        val d = SignatureMath.distance(eight, ten)
        assertTrue("lumaMean ${d.lumaMean}", d.lumaMean < 0.005)
        assertTrue("chromaMean ${d.chromaMean}", d.chromaMean < 0.005)
    }

    @Test
    fun `a left half bright and right half dark frame shows in the grid and rows run top to bottom`() {
        val sig = FrameSigner.sign(0, 0, 640, 360, 8, plane8(640, 360) { x, y -> if (y < 180) 200 else 40 }, plane8(320, 180) { _, _ -> 128 }, plane8(320, 180) { _, _ -> 128 })

        assertTrue(sig.y[0] > sig.y[SignatureFormat.CELLS - 1])
        assertTrue(sig.y[SignatureFormat.W * 3] > 40000)
        assertTrue(sig.y[SignatureFormat.W * 15] < 20000)
    }

    @Test
    fun `row padding of the decoder's buffer is skipped`() {
        val padded = plane8(640, 360, rowStride = 704) { x, y -> (x + y) % 200 + 16 }
        val tight = plane8(640, 360) { x, y -> (x + y) % 200 + 16 }
        val grey = plane8(320, 180) { _, _ -> 128 }

        val a = FrameSigner.sign(0, 0, 640, 360, 8, padded, grey, grey)
        val b = FrameSigner.sign(0, 0, 640, 360, 8, tight, grey, grey)

        assertEquals(0.0, SignatureMath.distance(a, b).lumaMean, 0.0)
    }

    @Test
    fun `the native reduction and the decoder side agree on a colour bar picture`() {
        // The value the native reduceProbe writes for pure red in BT.709: Y = 0.2126, Cb = -0.1146 (+0.5), Cr = +0.5 (+0.5 clamped to 1).
        val y = 0.2126
        val cb = (0.0 - y) / (2 * (1 - 0.0722)) + 0.5
        // The decoder side sees the limited range codes of that colour: Y 16 + 219 * 0.2126 = 62.6, Cb 128 - 224 * 0.1146 = 102.3, Cr 240.
        val sig = FrameSigner.sign(
            0, 0, 64, 36, 8,
            plane8(64, 36) { _, _ -> Math.round(16 + 219 * y).toInt() },
            plane8(32, 18) { _, _ -> Math.round(128 + 224 * (cb - 0.5)).toInt() },
            plane8(32, 18) { _, _ -> 240 },
        )

        assertEquals(y, sig.y[0].toDouble() / SignatureFormat.SCALE, 0.004)
        assertEquals(cb, sig.cb[0].toDouble() / SignatureFormat.SCALE, 0.004)
        assertEquals(1.0, sig.cr[0].toDouble() / SignatureFormat.SCALE, 0.004)
    }
}

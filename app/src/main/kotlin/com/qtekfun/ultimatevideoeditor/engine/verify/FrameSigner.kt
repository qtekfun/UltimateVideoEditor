package com.qtekfun.ultimatevideoeditor.engine.verify

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One plane of a decoded frame, read sample by sample: [wide] planes hold 16-bit little-endian values with a ten-bit
 * sample in the upper bits (P010), the others one byte per sample. ([originX], [originY]) is the crop corner in this plane.
 */
class SamplePlane(
    buffer: ByteBuffer,
    private val rowStride: Int,
    private val pixelStride: Int,
    private val wide: Boolean,
    private val originX: Int = 0,
    private val originY: Int = 0,
) {
    private val data: ByteBuffer = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN)

    /** The code value at ([x], [y]) of the cropped picture: 0..255, or 0..1023 for a wide plane. */
    fun at(x: Int, y: Int): Int {
        val offset = (originY + y) * rowStride + (originX + x) * pixelStride
        return if (wide) (data.getShort(offset).toInt() and 0xFFFF) ushr P010_SHIFT else data.get(offset).toInt() and 0xFF
    }

    private companion object {
        const val P010_SHIFT = 6
    }
}

/**
 * Signs a decoded frame in the grid of [SignatureFormat] in the stream's own nominal Y'CbCr: limited-range codes are mapped to
 * 0..1 (luma) and 0..1 around one half (chroma), the same scale `encode/frame_signature.h` writes. Each cell is estimated from
 * a regular lattice of samples, so a 4K frame costs a few hundred thousand reads, not eight million.
 */
object FrameSigner {
    private const val LUMA_SAMPLES = 16 // per cell and axis, for the frames that are compared
    private const val CHROMA_SAMPLES = 8
    private const val COARSE_DIVISOR = 4 // frames that are only looked at for flatness use a quarter of the lattice per axis

    fun sign(
        frame: Long,
        ptsUs: Long,
        width: Int,
        height: Int,
        bitDepth: Int,
        y: SamplePlane,
        cb: SamplePlane,
        cr: SamplePlane,
        coarse: Boolean = false,
    ): FrameSignature {
        val lumaSamples = if (coarse) LUMA_SAMPLES / COARSE_DIVISOR else LUMA_SAMPLES
        val chromaSamples = if (coarse) CHROMA_SAMPLES / COARSE_DIVISOR else CHROMA_SAMPLES
        val shift = bitDepth - 8
        val lumaBlack = 16 shl shift
        val lumaRange = 219 shl shift
        val chromaMid = 128 shl shift
        val chromaRange = 224 shl shift
        val yOut = IntArray(SignatureFormat.CELLS)
        val cbOut = IntArray(SignatureFormat.CELLS)
        val crOut = IntArray(SignatureFormat.CELLS)
        val chromaW = (width + 1) / 2
        val chromaH = (height + 1) / 2
        for (cy in 0 until SignatureFormat.H) {
            for (cx in 0 until SignatureFormat.W) {
                val i = cy * SignatureFormat.W + cx
                val yMean = cellMean(y, width, height, cx, cy, lumaSamples)
                yOut[i] = quantise((yMean - lumaBlack) / lumaRange)
                val cbMean = cellMean(cb, chromaW, chromaH, cx, cy, chromaSamples)
                val crMean = cellMean(cr, chromaW, chromaH, cx, cy, chromaSamples)
                cbOut[i] = quantise((cbMean - chromaMid) / chromaRange + 0.5)
                crOut[i] = quantise((crMean - chromaMid) / chromaRange + 0.5)
            }
        }
        return FrameSignature(frame, ptsUs, yOut, cbOut, crOut)
    }

    private fun cellMean(plane: SamplePlane, w: Int, h: Int, cx: Int, cy: Int, samples: Int): Double {
        val x0 = cx * w / SignatureFormat.W
        val x1 = maxOf(x0 + 1, (cx + 1) * w / SignatureFormat.W)
        val y0 = cy * h / SignatureFormat.H
        val y1 = maxOf(y0 + 1, (cy + 1) * h / SignatureFormat.H)
        val nx = minOf(samples, x1 - x0)
        val ny = minOf(samples, y1 - y0)
        var sum = 0L
        for (j in 0 until ny) {
            val py = y0 + ((2 * j + 1) * (y1 - y0)) / (2 * ny)
            for (i in 0 until nx) {
                val px = x0 + ((2 * i + 1) * (x1 - x0)) / (2 * nx)
                sum += plane.at(px.coerceAtMost(w - 1), py.coerceAtMost(h - 1))
            }
        }
        return sum.toDouble() / (nx * ny)
    }

    private fun quantise(v: Double): Int = (v.coerceIn(0.0, 1.0) * SignatureFormat.SCALE + 0.5).toInt()
}

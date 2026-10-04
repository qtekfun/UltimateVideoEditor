package com.ultimatevideo.uveditor.engine.still

/** The file is not a GIF this decoder can read. The message is fit to log; the caller falls back to the first frame. */
class GifFormatException(message: String) : Exception(message)

/**
 * A GIF read into frames, without any Android classes so it runs on the JVM in tests. The platform's
 * decoder cannot be asked for "frame N", and export needs exactly that, so GIFs are decoded here: global and
 * local colour tables, transparency, interlacing, disposal methods 0..3 and the delays of every frame.
 *
 * [render] returns the composited canvas (ARGB, straight alpha) after frame `index`, replaying the file from
 * the start only when asked for an earlier frame than the last one it drew, so playing forward is linear.
 */
class GifAnimation private constructor(
    override val width: Int,
    override val height: Int,
    private val frames: List<GifFrame>,
) : AnimatedPicture {
    override val frameCount: Int get() = frames.size

    /** Delay of every frame as the file states it, in milliseconds (a GIF stores hundredths of a second). */
    override val rawDelaysMs: List<Int> get() = frames.map { it.delayCs * 10 }

    override var framesDrawn: Long = 0
        private set

    private var canvas = IntArray(width * height)
    private var drawn = -1
    private var previousCanvas: IntArray? = null
    private val snapshots = CanvasSnapshots(frames.size, width * height)

    /** The composited picture after frame [index]; the array is a copy the caller owns. */
    @Synchronized
    override fun render(index: Int): IntArray {
        require(index in frames.indices) { "frame $index of ${frames.size}" }
        if (index < drawn) restoreBefore(index)
        while (drawn < index) drawNext()
        return canvas.copyOf()
    }

    /** Goes back to the newest snapshot at or before [index] (or to the empty canvas) so replaying is short. */
    private fun restoreBefore(index: Int) {
        val near = snapshots.floor(index)
        if (near == null) {
            reset()
        } else {
            canvas = near.second.canvas.copyOf()
            previousCanvas = near.second.previous?.copyOf()
            drawn = near.first
        }
    }

    private fun reset() {
        canvas = IntArray(width * height)
        drawn = -1
        previousCanvas = null
    }

    private fun drawNext() {
        // Undo the previous frame as its disposal method asks, before drawing the next one.
        if (drawn >= 0) {
            val prev = frames[drawn]
            when (prev.disposal) {
                DISPOSE_BACKGROUND -> clearRect(prev)
                DISPOSE_PREVIOUS -> previousCanvas?.let { canvas = it.copyOf() }
                else -> Unit
            }
        }
        val next = frames[drawn + 1]
        if (next.disposal == DISPOSE_PREVIOUS) previousCanvas = canvas.copyOf()
        drawFrame(next)
        drawn++
        framesDrawn++
        snapshots.offer(drawn, canvas, previousCanvas)
    }

    private fun clearRect(f: GifFrame) {
        for (y in f.top until minOf(f.top + f.height, height)) {
            for (x in f.left until minOf(f.left + f.width, width)) canvas[y * width + x] = 0
        }
    }

    private fun drawFrame(f: GifFrame) {
        val indices = GifLzw.decode(f.minCodeSize, f.data, f.width * f.height)
        val rows = if (f.interlaced) interlacedRows(f.height) else IntArray(f.height) { it }
        for (srcRow in 0 until f.height) {
            val y = f.top + rows[srcRow]
            if (y >= height) continue
            for (col in 0 until f.width) {
                val x = f.left + col
                if (x >= width) continue
                val i = indices[srcRow * f.width + col].toInt() and 0xFF
                if (i == f.transparentIndex || i >= f.palette.size) continue
                canvas[y * width + x] = f.palette[i]
            }
        }
    }

    companion object {
        private const val DISPOSE_BACKGROUND = 2
        private const val DISPOSE_PREVIOUS = 3

        /** Parses [bytes]. @throws GifFormatException when it is not a GIF or is cut short before its first frame. */
        fun parse(bytes: ByteArray): GifAnimation {
            val scan = GifScan.scan(bytes)
            return GifAnimation(scan.width, scan.height, scan.frames)
        }

        /** Destination row of each stored row of an interlaced image (passes of 8, 8, 4 and 2 rows). */
        internal fun interlacedRows(height: Int): IntArray {
            val out = IntArray(height)
            var n = 0
            for ((start, step) in listOf(0 to 8, 4 to 8, 2 to 4, 1 to 2)) {
                var y = start
                while (y < height) {
                    out[n++] = y
                    y += step
                }
            }
            return out
        }
    }
}

/** One image of a GIF with what its Graphic Control Extension said about it. */
internal class GifFrame(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    val interlaced: Boolean,
    val palette: IntArray,
    val minCodeSize: Int,
    val data: ByteArray,
    val delayCs: Int,
    val disposal: Int,
    val transparentIndex: Int,
)

internal class GifScan(val width: Int, val height: Int, val frames: List<GifFrame>) {
    companion object {
        /** Walks the blocks of [bytes] and collects every frame; the LZW data is kept, not decoded. */
        fun scan(bytes: ByteArray): GifScan {
            val r = Reader(bytes)
            val header = r.string(6)
            if (header != "GIF87a" && header != "GIF89a") throw GifFormatException("not a GIF")
            val width = r.u16()
            val height = r.u16()
            if (width <= 0 || height <= 0 || width.toLong() * height > MAX_PIXELS) throw GifFormatException("bad size ${width}x$height")
            val packed = r.u8()
            r.u8() // background colour index: GIFs are shown on transparent here
            r.u8() // pixel aspect ratio
            val globalPalette = if (packed and 0x80 != 0) palette(r, 1 shl ((packed and 7) + 1)) else null

            val frames = ArrayList<GifFrame>()
            var delayCs = 0
            var disposal = 0
            var transparent = -1
            while (r.remaining() > 0) {
                when (val block = r.u8()) {
                    0x21 -> {
                        val label = r.u8()
                        if (label == 0xF9) {
                            val size = r.u8()
                            if (size < 4) throw GifFormatException("short graphic control extension")
                            val gcePacked = r.u8()
                            disposal = (gcePacked shr 2) and 7
                            delayCs = r.u16()
                            val index = r.u8()
                            transparent = if (gcePacked and 1 != 0) index else -1
                            r.skip(size - 4)
                            r.skipSubBlocks()
                        } else {
                            r.skipSubBlocks()
                        }
                    }
                    0x2C -> {
                        val left = r.u16()
                        val top = r.u16()
                        val w = r.u16()
                        val h = r.u16()
                        val fp = r.u8()
                        val local = if (fp and 0x80 != 0) palette(r, 1 shl ((fp and 7) + 1)) else null
                        val pal = local ?: globalPalette ?: throw GifFormatException("a frame has no colour table")
                        val minCode = r.u8()
                        if (minCode !in 2..8) throw GifFormatException("bad LZW code size $minCode")
                        val data = r.subBlocks()
                        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) throw GifFormatException("bad frame size ${w}x$h")
                        frames += GifFrame(left, top, w, h, fp and 0x40 != 0, pal, minCode, data, delayCs, disposal, transparent)
                        delayCs = 0
                        disposal = 0
                        transparent = -1
                    }
                    0x3B -> break
                    else -> throw GifFormatException("unexpected block 0x${block.toString(16)}")
                }
            }
            if (frames.isEmpty()) throw GifFormatException("no frames")
            return GifScan(width, height, frames)
        }

        private fun palette(r: Reader, size: Int): IntArray = IntArray(size) {
            val red = r.u8()
            val green = r.u8()
            val blue = r.u8()
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }

        private const val MAX_PIXELS = 64L * 1024 * 1024
    }

    private class Reader(private val b: ByteArray) {
        private var at = 0

        fun remaining() = b.size - at

        private fun need(n: Int) {
            if (n < 0 || at + n > b.size) throw GifFormatException("cut short")
        }

        fun u8(): Int {
            need(1)
            return b[at++].toInt() and 0xFF
        }

        fun u16(): Int = u8() or (u8() shl 8)

        fun skip(n: Int) {
            need(n)
            at += n
        }

        fun string(n: Int): String {
            need(n)
            val s = String(b, at, n, Charsets.ISO_8859_1)
            at += n
            return s
        }

        fun skipSubBlocks() {
            while (true) {
                val n = u8()
                if (n == 0) return
                skip(n)
            }
        }

        fun subBlocks(): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val n = u8()
                if (n == 0) break
                need(n)
                out.write(b, at, n)
                at += n
            }
            return out.toByteArray()
        }
    }
}

/** Variable-width LZW as GIF uses it (least significant bit first, codes of 3 to 12 bits). */
internal object GifLzw {
    /** Decodes [data] into [pixelCount] palette indices; missing data (a truncated file) leaves zeros. */
    fun decode(minCodeSize: Int, data: ByteArray, pixelCount: Int): ByteArray {
        val out = ByteArray(pixelCount)
        val clear = 1 shl minCodeSize
        val eoi = clear + 1
        val prefix = IntArray(MAX_CODES)
        val suffix = ByteArray(MAX_CODES)
        val stack = ByteArray(MAX_CODES + 1)
        var codeSize = minCodeSize + 1
        var next = eoi + 1
        var prev = -1
        var first = 0
        var bits = 0
        var bitCount = 0
        var pos = 0
        var written = 0
        for (i in 0 until clear) suffix[i] = i.toByte()
        while (written < pixelCount) {
            while (bitCount < codeSize) {
                if (pos >= data.size) return out
                bits = bits or ((data[pos++].toInt() and 0xFF) shl bitCount)
                bitCount += 8
            }
            var code = bits and ((1 shl codeSize) - 1)
            bits = bits ushr codeSize
            bitCount -= codeSize
            if (code == clear) {
                codeSize = minCodeSize + 1
                next = eoi + 1
                prev = -1
                continue
            }
            if (code == eoi) break
            if (prev == -1) {
                if (code >= clear) return out
                out[written++] = suffix[code]
                prev = code
                first = code
                continue
            }
            val incoming = code
            var top = 0
            if (code >= next) {
                // The code being defined right now: previous string plus its own first byte.
                stack[top++] = first.toByte()
                code = prev
            }
            while (code >= clear) {
                if (top >= stack.size - 1) return out
                stack[top++] = suffix[code]
                code = prefix[code]
            }
            first = suffix[code].toInt() and 0xFF
            stack[top++] = first.toByte()
            if (next < MAX_CODES) {
                prefix[next] = prev
                suffix[next] = first.toByte()
                next++
                if (next == (1 shl codeSize) && codeSize < MAX_CODE_SIZE) codeSize++
            }
            prev = incoming
            while (top > 0 && written < pixelCount) out[written++] = stack[--top]
        }
        return out
    }

    private const val MAX_CODE_SIZE = 12
    private const val MAX_CODES = 1 shl MAX_CODE_SIZE
}

/**
 * The frame delays of an animated WebP, read from its RIFF container without decoding any picture: the file is
 * animated when its VP8X header sets the animation flag, and every ANMF chunk carries one frame's duration.
 */
object WebpAnimationScan {
    /** Raw delay of every frame in milliseconds, or an empty list when [bytes] is not an animated WebP. */
    fun delaysMs(bytes: ByteArray): List<Int> {
        if (bytes.size < 12 || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WEBP") return emptyList()
        var at = 12
        var animated = false
        val delays = ArrayList<Int>()
        while (at + 8 <= bytes.size) {
            val kind = tag(bytes, at)
            val size = u32(bytes, at + 4)
            val body = at + 8
            if (size < 0 || body + size > bytes.size) {
                // A cut-off file: keep what was read so far for ANMF chunks that fit completely.
                break
            }
            when (kind) {
                "VP8X" -> if (size >= 1) animated = bytes[body].toInt() and ANIMATION_FLAG != 0
                // x(3) y(3) width-1(3) height-1(3) duration(3) flags(1)
                "ANMF" -> if (size >= ANMF_HEADER) {
                    delays += (bytes[body + 12].toInt() and 0xFF) or ((bytes[body + 13].toInt() and 0xFF) shl 8) or
                        ((bytes[body + 14].toInt() and 0xFF) shl 16)
                }
            }
            at = body + size + (size and 1) // chunks are padded to an even length
        }
        return if (animated) delays else emptyList()
    }

    private fun tag(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)

    private fun u32(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16) or
            ((b[at + 3].toInt() and 0xFF) shl 24)

    private const val ANIMATION_FLAG = 0x02
    private const val ANMF_HEADER = 16
}

/** Reads the delays of a GIF without decoding any frame. */
object GifDelayScan {
    /** Raw delay of every frame in milliseconds, or an empty list when [bytes] is not a readable GIF. */
    fun delaysMs(bytes: ByteArray): List<Int> = try {
        GifScan.scan(bytes).frames.map { it.delayCs * 10 }
    } catch (e: GifFormatException) {
        emptyList()
    }
}

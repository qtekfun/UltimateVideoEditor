package com.qtekfun.ultimatevideoeditor.engine.still

/** The file is not an animated WebP this reader can use. The message is fit to log; the caller shows the first frame. */
class WebpFormatException(message: String) : Exception(message)

/** One chunk of a frame's payload: where its body sits in the file and how long it is (without the pad byte). */
internal class WebpChunk(val fourcc: String, val offset: Int, val size: Int)

/**
 * One ANMF frame of an animated WebP: where it sits on the canvas, how long it shows, how it is blended onto the
 * canvas and what happens to its rectangle afterwards, and the chunks that hold its picture (an optional ALPH, then
 * VP8 or VP8L).
 */
class WebpFrame internal constructor(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    /** Raw duration in milliseconds, as stored (0 is allowed in the file; the timing code normalises it). */
    val durationMs: Int,
    /** True: alpha-blend over the canvas. False: overwrite the rectangle. */
    val blend: Boolean,
    /** True: clear the rectangle to transparent before the next frame is drawn. */
    val disposeToBackground: Boolean,
    internal val chunks: List<WebpChunk>,
)

/** The container of an animated WebP: canvas, background colour hint, loop count and the frames. */
class WebpContainer internal constructor(
    val canvasWidth: Int,
    val canvasHeight: Int,
    /** The ANIM chunk's background colour as ARGB; informational, frames are composited on transparent. */
    val backgroundArgb: Int,
    /** 0 means loop forever. */
    val loopCount: Int,
    val frames: List<WebpFrame>,
)

/**
 * Reads the RIFF container of an animated WebP without decoding any picture: VP8X flags and canvas size, the ANIM
 * background and loop count, and each ANMF frame header and payload chunks. Every size is checked against the file
 * and against the limits below, so a corrupt or hostile file fails with [WebpFormatException] instead of reading out
 * of bounds or allocating without bound.
 */
object WebpContainerParser {
    const val MAX_FRAMES = 4096
    const val MAX_CANVAS_PIXELS = 64L * 1024 * 1024

    fun parse(bytes: ByteArray): WebpContainer {
        if (bytes.size < RIFF_HEADER || tag(bytes, 0) != "RIFF" || tag(bytes, 8) != "WEBP") throw WebpFormatException("not a WebP")
        val riffSize = u32(bytes, 4)
        // The RIFF size covers everything after its own field; a file longer than that has trailing bytes to ignore.
        val end = if (riffSize in 4..(bytes.size - 8)) riffSize + 8 else bytes.size
        var at = RIFF_HEADER
        var animated = false
        var canvasW = 0
        var canvasH = 0
        var haveVp8x = false
        var background = 0
        var loops = 0
        val frames = ArrayList<WebpFrame>()
        while (at + CHUNK_HEADER <= end) {
            val kind = tag(bytes, at)
            val size = u32(bytes, at + 4)
            val body = at + CHUNK_HEADER
            if (size < 0 || size.toLong() + body > end) throw WebpFormatException("chunk $kind runs past the end of the file")
            when (kind) {
                "VP8X" -> {
                    if (size < VP8X_SIZE) throw WebpFormatException("short VP8X chunk")
                    animated = bytes[body].toInt() and ANIMATION_FLAG != 0
                    canvasW = 1 + u24(bytes, body + 4)
                    canvasH = 1 + u24(bytes, body + 7)
                    if (canvasW.toLong() * canvasH > MAX_CANVAS_PIXELS) throw WebpFormatException("canvas ${canvasW}x$canvasH is too large")
                    haveVp8x = true
                }
                "ANIM" -> {
                    if (size < ANIM_SIZE) throw WebpFormatException("short ANIM chunk")
                    // Stored blue, green, red, alpha.
                    val b = bytes[body].toInt() and 0xFF
                    val g = bytes[body + 1].toInt() and 0xFF
                    val r = bytes[body + 2].toInt() and 0xFF
                    val a = bytes[body + 3].toInt() and 0xFF
                    background = (a shl 24) or (r shl 16) or (g shl 8) or b
                    loops = (bytes[body + 4].toInt() and 0xFF) or ((bytes[body + 5].toInt() and 0xFF) shl 8)
                }
                "ANMF" -> {
                    if (!haveVp8x) throw WebpFormatException("a frame comes before the VP8X header")
                    if (frames.size >= MAX_FRAMES) throw WebpFormatException("more than $MAX_FRAMES frames")
                    frames += frame(bytes, body, size, canvasW, canvasH)
                }
            }
            at = body + size + (size and 1) // chunks are padded to an even length
        }
        if (!haveVp8x || !animated) throw WebpFormatException("not an animated WebP")
        if (frames.isEmpty()) throw WebpFormatException("no frames")
        return WebpContainer(canvasW, canvasH, background, loops, frames)
    }

    private fun frame(bytes: ByteArray, body: Int, size: Int, canvasW: Int, canvasH: Int): WebpFrame {
        if (size < ANMF_HEADER) throw WebpFormatException("short ANMF chunk")
        val x = 2 * u24(bytes, body)
        val y = 2 * u24(bytes, body + 3)
        val w = 1 + u24(bytes, body + 6)
        val h = 1 + u24(bytes, body + 9)
        val duration = u24(bytes, body + 12)
        val flags = bytes[body + 15].toInt() and 0xFF
        if (x + w > canvasW || y + h > canvasH) throw WebpFormatException("a frame (${w}x$h at $x,$y) lies outside the ${canvasW}x$canvasH canvas")
        val chunks = ArrayList<WebpChunk>()
        val end = body + size
        var at = body + ANMF_HEADER
        while (at + CHUNK_HEADER <= end) {
            val kind = tag(bytes, at)
            val len = u32(bytes, at + 4)
            val start = at + CHUNK_HEADER
            if (len < 0 || len.toLong() + start > end) throw WebpFormatException("chunk $kind runs past its frame")
            if (kind == "ALPH" || kind == "VP8 " || kind == "VP8L") chunks += WebpChunk(kind, start, len)
            at = start + len + (len and 1)
        }
        val picture = chunks.count { it.fourcc == "VP8 " || it.fourcc == "VP8L" }
        if (picture != 1) throw WebpFormatException("a frame must hold exactly one VP8 or VP8L picture")
        return WebpFrame(
            x = x, y = y, width = w, height = h, durationMs = duration,
            blend = flags and BLEND_NO == 0,
            disposeToBackground = flags and DISPOSE_BACKGROUND != 0,
            chunks = chunks,
        )
    }

    /**
     * Frame [frame] of the file as a standalone still WebP the platform's static decoder can read: a RIFF 'WEBP'
     * with a VP8X header sized to the frame (alpha flag set when the frame has an ALPH chunk or its VP8L header
     * says it uses alpha), the ALPH chunk if any, and the VP8 or VP8L chunk, every chunk padded to an even length.
     */
    fun standalone(bytes: ByteArray, frame: WebpFrame): ByteArray {
        val picture = frame.chunks.first { it.fourcc == "VP8 " || it.fourcc == "VP8L" }
        val alpha = frame.chunks.firstOrNull { it.fourcc == "ALPH" }
        val hasAlpha = alpha != null || (picture.fourcc == "VP8L" && vp8lUsesAlpha(bytes, picture))
        val parts = listOfNotNull(alpha, picture)
        var payload = 4 + CHUNK_HEADER + VP8X_SIZE // "WEBP" + VP8X chunk
        for (c in parts) payload += CHUNK_HEADER + c.size + (c.size and 1)
        val out = ByteArray(8 + payload)
        var at = 0
        at = put(out, at, "RIFF")
        at = putU32(out, at, payload)
        at = put(out, at, "WEBP")
        at = put(out, at, "VP8X")
        at = putU32(out, at, VP8X_SIZE)
        out[at] = (if (hasAlpha) ALPHA_FLAG else 0).toByte()
        at += 4 // flags and three reserved bytes
        at = putU24(out, at, frame.width - 1)
        at = putU24(out, at, frame.height - 1)
        for (c in parts) {
            at = put(out, at, c.fourcc)
            at = putU32(out, at, c.size)
            System.arraycopy(bytes, c.offset, out, at, c.size)
            at += c.size + (c.size and 1) // the pad byte is already zero
        }
        check(at == out.size) { "wrote $at of ${out.size} bytes" }
        return out
    }

    // VP8L header: signature 0x2F, then 14 bits width-1, 14 bits height-1, 1 bit alpha_is_used, 3 bits version.
    private fun vp8lUsesAlpha(bytes: ByteArray, c: WebpChunk): Boolean {
        if (c.size < VP8L_HEADER || bytes[c.offset].toInt() and 0xFF != VP8L_SIGNATURE) return false
        return (u32(bytes, c.offset + 1) ushr 28) and 1 == 1
    }

    private fun tag(b: ByteArray, at: Int) = String(b, at, 4, Charsets.ISO_8859_1)

    private fun u24(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or ((b[at + 2].toInt() and 0xFF) shl 16)

    /** Little-endian 32-bit value; a value above Int.MAX_VALUE reads as negative and is rejected by the callers. */
    private fun u32(b: ByteArray, at: Int): Int = u24(b, at) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun put(out: ByteArray, at: Int, text: String): Int {
        for (i in text.indices) out[at + i] = text[i].code.toByte()
        return at + text.length
    }

    private fun putU24(out: ByteArray, at: Int, v: Int): Int {
        out[at] = v.toByte()
        out[at + 1] = (v shr 8).toByte()
        out[at + 2] = (v shr 16).toByte()
        return at + 3
    }

    private fun putU32(out: ByteArray, at: Int, v: Int): Int {
        putU24(out, at, v)
        out[at + 3] = (v shr 24).toByte()
        return at + 4
    }

    private const val RIFF_HEADER = 12
    private const val CHUNK_HEADER = 8
    private const val VP8X_SIZE = 10
    private const val ANIM_SIZE = 6
    private const val ANMF_HEADER = 16
    private const val VP8L_HEADER = 5
    private const val VP8L_SIGNATURE = 0x2F
    private const val ANIMATION_FLAG = 0x02
    private const val ALPHA_FLAG = 0x10
    private const val BLEND_NO = 0x02 // bit 1: 1 = do not blend
    private const val DISPOSE_BACKGROUND = 0x01 // bit 0: 1 = dispose to background
}

/** A decoded picture: [argb] holds width x height straight-alpha pixels, top row first. */
class WebpPixels(val width: Int, val height: Int, val argb: IntArray) {
    init {
        require(width > 0 && height > 0 && argb.size == width * height) { "pixel array does not match ${width}x$height" }
    }
}

/** Decodes one standalone still WebP into pixels. The Android one uses the platform's decoder; tests use a fake. */
fun interface WebpStillDecoder {
    /** @throws WebpFormatException when the picture cannot be decoded. */
    fun decode(webp: ByteArray): WebpPixels
}

/**
 * An animated WebP read into frames, without Android classes so it runs on the JVM in tests. Each frame's picture is
 * decoded by [decoder] (the platform handles lossy, lossless and alpha) from a standalone file built by
 * [WebpContainerParser.standalone]; this class composites them on a transparent canvas with the container's blend and
 * dispose bits. [render] returns the canvas after frame `index`, replaying from the start only for an earlier frame
 * than the last one drawn, so playing forward costs one frame per step.
 */
class WebpAnimation(
    private val bytes: ByteArray,
    private val container: WebpContainer,
    private val decoder: WebpStillDecoder,
) : AnimatedPicture {
    override val width: Int get() = container.canvasWidth
    override val height: Int get() = container.canvasHeight
    override val frameCount: Int get() = container.frames.size
    val loopCount: Int get() = container.loopCount

    /** Delay of every frame as the file states it, in milliseconds. */
    override val rawDelaysMs: List<Int> get() = container.frames.map { it.durationMs }

    override var framesDrawn: Long = 0
        private set

    private var canvas = IntArray(width * height)
    private var drawn = -1
    private val snapshots = CanvasSnapshots(container.frames.size, width * height)

    /** The composited picture after frame [index]; the array is a copy the caller owns. */
    @Synchronized
    override fun render(index: Int): IntArray {
        require(index in container.frames.indices) { "frame $index of ${container.frames.size}" }
        if (index < drawn) {
            val near = snapshots.floor(index)
            if (near == null) {
                reset()
            } else {
                canvas = near.second.canvas.copyOf()
                drawn = near.first
            }
        }
        while (drawn < index) drawNext()
        return canvas.copyOf()
    }

    private fun reset() {
        canvas = IntArray(width * height)
        drawn = -1
    }

    private fun drawNext() {
        if (drawn >= 0) {
            val prev = container.frames[drawn]
            if (prev.disposeToBackground) clearRect(prev)
        }
        val next = container.frames[drawn + 1]
        val pixels = decoder.decode(WebpContainerParser.standalone(bytes, next))
        if (pixels.width != next.width || pixels.height != next.height) {
            throw WebpFormatException("a frame decoded as ${pixels.width}x${pixels.height}, expected ${next.width}x${next.height}")
        }
        draw(next, pixels)
        drawn++
        framesDrawn++
        snapshots.offer(drawn, canvas, null)
    }

    private fun clearRect(f: WebpFrame) {
        for (y in f.y until f.y + f.height) {
            for (x in f.x until f.x + f.width) canvas[y * width + x] = 0
        }
    }

    private fun draw(f: WebpFrame, pixels: WebpPixels) {
        for (row in 0 until f.height) {
            val dst = (f.y + row) * width + f.x
            val src = row * f.width
            for (col in 0 until f.width) {
                val s = pixels.argb[src + col]
                canvas[dst + col] = if (f.blend) over(canvas[dst + col], s) else s
            }
        }
    }

    companion object {
        /** Parses [bytes]. @throws WebpFormatException when it is not an animated WebP. */
        fun parse(bytes: ByteArray, decoder: WebpStillDecoder): WebpAnimation =
            WebpAnimation(bytes, WebpContainerParser.parse(bytes), decoder)

        /** Source-over of straight-alpha [src] on straight-alpha [dst], rounded. */
        internal fun over(dst: Int, src: Int): Int {
            val sa = src ushr 24
            if (sa == 255) return src
            if (sa == 0) return dst
            val da = dst ushr 24
            if (da == 0) return src
            // Alpha scaled to 0..255*255 to keep the arithmetic in integers.
            val outA255 = sa * 255 + da * (255 - sa) // 0..65025
            val outA = (outA255 + 127) / 255
            fun channel(shift: Int): Int {
                val s = (src ushr shift) and 0xFF
                val d = (dst ushr shift) and 0xFF
                val num = s.toLong() * sa * 255 + d.toLong() * da * (255 - sa)
                return ((num + outA255 / 2) / outA255).toInt().coerceIn(0, 255)
            }
            return (outA.coerceIn(0, 255) shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}

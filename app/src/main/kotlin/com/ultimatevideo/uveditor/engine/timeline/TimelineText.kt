package com.ultimatevideo.uveditor.engine.timeline

import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The text of the native timeline canvas. The canvas draws text from a texture atlas of small bitmaps; this side makes
 * the bitmaps with the system font (so accents, symbols, emoji and every script the device has come out right and sharp
 * at the screen's density) and hands them to the canvas by a hash of their text. No third-party font or service is
 * involved. The pixels are made on a background thread, never on the render thread or the main thread.
 */
const val TEXT_CLASS_LABEL = 0
const val TEXT_CLASS_SMALL = 1
const val TEXT_CLASS_BOLD = 2

/** One bitmap the canvas will want: [text] at size class [sizeClass] (see the constants above). */
data class LabelNeed(val text: String, val sizeClass: Int)

/** Premultiplied RGBA pixels, `width * height * 4` bytes, row by row. [colour] is true for coloured glyphs such as emoji. */
class RasterLabel(val width: Int, val height: Int, val colour: Boolean, val pixels: ByteBuffer)

fun interface LabelRasteriser {
    /** The bitmap for [need], or null when it cannot be made (empty text, out of memory). */
    fun render(need: LabelNeed): RasterLabel?
}

/**
 * The canvas side: takes bitmaps and tells which generation of its atlas it is on (a new one means it dropped them all).
 * Between generations the atlas also evicts the bitmaps it has used least when it needs room: [takeEvicted] reports them.
 */
interface LabelSink {
    fun putLabel(hash: Long, label: RasterLabel)

    /** Fills [out] with hashes of bitmaps the canvas evicted since the last call (each reported once); returns how many. */
    fun takeEvicted(out: LongArray): Int = 0

    fun labelGeneration(): Int
}

object LabelHash {
    private const val OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val PRIME = 0x100000001b3L

    /** FNV-1a over the size class byte then the UTF-8 bytes; the same function as `labelHash` in text_atlas.h. */
    fun of(text: String, sizeClass: Int): Long {
        var h = OFFSET
        h = (h xor (sizeClass and 0xFF).toLong()) * PRIME
        for (b in text.toByteArray(Charsets.UTF_8)) h = (h xor (b.toLong() and 0xFF)) * PRIME
        return h
    }
}

/** The glyphs the ruler, lane headers, speed badges and the playhead tag compose their text from. */
object StaticGlyphs {
    val needs: List<LabelNeed> = buildList {
        for (ch in "0123456789:+.-x<|") add(LabelNeed(ch.toString(), TEXT_CLASS_SMALL))
        for (ch in "0123456789:VATMS") add(LabelNeed(ch.toString(), TEXT_CLASS_BOLD))
    }
    private val set = needs.toHashSet()

    fun contains(need: LabelNeed): Boolean = need in set
}

/**
 * The size class a label is drawn at: the text of a title and the name of a sticker sit in the body of their block at
 * the regular size; the name of a media clip (in its header strip) and a marker's name are small. The canvas applies
 * the same rule (`labelClassOf` in timeline_renderer.cpp).
 */
internal fun TimelineSnapshot.labelNeeds(): List<LabelNeed> {
    val byKey = clips.associateBy { it.clipKey }
    return labels.mapNotNull { label ->
        if (label.text.isEmpty()) return@mapNotNull null
        val clip = byKey[label.clipKey]
        val bodyText = clip != null && (tracks[clip.trackIndex] == SnapshotTrackType.TITLE || clip.kind == SnapshotClipKind.STICKER)
        LabelNeed(label.text, if (bodyText) TEXT_CLASS_LABEL else TEXT_CLASS_SMALL)
    }
}

/**
 * Keeps the canvas supplied with bitmaps: [request] with what the current snapshot needs, and whatever the canvas does
 * not have yet is rasterised on [executor] (one thread) and sent. A bitmap is sent once; if the canvas drops its atlas
 * (its generation changes) everything still needed is sent again. At most [maxLabels] distinct bitmaps are sent per
 * generation so a huge project cannot overflow the atlas; the rest stay on the canvas's plain fallback font.
 */
class LabelPump(
    private val rasteriser: LabelRasteriser,
    private val sink: LabelSink,
    private val executor: Executor,
    private val maxLabels: Int = 700,
) {
    private val sent = HashSet<Long>()
    private val evictedScratch = LongArray(256)
    private var generation = Int.MIN_VALUE
    private val pending = AtomicReference<List<LabelNeed>?>(null)
    private val running = AtomicBoolean(false)

    /** Replaces the wanted set; cheap enough to call on every snapshot. */
    fun request(needs: List<LabelNeed>) {
        pending.set(needs)
        if (running.compareAndSet(false, true)) executor.execute(::drain)
    }

    private fun drain() {
        try {
            while (true) {
                val needs = pending.getAndSet(null) ?: break
                sendMissing(StaticGlyphs.needs + needs)
            }
        } finally {
            running.set(false)
            // A request that arrived between the last check and the flag going down must not be lost.
            if (pending.get() != null && running.compareAndSet(false, true)) executor.execute(::drain)
        }
    }

    /** Forgets that the bitmaps the canvas evicted were sent, so the next pass sends them again if they are still needed. */
    private fun forgetEvicted(): Boolean {
        var any = false
        while (true) {
            val n = sink.takeEvicted(evictedScratch)
            if (n <= 0) return any
            for (i in 0 until n) sent.remove(evictedScratch[i])
            any = true
            if (n < evictedScratch.size) return true
        }
    }

    internal fun sendMissing(needs: List<LabelNeed>) {
        repeat(2) {
            val gen = sink.labelGeneration()
            if (gen != generation) {
                sent.clear()
                generation = gen
            }
            forgetEvicted()
            for (need in needs) {
                if (sent.size >= maxLabels && !StaticGlyphs.contains(need)) continue
                val hash = LabelHash.of(need.text, need.sizeClass)
                if (!sent.add(hash)) continue
                val raster = rasteriser.render(need) ?: continue
                sink.putLabel(hash, raster)
            }
            // The canvas dropped its atlas, or evicted some of what we just sent, while we were sending: send it again.
            if (sink.labelGeneration() == generation && !forgetEvicted()) return
        }
    }
}

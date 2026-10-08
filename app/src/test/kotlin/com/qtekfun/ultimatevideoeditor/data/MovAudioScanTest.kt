package com.qtekfun.ultimatevideoeditor.data

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The scan that tells the probe an iPhone .mov has a linear-PCM soundtrack the platform extractor does not list. */
class MovAudioScanTest {
    private fun bytes(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()
    private fun ByteArrayOutputStream.u32(v: Long) = repeat(4) { write((v shr (8 * (3 - it))).toInt() and 0xFF) }
    private fun ByteArrayOutputStream.tag(t: String) = write(t.toByteArray(Charsets.ISO_8859_1))

    private fun box(type: String, payload: ByteArray) = bytes { u32(payload.size + 8L); tag(type); write(payload) }
    private fun box(type: String, vararg parts: ByteArray) = box(type, bytes { parts.forEach { write(it) } })

    private fun hdlr(handler: String) = box("hdlr", bytes { u32(0); u32(0); tag(handler); write(ByteArray(12)) })
    private fun mdhd(scale: Long, duration: Long) = box("mdhd", bytes { u32(0); u32(0); u32(0); u32(scale); u32(duration); u32(0) })
    private fun stsd(entryType: String) = box(
        "stsd",
        bytes {
            u32(0); u32(1)
            val entry = bytes { tag(entryType); write(ByteArray(8)) }
            u32(entry.size + 4L); write(entry)
        },
    )
    private fun trak(handler: String, entryType: String, scale: Long = 48000, duration: Long = 96000) =
        box("trak", box("mdia", mdhd(scale, duration), hdlr(handler), box("minf", box("stbl", stsd(entryType)))))

    private fun source(data: ByteArray) = object : ByteSource {
        override val size = data.size.toLong()
        override fun read(offset: Long, length: Int) =
            if (offset < 0 || offset + length > data.size) null else data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun mov(vararg traks: ByteArray) = bytes {
        write(box("ftyp", ByteArray(12)))
        write(box("mdat", ByteArray(5000)))
        write(box("moov", *traks))
    }

    @Test
    fun `finds an lpcm sound track after the video track and reads its duration`() {
        val found = MovAudioScan.find(source(mov(trak("vide", "hvc1", 600, 6000), trak("soun", "lpcm"))))
        assertNotNull(found)
        assertEquals(2_000_000L, found!!.durationMicros)
    }

    @Test
    fun `recognises the older QuickTime PCM entry types`() {
        for (type in listOf("sowt", "twos", "in24", "in32", "fl32", "fl64", "raw ")) {
            assertNotNull(type, MovAudioScan.find(source(mov(trak("soun", type)))))
        }
    }

    @Test
    fun `compressed or missing audio is not reported`() {
        assertNull(MovAudioScan.find(source(mov(trak("soun", "mp4a")))))
        assertNull(MovAudioScan.find(source(mov(trak("vide", "hvc1")))))
        // A video track whose sample entry happens to be called 'lpcm' is still video.
        assertNull(MovAudioScan.find(source(mov(trak("vide", "lpcm")))))
    }

    @Test
    fun `a file that is not a movie, or is cut short, is not reported and does not throw`() {
        assertNull(MovAudioScan.find(source(ByteArray(100) { 0x42 })))
        assertNull(MovAudioScan.find(source(ByteArray(0))))
        val whole = mov(trak("soun", "lpcm"))
        assertNull(MovAudioScan.find(source(whole.copyOf(whole.size - 40))))
        // A moov placed first (fast-start layout) is found without walking mdat.
        val moovFirst = bytes { write(box("ftyp", ByteArray(12))); write(box("moov", trak("soun", "sowt"))); write(box("mdat", ByteArray(100))) }
        assertNotNull(MovAudioScan.find(source(moovFirst)))
    }
}

package com.ultimatevideo.uveditor.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * The base track of a project exported with no sound: the clips' audio was uncompressed PCM in a MOV, which Android's extractor
 * does not list, so the probe said hasAudio=false. Table-driven over every PCM flavour, in the box layouts real writers use, and
 * (when ffmpeg is installed) over files ffmpeg really writes. Compressed audio must stay with the extractor: the scan finds PCM only.
 * `scripts/qa-smoke.sh` then exports such a file on a device and measures the sound in the result.
 */
class MovAudioScanTableTest {
    private fun bytes(block: ByteArrayOutputStream.() -> Unit) = ByteArrayOutputStream().apply(block).toByteArray()
    private fun ByteArrayOutputStream.u32(v: Long) = repeat(4) { write((v shr (8 * (3 - it))).toInt() and 0xFF) }
    private fun ByteArrayOutputStream.u64(v: Long) = repeat(8) { write((v shr (8 * (7 - it))).toInt() and 0xFF) }
    private fun ByteArrayOutputStream.tag(t: String) = write(t.toByteArray(Charsets.ISO_8859_1))

    private fun box(type: String, payload: ByteArray) = bytes { u32(payload.size + 8L); tag(type); write(payload) }
    private fun box(type: String, vararg parts: ByteArray) = box(type, bytes { parts.forEach { write(it) } })
    private fun bigBox(type: String, payload: ByteArray) = bytes { u32(1); tag(type); u64(payload.size + 16L); write(payload) }

    private fun hdlr(handler: String) = box("hdlr", bytes { u32(0); u32(0); tag(handler); write(ByteArray(12)) })
    private fun mdhd(version: Int, scale: Long, duration: Long) = box(
        "mdhd",
        bytes {
            if (version == 1) { u32(1L shl 24); u64(0); u64(0); u32(scale); u64(duration); u32(0) } else { u32(0); u32(0); u32(0); u32(scale); u32(duration); u32(0) }
        },
    )

    /** A sample entry as the writers do: size, type, 6 reserved bytes, data reference index, then the audio fields (version 0). */
    private fun stsd(entryType: String, channels: Int = 2, bits: Int = 16, rate: Long = 48000) = box(
        "stsd",
        bytes {
            u32(0); u32(1)
            val entry = bytes {
                tag(entryType); write(ByteArray(6)); write(byteArrayOf(0, 1))
                write(ByteArray(8)); write(byteArrayOf(0, channels.toByte())); write(byteArrayOf(0, bits.toByte())); write(ByteArray(4))
                write(byteArrayOf((rate shr 8).toByte(), rate.toByte(), 0, 0))
            }
            u32(entry.size + 4L); write(entry)
        },
    )

    private fun trak(handler: String, entryType: String, version: Int = 0, scale: Long = 48000, duration: Long = 144000) =
        box("trak", box("tkhd", ByteArray(84)), box("mdia", mdhd(version, scale, duration), hdlr(handler), box("minf", box("stbl", stsd(entryType)))))

    private fun source(data: ByteArray) = object : ByteSource {
        override val size = data.size.toLong()
        override fun read(offset: Long, length: Int) =
            if (offset < 0 || offset + length > data.size) null else data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    private fun file(layout: String, vararg traks: ByteArray): ByteArray {
        val ftyp = box("ftyp", bytes { tag("qt  "); u32(0); tag("qt  ") })
        val moov = box("moov", box("mvhd", ByteArray(100)), *traks, box("udta", ByteArray(40)))
        val mdat = box("mdat", ByteArray(20_000))
        return when (layout) {
            "moov last" -> ftyp + mdat + moov
            "moov first" -> ftyp + moov + mdat
            "wide before moov" -> ftyp + box("wide", ByteArray(0)) + bigBox("mdat", ByteArray(20_000)) + moov
            "free padding" -> ftyp + box("free", ByteArray(500)) + moov + mdat
            else -> error(layout)
        }
    }

    // ffmpeg's names for the PCM codecs in a MOV and the sample entry each one writes.
    private val pcmEntries = mapOf(
        "pcm_s16le" to "sowt",
        "pcm_s16be" to "twos",
        "pcm_s24le" to "in24",
        "pcm_s32le" to "in32",
        "pcm_f32le" to "fl32",
        "pcm_f64le" to "fl64",
        "iPhone lpcm" to "lpcm",
    )

    @Test
    fun `every PCM flavour is found in every layout, whatever the track order`() {
        for ((codec, entry) in pcmEntries) {
            for (layout in listOf("moov last", "moov first", "wide before moov", "free padding")) {
                for (order in listOf("video first", "audio first", "two videos before")) {
                    val video = trak("vide", "hvc1", scale = 600, duration = 1800)
                    val audio = trak("soun", entry)
                    val traks = when (order) {
                        "video first" -> arrayOf(video, audio)
                        "audio first" -> arrayOf(audio, video)
                        else -> arrayOf(video, trak("vide", "avc1"), audio)
                    }
                    val found = MovAudioScan.find(source(file(layout, *traks)))
                    assertNotNull("$codec ($entry), $layout, $order", found)
                    assertEquals("$codec ($entry), $layout, $order: duration", 3_000_000L, found!!.durationMicros)
                }
            }
        }
    }

    @Test
    fun `a 64 bit media header gives the same duration`() {
        val found = MovAudioScan.find(source(file("moov last", trak("vide", "hvc1"), trak("soun", "sowt", version = 1, scale = 44100, duration = 88200))))
        assertEquals(2_000_000L, found!!.durationMicros)
    }

    @Test
    fun `compressed audio is left to the extractor`() {
        for (entry in listOf("mp4a", "ac-3", "ec-3", "alac", ".mp3", "Opus", "samr", "sawb")) {
            assertNull(entry, MovAudioScan.find(source(file("moov last", trak("vide", "avc1"), trak("soun", entry)))))
        }
    }

    @Test
    fun `no sound track at all is not reported, nor is a picture only file`() {
        assertNull(MovAudioScan.find(source(file("moov last", trak("vide", "hvc1")))))
        assertNull(MovAudioScan.find(source(file("moov first", trak("vide", "avc1"), trak("text", "tx3g")))))
    }

    @Test
    fun `damaged files never throw`() {
        val whole = file("moov last", trak("vide", "hvc1"), trak("soun", "sowt"))
        for (cut in listOf(0, 7, 8, 40, whole.size / 2, whole.size - 200, whole.size - 1)) {
            // Any outcome but an exception is fine for a damaged file; a cut after the whole moov still finds it.
            MovAudioScan.find(source(whole.copyOf(cut)))
        }
        val garbage = ByteArray(4096) { (it * 31).toByte() }
        MovAudioScan.find(source(garbage))
    }

    @Test
    fun `files written by ffmpeg are found when ffmpeg is installed`() {
        assumeTrue("ffmpeg not installed", ffmpegAvailable())
        val dir = java.nio.file.Files.createTempDirectory("uv-mov-scan").toFile()
        try {
            for (codec in listOf("pcm_s16le", "pcm_s16be", "pcm_s24le", "pcm_s32le", "pcm_f32le")) {
                val out = File(dir, "$codec.mov")
                assertEquals("ffmpeg could not write $codec", 0, ffmpeg(out, "-c:v", "libx264", "-c:a", codec))
                val found = RandomAccessFile(out, "r").use { MovAudioScan.find(fileSource(it)) }
                assertNotNull("$codec in a MOV must be found", found)
                assertEquals(codec, 1_000_000.0, found!!.durationMicros.toDouble(), 100_000.0)
            }
            // AAC in an MP4 and in a MOV is what the platform extractor lists: not this scan's business.
            for (name in listOf("a.mp4", "a.mov")) {
                val out = File(dir, name)
                assertEquals(0, ffmpeg(out, "-c:v", "libx264", "-c:a", "aac"))
                assertNull(name, RandomAccessFile(out, "r").use { MovAudioScan.find(fileSource(it)) })
            }
            // Video without sound.
            val silent = File(dir, "silent.mov")
            assertEquals(0, ffmpeg(silent, "-c:v", "libx264", "-an"))
            assertNull(RandomAccessFile(silent, "r").use { MovAudioScan.find(fileSource(it)) })
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun fileSource(file: RandomAccessFile) = object : ByteSource {
        override val size = file.length()
        override fun read(offset: Long, length: Int): ByteArray? {
            val buffer = ByteArray(length)
            return try {
                file.seek(offset)
                file.readFully(buffer)
                buffer
            } catch (e: java.io.IOException) {
                null
            }
        }
    }

    private fun ffmpegAvailable(): Boolean = try {
        ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().let { it.inputStream.readBytes(); it.waitFor() == 0 }
    } catch (e: java.io.IOException) {
        false
    }

    private fun ffmpeg(out: File, vararg codecs: String): Int {
        val command = listOf(
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "color=c=blue:s=160x120:r=10:d=1", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1",
        ) + codecs + listOf("-pix_fmt", "yuv420p", "-shortest", out.path)
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly()
        return process.exitValue()
    }
}

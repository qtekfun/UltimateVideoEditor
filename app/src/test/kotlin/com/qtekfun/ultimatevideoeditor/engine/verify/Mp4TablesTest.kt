package com.qtekfun.ultimatevideoeditor.engine.verify

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class Mp4TablesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the reader finds the samples of a built file`() {
        val built = Mp4TestBuilder(videoFrames = 90, fpsNum = 30, audioSamples = 140).build()

        val file = Mp4Reader.read(ArrayByteSource(built.bytes))
        val video = checkNotNull(file.video)

        assertEquals(90, video.count)
        assertEquals(built.videoOffsets.toList(), video.offsets.toList())
        assertEquals(built.videoSizes.toList(), video.sizes.toList())
        assertEquals(33_333L, video.presentationUs(1))
        assertEquals(3_000_000L, video.durationUs)
        assertEquals(listOf(0, 30, 60), video.syncSamples.toList())
        assertEquals(4, video.nalLengthSize)
        assertEquals(140, checkNotNull(file.audio).count)
        assertEquals(built.moovOffset, file.moovOffset)
    }

    @Test
    fun `a file without its index is refused with a reason`() {
        val built = Mp4TestBuilder(videoFrames = 30).build()
        val cut = built.bytes.copyOf(built.moovOffset.toInt() + 20)

        try {
            Mp4Reader.read(ArrayByteSource(cut))
            fail("expected an Mp4FormatException")
        } catch (e: Mp4FormatException) {
            assertTrue(e.message, e.message!!.contains("moov"))
        }
    }

    @Test
    fun `a file of zeros is refused`() {
        try {
            Mp4Reader.read(ArrayByteSource(ByteArray(5000)))
            fail("expected an Mp4FormatException")
        } catch (e: Mp4FormatException) {
            assertTrue(e.message, e.message!!.isNotEmpty())
        }
    }

    // ---- real files written by ffmpeg, damaged on disk (skipped without ffmpeg) ----

    private val total = 300L
    private val exp = VerifyExpectation(total, 30, 1, hasAudio = true, hdr = false)

    private fun ffmpeg(vararg args: String): Boolean = try {
        val process = ProcessBuilder(listOf("ffmpeg", "-v", "error", "-y") + args).redirectErrorStream(true).start()
        process.inputStream.readBytes()
        process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0
    } catch (e: java.io.IOException) {
        false
    }

    private fun makeClip(): File {
        val out = tmp.newFile("clip.mp4")
        val ok = ffmpeg(
            "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=30", "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000",
            "-t", "10", "-c:v", "libx264", "-g", "30", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", out.path,
        )
        assumeTrue("ffmpeg is not available", ok && out.length() > 0)
        return out
    }

    private fun findings(file: File, expectation: VerifyExpectation = exp): List<Finding> {
        val source = ChannelByteSource(RandomAccessFile(file, "r").channel)
        val parsed = try {
            Mp4Reader.read(source)
        } catch (e: Mp4FormatException) {
            return listOf(Finding(VerifyCheck.CONTAINER, e.message.orEmpty()))
        }
        val result = ContainerCheck.check(parsed, expectation).toMutableList()
        parsed.video?.let { result += SampleCheck.checkVideo(source, it, 30, 90) }
        parsed.audio?.let { result += SampleCheck.checkAudio(source, it, 100) }
        return result
    }

    @Test
    fun `an undamaged ffmpeg file passes every file level check`() {
        val clip = makeClip()

        val found = findings(clip)

        assertEquals(found.toString(), emptyList<Finding>(), found)
    }

    @Test
    fun `ffmpeg's audio and B-frame offsets are read`() {
        val file = Mp4Reader.read(ArrayByteSource(makeClip().readBytes()))

        assertEquals(300, checkNotNull(file.video).count)
        assertTrue(checkNotNull(file.audio).count > 400)
        assertTrue(checkNotNull(file.video).syncSamples.size >= 10)
    }

    @Test
    fun `a truncated file is flagged`() {
        val clip = makeClip()
        RandomAccessFile(clip, "rw").use { it.setLength(it.length() - 5000) }

        assertTrue(findings(clip).any { it.check == VerifyCheck.CONTAINER })
    }

    @Test
    fun `a zeroed tail is flagged`() {
        val clip = makeClip()
        RandomAccessFile(clip, "rw").use {
            val from = it.length() * 7 / 10
            it.seek(from)
            it.write(ByteArray((it.length() - from).toInt()))
        }

        assertTrue(findings(clip).isNotEmpty())
    }

    @Test
    fun `zeroed frame data with the index intact is flagged by the stored data check`() {
        val clip = makeClip()
        val parsed = Mp4Reader.read(ArrayByteSource(clip.readBytes()))
        val video = checkNotNull(parsed.video)
        RandomAccessFile(clip, "rw").use {
            for (i in video.count - 20 until video.count) {
                it.seek(video.offsets[i])
                it.write(ByteArray(video.sizes[i]))
            }
        }

        val found = findings(clip)

        val data = found.first { it.check == VerifyCheck.SAMPLE_DATA }
        assertEquals(20L, data.tailFrames)
    }

    @Test
    fun `a file with the last frame dropped is flagged`() {
        val clip = makeClip()
        val shorter = tmp.newFile("short.mp4")
        assumeTrue(ffmpeg("-i", clip.path, "-map", "0", "-c", "copy", "-frames:v", "299", shorter.path))

        val found = findings(shorter)

        assertTrue(found.toString(), found.any { it.check == VerifyCheck.FRAME_COUNT && it.tailFrames == 1L })
    }

    @Test
    fun `a file whose last GOP is missing is flagged with its length`() {
        val clip = makeClip()
        val shorter = tmp.newFile("gop.mp4")
        assumeTrue(ffmpeg("-i", clip.path, "-map", "0", "-c", "copy", "-t", "9", shorter.path))

        val found = findings(shorter)

        val count = found.first { it.check == VerifyCheck.FRAME_COUNT }
        // ffmpeg keeps the frames up to 9 s plus the two B-frame lead-in frames: about a second of 30 fps is gone.
        assertTrue("tail was ${count.tailFrames}", count.tailFrames in 27..30)
    }

    @Test
    fun `audio shorter than the picture is flagged`() {
        val clip = makeClip()
        val audioOnly = tmp.newFile("a.m4a")
        assumeTrue(ffmpeg("-i", clip.path, "-t", "7", "-map", "0:a", "-c", "copy", audioOnly.path))
        val cutAudio = tmp.newFile("audio_short.mp4")
        // The picture of the clip with only seven seconds of sound.
        assumeTrue(ffmpeg("-i", clip.path, "-i", audioOnly.path, "-map", "0:v", "-map", "1:a", "-c", "copy", cutAudio.path))

        assertTrue(findings(cutAudio).any { it.check == VerifyCheck.AUDIO && it.message.contains("shorter") })
    }
}

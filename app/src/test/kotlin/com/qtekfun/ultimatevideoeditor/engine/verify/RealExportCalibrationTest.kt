package com.qtekfun.ultimatevideoeditor.engine.verify

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Calibration of the file level checks against real exports written by the Pixel 8 (skipped when the files are not on this
 * machine). The files are big and are only ever read through `RandomAccessFile`: the index at the end and the last seconds.
 * Numbers measured here are quoted in DECISIONS.md "Post-export verification".
 */
class RealExportCalibrationTest {
    private val dir = File("/home/qtekfun/uvdata/out")

    private fun open(name: String): Pair<File, ByteSource>? {
        val f = File(dir, name)
        if (!f.isFile) return null
        return f to ChannelByteSource(RandomAccessFile(f, "r").channel)
    }

    @Test
    fun `v034 an HLG 4K 60 fps 12 minute export passes the file level checks and the audio length is measured`() {
        val (file, source) = open("v034.mp4").also { assumeTrue("v034.mp4 not on this machine", it != null) }!!
        val parsed = Mp4Reader.read(source)
        val video = checkNotNull(parsed.video)
        val audio = checkNotNull(parsed.audio)
        val exp = VerifyExpectation(video.count.toLong(), 60, 1, hasAudio = true, hdr = true)

        val findings = ContainerCheck.check(parsed, exp) +
            SampleCheck.checkVideo(source, video, 30, VerifyRunner().tailFrameCount(exp).toInt()) + SampleCheck.checkAudio(source, audio, 200)

        println("CAL ${file.name}: ${video.count} frames, video ${video.durationUs} us, audio ${audio.durationUs} us (${audio.durationUs - video.durationUs} us), nal=${video.nalLengthSize} codec=${video.codec}")
        println("CAL first pts ${video.presentationUs(video.presentationOrder().first())} us, sync samples ${video.syncSamples.size}")
        assertEquals(findings.toString(), emptyList<Finding>(), findings)
        assertTrue(audio.durationUs - video.durationUs in 0..ContainerCheck.AUDIO_LONGER_TOLERANCE_US)
    }
}

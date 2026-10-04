package com.ultimatevideo.uveditor.engine.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSnapshotTest {

    private fun clip(key: Long, voice: VoiceSpec = VoiceSpec.NONE, lanes: Boolean = false) = AudioClipSpec(
        key,
        assetKey = 9,
        startFrame = key * 40,
        durationFrames = 30,
        sourceInFrame = 0,
        sourceFpsNum = 30,
        sourceFpsDen = 1,
        automation = if (lanes) listOf(AutomationLane(AutoParam.PAN, listOf(AutoPoint(0, -1f), AutoPoint(10, 1f)))) else emptyList(),
        voice = voice,
    )

    private val shifted = VoiceSpec(pitchSemitones = 4f, formantSemitones = -3f, whisperMix = 0.25f, ringHz = 70f, ringMix = 0.5f, bandLowHz = 300f, bandHighHz = 3400f, driveDb = 6f, echoMs = 240f, echoFeedback = 0.4f, echoMix = 0.5f, reverbSize = 0.7f, reverbDamping = 0.3f, reverbMix = 0.2f)

    private fun voicesAt(buffer: java.nio.ByteBuffer, clips: Int, laneBytes: Int = 0) =
        buffer.limit() - Int.SIZE_BYTES - laneBytes - clips * Int.SIZE_BYTES - clips * AudioSnapshot.VOICE_BYTES

    @Test
    fun `the version is 7 and every clip gets a 64 byte voice block then a voice lane count`() {
        assertEquals(7, AudioSnapshot.VERSION)
        val buffer = AudioSnapshot(30, 1, listOf(clip(1, shifted), clip(2))).encode()
        val clipsAt = AudioSnapshot.HEADER_BYTES + AudioSnapshot.TRACK_BYTES + AudioSnapshot.DUCKING_BYTES
        // Two clips: blocks, two lane counts (0), the byte size of the lanes (0).
        assertEquals(clipsAt + 2 * AudioSnapshot.CLIP_BYTES + 2 * AudioSnapshot.VOICE_BYTES + 2 * 4 + 4, buffer.remaining())
        assertEquals(7, buffer.getInt(4))

        val voices = voicesAt(buffer, 2)
        val expected = floatArrayOf(4f, -3f, 0.25f, 70f, 0.5f, 300f, 3400f, 6f, 240f, 0.4f, 0.5f, 0.7f, 0.3f, 0.2f, 0f, 0f)
        for (i in expected.indices) assertEquals("float $i", expected[i], buffer.getFloat(voices + i * 4), 0f)
        // The second clip has no effect: zeros, except the reverb damping default.
        val second = voices + AudioSnapshot.VOICE_BYTES
        for (i in 0 until 16) assertEquals("neutral float $i", if (i == 12) 0.5f else 0f, buffer.getFloat(second + i * 4), 0f)
        assertEquals(0, buffer.getInt(buffer.limit() - 12))
        assertEquals(0, buffer.getInt(buffer.limit() - 8))
        assertEquals(8, buffer.getInt(buffer.limit() - 4)) // the lane region: two counts
    }

    @Test
    fun `the voice blocks come after the automation lanes`() {
        val withLanes = AudioSnapshot(30, 1, listOf(clip(1, shifted, lanes = true))).encode()
        val noLanes = AudioSnapshot(30, 1, listOf(clip(1, shifted, lanes = false))).encode()
        val laneBytes = AudioSnapshot.LANE_BYTES + 2 * AudioSnapshot.POINT_BYTES
        assertEquals(noLanes.remaining() + laneBytes, withLanes.remaining())
        assertEquals(4f, withLanes.getFloat(voicesAt(withLanes, 1)), 0f)
        assertEquals(4f, noLanes.getFloat(voicesAt(noLanes, 1)), 0f)
    }

    @Test
    fun `voice lanes are written after the voice blocks with their byte size at the very end`() {
        val lanes = listOf(
            VoiceLane(VoiceField.PITCH, listOf(AutoPoint(0, -2f), AutoPoint(10, 5f), AutoPoint(30, 0f))),
            VoiceLane(VoiceField.ECHO_MS, listOf(AutoPoint(5, 0f), AutoPoint(6, 250f))),
        )
        val animated = clip(1, shifted).copy(voiceAutomation = lanes)
        val buffer = AudioSnapshot(30, 1, listOf(animated, clip(2))).encode()
        val laneBytes = (AudioSnapshot.LANE_BYTES + 3 * AudioSnapshot.POINT_BYTES) + (AudioSnapshot.LANE_BYTES + 2 * AudioSnapshot.POINT_BYTES)
        val region = laneBytes + 2 * 4 // plus a count per clip
        assertEquals(region, buffer.getInt(buffer.limit() - 4))

        var at = buffer.limit() - 4 - region
        assertEquals(2, buffer.getInt(at))
        at += 4
        assertEquals(VoiceField.PITCH.code, buffer.getInt(at))
        assertEquals(3, buffer.getInt(at + 4))
        at += AudioSnapshot.LANE_BYTES
        assertEquals(0L, buffer.getLong(at))
        assertEquals(-2f, buffer.getFloat(at + 8), 0f)
        assertEquals(30L, buffer.getLong(at + 2 * AudioSnapshot.POINT_BYTES))
        at += 3 * AudioSnapshot.POINT_BYTES
        assertEquals(VoiceField.ECHO_MS.code, buffer.getInt(at))
        assertEquals(2, buffer.getInt(at + 4))
        at += AudioSnapshot.LANE_BYTES
        assertEquals(6L, buffer.getLong(at + AudioSnapshot.POINT_BYTES))
        assertEquals(250f, buffer.getFloat(at + AudioSnapshot.POINT_BYTES + 8), 0f)
        at += 2 * AudioSnapshot.POINT_BYTES
        assertEquals(0, buffer.getInt(at)) // the second clip has no lanes
        assertEquals(buffer.limit() - 4, at + 4)
    }

    @Test
    fun `voice lanes are checked before they reach the engine`() {
        val clip = clip(1, shifted)
        assertThrows(IllegalArgumentException::class.java) { VoiceLane(VoiceField.PITCH, emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { VoiceLane(VoiceField.PITCH, listOf(AutoPoint(0, 13f))) }
        assertThrows(IllegalArgumentException::class.java) { VoiceLane(VoiceField.PITCH, listOf(AutoPoint(5, 0f), AutoPoint(5, 1f))) }
        assertThrows(IllegalArgumentException::class.java) { VoiceLane(VoiceField.ECHO_MS, listOf(AutoPoint(0, 0.5f))) } // 0 (off) or 1..2000
        assertThrows(IllegalArgumentException::class.java) { VoiceLane(VoiceField.WHISPER, listOf(AutoPoint(0, Float.NaN))) }
        assertTrue(VoiceLane(VoiceField.ECHO_MS, listOf(AutoPoint(0, 0f), AutoPoint(1, 1f))).points.size == 2)
        // One lane per setting, and no point past the clip's end.
        val lane = VoiceLane(VoiceField.WHISPER, listOf(AutoPoint(0, 0f)))
        assertThrows(IllegalArgumentException::class.java) { clip.copy(voiceAutomation = listOf(lane, lane)) }
        assertThrows(IllegalArgumentException::class.java) { clip.copy(voiceAutomation = listOf(VoiceLane(VoiceField.WHISPER, listOf(AutoPoint(31, 0f))))) }
        // The enum matches the wire order of the voice block.
        assertEquals((0 until 14).toList(), VoiceField.entries.map { it.code })
        val spec = VoiceSpec(pitchSemitones = 1f, formantSemitones = 2f, whisperMix = 0.3f, ringHz = 40f, ringMix = 0.4f, bandLowHz = 100f, bandHighHz = 5000f, driveDb = 7f, echoMs = 90f, echoFeedback = 0.5f, echoMix = 0.6f, reverbSize = 0.7f, reverbDamping = 0.8f, reverbMix = 0.9f)
        assertEquals(listOf(1f, 2f, 0.3f, 40f, 0.4f, 100f, 5000f, 7f, 90f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f), VoiceField.entries.map { spec.valueOf(it) })
    }

    @Test
    fun `settings outside the engine's ranges are refused before they reach it`() {
        assertThrows(IllegalArgumentException::class.java) { VoiceSpec(pitchSemitones = 13f) }
        assertThrows(IllegalArgumentException::class.java) { VoiceSpec(formantSemitones = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { VoiceSpec(whisperMix = 1.5f) }
        assertThrows(IllegalArgumentException::class.java) { VoiceSpec(ringHz = 5f, ringMix = 1f) }
        assertThrows(IllegalArgumentException::class.java) { VoiceSpec(echoMs = 5000f) }
        assertThrows(IllegalArgumentException::class.java) { VoiceSpec(echoFeedback = 0.99f) }
        assertThrows(IllegalArgumentException::class.java) { VoiceSpec(bandLowHz = 4000f, bandHighHz = 1000f) }
        assertThrows(IllegalArgumentException::class.java) { VoiceSpec(driveDb = 40f) }
        assertThrows(IllegalArgumentException::class.java) { VoiceSpec(reverbMix = -0.1f) }
        assertTrue(VoiceSpec(bandLowHz = 300f).bandHighHz == 0f) // one-sided band limits are fine
    }
}

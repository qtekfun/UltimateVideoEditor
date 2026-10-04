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

    @Test
    fun `the version is 6 and every clip gets a 64 byte voice block at the end`() {
        assertEquals(6, AudioSnapshot.VERSION)
        val buffer = AudioSnapshot(30, 1, listOf(clip(1, shifted), clip(2))).encode()
        val clipsAt = AudioSnapshot.HEADER_BYTES + AudioSnapshot.TRACK_BYTES + AudioSnapshot.DUCKING_BYTES
        assertEquals(clipsAt + 2 * AudioSnapshot.CLIP_BYTES + 2 * AudioSnapshot.VOICE_BYTES, buffer.remaining())
        assertEquals(6, buffer.getInt(4))

        val voices = buffer.limit() - 2 * AudioSnapshot.VOICE_BYTES
        val expected = floatArrayOf(4f, -3f, 0.25f, 70f, 0.5f, 300f, 3400f, 6f, 240f, 0.4f, 0.5f, 0.7f, 0.3f, 0.2f, 0f, 0f)
        for (i in expected.indices) assertEquals("float $i", expected[i], buffer.getFloat(voices + i * 4), 0f)
        // The second clip has no effect: zeros, except the reverb damping default.
        val second = voices + AudioSnapshot.VOICE_BYTES
        for (i in 0 until 16) assertEquals("neutral float $i", if (i == 12) 0.5f else 0f, buffer.getFloat(second + i * 4), 0f)
    }

    @Test
    fun `the voice blocks come after the automation lanes`() {
        val withLanes = AudioSnapshot(30, 1, listOf(clip(1, shifted, lanes = true))).encode()
        val noLanes = AudioSnapshot(30, 1, listOf(clip(1, shifted, lanes = false))).encode()
        val laneBytes = AudioSnapshot.LANE_BYTES + 2 * AudioSnapshot.POINT_BYTES
        assertEquals(noLanes.remaining() + laneBytes, withLanes.remaining())
        // The voice block is always the last 64 bytes.
        assertEquals(4f, withLanes.getFloat(withLanes.limit() - AudioSnapshot.VOICE_BYTES), 0f)
        assertEquals(4f, noLanes.getFloat(noLanes.limit() - AudioSnapshot.VOICE_BYTES), 0f)
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

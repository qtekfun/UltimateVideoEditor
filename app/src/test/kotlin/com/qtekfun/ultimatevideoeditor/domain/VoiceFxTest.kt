package com.qtekfun.ultimatevideoeditor.domain

import com.qtekfun.ultimatevideoeditor.engine.audio.VoiceSpec
import com.qtekfun.ultimatevideoeditor.ui.editor.voiceSpecOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceFxTest {

    private fun scene() = timeline(track("v1", clip("a", 0, 100)))

    @Test
    fun `every preset starts at defaults the engine accepts`() {
        for (preset in VoicePreset.entries) {
            val fx = preset.defaults()
            assertNull("${preset.label}: ${fx.problem()}", fx.problem())
            assertEquals(preset.sliders.size, fx.values.size)
            assertEquals(preset.sliders.map { it.default }, fx.values)
            assertFalse("${preset.label} defaults must do something", fx.params().isNeutral)
            voiceSpecOf(fx) // throws if a setting is outside the engine's range
        }
    }

    @Test
    fun `the extremes of every slider are valid engine settings`() {
        for (preset in VoicePreset.entries) {
            for (corner in listOf(preset.sliders.map { it.min }, preset.sliders.map { it.max })) {
                val fx = VoiceFx(preset, corner)
                assertNull("${preset.label} $corner: ${fx.problem()}", fx.problem())
                voiceSpecOf(fx)
            }
            // Mixed corners as well: each slider at an extreme, alternating.
            val mixed = preset.sliders.mapIndexed { i, s -> if (i % 2 == 0) s.min else s.max }
            voiceSpecOf(VoiceFx(preset, mixed))
        }
    }

    @Test
    fun `presets resolve to the engine settings they stand for`() {
        assertEquals(VoiceParams(pitchSemitones = 3.0, formantSemitones = -2.0), VoiceFx(VoicePreset.PITCH_FORMANT, listOf(3.0, -2.0)).params())
        assertEquals(VoiceParams(pitchSemitones = 7.0, formantSemitones = 7.0), VoiceFx(VoicePreset.CHIPMUNK, listOf(7.0)).params())
        val deep = VoiceFx(VoicePreset.DEEP, listOf(-5.0)).params()
        assertEquals(-5.0, deep.pitchSemitones, 0.0)
        assertEquals(-4.0, deep.formantSemitones, 1e-9) // the timbre moves less than the pitch
        val robot = VoiceFx(VoicePreset.ROBOT, listOf(0.6, 60.0)).params()
        assertEquals(60.0, robot.ringHz, 0.0)
        assertEquals(0.6, robot.ringMix, 0.0)
        assertTrue(robot.echoMs in 5.0..20.0 && robot.echoMix > 0.0) // the short comb
        assertEquals(0.4, VoiceFx(VoicePreset.WHISPER, listOf(0.4)).params().whisperMix, 0.0)
        val radio = VoiceFx(VoicePreset.RADIO, listOf(6.0, 0.0)).params()
        assertEquals(300.0, radio.bandLowHz, 1e-9)
        assertEquals(4500.0, radio.bandHighHz, 1e-9)
        assertEquals(6.0, radio.driveDb, 0.0)
        val narrow = VoiceFx(VoicePreset.RADIO, listOf(6.0, 1.0)).params()
        assertTrue(narrow.bandLowHz > radio.bandLowHz && narrow.bandHighHz < radio.bandHighHz && narrow.bandLowHz < narrow.bandHighHz)
        assertEquals(VoiceParams(echoMs = 300.0, echoFeedback = 0.5, echoMix = 0.25), VoiceFx(VoicePreset.ECHO, listOf(300.0, 0.5, 0.25)).params())
        assertEquals(VoiceParams(reverbSize = 0.8, reverbDamping = 0.3, reverbMix = 0.5), VoiceFx(VoicePreset.REVERB, listOf(0.8, 0.3, 0.5)).params())
        val mega = VoiceFx(VoicePreset.MEGAPHONE, listOf(20.0, 0.5)).params()
        assertEquals(20.0, mega.driveDb, 0.0)
        assertTrue(mega.bandLowHz in 400.0..600.0 && mega.bandHighHz in 3000.0..4000.0)
    }

    @Test
    fun `an effect that does nothing is sent as no effect`() {
        assertEquals(VoiceSpec.NONE, voiceSpecOf(null))
        assertEquals(VoiceSpec.NONE, voiceSpecOf(VoiceFx(VoicePreset.WHISPER, listOf(0.0))))
        assertEquals(VoiceSpec.NONE, voiceSpecOf(VoiceFx(VoicePreset.PITCH_FORMANT, listOf(0.0, 0.0))))
        val echo = voiceSpecOf(VoiceFx(VoicePreset.ECHO, listOf(280.0, 0.45, 0.4)))
        assertEquals(280f, echo.echoMs, 0f)
        assertEquals(0.45f, echo.echoFeedback, 1e-6f)
    }

    @Test
    fun `values are checked against the slider ranges`() {
        assertNotNull(VoiceFx(VoicePreset.ECHO, listOf(300.0, 0.5)).problem())              // wrong number of settings
        assertNotNull(VoiceFx(VoicePreset.ECHO, listOf(10.0, 0.5, 0.5)).problem())          // delay below its range
        assertNotNull(VoiceFx(VoicePreset.ECHO, listOf(300.0, 0.95, 0.5)).problem())        // repeats above its range
        assertNotNull(VoiceFx(VoicePreset.CHIPMUNK, listOf(Double.NaN)).problem())
        assertNotNull(VoiceFx(VoicePreset.DEEP, listOf(3.0)).problem())                     // a deep voice does not go up
        assertNull(VoiceFx(VoicePreset.DEEP, listOf(-12.0)).problem())
    }

    @Test
    fun `with replaces one slider and clamps it into its range`() {
        val fx = VoicePreset.ECHO.defaults()
        assertEquals(listOf(500.0, 0.45, 0.4), fx.with(0, 500.0).values)
        assertEquals(800.0, fx.with(0, 5000.0).values[0], 0.0)
        assertEquals(0.0, fx.with(2, -1.0).values[2], 0.0)
        assertEquals(fx, fx.with(1, fx.values[1]))
    }

    @Test
    fun `a clip with a voice effect is not neutral and a bad one is refused`() {
        val voiced = ClipAudio(voice = VoicePreset.REVERB.defaults())
        assertFalse(voiced.isNeutral)
        assertNull(voiced.problem(100))
        assertNotNull(ClipAudio(voice = VoiceFx(VoicePreset.REVERB, listOf(2.0, 0.5, 0.5))).problem(100))
        assertTrue(TimelineOps.setClipAudio(scene(), "a", ClipAudio(voice = VoiceFx(VoicePreset.ECHO, emptyList()))).errorOrFail() is EditError.InvalidAudio)
    }

    @Test
    fun `setting the effect is one undo step and a split keeps it on both halves`() {
        val start = scene()
        val voiced = ClipAudio(voice = VoicePreset.ROBOT.defaults())
        val history = EditHistory(start).execute(EditCommand.SetClipAudio("a", voiced)).getOrFail()
        assertEquals(voiced, history.timeline.trackOfClip("a")!!.clip("a")!!.audio)
        assertEquals(start, history.undo().timeline)
        assertEquals(voiced, history.undo().redo().timeline.trackOfClip("a")!!.clip("a")!!.audio)

        val split = TimelineOps.split(history.timeline, "v1", f(40), "b").getOrFail()
        assertEquals(voiced.voice, split.trackOfClip("a")!!.clip("a")!!.audio.voice)
        assertEquals(voiced.voice, split.trackOfClip("b")!!.clip("b")!!.audio.voice)
    }

    @Test
    fun `resetting a clip's sound removes the voice effect too`() {
        val voiced = TimelineOps.setClipAudio(scene(), "a", ClipAudio(pan = 0.2, voice = VoicePreset.WHISPER.defaults())).getOrFail()
        val reset = TimelineOps.setClipAudio(voiced, "a", ClipAudio.NONE).getOrFail()
        assertNull(reset.trackOfClip("a")!!.clip("a")!!.audio.voice)
        assertTrue(reset.trackOfClip("a")!!.clip("a")!!.audio.isNeutral)
    }
}

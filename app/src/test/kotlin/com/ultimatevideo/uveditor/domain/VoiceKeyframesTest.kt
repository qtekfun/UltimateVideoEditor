package com.ultimatevideo.uveditor.domain

import com.ultimatevideo.uveditor.engine.audio.AutoPoint
import com.ultimatevideo.uveditor.engine.audio.VoiceField
import com.ultimatevideo.uveditor.engine.audio.VoiceLane
import com.ultimatevideo.uveditor.ui.editor.voiceLanesOf
import com.ultimatevideo.uveditor.ui.editor.voiceSpecOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceKeyframesTest {

    private val echoMix = ParamIds.voice(2) // ECHO: delay, repeats, mix
    private val delay = ParamIds.voice(0)

    /** Clip "a" of 100 frames on an audio track with an echo voice effect (delay 280 ms, repeats 0.45, mix 0.4). */
    private fun scene(preset: VoicePreset = VoicePreset.ECHO): Timeline {
        val t = timeline(track("a1", clip("a", 0, 100), type = TrackType.AUDIO))
        return TimelineOps.setClipAudio(t, "a", ClipAudio(voice = preset.defaults())).getOrFail()
    }

    private fun Timeline.a(): Clip = checkNotNull(track("a1")!!.clip("a"))

    private fun key(frame: Long, value: Double, mode: Interpolation = Interpolation.LINEAR) = ParamKey(frame, value, mode)

    private fun Timeline.setKey(param: String, key: ParamKey): Timeline = ParamOps.setKey(this, "a", param, key).getOrFail()

    @Test
    fun `voice sliders have parameter ids, ranges and labels of their own`() {
        assertEquals("audio.voice.2", echoMix)
        assertEquals(2, ParamIds.parseVoice(echoMix))
        assertNull(ParamIds.parseVoice("audio.voice.x"))
        assertNull(ParamIds.parseVoice("audio.voice.-1"))
        assertNull(ParamIds.parseVoice("audio.pan"))

        val clip = scene().a()
        val spec = checkNotNull(clip.paramSpec(echoMix))
        assertEquals(0.0, spec.min, 0.0)
        assertEquals(1.0, spec.max, 0.0)
        assertEquals("Echo mix", spec.label)
        assertEquals(60.0, clip.paramSpec(delay)!!.min, 0.0)
        assertEquals(0.4, clip.staticParamValue(echoMix)!!, 0.0)
        // No such slider, and no voice effect at all.
        assertNull(clip.paramSpec(ParamIds.voice(3)))
        assertNull(clip.copy(audio = ClipAudio.NONE).paramSpec(echoMix))
        assertNull(clip.copy(audio = ClipAudio.NONE).staticParamValue(echoMix))
    }

    @Test
    fun `keys animate a slider and interpolate it, the fixed value stays the base`() {
        val t = scene().setKey(echoMix, key(10, 0.0)).setKey(echoMix, key(50, 1.0))
        val clip = t.a()
        assertEquals(listOf(echoMix), clip.params.map { it.paramId })
        assertEquals(0.4, clip.audio.voice!!.values[2], 0.0)
        assertEquals(0.0, clip.paramValueAt(echoMix, 0)!!, 1e-12) // held before the first key
        assertEquals(0.5, clip.paramValueAt(echoMix, 30)!!, 1e-12) // linear in between
        assertEquals(1.0, clip.paramValueAt(echoMix, 90)!!, 1e-12) // held after the last
        // Hold and ease segments behave like any other parameter.
        val held = scene().setKey(echoMix, key(10, 0.2, Interpolation.HOLD)).setKey(echoMix, key(50, 0.9))
        assertEquals(0.2, held.a().paramValueAt(echoMix, 49)!!, 1e-12)
        val eased = scene().setKey(echoMix, key(0, 0.0, Interpolation.EASE)).setKey(echoMix, key(50, 1.0))
        val mid = eased.a().paramValueAt(echoMix, 25)!!
        assertEquals(0.5, mid, 1e-9)
        assertTrue(eased.a().paramValueAt(echoMix, 5)!! < 0.1) // slow start
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `the controls show the animated value at the playhead`() {
        val clip = scene().setKey(echoMix, key(0, 0.0)).setKey(echoMix, key(50, 1.0)).a()
        assertEquals(0.5, clip.displayedAt(25).audio.voice!!.values[2], 1e-12)
        assertEquals(0.45, clip.displayedAt(25).audio.voice!!.values[1], 1e-12) // other sliders untouched
        assertEquals(clip.audio.voice, clip.displayedAt(null).audio.voice)
        assertEquals(clip.voiceAt(50)!!.values, clip.displayedAt(50).audio.voice!!.values)
        assertNull(clip.copy(audio = ClipAudio.NONE).voiceAt(5))
    }

    @Test
    fun `keys outside the slider's range or on a missing slider are refused`() {
        val t = scene()
        assertTrue(ParamOps.setKey(t, "a", echoMix, key(5, 1.5)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", delay, key(5, 10.0)).errorOrFail() is EditError.InvalidKeyframe) // 60..800 ms
        assertTrue(ParamOps.setKey(t, "a", echoMix, key(100, 0.5)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", ParamIds.voice(7), key(5, 0.5)).errorOrFail() is EditError.InvalidKeyframe)
        val noVoice = timeline(track("a1", clip("a", 0, 100), type = TrackType.AUDIO))
        assertTrue(ParamOps.setKey(noVoice, "a", echoMix, key(5, 0.5)).errorOrFail() is EditError.InvalidKeyframe)
    }

    @Test
    fun `removing the last key keeps its value as the slider's fixed value`() {
        var t = scene().setKey(echoMix, key(5, 0.75))
        t = ParamOps.removeKey(t, "a", echoMix, 5).getOrFail()
        val clip = t.a()
        assertTrue(clip.params.isEmpty())
        assertEquals(0.75, clip.audio.voice!!.values[2], 0.0)
        assertEquals(listOf(280.0, 0.45, 0.75), clip.audio.voice.values)
    }

    @Test
    fun `clearing, moving and pasting work for voice keys`() {
        var t = scene().setKey(echoMix, key(10, 0.1)).setKey(echoMix, key(20, 0.9))
        t = ParamOps.moveKey(t, "a", echoMix, 20, 60).getOrFail()
        assertEquals(listOf(10L, 60L), t.a().paramKeys(echoMix).map { it.frame })
        t = ParamOps.pasteKeys(t, "a", echoMix, listOf(key(30, 5.0))).getOrFail() // clamped to the slider's range
        assertEquals(1.0, t.a().paramKeys(echoMix).first { it.frame == 30L }.value, 0.0)
        t = ParamOps.clearTrack(t, "a", echoMix).getOrFail()
        assertTrue(t.a().params.isEmpty())
        assertEquals(0.4, t.a().audio.voice!!.values[2], 0.0)
    }

    @Test
    fun `splitting a clip crops the voice tracks and the halves continue the same curve`() {
        var t = scene().setKey(echoMix, key(0, 0.0)).setKey(echoMix, key(99, 1.0)).setKey(delay, key(20, 100.0)).setKey(delay, key(80, 700.0))
        val original = t.a()
        t = TimelineOps.split(t, "a1", FrameIndex(40), "b").getOrFail()
        val left = t.a()
        val right = checkNotNull(t.track("a1")!!.clip("b"))
        for (id in listOf(echoMix, delay)) {
            for (frame in 0L until 40L) assertEquals(original.paramValueAt(id, frame)!!, left.paramValueAt(id, frame)!!, 1e-9)
            for (frame in 0L until 60L) assertEquals(original.paramValueAt(id, frame + 40)!!, right.paramValueAt(id, frame)!!, 1e-9)
        }
        assertEquals(original.audio.voice, left.audio.voice) // both halves keep the effect
        assertEquals(original.audio.voice, right.audio.voice)
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `trimming either end keeps the voice curve over what remains`() {
        val t0 = scene().setKey(echoMix, key(0, 0.0)).setKey(echoMix, key(99, 1.0))
        val original = t0.a()
        val start = TimelineOps.trim(t0, "a", TrimEdge.START, FrameIndex(30)).getOrFail().a()
        for (frame in 0L until start.durationFrames) {
            assertEquals(original.paramValueAt(echoMix, frame + 30)!!, start.paramValueAt(echoMix, frame)!!, 1e-9)
        }
        val end = TimelineOps.trim(t0, "a", TrimEdge.END, FrameIndex(60)).getOrFail().a()
        assertEquals(60L, end.durationFrames)
        for (frame in 0L until 60L) assertEquals(original.paramValueAt(echoMix, frame)!!, end.paramValueAt(echoMix, frame)!!, 1e-9)
        assertTrue(timeline(track("a1", end, type = TrackType.AUDIO)).invariantViolations().isEmpty())
    }

    @Test
    fun `changing the speed stretches the voice tracks with the clip`() {
        val t = TimelineOps.setSpeed(scene().setKey(echoMix, key(0, 0.0)).setKey(echoMix, key(99, 1.0)), "a", 2, 1).getOrFail()
        val clip = t.a()
        assertEquals(50L, clip.durationFrames)
        assertEquals(listOf(0L, 49L), clip.paramKeys(echoMix).map { it.frame })
        assertEquals(clip.audio.voice, scene().a().audio.voice)
    }

    @Test
    fun `another preset or none takes its own animation, the same preset keeps it`() {
        val animated = scene().setKey(echoMix, key(10, 0.1)).setKey(echoMix, key(50, 0.9))
        // Moving another slider of the same preset leaves the keys alone.
        val louder = TimelineOps.setClipAudio(animated, "a", animated.a().audio.copy(voice = animated.a().audio.voice!!.with(1, 0.6))).getOrFail()
        assertEquals(animated.a().params, louder.a().params)
        assertEquals(0.6, louder.a().audio.voice!!.values[1], 0.0)
        // A different preset: no track of the old one survives (slider 2 of Reverb is a different thing).
        val reverb = TimelineOps.setClipAudio(animated, "a", animated.a().audio.copy(voice = VoicePreset.REVERB.defaults())).getOrFail()
        assertTrue(reverb.a().params.isEmpty())
        // Off, and Reset audio.
        assertTrue(TimelineOps.setClipAudio(animated, "a", animated.a().audio.copy(voice = null)).getOrFail().a().params.isEmpty())
        assertTrue(TimelineOps.setClipAudio(animated, "a", ClipAudio.NONE).getOrFail().a().params.isEmpty())
        // Other parameters' tracks are not touched.
        val withPan = ParamOps.setKey(animated, "a", ParamIds.PAN, key(0, 0.5)).getOrFail()
        val off = TimelineOps.setClipAudio(withPan, "a", withPan.a().audio.copy(voice = null)).getOrFail()
        assertEquals(listOf(ParamIds.PAN), off.a().params.map { it.paramId })
        assertTrue(off.invariantViolations().isEmpty())
    }

    @Test
    fun `a clip with a voice track but no voice effect is reported`() {
        val t = scene().setKey(echoMix, key(5, 0.5))
        val broken = t.a().copy(audio = ClipAudio.NONE)
        assertTrue(timeline(track("a1", broken, type = TrackType.AUDIO)).invariantViolations().any { it.contains("does not exist") })
    }

    @Test
    fun `a change of a keyframed slider at the playhead becomes a key and the fixed value stays`() {
        val t = scene().setKey(echoMix, key(0, 0.0)).setKey(echoMix, key(50, 1.0))
        val shown = t.a().displayedAt(25).audio // the controls show 0.5 here
        val edited = shown.copy(voice = shown.voice!!.with(2, 0.2).with(1, 0.7))
        val after = ParamOps.setClipAudioAt(t, "a", edited, 25).getOrFail().a()
        assertEquals(0.2, after.paramKeys(echoMix).first { it.frame == 25L }.value, 1e-12)
        assertEquals(3, after.paramKeys(echoMix).size)
        assertEquals(0.4, after.audio.voice!!.values[2], 0.0) // the fixed value of a keyframed slider is not overwritten
        assertEquals(0.7, after.audio.voice.values[1], 0.0) // a slider that is not animated is simply set
        // A slider that did not change adds no key; with no playhead on the clip a keyframed slider is left alone.
        val same = ParamOps.setClipAudioAt(t, "a", shown, 25).getOrFail().a()
        assertEquals(2, same.paramKeys(echoMix).size)
        val away = ParamOps.setClipAudioAt(t, "a", edited.copy(voice = edited.voice), null).getOrFail().a()
        assertEquals(2, away.paramKeys(echoMix).size)
        assertEquals(0.4, away.audio.voice!!.values[2], 0.0)
        // Picking another preset while a slider is animated is a new effect with no keys.
        val other = ParamOps.setClipAudioAt(t, "a", shown.copy(voice = VoicePreset.WHISPER.defaults()), 25).getOrFail().a()
        assertTrue(other.params.isEmpty())
        assertEquals(VoicePreset.WHISPER, other.audio.voice!!.preset)
    }

    @Test
    fun `every voice key command undoes exactly`() {
        var history = EditHistory(scene())
        val start = history.timeline
        val commands = listOf(
            EditCommand.SetParamKey("a", echoMix, key(10, 0.5)),
            EditCommand.SetParamKey("a", echoMix, key(40, 0.9)),
            EditCommand.SetParamKeyShape("a", echoMix, 10, Interpolation.BEZIER, BezierHandle(0.2, 0.1), BezierHandle(0.8, 0.9)),
            EditCommand.MoveParamKey("a", echoMix, 40, 60),
            EditCommand.SetClipAudioAt("a", ClipAudio(voice = VoicePreset.ECHO.defaults().with(0, 400.0)), 30),
            EditCommand.SetClipAudio("a", ClipAudio(voice = VoicePreset.REVERB.defaults())),
        )
        val states = ArrayList<Timeline>()
        for (command in commands) {
            history = history.execute(command).getOrFail()
            states += history.timeline
        }
        for (i in commands.indices.reversed()) {
            assertEquals(states[i], history.timeline)
            history = history.undo()
        }
        assertEquals(start, history.timeline)
    }

    // region the engine's lanes

    private fun renderClipOf(t: Timeline) = t.renderClips().first { it.clipId == "a" }

    private fun lane(lanes: List<VoiceLane>, field: VoiceField): VoiceLane? = lanes.firstOrNull { it.field == field }

    @Test
    fun `a slider keyed on its own becomes the lanes of the settings it drives and no others`() {
        val t = scene().setKey(echoMix, key(10, 0.0)).setKey(echoMix, key(50, 1.0))
        val lanes = voiceLanesOf(renderClipOf(t))
        assertEquals(listOf(VoiceField.ECHO_MIX), lanes.map { it.field })
        assertEquals(listOf(AutoPoint(10, 0f), AutoPoint(50, 1f)), lanes.single().points)
        // No keys, no voice effect: nothing.
        assertTrue(voiceLanesOf(renderClipOf(scene())).isEmpty())
        val noVoice = timeline(track("a1", clip("a", 0, 100), type = TrackType.AUDIO))
        assertTrue(voiceLanesOf(renderClipOf(noVoice)).isEmpty())
    }

    @Test
    fun `one slider can drive several settings and the lanes keep them in step`() {
        // Chipmunk shifts the voice and its formants by the same amount; Deep moves the formants by 80 % of it.
        val chip = scene(VoicePreset.CHIPMUNK).setKey(ParamIds.voice(0), key(0, 3.0)).setKey(ParamIds.voice(0), key(60, 9.0))
        val lanes = voiceLanesOf(renderClipOf(chip))
        assertEquals(setOf(VoiceField.PITCH, VoiceField.FORMANT), lanes.map { it.field }.toSet())
        assertEquals(lane(lanes, VoiceField.PITCH)!!.points, lane(lanes, VoiceField.FORMANT)!!.points)
        val deep = TimelineOps.setClipAudio(scene(), "a", ClipAudio(voice = VoicePreset.DEEP.defaults())).getOrFail()
            .setKey(ParamIds.voice(0), key(0, -2.0)).setKey(ParamIds.voice(0), key(60, -10.0))
        val deepLanes = voiceLanesOf(renderClipOf(deep))
        assertEquals(-8f, lane(deepLanes, VoiceField.FORMANT)!!.points.last().value, 1e-5f)
        assertEquals(-10f, lane(deepLanes, VoiceField.PITCH)!!.points.last().value, 0f)
    }

    @Test
    fun `settings that a slider moves through an affine map are exact between the points`() {
        // Radio: drive is the first slider; the band corners follow the second one (300 + 300 v and 4500 - 1700 v Hz).
        val radio = TimelineOps.setClipAudio(scene(), "a", ClipAudio(voice = VoicePreset.RADIO.defaults())).getOrFail()
            .setKey(ParamIds.voice(1), key(0, 0.0)).setKey(ParamIds.voice(1), key(80, 1.0))
        val lanes = voiceLanesOf(renderClipOf(radio))
        assertEquals(setOf(VoiceField.BAND_LOW, VoiceField.BAND_HIGH), lanes.map { it.field }.toSet())
        val low = lane(lanes, VoiceField.BAND_LOW)!!
        assertEquals(listOf(0L, 80L), low.points.map { it.frame }) // linear: two points are enough
        assertEquals(300f, low.points.first().value, 0f)
        assertEquals(600f, low.points.last().value, 0f)
        // At frame 40 the slider is 0.5: the line through the points gives 450 Hz, as the preset maps it.
        val clip = radio.a()
        val at40 = voiceSpecOf(clip.voiceAt(40)!!)
        assertEquals(at40.bandLowHz, (low.points[0].value + low.points[1].value) / 2, 1e-3f)
        assertEquals(at40.bandHighHz, (lane(lanes, VoiceField.BAND_HIGH)!!.points.let { it[0].value + it[1].value }) / 2, 1e-3f)
    }

    @Test
    fun `two animated sliders share the frames of both so each setting is exact at every key`() {
        val robot = scene(VoicePreset.ROBOT) // Metal (ring mix) 0.6 and Buzz (ring frequency) 60 Hz
            .setKey(ParamIds.voice(0), key(0, 0.0)).setKey(ParamIds.voice(0), key(40, 1.0))
            .setKey(ParamIds.voice(1), key(0, 30.0)).setKey(ParamIds.voice(1), key(90, 200.0))
        val lanes = voiceLanesOf(renderClipOf(robot))
        val mix = lane(lanes, VoiceField.RING_MIX)!!
        val hz = lane(lanes, VoiceField.RING_HZ)!!
        assertEquals(listOf(0L, 40L, 90L), mix.points.map { it.frame })
        assertEquals(mix.points.map { it.frame }, hz.points.map { it.frame })
        val clip = robot.a()
        for ((i, frame) in mix.points.map { it.frame }.withIndex()) {
            val spec = voiceSpecOf(clip.voiceAt(frame)!!)
            assertEquals(spec.ringMix, mix.points[i].value, 1e-6f)
            assertEquals(spec.ringHz, hz.points[i].value, 1e-4f)
        }
        // The comb of the robot voice is fixed: no lanes for it.
        assertNull(lane(lanes, VoiceField.ECHO_MS))
    }

    @Test
    fun `an eased segment gets a point per frame so the engine reproduces the curve`() {
        val t = scene().setKey(echoMix, key(0, 0.0, Interpolation.EASE)).setKey(echoMix, key(20, 1.0))
        val lane = voiceLanesOf(renderClipOf(t)).single()
        assertEquals((0L..20L).toList(), lane.points.map { it.frame })
        val clip = t.a()
        for (p in lane.points) assertEquals(clip.paramValueAt(echoMix, p.frame)!!.toFloat(), p.value, 1e-6f)
    }

    @Test
    fun `a key that keeps the fixed value everywhere asks for nothing, one that differs asks for a lane`() {
        val sameAsFixed = scene().setKey(echoMix, key(30, 0.4))
        assertTrue(voiceLanesOf(renderClipOf(sameAsFixed)).isEmpty())
        // A single key holds its value for the whole clip even though the fixed value differs.
        val other = scene().setKey(echoMix, key(30, 0.9))
        val lane = voiceLanesOf(renderClipOf(other)).single()
        assertEquals(listOf(AutoPoint(30, 0.9f)), lane.points)
    }

    @Test
    fun `lanes of a clip shorter than its keys' frames stay inside it and use the clip's own start as the origin`() {
        val t = scene().setKey(echoMix, key(0, 0.0)).setKey(echoMix, key(99, 1.0))
        val cut = TimelineOps.trim(t, "a", TrimEdge.END, FrameIndex(50)).getOrFail()
        val lane = voiceLanesOf(renderClipOf(cut)).single()
        assertTrue(lane.points.all { it.frame in 0..50 })
        assertEquals(0f, lane.points.first().value, 0f)
        assertNotNull(lane.points.lastOrNull())
    }

    @Test
    fun `the export plan carries the same voice lanes as the preview's snapshot`() {
        val t = scene().setKey(echoMix, key(10, 0.0)).setKey(echoMix, key(50, 1.0))
        val asset = com.ultimatevideo.uveditor.data.model.MediaAssetDto("a", "content://a", 600, 30, 1, "Rec709-SDR", hasVideo = false, hasAudio = true)
        val fps = FrameRate(30, 1)
        val preview = com.ultimatevideo.uveditor.ui.editor.audioSnapshotOf(t, listOf(asset), fps, { 1L }, { 2L })
        val export = com.ultimatevideo.uveditor.ui.export.buildExportPlan(t, listOf(asset), fps)!!.audio!!
        val lanes = preview.clips.single().voiceAutomation
        assertEquals(listOf(VoiceField.ECHO_MIX), lanes.map { it.field })
        assertEquals(lanes, export.clips.single().voiceAutomation)
        assertEquals(preview.clips.single().voice, export.clips.single().voice)
    }

    // endregion
}

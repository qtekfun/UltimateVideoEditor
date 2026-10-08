package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParamOpsTest {

    private val contrast = ParamIds.fx("c1", 0)

    /** Clip "a" of 100 frames on a video track with a contrast effect "c1" (static value 1.0). */
    private fun base(): Timeline {
        val t = timeline(track("v1", clip("a", 0, 100)))
        return TimelineOps.addEffect(t, "a", Effect("c1", EffectType.CONTRAST)).getOrFail()
    }

    private fun Timeline.a(): Clip = checkNotNull(track("v1")!!.clip("a"))

    private fun key(frame: Long, value: Double, mode: Interpolation = Interpolation.LINEAR) = ParamKey(frame, value, mode)

    private fun Timeline.setKey(param: String, key: ParamKey): Timeline = ParamOps.setKey(this, "a", param, key).getOrFail()

    // region effect values

    @Test
    fun `a key animates an effect value and the static value stays the base`() {
        val t = base().setKey(contrast, key(10, 0.5)).setKey(contrast, key(50, 1.5))
        val clip = t.a()
        assertEquals(listOf(contrast), clip.params.map { it.paramId })
        assertEquals(1.0, clip.fx.effect("c1")!!.values[0], 0.0) // fixed value untouched
        assertEquals(0.5, clip.paramValueAt(contrast, 0)!!, 1e-12)
        assertEquals(1.0, clip.paramValueAt(contrast, 30)!!, 1e-12)
        assertEquals(1.5, clip.paramValueAt(contrast, 90)!!, 1e-12)
        assertEquals(0.75, clip.fxAt(20).effect("c1")!!.values[0], 1e-12)
        assertEquals(clip.fx, clip.copy(params = emptyList()).fxAt(20))
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `setting a key at an existing frame replaces it`() {
        var t = base().setKey(contrast, key(10, 0.5))
        t = t.setKey(contrast, key(10, 0.9, Interpolation.HOLD))
        val keys = t.a().paramKeys(contrast)
        assertEquals(1, keys.size)
        assertEquals(0.9, keys[0].value, 0.0)
        assertEquals(Interpolation.HOLD, keys[0].interpolation)
    }

    @Test
    fun `bad keys are rejected with a typed error`() {
        val t = base()
        assertTrue(ParamOps.setKey(t, "a", contrast, key(-1, 1.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", contrast, key(100, 1.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", contrast, key(5, 9.0)).errorOrFail() is EditError.InvalidKeyframe) // contrast is 0..2
        assertTrue(ParamOps.setKey(t, "a", contrast, key(5, Double.NaN)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", ParamIds.fx("nope", 0), key(5, 1.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", ParamIds.fx("c1", 3), key(5, 1.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", "nonsense", key(5, 1.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "zz", contrast, key(5, 1.0)).errorOrFail() is EditError.ClipNotFound)
        val badHandle = ParamKey(5, 1.0, Interpolation.BEZIER, out = BezierHandle(2.0, 0.0))
        assertTrue(ParamOps.setKey(t, "a", contrast, badHandle).errorOrFail() is EditError.InvalidKeyframe)
    }

    @Test
    fun `the library key of a LUT is never animatable but its intensity is`() {
        val t = TimelineOps.addEffect(base(), "a", Effect("l1", EffectType.LUT, listOf(5.0, 1.0))).getOrFail()
        assertNull(t.a().paramSpec(ParamIds.fx("l1", 0)))
        assertTrue(ParamOps.setKey(t, "a", ParamIds.fx("l1", 0), key(5, 7.0)).errorOrFail() is EditError.InvalidKeyframe)
        val animated = t.setKey(ParamIds.fx("l1", 1), key(0, 0.0)).setKey(ParamIds.fx("l1", 1), key(40, 1.0))
        assertEquals(0.5, animated.a().fxAt(20).effect("l1")!!.values[1], 1e-12)
        assertEquals(5.0, animated.a().fxAt(20).effect("l1")!!.values[0], 0.0)
    }

    @Test
    fun `removing the last key keeps its value as the fixed value`() {
        var t = base().setKey(contrast, key(10, 0.5)).setKey(contrast, key(50, 1.7))
        t = ParamOps.removeKey(t, "a", contrast, 10).getOrFail()
        assertEquals(1, t.a().paramKeys(contrast).size)
        t = ParamOps.removeKey(t, "a", contrast, 50).getOrFail()
        assertTrue(t.a().params.isEmpty())
        assertEquals(1.7, t.a().fx.effect("c1")!!.values[0], 0.0)
        assertEquals(EditError.KeyframeNotFound(50), ParamOps.removeKey(t, "a", contrast, 50).errorOrFail())
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `removing a missing key fails`() {
        val t = base().setKey(contrast, key(10, 0.5))
        assertEquals(EditError.KeyframeNotFound(11), ParamOps.removeKey(t, "a", contrast, 11).errorOrFail())
    }

    @Test
    fun `moving a key changes its frame and replaces what was there`() {
        var t = base().setKey(contrast, key(10, 0.5)).setKey(contrast, key(20, 1.0)).setKey(contrast, key(30, 1.5))
        t = ParamOps.moveKey(t, "a", contrast, 10, 30).getOrFail()
        assertEquals(listOf(20L, 30L), t.a().paramKeys(contrast).map { it.frame })
        assertEquals(0.5, t.a().paramKeys(contrast).last().value, 0.0)
        assertTrue(ParamOps.moveKey(t, "a", contrast, 20, 100).errorOrFail() is EditError.InvalidKeyframe)
        assertEquals(EditError.KeyframeNotFound(3), ParamOps.moveKey(t, "a", contrast, 3, 4).errorOrFail())
    }

    @Test
    fun `changing the shape of a key sets mode and handles without touching the value`() {
        var t = base().setKey(contrast, key(0, 0.0)).setKey(contrast, key(40, 2.0))
        val out = BezierHandle(0.1, 0.9)
        val inn = BezierHandle(0.6, 0.2)
        t = ParamOps.setKeyShape(t, "a", contrast, 0, Interpolation.BEZIER, out, inn).getOrFail()
        val first = t.a().paramKeys(contrast).first()
        assertEquals(Interpolation.BEZIER, first.interpolation)
        assertEquals(out, first.out)
        assertEquals(0.0, first.value, 0.0)
        assertTrue(ParamOps.setKeyShape(t, "a", contrast, 0, Interpolation.BEZIER, BezierHandle(5.0, 0.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertEquals(EditError.KeyframeNotFound(7), ParamOps.setKeyShape(t, "a", contrast, 7, Interpolation.HOLD).errorOrFail())
    }

    @Test
    fun `clearing a track returns the parameter to its fixed value`() {
        val t = base().setKey(contrast, key(10, 0.5))
        val cleared = ParamOps.clearTrack(t, "a", contrast).getOrFail()
        assertTrue(cleared.a().params.isEmpty())
        assertEquals(1.0, cleared.a().paramValueAt(contrast, 50)!!, 0.0)
    }

    @Test
    fun `pasting keys merges them and clamps values to the range`() {
        val t = base().setKey(contrast, key(5, 1.0))
        val pasted = ParamOps.pasteKeys(t, "a", contrast, listOf(key(20, 5.0), key(30, -3.0), key(5, 0.25))).getOrFail()
        val keys = pasted.a().paramKeys(contrast)
        assertEquals(listOf(5L, 20L, 30L), keys.map { it.frame })
        assertEquals(listOf(0.25, 2.0, 0.0), keys.map { it.value })
        assertEquals(t, ParamOps.pasteKeys(t, "a", contrast, emptyList()).getOrFail())
        assertTrue(pasted.invariantViolations().isEmpty())
    }

    // endregion

    // region audio parameters

    private fun audioScene(): Timeline {
        val t = timeline(track("a1", clip("a", 0, 100), type = TrackType.AUDIO))
        return t
    }

    @Test
    fun `volume pan and EQ band gains can be keyframed`() {
        var t = audioScene()
        t = ParamOps.setKey(t, "a", ParamIds.GAIN_DB, key(0, 0.0)).getOrFail()
        t = ParamOps.setKey(t, "a", ParamIds.GAIN_DB, key(50, -20.0)).getOrFail()
        t = ParamOps.setKey(t, "a", ParamIds.PAN, key(0, -1.0)).getOrFail()
        t = ParamOps.setKey(t, "a", ParamIds.eqGain(2), key(10, 6.0)).getOrFail()
        val clip = checkNotNull(t.track("a1")!!.clip("a"))
        assertEquals(-10.0, clip.paramValueAt(ParamIds.GAIN_DB, 25)!!, 1e-12)
        assertEquals(-1.0, clip.paramValueAt(ParamIds.PAN, 25)!!, 1e-12)
        assertEquals(6.0, clip.paramValueAt(ParamIds.eqGain(2), 0)!!, 1e-12)
        assertTrue(t.invariantViolations().isEmpty())
        // Out-of-range values and EQ bands that do not exist.
        assertTrue(ParamOps.setKey(t, "a", ParamIds.PAN, key(5, 2.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", ParamIds.eqGain(2), key(5, 40.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", ParamIds.eqGain(9), key(5, 1.0)).errorOrFail() is EditError.InvalidKeyframe)
        assertTrue(ParamOps.setKey(t, "a", ParamIds.GAIN_DB, key(5, 99.0)).errorOrFail() is EditError.InvalidKeyframe)
    }

    @Test
    fun `removing the last audio key writes the value back to the static field`() {
        var t = audioScene()
        t = ParamOps.setKey(t, "a", ParamIds.GAIN_DB, key(5, -12.0)).getOrFail()
        t = ParamOps.setKey(t, "a", ParamIds.PAN, key(5, 0.5)).getOrFail()
        t = ParamOps.setKey(t, "a", ParamIds.eqGain(1), key(5, 4.0)).getOrFail()
        for (id in listOf(ParamIds.GAIN_DB, ParamIds.PAN, ParamIds.eqGain(1))) t = ParamOps.removeKey(t, "a", id, 5).getOrFail()
        val clip = checkNotNull(t.track("a1")!!.clip("a"))
        assertTrue(clip.params.isEmpty())
        assertEquals(-12.0, clip.gainDb, 0.0)
        assertEquals(0.5, clip.audio.pan, 0.0)
        assertEquals(4.0, clip.audio.eq.bands[1].gainDb, 0.0)
    }

    // endregion

    // region pose parameters (projected onto the joint keyframes)

    @Test
    fun `a pose parameter edits one component of the joint keyframe`() {
        var t = base()
        t = TimelineOps.setKeyframe(t, "a", Keyframe(0, ClipTransform(positionX = 0.0, scaleX = 1.0))).getOrFail()
        t = TimelineOps.setKeyframe(t, "a", Keyframe(60, ClipTransform(positionX = 600.0, scaleX = 3.0))).getOrFail()
        // Adding a position key at frame 30 keeps the animated scale the clip has there.
        t = ParamOps.setKey(t, "a", PoseParams.POSITION_X.id, key(30, 100.0)).getOrFail()
        val clip = t.a()
        assertEquals(listOf(0L, 30L, 60L), clip.keyframes.map { it.frame })
        assertEquals(100.0, clip.keyframes[1].transform.positionX, 0.0)
        assertEquals(2.0, clip.keyframes[1].transform.scaleX, 1e-12)
        // The per-parameter view of the same data.
        assertEquals(listOf(0.0, 100.0, 600.0), clip.paramKeys(PoseParams.POSITION_X.id).map { it.value })
        assertEquals(listOf(1.0, 2.0, 3.0), clip.paramKeys(PoseParams.SCALE_X.id).map { it.value })
        assertEquals(350.0, clip.paramValueAt(PoseParams.POSITION_X.id, 45)!!, 1e-9)
        // A pose track never lives in `params`.
        assertTrue(clip.params.isEmpty())
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `removing a pose key removes the whole keyframe`() {
        var t = base()
        t = TimelineOps.setKeyframe(t, "a", Keyframe(10, ClipTransform(positionX = 5.0))).getOrFail()
        t = TimelineOps.setKeyframe(t, "a", Keyframe(20, ClipTransform(positionX = 9.0))).getOrFail()
        t = ParamOps.removeKey(t, "a", PoseParams.SCALE_X.id, 10).getOrFail()
        assertEquals(listOf(20L), t.a().keyframes.map { it.frame })
    }

    @Test
    fun `a pose key can use a Bezier segment`() {
        var t = base()
        t = TimelineOps.setKeyframe(t, "a", Keyframe(0, ClipTransform(positionX = 0.0))).getOrFail()
        t = TimelineOps.setKeyframe(t, "a", Keyframe(50, ClipTransform(positionX = 100.0))).getOrFail()
        t = ParamOps.setKeyShape(t, "a", PoseParams.POSITION_X.id, 0, Interpolation.BEZIER, BezierHandle(0.9, 0.0), BezierHandle(0.2, 0.0)).getOrFail()
        val clip = t.a()
        assertEquals(Interpolation.BEZIER, clip.keyframes[0].interpolation)
        assertTrue(clip.transformAt(10).positionX < 10.0) // slow start
        // The native exporter gets one linear key per frame over the Bezier segment, exact at every frame.
        val baked = Keyframes.bakedForNative(clip.keyframes, clip.transform)
        assertTrue(baked.none { it.interpolation == Interpolation.BEZIER })
        for (frame in 0L..60L) {
            assertEquals(clip.transformAt(frame).positionX, Keyframes.evaluate(baked, frame, clip.transform).positionX, 1e-9)
        }
    }

    // endregion

    // region editing the clip keeps the tracks valid

    @Test
    fun `removing an effect drops its tracks and keeps the others`() {
        var t = TimelineOps.addEffect(base(), "a", Effect("s1", EffectType.SATURATION)).getOrFail()
        t = t.setKey(contrast, key(5, 1.0)).setKey(ParamIds.fx("s1", 0), key(5, 1.2))
        assertEquals(2, t.a().params.size)
        t = TimelineOps.removeEffect(t, "a", "c1").getOrFail()
        assertEquals(listOf(ParamIds.fx("s1", 0)), t.a().params.map { it.paramId })
        assertTrue(t.invariantViolations().isEmpty())
        t = TimelineOps.clearFx(t, "a").getOrFail()
        assertTrue(t.a().params.isEmpty())
    }

    @Test
    fun `a clip with a track that points at nothing is reported`() {
        val broken = base().a().copy(params = listOf(ParamTrack(ParamIds.fx("gone", 0), listOf(key(1, 1.0)))))
        val t = timeline(track("v1", broken))
        assertTrue(t.invariantViolations().any { it.contains("does not exist") })
        val twice = base().a().let { it.copy(params = listOf(ParamTrack(contrast, listOf(key(1, 1.0))), ParamTrack(contrast, listOf(key(2, 1.0))))) }
        assertTrue(timeline(track("v1", twice)).invariantViolations().any { it.contains("two tracks") })
        val outside = base().a().copy(params = listOf(ParamTrack(contrast, listOf(key(150, 1.0)))))
        assertTrue(timeline(track("v1", outside)).invariantViolations().any { it.contains("outside the clip") })
    }

    @Test
    fun `splitting a clip crops the tracks and the halves continue the same curve`() {
        var t = base().setKey(contrast, key(0, 0.0)).setKey(contrast, key(99, 2.0))
        val original = t.a()
        t = TimelineOps.split(t, "v1", FrameIndex(40), "b").getOrFail()
        val left = t.a()
        val right = checkNotNull(t.track("v1")!!.clip("b"))
        for (frame in 0L until 40L) assertEquals(original.paramValueAt(contrast, frame)!!, left.paramValueAt(contrast, frame)!!, 1e-9)
        for (frame in 0L until 60L) assertEquals(original.paramValueAt(contrast, frame + 40)!!, right.paramValueAt(contrast, frame)!!, 1e-9)
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `trimming the start of a clip keeps the curve over what remains`() {
        var t = base().setKey(contrast, key(0, 0.0)).setKey(contrast, key(99, 2.0))
        val original = t.a()
        t = TimelineOps.trim(t, "a", TrimEdge.START, FrameIndex(30)).getOrFail()
        val clip = t.a()
        for (frame in 0L until clip.durationFrames) {
            assertEquals(original.paramValueAt(contrast, frame + 30)!!, clip.paramValueAt(contrast, frame)!!, 1e-9)
        }
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `changing the speed stretches the tracks with the clip`() {
        var t = base().setKey(contrast, key(0, 0.0)).setKey(contrast, key(99, 2.0))
        t = TimelineOps.setSpeed(t, "a", 2, 1).getOrFail() // twice as fast: 50 frames
        val clip = t.a()
        assertEquals(50L, clip.durationFrames)
        assertEquals(listOf(0L, 49L), clip.paramKeys(contrast).map { it.frame })
        assertTrue(t.invariantViolations().isEmpty())
    }

    // endregion

    // region undo

    @Test
    fun `every parameter command undoes exactly`() {
        var history = EditHistory(base())
        val start = history.timeline
        val commands = listOf(
            EditCommand.SetParamKey("a", contrast, key(10, 0.5)),
            EditCommand.SetParamKey("a", contrast, key(40, 1.5)),
            EditCommand.SetParamKeyShape("a", contrast, 10, Interpolation.BEZIER, BezierHandle(0.2, 0.1), BezierHandle(0.8, 0.9)),
            EditCommand.MoveParamKey("a", contrast, 40, 60),
            EditCommand.PasteParamKeys("a", contrast, listOf(key(70, 1.0), key(80, 0.0))),
            EditCommand.RemoveParamKey("a", contrast, 70),
            EditCommand.ClearParamTrack("a", contrast),
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

    // endregion
}

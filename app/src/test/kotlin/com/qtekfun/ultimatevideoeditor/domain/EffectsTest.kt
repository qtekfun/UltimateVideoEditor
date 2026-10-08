package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectsTest {

    private fun twoClips(): Timeline = timeline(track("v1", clip("a", 0, 100), clip("b", 100, 100)))

    private fun Timeline.clipA(): Clip = checkNotNull(track("v1")!!.clip("a"))

    private fun effect(id: String, type: EffectType = EffectType.BLUR, values: List<Double> = type.defaults) = Effect(id, type, values)

    // --- model ---------------------------------------------------------------------------

    @Test
    fun `every effect type has defaults inside its own bounds`() {
        for (type in EffectType.entries) {
            assertEquals(type.name, null, Effect("e", type).problem())
            assertEquals(type.params.size, type.defaults.size)
        }
    }

    @Test
    fun `codes are unique and round trip`() {
        assertEquals(EffectType.entries.size, EffectType.entries.map { it.code }.toSet().size)
        for (type in EffectType.entries) assertEquals(type, EffectType.fromCode(type.code))
        assertNull(EffectType.fromCode(0))
        for (mode in BlendMode.entries) assertEquals(mode, BlendMode.fromCode(mode.code))
        assertEquals(BlendMode.NORMAL, BlendMode.fromCode(0))
    }

    @Test
    fun `effect validation rejects wrong arity, out of range and non finite values`() {
        assertTrue(Effect("e", EffectType.VIGNETTE, listOf(0.5)).problem()!!.contains("takes 2 values"))
        assertTrue(Effect("e", EffectType.CONTRAST, listOf(2.5)).problem() != null)
        assertTrue(Effect("e", EffectType.CONTRAST, listOf(Double.NaN)).problem() != null)
        assertTrue(Effect("e", EffectType.EXPOSURE, listOf(Double.POSITIVE_INFINITY)).problem() != null)
        assertTrue(Effect(" ", EffectType.BLUR).problem() != null)
        assertNull(Effect("e", EffectType.CONTRAST, listOf(2.0)).problem())
        assertNull(Effect("e", EffectType.CONTRAST, listOf(0.0)).problem())
    }

    @Test
    fun `mask validation covers centre, size and feather`() {
        assertNull(ClipMask().problem())
        assertTrue(ClipMask(centerX = 0.7).problem() != null)
        assertTrue(ClipMask(width = 0.0).problem() != null)
        assertTrue(ClipMask(height = 3.0).problem() != null)
        assertTrue(ClipMask(feather = -0.1).problem() != null)
        assertTrue(ClipMask(feather = 0.6).problem() != null)
        assertTrue(ClipMask(width = Double.NaN).problem() != null)
    }

    @Test
    fun `fx is neutral only without effects, blend and mask`() {
        assertTrue(ClipFx.NONE.isNeutral)
        assertFalse(ClipFx(blendMode = BlendMode.ADD).isNeutral)
        assertFalse(ClipFx(mask = ClipMask()).isNeutral)
        assertFalse(ClipFx(effects = listOf(effect("e"))).isNeutral)
    }

    @Test
    fun `duplicate effect ids and too many effects are rejected`() {
        assertTrue(ClipFx(effects = listOf(effect("e"), effect("e"))).problem()!!.contains("duplicate"))
        val many = (0..ClipFx.MAX_EFFECTS).map { effect("e$it") }
        assertTrue(ClipFx(effects = many).problem()!!.contains("at most"))
        assertNull(ClipFx(effects = many.take(ClipFx.MAX_EFFECTS)).problem())
    }

    // --- operations ----------------------------------------------------------------------

    @Test
    fun `add effect appends in order and keeps the timeline valid`() {
        var t = twoClips()
        t = TimelineOps.addEffect(t, "a", effect("e1", EffectType.BLUR)).getOrFail()
        t = TimelineOps.addEffect(t, "a", effect("e2", EffectType.SEPIA)).getOrFail()

        assertEquals(listOf("e1", "e2"), t.clipA().fx.effects.map { it.id })
        assertTrue(t.invariantViolations().isEmpty())
        assertTrue(checkNotNull(t.track("v1")!!.clip("b")).fx.isNeutral)
    }

    @Test
    fun `add rejects duplicate ids, bad values and unknown clips`() {
        val t = TimelineOps.addEffect(twoClips(), "a", effect("e1")).getOrFail()

        assertTrue(TimelineOps.addEffect(t, "a", effect("e1")).errorOrFail() is EditError.InvalidEffect)
        assertTrue(TimelineOps.addEffect(t, "a", effect("e9", EffectType.BLUR, listOf(5.0))).errorOrFail() is EditError.InvalidEffect)
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.addEffect(t, "zz", effect("e9")).errorOrFail())
    }

    @Test
    fun `audio clips cannot carry effects, blend or mask`() {
        val t = timeline(track("a1", clip("s", 0, 100), type = TrackType.AUDIO))

        assertTrue(TimelineOps.addEffect(t, "s", effect("e")).errorOrFail() is EditError.InvalidEffect)
        assertTrue(TimelineOps.setBlendMode(t, "s", BlendMode.ADD).errorOrFail() is EditError.InvalidEffect)
        assertTrue(TimelineOps.setMask(t, "s", ClipMask()).errorOrFail() is EditError.InvalidEffect)
    }

    @Test
    fun `title clips can carry effects`() {
        val title = clip("t", 0, 50, asset = null).copy(title = TitleContent("hello"))
        val t = timeline(track("t1", title, type = TrackType.TITLE))

        val result = TimelineOps.addEffect(t, "t", effect("e", EffectType.VIGNETTE)).getOrFail()
        assertEquals(1, result.track("t1")!!.clip("t")!!.fx.effects.size)
        assertTrue(result.invariantViolations().isEmpty())
    }

    @Test
    fun `remove effect and unknown effect ids`() {
        var t = TimelineOps.addEffect(twoClips(), "a", effect("e1")).getOrFail()
        t = TimelineOps.addEffect(t, "a", effect("e2", EffectType.SEPIA)).getOrFail()

        t = TimelineOps.removeEffect(t, "a", "e1").getOrFail()
        assertEquals(listOf("e2"), t.clipA().fx.effects.map { it.id })
        assertEquals(EditError.EffectNotFound("e1"), TimelineOps.removeEffect(t, "a", "e1").errorOrFail())
    }

    @Test
    fun `set values changes only that effect and validates`() {
        var t = TimelineOps.addEffect(twoClips(), "a", effect("e1", EffectType.CONTRAST)).getOrFail()
        t = TimelineOps.addEffect(t, "a", effect("e2", EffectType.SEPIA)).getOrFail()

        val changed = TimelineOps.setEffectValues(t, "a", "e1", listOf(1.5)).getOrFail()
        assertEquals(listOf(1.5), changed.clipA().fx.effect("e1")!!.values)
        assertEquals(EffectType.SEPIA.defaults, changed.clipA().fx.effect("e2")!!.values)
        assertEquals(EffectType.CONTRAST, changed.clipA().fx.effect("e1")!!.type)

        assertTrue(TimelineOps.setEffectValues(t, "a", "e1", listOf(9.0)).errorOrFail() is EditError.InvalidEffect)
        assertTrue(TimelineOps.setEffectValues(t, "a", "e1", emptyList()).errorOrFail() is EditError.InvalidEffect)
        assertEquals(EditError.EffectNotFound("nope"), TimelineOps.setEffectValues(t, "a", "nope", listOf(1.0)).errorOrFail())
    }

    @Test
    fun `move effect reorders and clamps the index`() {
        var t = twoClips()
        for (id in listOf("e1", "e2", "e3")) t = TimelineOps.addEffect(t, "a", effect(id)).getOrFail()

        assertEquals(listOf("e3", "e1", "e2"), TimelineOps.moveEffect(t, "a", "e3", 0).getOrFail().clipA().fx.effects.map { it.id })
        assertEquals(listOf("e2", "e3", "e1"), TimelineOps.moveEffect(t, "a", "e1", 99).getOrFail().clipA().fx.effects.map { it.id })
        assertEquals(listOf("e2", "e1", "e3"), TimelineOps.moveEffect(t, "a", "e2", -5).getOrFail().clipA().fx.effects.map { it.id })
        assertEquals(EditError.EffectNotFound("zz"), TimelineOps.moveEffect(t, "a", "zz", 0).errorOrFail())
    }

    @Test
    fun `blend mode and mask set and clear`() {
        var t = TimelineOps.setBlendMode(twoClips(), "a", BlendMode.SCREEN).getOrFail()
        t = TimelineOps.setMask(t, "a", ClipMask(shape = MaskShape.ELLIPSE, invert = true)).getOrFail()

        assertEquals(BlendMode.SCREEN, t.clipA().fx.blendMode)
        assertEquals(MaskShape.ELLIPSE, t.clipA().fx.mask!!.shape)

        assertTrue(TimelineOps.setMask(t, "a", ClipMask(width = 0.0)).errorOrFail() is EditError.InvalidEffect)

        t = TimelineOps.setMask(t, "a", null).getOrFail()
        assertNull(t.clipA().fx.mask)
        t = TimelineOps.clearFx(t, "a").getOrFail()
        assertTrue(t.clipA().fx.isNeutral)
    }

    // --- interaction with other edits ----------------------------------------------------

    private fun styled(): Timeline {
        var t = timeline(track("v1", clip("a", 0, 100)))
        t = TimelineOps.addEffect(t, "a", effect("e1", EffectType.SEPIA)).getOrFail()
        t = TimelineOps.setBlendMode(t, "a", BlendMode.MULTIPLY).getOrFail()
        return TimelineOps.setMask(t, "a", ClipMask()).getOrFail()
    }

    @Test
    fun `split gives both halves the same fx`() {
        val t = TimelineOps.split(styled(), "v1", f(40), "a2").getOrFail()
        val clips = t.track("v1")!!.clips

        assertEquals(2, clips.size)
        assertTrue(clips.all { it.fx == styled().track("v1")!!.clips.single().fx })
        assertTrue(t.invariantViolations().isEmpty())
    }

    @Test
    fun `trim and move keep fx`() {
        var t = TimelineOps.trim(styled(), "a", TrimEdge.END, f(60)).getOrFail()
        t = TimelineOps.move(t, "a", f(30)).getOrFail()

        assertEquals(styled().track("v1")!!.clip("a")!!.fx, t.track("v1")!!.clip("a")!!.fx)
    }

    @Test
    fun `overwrite pieces of a styled clip keep its fx`() {
        val t = TimelineOps.overwrite(styled(), "v1", clip("n", 40, 20)).getOrFail()
        val fx = styled().track("v1")!!.clips.single().fx

        assertEquals(fx, t.track("v1")!!.clip("a")!!.fx)
        assertTrue(t.track("v1")!!.clip("n")!!.fx.isNeutral)
        assertTrue(t.track("v1")!!.clips.filter { it.id != "n" }.all { it.fx == fx })
    }

    @Test
    fun `inserting a clip with invalid fx is refused`() {
        val bad = clip("n", 200, 20).copy(fx = ClipFx(effects = listOf(Effect("e", EffectType.BLUR, listOf(7.0)))))

        assertTrue(TimelineOps.overwrite(twoClips(), "v1", bad).errorOrFail() is EditError.InvalidClip)
    }

    @Test
    fun `invariants flag invalid fx on loaded clips`() {
        val bad = timeline(track("v1", clip("a", 0, 10).copy(fx = ClipFx(mask = ClipMask(width = 0.0)))))
        assertTrue(bad.invariantViolations().any { it.contains("fx") })

        val audio = timeline(track("a1", clip("s", 0, 10).copy(fx = ClipFx(blendMode = BlendMode.ADD)), type = TrackType.AUDIO))
        assertTrue(audio.invariantViolations().any { it.contains("visual effects") })
    }

    // --- undo ----------------------------------------------------------------------------

    @Test
    fun `every fx command is one undo step`() {
        var history = EditHistory(twoClips())
        val steps = listOf<EditCommand>(
            EditCommand.AddEffect("a", effect("e1", EffectType.CONTRAST)),
            EditCommand.SetEffectValues("a", "e1", listOf(1.4)),
            EditCommand.AddEffect("a", effect("e2", EffectType.SEPIA)),
            EditCommand.MoveEffect("a", "e2", 0),
            EditCommand.SetBlendMode("a", BlendMode.OVERLAY),
            EditCommand.SetMask("a", ClipMask()),
            EditCommand.RemoveEffect("a", "e1"),
            EditCommand.ClearFx("a"),
        )
        val snapshots = mutableListOf(history.timeline)
        for (step in steps) {
            history = (history.execute(step) as EditResult.Success).value
            snapshots += history.timeline
        }

        for (i in steps.indices.reversed()) {
            history = history.undo()
            assertEquals("after undoing step $i", snapshots[i], history.timeline)
        }
        assertFalse(history.canUndo)
        repeat(steps.size) { history = history.redo() }
        assertEquals(snapshots.last(), history.timeline)
    }
}

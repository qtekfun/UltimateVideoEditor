package com.qtekfun.ultimatevideoeditor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeTest {

    private fun curve(vararg points: Pair<Double, Double>) = GradeCurve(points.map { CurvePoint(it.first, it.second) })

    private fun twoClips(): Timeline = timeline(track("v1", clip("a", 0, 100), clip("b", 100, 100)))

    private fun Timeline.fxOf(id: String): ClipFx = checkNotNull(track("v1")!!.clip(id)).fx

    // --- curves ----------------------------------------------------------------------------

    @Test
    fun `the default curve is the identity and bakes to a ramp`() {
        assertTrue(GradeCurve().isIdentity)
        assertTrue(GradeCurves.IDENTITY.isIdentity)
        val baked = GradeCurve().bake()
        assertEquals(GradeCurves.SAMPLES, baked.size)
        for (i in baked.indices) assertEquals(i / 32.0, baked[i], 1e-12)
    }

    @Test
    fun `the curves bake interleaves master, red, green, blue per sample`() {
        val curves = GradeCurves(
            master = curve(0.0 to 0.0, 1.0 to 1.0),
            red = curve(0.0 to 0.2, 1.0 to 0.2),
            green = curve(0.0 to 0.4, 1.0 to 0.4),
            blue = curve(0.0 to 0.6, 1.0 to 0.6),
        )
        val baked = curves.bake()
        assertEquals(GradeCurves.SAMPLES * 4, baked.size)
        assertEquals(0.0, baked[0], 1e-12)
        assertEquals(0.2, baked[1], 1e-12)
        assertEquals(0.4, baked[2], 1e-12)
        assertEquals(0.6, baked[3], 1e-12)
        assertEquals(1.0, baked[32 * 4], 1e-12)
        assertEquals(0.6, baked[32 * 4 + 3], 1e-12)
    }

    @Test
    fun `a curve passes through its control points`() {
        val c = curve(0.0 to 0.0, 0.25 to 0.1, 0.5 to 0.6, 1.0 to 1.0)
        assertEquals(0.1, c.valueAt(0.25), 1e-9)
        assertEquals(0.6, c.valueAt(0.5), 1e-9)
        assertEquals(0.0, c.valueAt(0.0), 1e-12)
        assertEquals(1.0, c.valueAt(1.0), 1e-12)
    }

    @Test
    fun `a curve never overshoots and stays monotone when its points do`() {
        val c = curve(0.0 to 0.0, 0.1 to 0.05, 0.12 to 0.9, 0.5 to 0.92, 1.0 to 1.0)
        var previous = -1.0
        var x = 0.0
        while (x <= 1.0) {
            val y = c.valueAt(x)
            assertTrue("y=$y at x=$x", y in 0.0..1.0)
            assertTrue("not monotone at x=$x", y >= previous - 1e-12)
            previous = y
            x += 0.005
        }
    }

    @Test
    fun `an S curve darkens shadows and brightens highlights`() {
        val c = curve(0.0 to 0.0, 0.25 to 0.15, 0.75 to 0.85, 1.0 to 1.0)
        assertTrue(c.valueAt(0.2) < 0.2)
        assertTrue(c.valueAt(0.8) > 0.8)
        assertEquals(0.5, c.valueAt(0.5), 1e-9)
    }

    @Test
    fun `a curve is flat outside its end points`() {
        val c = curve(0.2 to 0.3, 0.8 to 0.7)
        assertEquals(0.3, c.valueAt(0.0), 1e-12)
        assertEquals(0.3, c.valueAt(0.2), 1e-12)
        assertEquals(0.7, c.valueAt(1.0), 1e-12)
        assertEquals(0.3, c.valueAt(-5.0), 1e-12)
        assertEquals(0.7, c.valueAt(5.0), 1e-12)
    }

    @Test
    fun `curve validation covers count, range and order`() {
        assertNull(GradeCurve().problem())
        assertTrue(GradeCurve(listOf(CurvePoint(0.0, 0.0))).problem() != null)
        assertTrue(GradeCurve((0..8).map { CurvePoint(it / 8.0, it / 8.0) }).problem()!!.contains("at most"))
        assertNull(GradeCurve((0..7).map { CurvePoint(it / 7.0, it / 7.0) }).problem())
        assertTrue(curve(0.0 to 0.0, 0.5 to 1.2).problem() != null)
        assertTrue(curve(0.0 to 0.0, 0.0 to 1.0).problem()!!.contains("increasing"))
        assertTrue(curve(0.5 to 0.5, 0.2 to 0.2).problem()!!.contains("increasing"))
        assertTrue(curve(0.0 to Double.NaN, 1.0 to 1.0).problem() != null)
    }

    @Test
    fun `GradeCurves reports which curve is wrong`() {
        assertTrue(GradeCurves(green = curve(0.0 to 0.0, 0.0 to 1.0)).problem()!!.startsWith("green"))
        assertNull(GradeCurves().problem())
    }

    // --- the effect type -------------------------------------------------------------------

    @Test
    fun `the colour grade has 21 neutral parameters on the wire`() {
        val type = EffectType.COLOR_GRADE
        assertEquals(14, type.code)
        assertEquals(21, type.params.size)
        assertEquals(1.0, type.defaults[15], 0.0) // contrast
        assertEquals(0.5, type.defaults[16], 0.0) // pivot
        assertEquals(1.0, type.defaults[17], 0.0) // saturation
        assertEquals(0.0, type.defaults[0], 0.0)
        assertNull(Effect("g", type).problem())
    }

    @Test
    fun `curves belong to a colour grade only`() {
        assertTrue(Effect("e", EffectType.BLUR, curves = GradeCurves.IDENTITY).problem()!!.contains("colour grade"))
        assertNull(Effect("g", EffectType.COLOR_GRADE, curves = GradeCurves.IDENTITY).problem())
        assertTrue(Effect("g", EffectType.COLOR_GRADE, curves = GradeCurves(red = curve(0.0 to 0.0, 0.0 to 1.0))).problem() != null)
    }

    // --- operations ------------------------------------------------------------------------

    @Test
    fun `setEffectCurves changes only the curves and keeps identity as null`() {
        val withGrade = TimelineOps.addEffect(twoClips(), "a", Effect("g", EffectType.COLOR_GRADE)).getOrFail()
        val s = GradeCurves(master = curve(0.0 to 0.0, 0.25 to 0.15, 0.75 to 0.85, 1.0 to 1.0))
        val curved = TimelineOps.setEffectCurves(withGrade, "a", "g", s).getOrFail()
        assertEquals(s, curved.fxOf("a").effect("g")!!.curves)
        assertEquals(EffectType.COLOR_GRADE.defaults, curved.fxOf("a").effect("g")!!.values)
        val cleared = TimelineOps.setEffectCurves(curved, "a", "g", GradeCurves.IDENTITY).getOrFail()
        assertNull(cleared.fxOf("a").effect("g")!!.curves)
        assertEquals(withGrade, cleared)
    }

    @Test
    fun `setEffectCurves rejects other effects, unknown ids and invalid curves`() {
        val t = TimelineOps.addEffect(twoClips(), "a", Effect("b", EffectType.BLUR)).getOrFail()
        assertTrue(TimelineOps.setEffectCurves(t, "a", "b", GradeCurves.IDENTITY).errorOrFail() is EditError.InvalidEffect)
        assertEquals(EditError.EffectNotFound("zz"), TimelineOps.setEffectCurves(t, "a", "zz", null).errorOrFail())
        val g = TimelineOps.addEffect(twoClips(), "a", Effect("g", EffectType.COLOR_GRADE)).getOrFail()
        val bad = GradeCurves(red = curve(0.0 to 0.0, 0.0 to 1.0))
        assertTrue(TimelineOps.setEffectCurves(g, "a", "g", bad).errorOrFail() is EditError.InvalidEffect)
        assertEquals(EditError.ClipNotFound("nope"), TimelineOps.setEffectCurves(g, "nope", "g", null).errorOrFail())
    }

    @Test
    fun `setGrade appends a new grade or replaces the existing one in a single step`() {
        val values = EffectType.COLOR_GRADE.defaults.toMutableList().also { it[11] = 0.5 }
        val curves = GradeCurves(master = curve(0.0 to 0.1, 1.0 to 0.9))
        val added = TimelineOps.setGrade(twoClips(), "a", "g1", values, curves).getOrFail()
        val grade = added.fxOf("a").effect("g1")!!
        assertEquals(EffectType.COLOR_GRADE, grade.type)
        assertEquals(values, grade.values)
        assertEquals(curves, grade.curves)
        val replaced = TimelineOps.setGrade(added, "a", "g1", EffectType.COLOR_GRADE.defaults, null).getOrFail()
        assertEquals(1, replaced.fxOf("a").effects.size)
        assertEquals(EffectType.COLOR_GRADE.defaults, replaced.fxOf("a").effect("g1")!!.values)
        assertNull(replaced.fxOf("a").effect("g1")!!.curves)
    }

    @Test
    fun `setGrade refuses an effect that is not a colour grade and out of range values`() {
        val t = TimelineOps.addEffect(twoClips(), "a", Effect("b", EffectType.BLUR)).getOrFail()
        assertTrue(TimelineOps.setGrade(t, "a", "b", EffectType.COLOR_GRADE.defaults, null).errorOrFail() is EditError.InvalidEffect)
        val tooBig = EffectType.COLOR_GRADE.defaults.toMutableList().also { it[15] = 5.0 }
        assertTrue(TimelineOps.setGrade(twoClips(), "a", "g", tooBig, null).errorOrFail() is EditError.InvalidEffect)
    }

    @Test
    fun `grade commands undo exactly in one step`() {
        val start = EditHistory(twoClips())
        val values = EffectType.COLOR_GRADE.defaults.toMutableList().also { it[3] = 0.4 }
        val applied = start.execute(EditCommand.SetGrade("a", "g", values, GradeCurves(red = curve(0.0 to 0.0, 1.0 to 0.8)))).getOrFail()
        assertTrue(applied.canUndo)
        val curved = applied.execute(EditCommand.SetEffectCurves("a", "g", GradeCurves(blue = curve(0.0 to 0.1, 1.0 to 1.0)))).getOrFail()
        assertEquals(applied.timeline, curved.undo().timeline)
        assertEquals(start.timeline, applied.undo().timeline)
        assertEquals(curved.timeline, curved.undo().redo().timeline)
    }

    @Test
    fun `an audio clip cannot be graded`() {
        val t = timeline(track("a1", clip("m", 0, 100), type = TrackType.AUDIO))
        assertTrue(TimelineOps.setGrade(t, "m", "g", EffectType.COLOR_GRADE.defaults, null).errorOrFail() is EditError.InvalidEffect)
    }

    // --- looks -----------------------------------------------------------------------------

    @Test
    fun `a look turns into a grade effect and back`() {
        val values = EffectType.COLOR_GRADE.defaults.toMutableList().also { it[17] = 1.4 }
        val curves = GradeCurves(master = curve(0.0 to 0.05, 1.0 to 1.0))
        val effect = Effect("e1", EffectType.COLOR_GRADE, values, curves)
        val look = GradeLook.of("l1", "Warm", effect)
        assertNull(look.problem())
        assertEquals(effect.copy(id = "e2"), look.toEffect("e2"))
        assertFalse(GradeLook.of("l2", "Plain", Effect("e", EffectType.COLOR_GRADE)).toEffect("x").curves != null)
    }

    @Test
    fun `look validation covers id, name and values`() {
        assertTrue(GradeLook(" ", "A", EffectType.COLOR_GRADE.defaults).problem() != null)
        assertTrue(GradeLook("i", " ", EffectType.COLOR_GRADE.defaults).problem() != null)
        assertTrue(GradeLook("i", "A", listOf(1.0)).problem() != null)
        assertNull(GradeLook("i", "A", EffectType.COLOR_GRADE.defaults).problem())
    }
}

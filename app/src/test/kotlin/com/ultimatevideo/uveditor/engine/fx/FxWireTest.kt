package com.ultimatevideo.uveditor.engine.fx

import com.ultimatevideo.uveditor.domain.BlendMode
import com.ultimatevideo.uveditor.domain.ClipFx
import com.ultimatevideo.uveditor.domain.ClipMask
import com.ultimatevideo.uveditor.domain.Effect
import com.ultimatevideo.uveditor.domain.CurvePoint
import com.ultimatevideo.uveditor.domain.EffectType
import com.ultimatevideo.uveditor.domain.GradeCurve
import com.ultimatevideo.uveditor.domain.GradeCurves
import com.ultimatevideo.uveditor.domain.MaskShape
import com.ultimatevideo.uveditor.domain.TimelineOps
import com.ultimatevideo.uveditor.domain.clip
import com.ultimatevideo.uveditor.domain.getOrFail
import com.ultimatevideo.uveditor.domain.renderClips
import com.ultimatevideo.uveditor.domain.timeline
import com.ultimatevideo.uveditor.domain.track
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FxWireTest {

    @Test
    fun `plain layers encode to nothing`() {
        assertEquals(0, FxWire.encode(emptyList()).size)
        assertEquals(0, FxWire.encode(listOf(ClipFx.NONE, ClipFx.NONE)).size)
    }

    @Test
    fun `a plain layer between styled ones still gets a header`() {
        val styled = ClipFx(blendMode = BlendMode.ADD)
        val wire = FxWire.encode(listOf(ClipFx.NONE, styled))

        assertEquals(2 * FxWire.HEADER_DOUBLES, wire.size)
        // plain: normal blend, no mask, unit mask box, no effects
        assertArrayEquals(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 0.0, 0.0), wire.copyOfRange(0, 9), 0.0)
        assertEquals(1.0, wire[FxWire.HEADER_DOUBLES], 0.0)
    }

    @Test
    fun `layout matches the native parser vectors`() {
        // The same numbers the C++ host test parses in effects_host_tests.cpp (fullLayerParses).
        val fx = ClipFx(
            effects = listOf(
                Effect("a", EffectType.CONTRAST, listOf(1.5)),
                Effect("b", EffectType.CHROMA_KEY, listOf(0.0, 1.0, 0.0, 0.4, 0.1, 0.2)),
            ),
            blendMode = BlendMode.SCREEN,
            mask = ClipMask(MaskShape.ELLIPSE, 0.1, -0.2, 0.5, 0.4, 0.05, invert = true),
        )

        val expected = doubleArrayOf(
            3.0, 2.0, 0.1, -0.2, 0.5, 0.4, 0.05, 1.0, 2.0,
            2.0, 1.0, 1.5,
            12.0, 6.0, 0.0, 1.0, 0.0, 0.4, 0.1, 0.2,
        )
        assertArrayEquals(expected, FxWire.encode(listOf(fx)), 1e-12)
    }

    @Test
    fun `effect type codes are the native enum values`() {
        val expected = mapOf(
            EffectType.BRIGHTNESS to 1, EffectType.CONTRAST to 2, EffectType.SATURATION to 3, EffectType.EXPOSURE to 4,
            EffectType.TEMPERATURE to 5, EffectType.TINT to 6, EffectType.BLUR to 7, EffectType.SHARPEN to 8,
            EffectType.VIGNETTE to 9, EffectType.GRAYSCALE to 10, EffectType.SEPIA to 11, EffectType.CHROMA_KEY to 12, EffectType.LUT to 13,
            EffectType.COLOR_GRADE to 14,
        )
        for ((type, code) in expected) assertEquals(type.name, code, type.code)
        assertEquals(EffectType.entries.size, expected.size)
        assertEquals(listOf(0, 1, 2, 3, 4), BlendMode.entries.map { it.code })
    }

    @Test
    fun `a colour grade writes its 21 values then 33 baked samples of four curves`() {
        val values = EffectType.COLOR_GRADE.defaults.toMutableList().also { it[11] = 0.5 }
        val curves = GradeCurves(red = GradeCurve(listOf(CurvePoint(0.0, 0.2), CurvePoint(1.0, 0.2))))
        val wire = FxWire.encode(listOf(ClipFx(effects = listOf(Effect("g", EffectType.COLOR_GRADE, values, curves)))))
        val at = FxWire.HEADER_DOUBLES
        assertEquals(14.0, wire[at], 0.0)
        assertEquals(153.0, wire[at + 1], 0.0)
        assertEquals(FxWire.HEADER_DOUBLES + 2 + 153, wire.size)
        assertEquals(0.5, wire[at + 2 + 11], 0.0)
        // sample 0 of each curve at wire[at + 2 + 21 + 0..3]: master 0, red 0.2, green 0, blue 0
        assertEquals(0.0, wire[at + 2 + 21], 1e-12)
        assertEquals(0.2, wire[at + 2 + 21 + 1], 1e-12)
        assertEquals(1.0, wire[at + 2 + 21 + 32 * 4 + 2], 1e-12) // green identity at x = 1
        assertEquals(0.2, wire[at + 2 + 21 + 32 * 4 + 1], 1e-12)
    }

    @Test
    fun `a grade without curves writes identity curves`() {
        val wire = FxWire.encode(listOf(ClipFx(effects = listOf(Effect("g", EffectType.COLOR_GRADE)))))
        val samples = wire.drop(FxWire.HEADER_DOUBLES + 2 + 21)
        assertEquals(132, samples.size)
        for (i in 0 until 33) for (c in 0 until 4) assertEquals(i / 32.0, samples[i * 4 + c], 1e-12)
    }

    @Test
    fun `the render plan carries each clips fx so preview and export see the same thing`() {
        var t = timeline(track("v1", clip("a", 0, 100), clip("b", 100, 100)))
        t = TimelineOps.addEffect(t, "b", Effect("e", EffectType.SEPIA)).getOrFail()
        t = TimelineOps.setBlendMode(t, "b", BlendMode.MULTIPLY).getOrFail()

        val clips = t.renderClips().associateBy { it.clipId }

        assertEquals(ClipFx.NONE, clips.getValue("a").fx)
        assertEquals(t.track("v1")!!.clip("b")!!.fx, clips.getValue("b").fx)
        assertEquals(
            FxWire.encode(listOf(ClipFx.NONE, clips.getValue("b").fx)).size,
            2 * FxWire.HEADER_DOUBLES + 2 + EffectType.SEPIA.params.size,
        )
    }
}

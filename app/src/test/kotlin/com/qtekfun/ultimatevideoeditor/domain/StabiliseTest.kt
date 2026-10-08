package com.qtekfun.ultimatevideoeditor.domain

import com.qtekfun.ultimatevideoeditor.engine.fx.FxWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StabiliseTest {
    private fun scene() = timeline(
        track("v1", clip("a", 0, 100), clip("b", 100, 60)),
        track("a1", clip("m", 0, 100), type = TrackType.AUDIO),
    )

    private fun Timeline.clipById(id: String) = checkNotNull(trackOfClip(id)?.clip(id))

    // region the op

    @Test
    fun `turning it on stores the settings with the strength rounded to a percent`() {
        val result = TimelineOps.setStabilise(scene(), "a", Stabilise(strength = 0.456, crop = StabCrop.TIGHT)).getOrFail()
        assertEquals(Stabilise(0.46, StabCrop.TIGHT), result.clipById("a").stabilise)
        assertNull(result.clipById("b").stabilise)
    }

    @Test
    fun `turning it off clears it`() {
        val on = TimelineOps.setStabilise(scene(), "a", Stabilise()).getOrFail()
        assertNull(TimelineOps.setStabilise(on, "a", null).getOrFail().clipById("a").stabilise)
    }

    @Test
    fun `only a clip that plays a video file can be stabilised`() {
        assertTrue(TimelineOps.setStabilise(scene(), "m", Stabilise()).errorOrFail() is EditError.InvalidClip)
        val title = timeline(track("t1", clip("t", 0, 50, asset = null).copy(title = TitleContent("Hi")), type = TrackType.TITLE))
        assertTrue(TimelineOps.setStabilise(title, "t", Stabilise()).errorOrFail() is EditError.InvalidClip)
        val still = timeline(track("v1", clip("p", 0, 50).copy(still = StillKind.PHOTO)))
        assertTrue(TimelineOps.setStabilise(still, "p", Stabilise()).errorOrFail() is EditError.InvalidClip)
        // Turning it off is always allowed, and an unknown clip is reported.
        assertNull(TimelineOps.setStabilise(still, "p", null).getOrFail().clipById("p").stabilise)
        assertEquals(EditError.ClipNotFound("zz"), TimelineOps.setStabilise(scene(), "zz", Stabilise()).errorOrFail())
    }

    @Test
    fun `a strength outside 0 to 1 is refused`() {
        assertTrue(TimelineOps.setStabilise(scene(), "a", Stabilise(strength = 1.5)).errorOrFail() is EditError.InvalidClip)
        assertTrue(TimelineOps.setStabilise(scene(), "a", Stabilise(strength = Double.NaN)).errorOrFail() is EditError.InvalidClip)
        assertNull(Stabilise(0.0).problem())
        assertNull(Stabilise(1.0).problem())
    }

    @Test
    fun `it is one undo step`() {
        val history = EditHistory(scene())
        val on = history.execute(EditCommand.SetStabilise("a", Stabilise(0.5, StabCrop.FULL))).getOrFail()
        assertEquals(StabCrop.FULL, on.timeline.clipById("a").stabilise?.crop)
        assertEquals(scene(), on.undo().timeline)
        assertEquals(on.timeline, on.undo().redo().timeline)
    }

    @Test
    fun `splitting and trimming keep the setting on every part`() {
        val on = TimelineOps.setStabilise(scene(), "a", Stabilise(0.7, StabCrop.TIGHT)).getOrFail()
        val split = TimelineOps.split(on, "v1", f(40), "a2").getOrFail()
        assertEquals(Stabilise(0.7, StabCrop.TIGHT), split.clipById("a").stabilise)
        assertEquals(Stabilise(0.7, StabCrop.TIGHT), split.clipById("a2").stabilise)
        val trimmed = TimelineOps.trim(on, "a", TrimEdge.END, f(80)).getOrFail()
        assertEquals(Stabilise(0.7, StabCrop.TIGHT), trimmed.clipById("a").stabilise)
    }

    // endregion

    // region the key

    @Test
    fun `the key is a stable non-zero 24-bit number`() {
        val a = StabKey.of("asset-1", Stabilise(0.5, StabCrop.MEDIUM))
        assertEquals(a, StabKey.of("asset-1", Stabilise(0.5, StabCrop.MEDIUM)))
        assertTrue(a in 1..0xFFFFFF)
        for (i in 0 until 500) assertTrue(StabKey.of("asset-$i", Stabilise(i / 500.0, StabCrop.entries[i % 3])) in 1..0xFFFFFF)
    }

    @Test
    fun `the key depends on the asset, the strength percent and the crop`() {
        val base = StabKey.of("asset-1", Stabilise(0.5, StabCrop.MEDIUM))
        assertNotEquals(base, StabKey.of("asset-2", Stabilise(0.5, StabCrop.MEDIUM)))
        assertNotEquals(base, StabKey.of("asset-1", Stabilise(0.6, StabCrop.MEDIUM)))
        assertNotEquals(base, StabKey.of("asset-1", Stabilise(0.5, StabCrop.TIGHT)))
        // The settings are stored in percent, so equal percents share a key.
        assertEquals(base, StabKey.of("asset-1", Stabilise(0.5004, StabCrop.MEDIUM)))
    }

    @Test
    fun `keys of many distinct settings rarely collide`() {
        val keys = HashSet<Int>()
        var total = 0
        for (asset in 0 until 20) {
            for (percent in 0..100 step 5) {
                for (crop in StabCrop.entries) {
                    keys += StabKey.of("asset-$asset", Stabilise(percent / 100.0, crop))
                    total++
                }
            }
        }
        assertTrue("$total settings gave ${keys.size} keys", keys.size >= total - 2)
    }

    // endregion

    // region the render plan and the wire

    @Test
    fun `a stabilised video clip carries its key in the render plan, nothing else does`() {
        val on = TimelineOps.setStabilise(scene(), "a", Stabilise(0.5, StabCrop.MEDIUM)).getOrFail()
        val clips = on.renderClips().associateBy { it.clipId }
        assertEquals(StabKey.of("a", Stabilise(0.5, StabCrop.MEDIUM)), clips.getValue("a").fx.stabKey)
        assertNull(clips.getValue("b").fx.stabKey)
        assertNull(clips.getValue("m").fx.stabKey)
        assertFalse(clips.getValue("a").fx.isNeutral)
        assertTrue(clips.getValue("b").fx.isNeutral)
    }

    @Test
    fun `a stabilised clip is not neutral and the key is not part of the user's effects`() {
        val fx = ClipFx(stabKey = 77)
        assertFalse(fx.isNeutral)
        assertNull(fx.problem())
        assertTrue(fx.effects.isEmpty())
    }

    @Test
    fun `the stabiliser goes first on the wire with placeholders for the per-frame values`() {
        val brightness = Effect("e1", EffectType.BRIGHTNESS, listOf(0.25))
        val wire = FxWire.encode(listOf(ClipFx(effects = listOf(brightness), stabKey = 4242)))
        // header (9 doubles), then the stabiliser: type 15, 6 values, then the brightness effect.
        assertEquals(2.0, wire[8], 0.0)  // effect count: the user's one plus the stabiliser
        assertEquals(15.0, wire[9], 0.0)
        assertEquals(6.0, wire[10], 0.0)
        assertEquals(listOf(4242.0, 1.0, 0.0, 0.0, 0.0, 1.0), wire.slice(11..16))
        assertEquals(EffectType.BRIGHTNESS.code.toDouble(), wire[17], 0.0)
        assertEquals(1.0, wire[18], 0.0)
        assertEquals(0.25, wire[19], 0.0)
        assertEquals(20, wire.size)
    }

    @Test
    fun `a stabiliser alone makes a layer non-neutral on the wire and a plain scene stays empty`() {
        val alone = FxWire.encode(listOf(ClipFx(stabKey = 9)))
        assertEquals(1.0, alone[8], 0.0)
        assertEquals(15.0, alone[9], 0.0)
        assertEquals(0, FxWire.encode(listOf(ClipFx.NONE, ClipFx.NONE)).size)
    }

    // endregion

    @Test
    fun animatedEffectValuesKeepTheStabiliseKey() {
        val effect = Effect(id = "e1", type = EffectType.entries.first { it.defaults.isNotEmpty() })
        val fx = ClipFx(effects = listOf(effect), stabKey = 1234)
        val track = ParamTrack(ParamIds.fx("e1", 0), listOf(ParamKey(0, 0.0), ParamKey(10, 1.0)))
        val shown = fx.animatedAt(listOf(track), 5)
        assertEquals(1234, shown.stabKey)
    }
}

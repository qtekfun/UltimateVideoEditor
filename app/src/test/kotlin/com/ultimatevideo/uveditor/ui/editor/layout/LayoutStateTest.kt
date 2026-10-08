package com.ultimatevideo.uveditor.ui.editor.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutStateTest {
    private val phone = WindowMetrics(412f, 892f)
    private val phoneSideways = WindowMetrics(892f, 412f)
    private val tablet = WindowMetrics(1280f, 800f)
    private val foldClosedMedium = WindowMetrics(700f, 900f)

    // region window classes

    @Test
    fun `width classes follow the Material breakpoints`() {
        assertEquals(WidthClass.COMPACT, WidthClass.of(599f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(600f))
        assertEquals(WidthClass.MEDIUM, WidthClass.of(839f))
        assertEquals(WidthClass.EXPANDED, WidthClass.of(840f))
    }

    @Test
    fun `layout keys separate size class and orientation`() {
        assertEquals("compact-portrait", LayoutKey.of(phone).id)
        assertEquals("expanded-landscape", LayoutKey.of(phoneSideways).id)
        assertEquals("expanded-landscape", LayoutKey.of(tablet).id)
        assertEquals("medium-portrait", LayoutKey.of(foldClosedMedium).id)
    }

    @Test
    fun `side docks need 600 dp and side panels never take more than 45 percent`() {
        assertFalse(phone.sideDocksAllowed)
        assertTrue(foldClosedMedium.sideDocksAllowed)
        assertEquals(315f, foldClosedMedium.maxSideWidthDp, 0.01f)
        assertEquals(520f, tablet.maxSideWidthDp, 0.01f)
    }

    // endregion

    // region defaults and presets

    @Test
    fun `defaults put the tray beside the editor on wide windows and under it otherwise`() {
        assertEquals(Dock.BOTTOM, LayoutState.defaultFor(phone).tray.dock)
        assertEquals(Dock.BOTTOM, LayoutState.defaultFor(foldClosedMedium).tray.dock)
        assertEquals(Dock.LEFT, LayoutState.defaultFor(tablet).tray.dock)
        assertEquals(Dock.OVERLAY, LayoutState.defaultFor(tablet).inspector.dock)
        assertEquals(LayoutPreset.DEFAULT, LayoutState.defaultFor(tablet).preset)
    }

    @Test
    fun `every preset is valid in every window and remembers which preset it is`() {
        for (window in listOf(phone, phoneSideways, tablet, foldClosedMedium)) {
            for (preset in LayoutPreset.entries) {
                val state = LayoutState.preset(preset, window)
                assertEquals(preset, state.preset)
                assertEquals(state, state.clamped(window))
            }
        }
    }

    @Test
    fun `timeline focus gives the timeline the room and preview focus gives it to the preview`() {
        val timeline = LayoutState.preset(LayoutPreset.TIMELINE_FOCUS, phone)
        val preview = LayoutState.preset(LayoutPreset.PREVIEW_FOCUS, phone)
        assertTrue(timeline.previewFraction < LayoutState.DEFAULT_PREVIEW_FRACTION)
        assertTrue(preview.previewFraction > LayoutState.DEFAULT_PREVIEW_FRACTION)
        assertEquals(LaneHeight.LARGE, timeline.laneHeight)
        assertEquals(LaneHeight.SMALL, preview.laneHeight)
        assertTrue(timeline.tray.collapsed && preview.tray.collapsed)
    }

    @Test
    fun `two panels uses both side columns on wide windows and falls back on a phone`() {
        val wide = LayoutState.preset(LayoutPreset.TWO_PANELS, tablet)
        assertEquals(Dock.LEFT, wide.tray.dock)
        assertEquals(Dock.RIGHT, wide.inspector.dock)
        val narrow = LayoutState.preset(LayoutPreset.TWO_PANELS, phone)
        assertEquals(Dock.BOTTOM, narrow.tray.dock)
        assertEquals(Dock.OVERLAY, narrow.inspector.dock)
    }

    // endregion

    // region reducer

    @Test
    fun `dragging the preview divider changes the share and clamps it to its limits`() {
        val start = LayoutState.defaultFor(phone)
        assertEquals(0.5f, start.reduce(LayoutAction.SetPreviewFraction(0.5f), phone).previewFraction, 0.0001f)
        assertEquals(LayoutState.MIN_PREVIEW_FRACTION, start.reduce(LayoutAction.SetPreviewFraction(-3f), phone).previewFraction, 0.0001f)
        assertEquals(LayoutState.MAX_PREVIEW_FRACTION, start.reduce(LayoutAction.SetPreviewFraction(9f), phone).previewFraction, 0.0001f)
    }

    @Test
    fun `a manual change forgets the preset and a reset divider goes back to the default`() {
        val moved = LayoutState.defaultFor(phone).reduce(LayoutAction.SetPreviewFraction(0.6f), phone)
        assertNull(moved.preset)
        val back = moved.reduce(LayoutAction.ResetPreviewDivider, phone)
        assertEquals(LayoutState.DEFAULT_PREVIEW_FRACTION, back.previewFraction, 0.0001f)
    }

    @Test
    fun `side widths are clamped to the window and reset to the default`() {
        val start = LayoutState.defaultFor(tablet)
        assertEquals(WindowMetrics.MIN_SIDE_WIDTH_DP, start.reduce(LayoutAction.SetSideWidth(Side.LEFT, 10f), tablet).leftWidthDp, 0.0001f)
        assertEquals(tablet.maxSideWidthDp, start.reduce(LayoutAction.SetSideWidth(Side.RIGHT, 5000f), tablet).rightWidthDp, 0.0001f)
        val wide = start.reduce(LayoutAction.SetSideWidth(Side.LEFT, 450f), tablet)
        assertEquals(LayoutState.DEFAULT_SIDE_WIDTH_DP, wide.reduce(LayoutAction.ResetSideDivider(Side.LEFT), tablet).leftWidthDp, 0.0001f)
    }

    @Test
    fun `lane height steps through the three sizes and stops at the ends`() {
        var state = LayoutState.defaultFor(phone)
        assertEquals(LaneHeight.MEDIUM, state.laneHeight)
        state = state.reduce(LayoutAction.StepLaneHeight(1), phone)
        assertEquals(LaneHeight.LARGE, state.laneHeight)
        assertEquals(LaneHeight.LARGE, state.reduce(LayoutAction.StepLaneHeight(1), phone).laneHeight)
        state = state.reduce(LayoutAction.StepLaneHeight(-2), phone)
        assertEquals(LaneHeight.SMALL, state.laneHeight)
        assertEquals(LaneHeight.SMALL, state.reduce(LayoutAction.StepLaneHeight(-1), phone).laneHeight)
        assertEquals(LaneHeight.LARGE, state.reduce(LayoutAction.SetLaneHeight(LaneHeight.LARGE), phone).laneHeight)
    }

    @Test
    fun `lane scales are the ones the native timeline expects`() {
        assertEquals(listOf(0.75f, 1.0f, 1.4f), LaneHeight.entries.map { it.scale })
    }

    @Test
    fun `audio lane height is its own setting, same by default, and a manual change clears the preset`() {
        val start = LayoutState.defaultFor(phone)
        assertEquals(AudioLaneHeight.SAME, start.audioLaneHeight)
        val tall = start.reduce(LayoutAction.SetAudioLaneHeight(AudioLaneHeight.TALLER), phone)
        assertEquals(AudioLaneHeight.TALLER, tall.audioLaneHeight)
        assertEquals(LaneHeight.MEDIUM, tall.laneHeight)
        assertNull(tall.preset)
        // The lane height steps do not touch it; a reset brings it back to the same height.
        assertEquals(AudioLaneHeight.TALLER, tall.reduce(LayoutAction.StepLaneHeight(1), phone).audioLaneHeight)
        assertEquals(AudioLaneHeight.SAME, tall.reduce(LayoutAction.Reset, phone).audioLaneHeight)
    }

    @Test
    fun `audio lane factors are the ones the native timeline expects`() {
        assertEquals(listOf(1.0f, 1.5f, 2.0f), AudioLaneHeight.entries.map { it.factor })
    }

    @Test
    fun `a panel can only go to a dock it supports and a side dock needs room`() {
        val start = LayoutState.defaultFor(tablet)
        assertEquals(Dock.RIGHT, start.reduce(LayoutAction.SetDock(Panel.TRAY, Dock.RIGHT), tablet).tray.dock)
        assertEquals(Dock.LEFT, start.reduce(LayoutAction.SetDock(Panel.INSPECTOR, Dock.LEFT), tablet).inspector.dock)
        // The tray never overlays the timeline and the inspector never sits at the bottom.
        assertEquals(start, start.reduce(LayoutAction.SetDock(Panel.TRAY, Dock.OVERLAY), tablet))
        assertEquals(start, start.reduce(LayoutAction.SetDock(Panel.INSPECTOR, Dock.BOTTOM), tablet))
        // No room for a side dock on a phone: nothing changes.
        val onPhone = LayoutState.defaultFor(phone)
        assertEquals(onPhone, onPhone.reduce(LayoutAction.SetDock(Panel.TRAY, Dock.LEFT), phone))
    }

    @Test
    fun `collapsing and customising`() {
        val start = LayoutState.defaultFor(tablet)
        val collapsed = start.reduce(LayoutAction.SetCollapsed(Panel.TRAY, true), tablet)
        assertTrue(collapsed.tray.collapsed)
        assertFalse(collapsed.reduce(LayoutAction.SetCollapsed(Panel.TRAY, false), tablet).tray.collapsed)
        val customising = start.reduce(LayoutAction.SetCustomising(true), tablet)
        assertTrue(customising.customising)
        // Turning customise on is not a layout change: the preset is kept.
        assertEquals(LayoutPreset.DEFAULT, customising.preset)
        // A preset keeps customise mode on; reset leaves it.
        assertTrue(customising.reduce(LayoutAction.ApplyPreset(LayoutPreset.PREVIEW_FOCUS), tablet).customising)
        assertFalse(customising.reduce(LayoutAction.Reset, tablet).customising)
    }

    @Test
    fun `reset returns the defaults of the window`() {
        val messy = LayoutState.defaultFor(tablet)
            .reduce(LayoutAction.SetPreviewFraction(0.7f), tablet)
            .reduce(LayoutAction.SetSideWidth(Side.LEFT, 480f), tablet)
            .reduce(LayoutAction.SetLaneHeight(LaneHeight.SMALL), tablet)
        assertEquals(LayoutState.defaultFor(tablet), messy.reduce(LayoutAction.Reset, tablet))
    }

    // endregion

    // region clamping on window changes

    @Test
    fun `side docks drop back when the window gets too narrow`() {
        val wide = LayoutState.preset(LayoutPreset.TWO_PANELS, tablet)
        val squeezed = wide.clamped(phone)
        assertEquals(Dock.BOTTOM, squeezed.tray.dock)
        assertEquals(Dock.OVERLAY, squeezed.inspector.dock)
    }

    @Test
    fun `widths shrink with the window`() {
        val big = LayoutState.defaultFor(tablet).reduce(LayoutAction.SetSideWidth(Side.LEFT, 500f), tablet)
        assertEquals(foldClosedMedium.maxSideWidthDp, big.clamped(foldClosedMedium).leftWidthDp, 0.0001f)
    }

    @Test
    fun `clamping a layout that is already valid changes nothing`() {
        val state = LayoutState.preset(LayoutPreset.TWO_PANELS, tablet)
        assertEquals(state, state.clamped(tablet))
    }

    // endregion

    // region which panels a side shows

    @Test
    fun `a bottom tray gives way while the overlay inspector is open`() {
        val phone = LayoutState()
        assertEquals(true, bottomTrayShown(phone, inspectorOpen = false))
        assertEquals(false, bottomTrayShown(phone, inspectorOpen = true))
    }

    @Test
    fun `a bottom tray stays when the inspector is docked at a side`() {
        val docked = LayoutState().let { it.copy(inspector = it.inspector.copy(dock = Dock.RIGHT)) }
        assertEquals(true, bottomTrayShown(docked, inspectorOpen = true))
    }

    @Test
    fun `a tray docked at a side is never a bottom tray`() {
        val side = LayoutState().let { it.copy(tray = it.tray.copy(dock = Dock.LEFT)) }
        assertEquals(false, bottomTrayShown(side, inspectorOpen = false))
    }

    @Test
    fun `the inspector only takes a side column while it is open`() {
        val state = LayoutState.preset(LayoutPreset.TWO_PANELS, tablet)
        assertEquals(listOf(Panel.TRAY), visiblePanels(state, Side.LEFT, inspectorOpen = false))
        assertEquals(emptyList<Panel>(), visiblePanels(state, Side.RIGHT, inspectorOpen = false))
        assertEquals(listOf(Panel.INSPECTOR), visiblePanels(state, Side.RIGHT, inspectorOpen = true))
    }

    @Test
    fun `both panels can share a side and the column collapses only when all of them are`() {
        val state = LayoutState.defaultFor(tablet)
            .reduce(LayoutAction.SetDock(Panel.INSPECTOR, Dock.LEFT), tablet)
        val panels = visiblePanels(state, Side.LEFT, inspectorOpen = true)
        assertEquals(listOf(Panel.TRAY, Panel.INSPECTOR), panels)
        val oneCollapsed = state.reduce(LayoutAction.SetCollapsed(Panel.TRAY, true), tablet)
        assertFalse(sideCollapsed(oneCollapsed, panels))
        val both = oneCollapsed.reduce(LayoutAction.SetCollapsed(Panel.INSPECTOR, true), tablet)
        assertTrue(sideCollapsed(both, panels))
        assertFalse(sideCollapsed(both, emptyList()))
    }

    // endregion
}

class LayoutCodecTest {
    private val tablet = WindowMetrics(1280f, 800f)

    @Test
    fun `a layout survives a round trip`() {
        val state = LayoutState.preset(LayoutPreset.TWO_PANELS, tablet)
            .reduce(LayoutAction.SetPreviewFraction(0.55f), tablet)
            .reduce(LayoutAction.SetSideWidth(Side.RIGHT, 400f), tablet)
            .reduce(LayoutAction.SetLaneHeight(LaneHeight.LARGE), tablet)
            .reduce(LayoutAction.SetCollapsed(Panel.TRAY, true), tablet)
        assertEquals(state, LayoutCodec.decode(LayoutCodec.encode(state)))
    }

    @Test
    fun `the audio lane height is saved and an old file without it reads as the same height`() {
        val state = LayoutState.defaultFor(tablet).reduce(LayoutAction.SetAudioLaneHeight(AudioLaneHeight.TALL), tablet)
        assertEquals(AudioLaneHeight.TALL, LayoutCodec.decode(LayoutCodec.encode(state))!!.audioLaneHeight)
        assertEquals(AudioLaneHeight.SAME, LayoutCodec.decode("pf=0.5;lane=LARGE")!!.audioLaneHeight)
        assertEquals(AudioLaneHeight.SAME, LayoutCodec.decode("audiolane=HUGE;lane=LARGE")!!.audioLaneHeight)
    }

    @Test
    fun `customise mode is not part of what is saved`() {
        val state = LayoutState.defaultFor(tablet).copy(customising = true)
        assertFalse(LayoutCodec.decode(LayoutCodec.encode(state))!!.customising)
    }

    @Test
    fun `garbage and unknown fields are skipped`() {
        assertNull(LayoutCodec.decode(""))
        assertNull(LayoutCodec.decode("nonsense"))
        val decoded = LayoutCodec.decode("pf=abc;lw=NaN;future=1;tray=NOWHERE,0;insp=BOTTOM,1;lane=HUGE;preset=-")!!
        assertEquals(LayoutState(preset = null), decoded)
    }

    @Test
    fun `a dock the panel never uses is ignored`() {
        val decoded = LayoutCodec.decode("tray=OVERLAY,0;insp=LEFT,1")!!
        assertEquals(Dock.BOTTOM, decoded.tray.dock)
        assertEquals(PanelState(Dock.LEFT, true), decoded.inspector)
    }
}

class DragSupportTest {
    @Test
    fun `a drag is applied at most once per interval and nothing is lost`() {
        val coalescer = DragCoalescer(40)
        assertEquals(5f, coalescer.offer(5f, 0)!!, 0.0001f)
        assertNull(coalescer.offer(3f, 10))
        assertNull(coalescer.offer(2f, 30))
        assertEquals(6f, coalescer.offer(1f, 45)!!, 0.0001f)
        assertNull(coalescer.offer(4f, 50))
        assertEquals(4f, coalescer.flush(), 0.0001f)
        // After the drag ends the next one starts fresh.
        assertEquals(7f, coalescer.offer(7f, 51)!!, 0.0001f)
    }

    @Test
    fun `the total applied equals the total dragged`() {
        val coalescer = DragCoalescer(25)
        var applied = 0f
        var time = 0L
        repeat(100) { step ->
            coalescer.offer(0.5f + step % 3, time)?.let { applied += it }
            time += 7
        }
        applied += coalescer.flush()
        val dragged = (0 until 100).sumOf { (0.5f + it % 3).toDouble() }.toFloat()
        assertEquals(dragged, applied, 0.01f)
    }

    @Test
    fun `a tick only when reaching the target from outside`() {
        assertTrue(crossesTarget(0.45f, 0.402f, 0.4f, 0.01f))
        assertTrue(crossesTarget(0.3f, 0.395f, 0.4f, 0.01f))
        assertFalse(crossesTarget(0.402f, 0.399f, 0.4f, 0.01f))
        assertFalse(crossesTarget(0.5f, 0.45f, 0.4f, 0.01f))
    }
}

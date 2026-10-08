package com.ultimatevideo.uveditor.ui.editor.layout

import com.ultimatevideo.uveditor.engine.timeline.WaveformScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorLayoutControllerTest {
    private class MemoryStore : LayoutStore {
        val saved = mutableMapOf<LayoutKey, LayoutState>()
        var writes = 0

        override fun load(key: LayoutKey): LayoutState? = saved[key]

        override fun save(key: LayoutKey, state: LayoutState) {
            saved[key] = state
            writes++
        }
    }

    private val phone = WindowMetrics(412f, 892f)
    private val phoneSideways = WindowMetrics(892f, 412f)
    private val tablet = WindowMetrics(1280f, 800f)

    @Test
    fun `a fresh install starts from the defaults of the window`() {
        val controller = EditorLayoutController(MemoryStore(), tablet)
        assertEquals(LayoutState.defaultFor(tablet), controller.state)
        assertEquals(Dock.LEFT, controller.tray.dock)
    }

    @Test
    fun `a discrete change is saved and a drag is saved once when it ends`() {
        val store = MemoryStore()
        val controller = EditorLayoutController(store, phone)
        controller.dispatch(LayoutAction.SetLaneHeight(LaneHeight.LARGE))
        assertEquals(1, store.writes)
        for (step in 1..20) controller.dispatch(LayoutAction.SetPreviewFraction(0.4f + step * 0.01f), persist = false)
        assertEquals(1, store.writes)
        controller.commit()
        assertEquals(2, store.writes)
        assertEquals(0.6f, store.saved.getValue(LayoutKey.of(phone)).previewFraction, 0.0001f)
    }

    @Test
    fun `the audio lane height is saved with the layout`() {
        val store = MemoryStore()
        val controller = EditorLayoutController(store, phone)
        controller.dispatch(LayoutAction.SetAudioLaneHeight(AudioLaneHeight.TALLER))
        assertEquals(1, store.writes)
        assertEquals(AudioLaneHeight.TALLER, controller.audioLaneHeight)
        assertEquals(AudioLaneHeight.TALLER, store.saved.getValue(LayoutKey.of(phone)).audioLaneHeight)
        assertEquals(AudioLaneHeight.TALLER, EditorLayoutController(store, phone).audioLaneHeight)
    }

    @Test
    fun `each window class and orientation keeps its own layout`() {
        val store = MemoryStore()
        val controller = EditorLayoutController(store, phone)
        controller.dispatch(LayoutAction.SetLaneHeight(LaneHeight.LARGE))
        controller.onWindow(phoneSideways)
        // The sideways layout is new: defaults, not the portrait one.
        assertEquals(LaneHeight.MEDIUM, controller.state.laneHeight)
        controller.dispatch(LayoutAction.SetLaneHeight(LaneHeight.SMALL))
        controller.onWindow(phone)
        assertEquals(LaneHeight.LARGE, controller.state.laneHeight)
        controller.onWindow(phoneSideways)
        assertEquals(LaneHeight.SMALL, controller.state.laneHeight)
    }

    @Test
    fun `a size change inside the same class only clamps`() {
        val store = MemoryStore()
        val controller = EditorLayoutController(store, tablet)
        controller.dispatch(LayoutAction.SetSideWidth(Side.LEFT, 500f))
        val writesBefore = store.writes
        // Split screen narrows the window but it is still wide and landscape.
        controller.onWindow(WindowMetrics(900f, 800f))
        assertEquals(WindowMetrics(900f, 800f).maxSideWidthDp, controller.state.leftWidthDp, 0.0001f)
        assertEquals(writesBefore, store.writes)
    }

    @Test
    fun `a saved layout is clamped to the window it is loaded in`() {
        val store = MemoryStore()
        store.saved[LayoutKey.of(phone)] = LayoutState(tray = PanelState(Dock.LEFT), previewFraction = 5f)
        val controller = EditorLayoutController(store, phone)
        assertEquals(Dock.BOTTOM, controller.state.tray.dock)
        assertEquals(LayoutState.MAX_PREVIEW_FRACTION, controller.state.previewFraction, 0.0001f)
    }

    @Test
    fun `customise mode is never saved and reset clears it`() {
        val store = MemoryStore()
        val controller = EditorLayoutController(store, tablet)
        controller.dispatch(LayoutAction.SetCustomising(true))
        assertTrue(controller.customising)
        assertFalse(store.saved.getValue(LayoutKey.of(tablet)).customising)
        controller.dispatch(LayoutAction.Reset)
        assertFalse(controller.customising)
    }

    @Test
    fun `the store may know nothing`() {
        val controller = EditorLayoutController(NoLayoutStore, phone)
        controller.dispatch(LayoutAction.ApplyPreset(LayoutPreset.TIMELINE_FOCUS))
        assertEquals(LayoutPreset.TIMELINE_FOCUS, controller.state.preset)
        assertNull(NoLayoutStore.load(LayoutKey.of(phone)))
        assertNotNull(controller.window)
    }

    private class MemoryWaveform(var scale: WaveformScale = WaveformScale.LINEAR) : WaveformScaleStore {
        var writes = 0

        override fun load() = scale

        override fun save(scale: WaveformScale) {
            this.scale = scale
            writes++
        }
    }

    private class MemoryPlacement(var enabled: Boolean = false) : VideoAudioPlacementStore {
        var writes = 0

        override fun isOn() = enabled

        override fun setOn(on: Boolean) {
            this.enabled = on
            writes++
        }
    }

    @Test
    fun `video audio on a track is off by default, remembered, and survives presets and other windows`() {
        val placement = MemoryPlacement()
        val controller = EditorLayoutController(MemoryStore(), phone, placementStore = placement)
        assertFalse(controller.videoAudioOnTrack)

        controller.chooseVideoAudioOnTrack(true)
        controller.chooseVideoAudioOnTrack(true) // no second write for the same value
        assertTrue(placement.enabled)
        assertEquals(1, placement.writes)

        controller.dispatch(LayoutAction.Reset)
        controller.onWindow(phoneSideways)
        assertTrue(controller.videoAudioOnTrack)
        assertTrue(EditorLayoutController(MemoryStore(), phone, placementStore = placement).videoAudioOnTrack)
        assertFalse(NoVideoAudioPlacementStore.isOn())
    }

    @Test
    fun `the waveform scale defaults to linear, is remembered, and survives presets and other windows`() {
        val waveform = MemoryWaveform()
        val controller = EditorLayoutController(MemoryStore(), phone, waveform)
        assertEquals(WaveformScale.LINEAR, controller.waveformScale)

        controller.chooseWaveformScale(WaveformScale.DECIBEL)
        controller.chooseWaveformScale(WaveformScale.DECIBEL) // no second write for the same value
        assertEquals(WaveformScale.DECIBEL, waveform.scale)
        assertEquals(1, waveform.writes)

        controller.dispatch(LayoutAction.ApplyPreset(LayoutPreset.PREVIEW_FOCUS))
        controller.dispatch(LayoutAction.Reset)
        controller.onWindow(phoneSideways)
        assertEquals(WaveformScale.DECIBEL, controller.waveformScale)

        // A new editor session starts from what was saved.
        assertEquals(WaveformScale.DECIBEL, EditorLayoutController(MemoryStore(), phone, waveform).waveformScale)
    }

    @Test
    fun `an unknown stored waveform scale means linear`() {
        assertEquals(WaveformScale.LINEAR, waveformScaleOf(null))
        assertEquals(WaveformScale.LINEAR, waveformScaleOf("LOGARITHMIC"))
        assertEquals(WaveformScale.DECIBEL, waveformScaleOf("DECIBEL"))
        assertEquals(WaveformScale.entries.map { it.code }.distinct().size, WaveformScale.entries.size)
    }
}

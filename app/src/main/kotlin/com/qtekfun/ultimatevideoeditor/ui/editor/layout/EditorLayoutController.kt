package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.qtekfun.ultimatevideoeditor.engine.timeline.WaveformScale

/** Layouts kept in local preferences, one entry per window size class and orientation. */
class PrefsLayoutStore(private val prefs: SharedPreferences) : LayoutStore, WaveformScaleStore, VideoAudioPlacementStore {
    override fun isOn(): Boolean = prefs.getBoolean(VIDEO_AUDIO_KEY, false)

    override fun setOn(on: Boolean) {
        prefs.edit().putBoolean(VIDEO_AUDIO_KEY, on).apply()
    }

    override fun load(): WaveformScale = waveformScaleOf(prefs.getString(WAVEFORM_KEY, null))

    override fun save(scale: WaveformScale) {
        prefs.edit().putString(WAVEFORM_KEY, scale.name).apply()
    }

    override fun load(key: LayoutKey): LayoutState? =
        prefs.getString(prefKey(key), null)?.let(LayoutCodec::decode)

    override fun save(key: LayoutKey, state: LayoutState) {
        prefs.edit().putString(prefKey(key), LayoutCodec.encode(state)).apply()
    }

    private fun prefKey(key: LayoutKey) = "layout.${key.id}"

    companion object {
        const val FILE = "editor_layout"
        private const val WAVEFORM_KEY = "waveform_scale"
        private const val VIDEO_AUDIO_KEY = "video_audio_on_track"
    }
}

/**
 * Holds the layout the editor shows. [state] is Compose state, so the parts of the screen that read it
 * update; the layout containers read it in their measure pass, which means a divider drag re-measures
 * instead of recomposing the editor. When the window changes class or orientation the layout remembered
 * for the new one is loaded; any other change of size only clamps the current one.
 */
@Stable
class EditorLayoutController(
    private val store: LayoutStore,
    initialWindow: WindowMetrics,
    private val waveformStore: WaveformScaleStore = NoWaveformScaleStore,
    private val placementStore: VideoAudioPlacementStore = NoVideoAudioPlacementStore,
) {
    var window by mutableStateOf(initialWindow)
        private set

    /** How the timeline draws waveform heights. One choice for every layout; the presets do not change it. */
    var waveformScale by mutableStateOf(waveformStore.load())
        private set

    fun chooseWaveformScale(scale: WaveformScale) {
        if (scale == waveformScale) return
        waveformScale = scale
        waveformStore.save(scale)
    }

    /** True when new video clips get their sound on an audio lane at once (SPECS 5.38). One choice for every project and layout. */
    var videoAudioOnTrack by mutableStateOf(placementStore.isOn())
        private set

    fun chooseVideoAudioOnTrack(on: Boolean) {
        if (on == videoAudioOnTrack) return
        videoAudioOnTrack = on
        placementStore.setOn(on)
    }

    var key by mutableStateOf(LayoutKey.of(initialWindow))
        private set

    var state by mutableStateOf(load(LayoutKey.of(initialWindow), initialWindow))
        private set

    // Pieces of the layout for composition. Reading [state] there would recompose the editor on every step of
    // a divider drag; these only change when their own value does.
    val customising: Boolean by derivedStateOf { state.customising }
    val laneHeight: LaneHeight by derivedStateOf { state.laneHeight }
    val audioLaneHeight: AudioLaneHeight by derivedStateOf { state.audioLaneHeight }
    val tray: PanelState by derivedStateOf { state.tray }
    val inspector: PanelState by derivedStateOf { state.inspector }
    val sideDocksAllowed: Boolean by derivedStateOf { window.sideDocksAllowed }

    /** Call when the window size is known or changes (rotation, split screen, folding). */
    fun onWindow(next: WindowMetrics) {
        if (next == window) return
        val nextKey = LayoutKey.of(next)
        window = next
        if (nextKey != key) {
            key = nextKey
            state = load(nextKey, next)
        } else {
            state = state.clamped(next)
        }
    }

    /**
     * Applies [action]. A drag passes `persist = false` for each step and calls [commit] when it ends, so
     * preferences are written once per gesture.
     */
    fun dispatch(action: LayoutAction, persist: Boolean = true) {
        state = state.reduce(action, window)
        if (persist) commit()
    }

    fun commit() = store.save(key, state.copy(customising = false))

    private fun load(key: LayoutKey, window: WindowMetrics): LayoutState =
        (store.load(key) ?: LayoutState.defaultFor(window)).clamped(window)
}

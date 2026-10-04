package com.ultimatevideo.uveditor.ui.editor.layout

import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Layouts kept in local preferences, one entry per window size class and orientation. */
class PrefsLayoutStore(private val prefs: SharedPreferences) : LayoutStore {
    override fun load(key: LayoutKey): LayoutState? =
        prefs.getString(prefKey(key), null)?.let(LayoutCodec::decode)

    override fun save(key: LayoutKey, state: LayoutState) {
        prefs.edit().putString(prefKey(key), LayoutCodec.encode(state)).apply()
    }

    private fun prefKey(key: LayoutKey) = "layout.${key.id}"

    companion object {
        const val FILE = "editor_layout"
    }
}

/**
 * Holds the layout the editor shows. [state] is Compose state, so the parts of the screen that read it
 * update; the layout containers read it in their measure pass, which means a divider drag re-measures
 * instead of recomposing the editor. When the window changes class or orientation the layout remembered
 * for the new one is loaded; any other change of size only clamps the current one.
 */
@Stable
class EditorLayoutController(private val store: LayoutStore, initialWindow: WindowMetrics) {
    var window by mutableStateOf(initialWindow)
        private set

    var key by mutableStateOf(LayoutKey.of(initialWindow))
        private set

    var state by mutableStateOf(load(LayoutKey.of(initialWindow), initialWindow))
        private set

    // Pieces of the layout for composition. Reading [state] there would recompose the editor on every step of
    // a divider drag; these only change when their own value does.
    val customising: Boolean by derivedStateOf { state.customising }
    val laneHeight: LaneHeight by derivedStateOf { state.laneHeight }
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

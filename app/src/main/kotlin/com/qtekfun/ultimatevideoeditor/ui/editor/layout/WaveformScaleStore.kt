package com.qtekfun.ultimatevideoeditor.ui.editor.layout

import com.qtekfun.ultimatevideoeditor.engine.timeline.WaveformScale

/**
 * Where the waveform scale is remembered. It is a display preference of the person, not of a window size, so it is one
 * value for every layout (unlike [LayoutStore]) and the layout presets leave it alone.
 */
interface WaveformScaleStore {
    fun load(): WaveformScale

    fun save(scale: WaveformScale)
}

/** Remembers nothing; the default is linear. Used by tests and as the fallback when preferences are unavailable. */
object NoWaveformScaleStore : WaveformScaleStore {
    override fun load(): WaveformScale = WaveformScale.LINEAR

    override fun save(scale: WaveformScale) = Unit
}

/** The stored name back to a scale; anything unknown (an older or newer version's value) means the default. */
fun waveformScaleOf(name: String?): WaveformScale = WaveformScale.entries.firstOrNull { it.name == name } ?: WaveformScale.LINEAR

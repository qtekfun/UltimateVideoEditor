package com.qtekfun.ultimatevideoeditor.ui.editor.layout

/**
 * Where the "Put video audio on an audio track" preference is remembered (SPECS 5.38). It belongs to the person, not to a project
 * or a window size, so it is one value for every project and layout. Off by default: a video clip then keeps its own sound.
 */
interface VideoAudioPlacementStore {
    fun isOn(): Boolean

    fun setOn(on: Boolean)
}

/** Remembers nothing; the setting is off. Used by tests and as the fallback when preferences are unavailable. */
object NoVideoAudioPlacementStore : VideoAudioPlacementStore {
    override fun isOn(): Boolean = false

    override fun setOn(on: Boolean) = Unit
}

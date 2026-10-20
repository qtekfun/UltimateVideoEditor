package com.qtekfun.ultimatevideoeditor.ui.editor.layout

/**
 * Where the "Zoom to detail" preference is remembered. It belongs to the person, so it is one value for every project and layout.
 * Off by default: when on, the transport row gets a button and a double tap on the time ruler does the same thing.
 */
interface DetailZoomStore {
    fun detailZoomOn(): Boolean

    fun setDetailZoom(on: Boolean)
}

/** Remembers nothing; the setting is off. Used by tests and as the fallback when preferences are unavailable. */
object NoDetailZoomStore : DetailZoomStore {
    override fun detailZoomOn(): Boolean = false

    override fun setDetailZoom(on: Boolean) = Unit
}

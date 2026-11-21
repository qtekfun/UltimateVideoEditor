package com.qtekfun.ultimatevideoeditor.ui.editor.layout

/**
 * Where the "tap on a clip previews it" preference is remembered: one value for every project and layout. On by default, because
 * a tap that adds the clip at once leaves no way to check it is the right one first (the + on the tile still adds at once).
 */
interface TapPreviewStore {
    fun tapPreviews(): Boolean

    fun setTapPreviews(on: Boolean)
}

/** Remembers nothing; a tap previews. Used by tests and as the fallback when preferences are unavailable. */
object NoTapPreviewStore : TapPreviewStore {
    override fun tapPreviews(): Boolean = true

    override fun setTapPreviews(on: Boolean) = Unit
}

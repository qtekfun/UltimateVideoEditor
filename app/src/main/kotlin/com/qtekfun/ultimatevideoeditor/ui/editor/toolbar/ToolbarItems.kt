package com.qtekfun.ultimatevideoeditor.ui.editor.toolbar

/**
 * Every item of the editor's main tool row, in the DEFAULT order (the declaration order). [id] is what is saved, so it never
 * changes once released; [label] is the name shown in the toolbar settings. [mandatory] items cannot be hidden.
 * A new button is added here at the place it should have by default; saved orders pick it up (see [ToolbarOrder.parse]).
 */
enum class ToolbarItem(val id: String, val label: String, val mandatory: Boolean = false) {
    IMPORT("import", "Import media"),
    SPLIT("split", "Split at playhead", mandatory = true),
    DETACH_AUDIO("detach-audio", "Detach audio"),
    DELETE("delete", "Delete", mandatory = true),
    MARKER("marker", "Marker"),
    SELECT_MODE("select-mode", "Select several clips"),
    CLOSE_GAP("close-gap", "Close gap before clip"),
    TITLE("title", "Add a title"),
    CAPTIONS("captions", "Captions"),
    STICKERS("stickers", "Stickers"),
    TEMPLATES("templates", "Titles and text templates"),
    QUICK_EDITS("quick-edits", "Quick edits"),
    LIBRARY("library", "Media library"),
    PROXY("proxy", "Proxy media"),
    MIXER("mixer", "Mixer"),
    MULTICAM("multicam", "Multicam"),
    SCOPES("scopes", "Video scopes"),
    TRANSITION("transition", "Transition"),
    ADJUST("adjust", "Adjust clip"),
    TRACK_CONTROLS("track-controls", "Track controls"),
    CANVAS("canvas", "Canvas format"),
    SAFE_ZONE("safe-zone", "Safe zones"),
    ;

    companion object {
        fun byId(id: String): ToolbarItem? = entries.firstOrNull { it.id == id }
    }
}

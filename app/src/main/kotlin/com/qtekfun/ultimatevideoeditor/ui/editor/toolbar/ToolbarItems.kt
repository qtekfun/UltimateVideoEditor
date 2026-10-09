package com.qtekfun.ultimatevideoeditor.ui.editor.toolbar

import com.qtekfun.ultimatevideoeditor.R
import androidx.annotation.StringRes

/**
 * Every item of the editor's main tool row, in the DEFAULT order (the declaration order). [id] is what is saved, so it never
 * changes once released; [label] is the name shown in the toolbar settings. [mandatory] items cannot be hidden.
 * A new button is added here at the place it should have by default; saved orders pick it up (see [ToolbarOrder.parse]).
 */
enum class ToolbarItem(val id: String, @StringRes val labelRes: Int, val mandatory: Boolean = false) {
    IMPORT("import", R.string.ed_2a_tool_import),
    SPLIT("split", R.string.ed_2a_tool_split, mandatory = true),
    DETACH_AUDIO("detach-audio", R.string.ed_2a_tool_detach_audio),
    DELETE("delete", R.string.common_delete, mandatory = true),
    MARKER("marker", R.string.ed_2a_tool_marker),
    SELECT_MODE("select-mode", R.string.ed_2a_tool_select_mode),
    CLOSE_GAP("close-gap", R.string.ed_2a_tool_close_gap),
    TITLE("title", R.string.ed_2a_tool_title),
    CAPTIONS("captions", R.string.ed_2a_tool_captions),
    STICKERS("stickers", R.string.ed_2a_tool_stickers),
    TEMPLATES("templates", R.string.ed_2a_tool_templates),
    QUICK_EDITS("quick-edits", R.string.ed_2a_tool_quick_edits),
    LIBRARY("library", R.string.ed_2a_tool_library),
    PROXY("proxy", R.string.ed_2a_tool_proxy),
    MIXER("mixer", R.string.ed_2a_tool_mixer),
    MULTICAM("multicam", R.string.ed_2a_tool_multicam),
    SCOPES("scopes", R.string.ed_2a_tool_scopes),
    TRANSITION("transition", R.string.ed_2a_tool_transition),
    ADJUST("adjust", R.string.ed_2a_tool_adjust),
    TRACK_CONTROLS("track-controls", R.string.ed_2a_tool_track_controls),
    CANVAS("canvas", R.string.ed_2a_tool_canvas),
    SAFE_ZONE("safe-zone", R.string.ed_2a_tool_safe_zone),
    ;

    companion object {
        fun byId(id: String): ToolbarItem? = entries.firstOrNull { it.id == id }
    }
}

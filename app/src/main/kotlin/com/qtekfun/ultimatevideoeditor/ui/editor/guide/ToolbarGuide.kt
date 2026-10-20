package com.qtekfun.ultimatevideoeditor.ui.editor.guide

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.qtekfun.ultimatevideoeditor.R
import com.qtekfun.ultimatevideoeditor.ui.editor.EditorIcons
import com.qtekfun.ultimatevideoeditor.ui.editor.SelectionIcons

/** Where a symbol lives on the editor screen; the guide groups its entries by this, in this order. */
internal enum class GuideSection(@StringRes val titleRes: Int, @StringRes val whereRes: Int) {
    TOP_BAR(R.string.g_sec_top_bar, R.string.g_sec_top_bar_where),
    TRANSPORT(R.string.g_sec_transport, R.string.g_sec_transport_where),
    EDIT(R.string.g_sec_edit, R.string.g_sec_edit_where),
    ADD(R.string.g_sec_add, R.string.g_sec_add_where),
    MEDIA(R.string.g_sec_media, R.string.g_sec_media_where),
    TRACKS(R.string.g_sec_tracks, R.string.g_sec_tracks_where),
    SELECTION(R.string.g_sec_selection, R.string.g_sec_selection_where),
    PANELS(R.string.g_sec_panels, R.string.g_sec_panels_where),
}

/**
 * One symbol of the editor and what it does. [icon] is the very [ImageVector] the toolbar draws, so the guide
 * cannot show a different picture.
 *
 * Keep each entry on one line with plain `R.string.x` names: scripts/gen-site.py reads this file and the English
 * `values/strings.xml` to build the icons page of the online guide. [enabledWhenRes] is 0 when the button is always available.
 */
internal class GuideEntry(
    val id: String,
    val section: GuideSection,
    val icon: ImageVector,
    @StringRes val nameRes: Int,
    @StringRes val helpRes: Int,
    @StringRes val enabledWhenRes: Int,
)

/** A touch gesture (no symbol): what you do and what happens. */
internal class GuideGesture(val id: String, @StringRes val titleRes: Int, @StringRes val helpRes: Int)

/**
 * The single list behind the in-app toolbar guide. `ToolbarGuideTest` fails when an icon used in the editor has no
 * entry here (or an entry names an icon no screen uses), so adding a button means adding its line below.
 */
internal object ToolbarGuide {
    val entries: List<GuideEntry> = listOf(
        GuideEntry("back", GuideSection.TOP_BAR, EditorIcons.Back, R.string.g_back_name, R.string.g_back_help, R.string.g_back_when),
        GuideEntry("help", GuideSection.TOP_BAR, EditorIcons.Help, R.string.g_help_name, R.string.g_help_help, 0),
        GuideEntry("layout", GuideSection.TOP_BAR, EditorIcons.LayoutPanes, R.string.g_layout_name, R.string.g_layout_help, 0),
        GuideEntry("undo", GuideSection.TOP_BAR, EditorIcons.Undo, R.string.g_undo_name, R.string.g_undo_help, R.string.g_undo_when),
        GuideEntry("redo", GuideSection.TOP_BAR, EditorIcons.Redo, R.string.g_redo_name, R.string.g_redo_help, R.string.g_redo_when),
        GuideEntry("export", GuideSection.TOP_BAR, EditorIcons.Export, R.string.g_export_name, R.string.g_export_help, R.string.g_export_when),

        GuideEntry("prev", GuideSection.TRANSPORT, EditorIcons.SkipPrevious, R.string.g_prev_name, R.string.g_prev_help, 0),
        GuideEntry("play", GuideSection.TRANSPORT, EditorIcons.Play, R.string.g_play_name, R.string.g_play_help, 0),
        GuideEntry("pause", GuideSection.TRANSPORT, EditorIcons.Pause, R.string.g_pause_name, R.string.g_pause_help, 0),
        GuideEntry("next", GuideSection.TRANSPORT, EditorIcons.SkipNext, R.string.g_next_name, R.string.g_next_help, 0),
        GuideEntry("save-frame", GuideSection.TRANSPORT, EditorIcons.FrameImage, R.string.g_save_frame_name, R.string.g_save_frame_help, 0),
        GuideEntry("zoom-detail", GuideSection.TRANSPORT, EditorIcons.ZoomDetail, R.string.g_zoom_detail_name, R.string.g_zoom_detail_help, 0),
        GuideEntry("fit", GuideSection.TRANSPORT, EditorIcons.Fit, R.string.g_fit_name, R.string.g_fit_help, 0),

        GuideEntry("import", GuideSection.EDIT, EditorIcons.Add, R.string.g_import_name, R.string.g_import_help, R.string.g_import_when),
        GuideEntry("split", GuideSection.EDIT, EditorIcons.Split, R.string.g_split_name, R.string.g_split_help, R.string.g_split_when),
        GuideEntry("detach-audio", GuideSection.EDIT, EditorIcons.DetachAudio, R.string.g_detach_audio_name, R.string.g_detach_audio_help, R.string.g_detach_audio_when),
        GuideEntry("clip-volume", GuideSection.EDIT, EditorIcons.Volume, R.string.g_clip_volume_name, R.string.g_clip_volume_help, R.string.g_clip_volume_when),
        GuideEntry("delete", GuideSection.EDIT, EditorIcons.Delete, R.string.g_delete_name, R.string.g_delete_help, R.string.g_delete_when),
        GuideEntry("select-mode", GuideSection.EDIT, SelectionIcons.SelectMode, R.string.g_select_mode_name, R.string.g_select_mode_help, 0),
        GuideEntry("close-gap", GuideSection.EDIT, EditorIcons.CloseGap, R.string.g_close_gap_name, R.string.g_close_gap_help, R.string.g_close_gap_when),

        GuideEntry("title", GuideSection.ADD, EditorIcons.Title, R.string.g_title_name, R.string.g_title_help, 0),
        GuideEntry("captions", GuideSection.ADD, EditorIcons.Captions, R.string.g_captions_name, R.string.g_captions_help, 0),
        GuideEntry("stickers", GuideSection.ADD, EditorIcons.Sticker, R.string.g_stickers_name, R.string.g_stickers_help, 0),
        GuideEntry("templates", GuideSection.ADD, EditorIcons.TextTemplate, R.string.g_templates_name, R.string.g_templates_help, 0),
        GuideEntry("flag-list", GuideSection.ADD, EditorIcons.FlagList, R.string.g_flag_list_name, R.string.g_flag_list_help, 0),
        GuideEntry("drop-mode-button", GuideSection.EDIT, EditorIcons.DropMode, R.string.g_drop_mode_button_name, R.string.g_drop_mode_button_help, 0),
        GuideEntry("marker", GuideSection.ADD, EditorIcons.Flag, R.string.g_marker_name, R.string.g_marker_help, 0),

        GuideEntry("quick-edits", GuideSection.MEDIA, EditorIcons.Silence, R.string.g_quick_edits_name, R.string.g_quick_edits_help, 0),
        GuideEntry("library", GuideSection.MEDIA, EditorIcons.Library, R.string.g_library_name, R.string.g_library_help, 0),
        GuideEntry("proxy", GuideSection.MEDIA, EditorIcons.Proxy, R.string.g_proxy_name, R.string.g_proxy_help, 0),
        GuideEntry("mixer", GuideSection.MEDIA, EditorIcons.Mixer, R.string.g_mixer_name, R.string.g_mixer_help, 0),
        GuideEntry("multicam", GuideSection.MEDIA, EditorIcons.Multicam, R.string.g_multicam_name, R.string.g_multicam_help, 0),
        GuideEntry("scopes", GuideSection.MEDIA, EditorIcons.Scopes, R.string.g_scopes_name, R.string.g_scopes_help, 0),

        GuideEntry("transition", GuideSection.TRACKS, EditorIcons.Transition, R.string.g_transition_name, R.string.g_transition_help, R.string.g_transition_when),
        GuideEntry("adjust", GuideSection.TRACKS, EditorIcons.Tune, R.string.g_adjust_name, R.string.g_adjust_help, R.string.g_adjust_when),
        GuideEntry("add-track", GuideSection.TRACKS, EditorIcons.Layers, R.string.g_add_track_name, R.string.g_add_track_help, 0),
        GuideEntry("remove-track", GuideSection.TRACKS, EditorIcons.Minus, R.string.g_remove_track_name, R.string.g_remove_track_help, R.string.g_remove_track_when),
        GuideEntry("lane-up", GuideSection.TRACKS, EditorIcons.LaneUp, R.string.g_lane_up_name, R.string.g_lane_up_help, R.string.g_lane_up_when),
        GuideEntry("lane-down", GuideSection.TRACKS, EditorIcons.LaneDown, R.string.g_lane_down_name, R.string.g_lane_down_help, R.string.g_lane_down_when),
        GuideEntry("canvas", GuideSection.TRACKS, EditorIcons.CanvasFormat, R.string.g_canvas_name, R.string.g_canvas_help, 0),
        GuideEntry("safe-zone", GuideSection.TRACKS, EditorIcons.SafeZone, R.string.g_safe_zone_name, R.string.g_safe_zone_help, 0),

        GuideEntry("copy", GuideSection.SELECTION, SelectionIcons.Copy, R.string.g_copy_name, R.string.g_copy_help, R.string.g_copy_when),
        GuideEntry("cut", GuideSection.SELECTION, SelectionIcons.Cut, R.string.g_cut_name, R.string.g_cut_help, R.string.g_cut_when),
        GuideEntry("paste", GuideSection.SELECTION, SelectionIcons.Paste, R.string.g_paste_name, R.string.g_paste_help, R.string.g_paste_when),
        GuideEntry("duplicate", GuideSection.SELECTION, SelectionIcons.Duplicate, R.string.g_duplicate_name, R.string.g_duplicate_help, R.string.g_duplicate_when),
        GuideEntry("paste-attributes", GuideSection.SELECTION, SelectionIcons.PasteAttributes, R.string.g_paste_attributes_name, R.string.g_paste_attributes_help, R.string.g_paste_attributes_when),
        GuideEntry("align-left", GuideSection.SELECTION, SelectionIcons.AlignLeft, R.string.g_align_left_name, R.string.g_align_left_help, R.string.g_align_left_when),
        GuideEntry("align-right", GuideSection.SELECTION, SelectionIcons.AlignRight, R.string.g_align_right_name, R.string.g_align_right_help, R.string.g_align_right_when),
        GuideEntry("more", GuideSection.SELECTION, SelectionIcons.More, R.string.g_more_name, R.string.g_more_help, 0),
        GuideEntry("clear-selection", GuideSection.SELECTION, SelectionIcons.Close, R.string.g_clear_selection_name, R.string.g_clear_selection_help, R.string.g_clear_selection_when),

        GuideEntry("keyframe-add", GuideSection.PANELS, EditorIcons.KeyframeOff, R.string.g_keyframe_add_name, R.string.g_keyframe_add_help, R.string.g_keyframe_add_when),
        GuideEntry("keyframe-remove", GuideSection.PANELS, EditorIcons.KeyframeOn, R.string.g_keyframe_remove_name, R.string.g_keyframe_remove_help, R.string.g_keyframe_remove_when),
        GuideEntry("fullscreen-exit", GuideSection.PANELS, EditorIcons.FullscreenExit, R.string.g_fullscreen_exit_name, R.string.g_fullscreen_exit_help, R.string.g_fullscreen_exit_when),
        GuideEntry("panel-left", GuideSection.PANELS, EditorIcons.ChevronLeft, R.string.g_panel_left_name, R.string.g_panel_left_help, 0),
        GuideEntry("panel-right", GuideSection.PANELS, EditorIcons.ChevronRight, R.string.g_panel_right_name, R.string.g_panel_right_help, 0),
        GuideEntry("panel-up", GuideSection.PANELS, EditorIcons.ChevronUp, R.string.g_panel_up_name, R.string.g_panel_up_help, R.string.g_panel_up_when),
    )

    val gestures: List<GuideGesture> = listOf(
        GuideGesture("press-hold-icon", R.string.gg_press_hold_icon_title, R.string.gg_press_hold_icon_help),
        GuideGesture("tap-clip", R.string.gg_tap_clip_title, R.string.gg_tap_clip_help),
        GuideGesture("drag-clip", R.string.gg_drag_clip_title, R.string.gg_drag_clip_help),
        GuideGesture("drop-mode", R.string.gg_drop_mode_title, R.string.gg_drop_mode_help),
        GuideGesture("trim", R.string.gg_trim_title, R.string.gg_trim_help),
        GuideGesture("scrub", R.string.gg_scrub_title, R.string.gg_scrub_help),
        GuideGesture("pinch-time", R.string.gg_pinch_time_title, R.string.gg_pinch_time_help),
        GuideGesture("scroll", R.string.gg_scroll_title, R.string.gg_scroll_help),
        GuideGesture("long-press-clip", R.string.gg_long_press_clip_title, R.string.gg_long_press_clip_help),
        GuideGesture("lane-reorder", R.string.gg_lane_reorder_title, R.string.gg_lane_reorder_help),
        GuideGesture("flag-long", R.string.gg_flag_long_title, R.string.gg_flag_long_help),
        GuideGesture("double-tap-preview", R.string.gg_double_tap_preview_title, R.string.gg_double_tap_preview_help),
        GuideGesture("preview-transform", R.string.gg_preview_transform_title, R.string.gg_preview_transform_help),
        GuideGesture("divider", R.string.gg_divider_title, R.string.gg_divider_help),
        GuideGesture("tray-drag", R.string.gg_tray_drag_title, R.string.gg_tray_drag_help),
        GuideGesture("tray-select", R.string.gg_tray_select_title, R.string.gg_tray_select_help),
        GuideGesture("audio-fades", R.string.gg_audio_fades_title, R.string.gg_audio_fades_help),
        GuideGesture("volume-curve", R.string.gg_volume_curve_title, R.string.gg_volume_curve_help),
        GuideGesture("tool-row-scroll", R.string.gg_tool_row_scroll_title, R.string.gg_tool_row_scroll_help),
    )

    /**
     * Entries whose name, help, section or enabled text contain every word of [query] (case-insensitive); all when blank.
     * [text] gives the words of a resource in the language in use, so the search follows what the person reads.
     */
    fun search(query: String, text: (Int) -> String): List<GuideEntry> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return entries
        return entries.filter { entry ->
            val whenText = if (entry.enabledWhenRes != 0) text(entry.enabledWhenRes) else ""
            val haystack = "${text(entry.nameRes)} ${text(entry.helpRes)} $whenText ${text(entry.section.titleRes)}".lowercase()
            words.all { it in haystack }
        }
    }

    fun searchGestures(query: String, text: (Int) -> String): List<GuideGesture> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return gestures
        return gestures.filter { gesture ->
            val haystack = "${text(gesture.titleRes)} ${text(gesture.helpRes)}".lowercase()
            words.all { it in haystack }
        }
    }
}

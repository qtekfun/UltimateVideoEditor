package com.ultimatevideo.uveditor.ui.editor.guide

import androidx.compose.ui.graphics.vector.ImageVector
import com.ultimatevideo.uveditor.ui.editor.EditorIcons
import com.ultimatevideo.uveditor.ui.editor.SelectionIcons

/** Where a symbol lives on the editor screen; the guide groups its entries by this, in this order. */
internal enum class GuideSection(val title: String, val where: String) {
    TOP_BAR("Top bar", "The row above the preview."),
    TRANSPORT("Transport row", "Under the preview: the timecode, play controls and the view buttons."),
    EDIT("Editing tools", "The scrolling tool row under the transport row."),
    ADD("Adding content", "The tool row: titles, captions, stickers and markers."),
    MEDIA("Media, sound and colour", "The tool row: library, proxies, mixer, multicam, scopes and quick edits."),
    TRACKS("Tracks, transitions and canvas", "The tool row, at its right end."),
    SELECTION("Selection bar", "Appears under the tool row while select mode is on or several clips are selected."),
    PANELS("Preview, inspector and panels", "Buttons inside the preview, the inspector and the side panels."),
}

/**
 * One symbol of the editor and what it does. [icon] is the very [ImageVector] the toolbar draws, so the guide
 * cannot show a different picture. [enabledWhen] is empty when the button is always available.
 *
 * Keep each entry on one line with plain string literals: scripts/gen-site.py reads this file to build the icons
 * page of the online guide.
 */
internal class GuideEntry(
    val id: String,
    val section: GuideSection,
    val icon: ImageVector,
    val name: String,
    val help: String,
    val enabledWhen: String,
)

/** A touch gesture (no symbol): what you do and what happens. */
internal class GuideGesture(val id: String, val title: String, val help: String)

/**
 * The single list behind the in-app toolbar guide. `ToolbarGuideTest` fails when an icon used in the editor has no
 * entry here (or an entry names an icon no screen uses), so adding a button means adding its line below.
 */
internal object ToolbarGuide {
    val entries: List<GuideEntry> = listOf(
        GuideEntry("back", GuideSection.TOP_BAR, EditorIcons.Back, "Back", "Saves the project and returns to the project list.", "Always. If saving keeps failing, a dialog explains and keeps you here."),
        GuideEntry("help", GuideSection.TOP_BAR, EditorIcons.Help, "Toolbar guide", "Opens this guide: every symbol of the editor and the gestures.", ""),
        GuideEntry("layout", GuideSection.TOP_BAR, EditorIcons.LayoutPanes, "Layout", "Opens the layout sheet: presets, track height, where the media tray and inspector sit, customise mode and reset.", ""),
        GuideEntry("undo", GuideSection.TOP_BAR, EditorIcons.Undo, "Undo", "Takes back the last edit. Every drag, drop and inspector change is one step.", "There is something to undo."),
        GuideEntry("redo", GuideSection.TOP_BAR, EditorIcons.Redo, "Redo", "Brings back what Undo took away.", "You have just undone something."),
        GuideEntry("export", GuideSection.TOP_BAR, EditorIcons.Export, "Export movie", "Opens the export dialog: resolution, frame rate, codec and destination.", "Playback is stopped and no other export is running."),

        GuideEntry("prev", GuideSection.TRANSPORT, EditorIcons.SkipPrevious, "Previous clip boundary", "Jumps the playhead to the previous clip start or end and stops playback if it was playing. In the inspector's keyframe rows the same arrow jumps to the previous keyframe.", ""),
        GuideEntry("play", GuideSection.TRANSPORT, EditorIcons.Play, "Play", "Starts playback from the playhead. The sound is the clock, so picture and sound stay together.", ""),
        GuideEntry("pause", GuideSection.TRANSPORT, EditorIcons.Pause, "Pause", "Stops playback. This button replaces Play while the project is playing.", ""),
        GuideEntry("next", GuideSection.TRANSPORT, EditorIcons.SkipNext, "Next clip boundary", "Jumps the playhead to the next clip start or end and stops playback if it was playing. In the inspector's keyframe rows the same arrow jumps to the next keyframe.", ""),
        GuideEntry("save-frame", GuideSection.TRANSPORT, EditorIcons.FrameImage, "Save frame as image", "Saves the picture under the playhead as a PNG or JPEG, for a thumbnail or a cover.", ""),
        GuideEntry("fit", GuideSection.TRANSPORT, EditorIcons.Fit, "Fit everything", "Zooms the timeline so the whole project and all tracks are visible. This is not fullscreen: double tap the preview for that.", ""),

        GuideEntry("import", GuideSection.EDIT, EditorIcons.Add, "Import media", "Adds videos, photos or audio from your device at the playhead. Your files are not copied.", "No import is running. (In the layout sheet the same plus makes tracks taller.)"),
        GuideEntry("split", GuideSection.EDIT, EditorIcons.Split, "Split at playhead", "Cuts the selected clip in two at the playhead.", "A clip is selected."),
        GuideEntry("delete", GuideSection.EDIT, EditorIcons.Delete, "Delete", "Deletes the selected clip. On the base track the gap closes by itself; on other tracks a gap is left.", "A clip is selected."),
        GuideEntry("select-mode", GuideSection.EDIT, SelectionIcons.SelectMode, "Select several clips", "Turns select mode on or off. While on (the button is highlighted), tap clips to add or remove them and drag across empty space to draw a selection rectangle.", ""),
        GuideEntry("close-gap", GuideSection.EDIT, EditorIcons.CloseGap, "Close gap before clip", "Slides an overlay or audio clip back until it touches the previous clip.", "A clip on an overlay or audio track is selected. The base track never has gaps, so it is off there."),

        GuideEntry("title", GuideSection.ADD, EditorIcons.Title, "Add a title", "Adds a text title at the playhead. Edit its text and look in Adjust clip.", ""),
        GuideEntry("captions", GuideSection.ADD, EditorIcons.Captions, "Captions", "Opens the captions sheet: type captions, import a .srt or .vtt file, pick a style.", ""),
        GuideEntry("stickers", GuideSection.ADD, EditorIcons.Sticker, "Stickers", "Opens the media tray on its Stickers tab (shapes and emoji).", ""),
        GuideEntry("templates", GuideSection.ADD, EditorIcons.TextTemplate, "Titles and text templates", "Opens the media tray on its Titles tab: lower third, pop title, slide-in headline, subtitle bar.", ""),
        GuideEntry("marker", GuideSection.ADD, EditorIcons.Flag, "Marker", "Tap: drops a marker at the playhead. Long press: previous or next marker, beat tools and marker snapping.", ""),

        GuideEntry("quick-edits", GuideSection.MEDIA, EditorIcons.Silence, "Quick edits", "Cuts the silences out of the selected clip, or reframes a clip for another canvas shape.", ""),
        GuideEntry("library", GuideSection.MEDIA, EditorIcons.Library, "Media library", "Opens the project's library: tags, notes, where each file is used, remove unused files, export a bundle. With a clip selected it opens on that clip's file.", ""),
        GuideEntry("proxy", GuideSection.MEDIA, EditorIcons.Proxy, "Proxy media", "Opens the proxy sheet: small copies of heavy video for smooth editing. Export always uses the originals.", ""),
        GuideEntry("mixer", GuideSection.MEDIA, EditorIcons.Mixer, "Mixer", "Opens the mixer: track volume, mute, solo, compressor and ducking.", ""),
        GuideEntry("multicam", GuideSection.MEDIA, EditorIcons.Multicam, "Multicam", "Lines up several cameras by their sound and lets you cut between them.", ""),
        GuideEntry("scopes", GuideSection.MEDIA, EditorIcons.Scopes, "Video scopes", "Shows or hides waveform, RGB parade, vectorscope and histogram over the preview.", ""),

        GuideEntry("transition", GuideSection.TRACKS, EditorIcons.Transition, "Transition", "Adds a crossfade at the selected cut. Choose its look in Adjust clip. In the selection bar the same symbol adds one to every selected clip.", "A clip next to another is selected, or the playhead is on a cut."),
        GuideEntry("adjust", GuideSection.TRACKS, EditorIcons.Tune, "Adjust clip", "Opens the inspector for the selected clip: position, scale, rotation, opacity, volume, speed, effects, colour, keyframes.", "A clip is selected, or the inspector is already open (tap again to close it)."),
        GuideEntry("add-track", GuideSection.TRACKS, EditorIcons.Layers, "Add track", "Opens a menu to add a video track (above the others) or an audio track.", ""),
        GuideEntry("remove-track", GuideSection.TRACKS, EditorIcons.Minus, "Remove selected track", "Removes the selected track. In the layout sheet the same minus makes tracks shorter.", "A track is selected, it is empty and not the last of its kind."),
        GuideEntry("lane-up", GuideSection.TRACKS, EditorIcons.LaneUp, "Move lane up", "Moves the selected overlay lane one step up. On the media tray the same arrow makes the tray taller.", "A track is selected. The base track never moves."),
        GuideEntry("lane-down", GuideSection.TRACKS, EditorIcons.LaneDown, "Move lane down", "Moves the selected overlay lane one step down. On the media tray the same arrow makes the tray shorter.", "A track is selected. The base track never moves."),
        GuideEntry("canvas", GuideSection.TRACKS, EditorIcons.CanvasFormat, "Canvas format", "Changes the aspect ratio, resolution and colour space of the project.", ""),
        GuideEntry("safe-zone", GuideSection.TRACKS, EditorIcons.SafeZone, "Safe zones", "Outlines the areas that TikTok, Reels or Shorts cover with their own buttons, over the preview.", ""),

        GuideEntry("copy", GuideSection.SELECTION, SelectionIcons.Copy, "Copy", "Copies the selected clips with their effects, keyframes, speed and transitions.", "Clips are selected."),
        GuideEntry("cut", GuideSection.SELECTION, SelectionIcons.Cut, "Cut", "Copies the selected clips, then deletes them.", "Clips are selected."),
        GuideEntry("paste", GuideSection.SELECTION, SelectionIcons.Paste, "Paste", "Pastes the copied clips with the earliest one at the playhead.", "Something has been copied."),
        GuideEntry("duplicate", GuideSection.SELECTION, SelectionIcons.Duplicate, "Duplicate", "Pastes a copy right after the last selected clip.", "Clips are selected."),
        GuideEntry("paste-attributes", GuideSection.SELECTION, SelectionIcons.PasteAttributes, "Paste attributes", "Copies position, effects, volume and speed of the copied clip onto the selection.", "Clips are selected and something has been copied."),
        GuideEntry("align-left", GuideSection.SELECTION, SelectionIcons.AlignLeft, "Align starts", "Moves the selected clips so they all start together.", "Two or more clips are selected. Not on the base track."),
        GuideEntry("align-right", GuideSection.SELECTION, SelectionIcons.AlignRight, "Align ends", "Moves the selected clips so they all end together.", "Two or more clips are selected. Not on the base track."),
        GuideEntry("more", GuideSection.SELECTION, SelectionIcons.More, "More selection actions", "Selects a whole lane or everything after the playhead; sets speed, volume or opacity for the whole selection.", ""),
        GuideEntry("clear-selection", GuideSection.SELECTION, SelectionIcons.Close, "Clear the selection", "Deselects every clip.", "Clips are selected."),

        GuideEntry("keyframe-add", GuideSection.PANELS, EditorIcons.KeyframeOff, "Add a keyframe", "In the inspector: stores the current value at the playhead. The outlined diamond means there is no keyframe here yet.", "A clip is selected and the inspector is open."),
        GuideEntry("keyframe-remove", GuideSection.PANELS, EditorIcons.KeyframeOn, "Remove the keyframe", "In the inspector: a filled diamond means a keyframe sits at the playhead; tap it to remove it. The same diamonds sit on clips in the timeline.", "The playhead is on a keyframe."),
        GuideEntry("fullscreen-exit", GuideSection.PANELS, EditorIcons.FullscreenExit, "Leave fullscreen", "Appears in the strip over a fullscreen preview, next to play and pause. Double tap or Back do the same.", "Only in fullscreen."),
        GuideEntry("panel-left", GuideSection.PANELS, EditorIcons.ChevronLeft, "Arrow left", "On a side panel: collapses a panel that sits on the left, or brings back a collapsed panel on the right. The arrow points the way the panel moves. In the marker popup it goes to the previous marker.", ""),
        GuideEntry("panel-right", GuideSection.PANELS, EditorIcons.ChevronRight, "Arrow right", "On a side panel: collapses a panel that sits on the right, or brings back a collapsed panel on the left. In the marker popup it goes to the next marker.", ""),
        GuideEntry("panel-up", GuideSection.PANELS, EditorIcons.ChevronUp, "Arrow up", "Brings back the media tray or inspector that you collapsed at the bottom.", "A bottom panel is collapsed."),
    )

    val gestures: List<GuideGesture> = listOf(
        GuideGesture("press-hold-icon", "Long press any icon", "Shows its name and what it does in a small bubble. Screen readers read the same text."),
        GuideGesture("tap-clip", "Tap a clip", "Selects it: a yellow outline with a handle at each end. Tap an empty lane to select that track."),
        GuideGesture("drag-clip", "Drag a selected clip", "Moves it. Edges snap to neighbours, the playhead and markers. Dropping on the base track near a cut inserts it."),
        GuideGesture("trim", "Drag a clip's edge", "Trims it. The handles at the ends of the selected clip are the edges to drag."),
        GuideGesture("scrub", "Drag the ruler or the red playhead", "Scrubs through the project."),
        GuideGesture("pinch-time", "Pinch the timeline", "Zooms the time axis."),
        GuideGesture("pinch-lanes", "Spread two fingers vertically", "Makes the lanes taller or shorter. Whichever way your fingers spread most at the start decides."),
        GuideGesture("scroll", "Drag on empty timeline space", "Scrolls the timeline; a fling keeps it coasting."),
        GuideGesture("long-press-clip", "Long press a clip", "Adds it to the selection (or removes it) without select mode."),
        GuideGesture("lane-reorder", "Long press and drag a lane header", "Reorders the lane among lanes of its kind. The base track never moves."),
        GuideGesture("flag-long", "Long press the flag", "Previous or next marker, beat tools and marker snapping."),
        GuideGesture("double-tap-preview", "Double tap the preview", "Fullscreen. Tap once to show the play and exit strip; double tap again or press Back to leave."),
        GuideGesture("preview-transform", "Drag, pinch and twist on the preview", "Moves, scales and rotates the selected clip (or the selected title layer) while the playhead is on it."),
        GuideGesture("divider", "Drag a divider, double tap it to reset", "The pill under the preview (and the bar beside a side panel) resizes the areas. A short vibration marks the default."),
        GuideGesture("tray-drag", "Hold a tile in the media tray, then drag", "Lifts the item and carries it onto the timeline; an indicator shows what releasing will do. Release outside the timeline to cancel."),
        GuideGesture("tool-row-scroll", "Swipe the tool row sideways", "The tool row scrolls when the window is too narrow for every button."),
    )

    /** Entries whose name, help, section or enabled text contain every word of [query] (case-insensitive); all when blank. */
    fun search(query: String): List<GuideEntry> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return entries
        return entries.filter { entry ->
            val haystack = "${entry.name} ${entry.help} ${entry.enabledWhen} ${entry.section.title}".lowercase()
            words.all { it in haystack }
        }
    }

    fun searchGestures(query: String): List<GuideGesture> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return gestures
        return gestures.filter { gesture ->
            val haystack = "${gesture.title} ${gesture.help}".lowercase()
            words.all { it in haystack }
        }
    }
}

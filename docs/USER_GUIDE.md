# ultimateVE user guide

A short guide to the screens, the toolbar icons and the main gestures. ultimateVE is a lightweight
LumaFusion-style video editor for Android. Some features are new and have only been exercised by
automated tests so far; see [Known limits](#known-limits).

> **Upgrading from release 0.3.13 or earlier?** The app's package name changed (from `com.ultimatevideo.uveditor` to
> `com.qtekfun.ultimatevideoeditor`), and Android treats a different package name as a different app. The new build installs
> next to the old one, does not replace it and does not see its projects. Move each project once: in the old app use
> **⋮ on a project card → Export bundle for another phone…**, then in the new app use **⋮ → Import project, bundle or
> LumaFusion package** on the hub. Uninstall the old app only after checking the imported projects.

## Contents

1. [Project hub](#project-hub)
2. [Editor layout](#editor-layout), [Fullscreen preview](#fullscreen-preview) and [Layout](#layout)
3. [Toolbar icons](#toolbar-icons)
4. [Media tray](#media-tray) and [Media library](#media-library)
5. [Timeline: tracks, gestures and drops](#timeline-tracks-gestures-and-drops) and [Selecting several clips](#selecting-several-clips)
6. [Inspector](#inspector)
7. [Titles, captions, stickers and templates](#titles-captions-stickers-and-templates) (including [layers, fonts and presets](#titles-with-layers-fonts-and-presets))
8. [Markers and beats](#markers-and-beats) and [Quick edits: cut silences and reframe](#quick-edits-cut-silences-and-reframe)
9. [Sound: pan, fades, EQ, noise, loudness, mixer and ducking](#sound)
10. [Stabilising shaky footage](#stabilising-shaky-footage)
11. [Colour grading and scopes](#colour-grading-and-scopes)
12. [Colour spaces and HDR](#colour-spaces-and-hdr)
13. [Exporting](#exporting), [Saving a frame as an image](#saving-a-frame-as-an-image) and [Sharing a project with other devices and tools](#sharing-a-project-with-other-devices-and-tools)
14. [Proxy media](#proxy-media)
15. [Multicam](#multicam)
16. [Missing media and recovery](#missing-media-and-recovery)
17. [About, privacy, tips and crash reports](#about-privacy-tips-and-crash-reports) and [Appearance](#appearance-dark-and-pure-black)
18. [Known limits](#known-limits)

## Project hub

The first screen is your library of projects. From the top:

- **Top bar**: the ultimateVE mark (three stacked bars in the colours of video, audio and title clips), a **search** icon
  and the **More options** menu: **New from a template…** (see [Templates](#project-templates)), **Import project, bundle or
  LumaFusion package** (a `project.json`, a `.uvbundle` from another device or a LumaFusion `.lfpackage` / `.lfarchive`, see
  [Sharing a project](#sharing-a-project-with-other-devices-and-tools)) and **About, privacy and help**. The engine version is
  in About, under Version; the Projects screen only mentions the engine when it failed to start.
- **Continue card**: the project you edited last, with its first frame, name, length, format (and HDR) and a **Continue editing**
  button. After the app was closed while a project was open, the card says so ("The app closed while … was open. Your edits
  were saved as you made them.") with **Reopen** and **Dismiss**. The card is hidden while you search or select, and when you
  have no projects.
- **Storage card**: how many projects you have, the free space on the device and a bar split into **Projects** (project
  files, backups), **Cache** (thumbnails, waveforms, analysis) and **Proxies**. Your footage is not counted: media stay where
  they are and projects only point to them. It is measured in the background and refreshed when you come back to the screen
  and after you delete or duplicate. Tap it to open About, where **Clear caches** lives.
- **Sorted by**: Last edited, Name, Size or Length, with a button to flip the direction, and a **List / Grid** switch. Both
  choices are remembered. Size is the project's folder on disk (project files and its caches).
- **List** shows dense rows: a small first frame with the length, the name, "1080p · 30 fps · SDR", the size on disk and how
  long ago it was changed. **Grid** shows posters (two or more columns, wider screens get more), each with the length, an
  **HDR** label when the project is HDR, the name and the date.
- **N missing** (red label): the last time you edited the project, that many media files could not be read. It is saved with
  the project, so you can see which projects need **Relink** without opening each one. The Projects screen never checks media
  itself; a project you only opened without editing keeps the label it had.
- **New project** (the button with the plus at the bottom right): opens the sheet described below.
- **Tap** a project to open it. The **⋮** on a row or poster has rename, duplicate, export the project file, export a bundle for
  another phone (a dialog asks whether to include the media files, LUTs and fonts) and delete.
- **Select several**: touch and hold a project. Tick more with a tap, **Select all** in the top bar, and the bar at the bottom
  has **Duplicate**, **Export bundle**, **Export file** and **Delete** (and **Rename** under its ⋮). Duplicate and Delete work
  on any number of projects (Delete asks once and says how many). Exporting and renaming work on one project at a time: with
  several ticked those buttons are dimmed, and tapping one says why. Back (or the close button) leaves selection mode.
- **Search**: the magnifier in the top bar opens a field; it filters by name. Back closes it.
- Projects that cannot be read are listed with **Recover** (from the `.bak` of the last good save) and **Delete**.
- With no projects the screen shows a welcome with **New project** and **Import a project**.

Projects live in the app's private storage. Your media is never copied: projects only reference the files
you imported. The card pictures are made on the device and cached; nothing is uploaded anywhere.

### Project templates

A template is a project without media: its format, titles, effects, transitions and the **slots** where your own
clips, photos and music go. **⋮ → New from a template…** lists them:

- Three starters come with the app (Vertical montage, Intro and outro, Lower thirds). They are generated by the app;
  nothing is downloaded.
- Tap a template, give the project a name and choose a file for each slot (**Choose…**). Optional slots (like the music
  of the montage) may stay empty and are removed. Before you create anything the sheet tells you what will happen: a clip
  longer than its slot is trimmed from its start, a shorter one shortens the slot (the clips and titles after it move up,
  like trimming on the base track), a photo takes the slot's length, a picture of another shape is centre-cropped to fill
  the canvas, and a transition is shortened or dropped where there is not enough footage around the cut. A transition into
  a clip makes the clip start a few frames in so it has room.
- **Make a template from one of your projects**: **Save as template** in the same sheet turns every clip, photo and song of
  a project into a slot of the same length and keeps everything else (titles, stickers, effects, grades, transitions,
  keyframes, tracks and manual markers). Speed changes, stabilisation, motion tracks and multicam links belong to the
  footage and are left out.
- **Share file…** writes a template as a `.uvtemplate` file you can send; **Import template file…** adds one you received.
  A template file never contains media.
- **Delete** removes your own templates (the starters stay).

### New project

The sheet opens with your last choices, or 1080p, 30 fps, SDR the first time.

1. **Name**: prefilled with a free name ("New project", "New project 2"…).
2. **Quick start**: one tap fills everything below. YouTube 1080p30, YouTube 4K30, TikTok · Reels · Shorts
   9:16, Instagram 4:5, Square 1:1, Cinema 24p, or **Match first clip**. You can still change any value.
3. Four selectors, each showing only its current value:
   - **Aspect ratio**: 16:9, 9:16, 1:1, 4:5, 4:3, 21:9 or Custom size (type width and height).
   - **Resolution**: 720p, 1080p, 1440p, 4K, or Custom short side. 1080p means 1920 × 1080 in landscape and
     1080 × 1920 in vertical. The exact pixels are written below the selector.
   - **Frame rate**: 23.976, 24, 25, 29.97, 30, 50, 59.94, 60 or 120 fps.
   - **Colour space**: SDR Rec.709 or HDR Rec.2020 HLG. It is the working and output space: every clip is
     converted to it individually, so SDR and HLG clips can be mixed.
4. A one-line summary and a small rectangle with the shape of the picture.
5. **Create**.

**Match first clip** asks you to choose a video or photo and copies its size, frame rate and colour space
(PQ clips use the HLG project space). The clip is only read; it is not added to the project and the app keeps
no access to it. A format taken from a clip is not remembered for next time.

## Editor layout

From top to bottom:

1. **Top bar**: back, project name, the **?** guide button, the **layout** button, undo, redo and export.
2. **Preview**: the current frame.
3. **Divider handle**: the small grey pill under the preview (see [Layout](#layout)).
4. **Transport and toolbar**: timecode, previous / play-pause / next, a fit button and the tools below
   (the toolbar scrolls sideways when it does not fit).
5. **Timeline**: ruler, tracks, playhead.
6. **Media tray** at the bottom (see [Media tray](#media-tray)): collapsed to a thin tab strip until you open it.

On wide windows (tablet, foldable open, or a phone sideways) the tray and the inspector can sit in side columns.

## Fullscreen preview

**Double tap** the picture on the preview to make it fill the whole screen. The timeline, toolbars and the system bars are
hidden; playback carries on exactly where it was, and the picture keeps its shape (black bars if the project's shape differs
from the screen's). **Tap once** to show a small strip with **play/pause** and **leave fullscreen**; it fades after about 2.5
seconds. To leave, **double tap** again, use the strip's exit icon or press **Back**. Swiping from a screen edge shows the
system bars for a moment. Fullscreen survives rotating the device. Dragging, pinching and twisting on the picture still edit
the selected clip, in fullscreen too (a tap or a double tap never moves anything). The
four-arrows button in the transport row is something else: it fits the whole project into the timeline.

## Layout

The workspace is yours to shape, and each window shape remembers its own layout (a phone upright, a phone
sideways and a tablet each keep theirs).

- **Resize with the dividers.** Drag the pill under the preview up or down to give the timeline or the preview
  more room. On wide windows a vertical bar separates a side panel from the editor: drag it sideways. A short
  vibration marks the default position, and a **double tap** on a divider resets it.
- **Layout button** (top bar) opens the layout sheet:
  - **Presets**: *Default*, *Timeline focus* (big timeline, small preview, tray folded), *Preview focus* and
    *Two panels* (tray on the left, inspector on the right; needs a window at least 600 dp wide).
  - **Track height**: Small, Medium or Large lanes, or the **-** and **+** buttons. Waveforms, thumbnails and
    keyframe diamonds scale with the lane.
  - **Audio track height**: *Same*, *1.5x* or *2x*. Audio lanes become that much taller than the other lanes, so waveforms and the
    fade and volume handles are easier to see and to grab. It is saved with the layout and works with any track height.
  - **Waveform scale**: *Linear* (the default: the height follows the loudness of the clip, speech and pauses stand out) or *dB*
    (quiet passages stay visible). Audio is drawn as a solid light envelope rising from the bottom of the clip, over a darker body, with a fainter
    shape behind it for the loudest peaks and a thin line across the middle; zoom in with a pinch to see individual sounds, and a gap
    between words is a flat thin line along the bottom, which is where to cut.
  - **Media tray** and **Inspector**: choose where each one sits (bottom or over the timeline, or left or right on
    wide windows) and whether it is **collapsed**. A collapsed side panel becomes a narrow strip with one button
    that brings it back; the collapsed tray at the bottom becomes a thin bar with an arrow.
  - **Customise layout**: makes the dividers bigger and adds buttons to the panels that move them between docks.
  - **Reset layout** goes back to the defaults of this window shape.
- If the window changes (rotation, split screen, folding), the layout for the new shape is loaded and kept inside
  what fits.

## Toolbar icons

**Not sure what a symbol does?** Three ways to find out:

- **In the app, offline:** tap the **?** button in the top bar of the editor, or open **About, privacy and help**, then **Toolbar guide**.
  It lists every symbol with its own picture, what it does and when it is available, grouped by where it sits, plus the
  gestures. Type in the search box to narrow it down. It is part of the app, so it always matches your version.
- **On the web:** [Toolbar symbols](https://qtekfun.github.io/UltimateVideoEditor/icons.html) shows the same list, and **About, then Online guide**
  opens the whole guide in your browser. (The app itself stays offline: your browser fetches the page after you tap.)
- **As a PDF:** each release page on GitHub has this guide as a PDF for reading without a connection.

Every icon also has a text description: long press it to see its name, or use a screen reader. In order:

| Icon | Name | What it does |
|---|---|---|
| ← | Back | Saves and returns to the hub. Refused with a dialog if saving keeps failing. |
| ↶ / ↷ | Undo / Redo | Steps through every edit. Each drag, drop and inspector change is one step. |
| ⬆ | Export movie | Opens the export dialog. |
| ⏮ / ▶ / ⏭ | Previous boundary, Play / Pause, Next boundary | Boundaries are clip starts and ends. Pressing a boundary button while playing stops playback and puts the playhead exactly on the boundary; press Play to continue. Play follows the audio clock. |
| 🖼 (picture) | Save frame as image | Saves the picture under the playhead as a JPEG in Pictures/ultimateVE with one tap, see [Saving a frame as an image](#saving-a-frame-as-an-image). |
| ⤢ | Fit the whole project | Zooms the time axis so the whole project fits. It keeps following the panel (rotation) and the project length until you pinch. The lane heights are not changed: they are fixed by the Layout sheet's Small / Medium / Large chips, and lanes that do not fit the panel scroll vertically. |
| + | Import media | Adds videos, photos or audio at the playhead (needs a track selected for overlays). |
| ▦ | Layout | Opens the layout sheet: presets, track height, where the panels sit, customise and reset. |
| ✂ | Split at playhead | With nothing selected, cuts every clip on every lane (video, audio, titles) that the playhead is over, as one undo step; a video clip and its linked audio are cut together, and the selection stays empty. Otherwise cuts the selected clip in two (with several selected, every one the playhead is over, as one undo step). Playback pauses and the playhead stays exactly on the cut, the first frame of the right-hand part, which becomes the selection so you can move the playhead and cut again. Undo and redo leave the playhead where it is. |
| ⛶ (dotted square) | Select several clips | Turns select mode on or off; see [Selecting several clips](#selecting-several-clips). |
| 🗑 | Delete | Deletes the selected clip. On the base track the gap closes and overlays over the removed part are trimmed or removed; on other tracks a gap is left. |
| →← | Close gap before clip | Slides an overlay or audio clip back to the end of the previous one. Disabled on the base track, which does it automatically. |
| T | Add a title | Adds a text title at the playhead. |
| CC | Captions | Opens the captions sheet: type captions, import a `.srt` / `.vtt` file, choose a style and restyle all captions. |
| ☺ | Stickers | Opens the media tray on the **Stickers** tab (built-in shapes and emoji). |
| Tt | Titles and templates | Opens the media tray on the **Titles** tab (lower third, pop title, slide-in headline, subtitle bar). |
| ⇄ | Transition | Adds a transition (a crossfade to start with) between the selected clip and the next one; choose its look in Adjust clip. |
| ≡ (sliders) | Adjust clip | Opens the inspector for the selected clip. |
| ▭ (canvas) | Canvas format | Changes aspect ratio and resolution of the project. |
| ⚑ | Marker | Tap: drop a marker at the playhead. Long press: previous / next marker, beat tools and marker snapping, see [Markers and beats](#markers-and-beats). |
| Stack of clips with a play triangle | Media library | Opens the media library: tags, notes, where each file is used, remove unused, export a bundle, see [Media library](#media-library). |
| ▮▮▮ | Quick edits | Cut silences from the selected base clip, or reframe a clip for another canvas shape, see [Quick edits](#quick-edits-cut-silences-and-reframe). |
| ⚡ (lightning) | Proxy media | Opens the proxy sheet: small copies of heavy video for smooth editing, see [Proxy media](#proxy-media). |
| Two faders | Mixer | Opens the mixer sheet, see [Sound](#sound). |
| Two overlapping frames | Multicam | Opens the multicam sheet: line up several cameras by their sound and cut between them, see [Multicam](#multicam). |
| ◫ (safe zone) | Safe zones | Shows TikTok, Reels or Shorts safe areas over the preview. |
| ▮▮▮ (bars) | Video scopes | Opens or closes the scopes over the preview, see [Colour grading and scopes](#colour-grading-and-scopes). |
| ◈ (layers) | Add track | Adds a video track (above the others) or an audio track. |
| – | Remove selected track | Removes the selected track if it is empty and not the last of its kind. |
| ▲ / ▼ | Move lane up / down | Reorders the selected overlay lane. The base track never moves. |

If an icon looks different on your device, its description always matches the table.

## Media tray

The tray keeps everything you can add in one place. On a phone it sits under the timeline: drag its header
up or down, or use the arrow button, to switch between collapsed, half and full height. On a tablet it is a
panel on the left.

- **Tabs**: **Media** (videos and photos of the project), **Stickers**, **Titles** (text templates) and
  **Audio** (audio files of the project).
- **Media and Audio tabs**: a grid or a list (the Grid/List button), a search box, and on the Media tab the
  filters All, Video, Photos and Unused. Each tile shows a thumbnail, the duration, an **HLG** or **PQ** badge
  for HDR files, **×N** when the file is used N times on the timeline, and a red **Missing** cover when the
  file cannot be read (relink it from the banner).
- **Import** (the first tile): adds files to the tray without putting them on the timeline.
- **Add at the playhead**: tap a tile, or tap the **+** on it (screen readers: the tile's "Add to timeline"
  action). Stickers and templates are also added with a tap.
- **Drag onto the timeline**: press and hold a tile for a third of a second (you feel a tick) and it lifts: a
  small copy of it (picture, name, length) follows your finger anywhere on the screen, and on a phone the tray
  fades so the timeline above it is easy to reach. Keep the finger down and move it over the timeline. While you
  drag, the timeline shows what releasing will do, exactly like moving a clip: near a cut on the base track a vertical bar means **Insert**; over a
  base clip a tinted range means **Overwrite**; on an overlay, audio or title lane a tinted range shows where
  the clip lands (and replaces what it covers); above the top lane a green placeholder means a **new lane**;
  a red tint (wrong kind of lane, for instance audio on a video lane, or far outside) means **cancel**. The
  timeline scrolls when you hold near its sides (sideways) or its top and bottom (through the lanes), after a
  short pause so that crossing an edge on the way in does not make it jump; a thin line shows when the clip's
  start or end snaps to the playhead, a marker or another clip. Release to drop; undo removes it in one step.
  To change your mind, release outside the timeline (the copy flies back to its tile), put a second finger
  down, or press Back. Moving the finger before the hold is over scrolls the tray as usual.
- **Reorder**: hold a tile and drop it on another tile of the tray to change the order of the library.
- **Files from other apps**: on tablets and in split screen you can drag videos, photos or audio from another
  app (for example Files) onto the tray, to add them to the library, or onto the timeline, to place them where
  you drop. If the source does not allow keeping access, the files work in this session and may need to be
  relinked after you restart the app.

## Timeline: tracks, gestures and drops

### Tracks

- The **base track** is the lowest video track and the guide of the timeline. It has no gaps: edits on it
  close or open space automatically, and overlays follow.
- **Overlay tracks** sit above the base, stacked upward. They are free: clips can be placed anywhere and gaps
  are kept. Higher lanes are drawn on top.
- **Audio tracks** are below the base. **Title** tracks hold text and caption clips.
- **Lane headers**: a small name tab (V3, V2, V1 for the base, A1, T1...) sits over the left edge of every lane, with a
  stripe in the lane's colour down its edge; audio lanes also show a red **M** chip when muted and a yellow **S** chip when soloed. **Tap** a header to select the lane.
  **Long press and drag** a header up or down to reorder the lane: a bar shows where it will land and releasing
  applies it as one undo step. Lanes only reorder among their own kind (overlay videos together, audio together, titles
  together) and the base never moves; the ▲ / ▼ buttons do the same one step at a time. The header covers the first
  22dp of every lane, so scroll the timeline slightly if you need to grab the very start of a clip.

### What the timeline shows

- **Ruler.** The time labels follow the zoom: `0:05`, `1:00`, `1:02:05` for whole seconds, minutes and hours; zoomed in
  far enough they become `0:05:12` (minutes, seconds, frames) and every frame gets a tick. Small ticks between the
  labels appear when there is room. The red tag at the top of the playhead shows the exact time under it.
- **Blocks** are rounded and coloured by what they are: blue video, green audio, violet titles, orange photos, pink
  stickers, teal multicam. The strip at the top of a block holds its name, a speed label (`2x`, `0.5x`, `<` reverse,
  `||` freeze), the keyframe diamonds and the effects badge.
- All text is drawn with the phone's own font and sizes, so it stays sharp on dense screens; nothing is downloaded.

### Gestures

- **Tap** a clip to select it (yellow outline and a handle at each end, the edges you can drag to trim). Tap an empty lane to select that track.
- **Drag the ruler or the red playhead** to scrub. While the project is playing, **swiping the timeline sideways** (a drag or a fling along the time axis, not a clip drag) also stops playback at that moment, with the picture and sound stopped on the frame the playhead shows, and then scrolls as usual; press Play to continue. A mostly vertical swipe (scrolling the lanes) does not stop it, and a plain tap on the ruler jumps the playhead and keeps playing.
- **Drag a selected clip** to move it. Drag its **left or right edge** to trim.
- **Pinch** on the timeline to zoom the time axis (only the time axis, whichever way the fingers spread). The lane height does not change by gesture: the Layout sheet's Small / Medium / Large chips set it.
- **Drag** on an empty area to scroll, **fling** to coast.
- On a selected **audio clip**: **drag the white circles** in its corners for fades, **double tap** it to add a volume point, **drag a dot** to shape the volume (see [Fades and volume on an audio clip](#fades-and-volume-on-an-audio-clip)).
- Snapping pulls clip edges to neighbours, the playhead and markers (about 8 frames).

### Detach the audio of a video clip

A video clip plays its own sound. To cut, move or delete that sound on its own, select the clip and tap **Detach audio** (the
picture-over-note icon in the editing tool row). The sound becomes a clip on the first audio lane with room (a new lane is added when
there is none), at the same frames and from the same part of the file, and the video clip goes silent. The video clip stops
showing a waveform (it has no sound of its own now; the audio clip shows it), and both linked clips carry a small chain mark at the
right end of their name strip.

- The two stay **linked**: moving, trimming, splitting, changing the speed or deleting the video clip does the same to its audio, and
  the other way round, all as one undo step. If the audio cannot follow (it would land on another audio clip) the edit is refused with
  a message.
- Open **Adjust clip** and the **Linked audio** block: **Unlink** makes the two independent. Then you can cut the audio into pieces,
  delete pieces, move it or trim it without touching the picture.
- **Delete the audio clip** (linked or not) and the picture stays, silent. Use **Restore embedded audio** (on the video clip, or on
  its linked audio) to give the clip its own sound back; the linked audio clip is removed.
- **Relink** joins a video clip with an audio clip of the same file that is not linked (select either one). If the audio plays early or
  late against the picture, the block says by how many frames; **Relink and realign** moves the audio back into sync first.
- Preview and export play exactly the same mix. Volume, pan, fades and EQ of the video clip are copied to the audio clip when you
  detach; from then on use the audio clip's own controls.
- A **transition** between two video clips whose sound is detached crossfades the sound too: the two audio clips overlap and fade
  over the same frames as the pictures, just like the embedded sound of a plain clip. (This needs the audio clip to start or end at
  the same frame as its picture; an audio clip you slid out of sync, or unlinked, keeps its own edges and fades.)
- To have this done for every new clip, open the **Layout** sheet and turn on **Put video audio on an audio track**. From then on a
  video clip that has sound arrives already detached and linked, whether you tap it in the media tray, drag it onto the timeline,
  drop a file or import it; undo removes the clip and its audio together. It is off by default and applies to the whole app, not to
  one project. Photos, silent videos and audio files are placed as before, and pasting or duplicating copies a clip as it is.

### Fades and volume on an audio clip

Select a clip on an **audio lane**. Two **white circles** appear in its top corners and the clip shows its volume
curve as a yellow line over the waveform (a flat line while the clip has no points).

- **Drag the left circle to the right** for a fade in, **the right circle to the left** for a fade out. The shaded ramp
  shows the curve; the two fades cannot cross. The Sound tools sheet sets the exact length and the shape.
- **Double tap the clip** where you want the volume to change: a yellow dot appears at that time and height. The first
  dot also pins the clip's fixed volume at both ends, so only the part around the dot changes.
- **Drag a dot** up for louder, down for quieter (the top of the lane is +12 dB, the bottom edge is silence, and the
  value sticks to 0 dB when you are close) and sideways to move it in time; it stays between its neighbours. Each
  gesture is one undo step.
- **Double tap a dot** to remove it.

Clips on video lanes show their fades and curve as read-only drawing; edit those from the Sound tools sheet. Preview and
the exported movie use the same fades and curve.

### Selecting several clips

- **Select mode** (dotted-square button, highlighted while on): tap clips to add or remove them, and drag on
  empty lane space to draw a rectangle that adds every clip it touches. Tapping empty space keeps the selection.
- **Long press** a clip in any mode to add it to the selection (or remove it again). Keep the finger down and move it to drag the clip straight away; a clip that was already selected stays selected when you drag it. A plain tap on a clip goes
  back to just that clip.
- The clip chosen last has the **yellow** outline: it is the one the inspector edits. The others are outlined
  in **blue**.
- A **selection bar** appears under the toolbar with the count and the group actions: copy, cut, paste,
  duplicate, delete, paste attributes, align starts, align ends, transitions, and a menu (select the whole
  lane, everything after the playhead or all clips, speed, volume, opacity) plus a button to clear the selection.
- **Drag any selected clip** to move the whole group together; the clips keep their offsets and lanes, snap as a
  block and a position that would land on another clip is refused. Dragging onto another lane of the same kind
  moves all of them by that many lanes.
- Every group action is **one undo step**. If an action cannot apply, a message says why (for example, base clips
  can only move together when they touch each other, and the base track cannot be aligned).
- **Copy** keeps the clips with their layout, effects, keyframes, speed and transitions between them. **Paste**
  puts the earliest clip at the playhead: overlay clips keep their offsets (and fail rather than land on another
  clip), base clips are inserted at the nearest cut. **Duplicate** pastes right after the last selected clip.
  **Paste attributes** copies transform, effects, volume and speed of the copied clip onto the selection.
- **Transitions** add a crossfade at the cut after each selected clip that touches the next one, or fade each
  picture clip in and out (head and tail).

### Drops (what happens when you release a dragged clip)

An indicator shows the action while you drag:

- **On the base, near a cut between two clips** (or the start/end): **Insert**. Later clips shift right and
  overlays that start at or after the cut shift with them. "Near" is about a fingertip wide at the current zoom, and
  counts the clip's start edge, its end edge and your finger; so you can drop a clip between two others even when the
  whole project is zoomed out. The timeline already shows the gap and the shifted clips while your finger is down.
- **On the base, over the body of a clip**: **Overwrite**. The covered footage is replaced; the base length
  does not change.
- **On an overlay, audio or title lane, in a cut between two touching clips**: **Insert**. Only that lane's later clips shift right; nothing else moves.
- **On an overlay, audio or title lane, elsewhere**: overlapping clips are **overwritten**; free space is a plain move.
- **Above the top lane**: a green placeholder shows a **new lane** that the clip will move into.
- **Far outside the lanes**: red tint, release to **cancel**.
- **Choosing Insert or Overwrite while dragging.** A small chip at the top right of the timeline shows what letting go
  will do ("auto" means your position decides). Tap it, or tap anywhere on the timeline with a second finger, to switch
  to the other action; the preview changes at once and the choice holds while you keep moving. Forced Insert goes to the
  nearest cut of that lane; forced Overwrite replaces what the clip covers even beside a cut. It resets for every drag,
  and releasing is one undo step either way. There is no dialog.
- Dragging a base clip up onto an overlay lane **lifts it off the base**: the base closes the gap and no
  overlay is deleted or shifted.

## Inspector

Select a clip and tap the sliders icon. Sections depend on the clip:

- **Transform**: position, scale, rotation and opacity (you can also drag, pinch and twist on the preview). Under the Rotation slider, **-90°** and **+90°** turn the clip a quarter turn counter-clockwise or clockwise from where it is, one undo step each.
- **Volume**: gain in dB for clips with audio.
- **Sound tools** (clips with audio): pan, fades, equaliser, noise suppression and loudness, see [Sound](#sound).
- **Speed**: presets and a slider (0.1x to 100x), reverse, ease-in / ease-out / bell ramps and **Freeze frame at
  the playhead**. Audio follows the speed between 0.25x and 4x and is muted outside that range. The **speed curve**
  editor sets the speed at points along the clip (drag, add or remove points, smooth or hold between them) and has
  presets: montage, hero, bullet and eased in / out. **Smooth slow motion** fills the frames between two source frames
  by following the motion in the picture (no AI, all on the device); the preview lowers its quality if the phone cannot
  keep up, the export always uses the full quality. Fast or complex motion falls back to blending the two frames.
- **Denoise** and **Deflicker** (effects): Denoise reduces video noise (strength and how much of the previous frame
  is used); Deflicker evens out brightness that pulses from frame to frame (strength).
- **Stabilise** (video clips): a switch, **Strength**, **Crop** (tight, medium, full) and an **Analyse** button. See
  [Stabilising shaky footage](#stabilising-shaky-footage).
- **Keyframes**: diamond button to add or remove a keyframe at the playhead, previous / next keyframe, and the
  interpolation (linear, ease, hold, Bezier with two handle sliders). Editing an animated clip at the playhead
  writes a keyframe. Effect sliders, colour-grade sliders, Volume, Pan and the EQ band gains each have their own
  diamond. A **Keyframes** lane under the selected clip shows every animated parameter: tap a point to select
  it, drag it in time or value, then use copy / paste, jump to previous / next, or clear the track. Keyframes
  follow the clip when you split, trim, move or change its speed.
- **Effects**: add up to 8 effects (colour grade, brightness, contrast, saturation, exposure, temperature,
  tint, blur, sharpen, vignette, grayscale, sepia, chroma key, LUT, HSL qualifier), reorder them, plus a **blend
  mode** and a **mask** (rectangle or ellipse, feather, invert). The **colour grade** has its own editor, see
  [Colour grading and scopes](#colour-grading-and-scopes). The **HSL qualifier** is a secondary correction: it keys a
  range of hue, saturation and luma and changes only the colours inside it. Its editor has:
  - **Pick colour**: tap it, then tap the picture on the preview. The key is set from the colour under your finger
    (hue centre and width, saturation and luma ranges around it) as one undo step; a grey keys every hue instead.
    The colour is read from the clip's own picture at the playhead before any effect, so the playhead must be on the
    clip, and a tap beside the picture just asks you to tap again. In an HLG project the picture is read the way the
    platform shows it (SDR), so refine the key by eye with Show matte.
  - **Show matte** (the selection as a grey picture, white = selected, to set the key) and **Invert** (correct
    everything else), as chips.
  - **Hue**: a strip of the colour wheel with the selected hues marked (the range wraps around the red end), and
    sliders for the centre, the width and the softness of the edges.
  - **Saturation** and **Luma**: a *From* and a *To* slider each (the lower one never goes above the other) plus a softness.
  - **Correct inside the selection**: hue shift, saturation and lightness, with *Reset correction*.
- **Title text**: for title clips, the text, size, colour, alignment and bold.
- **Transition**: its length at the cut and its **look**. Pick from Crossfade, Slide, Push, Zoom, Spin, Glitch, Wipe,
  Whip pan and Light leak; Slide, Push, Spin, Wipe and Whip pan also ask for a direction (the way the new picture
  travels). A strip of three frames shows what the chosen look does. Slide brings the new clip over the old one, Push
  carries the old one out, Zoom and Spin scale or turn the pictures while the new one fades in, Glitch jitters and
  flickers between them, Wipe reveals the new clip with a soft edge, Whip pan is a fast push with motion blur and
  Light leak adds a warm glow while it crossfades. All looks cross-fade the sound. Looks need spare footage around the
  cut, like the crossfade, and the preview and the exported movie draw them identically.
- **Reset** restores the clip's appearance; **Done** closes the panel.

Title and sticker blocks on the timeline show their text or name, in the system font: accents, other scripts, symbols and emoji all come out as typed (a long text is cut with …). Video, audio and photo blocks show the name of their file in the strip at the top of the block.

Photos and stickers behave like clips with no source length: stretch them freely from either edge. Speed and
reverse do not apply to them.

**Animated GIFs** import like photos and play: the clip starts at one pass of the animation and loops it for as long as
you stretch it, with each frame shown for the delay the file states (delays of 10 ms or less count as 100 ms, as in
browsers). The animation starts at the clip's first frame, so trimming the start does not skip into it. The export draws
the same frame at every frame of the movie. **Animated WebP** files work the same way (lossy, lossless and transparent
frames, with their own durations and blend/dispose settings). The file's own loop count is honoured: a GIF or WebP that
asks to play N times plays N times and the clip then holds the last frame (a GIF without a loop setting plays once, one that
asks for "forever" loops). Projects saved before this keep looping.
Pictures are kept at their own size and scaled by the GPU, and an export loads each frame only when the movie reaches it, so
even an animation of hundreds of frames on a 4K canvas stays within a fixed memory budget (128 MB). The only cost of a very
long animation is time: frames that fall out of the budget are decoded again when it loops.

## Stabilising shaky footage

Select a video clip, open the inspector and turn on **Stabilise**.

1. Tap **Analyse**. The app measures how the camera moved, once per video file, in the background (a progress bar
   and a Cancel button show up; you can keep editing). Nothing leaves the device: it only follows the picture's own
   features frame to frame.
2. **Strength** sets how steady the result is. Low values only calm small jitter; high values also smooth out slow
   wobbles and follow the overall movement loosely. Moving the slider is instant, no new analysis is needed.
3. **Crop** decides how the moving frame edges are hidden. *Tight* zooms in so no edge ever shows; *Medium* zooms
   half as much and may show a little repeated border on the shakiest frames; *Full* does not zoom and repeats the
   border pixels where the picture moves away.

The status under the controls says **Ready** when the clip is covered. If you extend the clip beyond the part that
was analysed it becomes **Stale**: tap **Analyse again** (the earlier part is kept). Turning the stabiliser off or
changing its settings is one undo step. Preview and export use the same correction.

It works best on handheld footage with plenty of detail. A mostly flat picture (a blank wall, the sky) or a scene
where something big fills the frame and moves cannot be measured reliably; a clip with nothing to follow reports
that it has too little picture or movement to analyse. Rolling-shutter wobble is not corrected.

## Tracking a moving object

Make a title, a sticker or another clip follow something that moves in a video clip. Everything is measured on the
device from the video's own picture; nothing is sent anywhere.

1. Select the **video clip** and move the playhead onto a frame where the object is clearly visible.
2. Open the inspector, find **Track motion** and tap **Track an object**. Pick the box size (Small, Medium or Large) and
   **tap** the object on the preview, or **drag a box** around it. Choose something with detail (an edge, a logo, a
   face); a flat area cannot be followed.
3. The app follows it forward and backward through the clip in the background (a progress bar and a Cancel button show
   up; you can keep editing). When it is **Ready**, **Show path** draws the route on the preview: red parts are where the
   object was lost (hidden, blurred or out of the picture); there the marker holds its last position and the following
   frames pick it up again if the object comes back.
4. Select the **title, sticker or overlay clip** that should follow, open its inspector and tap **Follow** next to the
   track. The clip is placed on the object and gets position keyframes along the path, in one undo step. A few keyframes
   describe the route, and you can still move, delete or change them like any other keyframes.

You can keep several targets on a clip and delete the ones you do not need. If you extend the clip beyond the analysed
part the target says it needs **Analyse again**. The clip that follows keeps its own size, rotation and opacity, and
only its position follows the object. Tracking a stabilised clip works on the original picture, so on very shaky
footage the marker can be a few pixels away. Photos and stickers cannot be tracked.

## Colour grading and scopes

### Filters (the built-in looks)

In **Adjust clip**, choose **Add** in the Effects section and pick **LUT**. The list starts with about twenty
built-in **filters**, each with a small swatch that shows what it does to skin, sky, foliage, red and two greys:
Cinematic, Teal and orange, Warm glow, Cool breeze, Faded film, Vintage, Golden hour, Noir, Silver, Bleach bypass,
Vivid, Muted, Moody blue, Sunset pink, Matte, Cross process, Forest, Day for night, Pastel and Original (no
change). Tap one and it is added to the clip as a LUT effect, so you can lower its **Intensity**, reorder it or
combine it with a colour grade. The filters were written for this app and are generated on the device the first
time you use them (nothing is downloaded); your own imported `.cube` files are listed below them.

### Colour grade

Select a video clip, open **Adjust clip**, choose **Add** in the Effects section and pick **Colour grade**.
The grade is one effect with:

- **Looks**: **Save look** stores the current grade under a name, **Looks** lists the saved ones (apply or
  delete), **Copy grade** and **Paste grade** move a grade from one clip to another. Applying a look or
  pasting replaces the clip's colour grade (or adds one) in a single undo step. Looks live on the device
  and are shared by all projects.
- **Lift, Gamma, Gain wheels**: drag the puck towards a colour to push that colour (a drag moves the puck only when it starts on the puck, a tap
  places it, and a swipe that starts elsewhere over the wheel scrolls the panel; lift = shadows, gamma =
  midtones, gain = highlights); the slider under each wheel moves that range up or down; double tap a wheel
  or use **Reset** to centre it.
- **Offset** (red, green, blue), **Contrast** and **Pivot** (the level contrast turns around), **Saturation**,
  **Vibrance** (saturates dull colours more than vivid ones), **Temperature** and **Tint**.
- **Curves**: Master, Red, Green and Blue. Tap on the curve to add a point (up to 8), drag a point to move
  it, long press a point to remove it; **Reset curve** puts it back to a straight line.
- **Reset colour grade** clears everything.

The grade works on the picture in the project's colour space (the Rec.709 signal in an SDR project, the HLG
signal in an HLG project). Every drag is shown live and is one undo step when you let go. The effect is the
same in the preview and in the exported file.

### Scopes

The **bars** icon in the toolbar opens the scopes over the bottom left of the preview: **Waveform** (how
bright each column of the picture is), **RGB parade** (red, green and blue side by side), **Vectorscope**
(colour direction and strength; the ring is full saturation, the line marks skin tones) and **Histogram**
(how many pixels sit at each level, with a white line for brightness). They are drawn on the GPU from what
the preview shows, up to 30 times a second, and only while open. The scale is in percent; in an HLG project
75 % is marked as 203 nit and 100 % as 1000 nit.

## Titles, captions, stickers and templates

- **T** adds a title. Drag it on the preview to move it (for a lower third, drag it to the bottom).
- **Tt** opens the Titles tab of the tray: lower third, pop title, slide-in headline and subtitle bar, then "My
  presets". Type your text first if you want, then tap a template; each one is a single editable title.

### Titles with layers, fonts and presets

Select a title and open the inspector. A plain title has one text style; tap **Edit as layers** to turn it into a
multilayer title (a caption with word timing stays a plain title).

- **Layers** are listed top first. **+ Text**, **+ Shape** (rectangle, rounded rectangle, ellipse, line),
  **+ Sticker** and **+ Photo** add one on top (up to 16). Tap a layer to edit it; **Up**, **Down**, **Copy** and
  **Remove** reorder, duplicate or delete it. Every change is one undo step.
- **Text layers** have text, size, colour, alignment, bold, italic, letter spacing, line height, border, shadow, a
  background box and a font. **Shapes** have size, fill, outline, shadow and corner radius. **Pictures** have a size
  and a shadow. Each layer is placed inside the title (across, down, scale, rotation, opacity).
- **On the preview**, with a layer selected, drag, pinch and twist move, scale and turn that layer (a ring and cross
  mark it) instead of the whole title.
- **Fonts**: **Import font…** picks a `.ttf` or `.otf` file from your device. Fonts stay on the device and are not
  embedded in projects or presets: if a project uses a font this device lacks, a banner says so and the default font
  is shown until you import the same font file. Check that a font's licence lets you use it in your videos.
- **Animation**: pick an **In** and an **Out** (fade, slide from a side, pop) and **Apply animation**; it becomes
  keyframes of the title and replaces any it had.
- **Presets**: **Save** stores the title (without photos, which belong to one project) with its animation as a preset
  in "My presets" of the Titles tab. **Export** writes a `.uvtitle` file you can share; **Import a .uvtitle file…**
  adds one from a file. Nothing is sent anywhere.

- **CC** opens the captions sheet. Type a caption, set where it starts and how long it lasts (the buttons step by one
  frame or one second; the next caption starts where the last one ended), and tap Add. Or import a `.srt` or `.vtt`
  subtitle file (it can start at the project start or at the playhead). Pick a style: Classic, Bold, Pop, Impact,
  Karaoke, Word pop, Typewriter or Bounce, and text and highlight colours. "Restyle" changes every caption at once.
  Nothing is sent anywhere: the app has no network access.
- **☺** opens the Stickers tab; tap a sticker to add it on an overlay lane.

## Markers and beats

**Drop a marker with one tap.** Tap the flag icon: a marker lands at the playhead at once and a small
"Marker added · Edit" note appears for a few seconds (tap **Edit** to name it). Tapping the flag again with
the playhead on a marker (within two frames) opens that marker instead of adding a second one. Adding is one
undo step.

**Markers stick to the video.** A marker dropped over a clip is attached to it: insert a clip in front, delete or
move clips before it, or move the clip itself, and the marker goes with the clip (on the base track the clip under the
playhead wins, otherwise the topmost overlay clip). Trimming the start keeps it on the same picture; if a trim cuts
the marker off it stays on the clip's first or last frame. Changing the speed scales its position with the clip;
splitting hands it to the part that holds it (a marker exactly on the cut goes to the right part). Deleting the clip
keeps the marker where it was, as a free marker (Undo brings both back). A marker over a gap is free and stays at its
frame. The **Stick to clip** switch in the marker panel turns this on or off for one marker at any time (it is off and
greyed out where there is no clip). Dragging a marker attaches it to the clip it lands on. Beat markers are always
free. Projects from before this feature open with free markers; switch them on one by one.

**Edit a marker.** Tap the marker's flag on the ruler (the touch area is about 40 dp wide, bigger than the
flag). The playhead jumps onto that marker and a small panel opens under the ruler with:

- a **name** (one line, up to 40 characters) and a **note** (several lines, up to 200 characters). When the
  timeline is zoomed in far enough, the name is written next to the flag, in the system font (accents, symbols and emoji
  included; a long name is cut where the next marker starts);
- the **Stick to clip** switch (see above);
- six **colours**; tap the chosen one again to clear it. The flag and the faint line through the lanes take the
  colour (pink when there is none), and a small light square under the flag shows that there is a note;
- **‹ ›** go to the previous or next marker (the playhead follows);
- the bin deletes the marker, **Done** closes the panel. Everything you type shows at once, and the whole
  editing session is one undo step. Tapping the timeline elsewhere also closes the panel and keeps the edits.

**Move a marker.** Touch a marker's flag and drag along the ruler: it follows your finger in whole frames, snaps
to clip edges, the playhead and other markers (about 8 frames) and cannot sit on another marker. Releasing is one
undo step. Dragging the playhead or the empty ruler still scrubs.

**Long press the flag** for the rest:

- **Previous marker / Next marker**: move the playhead without opening anything.
- **Find beats in the selected clip**: detects the rhythm of its audio and drops beat markers on the ruler.
  Works on music with a clear pulse; speech or ambience may report "no clear beat".
- **Cut to beat**: ends the selected base clip and the following ones on the nearest beats.
- **Clear detected beats** and **Snap to markers** (on/off; when on, the previous / next edit point buttons also
  stop at markers).

Names, notes and colours are saved with the project and are written to FCPXML exports (the name, or the note when
there is no name, with the colour as a `[red]` prefix in the marker text). Older projects open as before.

## Quick edits: cut silences and reframe

The level-meter icon next to the flag opens two edits that need no analysis beyond the clip's own audio or
the canvas shape. Nothing is detected with a model and nothing leaves the device.

**Cut silences** (select a clip on the base track that has audio and normal speed):

1. Set what counts as silence: the level (in dB, quieter than it), how long it must last, and how much to keep at
   each end so words are not clipped.
2. **Find silences** lists the stretches that qualify, with their timecodes. Untick any you want to keep.
3. **Remove** cuts them out in one step: the gap closes, later clips move up and overlays follow, exactly as when
   you delete a base clip by hand. One Undo puts everything back.

It reads the waveform the timeline already shows, so wait until the waveform is drawn. A clip with changed speed
or played backwards is not supported.

**Reframe** (select a video or photo clip; useful after changing the canvas to 9:16 or 1:1):

- Move the two sliders to the point of the picture that matters (for example a speaker's face), choose a zoom, and
  press **Apply**: the picture fills the canvas with that point in the middle, as far as the picture allows.
- To follow a moving subject, move the playhead, set the sliders for that moment and press **Mark at playhead**;
  repeat at other moments, then **Apply**. Each mark becomes an eased position keyframe, editable afterwards in the
  inspector. You pick the point yourself; the app does not look for the subject.

## Sound

Everything here is classical signal processing on the phone: no network, no AI models, nothing leaves the
device. Every setting is non-destructive and one undo step (a slider drag is one step, not one per frame).

**Per clip** (inspector, **Sound tools**; sliders change the sound while you drag during playback):

- **Pan**: left to right, equal loudness across the arc. Mono clips are placed; stereo clips are balanced.
- **Fade in / Fade out**: lengths in frames (the readout shows seconds and frames). Fades follow the clip edge when you
  trim it. Split or overwrite clears the fade at the new cut. **Fade curve** picks the shape: **Equal power** (the
  default, the same as a crossfade), **Linear**, or **Logarithmic** (even to the ear, slow to start). Fades work on
  every clip with sound, video clips included.
- **Volume curve**: a line of points over the clip's waveform. **Add point at playhead** puts a point holding the
  volume the clip has there; **Clear** removes the curve and the clip goes back to its fixed volume. The curve is
  drawn on the timeline and edited there, see below. The sound tools open by themselves for a clip on an audio lane.
- **Equaliser**: low cut, low shelf (100 Hz), peaking bands at 400 Hz, 1.5 kHz and 5 kHz, high shelf (10 kHz) and
  high cut. **Flat** resets it.
- **Noise suppression**: **Mark start** and **Mark end** at the playhead to choose a stretch with only background
  noise, then **Remove noise** and set the **Strength**. It learns the noise from that stretch (spectral
  gating), so it works best on steady hiss or hum. Speech in the marked stretch will be partly removed.
- **Loudness**: measures the clip (ITU-R BS.1770 / EBU R128) and **Normalise to** a target such as -16 LUFS by
  adding gain. **Measure again** after you change the clip. The measurement is cached on the device.
- **Voice effects**: **Off**, or a preset with one to three sliders. **Pitch and formant** shifts the pitch while
  the timbre stays (the **Formant** slider moves the timbre on its own); **Chipmunk** and **Deep** move both together;
  **Robot** (ring modulation plus a very short echo), **Whisper** (noise instead of pitch), **Radio** (telephone band
  and drive), **Echo** (delay, repeats, mix), **Reverb** (size, damping, mix) and **Megaphone**. A slider takes effect
  when you release it (the clip is read again), as one undo step. Echo and reverb keep sounding after the clip's own
  sound ends, up to the end of the clip. Every slider has a keyframe diamond like pan and the EQ gains: tap it to key the
  slider at the playhead and the effect changes over time (a pitch glide, an echo that fades in, a reverb that opens up);
  the sliders then show the value at the playhead and moving one adds a key there. Choosing another preset starts with no
  keys. Reverb size animates the decay, not the size of the room, and an echo delay that moves glides in pitch like a
  tape delay. It is the same signal processing for preview and export, and nothing leaves the phone.
- **Reset sound** restores all of the above.

**Mixer** (toolbar faders icon): for every track a **Mute**, **Solo**, **Volume** (dB), **Role** (Normal, Voice,
Music) and a **Compressor** switch (**Threshold**, **Ratio**, **Make-up**). Solo plays only the soloed tracks.
**Duck music under voice** lowers Music tracks by **Amount** while a Voice track speaks (**Trigger**
threshold, **Recovery** time). It needs one Voice and one Music track. The ducking is a gain curve computed
from the voice: your clips are never changed, and preview and export are the same.

A master limiter at -1 dBFS stops clipping. The level meter above the toolbar shows left and right peaks while
playing. Exported audio is produced by the same mixer as the preview.

## Colour spaces and HDR

- The **project colour space** (Rec.709 SDR or Rec.2020 HLG) is the working and export space.
- Clips shot in another space are converted automatically: HLG clips into an SDR project are tone-mapped, SDR
  clips into an HLG project are placed at reference white.
- With an HLG project and an HDR display, the preview shows HDR. Export offers **HLG 10-bit HEVC** when the
  device encoder supports it, otherwise SDR.

## Exporting

Tap the export icon, choose resolution, frame rate, codec (H.264 or HEVC), bitrate and, if you like, an
"Upload to" preset (YouTube, Shorts, TikTok, Reels, Instagram feed). Choose where to save the MP4. Progress is
shown while it renders; you can cancel (the partial file is removed) and share the file when it finishes.
Export is refused, with the clips named, if some media is missing.

**Checking the saved file.** When the movie is written, the app does not just say "done": it opens the saved file again, in the background,
and checks that it is complete. The dialog, the notification and the project list say **Verifying...** (with a percentage and a **Skip
check** button) and then one of:

- **Checked: the video is complete (18000 frames, 10:00)**. The file has the number of frames and the length it should have, its
  sound is as long as its picture, nothing in it is cut off or empty, the first second and the last three seconds decode without an
  error, and the first and last pictures look roughly like the ones that were sent to the encoder.
- **WARNING: the last N frames look damaged** (or **missing**, or "did not pass the check") with the checks that failed, for example
  "decoding: the decoder stopped at frame 17981 of 18000". The file is **kept**: you decide whether to use it. **Export again** takes you
  back to the settings; the damaged file stays where it is until you delete it.
- **Could not check the file** when the check itself could not run (for example no decoder on the phone could read the file back). This
  never says "checked": play the end of the movie before relying on it.
- **Verification skipped (cancelled)** when you pressed **Skip check**.

Under the result the summary also says how long it took: **Exported in 28:11, checked in 4 s** (the movie itself, then the check of the file). It appears in
the dialog, the project list bar and the notification.

The check takes a few seconds, also for a long 4K movie, because it only decodes the start and the end. If the exporter had to repeat
frames that could not be decoded, that note is shown together with the verdict, never instead of it. The check is part of the export and
cannot be switched off.
**The dialog starts on settings that keep your clips' quality.** It looks at the video clips that are on the timeline (not at
unused files in the library, photos or titles) and picks the smallest bitrate choice that is at least as high as the best of them
(a clip at 80 Mbit/s selects 80 Mbps, one at 52 Mbit/s selects 80 Mbps, 40 selects 50). It switches to HEVC when a clip is HEVC,
10-bit or HDR, or needs more than H.264 should carry. The size stays the project's. When the export is smaller than the clip (for
example 1080p from 4K footage) the rate scales with the pixels, so 80 Mbit/s of 4K selects 20 Mbps at 1080p. A line under the
Bitrate chips says what it did ("Your clips go up to 52 Mbps: 80 Mbps keeps their quality") and the suggested chips are marked
"recommended". You can change anything; picking a lower bitrate shows that some quality is lost. If a clip is above the highest
choice (80 Mbps) the line says so. When the app does not know a clip's bitrate (projects made by an older version learn it the next
time they are opened, LumaFusion imports use the figure in the archive when it has one), the usual defaults are used.

**Estimated size.** The dialog shows "Estimated size: about 3.1 GB (2.5 to 3.4 GB)" under the settings and updates it as you change
resolution, frame rate, codec, dynamic range or bitrate. It is the bitrate times the movie's length plus the audio; the encoder aims
at the bitrate, so the real file is usually close but can be smaller (quiet scenes) or a little bigger. If the estimate is more than
90% of the free space of the phone's storage, a warning says it may not fit. (If you save to an SD card or a cloud folder, the
free space of that place is not known to the app.)

**Smart export (HEVC).** Under Codec, **Copy untouched parts without re-encoding (faster, larger file)** is off by default. When
on, stretches of the movie where one clip is shown on its own, full screen and unchanged (no title, no effect, no speed change, no
transition, no other clip over it) are copied from your original file exactly as they were filmed, instead of being decoded and
encoded again; only the short pieces at the start and end of each stretch and everything with something on it are encoded as usual.
It needs footage in the same format as the export (an HDR export copies HDR footage, the same size and frame rate, for example your
iPhone's 4K 60 fps HLG clips). The file is larger, because camera footage has a higher bitrate than the export bitrate, and the picture
is stored rotated and flagged, like your iPhone files do (every player turns it upright). Phone clips shot upside down are copied that
way; one orientation is chosen for the whole file. If nothing can be copied, or anything goes wrong, the whole movie is exported
normally and a note says so. Progress and the time left count copied stretches as fast.

**Leaving the app during an export.** The export keeps running if you press Home, switch to another app, rotate the
screen, go back to the project list or the screen turns off. While it runs, a quiet notification "Exporting <project>" shows the
percentage and the time left, with a **Cancel** button; cancelling there removes the partial file just like the dialog's Cancel. When
the movie is ready the notification changes to "Export finished". Tapping either notification opens the project that is exporting with
its export dialog (progress and Cancel, or Share and Close, or the failure message); if that is no longer possible (the project was
deleted, or the app was closed and the export is gone) it opens the project list instead. While the export runs and the app is on
screen, the screen stays awake. The first time you press Export, Android asks whether the app may show notifications; if you say no
the export still runs in the background, you just see no notification (look in the Recent apps list to find your way back). Only
one export runs at a time. If Android has to close the app because the phone is out of memory, the export stops; start it again.

**The export bar in the project list.** While an export runs, the project list shows a bar at the bottom with the project name, a
progress bar, the percentage, the time left and **Cancel**; tap the bar to open that project with its export dialog. In the dialog,
**Hide** closes it without stopping the export. When the export ends the bar stays as "Export finished: <file>" with **Share** and
**Dismiss**, or shows why it failed with **Dismiss**; it never disappears by itself (a cancelled export just removes it). Because
only one export runs at a time, the Export button of every other project explains "Another export is running: <project>" instead of
opening the dialog until the first one is done.

## Saving a frame as an image

To make a thumbnail or a cover, move the playhead to the frame you want and tap the **picture icon** next to Fit. That is all: playback
stops, the whole picture (all layers, titles, stickers and transitions, as an export would draw it) is saved as a JPEG at your project's
size (for example 3840 x 2160) in the **Pictures/ultimateVE** folder, so it shows up in your gallery. A message at the bottom shows
"Saving frame..." and then "Saved to Pictures/ultimateVE/<name>" with **Share** and **Open** (the system image viewer).

The file is named after the project, the frame and the moment you saved it, for example `Review_IPhone_18_Pro_Max_00h01m23s12f_20261007-091503.jpg`
(project, hours-minutes-seconds-frames of the frame, date and time). A project larger than 4096 pixels on its longest side is saved at that
limit and the message says so. A frame in a gap of the timeline, or after the end of the project, is saved as a black image and the message
tells you. With an HDR (HLG) project the picture is converted to SDR with the same tone mapping as an SDR export, so it does not look washed
out; it is tagged sRGB. The picture is never saved if it would come out as one flat colour while the frame has video: you get an error
instead. Saving is refused while an export is running. The app asks for no new permission.

## Media library

The **library icon** in the toolbar (a stack of clips with a play triangle) opens the project's media library.
With a clip selected it opens on that clip's file, so it also works as **find in library**.

- **Search** matches names, tags and notes; filter by **All, Video, Audio, Images or Unused**, and tap a
  `#tag` chip to show only files with that tag (the number is how many files carry it).
- Each row shows a picture, the name, the kind, the length, an HLG or PQ badge, **how many times it is used**,
  the tags and the note. Missing files are marked in red.
- **Find in timeline** selects the next clip that uses the file and moves the playhead there; press it again for the
  one after, wrapping round. A message says which use it is ("Use 2 of 4 (V1)").
- **Tags & note** opens a small form: tags separated by commas (up to 16, 32 characters each; duplicates ignoring
  case are dropped) and a note of up to 280 characters. Tags and notes belong to the library, like its order:
  they are saved with the project and are not part of Undo.
- **Remove unused** takes out of the library the files that no clip uses, after asking. It only edits the
  project's list: the files on your device are not touched. A file that **Undo could still bring back** to the
  timeline (or that is on the clipboard) is kept.
- **Export to another tool…** writes the project as a bundle, an EDL or an FCPXML file (see below).

## Sharing a project with other devices and tools

Everything goes through the system file picker and stays on your device: nothing is uploaded.

- **Project bundle (`.uvbundle`)**, to move a project to another phone: from a project card in the hub
  (**⋮ → Export bundle for another phone…**) or from the library's export menu (**Export…**). A dialog asks what
  goes in, with the size it would have:
  - **Media files**: copies your videos, photos and audio into the bundle (it can be large). Off by default;
    without it the bundle carries only their names and sizes and the other phone relinks them. Files the app
    cannot read are named and left out.
  - **Colour LUTs**: the 3D LUTs the project uses. On by default, they are small.
  - **Fonts**: the imported fonts the project uses. Off until you tick it, because many font licences do not allow
    giving the font file to others; check yours first.
  Whatever the project uses but you leave out (or this phone does not have) is still named in the bundle, so the
  other phone can tell you which LUT or font to get. The project file and a card picture are always included.
  - **Progress and the end of the backup.** After you choose where to save, a window shows what is happening
    (**Packing media 3 of 12: IMG_0014.mov**), how many bytes are done of the total, the percent, the speed and the time left
    (for example **1.8 of 7.4 GB, about 2 min left**). **Hide** closes the window and the backup goes on; the same progress is
    in a bar at the bottom of the project list (tap it to open the window again) and in a notification, so you can leave the
    app or turn the screen off. **Cancel** stops within a moment and removes the half-written file; if the file manager refuses to
    delete it, the window says which file is left over. When it ends you read **Backup saved: Holiday.uvbundle (7.4 GB, 14 media
    files, took 4:12)** with **Share** and **Close**; the bar and the notification say the same. The app then reopens the saved
    file and checks that it is complete (its table of contents, every file and its size, the project data). If that finds a
    problem (a full disk, a card pulled out, a cut-short file) the result turns red and says what is wrong; do not rely on that
    file. A backup that cannot finish says why (storage full, permission lost, a media file that vanished) and removes what it wrote.
  - **One long job at a time.** A backup is refused with a message while a movie export runs, and a movie export is refused
    while a backup runs; wait for the first to finish or cancel it. Nothing is queued.
- **Importing a bundle** (hub, top-right ⋮): the project is unpacked next to your other projects, renamed if the
  name is taken ("Name (2)"), and its media is set up for you: files that came inside the bundle are used from
  the project's own folder; for the others the app looks among the files your other projects already use for one
  with **the same name and size** and relinks it; whatever is left is listed, and you can relink it in the
  editor ([Missing media and recovery](#missing-media-and-recovery)). LUTs and fonts that came inside are
  installed into the app's libraries (one that is already there is not copied again; a LUT that clashes with a
  different one gets a new library key and the project is updated to match). If any could not be installed, or the
  project needs one that is neither in the bundle nor on this phone, a list stays on screen until you dismiss it,
  and the project shows them as missing. A damaged or unsafe bundle is refused with a message and leaves nothing
  behind. Bundles made by an older version open here as before, and a bundle made here opens in an older version
  (it simply does not install the LUTs and fonts).
- **Importing a LumaFusion project** (hub, top-right ⋮, then pick the file): the app reads projects from
  **LumaFusion for iOS**, as an **`.lfpackage`** (the project with its footage inside, one big file) or a
  **`.lfarchive`** (only the project). Copy the file to the phone first (USB, cloud or a card). It is recognised by
  what is inside, not by its name. Nothing is sent anywhere.
  - **Media folder.** The footage inside an `.lfpackage` has to be copied out of the package. It goes into a folder
    **you** choose, not into the app's private storage, so you can see and manage the files, and it can be on a USB
    drive or an SD card. The app never puts loose files in the folder you pick: it makes its own subfolder called
    **ultimateVE** inside it (if the folder you pick is already called ultimateVE, or already contains one, that one
    is used). The footage of each imported project goes into `ultimateVE/Media/<project name>/`, so two projects never
    mix, and you can delete one project's footage by deleting that one folder (do that only when the project is gone:
    it still points at the files). Project backups (`.uvbundle`) open the save dialog in `ultimateVE/Project-Backups`.
    Folders are created when first needed, so you only see the ones in use. **About → Media folder** shows the full
    path (for example `Movies/ultimateVE`) and what is inside. Files that an earlier version put directly in your
    folder stay where they are, because projects point at them; nothing is moved or deleted. The first time, the app explains this and asks for the folder; afterwards you can see and
    change it in **About → Media folder**. A progress dialog shows the copy, with **Cancel** (nothing is left behind
    when you cancel or when it fails). The app checks that there is room and says so if not; if the folder is
    unplugged or access was removed, it says that too, and you choose it again. Files already in the folder are never
    replaced: a file with the same name is saved as "Name (2).MOV". **Deleting a project never deletes the files in
    your folder**, they are yours.
  - **A `.lfarchive` without footage** imports with the media shown as missing. Open the project and use **Relink**
    ([Missing media and recovery](#missing-media-and-recovery)) to point each file at the original video.
  - **What comes across:** the canvas size and frame rate; the cuts and every track (the main track as the base
    track, the other video tracks as layers above it, audio tracks); where each clip starts and which part of the
    footage it plays; the project colour space (LumaFusion's HDR becomes HDR Rec.2020 HLG, otherwise SDR; you can switch it any time: in the editor, the canvas button in the top bar > Colour space); photos as photo clips; titles with their text, size, colour, position and the rectangle behind
    them; clip opacity, clip and track volume, pan; and the size and horizontal position of clips in split-screen layouts. (Rotation values are not carried:
    LumaFusion stores the file's own orientation there and the app applies that itself.)
  - **What does not:** a dialog lists, with a count and where, everything that was **not imported**. It currently
    covers reversed clips and speed changes (they play at normal speed in the same place), transitions (hard cuts),
    effects (named), animated (keyframed) values, flips, crops and blend modes, a vertical offset, a turn you added yourself,
    title shadows and fonts (the default font is used), ducking, markers, hidden/locked track states, notes, master
    volume, colour spaces other than the two LumaFusion offers, and clips of kinds this app does not have (generators, blank clips). Features
    that only exist in LumaFusion for iOS are not replicated.
  - **How sure we are:** the cuts, tracks, times and footage matching were checked against two real projects
    written by LumaFusion 5.5.2. Opacity, volume other than silence, pan, rotation, scale and position units and title
    placement are **inferred** from those files (the dialog says "check split screens" when a position was converted):
    compare the result with your original before relying on it. Speed, reverse, transitions, markers and keyframes
    could not be checked because the sample projects do not use them, so they are reported rather than guessed.
- **EDL (CMX3600)**: one file per video or audio track (`-V1` is the base, `-V2` the overlay above it, `-A1`
  the first audio track), saved as a single `.edl` when there is one track and as a `.zip` when there are
  several. 29.97 and 59.94 are written in drop frame, other rates in non-drop frame. Titles, stickers, photos,
  effects, transforms and transitions are not part of the format (transitions become cuts); a speed change is
  written on an `M2` line.
- **FCPXML 1.9**: the base track is the primary storyline, the other tracks are clips connected to it (video
  and titles above, audio below), with trims, constant speed and reverse, position, scale, rotation, opacity,
  clip gain, titles as generators and markers. Effects, LUTs, colour grades, keyframes, masks, speed ramps,
  stickers, transitions and the audio tools are left out. The message after exporting names what was left
  out, and the same list is written in the sequence's note. Media is referenced by its Android address, so
  relink it in the other editor. Positions use a percentage of the frame height and retimes use a time map;
  both are untested against a real Final Cut Pro or DaVinci Resolve.

## Proxy media

Heavy video (4K, long-GOP, very high bitrate) can be slow to scrub. A **proxy** is a small copy of a video (720p or
1080p) that the preview and the timeline thumbnails use while you edit. **Export always uses the original files**, and
sound always comes from the original.

- Tap the **lightning** button in the toolbar to open the proxy sheet.
- **Use proxies for editing in this project** is a switch per project. Turning it on queues proxies for the videos that
  have none; the preview switches to each one as it is ready.
- **Proxy size**: 720p (lighter) or 1080p. Changing it makes new proxies; the old ones stay until they are evicted.
- **Make proxies for all videos**, or **Make / Cancel / Remove** per video. Proxies are made one at a time in the
  background while the app is open; if the app is closed they start again from the beginning next time.
- **Storage**: shows how much the proxies use and lets you set a limit (1 to 16 GB). When the limit is reached the
  proxies used least recently are deleted first; the ones the open project uses are kept. **Clear proxy cache** deletes
  all of them (asks first); projects keep their media.
- Badges on the media tray and library show **Proxy** (ready), **Proxy 43%** (being made), **Proxy…** (waiting) or
  **Proxy old** (the source changed, or the file is gone: it is not used until made again).
- When a project contains heavy video, or the preview keeps dropping frames, a banner offers proxies. Nothing is made
  unless you accept; **Not now** hides the offer for that project.
- HDR videos get an SDR proxy, so the picture looks flatter while editing with proxies on; the exported movie is
  unaffected.

## Multicam

Use it when two to six cameras (or phones, or a recorder) filmed the same event. Everything is local: the angles are
lined up by the loudness of their sound, with no network and nothing learned.

1. Import the recordings, then tap the **multicam** button in the toolbar.
2. Tap the files to use as angles (2 to 6). The first one is the reference; the others are matched against it.
3. **Sync by sound** finds how much later (or earlier) each angle started. "synced +90" means 90 frames later than
   the reference; "unsure" or "no match" means the sound did not give a clear answer (silence, very different
   audio): nudge it with **-1** / **+1** until a clap or a word lines up. Offsets are in project frames.
4. Put the playhead where the multicam clip should start and tap **Create at playhead**. The clip goes on the base
   track (later clips move right) and the sound of the first angle goes on a free audio lane.
5. Select the multicam clip. The sheet now shows one button per angle, labelled **live** (on screen, decoded at full
   quality), **proxy** (would be played from its small proxy copy) or **still**. Tap an angle to **cut to it at the
   playhead**.
6. To cut while the video plays, tap **Record cuts**, press play, tap angles as the action moves, then **Stop
   recording**: all the cuts are applied together and **Undo** removes the whole recording at once.
7. **Remove cut here** merges the stretch with the angle before it, **Sound from** chooses which angle's sound plays,
   **Fine sync** moves one angle by a frame, **Sync again** listens again, and **Flatten** keeps the cuts as normal
   clips and forgets the multicam group.

Good to know: the cuts are ordinary clips on the timeline, so export, speed, effects and the rest work as usual. If
you split, trim or delete one of those pieces by hand, the multicam group is dropped automatically (the clips stay).
An angle can only be cut to where it has recorded: a cut that would go past the start or end of its media is refused.

## Missing media and recovery

If a file was moved or its permission was lost, a banner appears in the editor and the affected clips are
hatched. Tap it to **Relink** each file to a replacement (the app warns if the replacement is shorter or has a
different frame rate or colour space). Everything else stays editable.

### Relink many files at once: scan a folder

If you moved a whole folder of footage, do not relink file by file. In the banner's list choose **Scan a folder...** and pick the
folder that now holds the files (the app asks Android for read access to it and keeps that access). ultimateVE looks through that
folder and the folders inside it, with a progress line and a **Cancel scan** button, and relinks every missing file it can
identify, in one step. Nothing changes until the scan is done.

- A file is matched by **name** (capital letters do not matter). Each match is opened and checked: it must be the same kind of
  media (a picture never replaces a video) and be readable. Differences that do not stop it from working, such as a shorter file,
  are listed as notes.
- If the folder moved as a whole, the first files found show where it went and the same place is tried for the others, which
  also settles files that share a name.
- If several different files share a name, the app compares length and picture size. If that does not settle it, the file is
  listed under **Several files with the same name** and you tap the one you want, or choose another file yourself. The app never
  guesses between two different files of the same name.
- The result says **Relinked N of M**. Files not found are listed with the reason and a **Relink** button that opens the usual
  single-file picker.
- If the files live in different folders (videos in one, audio in another), press **Scan another folder...** on the results screen. It
  scans the next folder only for what is still missing and adds to the same results: files already relinked stay relinked, the
  count (**Relinked N of M**) keeps growing and found files leave the lists. Press it as often as needed. **Back to the list**
  shows what is still missing (with a Relink button each) and offers the same **Scan another folder...**; once a scan has run it is
  the only scan button on any screen, and it disappears when nothing is missing. **Close** ends the session: the next **Scan a
  folder...** starts counting from zero.
- Relinking is saved straight away and is **not an undo step** (the same as relinking one file). To change it, relink the file again.

Each save keeps a `project.json.bak`. If a project fails to load, use **Recover** in the hub.

## About, privacy, tips and crash reports

Open the **More options** menu in the project list and choose **About, privacy and help**.

- **Appearance**: the **Pure black backgrounds** switch (see [Appearance](#appearance-dark-and-pure-black)).
- **Version** of the build and of the engine, the **licence** (GPL-3.0) with the full text, and where the source code lives.
- **Privacy**: what the app stores and why it needs no permissions, the same text as `docs/PRIVACY.md`.
- **Third-party software**: the libraries inside the app and their licences.
- **Storage**: how much space projects, caches and proxy copies use. **Clear caches** deletes waveforms, thumbnails and
  analysis results (they are rebuilt when needed); it never touches projects, your media or the proxy copies (manage
  those in the proxy sheet).
- **Last crash report**: if the app ever crashed, a short text report was saved on your phone. It has the app version,
  phone model, Android version and the technical stack, with file paths and names removed. **Copy** it or **Share** it
  (only when you tap Share does it leave the phone) to attach it to a bug report, or **Delete** it. It also covers
  crashes the app cannot catch itself (a native crash, or the system closing the app because it stopped responding):
  at the next start the app asks the system what happened to the previous run and adds a short summary with the
  function names the system recorded. This all stays on the phone.
- **Show tips again**: three short tips (import, cut and arrange, export and help) appear on first launch and can be
  brought back here.

## Appearance: dark and pure black

ultimateVE is dark, always: it does not follow the system light theme (a light screen next to a video preview was hard on
the eyes and made colours harder to judge). Open **About, privacy and help** and switch on **Pure black backgrounds** to
make the backgrounds black instead of dark grey, which saves power on OLED screens. It applies at once, the timeline
and the other canvases included, and is remembered on this phone only. The app does not use the wallpaper colours either:
the colours are the app's own, so the screens look the same on every phone.

## Known limits

- Most features added since the first editor are covered by automated tests but have had little or no time on a real
  phone (the per-area list is the *Verification debt* table in `PLAN.md`): captions and their styles, photos and stickers,
  beat detection, templates, relink, HDR export, keyframes, stabiliser, tracking, proxies, multicam, interchange and the
  new layout, tray and sheets. Report anything odd with the steps you used.
- Sound tools and the mixer are covered by automated tests; they have had only a short check on a real device
  (the mixer sheet and meter opened and playback ran). Listen to a noise-suppressed clip before exporting.
- Slow motion repeats frames (no blending). Audio speed change is varispeed.
- Animated GIFs and animated WebP files play and loop for the number of times the file asks for (then hold the last frame). A very long animation on a loop
  re-decodes frames that no longer fit the 128 MB picture budget.
- Reverse playback of long-GOP 4K footage is slow.
- Beat detection only reads loudness, not pitch.
- The scopes read the preview at 320 x 180 pixels, so fine detail in a 4K picture is sampled, not counted.
- On overlay, audio and title lanes an insert happens only in a cut between two touching clips; anywhere else a drop overwrites.
- Bundles, EDL and FCPXML exports have been checked with automated tests and golden files, not yet in a real
  editor. A bundle's media is matched on another device only by name and size, among files your other projects
  already use; there is no search of the whole device.
- The library's tags and notes are not undoable (like reordering the tray).
- Fading in and out (head and tail) uses opacity keyframes and does not fade the sound of video clips yet.
- Dragging stickers and templates onto the timeline is not available yet (tap them); dragging media assets is.

# ultimateVE user guide

A short guide to the screens, the toolbar icons and the main gestures. ultimateVE is a lightweight
LumaFusion-style video editor for Android. Some features are new and have only been exercised by
automated tests so far; see [Known limits](#known-limits).

## Contents

1. [Project hub](#project-hub)
2. [Editor layout](#editor-layout) and [Layout](#layout)
3. [Toolbar icons](#toolbar-icons)
4. [Media tray](#media-tray)
5. [Timeline: tracks, gestures and drops](#timeline-tracks-gestures-and-drops)
6. [Inspector](#inspector)
7. [Titles, captions, stickers and templates](#titles-captions-stickers-and-templates)
8. [Markers and beats](#markers-and-beats)
9. [Colour spaces and HDR](#colour-spaces-and-hdr)
10. [Exporting](#exporting)
11. [Missing media and recovery](#missing-media-and-recovery)
12. [Known limits](#known-limits)

## Project hub

The first screen lists your projects as cards: the first frame of the first clip, the name, a short
format line ("1080p · 30 fps · SDR"), the length and the last change. Tap a card to open it.

- **New project** (the one button at the bottom right): opens the sheet described below.
- **⋮ in the top bar**: **Import project file** brings in a `project.json` exported from another device.
- **⋮ on a card**: rename, duplicate, export the project file, delete.
- With more than six projects a **search field** and **Sort by** (Recent or Name) appear.
- Projects that cannot be read are listed with **Recover** (from the `.bak` of the last good save) and
  **Delete**. After a crash the hub offers to reopen the project you had open.

Projects live in the app's private storage. Your media is never copied: projects only reference the files
you imported. The card pictures are made on the device and cached; nothing is uploaded anywhere.

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

1. **Top bar**: back, project name, the **layout** button, undo, redo and export.
2. **Preview**: the current frame.
3. **Divider handle**: the small grey pill under the preview (see [Layout](#layout)).
4. **Transport and toolbar**: timecode, previous / play-pause / next, a fit button and the tools below
   (the toolbar scrolls sideways when it does not fit).
5. **Timeline**: ruler, tracks, playhead.
6. **Media tray** at the bottom (see [Media tray](#media-tray)): collapsed to a thin tab strip until you open it.

On wide windows (tablet, foldable open, or a phone sideways) the tray and the inspector can sit in side columns.

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
  - **Media tray** and **Inspector**: choose where each one sits (bottom or over the timeline, or left or right on
    wide windows) and whether it is **collapsed**. A collapsed side panel becomes a narrow strip with one button
    that brings it back; the collapsed tray at the bottom becomes a thin bar with an arrow.
  - **Customise layout**: makes the dividers bigger and adds buttons to the panels that move them between docks.
  - **Reset layout** goes back to the defaults of this window shape.
- If the window changes (rotation, split screen, folding), the layout for the new shape is loaded and kept inside
  what fits.

## Toolbar icons

Every icon has a text description: long press it to see its name, or use a screen reader. In order:

| Icon | Name | What it does |
|---|---|---|
| ← | Back | Saves and returns to the hub. Refused with a dialog if saving keeps failing. |
| ↶ / ↷ | Undo / Redo | Steps through every edit. Each drag, drop and inspector change is one step. |
| ⬆ | Export movie | Opens the export dialog. |
| ⏮ / ▶ / ⏭ | Previous boundary, Play / Pause, Next boundary | Boundaries are clip starts and ends. Play follows the audio clock. |
| ⤢ | Fit the whole project | Zooms the timeline to show everything. |
| + | Import media | Adds videos, photos or audio at the playhead (needs a track selected for overlays). |
| ▦ | Layout | Opens the layout sheet: presets, track height, where the panels sit, customise and reset. |
| ✂ | Split at playhead | Cuts the selected clip in two. |
| 🗑 | Delete | Deletes the selected clip. On the base track the gap closes and overlays over the removed part are trimmed or removed; on other tracks a gap is left. |
| →← | Close gap before clip | Slides an overlay or audio clip back to the end of the previous one. Disabled on the base track, which does it automatically. |
| T | Add a title | Adds a text title at the playhead. |
| CC | Captions | Opens the captions sheet: type captions, import a `.srt` / `.vtt` file, choose a style and restyle all captions. |
| ☺ | Stickers | Opens the media tray on the **Stickers** tab (built-in shapes and emoji). |
| Tt | Titles and templates | Opens the media tray on the **Titles** tab (lower third, pop title, slide-in headline, subtitle bar). |
| ⇄ | Crossfade | Adds a crossfade between the selected clip and the next one. |
| ≡ (sliders) | Adjust clip | Opens the inspector for the selected clip. |
| ▭ (canvas) | Canvas format | Changes aspect ratio and resolution of the project. |
| ⚑ | Markers and beats | Marker and beat tools, see [Markers and beats](#markers-and-beats). |
| ◫ (safe zone) | Safe zones | Shows TikTok, Reels or Shorts safe areas over the preview. |
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
- **Add at the playhead**: tap a tile. Stickers and templates are also added with a tap.
- **Drag onto the timeline**: long-press a tile and drag. While you drag, the timeline shows what releasing
  will do, exactly like moving a clip: near a cut on the base track a vertical bar means **Insert**; over a
  base clip a tinted range means **Overwrite**; on an overlay, audio or title lane a tinted range shows where
  the clip lands (and replaces what it covers); above the top lane a green placeholder means a **new lane**;
  a red tint (wrong kind of lane, for instance audio on a video lane, or far outside) means **cancel**. The
  timeline scrolls when you hold near its sides. Release to drop; undo removes it in one step.
- **Reorder**: long-press a tile and drop it on another tile of the tray to change the order of the library.
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

### Gestures

- **Tap** a clip to select it (yellow outline). Tap an empty lane to select that track.
- **Drag the ruler or the red playhead** to scrub.
- **Drag a selected clip** to move it. Drag its **left or right edge** to trim.
- **Pinch** on the timeline to zoom, **drag** on an empty area to scroll, **fling** to coast.
- Snapping pulls clip edges to neighbours, the playhead and markers (about 8 frames).

### Drops (what happens when you release a dragged clip)

An indicator shows the action while you drag:

- **On the base, near a cut between two clips** (or the start/end): **Insert**. Later clips shift right and
  overlays that start at or after the cut shift with them.
- **On the base, over the body of a clip**: **Overwrite**. The covered footage is replaced; the base length
  does not change.
- **On an overlay, audio or title lane**: overlapping clips are **overwritten**; free space is a plain move.
- **Above the top lane**: a green placeholder shows a **new lane** that the clip will move into.
- **Far outside the lanes**: red tint, release to **cancel**.
- Dragging a base clip up onto an overlay lane **lifts it off the base**: the base closes the gap and no
  overlay is deleted or shifted.

## Inspector

Select a clip and tap the sliders icon. Sections depend on the clip:

- **Transform**: position, scale, rotation and opacity (you can also drag, pinch and twist on the preview).
- **Volume**: gain in dB for clips with audio.
- **Speed**: presets and a slider (0.1x to 8x), reverse, ease-in / ease-out / bell ramps and **Freeze frame at
  the playhead**. Audio follows the speed between 0.25x and 4x and is muted outside that range.
- **Keyframes**: diamond button to add or remove a keyframe at the playhead, previous / next keyframe, and the
  interpolation (linear, ease, hold). Editing an animated clip at the playhead writes a keyframe.
- **Effects**: add up to 8 effects (brightness, contrast, saturation, exposure, temperature, tint, blur,
  sharpen, vignette, grayscale, sepia, chroma key), reorder them, plus a **blend mode** and a **mask**
  (rectangle or ellipse, feather, invert).
- **Title text**: for title clips, the text, size, colour, alignment and bold.
- **Crossfade**: duration of the transition at the cut.
- **Reset** restores the clip's appearance; **Done** closes the panel.

Photos and stickers behave like clips with no source length: stretch them freely from either edge. Speed and
reverse do not apply to them.

## Titles, captions, stickers and templates

- **T** adds a title. Drag it on the preview to move it (for a lower third, drag it to the bottom).
- **Tt** opens the Titles tab of the tray: lower third, pop title, slide-in headline and subtitle bar. Type your
  text first if you want, then tap a template; each one is a normal editable title with keyframes.
- **CC** opens the captions sheet. Type a caption, set where it starts and how long it lasts (the buttons step by one
  frame or one second; the next caption starts where the last one ended), and tap Add. Or import a `.srt` or `.vtt`
  subtitle file (it can start at the project start or at the playhead). Pick a style: Classic, Bold, Pop, Impact,
  Karaoke, Word pop, Typewriter or Bounce, and text and highlight colours. "Restyle" changes every caption at once.
  Nothing is sent anywhere: the app has no network access.
- **☺** opens the Stickers tab; tap a sticker to add it on an overlay lane.

## Markers and beats

The flag icon opens:

- **Add or remove a marker** at the playhead.
- **Find beats in the selected clip**: detects the rhythm of its audio and drops beat markers on the ruler.
  Works on music with a clear pulse; speech or ambience may report "no clear beat".
- **Cut to beat**: ends the selected base clip and the following ones on the nearest beats.
- **Clear detected beats** and **Snap to markers** (on/off).

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

## Missing media and recovery

If a file was moved or its permission was lost, a banner appears in the editor and the affected clips are
hatched. Tap it to **Relink** each file to a replacement (the app warns if the replacement is shorter or has a
different frame rate or colour space). Everything else stays editable.

Each save keeps a `project.json.bak`. If a project fails to load, use **Recover** in the hub.

## Known limits

- Several recent features are covered by automated tests but have had little time on a real device:
  captions and animated styles, photos and stickers, beat detection, text templates, relink flow and
  HDR export. Report anything odd with the steps you used.
- Slow motion repeats frames (no blending). Audio speed change is varispeed.
- Animated GIF and WebP use their first frame.
- Reverse playback of long-GOP 4K footage is slow.
- Beat detection only reads loudness, not pitch.
- Inserting (shifting later clips) works on the base track only; other tracks overwrite.
- Dragging stickers and templates onto the timeline is not available yet (tap them); dragging media assets is.

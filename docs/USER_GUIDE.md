# ultimateVE user guide

A short guide to the screens, the toolbar icons and the main gestures. ultimateVE is a lightweight
LumaFusion-style video editor for Android. Some features are new and have only been exercised by
automated tests so far; see [Known limits](#known-limits).

## Contents

1. [Project hub](#project-hub)
2. [Editor layout](#editor-layout)
3. [Toolbar icons](#toolbar-icons)
4. [Timeline: tracks, gestures and drops](#timeline-tracks-gestures-and-drops)
5. [Inspector](#inspector)
6. [Titles, captions, stickers and templates](#titles-captions-stickers-and-templates)
7. [Markers and beats](#markers-and-beats)
8. [Colour spaces and HDR](#colour-spaces-and-hdr)
9. [Exporting](#exporting)
10. [Missing media and recovery](#missing-media-and-recovery)
11. [Known limits](#known-limits)

## Project hub

The first screen lists your projects with their size, frame rate, colour space and last change.

- **New project**: pick a name, a resolution (grouped by 16:9, 9:16, 1:1 and 4:5), a frame rate and a
  project colour space. The project colour space is the working and output space; every clip is
  converted to it individually.
- **Import** (top right): imports a `project.json` file that was exported from another device.
- **⋮ menu on a project**: rename, clone, delete and export the project file.
- Projects that cannot be read are listed with **Recover** (from the `.bak` of the last good save) and
  **Delete**. After a crash the hub offers to reopen the project you had open.

Projects live in the app's private storage. Your media is never copied: projects only reference the files
you imported.

## Editor layout

From top to bottom:

1. **Top bar**: back, project name, undo, redo and export.
2. **Preview**: the current frame, with the timecode, previous / play-pause / next and a fit button.
3. **Toolbar**: the tools below (scrolls sideways when it does not fit).
4. **Timeline**: ruler, tracks, playhead.

On wide screens (tablet, foldable open) the media and inspector panels sit beside the preview.

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
| ✂ | Split at playhead | Cuts the selected clip in two. |
| 🗑 | Delete | Deletes the selected clip. On the base track the gap closes and overlays over the removed part are trimmed or removed; on other tracks a gap is left. |
| →← | Close gap before clip | Slides an overlay or audio clip back to the end of the previous one. Disabled on the base track, which does it automatically. |
| T | Add a title | Adds a text title at the playhead. |
| CC | Auto captions | Transcribes the selected clip on the device and creates caption clips. First use downloads a speech model. |
| ☺ | Add a sticker | Opens the sticker picker (built-in shapes and emoji). |
| Tt | Text templates | Adds an animated text template (lower third, pop title, slide-in headline, subtitle bar). |
| ⇄ | Crossfade | Adds a crossfade between the selected clip and the next one. |
| ≡ (sliders) | Adjust clip | Opens the inspector for the selected clip. |
| ▭ (canvas) | Canvas format | Changes aspect ratio and resolution of the project. |
| ⚑ | Markers and beats | Marker and beat tools, see [Markers and beats](#markers-and-beats). |
| ◫ (safe zone) | Safe zones | Shows TikTok, Reels or Shorts safe areas over the preview. |
| ◈ (layers) | Add track | Adds a video track (above the others) or an audio track. |
| – | Remove selected track | Removes the selected track if it is empty and not the last of its kind. |
| ▲ / ▼ | Move lane up / down | Reorders the selected overlay lane. The base track never moves. |

If an icon looks different on your device, its description always matches the table.

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
- **Tt** opens templates: lower third, pop title, slide-in headline and subtitle bar. Type your text first if
  you want, then apply; each template is a normal editable title with keyframes.
- **CC** creates captions from speech. Pick a language (or auto), a model (Fast or Balanced; downloaded once and
  then offline) and a style: Classic, Bold, Pop, Impact, Karaoke, Word pop, Typewriter or Bounce. "Restyle"
  changes the style of every caption at once.
- **☺** adds stickers on an overlay lane.

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
  auto captions and animated styles, photos and stickers, beat detection, text templates, relink flow and
  HDR export. Report anything odd with the steps you used.
- Slow motion repeats frames (no blending). Audio speed change is varispeed.
- Animated GIF and WebP use their first frame.
- Reverse playback of long-GOP 4K footage is slow.
- Beat detection only reads loudness, not pitch.
- Inserting (shifting later clips) works on the base track only; other tracks overwrite.

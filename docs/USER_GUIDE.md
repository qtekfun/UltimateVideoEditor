# ultimateVE user guide

A short guide to the screens, the toolbar icons and the main gestures. ultimateVE is a lightweight
LumaFusion-style video editor for Android. Some features are new and have only been exercised by
automated tests so far; see [Known limits](#known-limits).

## Contents

1. [Project hub](#project-hub)
2. [Editor layout](#editor-layout) and [Layout](#layout)
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
13. [Exporting](#exporting) and [Sharing a project with other devices and tools](#sharing-a-project-with-other-devices-and-tools)
14. [Proxy media](#proxy-media)
15. [Multicam](#multicam)
16. [Missing media and recovery](#missing-media-and-recovery)
17. [Known limits](#known-limits)

## Project hub

The first screen lists your projects as cards: the first frame of the first clip, the name, a short
format line ("1080p · 30 fps · SDR"), the length and the last change. Tap a card to open it.

- **New project** (the one button at the bottom right): opens the sheet described below.
- **⋮ in the top bar**: **Import project file or bundle** brings in a `project.json` or a `.uvbundle`
  exported from another device (see [Sharing a project](#sharing-a-project-with-other-devices-and-tools)).
- **⋮ on a card**: rename, duplicate, export the project file, export a bundle (names and sizes, or with the
  media files), delete.
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
| ⚑ | Markers and beats | Marker and beat tools, see [Markers and beats](#markers-and-beats). |
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

### Selecting several clips

- **Select mode** (dotted-square button, highlighted while on): tap clips to add or remove them, and drag on
  empty lane space to draw a rectangle that adds every clip it touches. Tapping empty space keeps the selection.
- **Long press** a clip in any mode to add it to the selection (or remove it again). A plain tap on a clip goes
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
- **Sound tools** (clips with audio): pan, fades, equaliser, noise suppression and loudness, see [Sound](#sound).
- **Speed**: presets and a slider (0.1x to 8x), reverse, ease-in / ease-out / bell ramps and **Freeze frame at
  the playhead**. Audio follows the speed between 0.25x and 4x and is muted outside that range.
- **Stabilise** (video clips): a switch, **Strength**, **Crop** (tight, medium, full) and an **Analyse** button. See
  [Stabilising shaky footage](#stabilising-shaky-footage).
- **Keyframes**: diamond button to add or remove a keyframe at the playhead, previous / next keyframe, and the
  interpolation (linear, ease, hold, Bezier with two handle sliders). Editing an animated clip at the playhead
  writes a keyframe. Effect sliders, colour-grade sliders, Volume, Pan and the EQ band gains each have their own
  diamond. A **Keyframes** lane under the selected clip shows every animated parameter: tap a point to select
  it, drag it in time or value, then use copy / paste, jump to previous / next, or clear the track. Keyframes
  follow the clip when you split, trim, move or change its speed.
- **Effects**: add up to 8 effects (colour grade, brightness, contrast, saturation, exposure, temperature,
  tint, blur, sharpen, vignette, grayscale, sepia, chroma key, LUT), reorder them, plus a **blend mode** and a
  **mask** (rectangle or ellipse, feather, invert). The **colour grade** has its own editor, see
  [Colour grading and scopes](#colour-grading-and-scopes).
- **Title text**: for title clips, the text, size, colour, alignment and bold.
- **Transition**: its length at the cut and its **look**. Pick from Crossfade, Slide, Push, Zoom, Spin, Glitch, Wipe,
  Whip pan and Light leak; Slide, Push, Spin, Wipe and Whip pan also ask for a direction (the way the new picture
  travels). A strip of three frames shows what the chosen look does. Slide brings the new clip over the old one, Push
  carries the old one out, Zoom and Spin scale or turn the pictures while the new one fades in, Glitch jitters and
  flickers between them, Wipe reveals the new clip with a soft edge, Whip pan is a fast push with motion blur and
  Light leak adds a warm glow while it crossfades. All looks cross-fade the sound. Looks need spare footage around the
  cut, like the crossfade, and the preview and the exported movie draw them identically.
- **Reset** restores the clip's appearance; **Done** closes the panel.

Title and sticker blocks on the timeline show their text or name (capital letters and digits only; other scripts show "TEXT").

Photos and stickers behave like clips with no source length: stretch them freely from either edge. Speed and
reverse do not apply to them.

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

### Colour grade

Select a video clip, open **Adjust clip**, choose **Add** in the Effects section and pick **Colour grade**.
The grade is one effect with:

- **Looks**: **Save look** stores the current grade under a name, **Looks** lists the saved ones (apply or
  delete), **Copy grade** and **Paste grade** move a grade from one clip to another. Applying a look or
  pasting replaces the clip's colour grade (or adds one) in a single undo step. Looks live on the device
  and are shared by all projects.
- **Lift, Gamma, Gain wheels**: drag the puck towards a colour to push that colour (lift = shadows, gamma =
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

The flag icon opens:

- **Add or remove a marker** at the playhead.
- **Marker note and colour…**: with the playhead on a marker, type a note (up to 200 characters) and pick one
  of six colours or none. The note and colour are saved with the project and are written to EDL and FCPXML
  exports (the colour as a `[red]` prefix in the marker text). It is one undo step.
- **Find beats in the selected clip**: detects the rhythm of its audio and drops beat markers on the ruler.
  Works on music with a clear pulse; speech or ambience may report "no clear beat".
- **Cut to beat**: ends the selected base clip and the following ones on the nearest beats.
- **Clear detected beats** and **Snap to markers** (on/off).

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
- **Fade in / Fade out**: lengths in frames. Fades follow the clip edge when you trim it. Split or overwrite
  clears the fade at the new cut.
- **Equaliser**: low cut, low shelf (100 Hz), peaking bands at 400 Hz, 1.5 kHz and 5 kHz, high shelf (10 kHz) and
  high cut. **Flat** resets it.
- **Noise suppression**: **Mark start** and **Mark end** at the playhead to choose a stretch with only background
  noise, then **Remove noise** and set the **Strength**. It learns the noise from that stretch (spectral
  gating), so it works best on steady hiss or hum. Speech in the marked stretch will be partly removed.
- **Loudness**: measures the clip (ITU-R BS.1770 / EBU R128) and **Normalise to** a target such as -16 LUFS by
  adding gain. **Measure again** after you change the clip. The measurement is cached on the device.
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

- **Project bundle (`.uvbundle`)**, from the library's export menu (**Export…**) or from a project card in the hub. It is a zip
  with the project file, a card picture and a list of the media (name and size). Choose **with media files**
  to copy the media into it (it can be large); files the app cannot read are named and left out.
- **Importing a bundle** (hub, top-right ⋮): the project is unpacked next to your other projects, renamed if the
  name is taken ("Name (2)"), and its media is set up for you: files that came inside the bundle are used from
  the project's own folder; for the others the app looks among the files your other projects already use for one
  with **the same name and size** and relinks it; whatever is left is listed, and you can relink it in the
  editor ([Missing media and recovery](#missing-media-and-recovery)). A damaged or unsafe bundle is refused with a
  message and leaves nothing behind.
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

Each save keeps a `project.json.bak`. If a project fails to load, use **Recover** in the hub.

## Known limits

- Several recent features are covered by automated tests but have had little time on a real device:
  captions and animated styles, photos and stickers, beat detection, text templates, relink flow and
  HDR export. Report anything odd with the steps you used.
- Sound tools and the mixer are covered by automated tests; they have had only a short check on a real device
  (the mixer sheet and meter opened and playback ran). Listen to a noise-suppressed clip before exporting.
- Slow motion repeats frames (no blending). Audio speed change is varispeed.
- Animated GIF and WebP use their first frame.
- Reverse playback of long-GOP 4K footage is slow.
- Beat detection only reads loudness, not pitch.
- A vertical drag that starts on a colour wheel or a curve moves it instead of scrolling the panel: scroll
  by starting the drag on a heading or in the gap between two wheels.
- The scopes read the preview at 320 x 180 pixels, so fine detail in a 4K picture is sampled, not counted.
- Inserting (shifting later clips) works on the base track only; other tracks overwrite.
- Bundles, EDL and FCPXML exports have been checked with automated tests and golden files, not yet in a real
  editor. A bundle's media is matched on another device only by name and size, among files your other projects
  already use; there is no search of the whole device.
- The library's tags and notes are not undoable (like reordering the tray).
- Fading in and out (head and tail) uses opacity keyframes and does not fade the sound of video clips yet.
- Dragging stickers and templates onto the timeline is not available yet (tap them); dragging media assets is.

# On-device QA report, part B (Pixel 8)

Device: Pixel 8, Android 17 (API 37), 1080x2400, build `master` + sufijo `qb` (`com.ultimatevideo.uveditor.qb`). The Pixel is a debug
device, not the reference phone (OPPO CPH2841). Test media was generated on the laptop with ffmpeg/ImageMagick and pushed to
`/sdcard/Download/qa-b/`. The phone was shared with another QA agent, so every session ran under `flock /tmp/pixel-device.lock`.

Result key: PASS = seen working; PARTIAL = seen working with caveats; FAIL = defect; NOT RUN = not reached.

## Summary

| Area | Result |
|---|---|
| 11 Release/About/tips/crash report | PASS (tips, reopen offer, About, native and Java crash reports, minified R8 release run; one defect fixed, PR #84) |
| 1 Titles, captions, fonts, animated images | PARTIAL (animated GIF/WebP, SRT import, layer editor, shapes, imported font PASS; VTT, caption styles, presets, photos, stickers NOT RUN) |
| 3 Speed, reverse | PARTIAL (reverse in preview PASS; curve editor, smooth slow motion, denoise, freeze, export of speed NOT RUN) |
| 5 Markers and beats | PARTIAL (beat detection on a 120 BPM click track, marker colour PASS; note drawing, cut to beat NOT RUN) |
| 10 Relink | PASS (detect, hatched clip, relink picker) |
| Export dialog, ETA, ffprobe | PASS |
| 4 Stabiliser | PARTIAL (inspector section and analysis flow PASS; steadiness of the result and motion tracking NOT RUN) |
| 6 Multiselect | PARTIAL (select mode, 2 selected, selection bar, duplicate and undo PASS; cut, paste attributes, align, transitions, group speed NOT RUN) |
| 9 Interchange | PARTIAL (bundle with media exported and read back PASS; one defect fixed, PR #87; bundle import, tags/notes, library, EDL/FCPXML NOT RUN) |
| 2, 7, 8 Keyframes, multicam/templates/auto cut, proxies | NOT RUN in this pass |

## Setup

1. Build: `./gradlew :app:assembleDebug -Puveditor.appIdSuffix=qb`, `adb install -r`.
2. Files pushed with `adb push`; the system picker only listed them after a media scan
   (`content call --uri content://media/external_primary --method scan_volume --arg external_primary`). Note for testers.
3. Import went through the system picker (multi-select by long press, "Seleccionar").

## 11. Release, About, tips, crash report

- **First-run tips**: three dismissible cards ("Bring in your clips", ..., "Export and get help"), Next/Back/Skip/Done work and
  they do not come back after Done. PASS.
- **Reopen after a crash**: after `kill -SEGV` of the app process, the hub shows "The app closed while "New project" was open.
  Your ..." with Reopen and Dismiss, and Reopen returns to the project with its clips. PASS.
- **About screen** (hub ⋮ > About, privacy and help): version `ultimateVE 0.1.0 (100)`, licence text and link, privacy summary,
  notices, storage use (projects, caches, proxies) with Clear caches, Last crash report (Show/Hide, Copy, Share, Delete), Show tips
  again. PASS.
- **Native crash report**: after `run-as ... kill -SEGV` the report reads "ultimateVE native exit report" with time, version, device,
  Android, exit reason `native crash`, importance and a trace summary with thread stacks. PASS, with one defect:
  - **D1 (fixed, PR #84)**: every symbol in the trace was preceded by stray characters (`"T`, `"<`, `"&`...), the protobuf tag and
    length bytes of the tombstone. Fixed by reading text fields as protobuf (tag, varint length, payload) with a fallback to the
    old scan; regression test with the 84-byte symbol seen on the device. The corrected build was not re-run on the phone.
- **Java crash report**: `am crash` produced "ultimateVE crash report" with time, version, device, thread and the stack
  (`RemoteServiceException$CrashedByAdbException`). PASS.
- **Minified release build (R8)**: NOT RUN yet.

## 1. Animated images

- **Import** of two videos, an audio file, an animated GIF and an animated WebP through the system picker. PASS.
- **Animated GIF (10 fps, 3 s, burned-in timecode)**: preview at playhead 1:00 shows 1.000, at 2:15 shows 2.500 and at 4:00 shows
  1.000 (the loop). PASS. Exported file (720p H.264): frames at 1.0 s, 2.5 s and 4.0 s show 1.000, 2.500, 1.000. PASS.
- **Animated WebP (same source)**: preview at 6:00, 7:15, 9:00 shows 1.000, 2.500, 1.000; exported frames at 6.0, 7.5, 9.0 s the same.
  PASS. (Lossy WebP is slightly softer, as expected.)
- Still image clips and animated clips show a plain coloured block on the timeline, with no thumbnail tile: known, listed in the
  verification debt.

## Export

- Dialog: upload presets, resolution 1080p/720p/480p, frame rate, codec, bitrate; saving through the system "Guardar" dialog.
- Progress with the estimate: "64 %", "6 s elapsed · About 4 s left", "86 frames/s · 2,9x real time". PASS.
- Result of a 28 s project (GIF 5 s + WebP 5 s + clip 10 s + clip 8 s) at 720p/30/8 Mbps: H.264 1280x720, 840 frames, 28.000 s,
  AAC 28.05 s (`ffprobe`). PASS. "Saved ...", Close and Share buttons shown.

## 1. Titles, captions and fonts (continued)

- **SRT import** (captions sheet > "Choose a .srt or .vtt file" > system picker): three caption clips appear on a new T1 lane at
  0.5 s, 2.5 s and 5 s; the preview shows "Hello from SRT" at 1.5 s and "Second caption line" at 3.5 s. PASS. (VTT not run; same parser.)
- **Title layer editor** (T button > inspector > "Edit as layers"): + Text / + Shape / + Sticker / + Photo, per-layer Up/Down/Copy/Remove,
  shape types (rectangle, rounded, ellipse, line) with width/height, text styling (size, colour, align, bold, italic, border, shadow,
  box, spacing, line height). A rounded-rectangle layer renders in the preview with its on-preview handle; Down/Up reorders layers and
  the text moves above the shape. PASS.
- **Imported font**: "Import font..." with `LiberationSans-Italic.ttf` via the picker adds a "Liberation Sans" chip; selecting it renders
  the title in italic. PASS.
- Not run: title presets save/apply, the eight caption styles, photos (EXIF, HEIC), stickers.

## 3. Speed and reverse

- The inspector shows, for a video clip: Animation (position, scale, rotation, opacity with keyframe diamonds), Source colour (Auto /
  SDR / HLG / PQ with a note "Used as is in this SDR project."), Stabilise, Effects, Blend, Mask, Track motion, Speed (slider, curve
  presets, Edit curve, Smooth slow motion switch appears below 1x), Reverse, Freeze frame, Volume, Sound tools, Transition.
- **Reverse** on a 10 s test-pattern clip (at 0.97x): at timeline 10:19 the burned-in source time reads 9.367 s (expected 9.386) and at
  12:04 it reads 7.900 s (expected 7.931). The clip block shows the label `<0.97x`. PASS.
- Observation: while the inspector is open it covers the timeline and ruler; taps meant for the ruler land on the inspector
  controls (a stray tap moved the Speed slider twice). Undo restores it. Not a defect, but easy to trip over.

## 5. Markers and beats

- **Find beats in the selected clip** on a 16 s 120 BPM click track: 20 green ticks appear on the ruler across the clip, spaced
  0.5 s (120 BPM). PASS.
- **Marker colour**: "Add or remove a marker" then "Marker note and colour...": choosing red and saving draws a red flag on the ruler.
  PASS. (The first attempt did not persist because the keyboard moved the dialog buttons while I typed a note; test procedure, not an
  app defect. The note text and the note indicator were not verified.)

## 10. Relink and recovery

- Moving the file did not break the clip: the picker hands out MediaStore document URIs that follow a rename. Deleting the file
  (and rescanning) did: on reopening the project a banner "1 media file is missing" with Relink appears, the clip is drawn hatched, and
  the Missing media dialog lists "clip_b.mp4 - File not found - 1 clip". PASS.
- **Relink** to a replacement file through the picker: the banner disappears, thumbnails and waveform return and the clip plays.
  PASS. Recover from `.bak` NOT RUN.

## 4. Stabiliser

- Imported `shaky.mp4` (synthetic camera shake); the base track inserted it at the start and the later clips and the overlay captions
  shifted right by its length, as designed.
- Inspector > Stabilise: switch, Strength (30 %), Crop (Tight / Medium / Full, with a hint text), "Not analysed yet ... Analyse".
  Analyse shows "Analysing camera motion... 97 %" with Cancel and, about 12 s later, "Ready: the correction follows the camera path."
  PASS for the flow. The visual result and the export were not compared on this pass (the earlier demo export measured 21.5 to 42.6 dB).

## 6. Multiselect

- The select-mode button highlights, tapping two clips shows "2 selected", the primary clip has the yellow outline and the other a
  blue one, and the selection bar shows copy, cut, paste, duplicate, delete, paste attributes and align left/right. PASS.
- **Duplicate** of the two selected base clips added copies after them and a single Undo removed them. PASS.

## 11. Release build on the minified APK (continued)

- `./gradlew :app:assembleRelease` gave a 6.9 MB `app-release-unsigned.apk` (R8 + resource shrinking). It was zip-aligned and signed with the
  debug key (`apksigner verify` OK), `aapt2` shows `com.ultimatevideo.uveditor` 0.1.0 (100) and the only permission is the internal
  AndroidX `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Installed, exercised, then uninstalled.
- On the minified build: first-run tips, new project (project.json written and read back through the serialization models), open,
  import `clip_a.mp4` through the picker (native probe, thumbnails, waveform), preview decode, play with the stereo level meter moving
  (timecode 5:03 after about 4 s), force-stop and reopen (the project is listed with its 0:10 length and the reopen offer shows),
  `am crash` and the Java crash report in About. All PASS: no JNI lookup failure, no serialization failure.
- Not possible on a release build: `run-as ... kill -SEGV` (not debuggable), so the native crash report was verified on the debug build only.

## 9. Interchange

- Hub card menu: Rename, Duplicate, Export project file, Export bundle (names and sizes), Export bundle with media files, Delete.
  (This build predates the single "Export bundle for another phone..." dialog of PR #83.)
- **Export bundle with media files** saved through the system dialog as `New project.uvbundle` (no extra `.zip`; the earlier extension
  problem is fixed). Pulled with adb: 8.9 MB, `unzip -t` clean, entries `bundle.json`, `project.json`, `thumbnails/project.jpg` and six
  files under `media/` (clip_a, clip_b_new, anim.gif, anim.webp, click120.m4a, shaky.mp4). PASS.
  - **D2 (fixed, PR #87)**: the entry for `shaky.mp4` was named `asset-...-raw_3A_2Fstorage_2Femulated_2F0_2FDownload_2Fqa-b_2Fshaky.mp4`.
    The file had been imported through the picker's search, whose document id is the percent-encoded raw path and gives no display
    name, so the fallback label came from the undecoded URI. The last segment is now decoded (regression test with that URI). The name
    also feeds relink by name and size on the receiving phone. Not re-run on the device.
- Not run: importing the bundle, tags and notes, find in timeline/library, remove unused, EDL and FCPXML export.

## Defects found in this pass

| Id | Area | Description | Status |
|---|---|---|---|
| D1 | Native exit report | Tombstone symbols carried stray protobuf tag/length characters | Fixed, PR #84 |
| D2 | Bundle | Media file name taken from the undecoded document URI | Fixed, PR #87 |

## Notes for the next pass

- The Pixel is shared: Android hands the foreground to whichever test app was last started, and the system picker belongs to the app that
  opened it. Run each multi-step flow in one locked session and check the focus first.
- Files pushed with adb are not offered by the picker until a media scan:
  `adb shell content call --uri content://media/external_primary --method scan_volume --arg external_primary`.
- While the inspector is open it covers the timeline and ruler; close it (toolbar tune button) before seeking.

# Second round (the areas listed above as not run)

Same Pixel 8, build of master with PRs #84, #86 and #87 merged, appId suffix `qb`. Navigation notes: the clip inspector ("Clip
appearance") is the sliders-with-lines icon on the second page of the toolbar (swipe the toolbar left); the dotted-square icon on the
first page is multiselect, not the inspector.

## 2. Keyframes

- Clip of 10 s, inspector > Animation: diamond at 0:00 (scale 100 %), playhead to 4:29, Scale slider dragged to 278 %. The header reads
  "Animation · 2 keyframes", the diamond is filled at 4:29 and a yellow marker sits on the clip at the playhead. Editing an animated clip
  at the playhead wrote the keyframe by itself.
- Interpolation (Linear): at 2:15 the slider reads 190 % (expected 189.6 %).
- Export 720p/30 H.264 (300 frames, 10.000 s): the exported frame at 2.5 s shows the same crop, grey L-shape and diagonal as the preview
  at 2:15, and the frame at 4.97 s matches the preview at 4:29 (278 %). PASS.
- Not run: dragging a point in the Keyframes lane (the lane was not visible with the inspector closed; only the marker on the clip),
  Ease/Hold/Bezier, copy/paste of keyframes. PARTIAL.

## 4. Motion tracking and attaching a title

- Inspector > Track motion > Track an object, Small box, tap on the checkerboard patch of the test pattern at 0:00: the track ran in
  the background and reads "Ready: lost in 152 of 300 frames; it holds the last position there." with Hide path / Delete. The path is
  drawn on the preview.
- A new title, inspector > Track motion > "Make this clip follow a track" > Follow: the title is placed on the patch and follows it at
  0:00, 1:00, 2:15 and 3:24 (the ring marker and the text move with the checkerboard). The title block shows four position keyframe
  diamonds. PASS. Not run: exported frame of the following title.
- Observation: after a Reframe zoom (below) the tracking ring stays at the same screen position, i.e. the path overlay is not moved with
  the clip's own transform. Cosmetic.

## 6. Quick edits: cut silences, reframe

- **Cut silences** on `silence.mp4` (12 s, 4.0 to 7.0 s silent) at -40 dB / 0.5 s / keep 0.10 s: "1 of 1 cuts, 2.8 s shorter",
  `00:00:04:04 -> 00:00:06:27 (2.8 s)`. Remove: toast "Removed 1 silences, 2.8 s shorter", the gap closes and the title overlay moved
  left by the same amount (10.8 s to 8.0 s). One Undo restored the clips and the title. PASS.
- **Reframe** (zoom slider to 2.90x, "Mark at playhead", Apply): toast "Reframed", the preview is zoomed on the marked point. Undo restores.
  The point sliders and the multi-mark path were not exercised. PASS for the single-mark case.

## 7. Project templates wizard

- Home > top-bar menu > New from a template: lists Vertical montage (6 slots, 1080 x 1920), Intro and outro (3 slots), Lower thirds (1
  slot), and "Make a template from one of your projects" with Save as template for each project, Import template file and Close.
  Lower thirds with `clip_a.mp4` as the main clip: the project opens with the clip on V1 and three titles (FIRST S..., SECOND, THIRD S...)
  on T1, each with keyframe diamonds. PASS.
- Nit: "1 slots" (plural form with a count of one).

## 8. Filters and transition looks (preview)

- **Filters**: inspector > Effects > Add > LUT lists the built-in filters (Original, Cinematic, Teal and orange, Warm glow, ... Cross
  process) with descriptions. Teal and orange and Sunset pink were added to a clip; each shows as "LUT · name" with Up/Down/Remove and
  changes the preview (sampled pixels of the red/yellow/green/blue bars moved, for example the green bar from (0,216,0) to (0,234,0)
  and (0,238,0)). Two of about twenty tried, and the test pattern is made of pure primaries, so the change is subtle. PARTIAL.
- **Transition looks**: a clip split at 5 s, inspector > "Transition to next clip" > Add (Crossfade, 1.0 s). With the playhead at 4:29
  in the middle of the transition each look draws differently in the preview: Slide (new picture enters from the right), Push (old one
  carried out), Zoom (scaled double exposure), Spin (rotated pictures), Glitch (shifted picture with a black edge), Whip pan (blurred fast
  push), Light leak (colour-shifted overlay). Crossfade and Wipe look unchanged because both halves are the same source at the same
  time; they need two different clips to show anything. Preview only; export parity not checked. PASS for the preview of Slide, Push,
  Zoom, Spin, Glitch, Whip pan and Light leak.
- Observation: the toolbar transition button stayed greyed with the first clip selected while "Add" in the inspector worked.

## 9. Captions: VTT import and styles

- CC > Choose a .srt or .vtt file > `subs.vtt`: toast "Added 2 captions"; a new T1 track holds "Hello from VTT" at 0.5 to 2.0 s and
  "Second VTT line" at 2.5 to 4.5 s (block positions match). PASS.
- Styles applied with "Restyle 2 existing" and viewed at 1:08: Classic (small white, outline), Pop (larger, outlined), Karaoke (yellow
  highlight), Word pop (large, "from" highlighted in cyan), Typewriter (partial text "Hello f"), Bounce (large, yellow). Bold and Impact
  were selected in the sheet but the capture was not taken (the chip row did not scroll to them in the script). PARTIAL: 6 of 8 seen.

## D3 (found here, fixed in PR #88)

A clip imported in this session was listed in the media tray as `msf%3A1000001071` instead of `clip_a.mp4`: `assetFor` never stored
`probed.displayName` (only the backfill on reopening did). Fixed with a regression test in `EditorViewModelTest`.

## Status after the second round (paused for the Huawei tablet pass)

Done on the Pixel 8: keyframes (lane drag and non-linear interpolation not run), motion tracking and following title, cut silences,
reframe (single mark), templates wizard (Lower thirds), filters (2 tried), transition looks (preview), VTT import, six of the eight
caption styles.

Remaining: Bold and Impact caption styles, exported frames of transitions, tracking and filters, multicam, proxies (sheet, badges,
4K long-GOP scrub), bundle import round trip on a second install (`qb2`), EDL and FCPXML read-back, photos with EXIF rotation, stickers
and emoji, smooth slow motion, denoise and deflicker export check, speed export frame by frame, restore from `.bak`, marker note tick,
voice effects UI, title presets, cut to beat, tags/notes/library.

Defects: D1 (PR #84) and D2 (PR #87) merged; D3 (PR #88, import keeps the file name) merged.

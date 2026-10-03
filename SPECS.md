# ultimateVE — Technical Specifications

Status: v1, reconciled with the code after phases 1-4 and 6 · Date: 2026-10-03

## 1. Build and environment

- App name: ultimateVE · Application ID / namespace: `com.ultimatevideo.uveditor`
- Native library: `uveditor_engine` (C++20, CMake, NDK). JNI package prefix `com.ultimatevideo.uveditor.engine`.
- Gradle Kotlin DSL (`*.gradle.kts`) with a version catalog (`gradle/libs.versions.toml`).
- minSdk 33 · targetSdk 36 · compileSdk 37 (current AndroidX requires it) · ABIs: `arm64-v8a` (plus `x86_64` optional for emulator UI work).
- Dev host: Fedora Linux, no Android Studio. Command-line only: JDK 21, `$ANDROID_HOME=~/Android/Sdk`,
  `sdkmanager`, `adb`. Needed extras: NDK, CMake, Gradle wrapper.
- Test device: physical, wireless adb (more than one adb transport may be listed; use `adb -s <serial>`).

## 2. Module layout

```
app/                        Android app module (Compose UI, MVI, navigation)
  src/main/kotlin/com/ultimatevideo/uveditor/
    ui/hub/        Project hub (list, new project dialog, clone/rename/delete/import/export)
    ui/editor/     Editor: EditorViewModel (MVI), timeline host, inspector, transport, EditorPreview/EditorAudio
    ui/preview/    Preview SurfaceView host
    ui/export/     Export dialog and ViewModel
    mvi/           Contracts: State, Intent, Effect, base ViewModel
    domain/        Pure Kotlin: FrameIndex, FrameRate, Clip, Track, Timeline, edit operations, EditHistory (undo/redo)
    data/          ProjectRepository/Store, JSON DTOs (`data/model`), TimelineMapper (DTO <-> domain), media probing
    engine/        Kotlin facades over JNI, no logic: preview/, audio/, timeline/, export/
  src/main/cpp/    C++ engine (CMake root; each area adds its sources through `cmake/<area>.cmake`)
    core/          Error codes and shared helpers
    decode/        AMediaExtractor/AMediaCodec wrappers (VideoDecoder, GpuFrame)
    cache/         Frame cache (LRU with playback-window aware eviction)
    render/        EGL context, GLES 3.2 compositor (PreviewEngine, GlPipeline), shaders, layout/colour math
    timeline_view/ SurfaceView renderer for the timeline canvas (blocks, waveforms, thumbnails, playhead)
    thumbnail/     Thumbnail tile generation, atlas, disk store
    audio/         Oboe playback, mixer, master clock, PCM decoder, waveform extractor
    encode/        Export: offline render loop, MediaCodec encoder, AAC, muxer
    jni/           JNI bindings only
    tests/         Host-built tests (no GoogleTest; see section 8)
```

`domain/` has no Android dependencies so clip operations are plain JVM unit tests.
Splitting into Gradle modules (`:domain`, `:engine`, `:app`) is allowed once boundaries stabilise.

## 3. Time base

- All timeline coordinates are integer `FrameIndex` (Long) in project frame units. Never float seconds.
- Project fps is a rational (`fpsNum`/`fpsDen`, e.g. 60000/1001). The JSON example's `59.94` float
  is replaced with this rational to avoid drift (schema version bump rules in section 4).
- Conversions to presentation time use integer math: `ptsUs = frame * 1_000_000 * fpsDen / fpsNum`
  (128-bit/rounded safely). Audio uses sample frames at the sample rate; the audio device is the master clock during playback.
- **Source ranges (`sourceInFrame`/`sourceOutFrame`) are in project frames**, not in the asset's native
  frames: a clip plays its source at 1x on the project's frame grid, so its timeline duration equals its
  source duration and no per-frame rate conversion is needed. Assets are opened with the project fps as
  their frame rate (`fpsOverride`), which is how decoders map project frames to media time. (The first draft
  of this spec had native frames; the code and the saved files use project frames.) `nativeFpsNum/Den` in
  the media library records the real rate of the file for information.
- Playback clock: while playing, the **audio device is the master clock** (`AudioPlaybackEngine.positionFrame()`,
  latency compensated, in project frames). The editor's playhead follows it; the preview follows the
  playhead without seeking (section 5.3). Without audio output the system monotonic clock is the fallback.

## 4. Project data model (`project.json`)

Based on the study document, with these additions: rational fps, track kinds for audio/title,
per-clip gain, transitions, and `schemaVersion`. Unknown fields must be preserved on load/save.

```json
{
  "version": 1,
  "id": "uuid-v4",
  "name": "Tech_Review_01",
  "settings": {
    "width": 3840, "height": 2160,
    "fpsNum": 60000, "fpsDen": 1001,
    "colorSpace": "Rec709-SDR"
  },
  "mediaLibrary": [
    { "id": "asset-1", "uri": "content://media/external/video/media/105",
      "durationFrames": 1800, "nativeFpsNum": 60000, "nativeFpsDen": 1001,
      "colorSpace": "Rec2020-HLG", "hasVideo": true, "hasAudio": true }
  ],
  "tracks": [
    { "id": "track-v1", "type": "video", "order": 0, "clips": [
      { "id": "clip-101", "assetId": "asset-1",
        "timelineStartFrame": 0, "sourceInFrame": 120, "sourceOutFrame": 420,
        "transform": { "scale": [1.0, 1.0], "rotation": 0.0, "position": [0, 0], "opacity": 1.0 },
        "gainDb": 0.0, "colorOverride": null } ] }
  ],
  "transitions": [
    { "id": "transition-1", "type": "crossfade", "fromClipId": "clip-101", "toClipId": "clip-102",
      "durationFrames": 30 }
  ]
}
```

- Track types: `video`, `audio`, `title`. A clip on a `title` track has no `assetId` and carries a
  `title` object instead (a clip with `title` on any other track is invalid):
  `{ "text", "sizeFraction", "color": "#AARRGGBB", "alignment": "left|center|right", "bold" }`.
  `sizeFraction` is the font size as a fraction of the project height (0.01 to 0.5), so a title looks the
  same at any resolution. Its placement uses the clip's `transform`; the text block is centred on the
  canvas before it. A title's source range is `[0, duration)`.
- A transition joins two clips of one track where `fromClipId` ends exactly where `toClipId` starts. It
  is centred on the cut: `preFrames = durationFrames / 2` before it and the rest after it, so the clips do
  not move and the project length does not change. It consumes media beyond the trim points: the
  outgoing clip keeps playing for `postFrames` past its out point and the incoming one is already playing
  `preFrames` before its in point. A transition is rejected (or dropped when an edit makes it
  impossible) if the clips are not adjacent, it is shorter than 2 frames, it reaches past either clip,
  the incoming clip has no media before its in point, the outgoing one has none after its out point, or
  it overlaps the other transition of the same clip. Splitting the outgoing clip hands the transition to the
  right half (the one now at the cut).
- `hasVideo`/`hasAudio` on an asset default to true so older files stay valid; the editor re-probes each
  medium once on load and corrects a wrong flag (a file without an audio track must never reach the mixer).
- Clip appearance (`transform`, `gainDb`) lives in the domain `Clip` and is saved as is:
  - The clip's frame is first fitted ("contain") into the project canvas, then `scale` (`[x, y]`,
    each > 0) is applied about its centre, then `rotation` (degrees, **clockwise**), then the centre is
    moved by `position` (`[x, y]` in **project canvas pixels**, +x right, +y down, from the canvas centre).
  - `opacity` (0 to 1, default 1) is an addition to the study schema; files without it load as opaque.
  - `gainDb` is limited to -96..+24 dB. Values outside these ranges are rejected as invalid edits
    (`EditError.InvalidAppearance`) and, when found in a file, reported as a corrupt project.
  - Splits and overwrites copy the appearance to every part of the original clip.
- Video tracks stack in display order: the first video track is the top layer.
- Clip duration = `sourceOutFrame - sourceInFrame` (out exclusive). Clips on one track never overlap
  except via explicit transitions.
- Persistence: one directory per project in app-private `filesDir/projects/<id>/` (`project.json`,
  waveform cache, thumbnails). Writes are atomic (temp file + rename). Import/export of a project via SAF.
- Media referenced by `content://` URI with persisted read permission; never copied by default.
  Missing media must open the project in a "relink" state, not crash (see 4.1).

### 4.1 Missing media, relink and recovery
- **Detection.** When a project opens, every library file is probed once (`MediaImporter.verify`). A file that
  cannot be read is recorded with its reason (`UNREADABLE`, `PERMISSION_LOST`, `UNSUPPORTED`) in
  `EditorState.missingMedia`. Probing also re-takes the persisted read permission where Android allows; a file that
  is readable but whose permission cannot be persisted (a `file://` URI, the permission limit) is not "missing".
  Assets keep the file's `displayName` so a lost file can still be named.
- **While media is missing** its clips stay on the timeline and stay editable. The native canvas tints and hatches them
  (clip flag bit 2 of the timeline snapshot, no version bump); the preview, the mixer and the waveform/thumbnail
  workers skip the file (`EditorState.playableAssets`); export refuses with a message that names the clips by lane and time.
- **Relink** (`RelinkAsset`) replaces the asset's URI after `RelinkCheck`: rejected when the file is already another asset,
  is a picture where media was expected (or the reverse), lacks video the asset had, or lacks audio for an audio-only asset;
  accepted with warnings for no audio, a shorter file than the part in use, another frame rate, another colour space.
  The asset's duration/fps/flags are re-read from the new file, its waveform and thumbnails are invalidated, and its native
  key is replaced. It is a saved library change, not an undo step.
- **Permissions.** At startup, when 80% of Android's 512 persisted permissions are in use, those no project refers to are released.
- **Project files.** `project.json` is written through `project.json.tmp` and renamed. Each save of a parsable file first copies
  it to `project.json.bak` (the last good save). A corrupt or missing `project.json` is listed in the hub as unreadable;
  **Recover** restores the newest parsable of `.tmp` (an interrupted write) then `.bak` and keeps the damaged file as
  `project.json.corrupt`; **Delete** removes it.
- **Autosave failures** show a banner with Retry, are retried quietly three times, and refuse to leave the editor until the
  user retries or chooses to leave without saving.
- **Session marker.** The open project id is stored (synchronously) when the editor opens and cleared when the user leaves it;
  if it is still set at the next start the hub offers to reopen that project.

## 5. Architecture

### 5.1 UI layer (Kotlin / Compose, MVI)
- Each screen: immutable `State`, sealed `Intent`, one-shot `Effect`. ViewModel exposes
  `StateFlow<State>` and `Flow<Effect>`; reducers are pure functions.
- The timeline state (tracks, clips, selection, playhead, zoom) lives in the ViewModel and is decoupled
  from rendering. Each state change publishes an immutable snapshot to the engine.
- Adaptive layout through window size classes. Compose never draws timeline clips.

### 5.2 Engine boundary (JNI)
- Kotlin facade `EngineClient` is the only caller of native methods. JNI calls are cheap and non-blocking;
  heavy work happens on engine threads. Native errors are returned as typed results or thrown as
  Kotlin exceptions with a code; **no silent failures**.
- Timeline snapshot flows Kotlin → native as a compact immutable structure (primitive arrays or a
  direct `ByteBuffer`), not JSON.

### 5.3 Rendering
- Two `SurfaceView`s hosted via `AndroidView`: **preview** (GLES compositor) and **timeline canvas**
  (C++/GLES draws clip blocks, waveforms, playhead, thumbnails, handles).
- Touch gestures (scroll, pinch-zoom, drag, trim) on the timeline surface are handled by a Kotlin
  `View` and forwarded as intents/commands; hit-testing against the snapshot is native.
- Dedicated render thread per surface with its own EGL context (shared context for textures).
- **Multilayer compositor.** The preview shows a *scene*: the project canvas (project resolution) plus layers
  bottom to top, each `{asset or title, frame, transform, opacity}`. While paused Kotlin sends it with
  `PreviewEngine.setScene` (JNI `nativeSetScene`) on every playhead change, and while playing with `playScene`
  (below); the render thread draws it once every layer's frame is in
  the cache (never a half-updated composite). Each layer is a quad: `layerQuadMap()` in
  `render/layout_math.h` turns canvas size, displayed frame size and `LayerTransform` into a 2x3 map to clip
  space (host-tested; semantics in section 4), the vertex shader applies it, the fragment shader does colour
  conversion and outputs `alpha = opacity`, and layers are blended source-over. The canvas is letterboxed
  into the surface and layers are clipped to it.
- **Playback (`playScene`).** Seeking on every tick is what made 4K60 playback stutter, so during playback the
  editor does not drive frames. `PreviewEngine.playScene(canvas, layers, fps)` (JNI `nativePlayScene`) installs
  the scene like `setScene` and then advances **every layer in step on a native monotonic clock**
  (`CLOCK_MONOTONIC`) at the project frame rate, each from its own start frame and never past its
  `endFrame` (the clip's out point, so trimmed-away media is never shown). Between calls the native side runs
  by itself, with the decoders' look-behind/ahead windows as look-ahead. `EditorPreview.follow()` is called on
  every playhead tick (the playhead *is* the heard audio frame) and calls `playScene` again only when
  (a) the composition changed (a layer's clip, source offset, out point or transform, or the set of layers
  that finished opening), or (b) the heard frame differs from where the native clock should be by more than
  `DRIFT_THRESHOLD_FRAMES` (2 frames; `PreviewAnchor`, JVM-tested). Where no clip is under the playhead (a gap
  or the end) the native clock is paused and the last frame stays up. `setScene` (paused, scrubbing, editing)
  stops native playback. Known limit: the first frames of an incoming clip at a cut are not pre-rolled, so a
  cut may stall for a frame or two while its decoder seeks.
- **Offscreen use (export).** `GlPipeline::drawScene(layers, canvasW, canvasH, targetW, targetH)` composites into
  whatever framebuffer is bound, without binding, swapping or waiting. To render a frame for the encoder:
  bind an FBO of the export size, build `LayerDraw`s from cached frames (`GpuFrame` + `ColorMode` + container
  turns + `LayerTransform`), call `drawScene` with the canvas size equal to the target size, then read or
  hand the FBO to the encoder. `GlPipeline::draw()` and `PreviewEngine::seek/play` keep their old
  single-asset behaviour.
- Target frame pacing: Choreographer/`ASurfaceTransaction` vsync; 60 fps minimum, 120 fps where supported.

### 5.4 Decode and cache
- `AMediaExtractor` + `AMediaCodec` (H.264, HEVC) decoding to `AHardwareBuffer`-backed surfaces
  (`AImageReader` with GPU-usage flags); GL imports via `EGLImage`/`GL_OES_EGL_image_external`.
- Frame cache in native memory: LRU ring keyed by (asset, source frame), strict byte budget
  (default 1 GB on reference-class devices, configurable), look-ahead ±30–60 frames around the playhead.
  Eviction is LRU **outside the playback window** first: frames ahead of the playhead are older in
  recency than frames already played, so plain LRU evicts what is about to be shown and forces the
  decoder to seek and re-decode (measured: ~8 seeks/s and ~80% wasted decodes at 4K60).
- Per-frame GL work avoids stalls: EGLImages/textures are created once per `AHardwareBuffer` and
  cached; decoder buffers are returned with a native release fence (`AImage_deleteAsync`) instead of
  `glFinish`; frames are presented with `eglPresentationTimeANDROID` for even pacing.
- Decoders run on worker threads; never on the UI or render thread. Every open video asset owns one
  hardware decoder. `DecoderLimits` reads the device's `maxSupportedInstances` for H.264 and HEVC (capped at 4
  for the preview) and `DecoderPlanner` decides which assets to keep open: the layers under the playhead,
  topmost first, win; least recently shown assets are closed to make room; layers that do not fit are
  left out of the preview and the user is told. The cache budget is shared equally between open assets
  and each asset's look-behind/ahead window is sized to its share, so several layers never evict each other.

### 5.5 Colour
- Project colour space: `Rec709-SDR` (default) or `Rec2020-HLG` (HDR), chosen in New project and in the
  editor's project dialog (`ProjectColorSpace`). A source is SDR, HLG or PQ, from the decoder's transfer.
- A render target holds an `OutputSpace` (`Sdr709` or `Hlg2020`): the preview surface, or the encoder
  surface of an export. Each layer's `ColorMode` is derived from (source, target) by `colorModeFor`
  (`render/color_space.h`): SDR→SDR and HLG→HLG sample as is; HLG/PQ→SDR tone-map (203 nit diffuse white,
  soft shoulder, Rec.2020→Rec.709); SDR→HLG decodes gamma 2.4, maps primaries to Rec.2020, places SDR white
  at 203 nit (HLG signal ≈ 0.7512, BT.2408), inverts the HLG OOTF and applies the HLG OETF; PQ→HLG
  re-encodes display light clipped at 1000 nit. The GLSL in `render/shaders.h` mirrors the CPU reference in
  `render/color_math.h`, tested on the host (`uv_hdr_host_tests`). No 3D LUTs.
- In an HLG target layers are blended in HLG signal space (not linear light), effect intermediates are
  RGBA16F, the blend-mode destination snapshot is RGB10_A2, and titles are SDR graphics placed at reference
  white. Effects run after the colour conversion, on HLG signal values.
- Preview: HLG is requested only when the project is HDR and the display reports HLG (`Display.isHdr` on
  API 33, `Display.Mode.supportedHdrTypes` from 34). The native context then prefers an RGB10_A2 config and
  tags the window surface BT.2020 HLG (`EGL_EXT_gl_colorspace_bt2020_hlg`, else
  `ANativeWindow_setBuffersDataSpace`); the window uses `COLOR_MODE_HDR`. If the device refuses, the preview
  falls back to SDR (HLG sources tone-mapped) and the editor says so. The engine reports what it granted.

### 5.6 Audio
- Oboe (AAudio backend) low-latency output; mixer sums audio tracks and the embedded audio of video
  clips with per-clip gain (`gainDb`, -96..+24), clipped to ±1. The device clock, compensated with the
  hardware timestamp, is exposed as the master clock in project frames.
- **Device lifecycle.** The output stream is open only while it is needed: `EditorAudio` opens it on play,
  closes it 1.5 s after a pause (so a quick pause/resume does not reopen the device) and immediately when
  the app goes to the background (`ON_STOP`). The mixer state (assets, snapshot, position) survives a closed
  stream and scrubbing while paused never needs the device.
- Waveform worker decodes PCM from clips in the background, computes min/max peak pyramids at several
  zoom levels, and caches them on disk (`waveforms/<assetId>.peaks`). Timeline renderer reads the cache.

### 5.7 Titles and transitions
- **One render plan.** `domain/RenderPlan.kt` turns the timeline into `RenderClip`s: every clip with its
  transitions folded in (extended range, `crossfadeInFrames` for the incoming clip's fade, `crossfadeOutFrames`
  for the outgoing clip's audio fade, a decoder `lane`, and `layer` counted from the top over video and title
  tracks). The preview (`previewRequestsAt`), the exporter (`buildExportPlan`) and the audio mixer
  (`audioSnapshotOf`) all start from it, so a transition looks and sounds the same everywhere. The native
  compositor only knows layers with an opacity: it has no notion of a transition.
- **Crossfade curve.** Over `d` frames the incoming clip's opacity at frame `k` is `(k + 0.5) / d` (never exactly
  0 or 1 inside the fade, symmetric); the outgoing clip stays opaque underneath, so the picture is
  `out * (1 - p) + in * p`. Audio uses equal-power gains `cos` and `sin` of the same progress, so both fades
  cover exactly the same samples. Within a layer the later-starting clip is drawn on top. The curve is
  implemented in `CrossfadeCurve` (Kotlin) and `core/crossfade_math.h` (C++) and checked with shared vectors.
- **Decoders.** Two cuts of the same file that show together (the two sides of a transition) need two decoders:
  the preview opens the file under `assetKey + lane * 2^20`, the exporter keys decoders by `layer * 2 + lane`.
  Both respect the device's decoder limit; in the preview the top layer wins when it is exceeded.
- **Titles.** Text is rasterised on the Kotlin side (`AndroidTitleRasterizer`: `StaticLayout` + `Canvas` into an
  ARGB bitmap cropped to the text block, at project canvas pixels) and uploaded as an RGBA texture keyed by
  title appearance (`TitleKeyCache`, LRU). The compositor draws it 1:1 (no "contain" fit) with the clip's
  transform and treats it as premultiplied alpha. The exporter rasterises with the same code and the same
  canvas, which is what makes preview and export identical; a title is therefore drawn at project resolution
  and scaled by the export size, not re-rasterised at the export resolution.
- **Wire formats.** Timeline snapshot version 2 appends the transitions (for the canvas markers; version 3 adds
  keyframe markers, see 5.10); audio snapshot version 2 has 64-byte clips with `fadeInFrames`/`fadeOutFrames`.

### 5.8 Undo/redo
- `EditHistory` in `domain/`: a bounded stack of immutable timeline snapshots (exact restore), driven by
  the editor ViewModel. Drags are previewed provisionally and enter the history only when released; clip
  appearance edits (inspector, preview gestures) are one undo step per gesture (`SetAppearance`).

### 5.9 Export
- Offline render loop: frame N → compositor (`drawScene` into the encoder's input surface) → MediaCodec
  encoder (H.264 or HEVC) → `AMediaMuxer` to MP4; audio from the offline mixer, encoded to AAC. PTS are exact
  integer grid values. The AAC encoder delay (2048 samples) is compensated, so the first 42.7 ms of the mix are
  not heard. Progress, cancel and share through the export ViewModel; a failed or cancelled export deletes
  the partial file. Output is written through SAF (`CreateDocument`).
- HDR export: for an HLG project on a device whose encoder lists HEVC Main10 with HLG, the export dialog
  offers HDR (default on). The job renders into a ten-bit recordable encoder surface tagged BT.2020 HLG and
  configures HEVC Main10 with `COLOR_STANDARD_BT2020`, `COLOR_TRANSFER_HLG` and limited range. Without
  support the project exports as SDR (HLG clips tone-mapped) with a notice; a native refusal
  (`UnsupportedFormat`, e.g. no ten-bit surface) is reported with a hint to export as SDR. The JNI codec
  argument carries the HDR flag as bit 0x100.
- FFmpeg (static, NDK) is an optional later fallback for formats not supported by MediaCodec. Deferred, see
  DECISIONS.md.

### 5.10 Automatic captions
- Speech recognition runs on the device with whisper.cpp (MIT, git submodule `app/src/main/cpp/third_party/whisper.cpp`,
  pinned to `v1.9.4`, built statically into `uveditor_engine` through `cmake/captions.cmake`). CPU only, up to 4 threads;
  ggml is built for `armv8.2-a+dotprod+fp16`, which the arm64 phones this app targets (API 33+) have.
- No model ships in the APK. The user picks "Fast" (tiny, 31 MB) or "Balanced" (base, 57 MB), both multilingual
  `q5_1` ggml files downloaded once over HTTPS from the whisper.cpp model repository into
  `filesDir/caption-models`, verified by size and SHA-256 against the values pinned in
  `engine/captions/CaptionTypes.kt` and written atomically (`.part`, then rename). This is the only use of the
  `INTERNET` permission; captions work offline afterwards.
- Pipeline (`captions/caption_pipeline.cpp`): `AndroidPcmDecoder` seeks to the clip's source range -> stereo float ->
  `Mono16kConverter` (exact-integer box average to 16 kHz mono) -> `whisper_full` with token timestamps and
  `max_len = 1` / `split_on_word`, so each result is one word. Progress (decode 0-10 %, recognition 10-100 %) and
  cancellation flow through one JNI callback object; a cancelled coroutine aborts the run. At most 30 minutes
  (about 115 MB of PCM) per run.
- Words are in source milliseconds. `domain/captions/CaptionPlanner` maps them to timeline frames with the
  clip's `timelineStart - sourceIn` (source ranges are project frames, see section 4), groups them into cues by
  character limit, duration, pauses and sentence ends, and times each cue so cues never overlap or leave the clip.
- Captions are ordinary title clips (`outline = true` so they read over any footage) on a new title track
  placed on top, added by one `AddCaptions` command, so one Undo removes them all and they stay editable.
  Four static styles (Classic, Bold, Pop, Impact) set size, colour, position and chunking; the animated styles
  (Karaoke, Word pop, Typewriter, Bounce) are in section 5.15.

### 5.11 Keyframes, canvas formats and upload presets
- **Keyframes.** A clip (video or title) may carry `keyframes`: poses (position, scale, rotation, opacity) at
  integer *clip* frames (0 is the clip's first frame, so they travel with the clip when it moves). Before the first
  keyframe the first pose holds, after the last the last one holds; between two the earlier keyframe's mode
  decides: `linear`, `ease` (smoothstep) or `hold`. Rotation interpolates in degrees without wrapping, so
  720° is two full turns. Gain is not animated (the mixer takes one gain per clip).
  ```json
  "keyframes": [
    { "frame": 0,  "transform": { "scale": [1, 1], "rotation": 0, "position": [0, 0], "opacity": 1 }, "interpolation": "ease" },
    { "frame": 45, "transform": { "scale": [1.4, 1.4], "rotation": 0, "position": [0, -120], "opacity": 1 } }
  ]
  ```
  With keyframes the clip's `transform` is only the pose it returns to when the last keyframe is removed.
- **One evaluator.** `domain/Keyframes.evaluate` is used by `RenderClip.appearanceAt` (pose with the crossfade
  folded into the opacity), so the preview and the export share the plan; the exporter evaluates the same
  formulas natively (`core/keyframe_math.h`) because it renders from a flat clip list. Both are checked against
  the same vectors (`KeyframesTest`, `export_host_tests.cpp`).
- **Edits keep animations intact.** Split, trim and overwrite re-base keyframes onto the surviving range
  (`Keyframes.cropped`): when keyframes outside the range shaped the motion, a keyframe holding the pose is
  added at the new start or end. Linear and hold segments are exact; an ease that is cut in the middle keeps its
  mode over the remaining span. Editing an animated clip at the playhead writes a keyframe there (one undo
  step, via `EditCommand.Batch`); a gain-only edit never adds one.
- **Canvas formats.** The New project dialog groups presets by shape: 16:9 (YouTube), 9:16 (TikTok, Shorts,
  Reels), 1:1 and 4:5 (Instagram feed). The editor can switch the canvas later (`ChangeCanvas`): positions of clips
  and keyframes are scaled by the width and height ratios, the fitted size follows the canvas, and the undo
  history restarts because earlier steps were made on another canvas.
- **Safe zones.** An overlay on the preview (off by default) dims the edges that TikTok, Instagram Reels and
  YouTube Shorts cover with their own interface. The margins are approximate fractions of a 9:16 canvas
  (`SafeZonePlatform`), not official values.
- **Upload presets.** The export dialog fills size, rate, codec and bitrate for an upload destination
  (`ExportPresets`): the largest size not above the preset's, the project's own rate unless faster than the
  preset wants, the preset's codec, and its bitrate snapped to the nearest choice. A preset made for another
  shape than the project's shows a hint instead of reshaping the movie.
- **Wire format.** Timeline snapshot version 3 appends keyframe markers (`clipKey`, clip frame) after the
  transitions, drawn as small diamonds on the clip header; version 2 snapshots still parse. The export request
  carries per-clip keyframes in three flat arrays (see `NativeExport.nativeStart`).

### 5.12 Effects, masks and blend modes

- **Model.** A video or title clip carries `fx` (`ClipFx`): an ordered list of effects (at most 8), a blend mode
  and an optional mask. Audio clips cannot have any (the timeline invariants and `TimelineOps` refuse). Effect
  values are static for the whole clip; keyframes only animate the pose and opacity (a future step can key the
  values the same way).
- **Effects** (`EffectType`, order and values are the wire format): brightness, contrast, saturation, exposure,
  temperature, tint, gaussian blur, sharpen, vignette, grayscale, sepia, chroma key (key colour, similarity,
  smoothness, spill). Colour effects run on display-referred straight RGB (after the HLG to SDR conversion).
  Blur radius is a fraction of 2 % of the layer height, so it looks the same at any resolution.
- **Mask** (`ClipMask`): rectangle or ellipse, in fractions of the layer's own box (so it follows the clip's
  transform), with centre, size, feather (soft edge, half-width) and invert.
- **Blend modes:** normal, add, multiply, screen, overlay.
- **JSON** (per clip, all optional so older projects load): `"effects": [{"id", "type", "values": [...]}]`,
  `"blendMode": "normal"`, `"mask": {"shape", "centerX", "centerY", "width", "height", "feather", "invert"}`.
- **Rendering** (`GlPipeline::drawScene`). A layer with effects is first drawn at the size it covers on the
  canvas (more when scaled up, at most 4096 px) into an RGBA8 intermediate, premultiplied, then one fullscreen
  pass per effect ping-pongs between two intermediates (blur is two passes, one per axis, 33 taps with a spacing
  that covers three sigma). Intermediates exist only while a layer uses effects. The result is composited with
  the layer's transform like any other. The mask is a coverage factor evaluated in the composite pass. Blend
  modes other than normal read a snapshot of the target below the layer (`glCopyTexSubImage2D` of the
  letterboxed viewport) and mix in the shader, which works on any target (window surface or export FBO).
  `render/effect_math.h` is the CPU reference of every formula (change shader and reference together); the host
  tests pin them.
- **One description for preview and export.** `RenderClip.fx` carries the look through the render plan;
  `FxWire` (`engine/fx`) encodes one blob per layer into a flat `double` array that both `nativeSetScene` /
  `nativePlayScene` and `nativeStart` receive and `core/layer_fx.h` parses. An empty array means no layer has
  anything. The export reuses `drawScene`, so a frame exports as it previews.
- **Editor.** The inspector lists effects (add, up, down, remove, sliders; chroma key picks its colour from
  swatches), a blend mode row and mask controls. Sliders are shown live and make one undo step on release;
  everything else is one step. A small badge on the clip header marks clips that have a look (timeline snapshot
  clip flags bit 1).

### 5.13 Retiming: speed, ramps, reverse and freeze frames
- **Model.** A clip keeps its source range `[sourceIn, sourceOut)` (source frames, in project-frame units like
  everywhere in the editor). Three optional fields change how the range plays:
  `timelineFrames` (the clip's length on the timeline when it is not the range's length, so the speed is
  `range / timelineFrames`), `reverse`, and `speedRamp` (keys `{frame, weightPermille}` in clip frames; the speed
  at a frame is the clip's average times the weight, linear between keys and held outside them). A ramp only
  shapes the speed: the average still comes from range and length, so a ramp never moves the clip's ends. A
  **freeze frame** is a one-frame range held for a longer length (`sourceOut = sourceIn + 1`,
  `timelineFrames = N`): there is no separate model for it. Titles are never retimed.
  ```json
  { "id": "c1", "assetId": "a1", "timelineStartFrame": 0, "sourceInFrame": 20, "sourceOutFrame": 120,
    "timelineFrames": 50, "reverse": false, "speedRamp": [ { "frame": 0, "weightPermille": 400 }, { "frame": 49, "weightPermille": 1600 } ] }
  ```
- **One mapping.** `domain/Retime.kt` (`ClipRetime`) maps a clip frame `t` (any integer: a transition extends clips
  past their ends) to the source frame shown: `floor(t * range / length)` in exact integer arithmetic without a
  ramp, the ramp's integral (`position`) with one, `sourceOut - 1 - offset` when reversed, and the single frame
  of a freeze. The preview (per tick), the exporter (a per-clip table) and the mixer (knots) all start from it.
- **Edits.** The constant speed is limited to 0.1x–8x (`SpeedLimits`). `setSpeed` keeps the range and changes the
  length (rounded to a whole frame, at least 1), stretching keyframes and the ramp with it; getting longer
  must not run into the next clip unless it ripples (the editor ripples). Split, trim and overwrite go through
  `Clip.cropped(from, to)`, which re-derives the range, length, ramp and keyframes from the same mapping, so the two
  halves of a split meet exactly at the cut (when the cut falls inside a source frame held by slow motion both
  halves show that frame), and extending a trimmed clip continues its speed (bounded by the media length).
  `freezeFrame` splits the video clip at the playhead and inserts the still, moving later clips on the track.
- **Wire formats.** Timeline snapshot version 4 appends the retimed clips (`clipKey`, `sourceSpanFrames`, flags
  reverse/freeze) so the canvas can place waveforms and thumbnails and label the speed ("2x", "0.5x", "<" for
  reverse, "||" for a freeze); a ramp is drawn at its average speed. Audio snapshot version 3 keeps the clip
  table and appends the knots (`frame`, absolute `sourceFrame`) of retimed clips after it. The export request
  carries `sourceFrames` (one source frame per project frame) and a reverse flag per retimed clip.
- **Sound.** Audio follows the mapping with linear interpolation, so the pitch follows the speed (varispeed). A clip is
  muted outside 0.25x–4x (checked at every knot of a ramp) and a freeze frame is silent; time-stretching that keeps
  the pitch is future work. Reverse playback decodes a block of source below the playhead and reads it backwards.
- **Decoding.** A retimed clip's preview layer is re-anchored every tick, like an animated one. A reversed layer
  mirrors the decoder's window (frames behind the playhead are kept decoded instead of those ahead) so one pass
  over a GOP serves the next stretch; the exporter does the same with a window bounded by memory
  (`reverseWindowFrames`: about 8 frames at 4K, 32 at 1080p) and drops frames after the one drawn. Reverse
  playback of long-GOP footage therefore re-decodes a GOP every few frames, which is slow at 4K; fast forward
  (above 2x) is limited by decoder throughput.

### 5.14 Lane layout, drops and lane order

- **Layout.** Tracks are in display order (first is topmost). The timeline panel draws the lane stack bottom-anchored
  (`Layout::anchoredBottom`): the last lane rests on the panel bottom, the ruler stays on top, and the room between
  them is the 'add a lane' zone (`HitKind::AboveLanes`). A taller stack scrolls and opens scrolled to the bottom.
  The base is the lowest *video* lane; audio lanes sit below it.
- **Drop decision.** `domain/DropPlan.decide` maps (clip, requested start, target) to a `DropDecision` (command +
  `DropHint`). Targets: a lane, `AboveLanes` (new overlay lane), `Outside` (cancel; `HitKind::OUTSIDE`, the finger left
  the panel). On the base: start edge within `INSERT_RADIUS_FRAMES` (10) of a junction -> INSERT (`MoveClip`, ripple,
  overlays follow); past the end -> append; otherwise OVERWRITE (`LaneOps.overwriteMove`). Base clips only REORDER.
  On other lanes: overlapping clips -> OVERWRITE, free space -> MOVE. No insert on those lanes (deferred).
- **Indicator.** `EditorState.dropHint` is passed to the native canvas with `TimelineEngine.setDropHint` and drawn by
  `timeline_view/drop_hint.h` + the renderer: bar and arrow (insert), tinted range (overwrite), lane placeholder
  (new lane), wash (cancel).
- **Lane ops** (`domain/LaneOps`): `moveToNewLane`, `overwriteMove`, `moveTrack` (up/down among lanes of the same kind;
  the base never moves), each one undo step.

### 5.15 Animated captions

- **Data.** A caption is a title clip whose `TitleContent` also carries `words` (`TitleWord(text, startFrame, endFrame)` in
  clip frames, 0 = the clip's first frame), an `animation` (`NONE`, `KARAOKE`, `POP_IN`, `TYPEWRITER`) and a
  `highlightArgb`. In JSON they are the optional `words`, `animation` (`none|karaoke|pop_in|typewriter`) and `highlight`
  fields of the title, so older projects load unchanged. `CaptionPlanner` fills `CaptionCue.words` with each cue's words;
  split/trim/overwrite move the words with the clip through `Clip.cropped` (`TitleContent.shiftedBy`), and editing the
  text re-spaces the words evenly instead of leaving stale timing. Words that no longer appear in the text in order
  make the caption draw as a static title.
- **Looks.** `domain/captions/CaptionAnimator.lookAt(title, clipFrame)` gives a `TitleLook` (words/characters shown,
  active word, its size in percent) with integer maths only. Karaoke: all words show, the word being spoken is
  highlighted at 110 %. Word pop: words appear as they start, the newest is highlighted and settles 135 -> 118 -> 106 ->
  100 % in steps of 2 frames. Typewriter: letters appear across each word's span, the space before a word comes with its
  first letter. A look is a renderer detail (`TitleContent.look`), never stored.
- **Same pictures in preview and export.** The preview sets the look for the frame it shows
  (`previewRequestsAt` -> `CaptionAnimator.contentAt`) and keys its raster by (content without timing, look, canvas);
  a look change changes the key, which re-anchors the native clock like any animated clip. The exporter splits the clip
  into one `VideoClipSpec` per run of equal look (`ExportPlan.titleParts`; only the first run fades in with a
  transition, stretched to cover the whole fade), so no native change was needed. Both go through the same
  `AndroidTitleRasterizer`.
- **Rasterising.** Hidden words/letters are transparent spans, so the block keeps its size and the text does not move.
  The active word is drawn again, scaled around its own centre (`ScaledWord`), when it fits on one line; the bitmap margin
  grows with the pop size. Word spans colour the fill pass and the outline pass separately.
- **Entrance.** Bounce and scale-in are ordinary keyframes added when the caption is made
  (`CaptionEntrance.keyframes`: ease, overshoot to 115 %, settle on the normal pose; short clips keep the last step),
  so they are editable and travel with the clip.
- **Styles and restyle.** `CaptionStyle` gains `animation`, `highlightArgb` and `entrance`; Karaoke, Word pop,
  Typewriter and Bounce join the four static styles. `RestyleCaptions` puts every generated caption (id prefix
  `caption-`) in a style as one undo step: text, timing and timeline place stay; position, animation, entrance and
  colours follow the style (captions without word timing get evenly spaced words). The captions sheet shows a card per
  style, text/highlight colour swatches, and "Restyle N existing" (or opens alone to restyle when no clip is selected).

### 5.16 Still clips: photos and stickers

A still clip shows one picture for as long as it lasts. `Clip.still` is `PHOTO` or `STICKER`; the clip lives on a **video** track
(the base or an overlay), has no `title`, and has no media length of its own. Like a title its source range is only its length
(`sourceIn = 0`, `sourceOut = duration`; `Clip.cropped` normalises it), so it can be trimmed or stretched on either edge without a
limit, is never retimed (`hasMedia` is false: speed, reverse, ramp and freeze are refused) and needs no handle for a transition.
All timeline operations, the magnetic base and drops treat it as an ordinary clip.

- **Photo:** `assetId` is an image in the media library (`MediaAssetDto.isImage`, `hasVideo = hasAudio = false`; its `durationFrames`
  is only the default length, 5 s). Imported through the same SAF picker (`image/*`), probed by decoding the header only.
- **Sticker:** `assetId` is a built-in id (`shape:heart`, `emoji:🔥`, ...; ids are persisted, never renamed). No library entry.
  Shapes are drawn procedurally (`engine/still/StickerArt`, original artwork) and emoji with the system font: no bundled files.
- **JSON:** `ClipDto.still` is `"photo"` or `"sticker"`, absent otherwise; older projects load unchanged.
- **Render path:** there is no decoder. `engine/still/StillRasterizer` turns a still into the same premultiplied RGBA picture a title
  becomes (drawn 1:1, centred, then transformed), so the compositor, effects, blend modes, keyframes, HDR reference-white handling and
  the exporter (`drawScene`) need no change. A photo is decoded once with `ImageDecoder` (EXIF orientation applied, sRGB, reduced by
  a power-of-two sample size) and fitted *inside the canvas* (contain); a sticker is a square of 35 % of the canvas' shorter side.
  The preview decodes off the main thread and shows the layer when the texture is uploaded; `StillKeyCache` evicts by an estimated
  byte budget (192 MB, keys start at 1,000,000 so they never collide with title keys). The export plan rasterises each distinct still
  once and shares one key space with titles.
- **Timeline canvas:** a still's snapshot clip has no asset key, so no waveform or thumbnails are requested for it.

### 5.17 Markers, beat detection and text templates

**Markers.** `Timeline.markers` is a list of `Marker(id, frame, kind)` with `kind` `MANUAL` (placed at the playhead) or `BEAT` (found
by beat detection), sorted by frame with unique frames and ids (`MarkerOps`, checked by `invariantViolations`). They sit at absolute
project frames and do **not** move when clips are edited around them. `AddMarker`, `RemoveMarker` and `SetBeatMarkers` are undoable;
`SetBeatMarkers(beats, from, until)` replaces only the beats inside that frame range, so analysing a second clip keeps the first
clip's beats, and manual markers are never touched. JSON: `ProjectDto.markers` (`{id, frame, kind}`), absent in older projects.
**Snapping:** `Snap.extraTargets` carries marker frames (when the editor's "Snap to markers" is on) into move, trim and drop decisions.
**Canvas:** timeline snapshot version 5 appends `i32 markerCount` and per marker `i64 frame, i32 flags (bit0 = beat), i32 reserved`
(16 bytes each); version 4 still parses. The ruler draws a tall flagged tick for a manual marker (plus a faint line through the
lanes) and a short tick for a beat (beats closer than 3 dp are skipped when zoomed out).

**Beat detection** (`domain.beat.BeatDetector`, Kotlin, no new native code). Input is a loudness envelope built from the finest level of
the waveform peak cache (`PeaksFile.readWindow` reads only the asked window of `waveforms/<assetId>.peaks`), so no audio is decoded
twice and nothing runs unless the waveform has been extracted (otherwise the editor says "the waveform is still being prepared").
The envelope is resampled to 100 Hz, its log-compressed positive rise over a 200 ms mean is the onset curve, its autocorrelation over
60-180 BPM with a log-normal prior around 120 BPM picks the period (a confidence below 1.8 means "no clear beat"), the best phase of that
period is chosen and each beat is pulled to the strongest onset within 15 % of the period (the period is then followed from where the
beat really was). It reads amplitude only, so it suits music with a clear pulse; it may report a fast pulse at half tempo (beats still
land on real beats). The editor analyses the clip's source range plus 8 s each side (a short clip still shows the pulse); `BeatMapping`
then places the beats through the clip's own trim, speed, ramp and reverse (nearest clip frame, using `ClipRetime.sourceFrameAt`), and
skips titles, photos, stickers and freeze frames.

**Cut to beat** (`CutToBeat`, one undo step). For the selected base clip and every later base clip, in order: take the clip's current end
as the aim, choose the nearest marker after its start (a tie goes to the earlier one) among the markers it can reach (a clip with media can
only grow as far as its media; titles and stills freely), and trim its end there with `MagneticBase.trim`, so later base clips ripple and
overlays follow. A clip with no reachable marker is left alone. The first clip keeps its start; every cut between the clips lands on a marker.

**Text templates** (`TextTemplates`, `AddTextTemplate`, one undo step). A template is data: layers of kind `TEXT` (a title clip) or `BAR`
(a solid sticker clip, `shape:bar-dark` / `shape:bar-accent`, stretched to a canvas fraction using the sticker side of `StillFit`), each with
a resting pose in canvas fractions and `TemplateKey`s (seconds from the start or the end, offsets as canvas fractions, scale, opacity,
interpolation) that become ordinary keyframes in clip frames, so preview and export are identical for free. Text goes on a title lane and bars
on an overlay lane (never the base); a lane is reused only when it is free over the template's range, otherwise a new one is added, so
nothing is overwritten. Built in: Lower third, Pop title, Slide-in headline, Subtitle bar.

## 6. Timeline operations (specification for tests)

Free placement with magnetic snapping to clip edges and playhead. For each operation, tests must
cover collisions, gaps, and boundaries:

| Operation | Behaviour |
|---|---|
| Split | At playhead inside a clip; produces two adjacent clips with contiguous source ranges; no-op at edges |
| Move | Free drag, snaps to neighbours/playhead; rejects or resolves overlaps per mode |
| Overwrite | Placed clip replaces overlapped regions, trimming/splitting/removing existing clips |
| Ripple delete | Removes clip and shifts later clips on the same track left by its duration |
| Ripple append | Snaps clip to the end of the previous clip with no gap |
| Trim | Changes in/out points, bounded by source length and neighbours |
| Speed | Changes the length (range / speed) and stretches keyframes and ramp; fails on overlap unless it ripples |
| Reverse, ramp | Do not change place or length; a ramp's keys must lie inside the clip |
| Freeze frame | Splits the video clip at the playhead and inserts a one-frame still; later clips move by its length |

Invariants: sorted by `timelineStartFrame`, no overlaps on a track, durations > 0, all values integers.

### 6.1 Base track and overlays (LumaFusion model)

The **base track** is the lowest video track (the last video track in display order). It is the guide and
is **magnetic**: it is contiguous from frame 0, and no editor operation can open a gap in it
(`MagneticBase.baseViolations` must be empty after every op, and the randomized tests assert it). Every other
track (overlay video, audio, titles) is free-form and follows the base's time. Overlay edits never touch the base.
`ClipDeletion` (delete) and `MagneticBase` (insert, move, trim) are the base-aware operations; the plain
`TimelineOps` primitives stay free-form and are what overlays use. Commands: `DeleteClip`, `InsertBase`,
`MoveClip`, `TrimClip` (each one undo step).

| Edit on the base | Base | Overlays |
|---|---|---|
| Delete a clip | Gap closes, later clips shift left | Frames of the deleted range are removed: clips inside vanish, edge overlaps are trimmed, a spanning clip is cut in two and rejoined; later clips shift left |
| Insert / import at a frame | New clip goes at the nearest clip boundary (ties go to the end; past the end appends); later clips shift right | Overlays starting at or after that boundary shift right; overlays crossing it stay |
| Reorder (drag within the base) | The clip takes the slot its centre is over; the base is repacked from 0 | Overlays follow the footage they sit over: they are cut where the footage under them moves by different amounts and each piece travels with its footage |
| Trim an edge (shorten) | Clip keeps its start, followers shift left | Removed frames are removed from overlays like a deletion; later overlays shift by the same delta |
| Trim an edge (lengthen, needs source handle) | Clip keeps its start, followers shift right | Overlays at or after the trim point (the old end, or the clip start for the front edge) shift right |
| Split | Nothing moves | Nothing moves |

Dragging an overlay onto the base inserts it there (its old lane keeps a gap); a base clip cannot be dragged
to another track (`BaseClipCannotLeave`). A base that already has gaps (older projects) is repaired on the first
magnetic edit: gaps are closed like deletions. Deleting from an overlay removes the clip and leaves its gap.
Transitions that no longer hold after an edit are dropped.

Editor constants: snapping to clip edges, the playhead and frame 0 uses a **fixed 8-frame threshold**
(`EditorViewModel.SNAP_THRESHOLD_FRAMES`; the zoom is not exposed to Kotlin, so the threshold is not in
pixels). Dragging near the left or right edge of the timeline auto-scrolls it (up to 14 dp per frame inside
the outer 56 dp). The timeline pages to keep the playhead on screen (it jumps so the playhead sits 10% from
the left when it leaves the 10%–90% band).

## 7. Error handling

- Native code returns `Result`-style error codes with context (codec, URI, MediaStatus); JNI converts to
  typed Kotlin errors surfaced as MVI effects/state. No swallowed exceptions, no `catch {}`.
- Codec/IO failures during playback show an error state on the clip and keep the editor responsive.

## 8. Testing

- JVM unit tests for `domain/` (operations, undo, serialization round-trips, time math).
- Native unit tests are host-built executables without GoogleTest (`app/src/main/cpp/tests`, run with CMake +
  ctest, see CLAUDE.md): viewport, hit-testing, snapshot parsing, peaks, tile math and atlas LRU, audio core,
  export maths, colour and layout maths.
- Instrumented tests on device for JNI smoke tests, media probing and audio (`AudioPlaybackInstrumentedTest`).
- `scripts/av-drift-test.sh <serial> [minutes]` logs, every 5 s, the drift between the audio clock and the
  native preview clock over a long synthetic timeline (tag `UVSync`).
- Verification commands (CLI): `./gradlew :app:testDebugUnitTest`, `./gradlew :app:assembleDebug`,
  `./gradlew :app:connectedDebugAndroidTest`.

## 9. Roadmap specs: closing the gaps with LumaFusion

Derived from `docs/lumafusion-comparison.md`. Each work package is written so it can be handed to one
agent as is: scope, design, data model, UI, tests, acceptance criteria, dependencies and which files it
owns (to avoid conflicts when two packages run in parallel). Existing conventions apply to all of them:
integer frames only, MVI, domain in pure Kotlin with unit tests, preview and export through the same
`RenderPlan` / `drawScene` / offline mixer, optional JSON fields (old projects must load unchanged), one
undo step per user action, explicit typed errors, decisions logged in `DECISIONS.md`.

### 9.0 Queue and parallelism

Run at most two packages at a time. Pairs that do not share files:

| Wave | Package A | Package B |
|---|---|---|
| 1 | WP-C Colour tools and scopes | WP-S Multiselect and bulk edits |
| 2 | WP-A Audio tools | WP-T Multilayer titles and fonts |
| 3 | WP-K Generalised keyframes (needs WP-C and WP-A) | WP-X Stabiliser |
| 4 | WP-I Interchange and media library | WP-P Proxy media |
| 5 | WP-M Multicam (needs WP-A, WP-S, WP-P) | WP-R Release preparation |

Priority inside the queue follows the comparison: colour and audio first (largest "pro" gap), then bulk
editing and titles, then keyframes and stabilisation. WP-C absorbs the 3D LUT and per-clip colour work that
is already in flight; start it only after those PRs are merged.

Shared definition of done for every package: unit tests green, host native tests green where C++ changed,
CI green, `PLAN.md` boxes ticked only for what was verified, a "Not verified on the device" list in the PR,
`SPECS.md` section updated, `docs/USER_GUIDE.md` updated for anything the user can see.

### 9.1 WP-C Colour tools and scopes

**Goal:** a colourist workflow: see the signal (scopes) and shape it (wheels, curves, looks).

**Scopes (native, GLES 3.2).**
- Waveform (luma and RGB parade), vectorscope (Cb/Cr with skin-tone line) and histogram (luma + RGB).
- Computed from the last composed frame, downscaled to about 320x180 on the GPU, by drawing one point per
  sample into an accumulation texture (RGBA16F, additive blending) or with a compute shader for the
  histogram; no CPU readback. Updated at most 30 Hz and only while the scopes panel is visible, and paused
  during playback at 4K if the frame budget is exceeded (log it).
- Scale labels follow the project space: IRE/percent for SDR, nits or HLG signal percent for HLG.
- Drawn into its own `SurfaceView` panel (not Compose), toggled from the Colour section of the inspector and
  a toolbar button.

**Grade effect (`COLOR_GRADE`, one effect type in the existing chain).**
- Primary controls: lift / gamma / gain wheels with a master slider each, offset, contrast with pivot,
  saturation, vibrance, temperature and tint (existing sliders migrate into this effect).
- Curves: master + R/G/B, up to 8 points each, monotone cubic spline, baked on the CPU into a 256-entry 1D LUT
  (RGBA texture); wheels and matrix baked into a 3x3 + offset. The shader does matrix, 1D LUT, then the
  wheels in one pass.
- Secondary: hue vs hue, hue vs sat and luma qualifier (HSL key with softness) as a follow-up inside the same
  package if the primary part lands early; otherwise logged as deferred.
- Colour-space rule (documented in the PR): the grade runs on the clip's signal after conversion to the
  project space; for HLG projects the wheels operate on the HLG signal.
- Looks: save the current grade as a named look (JSON in app storage), apply from a list, copy grade from one
  clip and paste to others (works with WP-S multiselect later).

**Data model.** `Effect(type = COLOR_GRADE, values = [...])` with a versioned parameter layout and an optional
`curves` payload (`ClipFx.grade`), optional JSON fields only. Looks stored as `looks/<id>.json`.

**Tests.** CPU reference for the grade maths (wheels, curves spline, matrix) with documented vectors; host
tests for scope accumulation (known ramps and charts); JVM tests for the model, undo and look save/load.

**Acceptance.** A ramp in the preview shows a diagonal waveform; moving gain changes the waveform and the
picture identically in preview and export; a look applies in one undo step.

**Owns:** `render/` grade shader and scope passes, `engine/scopes/`, `ui/editor/ColorControls*.kt`,
`domain/` grade types. Avoid: audio, titles, timeline_view.

### 9.2 WP-S Multiselect and bulk edits

**Goal:** select several clips and act on them together, like LumaFusion.

- **Selection model:** `Selection(clipIds)` in the editor state; tap adds in "select mode" (toggle button) or
  long-press toggles; marquee selection by dragging on empty lane space; select all in lane and in time range
  (from playhead). The existing single selection stays the primary clip for the inspector.
- **Group operations (domain, atomic, one undo step each, all-or-nothing on collisions):** move (keeps relative
  offsets and lanes), delete (base follows the ClipDeletion rules per clip, in descending time order),
  duplicate, copy / cut / paste at the playhead (clipboard of clips with relative layout and attributes),
  paste attributes (transform, effects, audio, speed) onto the selection, set speed / volume / opacity for the
  group, align left or right edges, apply transition to many (including head and tail dissolves as in
  LumaFusion Android 2.5).
- **Base track rules:** a base selection must be contiguous to move as a group; otherwise only attribute
  operations apply (message explains why).
- **Native:** selection set passed in the timeline snapshot (extend flags into a bitset or id array,
  snapshot version bump), draw multiple outlines, marquee rectangle, hit-test for marquee; keep single
  selection behaviour identical.
- **UI:** a selection bar (count, copy, cut, delete, duplicate, paste attributes), no new top-level screens.

**Tests.** Group move rejects collisions without partial changes, magnetic-base interactions, copy/paste
round trips with transitions and keyframes, randomized invariant test extended to group ops, undo exactness.

**Acceptance.** Move three overlay clips together over a base clip; paste a clip's effects onto five clips
with one undo.

**Owns:** `domain/` selection and group ops, `ui/editor/` selection UI, `timeline_view/` selection drawing.
Avoid: render/, audio/.

### 9.3 WP-A Audio tools

**Goal:** a usable mixer for voice and music.

- **Per clip:** pan (-1..1, equal-power), fade in / fade out (frames, with draggable handles on the clip),
  EQ (low shelf, 3 peaking bands, high shelf and high/low-pass, biquads, Q and gain ranges documented),
  noise suppression (RNNoise or an equivalent permissively licensed model; log the choice and licence in
  `THIRD_PARTY_NOTICES.md`), loudness normalise to a target LUFS (BS.1770 measured offline and cached, applied
  as gain).
- **Per track:** volume, mute, solo, a simple bus compressor option; master limiter at -1 dBTP.
- **Auto-ducking:** pick a "voice" track and one or more "music" tracks; the voice envelope (from the waveform
  cache, with attack, release and threshold) produces a gain curve applied to the music tracks; amount in dB.
  Implemented as computed gain automation so preview and export share it, regenerated when the timeline changes
  (debounced), never destructive.
- **Meters:** stereo peak meters in the editor while playing.
- **Engine:** per-clip DSP chain in `audio/` at the stream sample rate, double-precision biquads, parameter
  smoothing to avoid zipper noise; offline mixer uses the same code; the master clock behaviour stays
  unchanged. Stereo, mono and dual-mono sources, downmix of more than two channels.
- **Data model:** `Clip.audio` optional block `{ pan, fadeInFrames, fadeOutFrames, eq[], denoise, targetLufs }`,
  `Track.audio { volumeDb, mute, solo, comp }`, `Timeline.ducking[]`; snapshot version bump for the audio
  engine.
- **UI:** an Audio section in the inspector, track header controls (mute/solo/volume) on audio lanes, a duck
  dialog.

**Tests.** Biquad frequency response vectors, pan law, fade shapes, LUFS vectors (EBU test signals),
ducking envelope, parity between realtime mixing and offline mixing on the same project (bit-exact within a
tolerance), host tests for the DSP.

**Acceptance.** A voice-over over music ducks the music by the chosen amount and recovers smoothly; export
audio equals the preview audio; a noisy clip is audibly cleaner (qualitative note in the PR).

**Owns:** `audio/`, `engine/audio/`, `domain/` audio types, `ui/editor/AudioControls*.kt`. Avoid: render/,
timeline selection code.

### 9.4 WP-T Multilayer titles and fonts

**Goal:** LumaFusion-style title editor: text + shapes + images in one title, custom fonts, presets.

- **Model:** a title is an ordered list of layers (top first in the editor, as in LumaFusion 5.5.3): `TEXT`
  (content, font, size, colour, alignment, bold/italic, letter spacing, line height, border, shadow,
  background box), `SHAPE` (rectangle, rounded rectangle, ellipse, line; fill, stroke, shadow, corner
  radius), `IMAGE` (asset or built-in sticker); each with its own offset, scale, rotation and opacity inside
  the title. The existing single-text `TitleContent` maps to a one-layer title (backwards compatible).
- **Rendering:** the group is rasterised into one texture by the existing title rasteriser (extended), cached
  by content hash; animated captions and keyframes keep working on the clip as a whole.
- **Fonts:** import `.ttf` / `.otf` through SAF into app storage, validate and register by family name, list in
  the editor with a preview; the project stores the font reference and falls back to the default font when a
  font is missing (banner like missing media). Licence reminder shown on import.
- **Presets:** save a title as a preset (`.uvtitle` JSON, shareable through SAF), list built-in presets (the
  current templates migrate to this format).
- **UI:** a title editor screen or sheet with a layer list (reorder, add, delete), on-preview handles for the
  selected layer, a style panel; in/out animation presets built on keyframes.

**Tests.** Layer ordering, hash stability, rasteriser output on JVM-friendly fakes where possible,
instrumented rasteriser test kept but not required for CI, JSON round trips, font fallback.

**Acceptance.** A lower third made of a rounded rectangle, a logo image and two text lines, saved as a
preset, applied to another project, identical in preview and export.

**Owns:** `domain/` title layers, `engine/still/` rasteriser, `ui/editor/title/`, `data/` fonts. Avoid: audio/,
render/ shaders.

### 9.5 WP-K Generalised keyframes (after WP-C and WP-A)

**Goal:** animate anything: effect parameters, grade values, audio level, pan and EQ gain.

- Replace the fixed pose keyframes with named parameter tracks `ParamTrack(paramId, keys[])` while keeping the
  current pose keyframes as the `pose.*` params (JSON migration reads old and writes the new form behind an
  optional field; old files load).
- Interpolation: linear, hold, ease, plus Bezier handles (a cubic per segment); integer-frame time, evaluation
  in Kotlin inside `RenderPlan` for video parameters, sample-accurate evaluation inside the mixer for audio.
- UI: a diamond on every animatable control, a keyframe lane under the selected clip showing the curve with
  draggable points, copy and paste keyframes, previous/next keyframe per parameter.

**Tests.** Interpolation vectors including Bezier, split/trim cropping of every track, migration round trips,
parity of video and audio evaluation at frame boundaries.

**Acceptance.** Fade a colour grade over 2 s and automate a music volume dip; identical in export.

**Owns:** `domain/` keyframes, `ui/editor/` keyframe lane, `engine/` evaluators. Depends on WP-C and WP-A for
the parameters it animates; coordinate on `RenderPlan`.

### 9.6 WP-X Stabiliser

**Goal:** steady handheld footage like LumaFusion's stabiliser.

- Analysis pass per asset and range: downscaled frames from a separate low-priority decoder (reuse the
  thumbnail decoder path), pyramidal Lucas-Kanade feature tracking with RANSAC for a similarity transform per
  frame, in C++ (no OpenCV dependency unless justified in `DECISIONS.md`).
- Path smoothing: Gaussian or L1-optimal camera path with a user strength; output per-frame correction
  (translation, rotation, scale) stored in a cache file `stab/<assetId>.<hash>`.
- Application: an effect stage in the layer pass applying the correction with a cropped, scaled warp; the
  crop level is a parameter ("tight", "medium", "full" with edge fill).
- UI: a Stabilise toggle with strength and crop in the inspector, progress and cancel, status when the
  analysis is stale (clip trimmed or asset changed).
- Out of scope: rolling-shutter correction.

**Tests.** Synthetic sequence with known jitter: smoothed path variance below a threshold; cache key
invalidation; host tests for the tracker and smoother.

**Acceptance.** On a handheld sample the picture is visibly steadier in preview and export, with the analysis
running in the background without UI jank.

**Owns:** `stabilise/` native, `jni/stabilise_jni.cpp`, `cmake/stabilise.cmake`, `engine/stabilise/`, inspector
section. Avoid: audio/, title code.

### 9.7 WP-I Interchange and media library

**Goal:** move work between devices and tools; find media quickly.

- **Project bundle:** export a zip with `project.json`, thumbnails and an optional media copy; import it and
  relink media automatically by name and size or through the relink flow.
- **EDL (CMX3600)** export for cuts of the base and overlay video/audio tracks.
- **FCPXML 1.9** export of the supported subset (clips, positions, trims, speed, basic transform, markers,
  titles as generators) with the unsupported features listed in the file as notes; test by importing the
  result in DaVinci Resolve or Final Cut when someone can (not blocking).
- **Media library:** a panel listing assets with thumbnail, duration, colour space, usage count, tags, search,
  filters (video/audio/image/unused), "find in timeline" and "find in library" (both directions), delete unused
  with confirmation; notes and colours on markers.

**Tests.** Bundle round trips on temp dirs, EDL and FCPXML golden files, library queries and usage counts.

**Acceptance.** A project exported on one device opens on another with media relinked; the exported EDL
re-imports with the same cut points in a third-party tool (manual note).

**Owns:** `data/interchange/`, `ui/library/`, hub import path. Avoid: render/, audio/.

### 9.8 WP-P Proxy media

**Goal:** smooth editing of heavy footage (4K HEVC long-GOP, high bitrate) and a budget for multicam.

- Background proxy generation per asset (MediaCodec transcode to 720p or 1080p all-intra-friendly H.264, low
  priority, resumable, cache under app storage with a size budget and LRU eviction).
- Per-project switch "Use proxies for editing"; preview and scrub use the proxy, export always uses the
  originals; mapping through the same asset id with `proxyUri` in a side index (not in the project JSON).
- Heuristics: auto-suggest proxies when decoding drops frames or the asset exceeds a bitrate or resolution.
- Settings: storage budget and a clear-cache action.

**Tests.** Cache budget and eviction, source/proxy mapping, resume after kill, the planner choosing proxies
only in preview; export unaffected.

**Acceptance.** A 4K60 HEVC long-GOP clip scrubs without stalls with proxies on; export uses the original.

**Owns:** `proxy/` native or Kotlin transcoder, `data/` index, settings UI. Avoid: titles, audio DSP.

### 9.9 WP-M Multicam (after WP-A, WP-S and WP-P)

**Goal:** sync up to six cameras or audio sources and cut between them.

- **Sync:** cross-correlation of audio envelopes (FFT on 8 kHz mono, coarse-to-fine) to find offsets in
  frames, with a manual nudge; confidence shown.
- **Multicam clip:** a clip type holding up to 6 angles, an audio source choice, in/out; angle switching is
  recorded on the timeline as cuts at the playhead during playback or by tapping angle buttons; "flatten"
  converts the recording to normal clips.
- **Decoding budget:** only the active angle is decoded at full quality; the others use proxies or low-rate
  thumbnails in a grid viewer (depends on WP-P).
- **Data model:** `MulticamClip { angles[], offsets, audioAngle }`, JSON optional; export flattens.

**Tests.** Sync recovers a known offset from shifted synthetic audio, switching ops and undo, flatten
equivalence, decoder budget planner.

**Acceptance.** Two phones recording the same event line up within 1 frame and can be cut live.

**Owns:** `domain/multicam/`, `ui/editor/multicam/`, `audio/` correlation helper.

### 9.10 WP-R Release preparation

- App signing and release build config (keystore from env, never committed), R8 rules for JNI classes, a
  release CI job that builds an unsigned AAB and APK as artifacts, versioning scheme, crash reporting opt-in
  (decision logged: local logs only vs a privacy-preserving service), a privacy note (no network except model
  download), Play listing text and screenshots checklist in `docs/`, and a first-run onboarding that points to
  `docs/USER_GUIDE.md` content in app (short tips, dismissible).

**Acceptance.** `./gradlew :app:bundleRelease` works with the signing config supplied by environment and the
CI job uploads the artifacts.

### 9.11 Cross-cutting requirements for every package

- Device verification list in the PR body (what was seen on the OPPO CPH2841, what was not).
- Performance budget: no new per-frame allocations on hot paths; state that changes per playhead tick stays
  out of the big editor state; measure with the existing frame-time logs when touching render or timeline.
- Memory: new caches are bounded and registered with the existing budget logic.
- Accessibility: every new control has a content description; the user guide gets a row.
- Backwards compatibility: opening a project from the previous build must still work, covered by a JSON test.

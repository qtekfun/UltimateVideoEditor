# ultimateVE — Technical Specifications

Status: v1, reconciled with the code after phases 1-4 and 6 · Date: 2026-10-03

## Contents

- [1. Build and environment](#1-build-and-environment)
- [2. Module layout](#2-module-layout)
- [3. Time base](#3-time-base)
- [4. Project data model (`project.json`)](#4-project-data-model-projectjson)
  - [4.1 Missing media, relink and recovery](#41-missing-media-relink-and-recovery)
- [5. Architecture](#5-architecture)
  - [5.1 UI layer (Kotlin / Compose, MVI)](#51-ui-layer-kotlin--compose-mvi)
  - [5.2 Engine boundary (JNI)](#52-engine-boundary-jni)
  - [5.3 Rendering](#53-rendering)
  - [5.4 Decode and cache](#54-decode-and-cache)
  - [5.5 Colour](#55-colour)
  - [5.6 Audio](#56-audio)
  - [5.7 3D LUT effect](#57-3d-lut-effect)
  - [5.8 Titles and transitions](#58-titles-and-transitions)
  - [5.9 Undo/redo](#59-undoredo)
  - [5.10 Export](#510-export)
  - [5.11 Captions (typed or imported, no recognition)](#511-captions-typed-or-imported-no-recognition)
  - [5.12 Keyframes, canvas formats and upload presets](#512-keyframes-canvas-formats-and-upload-presets)
  - [5.13 Effects, masks and blend modes](#513-effects-masks-and-blend-modes)
  - [5.14 Retiming: speed, ramps, reverse and freeze frames](#514-retiming-speed-ramps-reverse-and-freeze-frames)
  - [5.15 Lane layout, drops and lane order](#515-lane-layout-drops-and-lane-order)
  - [5.16 Animated captions](#516-animated-captions)
  - [5.17 Still clips: photos and stickers](#517-still-clips-photos-and-stickers)
  - [5.18 Markers, beat detection and text templates](#518-markers-beat-detection-and-text-templates)
  - [5.19 Multi-selection and group edits](#519-multi-selection-and-group-edits)
  - [5.20 Colour grade, looks and video scopes](#520-colour-grade-looks-and-video-scopes)
  - [5.21 Stabiliser and the shared tracker](#521-stabiliser-and-the-shared-tracker)
  - [5.22 Motion tracking](#522-motion-tracking)
  - [5.23 Parameter keyframes (WP-K)](#523-parameter-keyframes-wp-k)
  - [5.24 Interchange and media library (WP-I)](#524-interchange-and-media-library-wp-i)
  - [5.25 Silence auto cut and manual reframe (WP-V2, no AI)](#525-silence-auto-cut-and-manual-reframe-wp-v2-no-ai)
  - [5.26 Multilayer titles, fonts and presets](#526-multilayer-titles-fonts-and-presets)
  - [5.27 Filter pack](#527-filter-pack)
  - [5.28 Transition pack](#528-transition-pack)
  - [5.29 Multicam (WP-M)](#529-multicam-wp-m)
  - [5.30 Project templates](#530-project-templates)
  - [5.31 Proxy media (WP-P)](#531-proxy-media-wp-p)
  - [5.32 Appearance: dark only, optional pure black](#532-appearance-dark-only-optional-pure-black)
  - [5.33 Timeline canvas rendering: text atlas, ruler, blocks](#533-timeline-canvas-rendering-text-atlas-ruler-blocks)
- [6. Timeline operations (specification for tests)](#6-timeline-operations-specification-for-tests)
  - [6.1 Base track and overlays (LumaFusion model)](#61-base-track-and-overlays-lumafusion-model)
- [7. Error handling](#7-error-handling)
- [8. Testing](#8-testing)
- [9. Roadmap specs: closing the gaps with LumaFusion](#9-roadmap-specs-closing-the-gaps-with-lumafusion)

Section numbers are stable: other documents refer to them (for example `SPECS 5.8`). The sub-sections of 9 are listed in 9.0.

## 1. Build and environment

- App name: ultimateVE · Application ID / namespace: `com.ultimatevideo.uveditor`
- Native library: `uveditor_engine` (C++20, CMake, NDK). JNI package prefix `com.ultimatevideo.uveditor.engine`.
- Gradle Kotlin DSL (`*.gradle.kts`) with a version catalog (`gradle/libs.versions.toml`).
- minSdk 31 · targetSdk 36 · compileSdk 37 (current AndroidX requires it) · ABIs: `arm64-v8a` (plus `x86_64` optional for emulator UI work).
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
    stabilise/     Classical tracker, motion analyser, path smoothing, analysis cache, table registry (SPECS 5.21)
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
- Pinch is two-axis: `PinchAxisLock` (Kotlin, pure) picks time or lanes from the axis the finger span changed along most
  once past a 12 dp slop (a tie is the time axis, the factor seen before the choice is held back and applied after it, so
  a horizontal pinch zooms exactly as before). The choice is kept until the fingers lift. Vertical pinch calls
  `TimelineRenderer::zoomLanesBy(factor, focusY)`.
- Lane zoom (`timeline_view/lane_zoom.h`): one scalar `LaneScale`, Q12 fixed point, 0.5x to 3x of the 64 dp default lane
  (32 to 192 dp), fed to `Layout::forDensity` once per frame snapshot; ruler, gaps and touch slop do not scale. The scroll
  is re-anchored with `anchoredScrollY` (the fractional lane position under the focus stays under it, bottom-anchoring inset
  included) and then clamped. `fitLaneScale(density, lanes, viewHeight)` gives the largest scale at which the ruler and all
  lanes fit; if even 0.5x does not fit it returns 0.5x with `fitsAll = false` and the renderer scrolls to the base lane. An
  empty timeline or a panel without room keeps 1x. All lanes have the same height (there is no per-lane collapsed state).
  The Fit button (`fitToContent`) fits both axes and turns on "follow": a resize/rotation or a lane added or removed refits.
  A pinch on an axis, or choosing a height preset, ends the follow for that axis. View state lives in the native renderer
  like the horizontal zoom, survives rotation (the activity handles configuration changes) and is not persisted (the
  horizontal zoom is not either). The snapshot format is unchanged. Below a 9 dp header strip clip names are not drawn and
  below a 12 dp body the filmstrip is skipped (neither triggers at the 0.5x minimum; they guard the layout limits).
- Touch gestures (scroll, drag, trim) on the timeline surface are handled by a Kotlin
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

Per-clip source colour: each video clip may override how its source is read (`Auto`, SDR, HLG, PQ; `clips[].colorOverride`). The project colour space remains the working/export space and every clip is converted to it individually, so SDR and HLG clips mix freely in either kind of project. The override reaches the native layer through the scene `params` (`-1` auto, `0..2` a `SourceTransfer`) and the export spec's `colorMode`.

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

### 5.7 3D LUT effect

`EffectType.LUT` (wire code 13, values `[libraryKey, intensity]`). `.cube` files (3D, sizes 2..65, default domain) are imported into `LutStore`; the preview uploads a used LUT once (`PreviewEngine.uploadLut`) and the exporter receives it in `ExportRequest.luts`. The effect pass samples an RGB16F `GL_TEXTURE_3D` trilinearly (`render/gl_pipeline.cpp`, `kEffectFragment` type 13; CPU reference `applyLut` in `render/effect_math.h`) on the layer's working-space pixels. A missing LUT is skipped.

### 5.8 Titles and transitions
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

### 5.9 Undo/redo
- `EditHistory` in `domain/`: a bounded stack of immutable timeline snapshots (exact restore), driven by
  the editor ViewModel. Drags are previewed provisionally and enter the history only when released; clip
  appearance edits (inspector, preview gestures) are one undo step per gesture (`SetAppearance`).

### 5.10 Export
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
- FFmpeg (static, NDK) is an optional fallback for formats not supported by MediaCodec: built behind
  `-Puveditor.ffmpeg=<dir>`, off by default; see `docs/ffmpeg-fallback.md`.

### 5.11 Captions (typed or imported, no recognition)
- There is no speech recognition and no model: the app is offline by design (`docs/PRIVACY.md`). An earlier
  on-device whisper.cpp pipeline was removed for that reason (see DECISIONS.md, "Privacy").
- Captions come from the user: **typed** one at a time (text, a start and a length stepped by frames or seconds, the next
  caption starting where the last ended) or **imported** from a `.srt` or `.vtt` file picked with the system file
  picker. `domain/captions/Subtitles` decodes the file (BOM, strict UTF-8, UTF-16 without a mark, Latin-1 fallback; at
  most 5 MB), detects SRT or WebVTT, ignores the WebVTT header, NOTE / STYLE / REGION blocks and cue settings,
  strips tags and common entities, joins multi-line cues, counts and skips unusable blocks, and sorts the cues.
- `Subtitles.toCues` maps times to project frames with integer maths from an offset (the start of the project, or
  the playhead), makes every cue at least one frame, cuts a cue short when the next one starts before it ends, and
  joins cues that start on the same frame. Each cue gets evenly timed words (`CaptionAnimator.synthesizeWords`) so the
  animated styles work on any caption. `CaptionPlanner` (grouping timed words into cues) stays for sources that
  carry word times.
- Captions are ordinary title clips (ids start with `caption-`, `outline = true` so they read over any footage). An
  imported file goes on a new title track on top (`AddCaptions`, one undo step; one track per file, so languages stay
  apart); typed captions go on the existing caption track (`AddCaptionsToTrack`, overwriting what they cover) or
  start one. Eight styles set size, colour, position, chunking and animation: Classic, Bold, Pop, Impact and the
  animated Karaoke, Word pop, Typewriter and Bounce (section 5.16); "Restyle" applies a style and colours to every
  caption in one undo step.

### 5.12 Keyframes, canvas formats and upload presets
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

### 5.13 Effects, masks and blend modes

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

### 5.14 Retiming: speed, ramps, reverse and freeze frames
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

### 5.15 Lane layout, drops and lane order

- **Layout.** Tracks are in display order (first is topmost). The timeline panel draws the lane stack bottom-anchored
  (`Layout::anchoredBottom`): the last lane rests on the panel bottom, the ruler stays on top, and the room between
  them is the 'add a lane' zone (`HitKind::AboveLanes`). A taller stack scrolls and opens scrolled to the bottom.
  The base is the lowest *video* lane; audio lanes sit below it.
- **Drop decision.** `domain/DropPlan.decide` maps (clip, requested start, target) to a `DropDecision` (command +
  `DropHint`). Targets: a lane, `AboveLanes` (new overlay lane), `Outside` (cancel; `HitKind::OUTSIDE`, the finger left
  the panel). On the base: start edge within `INSERT_RADIUS_FRAMES` (10) of a junction -> INSERT (`MoveClip`, ripple,
  overlays follow); past the end -> append; otherwise OVERWRITE (`LaneOps.overwriteMove`). Base clips only REORDER.
  On other lanes: a start edge within the same radius of a cut between two touching clips -> INSERT (`InsertOnLane`: that
  lane's later clips shift right, nothing else moves); other overlapping clips -> OVERWRITE, free space -> MOVE.
- **Indicator.** `EditorState.dropHint` is passed to the native canvas with `TimelineEngine.setDropHint` and drawn by
  `timeline_view/drop_hint.h` + the renderer: bar and arrow (insert), tinted range (overwrite), lane placeholder
  (new lane), wash (cancel).
- **Lane ops** (`domain/LaneOps`): `moveToNewLane`, `overwriteMove`, `insertOnLane`, `liftFromBase`, `moveTrack` (up/down among lanes of the same kind;
  the base never moves), each one undo step.

### 5.16 Animated captions

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

### 5.17 Still clips: photos and stickers

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
  a power-of-two sample size) and *drawn* fitted inside the canvas (contain); a sticker is a square of 35 % of the canvas' shorter side.
  The texture keeps the picture's **native size** and is reduced only when it is larger than its fit (`StillFit.plan`); the upload
  carries a separate *display size* (the contain fit, in canvas pixels) that the compositor uses for the quad and for effects, so the
  GPU scales a small picture up and nothing is stored at canvas size. The preview decodes off the main thread and shows the layer when
  the texture is uploaded; `StillKeyCache` guesses a size until `resize` records the real one and evicts by one shared byte budget
  (`PictureBudget`, 128 MB; keys start at 1,000,000 so they never collide with title keys). The export plan names each distinct still
  once and shares one key space with titles; the exporter does **not** upload them up front: the engine requests a still through the
  listener (`loadPicture`) the first time a frame draws it, and `encode/picture_residency.h` releases the least recently used beyond the
  budget, never the pictures the current frame draws. Sources are checked once before the export starts.
- **Animated GIF and WebP:** both implement `AnimatedPicture` (`render(index)` composites on a transparent canvas; forward play costs
  one frame per step; `CanvasSnapshots` keeps the canvas every N frames, N grown with the canvas size so snapshots stay within 32 MB, so a
  seek back replays less than N frames). WebP is read from its RIFF container (`WebpContainerParser`: VP8X, ANIM, ANMF with
  offset, duration, blend and dispose bits, with size and frame-count limits); every frame is wrapped as a standalone still WebP
  and decoded by the platform (`ImageDecoder`, straight alpha), so no VP8 decoder is written. The file's loop count is ignored.
  Frame timing is `AnimationTiming` for both (0 ms and 10 ms or less count as 100 ms).
- **Timeline canvas:** a still's snapshot clip has no asset key, so no waveform or thumbnails are requested for it.

### 5.18 Markers, beat detection and text templates

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

**Text templates** (`TextTemplates`, `AddTextTemplate`, one undo step). Since WP-T a template is a multilayer title preset (section 5.26):
one title clip on a title lane (a lane is reused only when it is free over the template's range, otherwise a new one is added above;
never the base, so nothing is overwritten), whose layers are a bar shape and the text, with an in and out `MotionPreset` turned into
ordinary clip keyframes (`TitleMotion`), so preview and export are identical for free. The text typed in the tray goes into the first text
layer. Built in: Lower third, Pop title, Slide-in headline, Subtitle bar. (Before WP-T a template was two clips: a text clip and a solid
bar sticker on an overlay lane, each with its own keyframes; the bar stickers `shape:bar-dark` / `shape:bar-accent` are no longer used.)

### 5.19 Multi-selection and group edits

State (`EditorState`): `selectedClipId` stays the primary clip (the inspector's); `selectedClipIds` holds the whole
group when more than one clip is selected and always contains the primary one; `selectMode` and `clipboardCount`
complete it. `EditorState.selection` is the effective set: the group if it still holds the primary clip, else just
the primary. Every plain tap, empty-space tap and ClearSelection resets `selectedClipIds`, so a stale group can
never come back. Intents live in `SelectionIntent` (a sealed sub-interface of `EditorIntent`).

Gestures (Kotlin, `TimelineSurfaceView`): in select mode a tap toggles a clip and a drag that starts on empty lane
space draws a marquee; a long press toggles a clip in any mode. The marquee rectangle is native state
(`nativeSetMarquee`), and on release `nativeClipsInRect` returns the clip keys it touches (`clipsInRect` in
`hit_test.cpp`, host-tested; the ruler never counts). Dragging any selected clip with more than one selected moves
the group (`GroupMove`, snapped as a block by `GroupOps.snappedDelta`; the lane delta counts lanes of the same
kind under the finger; dragging off the panel cancels).

Snapshot version 6: the per-clip flags gain bit 3 = primary (the layout is the same as version 5, and a version 5
clip is its own primary when selected). The canvas outlines the primary clip in yellow and the others in blue.

Domain (`GroupOps`, `Clipboard`, `ClipSelection`, commands in `GroupCommands.kt`): every operation maps a
`Timeline` to a new `Timeline` or a typed `GroupEditUnavailable` with a message for the user, so each is
all-or-nothing and one undo step.

| Operation | Rule |
|---|---|
| Move | Same delta and lane delta for all; no clip may land on one that stays or on one that moves with it; frame >= 0. A base selection must touch and be only base clips: the run is reordered like `MagneticBase.reorderBlock`. |
| Delete | Other lanes first (gaps stay), then base clips last to first (each closes its gap, overlays follow); clips already removed by a base deletion are skipped. |
| Copy / paste / duplicate | `Clipboard.capture` keeps clips, lane ids, relative offsets and transitions between copied clips. Paste puts the earliest clip at the playhead; overlay clips go to their lane (else the first lane of the kind) and fail on overlap; base clips are inserted as a run at the nearest cut. Fresh ids (`<id>~cN`). Duplicate pastes at the end of the last selected clip. |
| Paste attributes | Transform and effects on picture clips, gain on clips with sound, speed and reverse on clips with media; fails when nothing can take any. Keyframes and ramps are not copied. |
| Speed / gain / opacity | Per clip through the single-clip rules (`MagneticBase.setSpeed` ripples as for one clip); opacity also sets the keyframes of an animated clip. |
| Align | Starts or ends on the first start / last end; clips of one lane that would stack are refused; refused for the base. |
| Transitions | BETWEEN: crossfade at the cut after each selected clip that touches the next (shortened to what clips and media allow, an existing one is resized). HEAD_AND_TAIL: opacity keyframes fade a picture clip in and out. |

### 5.20 Colour grade, looks and video scopes

**The effect.** `EffectType.COLOR_GRADE` (code 14) is a normal effect of the chain with 21 values, in this
order (also documented in `render/grade_math.h`): lift R G B master (0..3), gamma R G B master (4..7), gain
R G B master (8..11), each -1..1; offset R G B (12..14, -0.5..0.5); contrast (15, 0..2), pivot (16, 0..1),
saturation (17, 0..2), vibrance (18, -1..1), temperature (19) and tint (20), -1..1. `Effect.curves`
(optional, only for a grade) holds four `GradeCurve`s (master, red, green, blue), each 2..8 control points in
0..1 with increasing x; the curve is the monotone cubic (Fritsch-Carlson) through them and flat outside the
end points. JSON: `EffectDto.curves` with a list of `{x, y}` per curve, omitted/empty meaning identity.

**Maths** (straight display-referred RGB in the project's working space): white balance (temperature and tint
as the standalone effects), plus offset, contrast about the pivot, then lift (`x + lift (1 - x)`, lift =
0.5 x (master + channel)), gain (`x 2^(master + channel)`), gamma (`x^(2^-(master + channel))`), saturation and
vibrance (`mix(luma, x, sat x (1 + vibrance (1 - chroma)))`), then the master curve and the channel curve.
`render/grade_math.h` is the CPU reference and `kEffectFragment` (type 14) the GLSL.

**Wire.** The grade writes 153 values: the 21 values then 33 curve samples x (master, red, green, blue)
(`FxWire.effectValues`, `core::kGradeWireValues`). The shader holds them in `uG[21]` and `uCurve[33]` and
interpolates linearly between samples.

**Edits.** Wheel, slider and curve drags send `EditorIntent.UpdateGrade` (shown live, committed by
`EndFxEdit` as one undo step like every effect slider); `ApplyGrade` applies a saved look or a pasted grade
to the selected clip (`EditCommand.SetGrade`, one step: replaces the clip's first grade or appends one).
Looks are `looks/<id>.json` in the app's private storage (`LookStore`), listed by `LookLibraryViewModel`
together with the in-memory copy/paste clipboard.

**Scopes.** `render/scope_renderer.cpp`: while a scope surface is attached, `PreviewEngine::maybeDraw` blits
the letterboxed picture from the window framebuffer (before the swap) into a 320 x 180 texture; a vertex
shader turns each of its pixels into one point added to a half-float accumulation texture (waveform and
vectorscope into alpha, parade into the channel's own colour, histogram into R, G, B and luma); a display
pass draws the picture and graticule on a second EGL window surface of the same context, at most 30 times a
second (a late redraw keeps a paused scope current). Nothing is read back to the CPU. Modes:
`ScopeMode.WAVEFORM / PARADE / VECTORSCOPE / HISTOGRAM` (`scope::Mode`). The maths of where a sample lands is
`render/scope_math.h` (with a CPU accumulator used by the host tests). The scale labels (percent, and 203 and
1000 nit marks in an HLG project) are Compose text over the surface (`ui/editor/ScopeScale.kt`).

**Not included:** secondary HSL qualifiers (see `DECISIONS.md`).

### 5.21 Stabiliser and the shared tracker

Camera-shake correction of a video clip, computed on the device with classical computer vision only: no
models, no third-party libraries, no network. Settings per clip (`Clip.stabilise`, JSON optional field
`stabilise: { strength, crop }`): **strength** 0..1 (stored in percent) and **crop** `tight` / `medium` / `full`.

**Pipeline.**
1. *Analysis* (once per media file, background, cancellable). `stabilise/LumaDecoder` decodes the clip's source
   range plus a 1 s margin on each side sequentially with its own `AMediaCodec` (no output surface, one hardware
   decoder, released when the job ends; the same approach as the thumbnail decoder) and reads only the luma plane,
   rotated upright and scaled to at most 480 px (`stabilise/luma.h`). `MotionAnalyser` detects Shi-Tomasi corners
   (spread over an 8x6 grid) in the previous frame, tracks them with pyramidal Lucas-Kanade (3 levels, 15x15
   window, forward-backward check) and fits a similarity transform with RANSAC (2-point hypotheses, deterministic
   seed, 1 px threshold, least-squares refit), so a moving subject does not drag the estimate. A frame with too
   little to track counts as no motion. Result: one `FrameMotion` per decoded frame (translation in height units,
   rotation, log scale, quality).
2. *Cache* `stab/<assetId>.<hash>` under the project folder (`stabilise/stab_cache.h` has the byte layout; checksum
   CRC-32; written through a temporary file and renamed). The file holds the **raw** motion, so strength and crop
   changes never need a new analysis. The hash covers the asset's URI, length, frame rate and the analysis version, so
   relinking or a tracker change finds no cache. The header records the analysed range: a clip that now reaches
   outside it (extended by a trim) is `Stale` and must be analysed again; the new analysis merges with the old range.
3. *Table*. `registerFromCache` builds the correction table: compose the camera path `P_t`, resample it to the
   project frame rate (the frame numbering of the preview decoder, which counts from the media's first frame),
   low-pass its parameters with a Gaussian (sigma from 0.1 s to 2.5 s, mirrored odd extension at the ends so a
   steady drift stays steady), take `C_t = Q_t o P_t^-1`, then scale by one constant zoom per clip: `tight` is the
   largest zoom any frame needs so that no frame edge shows (at most 2x), `medium` half of it, `full` none. Strength 0
   is an identity table.
4. *Registry*. Tables live in `StabRegistry`, keyed by `StabKey.of(assetId, settings)` (24 bits, so it travels as a
   float). The render plan puts the key into `ClipFx.stabKey`; `FxWire` writes it as an effect of type 15 that always
   runs first. The preview (`PreviewEngine::maybeDraw`) and the exporter (`renderFrame`) call
   `resolveStabilisation(fx, sourceFrame)`, which looks the table up by the **source frame** being drawn and fills the
   effect's per-frame values (dx, dy, theta, scale). Retiming, reverse and transitions therefore need no special case,
   and preview and export draw the same picture. An unregistered key drops the effect (the clip draws unstabilised).
   The preview redraws when the registry changes (`drawnStabRevision_`).
5. *Warp*. Effect type 15 in the effect fragment shader samples the layer through the inverse of
   `Xo = scale * R(theta) * X + (dx, dy)` (positions in height units; `stabilise/stab_warp.h` is the CPU reference), with
   edges repeating the border pixels. It runs before the user's effects, mask and blend, at the layer's own size.

**Cost.** Analysis is bounded by decoding plus roughly 5 ms of tracking per frame at 480 px; it runs at background
priority and never blocks the render, UI or preview decoder threads. Drawing a stabilised layer adds one effect pass.

**UI.** Inspector section for video clips: switch, strength slider (one undo step on release), crop chips, an
*Analyse* button with progress and cancel, and the status (`Not analysed`, `Ready`, `Stale`). Everything is one
`SetStabilise` command; the analysis itself is not an edit.

**Out of scope:** rolling-shutter correction, 3D camera reconstruction, using gyroscope metadata.

**Reusable tracker (for WP-V1 motion tracking).** `stabilise/tracker.h` has `detectCorners`, `trackLk`,
`trackPoint(prev, next, point, &out)` and `BoxTracker(firstFrame, box)` with `update(nextFrame)` returning a
confidence (0 means lost and the box stays); `stabilise/similarity.h` has `Similarity`, `fitSimilarity` and
`estimateSimilarityRansac`. All take `Gray` float images (`stabilise/gray.h`, pyramids with `buildPyramid`) and are
covered by `tests/stabilise_host_tests.cpp`. Frames come from `LumaDecoder::run`, which hands each decoded frame to a
callback with its time from the media's first frame.

### 5.22 Motion tracking

Follow a point or a region of a video clip's picture through the clip, and make another clip (a title, a sticker, an
overlay) follow it. Classical computer vision only, on the device: the tracker is the stabiliser's `BoxTracker`
(Shi-Tomasi corners, pyramidal Lucas-Kanade with a forward-backward check, a similarity fit per frame), no models, no
third-party libraries, no network.

**Model.** `Timeline.motionTracks: List<MotionTrack(id, clipId, name, seed)>` with `TrackSeed(sourceFrame, cx, cy, w, h)`:
the point or box the user picked, in fractions of the upright source frame (0..1 across and down), at a source frame in
project frames (like every source range, so retiming, trimming and moving the clip do not invalidate it). JSON optional
field `motionTracks` (`MotionTrackDto`); an invalid one is a corrupt project. Only a video clip that plays a video file
can be tracked. Deleting the clip removes its tracks (`Timeline.pruned`). `EditCommand.AddMotionTrack`,
`RemoveMotionTrack` and `AttachToMotionTrack` are undoable; the analysis itself is not an edit.

**Analysis** (`track/`, one decode pass, background priority, cancellable, progress polled like the stabiliser's):
1. `TrackService` opens the media with `stab::LumaDecoder` (own `AMediaCodec`, luma only, rotated upright, scaled to
   320 px on the long side) over the clip's source range plus 0.5 s each side, widened to hold the seed.
2. Frames before the seed are kept as 8-bit copies (at most 900, about 50 MB; the ones closest to the seed). At the first
   frame within half a frame of the seed time, a backward `TrackRunner` walks the kept frames in reverse from the seed
   while a forward one starts; the decoder then goes on and the forward runner follows each new frame. The two runs are
   merged into one chronological path with the seed once (`mergeRuns`).
3. `TrackRunner` marks a frame **lost** when the box tracker's confidence (share of tracked points that agree with the
   estimated motion) is below 0.15; the box then stays at its last believable position, so a blank or blurred stretch
   never throws it away, and tracking resumes when the target is back. Boxes smaller than 12 px are grown.
4. The path is written to `track/<assetId>.<hash>` (`track/track_path.h`: `UVTK`, header with aspect, seed time and range,
   36-byte samples with fractions of the frame, rotation, confidence, lost flag, CRC-32; temp file then rename). The hash
   covers the media (URI, length, frame rate), the analysis version and the seed, so another target, a relinked file or a
   tracker change finds no cache. Kotlin parses the file (`engine/track/TrackCacheFile`), checks the CRC and numbers frames
   with the project frame rate (`microsToFrames` after adding half a frame), which is how the preview decoder numbers them.
5. `FileMotionTracker.statusOf`: `NotAnalysed` (no valid file or an older analysis version), `Stale` (the clip now
   reaches outside the analysed range, with a two-frame tolerance), `Ready(lost, frames)`.

**From the picture to the canvas** (`domain/MotionTracking.kt`, pure, the same fit as the compositor): the clip's frame is
fitted ("contain") into the canvas, then scaled about its centre, rotated clockwise and moved by its position.
`TrackMath.toCanvas(u, v, aspect, canvas, pose)` gives canvas pixels from the canvas centre; `fromCanvas` is its exact
inverse (used to turn a tap on the preview into a point of the picture). `canvasPath` walks the project frames of the
tracked clip, maps each to its source frame with the clip's retiming (`sourceFrameAtProjectFrame`: trim, speed, ramps,
reverse, freeze) and its pose at that frame (its own keyframes), and returns canvas points flagged lost or not.
The stabiliser's correction is not applied to the path: a stabilised clip shows a slightly warped picture, so the target
can be a few pixels off on very shaky footage.

**Following.** `TrackMath.attachKeyframes(path, tracked, attached, canvas, offset, tolerance)` computes the target's canvas
position at every project frame the attached clip occupies (before and after the tracked clip the first or last position
holds), reduces it with Douglas-Peucker on (frame, x, y) to keys within 1 px of every dropped frame under linear
interpolation (a straight drift is two keys), and merges them with the attached clip's existing keyframe frames. Only the
position follows the path: scale, rotation and opacity are the clip's own (evaluated from its existing keyframes), and
existing keys keep their interpolation and handles. Preview and export need nothing new: they are plain position
keyframes. The result replaces the clip's keyframes in one undo step and is editable afterwards. The attached clip is
centred on the target (offset 0).

**UI.** Inspector section "Track motion" (`TrackControls`): *Track an object* waits for a pick on the preview
(`TrackTargetLayer`: a tap picks a point with a box of 7, 12 or 20 % of the frame height, a drag draws the box), adds the
target at the playhead's frame (the playhead must be on the clip) and starts the analysis with progress and cancel; each
target has *Show path* (a line on the preview, red where lost, a dot at the playhead), *Analyse (again)* and *Delete*.
For any other visual clip the section lists the targets of other clips with *Follow*. The path overlay reads the
playhead in its own scope so a tick redraws only the dot.

**Limits.** Position only (no scale or rotation following); one target at a time is analysed; a photo or sticker cannot be
tracked; frames more than 900 before the seed are not tracked backward (the path holds its first position there).

### 5.23 Parameter keyframes (WP-K)

Any single value of a clip can be animated, not only its pose. A clip carries `params: List<ParamTrack>`; each
track is a `paramId` and strictly increasing `ParamKey(frame, value, interpolation, out, inn)` in clip frames
(0 is the clip's first frame, so keys travel with the clip and are cropped by split, trim and overwrite, and
stretched by a speed change, like the pose keyframes). Before the first key and after the last the value holds.

**Parameters** (`domain/ParamTracks.kt`, ids in `ParamIds`):

| Id | Value | Range |
|---|---|---|
| `fx.<effectId>.<index>` | value `index` of an effect (colour grade, LUT intensity, chroma key, ...); the LUT library key is not animatable | the effect parameter's range |
| `audio.gainDb` | volume (the loudness-normalise gain is added when mixing) | -96..24 dB |
| `audio.pan` | balance | -1..1 |
| `audio.eq.<band>.gainDb` | gain of EQ band 0..4 | -18..18 dB |
| `pose.positionX`, `pose.positionY`, `pose.scaleX`, `pose.scaleY`, `pose.rotation`, `pose.opacity` | the pose, a **per-parameter view** of the joint pose keyframes | the transform ranges |

The pose stays stored as the clip's joint `Keyframe`s (native exporter, snapshot and older projects are
unchanged); `Clip.paramKeys("pose.x")` projects them, and setting a pose component at a frame writes the joint
keyframe with the pose the clip already has there for the other components. Removing a pose key removes the
whole keyframe. A track in `params` never has a `pose.*` id.

**Interpolation.** `LINEAR`, `EASE` (smoothstep), `HOLD`, and `BEZIER`: a cubic between the two keys shaped by
the earlier key's `out` handle and the later key's `inn` handle, the CSS `cubic-bezier(x1, y1, x2, y2)`
convention in the segment's unit square (`x` is a fraction of the segment's length in 0..1, `y` a fraction of the
value change and may overshoot, -2..3). Missing handles default to an ease in-out. The curve parameter is solved
by bisection (48 steps), so the result is deterministic. A Bezier segment that a crop cuts in the middle keeps its
handles over what remains (like an ease it changes shape slightly; linear and hold are exact).

**Evaluation is in Kotlin and identical in preview and export**, at integer frames:

- *Video parameters.* `RenderClip.fxAt(frame)` evaluates the effect values; the preview scene is built from it at
  the playhead and (like an animated pose) re-anchors the native clock every tick when a value moves. The exporter
  receives `VideoClipSpec.fxFrames`, the effects of every project frame of the clip, encoded as consecutive
  `core/layer_fx.h` blobs (`parseFxFrameTables` validates index, count and size before allocating); the native
  loop picks `fxFrames[frame - startFrame]` (`encode/export_math.h` `fxAt`). A Bezier segment of the *pose* is
  sent to the native evaluator as one linear key per frame (`Keyframes.bakedForNative`), exact at every frame.
- *Audio parameters.* `automationLanesOf` turns each audio track into a lane of (frame, value) points
  (`ParamTracks.audioPoints`: two points for a linear segment, one per frame for an ease or a Bezier, a hold keeps
  its value to the last frame before the next key) in the audio snapshot (**version 5**, still reading 4; layout in
  `audio/audio_snapshot.h`). The mixer interpolates linearly between points per sample for the volume and in
  32-sample chunks aligned to the clip for pan and EQ band gains (the chunk is the unit, never the block, so the
  realtime stream and the offline export render the same samples whatever the block size). EQ chains with an
  animated band keep all five band stages (`EqChain::designAll`) so the filter states never shift.

**Editing.** The inspector shows the clip as `Clip.displayedAt(frame)` (volume, pan, EQ and effect values
evaluated at the playhead). A control that edits an animated value writes a key at the playhead (keeping the
shape of the key already there) and leaves the fixed value as the base (`ParamOps.setFxAt`, `setClipAudioAt`,
`setGainAt`); an unanimated control edits in place as before. Removing the last key of a parameter writes that
key's value back as its fixed value, so nothing jumps; removing an effect drops its tracks (`withoutDanglingParams`).
Commands: `SetParamKey`, `RemoveParamKey`, `MoveParamKey`, `ClearParamTrack`, `PasteParamKeys`,
`SetParamKeyShape`, `SetFxAt`, `SetClipAudioAt`, `SetGainAt`, each one undo step. A key drag in the lane is shown
live and committed on release (`UpdateParamKey` / `EndParamKeyEdit`).

**UI** (`ui/editor/ParamKeyframeUi.kt`): a diamond at the end of every animatable slider; a *Keyframes* section
under the inspector with one row per animated value (curve over the clip, playhead, draggable diamonds: sideways
moves the key in time, up or down changes its value), previous / next key, copy, paste (the first copied key lands
on the playhead, values clamped to the target range) and clear; tapping a key selects it and shows the curve
controls (Linear / Ease / Hold / Bezier and the four handle coordinates). The pose row only moves keys in time.
The colour wheels (three values each) have no diamond; their sliders do.

**JSON** (`ClipDto.params`, optional): `[{ "paramId": "fx.c1.0", "keys": [{ "frame": 0, "value": 0.5,
"interpolation": "bezier", "out": {"x": 0.3, "y": 0.0}, "inn": null }] }]`; pose `KeyframeDto` gains optional
`out` / `inn` and the `bezier` mode. Projects written before load unchanged.

**Copy and paste of clips** (WP-S): `Clip.params` travels with the clip like `keyframes`; a pasted clip's tracks
are relative to its own start, so they need no change.

### 5.24 Interchange and media library (WP-I)

Implemented in `data/interchange/` (pure formats, repository entry points) and `ui/library/` (library
model and sheet). Everything is local: files go through the system picker, nothing is fetched or uploaded.

**Project bundle (`.uvbundle`).** A zip (`ProjectBundle`):

| Entry | Content |
|---|---|
| `bundle.json` | manifest: `format` = `uveditor-bundle`, `formatVersion` (1), app, project name, schema version, one `media` item per library file (`assetId`, `name`, `sizeBytes`, `entry` when the bytes are inside) and the thumbnail entry names |
| `project.json` | the raw text of the project file, so fields this build does not know survive |
| `thumbnails/<file>` | the project card picture (JPEG) when one exists |
| `media/<assetId>-<name>` | the media files, only for "with media files"; stored, not recompressed |
| `resources/<kind>-<key>-<name>` | the LUTs (`.cube`, deflated) and fonts (stored) the project refers to, when chosen (see below) |

- Writing: entries carry no timestamps, so the same input gives the same bytes; media that cannot be read
  are named in the result and left out; the manifest still lists their name and size.
- Reading (`ProjectBundle.extract`) never writes outside its target directory: names must be relative, use `/`,
  contain no `..`, `.`, empty parts, NUL or drive letters, and `media/` and `thumbnails/` entries must be a
  single leaf (anything else is `UnsafePath`); duplicate names are refused. Limits (`BundleLimits`): 20 000
  entries, 64 MB for each JSON entry, 8 MB per thumbnail, 64 GB per media file, 128 GB in total; a truncated or
  non-bundle zip becomes a typed `BundleError`, shown to the user through `ProjectError.Bundle`.
- Importing (`ProjectRepository.importWithReport`, also what `importFrom` uses) sniffs the first four bytes
  (`PK\x03\x04`) to tell a bundle from a project file, unpacks into a scratch folder `.import-<id>` under
  the projects folder, rewrites the project (new id when needed, "Name (2)" on a clash), points assets whose
  bytes came along at `file://<project folder>/media/<file>`, relinks the rest, writes `project.json` inside
  the scratch folder and moves the whole folder into place with one atomic rename; the scratch folder is
  always removed, so a failure leaves nothing behind. Folders starting with `.` are never listed as projects.
- Auto-relink (`AutoRelink`): among the assets of the other local projects, a file matches when its name
  (ignoring case) **and** size equal the bundle's entry; a name alone never matches, unknown sizes never match,
  the first candidate wins. There is no search outside those libraries and no new permission.

**Resources in the bundle (LUTs and fonts).** A project refers to two kinds of things that live in app-wide
libraries rather than in the project: imported 3D LUTs (a LUT effect holds the library key in its first value,
`LutStore`) and imported fonts (a text layer holds the font id, `FontRegistry`). Looks, title presets and
templates are copied into the project by value when applied, and stickers and emoji are built in, so those two
are the only global references (`ResourceRefs.collect` reads them from the raw `project.json`).

- Manifest: `resources` (optional, after format 1 shipped) lists `{kind: "lut"|"font", key, name, sizeBytes,
  sha256, entry}`; `entry` is set when the bytes are inside. A resource the project uses that stays out (not
  ticked, or not on the exporting device) is still listed, with no `entry`, so the importer can name it.
  `formatVersion` stays **1**: an older build decodes the manifest ignoring unknown keys and `extract` skips
  entries it does not know, so it opens a new bundle and simply does not install the resources; a bundle
  without the key imports exactly as before. Reading: `resources/` entries are single leaves, at most 32 MB each,
  256 of them and 512 MB in total (`BundleLimits`); a manifest `entry` outside `resources/` is `Corrupt`.
- Choice (`BundleChoice`): media off, **LUTs on**, **fonts off** by default. Fonts are opt-in because their
  licences may not allow giving the file to others; the dialog says so next to the switch.
- Install (`BundleResourceInstaller`, called by `importBundle` before the project is moved into place): each
  entry is checked against its SHA-256, validated by the normal parser (`.cube` or sfnt font) and stored through
  `ResourceLibrary`. A resource already stored (same bytes) is not copied again. LUT keys are a 24-bit hash, so
  `LutStore.install` compares the content under a key: a different LUT on the same key moves the new one to
  the next free key and `ResourceRefs.withLutKeys` rewrites the project's references (idempotent: importing
  again finds the LUT it placed before). A font id is the first 8 bytes of the SHA-256 of the file, so an id
  that does not match the bytes (a doctored manifest) is refused before anything is stored, and a clash with
  different bytes is refused. One bad resource never stops the import: it is reported.
- Report (`ResourceImportReport`): installed, already here, re-keyed, failed (name and reason) and missing
  (referenced, neither in the bundle nor here); the hub shows a snackbar sentence and, when something failed or is
  missing, a dialog that stays until dismissed.
- Export dialog (`ui/library/BundleExportDialog`, shared by the hub's "Export bundle for another phone…" and the
  editor's library menu): switches for media, LUTs and fonts with the counts and sizes from `bundlePreview`,
  resources this device does not hold shown as such and not counted, and an estimate before the picker opens.

**EDL (`Edl`).** CMX3600, one document per video or audio track, named `<project>-<label>.edl` (labels as the
editor shows them: `V1` base, `V2` above it, `A1`, ...). `FCM:` is drop frame (`;`) for 29.97 and 59.94 and
non-drop frame otherwise (23.976 at 24 with a note); one event per clip with source and record timecodes
(`B` channel for video with audio, `V` video only, `AA` audio tracks), `M2` line for constant speed (negative for
reverse, 0 for a freeze), `* FROM CLIP NAME` and `* CLIP ID` comments. Titles, stickers, photos, effects,
transforms, keyframes and transitions are not carried; `EdlExport.notes` says what was left out. With more than
one track the editor writes a zip of the files (`Edl.zip`, reproducible bytes).

**FCPXML 1.9 (`Fcpxml`).** Resources: one `format` (project size and rate, Rec. 709 or Rec. 2020 HLG colour
space), one `asset` per file used (`media-rep` with the stored address), a `Basic Title` effect when titles
exist. The base track is the spine (a `gap` fills holes); other tracks are connected clips (`lane` = overlay
number counting up from the base, titles above the overlays, audio below counting down) attached to the base
clip they start in, with `offset` in that clip's time (as if it played at normal speed). Clips carry
`start`/`duration` in rational seconds, a `timeMap` for retime, `adjust-transform` (position as a percentage
of the frame height with y flipped, rotation negated, scale), `adjust-blend` for opacity and `adjust-volume`
for gain; titles are `title` generators with a text style; markers are `marker` elements on the spine item
they fall in (colour as a `[colour]` prefix, note in `note`). Not exported: effects, LUTs, grades, masks,
keyframes, speed ramps, stickers, transitions, audio tools; `FcpxmlExport.notes` lists them and the sequence
`<note>` repeats them. Unverified against a real Final Cut Pro or DaVinci Resolve import.

**Media library.** `Library` (pure) filters and searches (`LibraryQuery`: text over name, tags and note, kind
filter, tag), counts tags, normalises tags (trim, no commas, 32 characters, 16 per file, duplicates ignoring
case) and notes (280), lists where a file is used (`uses`, `nextUse` for stepping through them, `assetOfClip` for
find in library) and finds unused files. Tags and notes are optional `MediaAssetDto` fields (`tags`, `note`);
editing them, like reordering the tray, is saved with the project and is not an undo step. "Remove unused"
considers a file used if it appears in the timeline, in any state undo or redo can reach (`EditHistory.reachableTimelines`)
or on the clipboard, so an undo can never meet a file that is no longer in the library.

**Markers.** `MarkerDto` and `Marker` gain optional `note` (200 characters) and `color` (red, orange, yellow,
green, blue, purple); `AnnotateMarker` is one undo step. The native ruler does not draw colours (it would need
a snapshot version bump); the colour shows in the dialog and in exports.

**Quick markers (LumaFusion style).** `Marker` also has an optional `name` (`MarkerDto.name`, 40 characters, blank
means none; older files load without it). `MarkerOps.update` sets name, note and colour together (`EditMarker`),
`MarkerOps.move` moves a marker to another frame (`MoveMarker`; frames stay unique and sorted, a taken frame is
refused), `previous` / `next` find the neighbours. Each command is one undo step and undo is exact.
In the editor: `MarkerIntent.AddAtPlayhead` (one tap) runs only `AddMarker` and sets the `markerHint` state
(cleared after 3 s); a marker within 2 frames of the playhead opens its popup instead. The popup (`markerPopup`)
keeps a draft, shows it live through `dragPreview` and commits one `EditMarker` when it closes, steps to the
previous / next marker, or is dismissed by a tap on the timeline; delete is one `RemoveMarker`. A drag that starts
on a marker (`HitKind.MARKER`, `DragMode.MARKER`) previews `MoveMarker` with the clip-edge, playhead and marker snap
(8 frames) and commits it on release. Beat tools, previous / next marker and marker snapping sit behind a long
press of the marker button. With "Snap to markers" on, markers are also edit points for the previous / next edit
point buttons.
**Canvas:** the finger target is native (`HitKind.Marker = 10`, `Layout::markerHitHalf` = 20 dp each side, so a 40 dp
wide target; the nearest marker within it wins, a marker beats the playhead unless the playhead is strictly
nearer, the hit carries the marker's index in `clipKey` and the finger frame in `frame`). A marker's name is a
snapshot label under the key `-2 - markerIndex` (`SnapshotLabel.markerKey`), drawn beside the flag only when it fits
before the next marker (cut at the next marker's tick; nothing when under 24 dp is left). Since snapshot version 8 the name is
UTF-8 and drawn from a text bitmap (section 5.33); before that it was ASCII in the built-in 3x5 font. The label section already
carries arbitrary keys and an old canvas simply ignores keys it does not know. The EDL has no markers (a CMX3600 list has no place for them); FCPXML uses the name, then the note.

### 5.25 Silence auto cut and manual reframe (WP-V2, no AI)

Both work from data the app already has and send nothing anywhere.

**Silence auto cut.** `SilenceDetector` (domain) reads the loudness envelope of the clip's source (the peak cache
the timeline fills for waveforms, through `EnvelopeSource`) and returns the runs of bins under a level that last
at least a minimum, shortened by a padding at both ends. `AutoCutPlanner` maps those spans to timeline frames
through the clip's source range (only for a clip that maps frame for frame onto its source: no speed change, no
reverse), shrinking each to whole frames inside the clip. `AutoCut(spans)` is one `EditCommand`: spans are removed
from the end backwards; each is split out of its base clip and removed with `ClipDeletion.delete`, so the base
closes and overlays follow exactly as for a manual delete (6.1). The sheet lets the user tune level, length and
padding, lists the proposals with timecodes and lets each be switched off before applying.

**Reframe helper.** `Reframe.poseFor` returns the pose that fills the canvas with a clip's frame (cover, times a
zoom up to 4x) and puts the user's point of interest in the middle, with the offset limited so no canvas edge is
uncovered (it uses the same contain-fit as the compositor, `TrackMath.fitSize`). `ReframeClip` writes one fixed pose
for one point or an eased position/scale keyframe per marked moment (frames relative to the clip), replacing earlier
keyframes, as one undo step. The picture's shape comes from the existing aspect probe. No subject detection.

### 5.26 Multilayer titles, fonts and presets

A title is either a **plain** title (the single-text fields of `TitleContent`, used by captions and old projects) or a **multilayer** one:
`TitleContent.layers` holds `TitleLayer`s drawn in list order, so the first is at the bottom and the last on top (the editor lists them
top first). `TitleContent.text` mirrors the first text layer so lists and labels have something to show; `TitleLayerEdit.synced` keeps it
so. Layer kinds: `TextLayer` (text, optional imported `fontId`, size, colour, alignment, bold, italic, letter spacing in em, line height,
border `LayerStroke`, `LayerShadow`, background `LayerBox`), `ShapeLayer` (rect, rounded rect, ellipse, line; fill, outline, shadow,
corner radius) and `ImageLayer` (a library photo or a built-in sticker, size, shadow). Every layer has a `LayerPlacement` (offset as
canvas fractions from the centre, scale, rotation, opacity) inside the title, which the clip's own transform then moves. All sizes are
fractions of the canvas, so a title is resolution independent. At most `TitleLayers.MAX_LAYERS` (16) layers; the last layer cannot be
removed. `TitleLayerEdit` holds the pure edits (convert, add, remove, replace, move, duplicate); the editor commits each through
`EditCommand.SetTitle`, so every change is one undo step. A plain title converts losslessly except a caption with word timing or an
animation (`canConvert`).

**Rendering.** `AndroidTitleRasterizer` sends a layered title to `LayeredTitleDrawer`, which rasterises the whole group into **one**
premultiplied RGBA bitmap, so the compositor, the cache keys (`TitleKeyCache` by content equality) and the exporter are unchanged and
preview and export draw the same picture. The bitmap is symmetric around the canvas centre (the compositor centres title bitmaps) and as
small as the layers allow (`LayerBounds.halfExtents`, capped at 0.6 of the canvas beyond each side); a lower third is a thin strip. Text
uses `StaticLayout` (wrapping at 90 % of the canvas width), shadows use `Paint.setShadowLayer` (a box carries the shadow when there is
one), pictures come from `LayerImages` (`AndroidLayerImages`: `ImageDecoder` scaled to the drawn size, EXIF applied, a small LRU) and a
photo that cannot be loaded is skipped. A photo layer stores the asset id; `TitleLayers.resolved` points it at the library file when the
preview scene or the export plan is built (`ImageLayer.resolvedUri`, never stored), so relinking a photo changes the cache key and redraws it.

**Fonts.** `FontRegistry` (app-private `filesDir/fonts`) stores fonts the user imports with the system picker: `FontMetaParser` validates
the sfnt table directory (TrueType, OpenType/CFF; no collections; at most 25 MB) and reads the family name from the `name` table. A font's
id is the first 16 hex digits of its SHA-256, so the same file has the same id on every device; there is no index to corrupt (the list is
read from the files). `RegistryFontResolver` makes a `Typeface` (bold and italic are synthesised when the font lacks them); a missing or
unloadable font falls back to the system font. Titles that name fonts this device lacks raise a banner with an "Import font" button
(`EditorState.missingFonts`), and the title editor shows the same warning on the layer. Nothing is downloaded; the import dialog reminds
the user to check the font's licence.

**Motion.** `MotionPreset` (None, Fade, Slide from left/right/bottom/top, Pop) is turned by `TitleMotion.keyframes` into ease keyframes of
the clip: the intro runs over the first `edgeFrames`, the outro over the last (shortened so they never meet), and `SetTitleMotion` replaces
the clip's keyframes in one undo step.

**Presets (`.uvtitle`).** A preset is a `TextTemplate`: layers, `intro`/`outro`, `edgeSeconds`, default text and length, fonts used (id and
family). `TitlePresetCodec` reads and writes the file (JSON, `format: "uvtitle"`, `version: 1`, at most 256 KB; newer versions and other
formats are refused with a message). Photos cannot travel (they are files of one project), stickers can; fonts are listed but not embedded.
`TitlePresetStore` keeps the user's presets as `<id>.uvtitle` under `filesDir/title-presets` (the id is a hash of name and layers, so saving
the same title twice replaces it); a damaged file is skipped. Import and export use the system picker (`OpenDocument` /
`CreateDocument`). The tray's Titles tab lists the built-in templates and "My presets"; the title editor saves, exports and deletes them.

**JSON.** `TitleDto.layers` (`TitleLayerDto`: `type` `text`, `shape` or `image`, plus the fields of that type, all optional), absent for plain
titles, so older projects load unchanged and a plain title writes no layers. Unknown types, shapes, alignments and colours are corrupt-data
errors, never guessed.

**Editing on the preview.** With a layer selected, the preview's drag, pinch and twist gestures move, scale and turn that layer
(`EditorIntent.LayerGesture`, part of the title edit session and committed by `EndAppearanceEdit`), and `LayerHandleOverlay` draws a ring
and cross at its centre (plus its outline for a shape).

### 5.27 Filter pack

`FilterPack` (domain) holds 20 looks written for this project, each a `LookParams` made of a few ordered
operations on gamma-encoded RGB: exposure gain, mid-tone gamma, contrast about 0.5, warmth and tint offsets,
saturation, a black-and-white blend, a sepia blend, shadow and highlight tinting weighted by `(1-l)^2` and `l^2`,
and a raised black point; the result is clamped to 0..1. `FilterPack.cube` bakes a look into a 17-point `.cube`
text (red varies fastest), deterministically, so `LutStore.keyOf` gives every look a stable library key. The LUT
picker lists the pack first, with a swatch computed from the same function on six reference colours; choosing a
look calls `LutLibraryViewModel.installFilter`, which imports the generated cube into the LUT library (a no-op if
present) and adds a LUT effect to the clip. From there it is an ordinary LUT: intensity, reorder, keyframes through
the effect parameter tracks, preview and export through the existing LUT path. Properties covered by tests: the
Original look is the identity, every look stays in 0..1, grey is non-decreasing in every channel and in luma, the
black-and-white looks have equal channels, a cube reproduces the look on its lattice, and installing twice stores it
once. The pack contains no third-party data.

### 5.28 Transition pack

`Transition` gains a look (`TransitionType`: crossfade, slide, push, zoom, spin, glitch, wipe, whip pan, light
leak) and a direction (left, right, up, down; used by slide, push, spin, wipe and whip pan). Its length, place and
the clip overlap (5.8) are unchanged, and the audio is the same equal-power crossfade for every look.

The picture is a pure function. `TransitionLooks.modAt(look, incoming, k, canvasW, canvasH, projectFrame)` returns a
`TransitionMod` (offset in canvas pixels, scale, rotation, an opacity factor, optional effects and an optional mask)
for the clip on one side of the transition at frame `k` of it, with eased progress `(k + 0.5) / d`. `RenderClip`
carries the `TransitionLook` of its incoming and outgoing transition; `appearanceAt(frame, canvasW, canvasH)` and
`fxAt(frame, canvasW, canvasH)` apply the mod to the pose and the effect chain, and only a look that
`fadesVideo` (crossfade, light leak) multiplies in the opacity ramp. Looks: slide moves the incoming picture in;
push also moves the old one out; zoom scales both and fades the new one in; spin turns and scales; glitch adds a
fixed-hash jitter, flicker and a contrast/saturation bump; wipe reveals the incoming picture with a soft-edged
rectangle mask; whip pan is a smootherstep push with a blur bell; light leak adds exposure and warmth bells to both
pictures on top of the crossfade. A clip's own mask wins over a wipe's, and extra effects past the limit of 8 are dropped.

Preview: `previewRequestsOnCanvas` evaluates the mods at the playhead frame, so playback re-anchors every tick during
a transition exactly as it does for a crossfade. Export: no native change. For a look that moves the pose,
`buildExportPlan` replaces the clip's native pose keys inside the transition with the composite pose of every frame
(linear keys), adds keys just outside both ends holding the plain pose so the picture settles exactly, and hands the
native fade a length of 0 for a look that does not fade; looks that add effects or a mask send the per-frame effect
list (`fxFrames`). The same functions feed both paths, and a test compares the preview pose with the exported key
frame by frame for every moving look. Limit: where a clip has its own Bezier or eased pose keys inside a transition,
that animation is flattened to linear keys over the transition's frames.

### 5.29 Multicam (WP-M)

A multicam clip lines up two to six recordings of one event and cuts between them. Code: `domain/multicam/`
(`Multicam.kt`, `AudioSync.kt`), `engine/multicam/MulticamServices.kt`, `ui/editor/multicam/`.

- **Shared time.** Every angle (`MulticamAngle`) has an `offsetFrames` in project frames: shared frame `t` is the
  angle's own frame `t - offsetFrames` (0 for the reference, negative if it started earlier) and a `durationFrames`.
  The group (`MulticamClip`) covers shared time `[inFrame, inFrame + lengthFrames)`, sits on the timeline from
  `startFrame`, and holds sorted `cuts` (`AngleCut(frame, angle)`, the first at 0, neighbours never the same angle).
  Only the angle on screen has to cover its stretch.
- **Realised as ordinary clips.** The programme is written to `Timeline.tracks` as one clip per stretch of one angle
  (`mc-<id>-v<n>`, source = `inFrame + cutFrame - offset`) on `videoTrackId`, plus one clip from `audioAngle` for the
  whole length (`mc-<id>-a`) on `audioTrackId`; the picture clips then carry -96 dB so the sound never follows the
  cuts. Preview, export and every other edit therefore work on a flattened timeline, and "Flatten" only forgets the
  group (`FlattenMulticam`). Without an audio lane each angle keeps its own sound.
- **Edits** (`MulticamOps`, all one undo step, all rewriting the realised clips in place over the same stretch so the
  base stays gap free): `create` (on the base: a ripple insert at the nearest cut, the group follows where it landed;
  on another lane the stretch must be free), `cutAt`, `record` (a whole live recording at once), `removeCut`,
  `nudge`, `setOffsets` (a sync result), `setAudioAngle`, `flatten`. A cut that would show an angle where it has no
  media is refused and nothing changes.
- **Staying consistent.** `Timeline.pruned()` calls `MulticamOps.settle`: a group whose realised clips moved together
  (a ripple, a block drag, a group move) follows them (`startFrame` is re-read from the first clip); one that was
  edited clip by clip (split, trim, retime, a deleted piece) is forgotten and its clips carry on as ordinary clips.
  Styling a clip (gain, transform, effects) keeps the group.
- **Sync** (`AudioSync.offsetOf`, no learning): the loudness envelopes the waveform cache already holds are brought to
  100 Hz, log-compressed and centred; a coarse pass (1/8 rate) correlates every offset through one FFT scored by
  normalised correlation with a minimum overlap, a fine pass searches +-2 coarse steps at full rate, and the offset is
  converted to project frames with exact integer rounding (`stepsToFrames`). `SyncResult.ncc` is the correlation at
  the winner and `confidence` is that minus the best rival peak more than a second away; below
  `AudioSync.MIN_CONFIDENCE` (0.15) the offset is not applied and the user nudges by ear. Resolution is 10 ms, so the
  result is within one frame at 60 fps and below.
- **Viewer budget** (`MulticamPlanner`): the active angle is `FULL` (the one the preview decodes), other angles are
  `PROXY` while a proxy is ready and a decoder is spare (`maxDecoders - 1`), else `STILL`.
- **JSON.** `ProjectDto.multicams` (optional; old projects load with none): angles, audio angle, lane ids, start, in,
  length and cuts. A stored group whose clips are missing is a corrupt project.
- **UI.** A toolbar button opens the Multicam sheet: pick 2 to 6 library files, "Sync by sound", nudge, "Create at
  playhead"; then angle buttons that cut at the playhead (with live / proxy / still badges), "Record cuts" (taps are
  collected with their playhead frame and applied as one undo step), "Remove cut here", the audio angle, fine sync,
  "Sync again" and "Flatten".
- **Not in this version:** the grid does not show moving pictures of the other angles (the badges show how each would
  be fed), and a multicam clip is always created on the base.

### 5.30 Project templates

`ProjectTemplate` (domain) is a project without media: width, height, frame rate, colour space, a `Timeline` and a list of
`Placeholder`s. A placeholder has an id, a name, a kind (video, video or photo, photo, audio), a length in project frames,
a minimum, and an `optional` flag; the timeline clip that stands for it carries the asset id `slot:<placeholderId>`
(`Placeholder.idOfSlotAsset`). Slot clips pretend to have `TemplateBuilder.SLOT_HANDLE_FRAMES` (60) of footage before their
in point, so transitions around them are valid in the template.

`TemplateInstantiator.instantiate(template, fills)` returns a timeline plus the assets it uses and warnings, or an error:
for each slot in timeline order, no fill means remove an optional slot with `ClipDeletion.delete` (the gap closes, overlays
follow) or fail on a required one; the fill is checked against the kind; a video is placed at the chosen start (bumped to
the incoming transition's lead when the file can afford it) with the slot's length, then shortened with
`MagneticBase.trim` when the file is shorter (so what follows moves up); a photo becomes a still of the slot's length; a
picture whose size is known is centre-cropped to the canvas with `Reframe.poseFor`; finally the template's transitions are
put back with `TimelineOps.addTransition`, shortened to `maxTransitionFrames` and dropped with a warning when even the
shortest does not fit.

`TemplateBuilder.fromProject` goes the other way: every media clip becomes a placeholder of its length at its place (kind
from the asset, minimum half the length), keeping effects, grades, keyframes, titles, stickers, tracks, transitions that
stay valid, and manual markers, and dropping speed changes, stabilisation, motion tracks and multicam links.

`TemplateFile` reads and writes the `.uvtemplate` document: `format`, `version`, `id`, `name`, `description`, the same
`project` object `project.json` uses (with an empty media library) and the placeholders. `decode` refuses a file that is
too big, not a template, newer than this build, holds media, has a clip that plays media but is not a slot, has an unknown
placeholder kind or fails `ProjectTemplate.problem()`. `TemplateStore` keeps the user's templates as files under the app's
`templates` folder (atomic writes, free ids, broken files skipped) and lists the built-in ones first. The built-in starters
(`BuiltInTemplates`) are built in code.

UI: `TemplateWizardViewModel` and `TemplateWizardSheet` (reached from the hub's top-bar menu): the list of templates, then one
row per slot with a picker, a live preview of what fitting will do (warnings, or why it cannot be done yet) and Create, which
creates the project through `ProjectRepository` and opens it; the sheet also saves a project as a template, shares a
template as a file and imports one. All files come from the system picker; nothing touches the network.

### 5.31 Proxy media (WP-P)

Heavy footage (4K, long GOP, high bitrate) is edited through small stand-in files; export never uses them.

- **Proxy file.** A one-clip movie made by the offline export engine (`ProxyGenerator` -> `NativeExportRunner`): the
  source's own frame rate and length (so every timeline frame maps to the same frame of the proxy), scaled so the
  short side is 720 or 1080 (never larger than the source, even sides, same aspect and display rotation), H.264,
  about 0.15 bits per pixel (2-40 Mbps), a keyframe every second (the engine's setting), **no audio** (sound is
  always read from the original). HDR sources are tone-mapped to SDR; the preview reads a proxy as SDR Rec.709
  (`PreviewRequest.sourceOverride = 0`), so HDR looks flatter while editing with proxies.
- **Side index** (`proxy/ProxyIndex`): `cacheDir/proxies/index.json` plus `<key>.mp4`; never in `project.json`, so
  bundles and interchange files are unaffected. The key is a hash of source uri, length, frame rate and proxy size
  (a relinked or re-proxied asset gets a new key). Entries carry their own job (uri, length, rate, colour space), so a
  restarted process resumes the queue without any project open. States: QUEUED, RUNNING, READY, STALE, FAILED.
  Atomic writes; a damaged index reads as empty; `recoverAfterKill` puts RUNNING back in the queue (part file
  deleted, the job restarts from the beginning), drops READY entries whose file is missing or the wrong size, and
  removes files nothing refers to.
- **Queue** (`ProxyWorker`): one job at a time on one background-priority thread, outcomes kept in the index,
  cache held to the budget after each job (least recently used first; proxies of the open project and anything
  queued or running are kept), cancel for the running or a queued job.
- **Choosing the file** (`ProxyPlanner`, pure): `MediaPurpose.PREVIEW` and `THUMBNAIL` may use a proxy, only when the
  project switch is on, the asset is decoded video and the proxy is READY with its file present; `ANALYSIS`
  (waveforms, beats, loudness) and `EXPORT` always get the original. Export code never references the proxy
  package (a test scans for it). A proxy that fails to open in the preview is marked STALE and the original is shown.
- **Preview.** `previewRequestsWithSources` builds the same requests as `previewRequestsAt` with a per-asset source;
  `EditorPreview` reopens an asset when the file it was opened from changes (switch flipped, proxy finished).
- **Per-project switch and settings** live in local preferences (`ProxyPrefs`): switch and dismissed suggestion per
  project id, proxy size, storage budget (1-16 GB, default 4 GB).
- **Suggestion** (`ProxySuggester`): while proxies are off and the offer has not been dismissed, heavy video (short
  side >= 1440 or >= 50 Mbps) without a proxy, or three preview stalls, raises a dismissible banner. Nothing is made
  without the user's consent.
- **UI**: a toolbar button opens the proxy sheet (switch, size, make/cancel/remove per video, storage and budget,
  clear cache with confirmation); proxy badges on the media tray tiles and in the library rows.
- **Validation** (`ProxyManager.validate`): a proxy whose source changed size, or whose file is gone, becomes STALE.

### 5.32 Appearance: dark only, optional pure black

The app has one look, dark. There is no light scheme and no dynamic (wallpaper) colour: `ui/theme/Palette.kt` holds every colour as
a plain ARGB `Int` (`Palette.Dark`, `Palette.Amoled` = the same with black backgrounds), `Theme.kt` builds the Material 3
`darkColorScheme` from it (`paletteColorScheme`) and provides it as `LocalPalette`. `PreferencesAppearanceStore` keeps the one choice
(`amoled`, SharedPreferences `appearance`, device only) in Compose state, so changing it recomposes `UVEditorTheme` and everything under it
at once, with no activity restart. `MainActivity` also sets the window background from it before the first frame (no grey flash on a
black theme), draws the system bars transparent with light icons (`SystemBarStyle.dark`), and `Theme.UVEditor` (themes.xml) has the dark
window background, light-icon bars and a dark splash background.
**One source of truth.** The native renderers do not keep a second palette: `Palette.nativeColours()` is a flat list of
`NATIVE_COLOUR_COUNT` (22) ARGB values in a fixed order (background, lane A/B, ruler, tick, ruler text, six block colours, on-block text,
playhead, selection, keyframe, marker, header tab, on-surface, on-surface-variant, primary, error) that `EditorScreen` pushes through
`TimelineEngine.setPalette` (JNI `nativeSetPalette`) whenever the palette changes; `timeline_theme.h` reads them in the same order and
ignores a list of another length (the defaults, which equal `Palette.Dark`, stay). Colours that carry their own meaning on any theme
(drop-target amber / orange / green / red, the missing-media red veil, transition bands) stay constants in the renderer.
**Contrast** is checked by `PaletteTest` (WCAG 2.x maths over ARGB): 4.5:1 for text and 3:1 for graphics, for every on-colour pair, text on
all six surface steps, block text on each block colour and on its darker header strip, and playhead, selection, keyframe and marker against the
lanes and the ruler, in both variants. The AMOLED variant keeps the surface steps ordered lighter so cards still separate from the black.

### 5.33 Timeline canvas rendering: text atlas, ruler, blocks

**Text.** The canvas used to draw all text with a 3x5 pixel font scaled up (capital ASCII only, blocky on a 2800 px tablet). Text is now
bitmaps made by Kotlin with the system typeface (`AndroidLabelRasteriser`: `Paint` + `Canvas`, anti-aliased, dp x density sizes, system
fallback fonts for accents, scripts, symbols and emoji, white on transparent; a bitmap with colour of its own is flagged and drawn
untinted). Three size classes: 0 regular 11 dp, 1 small 9.5 dp, 2 bold 11 dp. The sides agree on a bitmap by a 64-bit FNV-1a hash of
`size class byte + UTF-8` (`LabelHash.of` / `labelHash` in `text_atlas.h`, one shared test vector in both test suites), so only pixels
cross the JNI (`nativeLabelPut`, a copy; callable from any thread). `LabelPump` (Kotlin, one background thread) is told what the
snapshot needs (`labelNeeds()`: every label text with its class, plus `StaticGlyphs`: digits, `:+.-x<|`, `VATMS` for the ruler, lane
names, speed badges, timecode and M/S chips), rasterises what the canvas does not have and sends it once; only the latest request
is kept while it is busy. The render thread takes queued bitmaps at the start of a frame, at most 512 KB per frame (at least one), places
them with a shelf packer (`ShelfPacker`, one pixel gutter) in a 2048x1024 RGBA atlas (`LabelTable`, 8 MB, NEAREST, created once and kept for the
life of the context) and uploads them with `glTexSubImage2D`. When the atlas is full it is emptied (`LabelTable::reset`) and a generation
counter moves on (`nativeLabelGeneration`); `LabelPump` sees it and sends again what is still needed. At most 700 distinct labels per generation.
A label is drawn as one quad snapped to whole pixels; a run of single glyphs (ruler, lane name, timecode) is one quad per glyph. Text is
queued in its own vertex array (position, uv, colour; a negative alpha marks a coloured glyph) and drawn by a text program that outputs
premultiplied colour: two passes per frame (blocks and lane names, then ruler, markers and the playhead tag), so text is **2 extra draw
calls** per frame. Until a bitmap has arrived, plain ASCII falls back to the old font and any other text waits (no blocking, no hitch).
**Snapshot version 8.** Same layout as 7; the per-clip flags gain bits 4..6 = `ClipKind` (0 by lane type, 1 image, 2 sticker, 3 multicam;
photos, stickers and multicam clips get their own colour) and a label is UTF-8, 0..96 bytes, validated (`utf8Valid`); versions 2 to 7 still
parse (v7 labels stay ASCII, at most 24 bytes). `ClipLabels` (Kotlin) tidies text (control characters and blank runs become one space) and cuts it at 32
characters / 96 bytes on a grapheme boundary with an ellipsis; media and photo clips now send their file name without the extension.
**Ruler** (`ruler_ticks.h`, host-tested). `planRuler(fps, pxPerFrame, 72 dp, 7 dp)` picks the smallest round step (1, 2, 5, 10 frames, then 1,
2, 5, 10, 15, 30 s, 1, 2, 5, 10, 30 min, 1, 2, 6, 24 h) with at least 72 dp between labelled ticks, and 5, 4, 3 or 2 small ticks per step
when they are at least 7 dp apart. Labels `m:ss`, `h:mm:ss`, and `m:ss:ff` below one second per step; the playhead tag shows `m:ss:ff`. The tick
before the left edge is drawn too, so its label reaches into view. Ticks and labels sit on whole pixels.
**Blocks.** A block is drawn on a plain rectangle and its four corners are then cut back with small triangle fans in the colour behind the
block (3 segments, 4 dp; the sagitta error is under 0.4 px at 2.6x), so thumbnails and waveforms need no rounding of their own and the number of
draw calls does not change. A selected block gets a rounded outline fill under the body (primary: the selection colour, 2 dp; others: the
primary colour, 1.5 dp) and, for the primary clip, a rounded handle pill at each end. The 18 dp header strip (darker, with a highlight
line) carries the name, speed label, keyframe diamonds and fx badge; titles and stickers put their text in the body. The waveform gets a
vertical shading and a centre line under it when there are no thumbnails. Lane bands have one-pixel edges in the ruler colour; lane headers
have a stripe in the lane's colour, bold name and M/S chips. All sizes are dp x density.
**Cost.** Everything stays in the batched coloured quads plus the thumbnail and text draws; per-frame allocations are none (the vertex vectors and
the upload list are reused). The renderer can print its own frame statistics: `setprop debug.uveditor.timeline_stats 1` before opening the
editor, then `logcat -s uv_timeline` shows every 240 frames the CPU milliseconds to build and submit a frame (up to, not including, the buffer
swap) as p50 / p95 / p99 / max plus the draw calls and vertices per frame; `scripts/perf-timeline.sh` drives a 110-clip project
(`scripts/make-perf-project.py`) and prints them. Numbers are in DECISIONS.md.

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
owns (to avoid conflicts when two packages run in parallel). **Privacy rule (hard):** no AI or ML features, no models, no network access, no third-party services, no
analytics. Packages below that would need any of these were removed or rewritten with classical on-device
algorithms (see DECISIONS.md, "Privacy"). Existing conventions apply to all of them:
integer frames only, MVI, domain in pure Kotlin with unit tests, preview and export through the same
`RenderPlan` / `drawScene` / offline mixer, optional JSON fields (old projects must load unchanged), one
undo step per user action, explicit typed errors, decisions logged in `DECISIONS.md`.

### 9.0 Queue and parallelism

> The table below was the first draft; the current queue, which adds the interface and CapCut-style
> packages (sections 9.12 to 9.19), is in **9.20**.

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
  noise suppression with classical DSP only (spectral gating with a noise profile taken from a quiet
  stretch, or a Wiener filter; no neural denoiser), loudness normalise to a target LUFS (BS.1770 measured offline and cached, applied
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
  release CI job that builds an unsigned AAB and APK as artifacts, versioning scheme, crash information as local
  logs only (no reporting service, no network), a privacy note pointing to `docs/PRIVACY.md`, Play listing text and screenshots checklist in `docs/`, and a first-run onboarding that points to
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

### 9.12 WP-U1 New-project flow and a simpler hub (first in the queue)

**Problem.** The creation dialog shows many buttons at once (resolutions, rates, colour spaces as chips),
which is hard to read. Target: a handful of selectors, sensible defaults, one primary button.

**New project sheet (Compose, bottom sheet on phones, dialog on tablets).**
- A **Name** field (prefilled "Project <date>" or "New project 2" if taken).
- A row of **quick presets** as a single horizontally scrolling chip row: "YouTube 1080p30", "YouTube 4K30",
  "TikTok / Reels / Shorts 9:16", "Instagram 4:5", "Square 1:1", "Cinema 24p", "Match first clip". One tap
  fills the selectors below; the selectors stay editable.
- Four **dropdown selectors** (`ExposedDropdownMenuBox`), each showing only its current value:
  1. **Aspect ratio**: 16:9, 9:16, 1:1, 4:5, 4:3, 21:9, Custom.
  2. **Resolution**: 720p, 1080p, 1440p, 4K (2160p), Custom; the labels adapt to the aspect ratio
     (the "p" value is the short side), and the exact pixels are shown as a caption ("1920 x 1080").
  3. **Frame rate**: 23.976, 24, 25, 29.97, 30, 50, 59.94, 60, 120 fps (rational values kept exact).
  4. **Colour space**: Rec.709 SDR (default), Rec.2020 HLG (HDR). A small info icon explains that each clip
     is converted to this space individually (see per-clip colour space).
- A one-line **summary** ("1920 x 1080, 30 fps, SDR") and a small **aspect preview** rectangle.
- **Start from**: a segmented control with "Blank" and "Match first clip" (the project takes size, frame
  rate and colour space from the first imported clip, like LumaFusion and CapCut); "Template" appears once
  WP-V5 exists.
- The last used choices are remembered (preferences) and offered as the default.
- One primary button **Create**. Advanced values (custom size, odd frame rates) live behind the Custom entries.

**Hub clean-up.**
- One floating **New project** button; **Import** moves into an overflow menu with Settings and Help.
- Each project card shows a thumbnail (first frame of the first clip, cached), name, "1080p, 30 fps, SDR",
  duration and last change; actions in one overflow menu (rename, duplicate, export file, delete); search
  field and sort (recent, name) when the list is longer than a screen.
- The relink, recover and reopen banners keep working, in a single notice area.

**Tests.** ViewModel tests for preset application, aspect x resolution to pixel mapping (all combinations,
even dimensions), "Match first clip" from probed metadata, remembered choices, name uniqueness; screenshot
checklist in the PR.

**Acceptance.** Creating a 9:16 1080p 30 fps project takes three taps and no scrolling in the dialog.

**Owns:** `ui/hub/`, `data/` settings store. Avoid: editor, render, audio.

### 9.13 WP-U2 Media tray with drag and drop

**Problem.** Adding media today means the + button and a system picker, then guessing where it lands.

**Tray.**
- A persistent **media tray** in the editor: a bottom panel on phones (collapsible, snap heights), a side
  panel on wide windows. Tabs: **Media** (project assets), **Stickers**, **Titles/Templates**, **Audio**
  (project audio assets). The existing sheets (stickers, templates) open as tray tabs instead of modal sheets.
- Media tab: grid or list toggle, thumbnails (reuse the thumbnail cache), duration badge, colour-space badge
  (SDR/HLG), usage badge (count in the timeline), a "missing" overlay for unreadable media, search and
  filters (video, photo, audio, unused). An **Import** tile at the start opens the system picker and adds the
  files to the tray without touching the timeline.
- Long-press an asset to enter drag; tap adds it at the playhead as today (to the selected lane or the base).
- Multi-select (checkbox mode) and dragging several assets at once becomes available with WP-S.

**Drag and drop to the timeline.**
- Use the platform drag-and-drop (`startDragAndDrop` with a `ClipData` carrying the asset id, plus a
  shadow of the thumbnail) so the native timeline `SurfaceView` receives `DragEvent`s with coordinates.
- While hovering, the same decision as clip drags runs for a clip that is not on the timeline yet
  (`DropPlan.decideNew(asset, requestedStart, target)`): on the base near a junction -> **Insert**, over a
  clip -> **Overwrite**; on overlays -> overwrite or free placement; above the top lane -> new lane; outside
  -> cancel. The native indicator draws the hint; the tray shows the dragged asset's ghost near the finger.
- Auto-scroll of the timeline near its edges while dragging, snapping to the playhead and markers, one undo
  step on drop. Audio-only assets can only land on audio lanes; photos default to 5 s.
- **Drop from other apps** (tablets, split screen, desktop windowing): accept `video/*`, `image/*`, `audio/*`
  content URIs dragged from the Files app onto the tray or the timeline (take persistable permission when
  offered, probe like a normal import).
- **Reorder in the tray**: drag assets within the tray to sort (stored as an optional order list).

**Tests.** `DropPlan.decideNew` over all zones, ViewModel tests for tray drop intents, undo, audio-only and
photo rules, import without placement; host tests for drag-hit geometry.

**Acceptance.** Drag a clip from the tray onto the junction of two base clips: the insert marker shows, release
inserts, undo removes it; drag onto an empty overlay area creates an overlay clip at that time.

**Owns:** `ui/editor/tray/`, `domain/DropPlan.kt` additions, `timeline_view/` drag-hover bridge. Avoid:
render/, audio/. Run after WP-U1 and before WP-U3.

**Implementation notes (as built).** `ui/editor/tray/` holds `TrayModel.kt` (tabs, filters, search, usage counts,
library reordering: pure, tested), `MediaTray.kt` (Compose panel, tiles, header with snap heights),
`AssetThumbnails.kt` and `DragPayload.kt`. The native canvas view implements `TimelineDropTarget` through the
platform `DragEvent` listener and forwards positions as `TrayDragMove` / `ExternalDrop` intents;
`DropPlan.decideNew` plans the drop and `LaneOps.addClipOnNewLane` / `overwriteNewClip` apply it. Tray
payloads use the clip label `uveditor-asset` with the asset id as text. See the decisions in `DECISIONS.md`
for what was left out (drag of stickers/templates, a native "place" indicator).

### 9.14 WP-U3 Resizable and customisable layout

**Goal:** the user shapes the workspace, like LumaFusion.

- **Dividers:** draggable splitters between preview and timeline (vertical), and between the preview and the
  side panel (horizontal) on wide windows; minimum and maximum sizes; double-tap a divider to reset; haptic tick
  at the default position.
- **Track height (done; see the pinch and lane zoom notes in the timeline UI section above):** per-timeline vertical zoom (pinch with two fingers vertically or a +/- control) and a
  choice of Small / Medium / Large lane heights; waveforms, thumbnails and keyframe diamonds scale.
- **Panels:** the tray, inspector and scopes are dockable panels that can sit at the bottom, left or right
  (on wide windows), collapsed to an edge handle, or floating on tablets (stretch goal); full-screen preview
  toggle already exists.
- **Layout presets:** Default, Timeline focus (big timeline, small preview), Preview focus, Two panels (tablet),
  plus "Customise layout" mode that shows handles and lets the user drag panels between docks; "Reset layout".
- **Persistence:** stored per window size class and orientation in preferences (DataStore), restored on open,
  clamped when the window changes (foldables, split screen, rotation).
- **Implementation:** a `LayoutState` (pure Kotlin, reducer-tested) holding split fractions, dock assignments
  and lane height; Compose layout using the state; the native views only receive their new size. Resize drags
  must not recompose the timeline: only the container weights change, and the surfaces resize once the drag
  ends or at a throttled rate.

**Tests.** `LayoutState` reducer (clamping, presets, reset), persistence round trips, window-size
transitions; a manual checklist for devices.

**Acceptance.** Drag the divider to make the timeline taller; rotate and reopen: the layout restores for each
orientation; "Reset layout" returns to the default.

**Owns:** `ui/editor/layout/`, preferences store, `EditorScreen` structure. Avoid: domain/, audio/, render/.
Because it restructures `EditorScreen`, run it as the only editor-structure package in its wave.

### 9.15 WP-V1 Motion tracking (classical)

Smart cutout (ML segmentation) was removed: it needs a model. The existing chroma key, luma key and masks cover
keying; manual mask keyframes (WP-K) cover moving subjects.

- **Motion tracking:** track a user-chosen point or box (pyramidal Lucas-Kanade or a small template matcher; reuse the
  tracker code of WP-X), store a per-frame path; "attach" a title, sticker or clip to the path (generates position
  keyframes through WP-K, or a dedicated `TrackedPose` evaluated by `RenderPlan` if WP-K is not ready).
- **UI:** a "Track" action in the inspector with progress and cancel, status when stale.
- **Tests:** synthetic moving square tracking error bounds, path cache keys, attach maths.
- **Acceptance:** a title attached to a moving object follows it within a few pixels over a 10 s clip.

### 9.16 WP-V2 Auto cut and manual reframe helper

Subject-detecting auto reframe was removed: it needs a model.

- **Auto cut:** silence removal (threshold, minimum gap, padding) from the waveform cache producing cuts on the
  base with ripple; "highlights" as a stretch goal using audio energy and scene changes (classical frame differences);
  a preview of the cuts before applying; one undo step.
- **Manual reframe helper:** when the canvas aspect ratio changes, offer a centred crop and a two-point "pan"
  (start and end framing set by the user on the preview) that writes position/scale keyframes on the clip, editable
  afterwards (WP-K).
- **Tests:** silence detection on synthetic audio, cut application and undo, pan-keyframe generation and bounds.
- **Acceptance:** silence removal shortens a clip with long pauses and keeps A/V sync; a 16:9 clip can be reframed to
  9:16 with a start and an end framing in three taps.

### 9.17 WP-V3 Voice effects

Text to speech, vocal isolation and speaker-aware captions were removed: they need a model or a speech engine
that may reach a server (the Android system `TextToSpeech` service can use network voices, so it is dropped for
strictness; see DECISIONS.md, "Privacy").

- **Voice effects:** pitch/formant shift (WSOLA or phase vocoder), robot, chipmunk, deep, echo and reverb presets as
  audio clip effects in the WP-A chain; all classical DSP.
- **Tests:** effect DSP vectors (pitch ratio, delay times), parity between realtime and offline mixing.
- **Acceptance:** a voice clip with the "deep" preset sounds lower and exports identically.

**Implemented (host-verified, not yet heard on a device).**
- Native `audio/voice_fx.{h,cpp}`: a `VoiceProcessor` per clip in the decode worker, after the noise suppressor, input-aligned like
  `SpectralDenoiser` (process/flush/reset; the 1536-sample latency of the spectral stage is hidden by priming). Chain order:
  spectral stage (pitch shift of the excitation, independent formant shift, whisper) -> ring modulation -> band limit and
  soft-clip drive -> echo / short comb -> Schroeder reverb. All state advances per input sample, so the output does not
  depend on how the stream is cut into calls or blocks (realtime, offline and export are identical, tested).
- Pitch and formant: STFT (2048, hop 512, Hann) phase vocoder with spectral peaks moved rigidly and phase-locked to
  their analysis phases (identity phase locking), so a shifted sinusoid keeps its level (within 1 dB) and pitch (0.0
  cents measured). The formant control splits the spectrum into a cepstrally smoothed envelope and a flattened
  excitation; the excitation moves by the pitch ratio and the envelope by the formant ratio. When both shifts are
  equal the envelope split is skipped (plain shifting).
- Whisper uses the smooth envelope with a fresh random phase per frame (seeded, deterministic), scaled to the input
  energy, so the harmonics disappear (autocorrelation at the pitch lag falls from 1.0 to 0.0).
- Echo and reverb tails: when the media ends before the clip, the worker keeps feeding silence in chunks
  (`ClipSource::drainLeft`) so the tail reaches the buffer progressively, never beyond the clip's end or 8 s.
- Snapshot version 6 adds one 64-byte voice block per clip after the automation lanes (16 floats, see
  `audio_snapshot.h`); versions 4 and 5 still parse. Kotlin: `domain/AudioTools.kt` (`VoicePreset`, `VoiceFx`,
  `VoiceParams`), `ClipAudio.voice`, JSON `voice: {preset, values}` (optional), `engine/audio/VoiceSpec`,
  inspector subsection in `AudioControls.kt`.
- Not keyframable (changing a value restarts decoding of the clip); the effect belongs to the clip, so a split keeps it on both halves.

### 9.18 WP-V4 Optical-flow slow motion, video denoise and deflicker

- **Smooth slow motion:** frame interpolation for speeds below 1x (GPU optical flow at reduced resolution,
  block matching plus bidirectional warping and blending, with fallbacks to frame blending when the motion is
  too large); applied in preview at a quality level that holds the frame budget and in full quality at export.
- **Speed curves:** a graphical editor for the existing speed ramps (Bezier handles, presets like "montage",
  "hero", "bullet"), speed limit raised to 100x with skip-decode rules.
- **Video noise reduction:** temporal-spatial denoise as an effect (GPU), strength slider.
- **Flicker removal:** per-frame luminance normalisation with a smoothing window.
- **Tests:** warping/blending maths with synthetic motion, speed-curve evaluation, denoise reference.
- **Acceptance:** a 240 fps or 60 fps clip slowed to 0.25x looks smoother than frame repetition in a side by
  side export.

**Implementation notes (as built).**
- *Retiming:* `ClipRetime.mixAt` returns the source frame and a per-mille mix towards its neighbour (the next frame, or the
  previous one for reversed clips). Speed keys may be `smooth` (eased with smoothstep, closed-form integral); presets
  `montage`, `hero`, `bullet` and eased in/out. `SpeedLimits.MAX = 100`. A clip above 1x asks for frames far apart, and the
  decoders' `needsSeek` policy (`decode/seek_policy.h`) seeks instead of decoding through any forward gap over 120 frames, so
  at 100x only the needed frames are decoded.
- *Interpolation:* block-matching flow (3x3 SAD, radius 6 in preview / 8 in export, flow grid 160 / 320 px wide, parabolic
  sub-pixel refinement, small displacement penalty), confidence by smoothstep of the SAD gap, bidirectional warp with a second
  flow lookup at the source of the content, and plain blending where confidence is low or at the border. CPU reference in
  `render/repair_math.h` (host tests `repair_host_tests`), GPU in `render/gl_repair.cpp` and `shaders.h`. Preview uses a 4-entry
  flow cache and falls back to blending when the frame budget is missed; export uses full quality.
- *Repair effects:* `DENOISE` (code 16: strength, temporal amount) is a 5x5 bilateral filter plus a temporal mix of the
  spatially smoothed previous frame, faded where there is motion (max 0.5). `DEFLICKER` (code 17: strength) divides out the
  difference between the frame's mean luma and the mean over a 3-frame window (gain clamped 0.5-2). Both run first in the
  effect chain and are keyframable through `Clip.params`.
- *Export table:* an entry is `frame | (mixPermille << 44)`; plain frames keep the old encoding.
- *Speed curve editor:* `SpeedCurveModel` (pure) and `SpeedCurveEditor` (Compose): integer-frame points, drag, add, remove,
  smooth or hold per point, presets.
- *Measured on a Pixel 8* (`scripts/check-slowmo-export.sh`, `check-repair-export.sh`; 60 fps synthetic texture scrolling
  diagonally, 0.25x, ground truth rendered at 240 fps): frame repetition PSNR 35.9 dB, SSIM 0.982, judder 1.71; interpolation
  PSNR 43.9 dB, SSIM 0.991, judder 0.13; export 1.55 s against 1.76 s for 60 frames at 720p. Noisy clip with +-6 % flicker: PSNR
  to the clean clip 24.8 dB (luma) without repair and 29.7 dB with it; frame-to-frame mean-luma step 28.0 down to 9.2 (clean
  0.01). Chroma PSNR falls from 39 to 32 dB with repair (the bilateral filter softens chroma noise less than luma noise and the
  temporal term smears it slightly): a known cost.
- *Not verified:* the inspector and curve editor on a device, the reference phone, 4K, real footage, preview GPU timing under
  playback (only the preview picture is compared with ground truth by `check-slowmo-preview.sh`).

### 9.19 WP-V5 Project templates and content packs

- **Project templates:** a template is a project with **placeholders** (named, typed, duration bounds); "Use
  template" asks for media for each placeholder, fits clips (center crop, trim to duration), keeps titles,
  effects, transitions and music slots; save the current project as a template (strips media, keeps structure);
  import/export `.uvtemplate` through SAF; a small built-in starter set.
- **Transition pack:** slide, zoom, spin, glitch, wipe, whip pan and light leak as shader transitions on top
  of the crossfade infrastructure (preview and export parity), plus a transition picker with previews.
- **Filter pack:** 20 LUT-based looks (original, licence-clean, `.cube` generated by us) with thumbnails.
- **Music and sound effects:** no bundled commercial library; "bring your own" audio plus a documented list of
  free-licence sources; a local sound-effects mini pack only if originals can be generated (tones, whooshes,
  clicks) with a clear licence.
- **Tests:** placeholder fitting maths, template round trip, transition parity tests, LUT sanity checks.
- **Acceptance:** a user creates a 15 s vertical montage from a template by choosing five clips.

### 9.20 Updated queue (supersedes the table in 9.0 where they differ)

| Wave | Package A | Package B |
|---|---|---|
| 0 | WP-U1 New-project flow and hub | WP-U2 Media tray with drag and drop |
| 1 | WP-U3 Resizable layout | WP-C Colour tools and scopes |
| 2 | WP-S Multiselect and bulk edits | WP-A Audio tools |
| 3 | WP-T Multilayer titles and fonts | WP-K Generalised keyframes |
| 4 | WP-X Stabiliser | WP-I Interchange and media library |
| 5 | WP-V1 Motion tracking | WP-V4 Optical-flow slow motion and repair |
| 6 | WP-V2 Auto cut and reframe helper | WP-V3 Voice effects |
| 7 | WP-P Proxy media | WP-V5 Templates and packs |
| 8 | WP-M Multicam | WP-R Release preparation |

Rationale: usability first (the user reported the creation flow and the lack of a tray), then colour in
parallel with the layout work (different files), then editing and audio depth, then CapCut-style creator
tools. WP-X (wave 4) builds the tracker and smoother; WP-V1 and WP-V2 reuse that code (record it in
`DECISIONS.md`).

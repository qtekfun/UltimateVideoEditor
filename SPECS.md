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
  Missing media must open the project in a "relink" state, not crash.

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
- Project colour space (MVP: Rec.709 SDR). Per-clip source colour space with optional override.
- GLSL fragment stage converts source → project space: YUV→RGB matrix selection, transfer function
  linearisation (HLG OETF⁻¹), gamut matrix Rec.2020→Rec.709, tone-mapping, re-encode. No 3D LUTs.

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
- **Wire formats.** Timeline snapshot version 2 appends the transitions (for the canvas markers); audio
  snapshot version 2 has 64-byte clips with `fadeInFrames`/`fadeOutFrames`.

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
- FFmpeg (static, NDK) is an optional later fallback for formats not supported by MediaCodec.

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

Invariants: sorted by `timelineStartFrame`, no overlaps on a track, durations > 0, all values integers.

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

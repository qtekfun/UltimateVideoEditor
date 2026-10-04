# ultimateVE architecture

A one-page tour for someone who is about to change the code. The authoritative detail lives in
[SPECS.md](../SPECS.md) (section numbers are quoted below), the reasons for choices in
[DECISIONS.md](../DECISIONS.md), the engineering rules in [CLAUDE.md](../CLAUDE.md).

## 1. The shape of the app

```
 Compose UI (ui/)  ──Intent──▶  ViewModel (MVI)  ──snapshot──▶  EngineClient (engine/)  ──JNI──▶  C++ engine (cpp/)
   hub, editor, sheets          State / Effect                   the only JNI callers                decode, render, audio,
   never draws clips            domain/ model + undo                                                  encode, timeline canvas
```

- **`ui/`** Compose screens: hub, editor (toolbar, inspector, tray, layout, sheets), export, library, templates, about.
  Compose never draws timeline clips and never decodes anything. The editor hosts two `SurfaceView`s that C++ draws into: the
  **preview** and the **timeline canvas** (SPECS 5.3).
- **`mvi/`** `State` (immutable) / `Intent` (sealed) / `Effect` (one-shot) over `StateFlow`; reducers are pure (SPECS 5.1).
- **`domain/`** Pure Kotlin, no Android imports: `Timeline`, `Track`, `Clip`, all edit operations (`TimelineOps`, `MagneticBase`,
  `ClipDeletion`, `LaneOps`, `GroupOps`, `DropPlan`), `EditHistory` (undo/redo, one step per user action), keyframes and parameter
  tracks, retiming, effects, grade, titles, captions, markers, multicam, templates, and the **render plan**. This is where the
  collision and gap tests live.
- **`data/`** `project.json` DTOs and mapping to/from the domain (`TimelineMapper`), `ProjectRepository` (atomic writes, `.bak`,
  recovery), media import and probing, relink, interchange (bundle, EDL, FCPXML), preferences stores.
- **`engine/`** Kotlin facades over native code (`preview`, `timeline`, `audio`, `export`, `stabilise`, `track`, `still`, `title`, `fx`,
  `multicam`). Nothing else calls JNI, and no logic lives in the native method declarations.
- **`proxy/`, `crash/`** proxy media manager; local crash report.
- **`app/src/main/cpp/`** the C++20 engine (library `uveditor_engine`): `core/` (errors, versions), `decode/` (AMediaCodec decode and the
  seek policy), `cache/` (LRU frame cache), `render/` (GLES compositor, shaders, colour, effects, scopes), `encode/` (export),
  `audio/` (Oboe, mixer, DSP, waveform), `thumbnail/`, `timeline_view/` (the timeline renderer), `stabilise/`, `track/`, `jni/`
  (bindings only). Host-buildable tests live in `cpp/tests/` and run with `scripts/run-native-tests.sh`.

## 2. Data flow

1. A gesture or button becomes an `EditorIntent`. The `EditorViewModel` turns it into an `EditCommand`.
2. `EditHistory.execute(command)` applies a pure function `Timeline -> EditResult<Timeline>`. Failure returns a typed `EditError`
   that the UI shows as a message; success pushes one undo step.
3. The new `Timeline` is saved (debounced, atomic) through `ProjectRepository`, and published to the engine:
   - the **timeline snapshot** (`TimelineSnapshot`, version 7) goes to the timeline canvas: clip rectangles, lanes, selection,
     markers, labels, keyframe diamonds, thumbnails and waveform keys. Hit testing against it is native;
   - the **audio snapshot** (`AudioSnapshot`, version 5) goes to the mixer: clip placement in project frames, gain, pan, fades, EQ,
     ducking, parameter automation;
   - the **preview scene** goes to the compositor: the canvas plus layers bottom to top.
4. Drags are previews: the ViewModel evaluates the same command on a copy (`dragPreview`) and commits one command on release.

### Preview and export parity

Preview, export and audio all consume `domain/RenderPlan.kt`. It resolves, for any project frame, which layers exist, which source
frame each one shows (`Retime`), transform, opacity, effects (parameter tracks evaluated by `fxAt`), transitions (`TransitionLook`),
titles and stills (rasterised through the same rasteriser), and the audio mix. The compositor draws it with
`GlPipeline::drawScene(...)`, which composites into whichever framebuffer is bound; export binds an offscreen target of the export
size and feeds the encoder, so what you see is what is exported. Shaders and CPU reference maths (`color_math.h`, `effect_math.h`,
`grade_math.h`, `repair_math.h`, ...) are tested against each other in host tests. SPECS 5.3, 5.10, 5.14.

### Time

Every timeline position is an integer `FrameIndex` in project frames; the frame rate is rational (`fpsNum/fpsDen`). Conversions
to microseconds or audio samples use integer maths with documented rounding (SPECS 3). Source ranges of clips are stored in project
frames; retiming keeps a clip's length separately (`retimedFrames`) and maps timeline frames to source frames exactly.
The audio device is the master clock during playback; the preview follows it (SPECS 5.3).

## 3. Threading model

| Thread | Does | Must not |
|---|---|---|
| Main (UI) | Compose, ViewModels (`viewModelScope`), intents, state reduction | decode, rasterise, touch the disk for big files, call blocking JNI |
| Render threads (one per `SurfaceView`) | EGL contexts, drawing the preview and the timeline, texture uploads | wait for I/O or decoders |
| Decoder workers | one `AMediaCodec` per open asset, look-ahead windows | run on UI or render threads |
| Audio callback (Oboe) | mixing at the stream rate | allocate, lock, or call into Kotlin |
| Workers (`Dispatchers.IO`/`Default` or native threads) | waveforms, thumbnails, beats, stabiliser and tracker analysis, proxy transcodes, export | block the UI; all report progress and can be cancelled |

Kotlin code that starts background work takes its dispatcher as a constructor parameter so tests can control it. Native calls from
the UI are cheap and non-blocking; heavy work is queued.

## 4. Caches and budgets

| Cache | Where | Bound |
|---|---|---|
| Decoded frames | native, `AHardwareBuffer`, LRU | strict byte budget (default 1 GB on the reference class), shared between open assets; eviction protects the playback window (SPECS 5.4) |
| Decoders | native | the device's hardware limit (preview capped at 4); `DecoderPlanner` keeps the top layers |
| Waveform peaks, thumbnails, stabiliser corrections, tracks, loudness | per project / app files | keyed by asset, range and a hash; stale entries are ignored; thumbnails use a GPU atlas with LRU and a memory budget |
| Rasterised titles | memory | bounded LRU keyed by a content hash |
| Stills (photos, stickers, GIF/WebP frames) | memory + GPU | stored at native size (reduced only when larger than their fit) and scaled by the GPU; one shared 128 MB budget (`PictureBudget`): the preview's `StillKeyCache` accounts real texture sizes, the exporter loads each picture on demand and a native LRU (`PictureResidency`) releases the oldest; animations keep a canvas snapshot every N frames for seeking (SPECS 5.17) |
| Proxies | `cacheDir/proxies` | user budget (1 to 16 GB) with LRU eviction; never used for export (SPECS 5.31) |
| Looks, LUTs, fonts, presets | app files | small, user-managed |

New caches must be bounded and keyed by something that changes when the source changes.

## 5. File formats and versions

| Format | Version | Compatibility rule |
|---|---|---|
| `project.json` | schema 1 (`CURRENT_SCHEMA_VERSION`) | new fields are optional with defaults; unknown future versions are refused with a clear error; unknown fields are preserved on save. A `.bak` of the last good save and the `.tmp` of an interrupted write sit next to it (SPECS 4, 4.1) |
| Timeline snapshot (Kotlin to native) | 7 | native reads older versions down to its minimum (`kSnapshotMinVersion`) |
| Audio snapshot | 5 | the mixer still reads 4 |
| `.uvbundle` | 1 | manifest carries `formatVersion` and `schemaVersion`; the optional `resources` list (LUTs and fonts under `resources/`) was added without a bump: older builds ignore it (SPECS 5.24) |
| `.uvtemplate`, `.uvtitle` | 1 each | refuse files from newer versions |

Whenever a native structure changes, bump its version, keep the old reader, and add a test for both.

## 6. How to add a feature safely

1. **Write the domain part first**, pure Kotlin: a model change with optional JSON fields, an operation returning
   `EditResult`, an `EditCommand` (one undo step), and tests for collisions, gaps, boundary frames, undo exactness and, if it
   touches the base track, the randomized invariant test.
2. **Persist it**: DTO field with a default, `TimelineMapper` both ways, a JSON test with an old project.
3. **Make preview and export agree**: extend `RenderPlan` (and the mixer snapshot for sound) and let both read it. If a shader is needed,
   write the CPU reference first and a host test that compares it with documented vectors.
4. **Add the UI** as MVI: state field, intent, effect, composable; read frequently changing state (playhead) in its own scope so a tick
   does not recompose the screen; give every control a content description and, for toolbar buttons, a tooltip.
5. **Respect the boundaries**: no JNI outside `engine/`, no Android imports in `domain/`, no blocking work on the UI or render thread.
6. **Respect privacy**: no network, no analytics, no ML, no third-party service. `OfflineGuaranteeTest` fails the build otherwise.
7. **Update the docs**: the SPECS section, `docs/USER_GUIDE.md` (and its icon table), the PLAN box and a `DECISIONS.md` entry
   with the alternative you rejected.
8. **Verify**: JVM tests, host native tests (`scripts/run-native-tests.sh`), `./gradlew :app:assembleDebug`; then run it on a phone and
   say in the pull request what you did and did not see (the *Verification debt* table in `PLAN.md` is the place to record it).
   Never run `connectedAndroidTest` on a device whose app data matters: it clears it.

## 7. Where to look

| I want to change | Start at |
|---|---|
| a clip operation | `domain/TimelineOps.kt`, `MagneticBase.kt`, `EditHistory.kt` (SPECS 6, 6.1) |
| what a drag does | `domain/DropPlan.kt`, `ui/editor/EditorViewModel.kt` (drag section) (SPECS 5.15) |
| how a frame is drawn | `domain/RenderPlan.kt`, `cpp/render/gl_pipeline.cpp`, `cpp/render/shaders.h` |
| playback sync | `ui/editor/PreviewFollow.kt`, `cpp/render/preview_engine.cpp`, `engine/audio/` |
| export | `ui/export/`, `engine/export/`, `cpp/encode/` (SPECS 5.10) |
| the timeline canvas | `cpp/timeline_view/`, `engine/timeline/TimelineSnapshot.kt` |
| project files | `data/model/ProjectDto.kt`, `data/TimelineMapper.kt`, `data/ProjectRepository.kt` |

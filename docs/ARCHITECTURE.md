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
- **Long jobs.** `ui/export/` holds three process-lived executors (`ExportExecutor`, `BundleExportExecutor`, `BundleImportExecutor`), each running at most one
  job and publishing a `StateFlow`; `ExportService` is a foreground service that only mirrors the state of all three into one notification (SPECS 5.10, 5.24).
- **`MediaAccess` (`data/`)** the one boundary where a `content://` URI is opened; a vanished USB volume, dead provider or missing file becomes a typed
  `MediaImportException` instead of a crash (SPECS 4.1). Relink by folder scan builds on it (`data/relink/`, SPECS 5.40).
- **Edit settling.** `ClipLinks.settle` (detached audio and its video, SPECS 5.38) and `MarkerAnchors.settle` (markers stuck to clips, SPECS 5.41) compare the
  timeline before and after any edit inside `EditHistory`, so every operation carries its linked clip and markers in the same undo step.
- **Toolbar order.** `ui/editor/toolbar/ToolbarOrder.kt` is pure data plus a `ToolbarOrderStore` (local preferences); the editor row and the More menu read it.
- **Waveform states.** The native waveform service reports each asset as pending, peaks, or a failure state and logs one line under tag `uv_wave`
  (`audio/wave_state.h`); the canvas draws a state, never a guess (SPECS 5.6).
- **Hub library.** The Projects screen (`ui/hub/`) is a library-style list with sorting and a stored view mode (`HubViewStore`) (SPECS 4.2).
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

A feature is a vertical slice with six parts. Each lives in its own file(s), so adding one never means growing a shared giant.

| Part | Where | Rule |
|---|---|---|
| 1. Domain | `domain/<feature>/` | Pure Kotlin, JVM tests, no `android.*`, no Compose, no `ui`/`engine`/`data` imports. |
| 2. Persistence | `data/model` DTO + `TimelineMapper` | New JSON fields are optional with a default; test with an old project file. |
| 3. Preview and export | `domain/RenderPlan.kt`, the mixer snapshot | Both read the same plan; no special case in native code. |
| 4. Intents and handler | `ui/editor/<feature>/<Feature>Handler.kt`; intents in `EditorContract.kt` | The ViewModel stays a router. |
| 5. UI region | `ui/editor/<feature>/<Feature>Panel.kt` | One composable per region, state in, callbacks out. |
| 6. Text, guide, tests | `res/values*/strings.xml`, `ToolbarGuide.kt`, `src/test` | English and Spanish together. |

### The recipe

1. **Write the domain part first.** A model change with optional JSON fields, an operation returning `EditResult`, an `EditCommand`
   (one undo step), and tests for collisions, gaps, boundary frames, undo exactness and, when it touches the base track, the randomized
   invariant test. Time is integer `FrameIndex`; never seconds or floats for positions.
2. **Persist it.** DTO field with a default, `TimelineMapper` both ways, a JSON test that loads an old project.
3. **Make preview and export agree.** Extend `RenderPlan` (and the mixer snapshot for sound) and let both read it. If a shader is
   needed, write the CPU reference first and a host test that compares it with documented vectors.
4. **Add an intent group and its handler.** The feature's intents are cases of `EditorIntent` in `EditorContract.kt`; its
   behaviour is `internal class <Feature>Handler(host: EditorHost, ...) : EditorHost by host`, which reaches state, effects and
   scope only through the small `EditorHost` interface (a `FakeEditorHost` makes it testable), with the business logic in
   `domain/`. `EditorViewModel` only maps an intent to its handler and holds no feature logic. A handler is plain Kotlin and is unit tested without Compose or a device. State that changes often
   (the playhead) is read in its own scope so a tick does not recompose the screen.
5. **Add one UI region.** A composable in `ui/editor/<feature>/` that receives immutable state and callbacks; `EditorScreen` only
   places it. Give every control a content description and, for toolbar buttons, a tooltip. Layout decisions (what wraps, scrolls or
   goes into a menu at 320 dp or font scale 2.0) are pure functions with a window x font-scale test, like `ChromeLayout`.
6. **Text.** Every string is in `res/values/strings.xml` and `res/values-es/strings.xml`: `stringResource` in a composable, a `UiText`
   anywhere else; positional arguments and plurals, never text built from fragments (SPECS 5.43, `docs/TRANSLATING.md`).
   `TranslationsGuardTest` and `HardCodedTextRatchetTest` fail otherwise.
7. **Toolbar guide.** A feature with an icon needs its entry in `ui/editor/guide/ToolbarGuide.kt`; `ToolbarGuideTest` fails without it.
   Add the row to `docs/USER_GUIDE.md` (and its icon table).
8. **Respect the boundaries and privacy.** No JNI outside `engine/`, no blocking work on the UI or render thread, no network, no
   analytics, no ML, no third-party service (`OfflineGuaranteeTest`).
9. **Update the docs.** The SPECS section, the PLAN box, a `DECISIONS.md` entry with the alternative you rejected, and the `CHANGELOG.md`
   line.
10. **Verify.** `./gradlew :app:testDebugUnitTest :app:assembleDebug`, `scripts/run-native-tests.sh` for native changes, then a phone
    (record what you did and did not see in the pull request and in the *Verification debt* table of `PLAN.md`). Never run
    `connectedAndroidTest` on a device whose app data matters: it clears it.

### Size limits and ratchets

These are enforced by tests, not by convention (`ArchitectureGuardTest`, `DECISIONS.md` "Guards, not prose"):

| Rule | Limit or check |
|---|---|
| Kotlin source file | at most **800** lines |
| C++ source or header | at most **1500** lines (host tests: 2000) |
| Kotlin test file | at most 1500 lines |
| `domain/` | imports nothing from `android.*`, `androidx.*`, `ui`, `engine`, `data` |
| JNI (`external fun`, `System.loadLibrary`) | only in `engine/` |
| Compose imports | only in `ui/` (never `domain/` or `data/`) |
| `ui/` importing `data/` classes | only files on the allow-list |
| User-visible text | only through resources or `UiText` (`HardCodedTextRatchetTest`) |
| Debug properties | only the allow-listed ones, none may switch a fix off (`DebugPropertyGuardTest`) |
| Network | none (`OfflineGuaranteeTest`) |

The existing exceptions are listed in `app/src/test/arch/` (`oversized.txt`, `domain-imports.txt`, `ui-data-imports.txt`). Each list is a
**ratchet**: a new violation fails, and so does a listed file that no longer violates, so the list can only shrink. When the test tells
you a file is fixed, delete its line. Never add a line to make a build pass; split the file or move the type instead. If a file is on a
list because it is being split, the split removes the line in the same change.

### Screenshot tests

Layout regressions (cut-off controls at 320 dp or font scale 2.0) are caught on the JVM with Robolectric and Roborazzi: a test renders a
composable and compares it with a small PNG in `app/src/test/screenshots/` (see `TransportRowScreenshotTest`). They run with the normal
`./gradlew :app:testDebugUnitTest` (verify mode). After an intended visual change, re-record and commit the changed PNGs with the change:

```
./gradlew :app:testDebugUnitTest --tests '*ScreenshotTest' -Puveditor.recordScreenshots
```

Keep each shot small (1 px = 1 dp, `mdpi`, a single component) so goldens stay a few kilobytes. These libraries are test-only and run
fully offline (`THIRD_PARTY_NOTICES.md`).

### Text and languages

New user-visible text goes in `res/values/strings.xml` and its Spanish in `values-es/strings.xml`, never in code: `stringResource` in a
composable, a `UiText` anywhere else (view model state, effects, notification models, pure builders). See SPECS 5.43 and
`docs/TRANSLATING.md`; `HardCodedTextRatchetTest` fails for a new literal outside the allow-list.

### Where things live

| Kind of code | Place |
|---|---|
| Pure rules, edit operations, models, render plan, parsers | `domain/` (and its feature subpackages) |
| Files, stores, DTOs, import, relink, interchange | `data/` |
| Everything that crosses JNI, and the Kotlin facades of native services | `engine/` only |
| Screens, sheets, panels, their ViewModels, intents and handlers | `ui/<screen>/` and `ui/editor/<feature>/` |
| Pure layout decisions (what fits, what wraps) | `ui/editor/layout/` |
| Text for the user | `res/values*/strings.xml`, `UiText` |
| Toolbar icon documentation | `ui/editor/guide/ToolbarGuide.kt` |
| Native logic with no Android dependency | `cpp/<area>/` plus a host test in `cpp/tests/` |
| JNI glue | `cpp/jni/` (bindings only) |
| Guards | `app/src/test/.../qa/`, allow-lists in `app/src/test/arch/` and `app/src/test/i18n/` |

## 7. Where to look

| I want to change | Start at |
|---|---|
| a clip operation | `domain/TimelineOps.kt`, `MagneticBase.kt`, `EditHistory.kt` (SPECS 6, 6.1) |
| what a drag does | `domain/DropPlan.kt`, `ui/editor/EditorViewModel.kt` (drag section) (SPECS 5.15) |
| how a frame is drawn | `domain/RenderPlan.kt`, `cpp/render/gl_pipeline.cpp`, `cpp/render/shaders.h` |
| playback sync | `ui/editor/PreviewFollow.kt`, `cpp/render/preview_engine.cpp`, `engine/audio/` |
| export | `ui/export/`, `engine/export/`, `cpp/encode/` (SPECS 5.10) |
| a long job (export, backup, import) | `ui/export/*Executor.kt`, `ExportService.kt`, `ExportCenter.kt` |
| linked clips and sticky markers | `domain/ClipLinks.kt`, `domain/MarkerAnchors.kt`, `EditHistory.kt` |
| the timeline canvas | `cpp/timeline_view/`, `engine/timeline/TimelineSnapshot.kt` |
| project files | `data/model/ProjectDto.kt`, `data/TimelineMapper.kt`, `data/ProjectRepository.kt` |

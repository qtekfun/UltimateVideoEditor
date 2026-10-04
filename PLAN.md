# ultimateVE — Implementation Plan

Status: reconciled 2026-10-04. Details live in `SPECS.md` (how), `PRD.md` (what), `DECISIONS.md` (why),
`docs/QA_REPORT.md` (what was exercised on a phone) and `docs/ARCHITECTURE.md` (overview).

**Convention.** `[x]` means *implemented, and covered by the automated tests that pass in CI* (JVM unit tests,
native host tests, the debug and release builds). It does **not** mean "seen on a phone": what has and has not been
seen on a device is tracked in [Verification debt](#verification-debt) at the end, which is the checklist to
walk through on the reference phone (OPPO CPH2841). `[ ]` means not built, or a gate that is still open.

**Project rule (privacy).** No AI/ML, no third-party services, no analytics, no accounts, no network access.
`docs/PRIVACY.md` states it and `OfflineGuaranteeTest` enforces the technical part.

## Phase 0 — Scaffold
- [x] Gradle Kotlin DSL project, version catalog, `com.ultimatevideo.uveditor`, minSdk 33, NDK/CMake from the command line
- [x] Jetpack Compose + Material 3 + Navigation, theme, edge-to-edge
- [x] `uveditor_engine` shared library with JNI, MVI base contracts, `.gitignore`, GPL-3.0 licence, README
- [x] Unit-test and instrumented smoke-test wiring; CI (`.github/workflows/ci.yml`)
- **Gate (met):** `./gradlew :app:assembleDebug :app:testDebugUnitTest` pass; the app launches and shows the engine version.

## Phase 1 — Project hub
- [x] Hub (project list, empty state), New project flow, `project.json` with atomic writes and forward compatibility
- [x] Create, clone, rename, delete, import/export through SAF
- **Gate (met):** projects survive restarts; JSON round trips are tested.

## Phase 2 — Domain timeline and operations (pure Kotlin)
- [x] `FrameIndex`, rational fps, integer time conversions; track/clip model with invariants
- [x] Split, move, overwrite, ripple delete/append, trim, snapping; undo/redo stack
- [x] Exhaustive collision and gap tests and randomized invariant tests
- **Gate (met).**

## Phase 3 — Timeline canvas and waveforms
- [x] Native GLES timeline on a `SurfaceView` (ruler, lanes, clips, playhead, scroll/fling/pinch, hit testing)
- [x] SAF media import with persisted permissions; background waveform extraction with an on-disk peak cache
- [x] Waveforms (normalised per media) and video thumbnails on clips (cached filmstrip; photos get one tile)
- [x] Base track (magnetic, overlays follow) and free overlay lanes; touch editing: select, move, trim, split, delete, drops
- **Gate:** smooth scroll/zoom on a 50-clip timeline without UI jank. Seen on a Pixel 8: editor frames 379 in 10 s, 1.3 % jank,
  p50 10 ms, p99 18 ms. Not measured on the reference phone.

## Phase 4 — Decode, preview and audio playback
- [x] GLES 3.2 compositor, MediaCodec decode (H.264/HEVC) to `AHardwareBuffer`, LRU frame cache with look-ahead, scrubbing
- [x] Oboe playback with the audio clock as master; the preview follows it (re-anchored on change or drift > 2 frames)
- [x] Per-clip transform with on-preview gestures; multi-layer compositing within the decoder limit
- [x] Colour: HLG/Rec.2020 and PQ to SDR Rec.709 shaders, per-clip source colour override
- **Gate (open):** 4K60 single-layer playback without drops: met on synthetic clips on the reference phone (480/480 frames shown
  once); ~51 fps average on the Pixel 8. A/V drift over a long timeline: clocks agree within 0.4 ms over 55 s on the reference phone and
  at most 2 frames over ~35 s on the Pixel 8; the 5-minute run (`scripts/av-drift-test.sh <serial> 5`) is still to be recorded.

## Phase 5 — Titles and transitions
- [x] Title clips (multilayer text, shapes and images; custom fonts; presets; in/out motion) and composition
- [x] Crossfade and a transition pack (slide, push, zoom, spin, glitch, wipe, whip pan, light leak), equal-power audio fade
- **Gate:** titles and transitions render in preview and match the export (shared `RenderPlan`; checked by tests, not by eye).

## Phase 6 — Export
- [x] Offline render loop to MediaCodec encoder (H.264, HEVC) + muxer
- [x] Audio offline mix and AAC encode
- [x] Export UI: resolution/fps/bitrate, progress, cancel, share
- [x] Optional: static FFmpeg fallback behind a feature flag (`-Puveditor.ffmpeg=<dir>`, off by default): **built, verified in CI only** — host tests of the readers against a real libav (MPEG-2, MPEG-4, ProRes, H.264, AAC, AC-3: exact frame indices, seeking inside GOPs, audio seek), the pinned LGPL static build for arm64 (+7.6 MB in `libuveditor_engine.so`) and the engine linked against it. **Not yet run on a device** (preview/export path, RGBA8 upload, the "software decoding" notice): see `docs/ffmpeg-fallback.md`.
- _Status:_ exports the full timeline (all video layers composited with their transform and opacity through the preview's
  `drawScene`, clip gain in the audio mix, gaps black, HLG sources tone-mapped to SDR Rec.709) at the project
  or a lower frame rate, H.264 or HEVC + AAC in MP4, saved through SAF. On the reference device a 4K60 HEVC export runs at ~90 fps
  (1.5x real time) and a 1080p30 H.264 one at ~100 fps. Verified with ffprobe: exact frame counts and PTS grid, audio clicks land on
  their timestamps. The AAC encoder delay (2048 samples) is compensated, so the first 42.7 ms of the mix are not heard. Cancel and
  Share are covered by unit tests only (not exercised on the device); colour fidelity was checked with synthetic charts, not real footage.
- **Gate:** exported file plays correctly with matching A/V sync and colours.

- [x] Offline render loop to MediaCodec (H.264/HEVC, HEVC Main10 HLG) with AAC mix, MP4 through SAF, progress, ETA, cancel, share
- [x] Platform upload presets
- [ ] Optional static FFmpeg fallback — **designed, not built** (`docs/ffmpeg-fallback.md`); trigger: a real file MediaCodec cannot open
- Measured on the reference phone: 4K60 HEVC export ~90 fps (1.5x real time), 1080p30 H.264 ~100 fps, frame counts and PTS exact with
  ffprobe. Long-GOP material exported at ~11.6 fps until the seek fix; ~54-71 fps on the Pixel 8 afterwards (simulation in
  `uv_decode_sim_host_tests` protects it). Two-layer export ~1.3x real time. Cancel and Share are test-covered only.
- **Gate:** exported file plays with matching A/V sync and colours (checked with synthetic charts, not real footage).

## Phase 7 — Creator features
- [x] Social presets (9:16, 1:1, 4:5, 16:9), safe zones, upload presets
- [x] Keyframes: pose and opacity, then parameter tracks (effects, grade, audio level/pan/EQ) with Bezier handles and a lane
- [x] Speed 0.1x-100x, ramps with an editor, reverse, freeze, optical-flow slow motion (classical), video denoise and deflicker
- [x] Effects, masks, blend modes, chroma key; colour grade (wheels, curves), looks, scopes (waveform, parade, vectorscope, histogram)
- [x] 3D LUTs (`.cube`), filter pack (20 original looks)
- [x] Captions typed or imported (`.srt`/`.vtt`) with eight styles including karaoke and typewriter. The earlier on-device speech
  recognition was removed for the privacy rule.
- [x] Photos and stickers; text templates; beat markers, snap to markers, cut to beat
- [x] HDR end to end: HLG project space, 10-bit compositing, HLG preview, HEVC Main10 export
- [x] Stabiliser and motion tracking (classical tracker), auto cut by silence, manual reframe helper
- [x] Project templates (`.uvtemplate`), starter set, "New from a template" wizard
- [ ] Voice effects (WP-V3, classical DSP): not built
- [x] Vulkan renderer evaluation (`docs/vulkan-evaluation.md`): not worth migrating now (~0.85 ms of a 16.6 ms frame at 4K60)

## Phase 8 — Gaps against LumaFusion and CapCut (all packages of `SPECS.md` 9 are in)
- [x] WP-U1 New-project flow with selectors and a simpler hub
- [x] WP-U2 Media tray with drag and drop onto the timeline, drops from other apps
- [x] WP-U3 Resizable and customisable layout: dividers, lane heights, dockable panels, presets, persistence
- [x] WP-C Colour tools and scopes
- [x] WP-S Multiselect and bulk edits (SPECS 5.19)
- [x] WP-A Audio tools: pan, fades, EQ, classical noise suppression, LUFS normalise, track mixer, ducking, meters
- [x] WP-T Multilayer titles and fonts (SPECS 5.26)
- [x] WP-K Generalised keyframes (SPECS 5.23)
- [x] WP-X Stabiliser (SPECS 5.21)
- [x] WP-I Interchange and media library: bundle, EDL, FCPXML subset, tags, search (SPECS 5.24)
- [x] WP-V1 Motion tracking (SPECS 5.22)
- [x] WP-V4 Slow motion, speed curves, denoise, deflicker
- [x] WP-V2 Auto cut and manual reframe helper (SPECS 5.25)
- [x] WP-P Proxy media (SPECS 5.31)
- [x] WP-V5 Project templates, transition and filter packs
- [x] WP-M Multicam (SPECS 5.29)
- [x] WP-R Release preparation: versioning, optional signing, R8 (release APK 6.6 MB), release workflow, local crash report, About, tips
- Removed from the plan by the privacy rule: ML cutout, subject-detecting reframe, neural voices, vocal isolation, speaker captions,
  speech recognition.

## Cross-cutting
- Every clip-manipulation feature ships with unit tests for collisions and gaps.
- Profile on the reference device at the end of each phase (frame time, memory, battery).
- Keep CLAUDE.md and SPECS.md updated when decisions change.

## Deferred
- Frame blending option for slow motion, pitch-preserving time stretch, animated GIF/WebP (first frame only), dragging lane headers,
  dragging markers on the ruler, spectral beat detection, `.lrc`/`.ass` subtitles, HSL qualifiers, viewer thumbnails in motion for multicam.

## Verification debt

Everything below is covered by automated tests but has had little or no time on a real phone. Walk through it on the OPPO CPH2841
with a clip that has sound; report what looks wrong. "Seen" lists what a person or an agent did observe on a device (the OPPO unless
the Pixel 8 is named; the Pixel 8 is a debug device, not the reference).

| Area | Seen on a device | Still to check on the OPPO |
|---|---|---|
| Hub and projects | Project list (OPPO); new-project sheet opens in a wide window (Pixel) | Selector sheet, presets, "match first clip", thumbnails on cards, search/sort, About and tips, bundle import/export, template wizard, recover/reopen banners |
| Timeline editing | Layer layout, horizontal and vertical drags, lift from base, new lane, undo, scrub (OPPO); split, import, lanes (Pixel) | Trim handles, insert at junctions (base and overlay), overwrite, delete rules, group moves, magnetic reorder |
| Media tray | Not seen | Tray layout and snap heights, drag onto the canvas with the live indicator, edge auto-scroll, drops from other apps, reordering |
| Layout | Divider drag, layout sheet, Large lanes, presets (Pixel) | Inspector docked to a side, customise mode, folding a side column, persistence across restart, split screen |
| Playback and audio | AAudio started, clock drift 0.4 ms in 55 s (OPPO); playback with the v4 mixer, meter and Mixer sheet (Pixel) | Hearing it: sync by ear, EQ, noise suppression on speech, ducking, fades, pan; export audio against preview; 5-minute drift run |
| Export | Many ffprobe-checked exports on the OPPO; ETA text and a full UI export (Pixel) | Cancel, Share, HDR HEVC Main10 on an HDR display, real footage colour, long-GOP 4K, two-layer speed |
| Colour | Grade and scopes render and respond (Pixel) | HLG/SDR mixing look, LUT 3D path, save/apply a look, copy/paste, scopes cost at 4K60 |
| Titles, captions, stickers | Title and sticker blocks (Pixel) | Layer editor, fonts import, presets, SRT/VTT import, the eight caption styles, stickers and emoji art, photos (EXIF, HEIC) |
| Keyframes | Not seen | Diamonds, lane drag, exported frames against preview |
| Speed and slow motion | Export of freeze/2x/reverse/ramp/0.5x checked frame by frame (OPPO); 0.25x interpolation PSNR 43.9 dB vs 35.9 dB (Pixel) | Inspector and curve editor, reverse in preview, denoise/deflicker on real footage |
| Stabiliser and tracking | Stabiliser demo export 21.5 to 42.6 dB (Pixel) | Inspector sections, live preview, tracking on real clips, attaching a title to a path |
| Markers and beats | Not seen | Ruler markers, beat detection on music, cut to beat, templates look |
| Proxies | One 4K clip became a 720p proxy in 7.9 s (Pixel) | Sheet, badges, preview switching to the proxy and back, scrub smoothness with proxies |
| Multiselect | Select mode, marquee, group move, copy/paste (Pixel) | Cut, paste attributes, align, transitions, group speed/volume/opacity |
| Multicam, auto cut, reframe, templates, filter and transition packs | Not seen | Everything: sheets, sync on two real recordings, cuts, shader looks, wizard |
| Interchange and library | Bundle with media, EDL and FCPXML written and read, bundle import (Pixel) | Tags and notes, find in timeline/library, remove unused, relink by name and size; open the FCPXML/EDL in another editor |
| Relink and recovery | Offer to reopen after a crash (Pixel) | Relink picker round trip, hatched clips, recover from `.bak` |
| Release build | Release APK signed with a throwaway key verifies; builds and lint pass (host) | Minified build runtime behaviour (JNI lookups, project round trip), About screen, crash report, tips |

### Wave 1
- [x] WP-U3 Resizable and customisable layout: dividers, lane heights, dockable panels, layout presets, persistence
  - _Status:_ pure `LayoutState` reducer, presets, per-window persistence and the controller are covered by 34 JVM tests; the native lane scale by a host test.
    Seen on the Pixel 8 (not the reference phone): divider drag, the layout sheet, Large lanes, the Timeline focus preset, and the Two panels preset in a
    widened (762 dp) window with the tray in a left column. Not seen: the inspector docked to a side, customise mode buttons, collapsing a side column,
    persistence across a restart, split-screen/fold changes, and the OPPO. Pinch-to-resize lanes is not built (the -/+ control is).
- [x] WP-C Colour tools and scopes: waveform, RGB parade, vectorscope, histogram; colour grade effect (lift/gamma/gain wheels, offset, contrast + pivot, saturation, vibrance, temperature, tint, four tone curves); looks, copy and paste. _Status:_ implemented with CPU-reference, wire, JSON, undo and ViewModel tests (host and JVM pass) and checked on the Pixel 8 (not the reference OPPO): the grade shader compiles and renders (gain wheel towards red tints the preview and shifts the waveform), the four scopes draw on their own surface with graticule and scale labels, a curve point can be added and dragged and the mid tones follow, and no GL or fatal errors appear in logcat. Not seen: saving and applying a look and copy/paste on the device, an HLG project (scale labels), the export of a graded clip, scope cost at 4K60, and the OPPO. The HSL qualifiers of the spec are deferred (see DECISIONS.md).

### Wave 2
- [x] WP-S Multiselect and bulk edits (SPECS 5.19). _Status:_ domain operations, view model, native marquee/primary outline and snapshot v6 are covered by JVM and host tests. Seen working on the Pixel 8 (not the reference phone): select mode and the selection bar, blue/yellow outlines, tap and long press, the marquee rectangle, dragging a group, return to a single clip, and copy then paste at the playhead (clips pasted and selected). Duplicate with a base block and overlays was seen working too. Not yet seen on a device: cut, paste attributes, align, transitions, group speed/volume/opacity.
- [ ] WP-A Audio tools: pan, fades, EQ, noise suppression, loudness, track mixer, auto-ducking, meters
    Built and covered by host/JVM tests (DSP vectors, LUFS, ducking envelope, realtime vs offline parity, snapshot v4, model/undo/JSON). On the Pixel 8:
    app starts, playback with the v4 mixer, level meter and Mixer sheet render. Not verified: how denoise/EQ sound on real speech, ducking by ear, export audio
    compared by ear, fade handles on the clip, the inspector Sound tools on a device, the noise-region marking flow. Left unticked until those are checked.

### Wave 3
- [x] WP-T Multilayer titles and fonts (SPECS 5.25). _Status:_ domain (layers, edits, in/out motion), JSON, presets (`.uvtitle`), font registry, layer bounds, editor view model, plan resolution and the library view model are covered by JVM tests; the 40 fonts under `/usr/share/fonts` parse with the font reader. Seen working on the Pixel 8 (not the reference phone): converting a title to layers, adding a rectangle, dragging only the selected layer on the preview (ring and outline follow it), importing a real `.otf` from Downloads through the system picker and the title redrawing in it (rounded Comfortaa letters against the system font), a fade-in leaving frame 0 transparent, and saving a preset (listed with Export and Delete). Not yet seen on a device: photo layers and stickers inside a title, shadows, borders and boxes, pinch and twist on a layer, preset export and import through the picker, the missing-font banner, the export of a layered title (preview and export share one plan and one rasteriser, checked only by tests), the instrumented rasteriser test (compiles, not run), anything on the OPPO.
- [ ] WP-K Generalised keyframes (after WP-C and WP-A): implemented and covered by JVM and native host tests (tracks, Bezier, cropping, migration, preview/export parity, audio automation v5, lane UI, loudness cache wiring); NOT yet verified on the Pixel (diamond, lane drag, exported frames) so left unticked

### Wave 4
- [ ] WP-X Stabiliser (builds the shared tracker and smoother). _Status:_ engine, JNI, shader stage, model, undo, JSON, analysis controller and inspector section are implemented; host tests (tracker, analyser, smoothing, crop, cache, registry, wire) and 1383 JVM tests pass; the box stays open until a device run is recorded below.
- [x] WP-I Interchange and media library: bundle, EDL, FCPXML subset, tags, search. _Status:_ implemented (SPECS 5.21) and covered by JVM tests (bundle round trips, zip-slip, size limits and atomic import, auto-relink, EDL and FCPXML golden files plus an XML well-formedness check, library queries, marker notes, view models). Checked on the Pixel 8 (not the reference phone, project built with its own id suffix): the library sheet shows pictures, lengths, usage counts, the red Missing mark and the filters; FCPXML, a bundle with media and an EDL (two tracks, so a zip) were written through the system picker and read back (the zip passes `testzip`, holds the manifest, the project, the card picture and the three readable media files, the unreadable one is listed with no entry; the FCPXML parses as XML); importing that bundle in the hub created "Interchange Test (2)" with the media unpacked into the project's own folder and the unreadable file left pointing at its old address. Not seen on a device: tags and notes dialog, find in timeline, remove unused, the marker note dialog, relink by name and size, and any import into Final Cut Pro, DaVinci Resolve or another editor.

### Wave 5 — CapCut-style creator tools
- [ ] WP-V1 Motion tracking (classical tracker). _Status:_ implemented (SPECS 5.22) and covered by JVM tests (domain maths, source-to-project mapping with trim, speed and reverse, decimation, attach keyframes, undo, cache reading, analysis controller, view model) and native host tests (synthetic pan: 0.9 px worst error forward and both ways, loss marked and recovered, cache format); the NDK build with `-Werror` links the JNI symbols. Not seen on a device yet: the picking layer, the path overlay and a real analysis; the box is not ticked until they are.
- [ ] WP-V4 Optical-flow slow motion, speed-curve editor, video denoise, deflicker. _Status:_ implemented (SPECS 9.18) with JVM tests (curve model, mix mapping, source packing, view model, limits) and native host tests (flow, interpolation, denoise, deflicker, export table). Pixel 8 measurements: interpolated 0.25x export PSNR 43.9 dB against 35.9 dB for frame repetition (240 fps ground truth), judder 0.13 against 1.71; the preview picture matches too; denoise plus deflicker raise PSNR 24.8 to 29.7 dB and cut the luma flicker step 28.0 to 9.2. Not seen on a device: the inspector and curve editor, the reference phone, 4K and real footage; the box is not ticked until they are.

### Wave 6
- [ ] WP-V2 Auto cut (silence removal) and manual reframe helper. _Status:_ implemented (SPECS 5.23) and covered by JVM tests (silence detection on synthetic envelopes, source-to-timeline mapping, atomic cut with base ripple and overlays following, undo, reframe maths and keyframes, view model flows); the sheets and the real waveform path have not been seen on a device yet, so the box stays open.
- [x] WP-V3 Voice effects (classical DSP): pitch/formant shift, Chipmunk, Deep, Robot, Whisper, Radio, Echo, Reverb, Megaphone (SPECS 9.17). _Status:_ host-verified only (native DSP tests: pitch within 0.1 cent, level within 1 dB, formant moves the envelope, whisper removes the pitch, ring-mod spectrum, impulse responses, chunk invariance, tails, no NaN/denormals; core tests: realtime = offline within 1e-6, retimed path, tail past the media end; JVM tests for the model, JSON, snapshot v6, mapping and view model). Not yet heard on a device; the inspector subsection was not seen on a screen.

### Wave 7
- [ ] WP-P Proxy media (implemented and unit-tested, SPECS 5.22; tick after it has been seen working on the OPPO)
- [ ] WP-V5 Project templates, transition and filter packs. _Transition pack:_ slide, push, zoom, spin, glitch, wipe, whip pan and light leak with directions, a look picker with a three-frame preview, preview/export parity by one shared evaluator (SPECS 5.25), JVM tests for the looks, the render plan, export keys and per-frame effects, JSON and undo; not yet seen on a device. _Filter pack:_ 20 original looks generated in code and installed into the LUT library on first use, with swatches in the LUT picker (SPECS 5.24), covered by JVM tests (identity, range, monotonic grey, cube round trip, idempotent install); not yet seen on a device. _Templates:_ placeholders, fitting (trim, shorten with ripple, centre crop, optional slots, transitions clamped or dropped), save-as-template, `.uvtemplate` import/export, three built-in starters and a "New from a template" wizard in the hub (SPECS 5.27), covered by JVM tests; the wizard has not been seen on a device, and the New project sheet has no separate "Template" start mode yet (the entry is the hub menu). _Transition pack:_ slide, push, zoom, spin, glitch, wipe, whip pan and light leak with directions, a look picker with a three-frame preview, preview/export parity by one shared evaluator (SPECS 5.25), JVM tests for the looks, the render plan, export keys and per-frame effects, JSON and undo; not yet seen on a device.

### Wave 8
- [x] WP-M Multicam (after WP-A, WP-S, WP-P). _Status:_ implemented (SPECS 5.26) and covered by JVM tests (sync recovers known offsets from synthetic shifted audio with noise, cut/record/undo, flatten equivalence, following moves, decoder budget planner, JSON round trips); nothing seen on a device (the Pixel was locked with a credential), so the sheet layout, a real sync of two phone recordings and live recording during playback are unverified
- [ ] WP-R Release preparation. _Status:_ done and verified on the host: single-source versioning (`gradle/version.properties`, versionCode derived), optional signing from `keystore.properties` or `UVEDITOR_*` variables (a signed APK built with a throwaway key passes `apksigner verify`, versionCode 100, no permissions beyond AndroidX's own), R8 minification and resource shrinking with keep rules (APK 30.1 MB -> 6.5 MB; mapping inspected: JNI classes, native holders and serializers kept), `lintRelease` clean of errors (a literal BOM in `Subtitles.kt` and a release-only `-Werror` failure in `thumb_atlas.h` were fixed), local crash report writer, About screen model, first-run tips model, `.github/workflows/release.yml` (valid YAML; runs only on a `v*` tag or by hand, so it has not run on GitHub yet), `docs/RELEASE.md` with store text, data-safety answers and checklist. Not verified: the minified build and the About screen, tips and crash handler have not been run on a device (the Pixel was locked, the OPPO absent), so R8 runtime behaviour (JNI lookups, project.json round trip) is only checked through the mapping file and the unit tests of the unminified build; the release workflow has not run.

### Standing requirements for every package
- [ ] Privacy rule: no AI/ML, no network, no third-party service, no analytics (`docs/PRIVACY.md`; `OfflineGuaranteeTest` enforces the technical part)
- [ ] Verified on the OPPO CPH2841 (list what was and was not seen in the PR body)

When an area has been walked through, move it out of this table and say so in the pull request.

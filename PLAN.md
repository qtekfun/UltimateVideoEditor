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
- [x] Gradle Kotlin DSL project, version catalog, `com.ultimatevideo.uveditor`, minSdk 31, NDK/CMake from the command line
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
- [x] Voice effects (WP-V3, classical DSP): pitch/formant shift and 9 presets (SPECS 9.17). _Status:_ host-verified only (native DSP tests: pitch within 0.1 cent, level within 1 dB, formant moves the envelope, whisper removes the pitch, ring-mod spectrum, impulse responses, chunk invariance, tails, no NaN/denormals; core tests: realtime = offline within 1e-6, retimed path, tail past the media end; JVM tests for the model, JSON, snapshot v7, mapping and view model). Sliders are keyframable (`audio.voice.<i>` tracks, snapshot v7 voice lanes; JVM tests for interpolation, split/trim/speed re-basing, undo, lane mapping and export parity, native tests for the schedule, glides, seek position and chunk/realtime/offline parity). Not yet heard on a device; the inspector subsection was not seen on a screen.
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
- Frame blending option for slow motion, pitch-preserving time stretch, dragging lane headers,
  dragging markers on the ruler, spectral beat detection, `.lrc`/`.ass` subtitles, viewer thumbnails in motion for multicam.
  (HSL qualifier: the effect, its grouped editor and the eyedropper are done, see DECISIONS.md "HSL qualifier"; host-tested only.)

- Frame blending option for slow motion, pitch-preserving time stretch, dragging markers on the ruler, spectral beat detection, `.lrc`/`.ass` subtitles, HSL qualifiers, viewer thumbnails in motion for multicam.

## Verification debt

Everything below is covered by automated tests but has had little or no time on a real phone. Walk through it on the OPPO CPH2841
with a clip that has sound; report what looks wrong. "Seen" lists what a person or an agent did observe on a device (the OPPO unless
the Pixel 8 is named; the Pixel 8 is a debug device, not the reference).

| Area | Seen on a device | Still to check on the OPPO |
|---|---|---|
| Hub and projects | Project list (OPPO); new-project sheet opens in a wide window (Pixel); thumbnails on cards, rename, duplicate, delete, bundle export with media and import, reopen offer after a forced stop (Pixel, pass A) | Selector sheet, presets, "match first clip", search/sort, About and tips, template wizard |
| Timeline editing | Layer layout, horizontal and vertical drags, lift from base, new lane, undo, scrub (OPPO); split, import, lanes (Pixel) | Trim handles, insert at junctions (base and overlay), overwrite, delete rules, group moves, magnetic reorder |
| Media tray | Expanded tray, tabs, filters, search, long-press drag onto a base junction inserts the clip (Pixel, pass A) | Snap heights, drag onto the canvas with the live indicator, edge auto-scroll, drops from other apps, reordering |
| Layout | Divider drag, layout sheet, Large lanes, presets (Pixel) | Inspector docked to a side on a wide window, folding a side column, split screen (persistence across a forced stop, Reset and customise switch seen on the Pixel, pass A) |
| Playback and audio | AAudio started, clock drift 0.4 ms in 55 s (OPPO); playback with the v4 mixer, meter and Mixer sheet (Pixel); 7.6-minute play/pause/background stress without a crash (Pixel, pass A) | Hearing it: sync by ear, EQ, noise suppression on speech, ducking, fades, pan; export audio against preview; 5-minute drift run |
| Export | Many ffprobe-checked exports on the OPPO; ETA text and a full UI export (Pixel); H.264 and HEVC 1080p30 ffprobe-checked, Cancel leaves no file, Share chooser opens (Pixel, pass A) | HDR HEVC Main10 on an HDR display, real footage colour, long-GOP 4K, two-layer speed |
| Colour | Grade and scopes render and respond (Pixel); save look, copy/paste grade, filter packs, `.cube` import, forced-HLG tone-map message (Pixel, pass A) | HLG/SDR mixing look on real HLG footage, HSL eyedropper, scopes cost at 4K60 |
| Titles, captions, stickers | Title and sticker blocks (Pixel) | Layer editor, fonts import, presets, SRT/VTT import, the eight caption styles, stickers and emoji art, photos (EXIF, HEIC) |
| Animated GIF/WebP, picture memory | Not seen | Import and play a GIF and an animated WebP (lossy, lossless, transparent), loop, scrub back, export one with hundreds of frames on a 4K canvas (check the picture budget in `dumpsys meminfo`), stills drawn at the same size and place as before the native-size change (EXIF-rotated photo, small photo on a big canvas, effects on a still) |
| Keyframes | Not seen | Diamonds, lane drag, exported frames against preview |
| Speed and slow motion | Export of freeze/2x/reverse/ramp/0.5x checked frame by frame (OPPO); 0.25x interpolation PSNR 43.9 dB vs 35.9 dB (Pixel) | Inspector and curve editor, reverse in preview, denoise/deflicker on real footage |
| Stabiliser and tracking | Stabiliser demo export 21.5 to 42.6 dB (Pixel) | Inspector sections, live preview, tracking on real clips, attaching a title to a path |
| Markers and beats | Not seen | Ruler markers, beat detection on music, cut to beat, templates look |
| Dark theme and pure black | Not seen (Pixel locked; JVM contrast tests only) | Pure black switch live, no white flash at start, every sheet and dialog dark, system bars icons |
| Timeline canvas redesign | Not seen (Pixel locked; host and JVM tests only) | Sharp text at 2800x1840 and 1080x2400, accents and emoji in titles, ruler zoom steps, rounded blocks, selection handles; frame stats before/after with `scripts/perf-timeline.sh` (limit 5 % on p99) |
| Proxies | One 4K clip became a 720p proxy in 7.9 s (Pixel) | Sheet, badges, preview switching to the proxy and back, scrub smoothness with proxies |
| Multiselect | Select mode, marquee, group move, copy/paste (Pixel) | Cut, paste attributes, align, transitions, group speed/volume/opacity |
| Multicam, auto cut, reframe, templates, filter and transition packs | Not seen | Everything: sheets, sync on two real recordings, cuts, shader looks, wizard |
| Interchange and library | Bundle with media, EDL and FCPXML written and read, bundle import (Pixel) | Tags and notes, find in timeline/library, remove unused, relink by name and size; open the FCPXML/EDL in another editor |
| Bundle with LUTs and fonts | Not seen (host tests only) | Export dialog switches and estimate, a bundle with a LUT and a font moved to a second phone and opened there, the import report dialog, a bundle made by an older build opening here |
| Relink and recovery | Offer to reopen after a crash (Pixel) | Relink picker round trip, hatched clips, recover from `.bak` |
| Release build | Release APK signed with a throwaway key verifies; builds and lint pass (host) | Minified build runtime behaviour (JNI lookups, project round trip), About screen, crash report, tips |

When an area has been walked through, move it out of this table and say so in the pull request.

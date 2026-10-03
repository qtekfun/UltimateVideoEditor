# ultimateVE — Implementation Plan

Status: reconciled 2026-10-03. Each phase has a gate. Phases were run in parallel; where a gate is still open it says why.
Check items off as completed. Details live in SPECS.md; scope in PRD.md.

## Phase 0 — Scaffold (this is where we start)
- [x] Install NDK, CMake, platform 36, Gradle wrapper via `sdkmanager` (CLI, no Android Studio)
- [x] Gradle Kotlin DSL project, version catalog, `com.ultimatevideo.uveditor`, minSdk 33
- [x] Jetpack Compose + Material 3 + Navigation, theme, edge-to-edge
- [x] CMake + `uveditor_engine` shared library with a JNI hello (`EngineClient.version()`)
- [x] MVI base contracts (State/Intent/Effect/ViewModel) and a sample screen
- [x] Unit test and instrumented smoke test wiring; `.gitignore`, LICENSE (GPL-3.0), README
- [x] Debug APK builds and runs on the reference device via `adb -s <serial>`
- **Gate:** `./gradlew :app:assembleDebug` and `:app:testDebugUnitTest` pass; app launches on device and shows the JNI version string.

## Phase 1 — Project hub
- [x] Hub UI (project list, empty/welcome state)
- [x] New Project dialog (resolution, FPS rational, colour space)
- [x] `project.json` model + serialization (kotlinx.serialization), atomic writes, forward-compatible
- [x] CRUD: create, clone, delete, rename; import/export via SAF
- [x] Unit tests: serialization round-trips, repository operations
- **Gate:** projects survive app restarts; JSON validates against the SPECS schema.

## Phase 2 — Domain timeline and operations (pure Kotlin, TDD)
- [x] `FrameIndex`, rational fps, time conversion helpers
- [x] Track/Clip model with invariants
- [x] Split, move, overwrite, ripple delete, ripple append, trim, snapping
- [x] Undo/redo command stack
- [x] Exhaustive unit tests for collisions and gaps
- **Gate:** all operation tests green; invariants fuzz-tested.

## Phase 3 — Timeline canvas and waveforms
- [x] Timeline `SurfaceView` + native GLES renderer (blocks, playhead, ruler)
- [x] Scroll and pinch-zoom at 60/120 fps; gesture forwarding; hit-testing (scroll, fling and tap seen working on device; frame rate not measured; pinch-zoom only host-tested)
- [x] Media import (SAF picker, persisted URI permission), media library panel (probe verified on device and picker opens; the picker-to-timeline path was not seen end to end)
- [x] Background waveform extraction and on-disk peak cache
- [x] Magnetic base track with overlays that follow it (delete, insert/import, reorder, trim; `MagneticBase`, `ClipDeletion`), unit-tested incl. randomized invariants; not yet verified on device
- [ ] Draw waveforms and thumbnails; snapping, split, move, trim via touch (waveforms drawn; play/seek transport and playhead drag verified on device; clip drag/trim, split and delete implemented and unit-tested but not yet verified on device; thumbnail filmstrip drawn from a disk-cached, atlas-LRU tile pipeline and verified on device with a synthetic clip)
- **Gate:** smooth scroll/zoom on a 50-clip timeline; waveforms appear without UI jank. (Waveforms are now normalised per media and use the whole clip body; zoom fits the whole project until the user zooms by hand. Both are host-tested only: not yet seen on the device.)

## Phase 4 — Decode, preview and audio playback
- [x] EGL/GLES 3.2 preview compositor on a `SurfaceView`
- [x] AMediaExtractor/AMediaCodec decode (H.264/HEVC) to AHardwareBuffer
- [x] LRU frame cache with look-ahead; scrubbing
- [x] Oboe audio playback + mixer (per-clip gain in the mixer); audio device as master clock (`AudioPlaybackEngine.positionFrame()`)
- [x] A/V sync: the preview follows the audio clock without per-tick seeks (native `playScene` clock, re-anchored on composition change or drift > 2 frames; `PreviewAnchor` JVM-tested). Audio output closes when paused and in the background. Not yet exercised on the device: the phone was disconnected when this was written, so `scripts/av-drift-test.sh` has not been run
- [x] Per-clip transform (position/scale/rotation/opacity) with on-preview gestures; gain control UI — inspector sliders and one-finger drag verified on the reference device; pinch and twist are unit-tested maths only (adb cannot inject multi-touch)
- [x] Colour shaders: HLG/Rec.2020 → SDR Rec.709; per-clip override
- [x] Multi-layer compositing (video tracks above one another): one decoder per layer within the device's hardware-decoder limit (top layers win), per-layer transform and opacity, source-over blending in track order; `GlPipeline::drawScene` is reusable offscreen for export. Not yet measured: playback performance with several 4K layers.
- _Status (4a, after preview-perf):_ synthetic 4K60 HEVC (480 frames) and 1080p30 H.264 (300 frames) play on the reference device with every frame decoded once and shown (0 seeks, 0 dropped decodes in steady state); render thread spends ~0.2 ms blit + ~0.15 ms draw + ~0.5 ms swap per frame. The 4K60 criterion is met on synthetic clips only (not real footage). The A/V drift criterion cannot be measured until audio lands, so the gate stays open.
- _Status (audio):_ on the reference device the Oboe stream (AAudio, shared mode, 192-frame burst, 384-frame buffer) holds the master clock to within 0.4 ms over 55 s with zero underruns; estimated output latency ~30 ms. Linear resampling, no downmix beyond the first two channels, no fades at clip edges yet.
- **Gate (open):** 4K60 single-layer playback without drops (met on synthetic clips only); no measurable A/V drift on a long timeline (NOT measured yet: run `scripts/av-drift-test.sh <serial> 5` on the device; both clocks are CLOCK_MONOTONIC based and the audio clock was seen to hold within 0.4 ms over 55 s, which predicts well under one frame over 5 minutes, but that is an inference, not a measurement).

## Phase 5 — Titles and transitions
- [x] Title clips (text, size, colour, alignment, bold, position/scale/rotation/opacity) and composition
- [x] Crossfade transition between adjacent clips (video opacity ramp, equal-power audio, handles-aware limits)
- _Status:_ domain, JSON, preview, export, audio and editor UI are implemented and unit-tested (see the PR for
  what was and was not verified on the device). Not done: other transition types, title animations, custom fonts.
- **Gate:** titles and transitions render in preview and match export.

## Phase 6 — Export
- [x] Offline render loop to MediaCodec encoder (H.264, HEVC) + muxer
- [x] Audio offline mix and AAC encode
- [x] Export UI: resolution/fps/bitrate, progress, cancel, share
- [ ] Optional: static FFmpeg fallback behind a feature flag
- _Status:_ exports the full timeline (all video layers composited with their transform and opacity through the preview's
  `drawScene`, clip gain in the audio mix, gaps black, HLG sources tone-mapped to SDR Rec.709) at the project
  or a lower frame rate, H.264 or HEVC + AAC in MP4, saved through SAF. On the reference device a 4K60 HEVC export runs at ~90 fps
  (1.5x real time) and a 1080p30 H.264 one at ~100 fps. Verified with ffprobe: exact frame counts and PTS grid, audio clicks land on
  their timestamps. The AAC encoder delay (2048 samples) is compensated, so the first 42.7 ms of the mix are not heard. Cancel and
  Share are covered by unit tests only (not exercised on the device); colour fidelity was checked with synthetic charts, not real footage.
- **Gate:** exported file plays correctly with matching A/V sync and colours.

## Phase 7 — CapCut-style features (post-MVP, in this order, revisit priority later)
- [x] Social format presets (9:16, 1:1, 4:5, 16:9), safe zones, per-platform export presets — JVM and host tests
      pass; not yet seen on the device (phone offline during this work)
- [x] Keyframes: position, scale, rotation, opacity (linear / ease / hold), inspector diamond, timeline markers,
      same pose in preview and export — JVM and host tests pass; not yet seen on the device
- [x] Speed changes (0.1x–8x), ramps, reverse, freeze frame — domain, preview, export table, audio (varispeed, muted
      outside 0.25x–4x) and inspector; JVM and host tests pass. On the OnePlus the export of a timeline with a freeze,
      2x, reversed, ramped and 0.5x clip was checked frame by frame and by pitch (`scripts/run-retime-export-test.sh`):
      correct in 3 clean runs, but 2 of the first 6 runs failed (a decoder stall, an audio decode error) while another
      session shared the phone. The inspector, the timeline labels and reverse playback in the preview were not seen.
      Export reliability fixes (fetch waits for late frames, decoder self-recovery, offline audio retries) are in and pass
      host tests, but were not yet re-run on the phone (adb offline): re-run `scripts/run-retime-export-test.sh` ~10 times.
      Follow-ups: pitch-preserving time stretch, frame blending for slow motion
- [x] Chainable shader effects, chroma key, masks, blend modes — domain, JSON, undo, JVM and host tests pass and the
      native engine compiles; the shaders and the inspector are not yet seen on the device (adb offline)
- [ ] Automatic subtitles (on-device transcription) and animated caption styles
  - _Status (captions):_ implemented but not yet verified on the device (adb was offline): whisper.cpp v1.9.4 in the engine,
    model download with checksum, audio -> 16 kHz mono -> word timestamps -> caption title clips on a new track (one undo), four
    static styles, "Auto captions" sheet in the editor. Still to do: run it on the phone.
  - _Status (animated captions):_ implemented and unit-tested, not yet seen on the device (adb was offline): captions keep
    per-word timing; karaoke highlight, word-by-word pop-in, typewriter and bounce entrance styles; per-frame look evaluated
    in Kotlin so preview and export draw the same pictures; style picker with colour options and "restyle all captions"
    (one undo step). The box stays open until the transcription and the animated looks have been run on the phone.
- [ ] Stickers, animated text templates, beat sync
- [x] HDR end-to-end (HLG project colour space, 10-bit compositing, HLG preview, HEVC Main10 export). _Status:_ CPU reference and host tests pass and the engine builds; the HDR surface, the HEVC Main10 HLG encode and the look of the conversions have not been seen on an HDR display (see the device notes in DECISIONS.md)
- [ ] 3D LUTs
- [ ] Vulkan renderer evaluation

## Cross-cutting
- Every clip-manipulation feature ships with unit tests for collisions and gaps.
- Profile on the reference device at the end of each phase (frame time, memory, battery).
- Keep CLAUDE.md and SPECS.md updated when decisions change.

## Lane layout and drops (added after the first on-device review)
- [x] Video stack anchored to the bottom of the timeline panel (overlays above, base below, audio under it)
- [x] Drop zones decided by position with a live native indicator: insert (base), overwrite, new lane, cancel
- [x] Move lanes up/down (toolbar)
- [ ] Verified on the OPPO (the device was unreachable when this was written)
- [ ] Insert on overlay/audio lanes (deferred)

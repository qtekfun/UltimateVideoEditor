# ultimateVE — Implementation Plan

Status: Draft v1 · Date: 2026-10-03. Each phase has a gate; do not start the next until the gate passes.
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
- [ ] Hub UI (project list, empty/welcome state)
- [ ] New Project dialog (resolution, FPS rational, colour space)
- [ ] `project.json` model + serialization (kotlinx.serialization), atomic writes, forward-compatible
- [ ] CRUD: create, clone, delete, rename; import/export via SAF
- [ ] Unit tests: serialization round-trips, repository operations
- **Gate:** projects survive app restarts; JSON validates against the SPECS schema.

## Phase 2 — Domain timeline and operations (pure Kotlin, TDD)
- [x] `FrameIndex`, rational fps, time conversion helpers
- [x] Track/Clip model with invariants
- [x] Split, move, overwrite, ripple delete, ripple append, trim, snapping
- [x] Undo/redo command stack
- [x] Exhaustive unit tests for collisions and gaps
- **Gate:** all operation tests green; invariants fuzz-tested.

## Phase 3 — Timeline canvas and waveforms
- [ ] Timeline `SurfaceView` + native GLES renderer (blocks, playhead, ruler)
- [ ] Scroll and pinch-zoom at 60/120 fps; gesture forwarding; hit-testing
- [ ] Media import (SAF picker, persisted URI permission), media library panel
- [ ] Background waveform extraction and on-disk peak cache
- [ ] Draw waveforms and thumbnails; snapping, split, move, trim via touch
- **Gate:** smooth scroll/zoom on a 50-clip timeline; waveforms appear without UI jank.

## Phase 4 — Decode, preview and audio playback
- [ ] EGL/GLES 3.2 preview compositor on a `SurfaceView`
- [ ] AMediaExtractor/AMediaCodec decode (H.264/HEVC) to AHardwareBuffer
- [ ] LRU frame cache with look-ahead; scrubbing
- [ ] Oboe audio playback + mixer; audio master clock, A/V sync
- [ ] Per-clip transform (position/scale/rotation) with on-preview gestures; gain control
- [ ] Colour shaders: HLG/Rec.2020 → SDR Rec.709; per-clip override
- [ ] Multi-layer compositing (video tracks above one another)
- **Gate:** 4K60 single-layer playback without drops; no measurable A/V drift on a long timeline.

## Phase 5 — Titles and transitions
- [ ] Title clips (text, font, colour, position) and composition
- [ ] Crossfade transition between adjacent clips
- **Gate:** titles and transitions render in preview and match export.

## Phase 6 — Export
- [ ] Offline render loop to MediaCodec encoder (H.264, HEVC) + muxer
- [ ] Audio offline mix and AAC encode
- [ ] Export UI: resolution/fps/bitrate, progress, cancel, share
- [ ] Optional: static FFmpeg fallback behind a feature flag
- **Gate:** exported file plays correctly with matching A/V sync and colours.

## Phase 7 — CapCut-style features (post-MVP, in this order, revisit priority later)
- [ ] Social format presets (9:16, 1:1, 4:5, 16:9), safe zones
- [ ] Keyframes; speed changes, ramps, reverse, freeze frame
- [ ] Chainable shader effects, chroma key, masks, blend modes
- [ ] Automatic subtitles (on-device transcription) and animated caption styles
- [ ] Stickers, animated text templates, beat sync
- [ ] HDR end-to-end (HLG, HEVC Main10), 3D LUTs
- [ ] Vulkan renderer evaluation

## Cross-cutting
- Every clip-manipulation feature ships with unit tests for collisions and gaps.
- Profile on the reference device at the end of each phase (frame time, memory, battery).
- Keep CLAUDE.md and SPECS.md updated when decisions change.

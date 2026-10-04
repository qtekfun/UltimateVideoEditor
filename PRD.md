# ultimateVE (Ultimate Video Editor) — Product Requirements Document

Status: Draft v1 · Date: 2026-10-03 · License: GPL-3.0 (open source)

## 1. Vision

A lightweight, modular, high-performance mobile NLE for Android. The product is a hybrid:
**LumaFusion's** professional multitrack timeline model, plus **CapCut-style** quick-edit
features for creators publishing to YouTube and short-form platforms (TikTok, Shorts, Reels).

Native Android (Kotlin + Jetpack Compose) with a C++/NDK engine for decode, composition,
timeline rendering and audio. Low memory use and hardware-accelerated pipelines are
the differentiators.

## 2. Target users

- Primary: the author (power user of LumaFusion on iPad/iPhone) editing on Android.
- Secondary: open-source community, YouTube/short-form creators on high-end Android devices.

## 3. Platform and constraints

| Item | Decision |
|---|---|
| minSdk | 31 (Android 12) |
| Reference device | OnePlus CPH2841, SM8850, Android 16 (API 36), GLES 3.2, Vulkan (Adreno), 11 GB RAM |
| Form factor | Adaptive (Compose window size classes): phone portrait/landscape, tablet, foldable |
| Engine | C++ (CMake/NDK), library `uveditor_engine` |
| Graphics | OpenGL ES 3.2 first; Vulkan is a possible later evolution |
| Language of code and docs | English |
| Distribution | Open source (GPL-3.0); FFmpeg fallback may be GPL-linked |

## 4. Goals and non-goals

### Goals (MVP)
1. Project hub: create, clone, delete, import, save projects (JSON).
2. Multitrack timeline (video + audio, free placement with snapping), smooth at 60/120 fps.
3. Clip operations: split, move, overwrite, ripple delete, ripple append, trim.
4. Per-clip 2D transform (position, scale, rotation) and audio gain.
5. Hardware decode (H.264/HEVC) with scrub-friendly cache; synchronized audio playback.
6. Audio waveforms generated in background and cached.
7. SDR Rec.709 project colour space with HLG/Rec.2020 clips normalised to it via shaders.
8. Basic titles/text and transitions (crossfade first).
9. Undo/redo for all timeline edits.
10. Basic export (H.264/HEVC via MediaCodec encoder, offline frame-by-frame).

### Non-goals (MVP)
- 3D LUTs, end-to-end HDR (HLG) projects, cloud features, collaboration.
- All CapCut-style features listed in section 6 (post-MVP, scheduled at the end of the plan).

## 5. Key user stories

- As an editor I create a project choosing resolution, FPS and colour space.
- I import clips from the device via the system picker without copying them.
- I scrub the timeline with no dropped frames and see waveforms on audio.
- I split, trim, move and delete clips with magnetic snapping, and undo any step.
- I position/scale/rotate a clip directly on the preview.
- I mix an iPhone HLG clip with SDR clips and they look consistent.
- I add a title and a crossfade between two clips.
- I export the result as an MP4 and share it.

## 6. Post-MVP roadmap (all wanted, scheduled last)

- Captions (typed or imported from subtitle files) with animated social styles.
- Keyframes, speed changes, speed ramps, reverse, freeze frame.
- Social format presets (9:16, 1:1, 4:5, 16:9), safe zones, per-platform export.
- Chroma key, masks, chainable shader effects, blend modes.
- Stickers, animated text templates, caption styling, beat sync.
- HDR (HLG) end-to-end projects, 3D LUTs, Vulkan renderer.
- FFmpeg fallback for unsupported formats.

## 7. Success criteria

- Timeline scroll/zoom holds 60 fps (120 fps on capable displays) on the reference device.
- Scrub latency to a decoded frame is under ~100 ms inside the cache window.
- 4K60 H.264/HEVC playback of a single layer without dropped frames.
- Peak native frame cache stays within its configured budget (default 1 GB high-end).
- No A/V drift over a 30-minute timeline (integer-frame time base).
- Unit-test coverage of all clip operations including collision and gap cases.

## 8. Risks

| Risk | Mitigation |
|---|---|
| Vendor MediaCodec quirks | Develop on the real reference device; wrap codec in a single class with explicit error codes |
| Timeline SurfaceView + Compose interop | Isolate in an `AndroidView` host with a thin JNI surface |
| Multi-layer decode limits (concurrent hardware decoders) | Query codec capabilities; decoder pool with priorities |
| FFmpeg licensing | GPL-3.0 chosen; document linkage and sources |
| Scope creep | Strict phase gates in PLAN.md |

## 9. Privacy (hard requirement)

The app works entirely on the device: no network access, no accounts, no analytics, no crash reporting, no AI or
machine-learning features and no third-party services. Features that would need them are out of scope. Details and how to
verify it: `docs/PRIVACY.md`.

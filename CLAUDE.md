# ultimateVE — Claude Code Guide

Lightweight, high-performance Android video editor (LumaFusion-style timeline with CapCut-style
quick-edit features, added later). Open source, GPL-3.0.
Read `PRD.md` (what), `SPECS.md` (how), `PLAN.md` (order of work) before writing code.

## Project facts
- App name: ultimateVE · Application ID / namespace: `com.ultimatevideo.uveditor`
- Kotlin + Jetpack Compose (UI), C++20 via CMake/NDK (engine), native library `uveditor_engine`
- minSdk 33, targetSdk 36, compileSdk 37, ABI `arm64-v8a`
- Graphics: OpenGL ES 3.2 first (Vulkan later). Audio: Oboe/AAudio. Decode/encode: NDK MediaCodec.
- Language: code, comments, commits and docs in English.

## Environment (Fedora, no Android Studio)
- `ANDROID_HOME=~/Android/Sdk`, JDK 21 (Temurin). Use `sdkmanager` for NDK/CMake/platforms.
- Build and test from the CLI with the Gradle wrapper:
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:connectedDebugAndroidTest`
  - `scripts/run-native-tests.sh` (host-built C++ tests for cache/time/colour math, needs only g++)
- Reference device is physical over wireless adb. Several adb transports can appear for the same
  device, so always pass `adb -s <serial>`; for Gradle use `ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest` (otherwise the install fails). The screen must be unlocked to view the UI. Parallel agents share the same phone and the same package, so installs overwrite each other; check `dumpsys window | grep mCurrentFocus` before any `input tap`. Device: OnePlus CPH2841, SM8850, Android 16, 11 GB RAM.

## Engineering principles
1. **UI/engine isolation.** Compose never draws timeline clips and never touches decode. Only
   `engine/EngineClient` calls JNI. JNI layer contains bindings only, no business logic.
2. **Timeline in a SurfaceView.** The timeline canvas and preview are `SurfaceView`s rendered from C++/GLES.
   Do not draw clips, waveforms or the playhead with Compose.
3. **Integer time.** All timeline coordinates are `FrameIndex` integers. Never floats or seconds for
   positions. FPS is rational (`fpsNum/fpsDen`). Convert with integer math only.
4. **MVI.** `State` (immutable) / `Intent` (sealed) / `Effect` (one-shot) with `StateFlow`. Reducers are pure.
   Timeline state is independent from rendering; the engine receives immutable snapshots.
5. **Zero-copy, no blocking.** Frames live in `AHardwareBuffer`; no JVM heap frame copies. Never block the
   UI or render thread with I/O or decoding.
6. **Explicit errors.** Native code returns error codes with context; JNI maps them to typed Kotlin errors.
   No silent failures, no empty `catch`.
7. **Strict typing.** Kotlin: no `!!`, minimise nullable types, `explicitApi` for library-like modules.
   C++: RAII, no raw owning pointers, `-Wall -Wextra -Werror` for engine code.

## Native host tests
Pure-logic C++ (snapshot parsing, viewport, hit-testing, waveform peaks) is tested on the desktop without GoogleTest:
`cmake -S app/src/main/cpp/tests -B /tmp/uv-host -G Ninja && cmake --build /tmp/uv-host && ctest --test-dir /tmp/uv-host`
(CMake/Ninja from `$ANDROID_HOME/cmake/<ver>/bin`). Add new pure sources to `tests/CMakeLists.txt`.
`uv_audio_host_tests` covers the audio core (time math, clock mapping, resampler, mixer, decode worker with a fake decoder).
Audio on device: `AudioPlaybackInstrumentedTest` (offline tone/gain/resample/seek checks plus a ~60 s Oboe clock-drift and
latency run logged under tag `UVAudioTest`). Its assets are ffmpeg-generated tones in `src/androidTest/assets`. The app and test
APKs share one package on the device, so do not run two agents' installs/instrumentation against it at the same time.
A debug-only harness for the native timeline: `adb -s <serial> shell am start -n com.ultimatevideo.uveditor/.debug.TimelineDemoActivity`
(push a file with audio to `/sdcard/Android/data/com.ultimatevideo.uveditor/files/demo_media.mp4` to see waveforms and, for a
video file, the thumbnail filmstrip). `--ef zoom <factor>` zooms in at start (adb cannot pinch). In debug builds
`adb shell setprop debug.uveditor.atlas_bytes <n>` shrinks the thumbnail atlas to exercise slot eviction (logged under tag `uv_thumb`).
`uv_thumbnail_host_tests` covers thumbnail tile math, the atlas slot LRU, the on-disk tile store and YUV conversion; without CMake:
`g++ -std=c++20 -Iapp/src/main/cpp app/src/main/cpp/tests/thumbnail_host_tests.cpp app/src/main/cpp/core/error.cpp app/src/main/cpp/thumbnail/thumb_store.cpp -o /tmp/thumb_tests && /tmp/thumb_tests`.
Export: `uv_export_host_tests` covers `encode/export_math.h` (PTS, audio sample tiling, progress, clip selection). A debug-only
harness exports a file without UI: `am start -n com.ultimatevideo.uveditor/.debug.ExportDemoActivity --es video <in.mp4> --es out <out.mp4>
[--es codec avc|hevc] [--ei w 1280 --ei h 720 --ei fps 30] [--es layout single|split|layers]`, then `ffprobe` the pulled file; the outcome is
written to `<out>.result.txt` (see the class comment). Exported audio is shifted earlier by the AAC encoder delay (`kAacDelaySamples`).

A/V drift: `scripts/av-drift-test.sh <serial> [minutes]` generates a beep+flash clip, seeds a project of N copies
(app-private `files/projects/avdrift`, needs a debug build for `run-as`), and logs tag `UVSync` while you open it and play
(`adb shell setprop log.tag.UVSync DEBUG` is set by the script). Playback model: audio is the master clock; the preview runs a native
clock and is re-anchored only on composition change or >2 frames of drift (SPECS.md 5.3).

## Testing rules
- Every clip-manipulation feature (split, move, overwrite, ripple delete/append, trim) needs unit tests
  covering collisions, gaps, and boundary frames. Write them with the operation, not afterwards.
- `domain/` is pure Kotlin so tests run on the JVM without a device.
- Run unit tests and a debug build before reporting a task done; say plainly if something was not run.

## Conventions
- Kotlin DSL for Gradle, version catalog in `gradle/libs.versions.toml`.
- Package layout and module roles: see `SPECS.md` section 2.
- Project files are JSON under app-private storage; media are referenced by `content://` URIs, never copied.
- Match surrounding code style; keep comments sparse and explain *why*.
- Follow `PLAN.md` phase gates. Update `PLAN.md` checkboxes and these docs when decisions change.
- For library/API syntax (Compose, AGP, Oboe, Media3, NDK APIs) check current docs rather than memory.

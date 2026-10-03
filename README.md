# ultimateVE

A lightweight, high-performance video editor for Android, aimed at creators who edit for YouTube and short-form
platforms. It pairs a LumaFusion-style multitrack timeline (a magnetic base track with free overlays) with CapCut-style
quick-edit tools. Native Android: Kotlin + Jetpack Compose for the UI, a C++ engine (NDK) for decoding, compositing,
timeline rendering, audio and export.

Licensed under [GPL-3.0](LICENSE). Third-party components are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

> **Status: early development.** Most features are implemented and unit-tested, but a good part of the native pipeline
> has only been exercised on one test phone, with synthetic clips. See [What works](#what-works) before relying on anything.

## What works

Implemented (and what has actually been verified; the full per-item notes are in [PLAN.md](PLAN.md)):

| Area | State |
|---|---|
| Project hub: create, clone, rename, delete, import/export (JSON, atomic writes) | Implemented; JVM-tested. Seen on the device |
| Multitrack timeline: split, move, trim, overwrite, ripple delete, undo/redo | Implemented; heavily unit-tested (collisions, gaps, randomized invariants) |
| Magnetic base track with overlays that follow it (delete, insert, reorder, trim) | Implemented; unit-tested. Not yet verified by touch on the device |
| Native timeline canvas (`SurfaceView`, GLES) with waveforms and thumbnail filmstrip | Seen on the device; frame rate not measured, pinch-zoom only host-tested |
| Preview: H.264/HEVC hardware decode to `AHardwareBuffer`, GLES 3.2 compositor, multilayer | Seen on the device with synthetic clips |
| Audio playback (Oboe) with the audio clock as master, per-clip gain | Clock drift measured over ~55 s; long-run A/V drift not yet measured; sound not listened to |
| Per-clip transform (position, scale, rotation, opacity), keyframes | One-finger edits seen on the device; pinch/twist and keyframes unit-tested only |
| Titles and crossfade transitions | Implemented; not seen on the device |
| Speed changes, ramps, reverse, freeze frame | Implemented; export checked frame by frame on the device, preview/UI not seen |
| Effects, chroma key, masks, blend modes | Implemented; shaders not yet seen on the device |
| Social format presets, safe zones, upload presets | Implemented; not seen on the device |
| Auto captions (on-device whisper.cpp, model downloaded on demand) | Implemented; not yet run on a device |
| HDR: HLG project colour space, HEVC Main10 export | Implemented; not seen on an HDR display |
| Export to MP4 (H.264 / HEVC + AAC), 4K60 HEVC at ~90 fps on the test phone | Verified with `ffprobe` on synthetic clips; cancel/share untested on device |

Not done yet: FFmpeg fallback, 3D LUTs, stickers and animated text templates, beat sync, Vulkan renderer, pitch-preserving
time stretch. The roadmap lives in [PLAN.md](PLAN.md); the reasoning behind choices in [DECISIONS.md](DECISIONS.md).

## Architecture

```
app/src/main/kotlin/com/ultimatevideo/uveditor/
  ui/         Compose screens (hub, editor, export dialogs); never draws timeline clips
  mvi/        State / Intent / Effect base classes (StateFlow)
  domain/     Pure Kotlin timeline model, edit operations, undo/redo, render plan (no Android imports)
  data/       Project JSON, repository, media import, domain mapping
  engine/     Kotlin facade over the native engine (the only caller of JNI)
app/src/main/cpp/
  core/ decode/ cache/ render/ encode/ audio/ captions/ thumbnail/ timeline_view/ jni/   (C++20, library `uveditor_engine`)
```

- **MVI.** Each screen has an immutable `State`, a sealed `Intent` and one-shot `Effect`s. The editor keeps the timeline
  in its ViewModel and publishes immutable snapshots to the engine.
- **Integer time.** Every timeline position is an integer `FrameIndex`; the frame rate is rational. No floating-point
  seconds, so there is no A/V drift from rounding.
- **Two `SurfaceView`s.** The preview and the timeline canvas are drawn by C++/OpenGL ES, so scrubbing and scrolling do
  not trigger Compose recomposition.
- **Zero-copy frames.** Decoded frames live in `AHardwareBuffer`s shared with the GPU, in an LRU cache with a strict byte budget.
- **One render plan.** Preview, export and audio all consume `domain/RenderPlan.kt`, so what you see is what is exported.

Details: [SPECS.md](SPECS.md) (technical), [PRD.md](PRD.md) (product), [CLAUDE.md](CLAUDE.md) (engineering rules).

## Requirements

- Android device with Android 13 or newer (minSdk 33); developed on a high-end phone (SM8850, Android 16).
- JDK 21, Android SDK with platform 37, NDK `29.0.14206865` and CMake `3.31.6`. Android Studio is not required.
- `git` with submodule support. `g++` (C++20) for the host-side native tests.

## Setup (command line, Linux)

```sh
git clone --recurse-submodules https://github.com/qtekfun/UltimateVideoEditor.git
cd UltimateVideoEditor
# if you cloned without --recurse-submodules (whisper.cpp is needed by the native build):
git submodule update --init --depth 1

export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=$HOME/Android/Sdk      # command-line tools installed under $ANDROID_HOME/cmdline-tools/latest
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-37.0" "ndk;29.0.14206865" "cmake;3.31.6"
```

The first native build compiles ggml/whisper for arm64 and takes a while; on a busy machine use
`nice -n 15 ./gradlew ... --max-workers=2`.

## Build, test, install

```sh
./gradlew :app:testDebugUnitTest          # JVM unit tests
./gradlew :app:assembleDebug              # app/build/outputs/apk/debug/app-debug.apk
scripts/run-native-tests.sh               # host-built C++ tests (g++ only)

# CMake host tests (needs cmake + ninja)
cmake -S app/src/main/cpp/tests -B build/uv-host -G Ninja
cmake --build build/uv-host && ctest --test-dir build/uv-host --output-on-failure

adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <serial> shell am start -n com.ultimatevideo.uveditor/.MainActivity
```

CI runs the unit tests, the debug build and the native host tests on every pull request
(`.github/workflows/ci.yml`) and uploads the debug APK as an artifact.

### Device notes

- **Wireless adb** can list the same phone under several transports (for example `...(2)._adb-tls-connect._tcp`).
  Always pass `adb -s <serial>`, and for Gradle use `ANDROID_SERIAL=<serial> ./gradlew ...`; otherwise installs fail.
- **Never run `connectedDebugAndroidTest` on a device whose app data you care about.** The instrumentation APK shares the
  app's package, and installing and uninstalling it **clears the app's data, including your projects**.
- The speech model for auto captions is downloaded at runtime (first use) and never bundled in the APK.
- The screen must be unlocked to see the UI; check which app is in the foreground before sending `adb shell input`.

## Roadmap

[PLAN.md](PLAN.md) lists the phases with their gates and what is still open: verifying the pipeline on real footage and
the long-run A/V drift, then the remaining CapCut-style tools (stickers, text templates, beat sync), 3D LUTs and an
FFmpeg fallback for unsupported formats.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

GPL-3.0. See [LICENSE](LICENSE) and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

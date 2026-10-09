# ultimateVE — Claude Code Guide

Lightweight, high-performance Android video editor (LumaFusion-style timeline with CapCut-style
quick-edit features, added later). Open source, GPL-3.0.
Read `PRD.md` (what), `SPECS.md` (how), `PLAN.md` (order of work and the verification checklist) and `docs/ARCHITECTURE.md` (overview and
how to add a feature safely) before writing code. Privacy is a hard rule: no AI, no network, no third-party services (`docs/PRIVACY.md`).

## Project facts
- App name: ultimateVE · Application ID / namespace: `com.qtekfun.ultimatevideoeditor`
- Kotlin + Jetpack Compose (UI), C++20 via CMake/NDK (engine), native library `uveditor_engine`
- minSdk 31, targetSdk 36, compileSdk 37, ABI `arm64-v8a`
- Graphics: OpenGL ES 3.2 first (Vulkan later). Audio: Oboe/AAudio. Decode/encode: NDK MediaCodec.
- Language: code, comments, commits and docs in English.

## Environment (Fedora, no Android Studio)
- `ANDROID_HOME=~/Android/Sdk`, JDK 21 (Temurin). Use `sdkmanager` for NDK/CMake/platforms.
- Build and test from the CLI with the Gradle wrapper:
  - `./gradlew :app:assembleDebug`
  - `./gradlew :app:testDebugUnitTest`
  - `./gradlew :app:connectedDebugAndroidTest`
  - `scripts/run-native-tests.sh` (host-built C++ tests for cache/time/colour math, needs only g++)
  - `scripts/qa-smoke.sh <serial> [--quick]` (device regression runner on synthetic media, suffixed `qa` build: `docs/QA.md`)
- Reference device is physical over wireless adb. Several adb transports can appear for the same
  device, so always pass `adb -s <serial>`; for Gradle use `ANDROID_SERIAL=<serial> ./gradlew :app:connectedDebugAndroidTest` (otherwise the install fails). The screen must be unlocked to view the UI. Parallel agents share the same phone: build with `-Puveditor.appIdSuffix=<name>` to install next to the others (separate data), and run device sessions under `flock /tmp/pixel-device.lock`; installs of the same package overwrite each other; check `dumpsys window | grep mCurrentFocus` before any `input tap`. Device: OPPO CPH2841, SM8850, Android 16, 11 GB RAM.

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

## Titles and transitions
Both flow through one plan, `domain/RenderPlan.kt` (`renderClips()`): a transition extends the clips around the
cut and fades the incoming one in, so the compositor only draws layers with an opacity. Preview, export and
audio must keep using it; do not special-case transitions in native code. Titles are rasterised in Kotlin
(`engine/title`) and uploaded as textures. Details: SPECS.md 5.8.

## Privacy rule (hard requirement)
The app is offline by design: no AI or ML features, no network access, no third-party services, no analytics, no
accounts. The only permissions are the export's foreground-service ones and POST_NOTIFICATIONS (docs/PRIVACY.md). Never add the INTERNET permission, a networking API, a telemetry/crash-reporting/ads SDK or a model download;
`OfflineGuaranteeTest` fails the build if one appears. Anything that would need a server or a downloaded model is out of
scope; use classical, on-device algorithms instead. Details: `docs/PRIVACY.md`.

## Languages
English is the source language, Spanish the first translation. Every text the user reads comes from `res/values*/strings.xml`
(`stringResource`/`pluralStringResource` in composables); text made outside a composable (view models, effects, notification models,
pure classes with JVM tests) is a `UiText` (`ui/text/UiText.kt`), resolved where it is shown. Positional arguments, plurals for counts, no text
built from fragments. A new language is a `values-xx/strings.xml` plus a line in `res/xml/locales_config.xml`: `docs/TRANSLATING.md`
(rules, glossary), SPECS 5.43. `TranslationsGuardTest` and `HardCodedTextRatchetTest` (allow-list `app/src/test/i18n/unmigrated.txt`,
only ever shrinks; do not add to it) fail the build for a missing translation or new hard-coded text; lint does the same on release.
Tests assert on `UiText.english()`. Device scripts pin the app to English (`scripts/device-ui.sh`, `docs/QA.md`). No translation SDK or
download, ever (privacy rule).

## Toolbar guide
Every editor icon needs an entry in `ui/editor/guide/ToolbarGuide.kt` (in-app guide, also the source of the website's icons page via
`scripts/gen-site.py`); `ToolbarGuideTest` fails otherwise. Pages/PDF workflows: `docs/RELEASE.md`, SPECS 5.36.

## Captions
Captions are ordinary title clips (ids start with `caption-`). They are typed by the user or imported from `.srt` / `.vtt`
files (`domain/captions/Subtitles.kt`, parsing and frame conversion, unit tested); there is no speech recognition. Styles,
word timing and the animator are pure Kotlin in `domain/captions/`; the sheet and its view model are in
`ui/editor/captions/`. Licence notices are in `THIRD_PARTY_NOTICES.md`; update it when adding a dependency.

## Native host tests
Pure-logic C++ (snapshot parsing, viewport, hit-testing, waveform peaks) is tested on the desktop without GoogleTest:
`cmake -S app/src/main/cpp/tests -B /tmp/uv-host -G Ninja && cmake --build /tmp/uv-host && ctest --test-dir /tmp/uv-host`
(CMake/Ninja from `$ANDROID_HOME/cmake/<ver>/bin`). Add new pure sources to `tests/CMakeLists.txt`.
`uv_repair_host_tests` covers the optical-flow, denoise and deflicker reference maths. Device measurements of slow motion and repair:
`scripts/check-slowmo-export.sh`, `check-slowmo-preview.sh`, `check-repair-export.sh <serial>` (need ffmpeg, the app built with an
id suffix and its androidTest APK installed; set `PKG`).
`uv_audio_host_tests` covers the audio core (time math, clock mapping, resampler, mixer, decode worker with a fake decoder).
Audio on device: `AudioPlaybackInstrumentedTest` (offline tone/gain/resample/seek checks plus a ~60 s Oboe clock-drift and
latency run logged under tag `UVAudioTest`). Its assets are ffmpeg-generated tones in `src/androidTest/assets`. The app and test
APKs share one package on the device, so do not run two agents' installs/instrumentation against it at the same time.
A debug-only harness for the native timeline: `adb -s <serial> shell am start -n com.qtekfun.ultimatevideoeditor/.debug.TimelineDemoActivity`
(push a file with audio to `/sdcard/Android/data/com.qtekfun.ultimatevideoeditor/files/demo_media.mp4` to see waveforms and, for a
video file, the thumbnail filmstrip). `--ef zoom <factor>` zooms in at start (adb cannot pinch). In debug builds
`adb shell setprop debug.uveditor.atlas_bytes <n>` shrinks the thumbnail atlas to exercise slot eviction (logged under tag `uv_thumb`).
`uv_thumbnail_host_tests` covers thumbnail tile math, the atlas slot LRU, the on-disk tile store and YUV conversion; without CMake:
`g++ -std=c++20 -Iapp/src/main/cpp app/src/main/cpp/tests/thumbnail_host_tests.cpp app/src/main/cpp/core/error.cpp app/src/main/cpp/thumbnail/thumb_store.cpp -o /tmp/thumb_tests && /tmp/thumb_tests`.
HDR: `uv_hdr_host_tests` covers `render/color_space.h` and the HLG/PQ conversions of `render/color_math.h` (CPU reference of the
composite shader; change `render/shaders.h` and `color_math.h` together); without CMake:
`g++ -std=c++20 -Iapp/src/main/cpp app/src/main/cpp/tests/hdr_host_tests.cpp -o /tmp/hdr_tests && /tmp/hdr_tests`.
Export: `uv_export_host_tests` covers `encode/export_math.h` (PTS, audio sample tiling, progress, clip selection). A debug-only
harness exports a file without UI: `am start -n com.qtekfun.ultimatevideoeditor/.debug.ExportDemoActivity --es video <in.mp4> --es out <out.mp4>
[--es codec avc|hevc] [--ei w 1280 --ei h 720 --ei fps 30] [--es layout single|split|layers]`, then `ffprobe` the pulled file; the outcome is
written to `<out>.result.txt` (see the class comment). Exported audio is shifted earlier by the AAC encoder delay (`kAacDelaySamples`).

Save frame as image (SPECS 5.35): `scripts/check-frame-export.sh <serial>` runs the checks below; `FrameDemoActivity` (debug) renders frames of a three-clip project from the `scripts/check-retime-export.py gen` source through `NativeFrameRenderer` and reads each frame's number back (`--es mode frames --es frames 0,37,60`); `--es mode export` writes the same project as `frame_export.mp4` for an ffmpeg comparison. `still_math.h` is covered by `uv_export_host_tests`.

A/V drift: `scripts/av-drift-test.sh <serial> [minutes]` generates a beep+flash clip, seeds a project of N copies
(app-private `files/projects/avdrift`, needs a debug build for `run-as`), and logs tag `UVSync` while you open it and play
(`adb shell setprop log.tag.UVSync DEBUG` is set by the script). Playback model: audio is the master clock; the preview runs a native
clock and is re-anchored only on composition change or >2 frames of drift (SPECS.md 5.3).

Retiming: `scripts/run-retime-export-test.sh <serial> [runs]` exports a timeline with a freeze, 2x, reversed, ramped and 0.5x clip
from a synthetic source (every frame carries its own number in binary squares, plus a 440 Hz tone), then
`scripts/check-retime-export.py` reads the frame numbers and the pitch back from the MP4 and compares them with what the domain
mapping (`domain/Retime.kt`, SPECS.md 5.14) says. Needs the app and androidTest APKs installed (`adb install -r` / `-r -t`); the test
APK's instrumentation does not clear app data when run with `am instrument`, but it does restart the app's process, so the script
refuses to run while the app is in the foreground on the device (FORCE=1 overrides). Do not run it while another session installs
builds. It also checks a plain 1x clip frame for frame (`plain_*` outputs) and prints substitutions/retries from the native log.

## Testing rules
- Every clip-manipulation feature (split, move, overwrite, ripple delete/append, trim) needs unit tests
  covering collisions, gaps, and boundary frames. Write them with the operation, not afterwards.
- `domain/` is pure Kotlin so tests run on the JVM without a device.
- Run unit tests and a debug build before reporting a task done; say plainly if something was not run.
- **Every defect found on a device gets a regression test in the same PR** (a JVM or host test where one can see it: `docs/QA.md` lists
  what each existing guard protects). A defect only a device shows gets a scripted check in `scripts/qa-smoke.sh` (and its assertion
  in `scripts/qa/check-export.py`, with a broken input in `scripts/qa/selftest.sh` proving it can fail). A fix without either is not done.
- **No debug property may switch a fix off** (DECISIONS.md, "No debug switch"); `DebugPropertyGuardTest` allow-lists what the code reads.
- Before a release run `scripts/qa-smoke.sh <serial>` on a device and record the result in `docs/RELEASE.md` (checklist step 7).
  Shared phone rules: suffixed build only (`-Puveditor.appIdSuffix=qa`), `flock /tmp/pixel-device.lock`, never the unsuffixed app.

## Huawei tablet installs
`scripts/huawei-install.sh <apk> [serial]` installs on the MatePad and presses EMUI's install confirmations (clickable
installer buttons only; it never bypasses security or a secure lock, exit 4 when locked). See docs/HUAWEI_TABLET.md.

## Conventions
- Kotlin DSL for Gradle, version catalog in `gradle/libs.versions.toml`.
- Package layout and module roles: see `SPECS.md` section 2.
- Project files are JSON under app-private storage; media are referenced by `content://` URIs, never copied.
  Next to `project.json` live `project.json.tmp` (interrupted write), `.bak` (last good save) and `.corrupt` (a damaged file kept by Recover); see SPECS 4.1.
- Match surrounding code style; keep comments sparse and explain *why*.
- Releases: version only in `gradle/version.properties`; the release variant is R8-minified, so a new JNI class,
  callback name or `@Serializable` model outside `engine/`/`data/model` needs a keep rule in `app/proguard-rules.pro`.
  Signing material (`keystore.properties`, `*.jks`) is never committed. See `docs/RELEASE.md`.
- Follow `PLAN.md` phase gates. Update `PLAN.md` checkboxes and these docs when decisions change.
- For library/API syntax (Compose, AGP, Oboe, Media3, NDK APIs) check current docs rather than memory.

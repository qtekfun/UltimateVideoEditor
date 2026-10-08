# QA safety net

Two layers keep the kind of defect that reached the user (a silent export, black saved frames, a crash on an Android version nobody
had tested, a stall that only a 16 fps clip showed) from reaching the user again:

1. **Host and JVM guards**, in CI on every PR: pure tests that fail the build. They cannot run a codec, so they pin what a program can
   see (tables, manifest, source patterns, simulations).
2. **`scripts/qa-smoke.sh`**, on a phone, before a release and after anything that touches decode, export, audio or the editor. It is
   not in CI. It exports and inspects real files, so it sees what the first layer cannot.

Rule (CLAUDE.md, Testing rules): every defect found on a device gets a regression test in the same PR, and a device-only defect gets a
check in the smoke runner. Add the row to the table below when you do.

## Running the smoke runner

```bash
# 1. Build the QA variant: a separate package next to the real app, so the user's app and project are never touched.
flock /tmp/gradle-build.lock ./gradlew :app:assembleDebug -Puveditor.appIdSuffix=qa
# 2. Run it (installs the APK, makes the synthetic media, seeds five projects, checks, prints a table). Needs adb, ffmpeg, python3.
scripts/qa-smoke.sh <adb-serial> --install            # about 3 minutes
scripts/qa-smoke.sh <adb-serial> --quick --install    # the 2 minute subset
scripts/qa-smoke.sh <adb-serial> --only FGS,UI-VER    # single checks (ids below)
scripts/qa/selftest.sh                                # no device: proves the assertions fail on broken files
```

Exit code 0 means every check passed (a SKIP is not a failure), 1 a check failed, 2 a refusal or bad usage, 3 the device is busy,
4 the screen is locked. The table is also written to `/home/qtekfun/uvdata/qa-smoke/last-result.txt`; logs and the exported
files of the last run stay in that directory (big files never go to `/tmp`, which is RAM).

Safety, enforced by the script:

- Only a suffixed package (default `com.qtekfun.ultimatevideoeditor.qa`). The unsuffixed app, which holds the user's project, is refused.
  The only package it ever uninstalls (`--reinstall`) or force-stops is the QA one. Nothing is `pm clear`ed.
- It takes `flock /tmp/pixel-device.lock` (`/tmp/tablet-device.lock` on a Huawei, where it installs with `scripts/huawei-install.sh`)
  for the whole run, and never taps unless one of its own windows has the focus. If `com.qtekfun.mapas` is in front it exits 3 and
  leaves the phone alone. It refuses an OPPO / CPH2841 / PGEM10 outright.
- It sets no system property. It only reads `debug.uveditor.*` (hygiene check); `--fix-props` resets stale ones to empty.
- Synthetic media only (`scripts/qa/gen-media.sh`: H.264/HEVC, three PCM soundtracks, AAC, VFR 30 and 27 fps, 60 fps, HLG, a
  rotated file, photos, a frame-numbered clip). The device lives under `/sdcard/Android/data/<pkg>/files`.
- Wireless adb drops now and then: `adb mdns services`, then `adb connect <host:port>`.

## The checks

| Check | Defect | What it asserts |
|---|---|---|
| `HYG-1` | D5 | no `debug.uveditor.*` property is at an unsafe value: diagnostics (`export_perf`, `timeline_stats`, `atlas_bytes`) unset or 0; the removed switches (`decode_gap`, `export_enc_flags`, `export_cull`) unset or 1, because 0 turned a fix off in a build that still read it; anything unknown unset |
| `ENV-1` | D7 | the installed APK's versionName equals `gradle/version.properties` (a stale build invalidates the run) |
| `PROBE` | D6 | `HdrProbeDemoActivity`: when an HEVC encoder configures the exporter's own Main10 HLG format, `supportsHlgExport` says yes |
| `Q-FPS` / `M-FPS` | D5 | export of the seeded project: exact frame count, strictly increasing presentation times one frame apart |
| `Q-AUD-*` / `M-AUD-*` | D1 | the export has an audio stream and every clip's seconds are not silent (mean volume above -60 dB): PCM s16le, s16be, s24le, AAC in MP4, AAC beside VFR 30 / 27 / CFR 60 and HEVC |
| `Q-TAGS` / `M-TAGS` | D6 | SDR export is tagged bt709 / bt709 / bt709, limited range |
| `Q-SEEK` / `M-SEEK` | D5 | the engine log of the export has no `decoder stalled` and at most `6 x clips + 6` decoder seeks (a seek per missing frame is hundreds) |
| `EXP-BUDGET` | D5 | the mixed 30 / 27 / 60 fps, 16 s export finishes within 120 s (about 7 s on a Pixel 8) |
| `FGS` | D4 | the export, started like the dialog starts it, shows `isForeground=true` with a non-zero service type in `dumpsys activity services`, the app process survives, and the log has no `InvalidForegroundServiceTypeException` / `FATAL EXCEPTION` |
| `EXP-HLG` | D6 | an HLG export, when the device can: HEVC Main 10, hvc1, bt2020nc / arib-std-b67 / bt2020, tv range; the probe and the exporter must agree (probe says no but the export works, or probe says yes but the exporter refuses, both fail) |
| `Q-VERIFY` / `M-VERIFY` / `VERIFY-HLG` | D10 | the app's own post-export verification of the file it just wrote says `verified` (SDR projects and the 10-bit HLG export); `skipped`, `could_not_verify`, `warning` or no verification at all fail the run |
| `VERIFY-DAMAGE` | D10 | `QaExportActivity --es damage zero2mb|zerotail|garble` damages the finished file (the last 2 MB zeroed, the data of the last 45 frames zeroed, a burst of bits flipped in each of the last 45 frames) before the verification runs; the verification must say `warning`: `verified` (a corrupted file passed), `skipped`, `could_not_verify` or nothing fail |
| `EXP-ROT` | D9 | a clip with a 90 degree container rotation is exported turned exactly once: of the four orientations of the source the export matches the container's best, by 3 dB or more |
| `FRAME` | D2 | `FrameDemoActivity` saves six frames of a three-clip project (plain from mid-GOP, reversed, 2x): each JPEG is not flat and matches the same frame of the export (PSNR 40 dB or more) |
| `UI-FRAME` | D2 | real UI: open a project, tap "Save frame as image", the new JPEG in `Pictures/ultimateVE` is not flat and matches the first frame of the clip |
| `UI-DRAG-CLIP` | D8 | real touch (`scripts/qa/touch/Touch.java`, one finger with a hold, which `adb shell input` cannot make): in the seeded `QA drag` project the overlay clip is held for 0.9 s and dragged right (it must move, and stay selected), then dragged left with no hold (it must move back). Fails on 0.3.5, where a hold followed by a drag did nothing. Calibrated for a phone in portrait with the tray collapsed; SKIPs when the canvas or the project cannot be found |
| D9 | A media tray tile could not be dragged onto the timeline: the platform drag and drop never started from a tile on the owner's phone (and could not be started by injected touches), so only tap-to-add worked | `TrayDragMachineTest` (hold, slop, second finger, drop target from coordinates with the lane height and scroll, edge scrolling) and `TrayViewModelTest` (snap line) | `UI-DRAG-TRAY` |
| `UI-DRAG-TRAY` | D9 | real touch: in the seeded `QA drag` project the tray is opened, the unused asset's tile is held 0.5 s and dragged onto the overlay lane in free space (the clip must land on the overlay lane near the dropped frame and leave the base alone), then held again and dropped on the base lane at the cut between the second and third clip (the clip must be inserted there and the third clip must move by its length). Read from the saved `project.json`. Fails on 0.3.8, where the platform drag and drop did not start from a tile and nothing was placed. Calibrated for a phone in portrait; SKIPs when the canvas, the tile or the project cannot be found |
| `UI-VER` | D7 | real UI: the hub footer reads `Engine v<versionName>` |
| `UI-THUMB` | D3 | real UI: open a project of eight photo clips; no `uv_thumb` warning in the app's log and no "Could not generate thumbnails" / "No filmstrip" on screen |
| `UI-EXPORT` | D6 | real UI: the export dialog of an SDR project preselects its own size; of an HLG project it offers and preselects "HDR (HLG, 10-bit HEVC)" (or says the device cannot, and the probe agrees) |

`scripts/qa/check-export.py` holds the assertions; `scripts/qa/selftest.sh` (in CI) feeds them good and deliberately broken
files: no audio, silent audio, wrong frame count, untagged colour, 8-bit "HLG", a clip rotated twice or never, a black picture, a
seek per missing frame. A check that cannot fail is removed or fixed.

The harnesses behind the checks are debug-only activities: `QaExportActivity` (exports a seeded `project.json` through the dialog's
own executor and foreground service), `FrameDemoActivity`, `HdrProbeDemoActivity`.

## The defects and their guards

| | What reached the user | Host / JVM guard (CI) | Device check |
|---|---|---|---|
| D1 | The base track exported with no sound: the clips' audio was uncompressed PCM (`lpcm`, `sowt`, `twos`, `in24`) in a MOV, which Android's extractor does not list, so the probe said `hasAudio=false` | `MovAudioScanTableTest` (every PCM flavour, four box layouts, track orders, 64-bit headers, damaged files, and files written by ffmpeg when it is installed), `MovAudioScanTest`, `mov_pcm_host_tests.cpp`, `LumaFusionPackageGuardTest` (volume 0 is -96 dB, audio kept) | `*-AUD-*` |
| D2 | "Save frame as image" saved completely black pictures | `NativeFrameRendererTest` and `StillFrameViewModelTest` (the flat-picture guard refuses a flat frame that has video), `still_math` in `export_host_tests.cpp`, `selftest.sh` (a black picture is flat) | `FRAME`, `UI-FRAME` |
| D3 | 43 photos reported "Could not generate thumbnails (IO_ERROR)" on every editor open: photos went through the video thumbnail path | `MediaKindGuardTest` (pictures are images, never video), `ThumbnailFailuresTest` (one message per asset), `thumbnail_host_tests.cpp` (the extractor's "unsupported" is not an I/O error and falls through to the image decoder) | `UI-THUMB` |
| D4 | The export service crashed the app on start on Android 17 (`InvalidForegroundServiceTypeException: Starting FGS with type none`, from androidx `ServiceCompat` 1.19.1) | `ExportServiceDeclarationTest`: `foregroundTypeFor(sdk)` is non-zero and declared with its permission for API 31 to 37, `ServiceCompat.startForeground` and the two-argument `startForeground` are banned | `FGS` |
| D5 | Full exports stalled or crawled with a seek per missing frame on 30 and 27 fps VFR clips in a 60 fps project (#118); a stale `debug.uveditor.decode_gap=0` silently disabled the fix and invalidated three hours of measurements | `decode_sim_tests.cpp` (exact .5 pts ties, 27 / 30 / 24 / 23.976 fps, VFR jitter, two decoders, each seeks once; the model also reproduces the old thrash), `DebugPropertyGuardTest` (the code reads only allow-listed properties, scripts reset what they set) | `*-FPS`, `*-SEEK`, `EXP-BUDGET`, `HYG-1` |
| D6 | HDR HLG export not offered on a phone that supports it: the probe used another `MediaFormat` than the exporter | none can configure a codec; `HdrExportSupport.kt` carries the reason in a comment | `PROBE`, `EXP-HLG`, `*-TAGS`, `UI-EXPORT` (the probe and the exporter must agree) |
| D10 | LumaFusion sometimes left the last frames of an export corrupted, and nothing told the user | `VerifyRunnerTest` (clean, lossy noise, truncated file, zeroed tail, last sample dropped, last GOP missing, decoder error, black or garbage tail, wrong picture, audio shorter, frame order, cancel, no decoder), `Mp4TablesTest` (files written by ffmpeg when it is installed, then truncated, zeroed, shortened), `SignatureMathTest` (the threshold table), `FrameSignerTest`, `ExportVerificationFlowTest`, `export_host_tests.cpp` (the native signature maths), `selftest.sh` (the verdict of the checker) | `*-VERIFY`, `VERIFY-HLG`, `VERIFY-DAMAGE` |

| D8 | A clip could not be dragged after holding it: the platform gesture detector stops reporting scrolls after its long press, and the long press toggled the clip, so holding a selected clip deselected it | `PressDragTest` (hold then move starts the drag, release without moving toggles, an unselected clip is selected by the hold) | `UI-DRAG-CLIP` |
| D7 | "Engine v0.1.0" hard-coded | `VersionWiringTest` (Gradle to CMake to `engineVersion()`, no literal), `engine_version_tests.cpp` built with the real versionName by `run-native-tests.sh` | `ENV-1`, `UI-VER` |
| D8 | Importing a package from the picker failed with EACCES: the reader reopened `/proc/self/fd/N` by path, which Android refuses for files served through FUSE | `PlatformPitfallsTest` bans `/proc/self/fd` and `/dev/fd` paths in Kotlin | **not covered** (see below) |
| D9 | The LumaFusion import turned clips twice: the decoder already applies the container rotation and `videoRotation` records the same orientation | `LumaFusionPackageGuardTest`, `LumaFusionImportTest` (no rotation is imported) | `EXP-ROT` |

## What is not covered

- **D8 on a device.** A picker grant on a FUSE-served file needs the system document picker; the runner does not drive it. The guard is
  a source ban, which stops the known cause only.
- **D6 in CI.** Whether an encoder accepts a format is a property of the device.
- **The flat-picture guard on a live frame that is legitimately black.** It refuses it (a false positive by design).
- Audio quality, A/V sync (`scripts/av-drift-test.sh`), slow motion, repair and stabiliser (their own scripts), titles, transitions,
  4K, long GOP at 4K (`scripts/run-export-throughput.sh`), thermal behaviour, other Android versions than the one on the phone.
- The unsuffixed app on the phone: by design, nothing here touches it.

## Release gate

Before any release: `scripts/qa-smoke.sh <serial> --install` (full, not `--quick`) on a device with the QA build of the release
commit; every row PASS; add the RESULT line to the log in `docs/RELEASE.md`. A failing check blocks the release until the app or the
check is fixed. Run `scripts/qa/selftest.sh` too if `check-export.py` changed.

## Adding a check

1. Reproduce the defect with a file or a project in `scripts/qa/gen-media.sh` / `make-projects.py`.
2. Assert it in `scripts/qa/check-export.py` (one line `OK ...` or `FAIL ...`) and add a broken input to `selftest.sh`.
3. Call it from `qa-smoke.sh` through `assert ID DEFECT "title" <subcommand> ...`, add its row above, and, if a JVM or host test
   can see the cause, write that test in the same PR.

# On-device QA report, part B (Pixel 8)

Device: Pixel 8, Android 17 (API 37), 1080x2400, build `master` + sufijo `qb` (`com.ultimatevideo.uveditor.qb`). The Pixel is a debug
device, not the reference phone (OPPO CPH2841). Test media was generated on the laptop with ffmpeg/ImageMagick and pushed to
`/sdcard/Download/qa-b/`. The phone was shared with another QA agent, so every session ran under `flock /tmp/pixel-device.lock`.

Result key: PASS = seen working; PARTIAL = seen working with caveats; FAIL = defect; NOT RUN = not reached.

## Summary

| Area | Result |
|---|---|
| 11 Release/About/tips/crash report | PARTIAL (tips, reopen offer, About, native and Java crash reports PASS; one defect fixed, PR #84; minified release build NOT RUN yet) |
| 1 Photos, GIF, WebP, titles, captions | PARTIAL (animated GIF and WebP PASS; the rest see below) |
| Export dialog, ETA, ffprobe | PASS |

## Setup

1. Build: `./gradlew :app:assembleDebug -Puveditor.appIdSuffix=qb`, `adb install -r`.
2. Files pushed with `adb push`; the system picker only listed them after a media scan
   (`content call --uri content://media/external_primary --method scan_volume --arg external_primary`). Note for testers.
3. Import went through the system picker (multi-select by long press, "Seleccionar").

## 11. Release, About, tips, crash report

- **First-run tips**: three dismissible cards ("Bring in your clips", ..., "Export and get help"), Next/Back/Skip/Done work and
  they do not come back after Done. PASS.
- **Reopen after a crash**: after `kill -SEGV` of the app process, the hub shows "The app closed while "New project" was open.
  Your ..." with Reopen and Dismiss, and Reopen returns to the project with its clips. PASS.
- **About screen** (hub ⋮ > About, privacy and help): version `ultimateVE 0.1.0 (100)`, licence text and link, privacy summary,
  notices, storage use (projects, caches, proxies) with Clear caches, Last crash report (Show/Hide, Copy, Share, Delete), Show tips
  again. PASS.
- **Native crash report**: after `run-as ... kill -SEGV` the report reads "ultimateVE native exit report" with time, version, device,
  Android, exit reason `native crash`, importance and a trace summary with thread stacks. PASS, with one defect:
  - **D1 (fixed, PR #84)**: every symbol in the trace was preceded by stray characters (`"T`, `"<`, `"&`...), the protobuf tag and
    length bytes of the tombstone. Fixed by reading text fields as protobuf (tag, varint length, payload) with a fallback to the
    old scan; regression test with the 84-byte symbol seen on the device. The corrected build was not re-run on the phone.
- **Java crash report**: `am crash` produced "ultimateVE crash report" with time, version, device, thread and the stack
  (`RemoteServiceException$CrashedByAdbException`). PASS.
- **Minified release build (R8)**: NOT RUN yet.

## 1. Animated images

- **Import** of two videos, an audio file, an animated GIF and an animated WebP through the system picker. PASS.
- **Animated GIF (10 fps, 3 s, burned-in timecode)**: preview at playhead 1:00 shows 1.000, at 2:15 shows 2.500 and at 4:00 shows
  1.000 (the loop). PASS. Exported file (720p H.264): frames at 1.0 s, 2.5 s and 4.0 s show 1.000, 2.500, 1.000. PASS.
- **Animated WebP (same source)**: preview at 6:00, 7:15, 9:00 shows 1.000, 2.500, 1.000; exported frames at 6.0, 7.5, 9.0 s the same.
  PASS. (Lossy WebP is slightly softer, as expected.)
- Still image clips and animated clips show a plain coloured block on the timeline, with no thumbnail tile: known, listed in the
  verification debt.

## Export

- Dialog: upload presets, resolution 1080p/720p/480p, frame rate, codec, bitrate; saving through the system "Guardar" dialog.
- Progress with the estimate: "64 %", "6 s elapsed · About 4 s left", "86 frames/s · 2,9x real time". PASS.
- Result of a 28 s project (GIF 5 s + WebP 5 s + clip 10 s + clip 8 s) at 720p/30/8 Mbps: H.264 1280x720, 840 frames, 28.000 s,
  AAC 28.05 s (`ffprobe`). PASS. "Saved ...", Close and Share buttons shown.

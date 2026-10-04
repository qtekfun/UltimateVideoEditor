# QA report: on-device smoke test (2026-10-04)

First end-to-end pass of the app on a real device, driven through `adb` (taps, swipes, `uiautomator dump`,
screenshots, `logcat`, `ffprobe` on exported files). Test media were generated with ffmpeg: 1080p30 H.264
with a single GOP of 30 frames (12 s), 720p30 H.264 with one key frame in 240 frames (8 s), 4K60 HEVC (10 s),
an AAC tone, and a PNG.

**Device:** Pixel 8 (Android 17, API 37, 1080x2400, Tensor G3). This is **not** the reference device (OPPO
CPH2841, Snapdragon SM8850); numbers below are indicative only. The Pixel was shared with other agents who
installed their own builds and ran instrumented tests (which clear app data) during the pass, so some
scenarios could not be completed (see "Not verified").

## Test matrix

| Area | Result | Notes |
|---|---|---|
| Install, launch, welcome screen | Pass | |
| New project (default 1080p30 SDR) | Pass | Old dialog is dense; being redone (WP-U1) |
| Import two videos through the system picker (multi-select) | Pass | Clips land on the base with waveforms |
| Layout: video stack at the bottom, audio under it | Pass | |
| Play with audio | Pass | Timecode follows real time; `dumpsys audio` shows an AAudio player `started` for the app |
| Split at playhead | Pass | |
| Vertical drag of a base clip to a new overlay lane | Pass | Base closes the gap, clip lands on a new lane above |
| Title, sticker (heart), undo state | Pass | Title and sticker render in the preview |
| Process restart offer ("The app closed while ... was open", Reopen) | Pass | Triggered by reinstalling over a running app |
| Export 1080p30 H.264 with ETA | Pass after fix | Elapsed, time left and throughput shown; output verified with ffprobe: 1920x1080, 446 frames, 14.87 s, AAC |
| Export on a long-GOP clip | **Fail, fixed** | See defect 2 |
| Toolbar Play button | **Fail, fixed** | See defect 1 |
| Editor layout with the media tray on a phone | **Fail, fixed** | See defect 3 |
| A/V drift (~35 s window) | Pass (indicative) | Max 2 frames, mean -0.34 frames, 4 re-anchors |
| 4K60 HEVC playback (debug preview) | Partial | About 51 fps average, 151 waits; Pixel 8, not the reference device |
| Editor playback frame times | Pass | 379 frames in 10 s, 1.3 % janky, p50 10 ms, p90 14 ms, p99 18 ms, GPU p99 4 ms |
| Relink flow, hub actions, rotation, split screen, keyframes, effects, LUT, colour override, speed/reverse, markers/beats, templates | Not verified | App data was cleared by another session mid-pass; see below |

## Defects

1. **Play button hidden under Fit** (fixed in #42). The tooltip wrapper (#34) applied the caller's modifier to
   the inner `IconButton`, so `Modifier.align(CenterEnd)` on the Fit button was ignored and Fit was drawn over
   Play in the transport row. Found because the accessibility tree showed "Fit" where "Play" should be.
2. **Export fails with "decoder stalled at source frame 126 (... seeks=353 ...)" on long-GOP clips** (fixed in
   #46). After a seek the decoder restarts at the previous sync frame; with one key frame in 240 frames that is
   frame 0, so `missing - decodePos > kMaxForwardSkipFrames` was true again on the next step and the thread
   seeked every ~50 ms and never reached the goal. The decision now lives in `decode/seek_policy.h` and does not
   re-seek while the decoder is still running toward the goal it just issued. Host test added.
3. **Media tray covers the whole editor on phones** (fixed in #47). `MediaTray` used `fillMaxSize` in a
   `wrapContentHeight` slot, taking all the height the editor column had left and squeezing the weighted
   preview and timeline to zero; the editor showed only the toolbar and the tray, collapsed or expanded.

## Open defects and observations

- **(Fixed in the second pass, see "Second pass" below.) Export throughput is low on long-GOP material (about 10 frames/s, 0.3x real time, 1080p30 with two
  layers) even after fix 2.** The log shows the decode thread rendering ~6 frames/s but dropping 140-200
  frames/s: each time the export asks for a frame the decoder has already run past it (frames beyond the cache
  window are decoded and dropped) it seeks backwards, and on a one-key-frame stream every seek re-decodes from
  frame 0. Suggested follow-up: keep the decoder from running more than a few frames past the cache window
  (hold output buffers until the target advances) and prefer forward decode when the missing frame is within
  one GOP of the decode position. The reference device measured 1.5x real time at 4K60 on short-GOP clips, so
  this is mostly a long-GOP and weak-codec effect, but it deserves a dedicated pass.
- **4K60 HEVC reaches about 51 fps on the Pixel 8** with the 1 GiB cache holding 32 frames; the Phase 4 gate
  (no drops) is not shown to be met on this device. It is the reference OPPO that decides the gate.
- **The debug preview cannot open files pushed into the app's external files directory** (`Cannot open ...`),
  because of scoped storage permissions on `adb push`; use its Pick button.
- **`scripts/av-drift-test.sh` only seeds the project**: someone has to open it and press play while it logs.
  It reports 0 windows otherwise. A 5-minute run was not done; the figures above are from a 35 s window.
- **`scripts/perf-editor.sh`** taps fixed positions that miss the first card when the "app closed while open"
  banner is shown (it force-stops the app first), so it measures an idle hub.
- **Sticker and title blocks on the timeline are plain coloured rectangles** without a label or thumbnail.
- **The default "New project" dialog is dense** (many chips); WP-U1 replaces it.
- `uiautomator dump` run in parallel by two sessions crashes the UiAutomation service ("already registered");
  not an app problem, but it makes concurrent debugging noisy.

## Not verified

- Relink flow, hub rename/clone/delete/export/import, rotation, split-screen, keyframes, effects, LUTs, colour
  override, speed/reverse/freeze, markers and beats, text templates, animated captions (intentionally skipped:
  privacy decision), photo clips.
- Because other sessions reinstalled their builds over the app several times ("PACKAGE UPDATED" exits at
  01:33, 01:34, 01:51, 01:52) and cleared the app data (the hub lost its projects at about 01:53), several
  scenarios were interrupted. Re-running them on a device used by one session at a time is recommended.
- The build with the layout presets (U3) was seen installed on the device with the same tray defect, so the fix
  in #47 applies to current `master`.

## Second pass (export reliability)

Same Pixel 8 (not the reference device), build installed as a separate package (`-PappIdSuffix=.d2`) because the
device is shared; every device session took `/tmp/pixel-device.lock`. The screen locked during the session (secure
lock, nobody to unlock it), so UI scenarios could not be driven; only non-UI measurements were done.

### Export throughput (1080p30 source, 300 frames, H.264 export at 1080p, `scripts/run-export-throughput.sh`)

| Scenario | Before | After |
|---|---|---|
| 1 layer, GOP 30 | 21 fps (0.70x) | 54-63 fps (1.8-2.1x) |
| 1 layer, one key frame (long GOP) | 11.6 fps (0.39x) | 54-71 fps (1.8-2.4x) |
| 2 layers, long GOP | 1.8 fps (0.06x) | 39-40 fps (1.3x) |
| 2 layers, GOP 30 | not measured | 38-42 fps (1.3-1.4x) |

### Preview (4K60 H.264, 10 s, debug preview activity, `scripts/preview-4k-test.sh`)

| | Before | After |
|---|---|---|
| Frames drawn of 600 | 429-513 | 605-606 |
| Stalls | 118-227 | 0 |

(HEVC 4K60 was not re-measured; the earlier 51 fps figure was with the old in-flight limit. The reference OPPO still
decides the Phase 4 gate. After the final fixes the screen was locked, so a later repeat only confirmed decoding
(600 decoded, 0 stalls), not drawing.)

### Frame-exact retime export (`scripts/run-retime-export-test.sh`)

| | Master before | After |
|---|---|---|
| Frame mismatches | 133 of 285 (clip B at 2x onward) or a failure | 0 of 285, 0 of 250 (plain clip) in 7 of 7 runs |
| Audio segments | several wrong (silence or wrong pitch) | all correct |
| Run time | 33-54 s, sometimes a hang | 7 s |

### Defects found and fixed

1. **Backward seeks on every few frames of a sequential export (long GOP: 0.35x real time).** The buffer queue between
   the codec and the image reader drops all but the newest queued frame, so releasing four frames at once lost three of
   them; a 400 ms timer then caused a backward seek and a re-decode from the key frame. Fix: one frame in flight.
2. **Premature end of stream and wrong frames when audio and video read the same file.** Descriptors duplicated with
   `dup()` share one file offset, so extractors on different threads disturbed each other (a clip ended after 177 of
   300 samples). Fix: an independent descriptor per reader. This is the likely root of the intermittent "decoder
   stalled" and "audio clip could not be decoded" errors.
3. **A reversed audio clip never became ready.** After a seek, a busy codec made each following call seek again to the
   same position (flushing the codec every time). Fix: keep filling the pending block. Regression test in the host suite.
4. **A stalled audio clip blocked every frame for 30 s.** Audio faults were only polled every 30 frames. Now every frame,
   so the export fails after 30 s with a clear message.

### Not done in this pass

UI scenarios (relink, hub actions, rotation, split screen, keyframes, effects, LUT, colour override, speed/reverse in the
preview, markers and beats, templates, captions, photos and stickers, tray drag and drop, the new-project sheet),
clip labels on title and sticker blocks, unattended perf/drift scripts and the 5-minute A/V drift number: they need an
unlocked screen. Run them again when the Pixel is unlocked.

### Unattended device scripts (added after the second pass)

`scripts/perf-editor.sh` and `scripts/av-drift-test.sh` no longer need anyone to open the project or press play: the new
`scripts/device-ui.sh` helpers find nodes with `uiautomator` (under `/tmp/uiautomator.lock`, because two parallel dumps crash
the service), dismiss the "app closed while ... was open" offer, open a project by name and press Play/Pause. Both take
`PKG=` for a side-by-side build (`-PappIdSuffix`). The node lookup is tested against a sample dump; **the scripts themselves
were not run on a device** (the Pixel was locked), so the 5-minute A/V drift number is still missing.

### Export on long-GOP material: model of the decoder worker (third pass, host only)

The long-GOP slowdown (about 10 fps in the first pass) was already fixed by the "one frame in flight" change of the
second pass (table above: 11.6 fps -> 54-71 fps on the Pixel). This pass adds a **deterministic simulation** of the
decoder worker with a sequential export consumer (`app/src/test/cpp/decode_sim_tests.cpp`, run by
`scripts/run-native-tests.sh` and as `uv_decode_sim_host_tests`). It mirrors `video_decoder.cpp` (window search, seek
decision, one frame released at a time, the image reader keeping only the newest undrained frame) in virtual time and
uses the production `needsSeek` and `pendingExpiredAfterDrain` unchanged; the codec is a fake (key frame every GOP,
4 ms per frame, 20 ms per flush; consumer 8 ms per frame). Numbers for 600 frames:

| Case | Seeks | Frames decoded | Dropped | Lost in the reader | Effective fps | Ideal max(decode, draw) |
|---|---|---|---|---|---|---|
| One key frame, **4 in flight (before)** | 138 | 15796 | 15293 | 275 | **1.9** (timed out) | 125 |
| One key frame, 1 in flight (now) | 1 | 600 | 0 | 0 | **124.3** | 125 |
| GOP 30, 1 in flight | 1 | 600 | 0 | 0 | 124.3 | 125 |
| Decode-bound (12 ms decode, 6 ms draw) | 1 | 600 | 0 | 0 | 71.2 | 83.3 |
| Draw-bound (3 ms decode, 15 ms draw) | 1 | 600 | 0 | 0 | 66.5 | 66.7 |
| Scrub back from 300 to 100 | 2 | 701 | 102 | 0 | 114.3 | 125 |
| Cut forward from 100 to 500 (GOP 60) | 2 | 221 | 20 | 0 | 115.5 | 125 |

Reading: with several frames in flight the reader drops all but the newest, every loss is retried after the pending
timeout, and every retry is a backward seek and a re-decode from the key frame (the simulation shows the same
mechanism as the device, with a larger factor: the real codec re-decodes faster than the model). With one frame in
flight sequential access performs exactly one seek, loses nothing, and runs within 15% of the pipelined ideal
(`max(decode, draw)`); the decode-bound case is the closest to the limit (85%) because only one frame can be ahead of
the consumer. Interactive behaviour is unchanged: a scrub back or a long jump seeks once. The tests assert all of
this, and that `kMaxInFlightFrames` (now in `decode/pending_policy.h`, shared by the decoder and the model) stays 1.

Not verified in this pass: anything on a device. The Pixel 8 was locked with a credential, so
`scripts/run-export-throughput.sh` (the real measurement, including the 2-layer case at 1.3x real time) was not rerun.
The two-layer export is probably bound by drawing two layers and the encoder rather than by decoding; that needs a
device profile.

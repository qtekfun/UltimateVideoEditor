# Decisions to confirm

Choices made autonomously while the owner was away. Append-only; each entry says what was chosen, why, and
the alternative, so any of them can be reversed cheaply.

## 2026-10-03 · A/V sync: native preview clock re-anchored from the audio clock
**Chosen:** while playing, the native compositor advances all layers on its own monotonic clock
(`PreviewEngine.playScene`); the editor re-anchors it only when the composition changes or the heard audio
frame differs from the native clock by more than 2 frames. **Why:** the old path re-sent the scene (a seek
for every layer) on every playhead tick, which made decoders thrash; both clocks are CLOCK_MONOTONIC based, so
they barely drift and re-anchors are rare. **Alternative:** push the audio position to the native side every
tick and let it pick frames (simpler to reason about, but a JNI call and a seek decision per tick).

## 2026-10-03 · Drift threshold of 2 frames
**Chosen:** 2 frames (33 ms at 60 fps, 67 ms at 30 fps). **Why:** one frame is within normal tick jitter and
would cause needless re-anchors (each costs a decoder seek); 2 frames is below what people notice as lip-sync
error on short drifts. **Alternative:** a time-based threshold (about 30 ms) so 24 fps projects are not looser
than 60 fps ones.

## 2026-10-03 · Layers hold at their clip's out point during playback
**Chosen:** `PreviewLayer.endFrame` (exclusive source frame) clamps each layer's native clock. **Why:**
without it a trimmed clip would play the media beyond its out point for up to one UI tick at a cut or in a
gap. **Alternative:** rely on the UI tick to notice the clip ended (visible for 16 ms or more).

## 2026-10-03 · Oboe stream closes 1.5 s after pause, at once in the background
**Chosen:** `EditorAudio` opens the stream on play, closes it after a 1.5 s idle pause and immediately on
`ON_STOP`. **Why:** an open AAudio stream keeps the audio path awake and drains battery, but reopening on
every pause/play toggle costs tens of ms and can click. **Alternative:** close immediately on every pause
(the literal request; slightly longer resume), or never close while the editor is open (the previous behaviour).

## 2026-10-03 · Thumbnail cells are sampled at their centre
**Chosen:** each filmstrip cell asks for the tile nearest to its centre time, clamped to the clip. **Why:**
sampling at the left edge could show media before a trimmed clip's in point. **Alternative:** sample at the left
edge but never before the in point (first cell could then show a frame up to one cell later than its edge).

## 2026-10-03 · Timeline follows the playhead by paging; drag auto-scroll constants
**Chosen:** if the playhead leaves the 10%-90% band of the view the timeline jumps so it sits 10% from the left;
dragging a clip within 56 dp of a side edge scrolls up to 14 dp per frame. **Why:** the usual editor
behaviour and cheap to implement without animating. **Alternative:** continuous centring of the playhead while
playing (smoother, but the clips under the finger move constantly while editing).

## 2026-10-03 · A/V drift was not measured on the device; the script is ready
**Chosen:** the phone was not connected to the laptop (wireless adb offline) when this work was done, so the
5-minute drift measurement was not run. `scripts/av-drift-test.sh` generates a flash+beep clip, seeds a
5-minute project and logs the audio-clock vs native-clock drift (tag `UVSync`). **Note:** it measures the
agreement of the two clocks, not display or speaker latency; true flash-to-beep offset would need an external
camera or a loopback rig. The Phase 4 gate stays open until the script is run.

## 2026-10-03 · SPECS.md follows the code, not the other way round
**Chosen:** source ranges are documented as project frames (what the code and saved files use), compileSdk 37,
and the real module layout. **Why:** the code is tested and shipped; changing it back to native-frame source
ranges would need a data migration for no user benefit. **Alternative:** keep native-frame source ranges and
convert at the boundaries (matches the first draft, adds rational conversions everywhere).

## 2026-10-03 · During a crossfade the preview re-anchors every tick
**Chosen:** a crossfade changes the incoming layer's opacity on every frame, and opacity is part of the
composition key in `EditorPreview.follow`, so the native clock is re-anchored each tick for the length of the
fade (typically under a second). Titles are treated as stills with a fixed offset, so they do not cause
re-anchors on their own. **Why:** the scene API takes static opacity per call and the decoders already
continue sequentially, so a re-anchor costs no seek. **Alternative:** give `playScene` per-layer opacity ramps
so the native side animates the fade itself (fewer JNI calls, more native code and a second implementation of
the crossfade curve).

## 2026-10-03 · Auto captions: whisper.cpp, vendored as a pinned submodule
**Chosen:** whisper.cpp `v1.9.4` as a git submodule built statically into `uveditor_engine` (CPU only, ggml built for
`armv8.2-a+dotprod+fp16`), models downloaded on demand. **Why:** it is MIT (compatible with GPL-3.0), has a plain C API
with word timestamps, runs offline and builds with the NDK we already use; a submodule keeps the repo small and the
version exact. **Alternative:** FetchContent at configure time (needs network on every fresh build) or copying the
sources into the tree (adds tens of MB). Contributors must run `git submodule update --init --depth 1`.

## 2026-10-03 · Auto captions: the first use of the INTERNET permission
**Chosen:** the manifest now declares `INTERNET`, used only to download the speech model once when the user asks for
captions. The app otherwise stays offline. **Why:** bundling a 31-57 MB model would bloat every install for a feature
many projects will not use, and a model must come from somewhere. **Alternative:** a side-loaded model picked through
the document picker (no permission, but a clumsy first run), or bundling the tiny model in the APK.

## 2026-10-03 · Auto captions: two quantised multilingual models, base as default
**Chosen:** "Balanced" = Whisper base `q5_1` (57 MB, default) and "Fast" = tiny `q5_1` (31 MB), both multilingual,
pinned by SHA-256. **Why:** q5_1 loses little accuracy at roughly a third of the size, and multilingual models let
"detect automatically" work for creators who post in several languages. **Alternative:** English-only `.en` models
(a little more accurate in English), or `small` (better, ~190 MB and several times slower on a phone).

## 2026-10-03 · Auto captions: static styles now, animated styles after keyframes
**Chosen:** four static styles (Classic, Bold, Pop, Impact) that set size, colour, position and chunking of ordinary
title clips, plus a dark text outline (`TitleContent.outline`, stored in the project JSON, default off) so captions read
over any footage. Word-highlight and pop-in animation are not in this PR. **Why:** animation needs the keyframe system
that another branch is adding; faking it with one title clip per word would bloat the timeline. **Alternative:** one title
clip per word with a highlight colour (works today, but hundreds of clips and awkward to edit).

## 2026-10-03 · Auto captions: always on a new title track, for the selected clip
**Chosen:** captions are added to a new title track on top (never merged into an existing title track) by a single
`AddCaptions` edit, for the selected clip's whole source range (not a time range). **Why:** a new track cannot clash with
existing titles, and one Undo removes the lot. **Alternative:** reuse the first title track and overwrite (cuts existing
titles), or let the user pick a range on the timeline (there is no range selection yet).

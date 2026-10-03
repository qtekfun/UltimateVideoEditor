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

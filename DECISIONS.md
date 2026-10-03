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

## 2026-10-03 · Keyframes are in clip frames and cover video clips and titles; gain is not animated
**Chosen:** a keyframe's time counts from the clip's own first frame, and both video clips and titles can be
animated (position, scale, rotation, opacity); audio gain stays one value per clip. **Why:** clip-relative time
means moving a clip never touches its keyframes, and the audio mixer takes a single gain per clip (animating it
would be a native mixer change on top of the audio work that just landed). **Alternative:** project-frame
keyframes (every move would have to shift them) or a gain envelope (volume ramps) added to the mixer later.

## 2026-10-03 · Editing an animated clip writes a keyframe at the playhead (auto-key)
**Chosen:** once a clip has keyframes, a slider or preview gesture at the playhead adds or replaces a keyframe
there (one undo step); with the playhead outside the clip the edit is refused with a hint. The diamond toggles
a keyframe by hand; removing the last one freezes that pose as the fixed transform; "Clear" keeps the pose at
the playhead. **Why:** it is how CapCut and LumaFusion behave, and nothing ever jumps. **Alternative:** a
separate record mode (more taps), or editing the fixed transform while keyframes override it (confusing).

## 2026-10-03 · Cropping keeps linear and hold animations exact; a cut ease only approximates
**Chosen:** split, trim and overwrite re-base keyframes onto the surviving range and add a keyframe holding the
pose at a new start or end when keyframes outside it shaped the motion. A smoothstep segment cut in the middle
keeps its mode over the remaining span, so its shape changes slightly. **Why:** exact eases would need
per-keyframe tangents or a stored curve parameter, which is a bigger model for a small visual difference.
**Alternative:** store a curve offset per keyframe, or turn a cut ease into a linear segment.

## 2026-10-03 · Changing the canvas rescales positions and clears the undo history
**Chosen:** positions of clips and keyframes are multiplied by the width and height ratios; scale is kept (the
clip's "contain" fit follows the new canvas); the undo stack restarts. **Why:** earlier undo steps were made on
another canvas and would put clips in the wrong place. **Alternative:** make the canvas change an undoable
command (needs canvas size in the timeline model) or keep pixel positions untouched (clips drift off-centre).

## 2026-10-03 · Safe-zone margins are approximate, not official
**Chosen:** TikTok 7/23/6/12 %, Reels 13/18/6/12 %, Shorts 7/25/6/13 % (top/bottom/left/right) of a 9:16 canvas.
**Why:** the platforms publish no exact values and change their interface; these are the commonly cited
creator figures, good enough to keep text clear of captions and buttons. **Alternative:** a single generic
"vertical" zone, or user-editable margins.

## 2026-10-03 · Upload presets fill the settings but never reshape the movie
**Chosen:** YouTube 1080p/4K/Shorts, TikTok, Instagram Reels and feed pick size, rate, codec and bitrate
(H.264 12/8 Mbps, HEVC 35 Mbps for 4K); a preset made for another shape than the project's only shows a hint.
Sizes never upscale past the project and rates keep the project's own unless faster than the preset's cap.
**Why:** changing the shape at export would crop or letterbox silently. **Alternative:** auto-switch the canvas
when a preset is chosen, or hide presets of another shape.

## 2026-10-03 · Animated clips make the preview re-anchor every tick during playback
**Chosen:** the pose is part of the composition key in `EditorPreview.follow`, so while an animated clip plays
the native clock is re-anchored each tick (same trade-off as a crossfade; decoders keep going sequentially so it
costs no seek). **Why:** the scene API takes a static pose per call. **Alternative:** pass keyframes to
`playScene` and animate natively (fewer JNI calls, a third copy of the interpolation).

## 2026-10-03 · Keyframes are evaluated twice (Kotlin and C++) with shared test vectors
**Chosen:** the exporter renders from a flat clip list on a native thread, so `core/keyframe_math.h` mirrors
`Keyframes.evaluate` (floating-point contraction off so results match the JVM); both are tested with the same
vectors. **Why:** same pattern as the crossfade curve. **Alternative:** sample poses per output frame in Kotlin
and ship them as arrays (no duplicate maths, but large arrays and a per-frame Kotlin pass).

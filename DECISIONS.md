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

## 2026-10-03 · A retimed clip stores its timeline length; speed is derived, a freeze is a one-frame range
**Chosen:** `Clip.retimedFrames` (null = 1x) holds the length on the timeline, so speed is `range / length` and
clip ends are exact integers; a freeze frame is a one-frame range held for N frames, with no separate model.
**Why:** a stored speed would need rounding every time a clip is cut, and the halves of a split could then
disagree on length; with the length stored, split/trim/overwrite re-derive both from one mapping and the halves
always tile. **Alternative:** store speed as a rational and derive the length (cuts drift by a frame), or add an
explicit freeze clip kind (more branches in every operation).

## 2026-10-03 · Speed ramps are relative weights, normalised to the clip's range
**Chosen:** a ramp is a list of `{frame, weightPermille}` keys (linear between, held outside) that only shapes
the speed; the average still comes from range and length. **Why:** a ramp can never change where a clip starts
or ends, so it never collides with neighbours and cuts stay consistent. **Alternative:** absolute speed keys
(the length would depend on the ramp and every ramp edit would move later clips).

## 2026-10-03 · The frame mapping lives only in Kotlin; export gets a table, the preview maps per tick
**Chosen:** `domain/Retime.kt` is the single definition. The exporter receives one source frame per project
frame of each retimed clip; the preview maps the playhead each tick (and re-anchors, like an animated clip);
audio gets knots. **Why:** a ramp's integral in floating point would otherwise exist twice and could disagree by
a frame at the boundaries; tables are small (one long per frame). **Alternative:** mirror the maths in C++ with
shared vectors, as keyframes do (fewer bytes, a second implementation to keep in sync).

## 2026-10-03 · Slow motion holds or skips source frames; no frame blending yet
**Chosen:** `floor` mapping, so 0.5x shows every source frame twice and 2x skips every other one. **Why:**
blending needs a second sampled layer per output frame in the compositor and the exporter. **Alternative (follow-up):**
blend the two nearest source frames for speeds below 1x.

## 2026-10-03 · Reverse playback: mirrored decode window, bounded by memory
**Chosen:** a reversed layer keeps frames behind the playhead decoded (preview: the window is swapped; export:
`reverseWindowFrames`, about 8 frames at 4K and 32 at 1080p) so one pass over a GOP serves the next stretch.
**Why:** no codec decodes backwards; a pass over the GOP is the cheapest way to get the frames. **Limit:**
long-GOP 4K footage re-decodes a GOP every few frames, so reverse playback and export are slow there.
**Alternative:** transcode a reversed proxy first (fast to play, costs time and storage).

## 2026-10-03 · Audio of retimed clips: varispeed between 0.25x and 4x, muted outside, freeze is silent
**Chosen:** the mixer reads the source along the clip's knots with linear interpolation, so the pitch follows
the speed; a clip whose speed leaves 0.25x–4x at any knot, and every freeze frame, is muted. **Why:** it is
exact, simple and bounded, and extreme speeds sound like noise anyway. **Alternative:** WSOLA time stretching
that keeps the pitch (a "maintain pitch" switch like CapCut's), a real DSP task for a follow-up.

## 2026-10-03 · Speed changes ripple later clips in the editor
**Chosen:** the inspector sends `SetSpeed` with ripple on: slowing a clip pushes later clips on its track later,
speeding up pulls them earlier. **Why:** without it slow motion fails with "would overlap" whenever the clip has
a neighbour. **Alternative:** leave gaps / fail and let the user move clips (the domain operation supports both).

## 2026-10-03 · Speed limits 0.1x–8x; the timeline labels a ramp at its average speed
**Chosen:** `SpeedLimits` (0.1x to 8x) for the control; split rounding can leave a half slightly outside that
range, which is allowed (only the control is limited). The canvas draws waveforms and thumbnails of a ramped clip
at the average speed and labels it with that speed. **Why:** the canvas has no ramp curve; the preview, the export
and the sound do follow it. **Alternative:** send the curve to the canvas as knots.

## 2026-10-03 · Effects are one generic type with a parameter table, and their values are not keyframable
**Chosen:** `Effect(id, type, values)` with `EffectType` carrying each parameter's name, range and default; one
uber fragment shader switches on the type; the wire format is a flat `double` blob per layer. **Why:** adding an
effect is a table row plus a shader branch and a CPU-reference case, instead of a sealed class, a DTO, a JNI
shape and a UI row each. Keyframes are tied to the pose (`ClipTransform`), so keying effect values would need a
second keyframe track and its own editing UI; static values cover the common looks. **Alternative:** a sealed
class per effect (typed, but several copies per effect) and a general keyframed-property system.

## 2026-10-03 · Every blend mode but normal reads a snapshot of the target instead of framebuffer fetch
**Chosen:** copy the letterboxed target into a texture (`glCopyTexSubImage2D`) before drawing a blended layer
and do the maths in the shader, with plain alpha blending for normal. **Why:** works on the window surface and on
the export FBO without extensions, and overlay cannot be expressed with fixed-function blending anyway.
**Alternative:** `GL_EXT_shader_framebuffer_fetch` (no copy, but not guaranteed on the default framebuffer) or
rendering the whole scene through an FBO. The copy costs one viewport-sized blit per blended layer.

## 2026-10-03 · Effects run at the size the layer covers on the canvas, premultiplied RGBA8
**Chosen:** intermediates are the layer's fitted size (up to 4x when scaled up, capped at 4096 px), RGBA8
premultiplied; blur sigma is a fraction of that height. **Why:** preview and export see the same pixels
regardless of their output resolution, and memory is only spent on layers that have effects. **Alternative:**
RGBA16F intermediates (no banding after several effects, twice the memory) or running at source resolution.

## 2026-10-03 · The mask has sliders and a clip badge, but no drag handles on the preview
**Chosen:** mask centre, size, feather, shape and invert are inspector sliders; clips with any look get a small
badge on the timeline. **Why:** a handle overlay needs the layer's on-screen box, which depends on the video's
display size that the editor state does not carry, and preview drags already move the clip. **Alternative:** an
outline plus corner handles on the preview, once assets expose their display size to the UI.

## 2026-10-03 · Chroma key works in the chroma plane with a spill pull to grey, not a despill matrix
**Chosen:** distance between the pixel's and the key's (Cb, Cr) decides the alpha with similarity and
smoothness; "spill" pulls colours just outside the keyed zone towards their luma. **Why:** it needs no per-key
dominant-channel logic and behaves for green, blue and arbitrary key colours. **Alternative:** channel-based
despill (`g = min(g, (r + b) / 2)` for green), which looks cleaner on green screens but only fits green or blue.

## 2026-10-03 · HDR projects blend in HLG signal space; intermediates are half float
**Chosen:** an HLG target is composited in HLG-encoded values with ordinary alpha blending, effect intermediates
are RGBA16F and the blend-mode snapshot is RGB10_A2 (it must stay fixed point to be copied from the 10-bit
surface). **Why:** it keeps `drawScene` one shared path for preview and export and needs no scene-wide float
framebuffer; HLG is designed to be used (and cross-faded) in signal space. **Alternative:** render the whole
scene into an RGBA16F linear-light framebuffer and convert to HLG at the end; physically exact for blends and
fades, but every layer and the final pass change, and a half-float scene buffer costs bandwidth at 4K.

## 2026-10-03 · The engine derives each layer's colour mode from (source, target); Kotlin only says what a clip is
**Chosen:** `ColorMode` values 0/1/4 mean SDR/HLG/PQ source for the export JNI and `colorModeFor` picks the
conversion for the target the engine renders in (preview: what the surface granted; export: the job's
setting). **Why:** the preview's target changes at run time (the device may refuse HLG) and a Kotlin-side
choice would go stale. **Alternative:** Kotlin computes the final mode per clip and re-sends it whenever the
output space changes.

## 2026-10-03 · SDR white sits at 203 nit (HLG ≈ 0.75) in an HLG project, gamma 2.4 decode
**Chosen:** SDR sources are decoded display-referred with gamma 2.4 (BT.1886) and placed at the BT.2408
reference white. **Why:** it matches how SDR video is shown on an SDR display and keeps graphics from glaring
next to HLG footage. **Alternative:** the BT.709 inverse OETF (scene-referred), or 100 nit white; both look
darker or brighter than the usual conversions.

## 2026-10-03 · PQ sources are supported with a simple path, no dynamic tone mapping
**Chosen:** PQ → HLG clips display light at 1000 nit; PQ → SDR reuses the HLG tone-map shoulder from the
203-nit-relative stage. **Why:** `Rec2020-PQ` already exists as a probed colour space and a fixed curve is
predictable. **Alternative:** HDR10 metadata-driven or content-adaptive tone mapping; not worth the code
without footage to tune on.

## 2026-10-03 · HLG preview is requested only for an HDR project on an HDR display; otherwise SDR with a label
**Chosen:** `wantedOutputSpace(projectIsHdr, displaySupportsHlg)`; the native engine can still refuse and
reports what it granted; the editor shows "HDR HLG" or "HDR project, SDR preview". **Why:** tagging an HLG
surface on an SDR display only makes the system tone-map unpredictably. **Alternative:** always render HLG and
let SurfaceFlinger tone-map.

## 2026-10-03 · The preview context prefers a ten-bit config for every project
**Chosen:** the preview's EGL config is RGB10_A2 when available (RGBA8 otherwise); HLG or SDR is then only a
property of the window surface's colour tag. **Why:** the EGL context cannot be recreated cheaply when the
project switches colour space (it owns the frame cache textures). **Alternative:** recreate the engine when the
space changes, or use `EGL_KHR_no_config_context`. An RGB10_A2 SDR surface should look identical; verify on
the device.

## 2026-10-03 · HDR export is HEVC Main10 only and falls back to SDR rather than failing
**Chosen:** HDR needs HEVC; the dialog offers it when `findEncoderForFormat` finds a Main10 HLG encoder, the
native job still refuses (`UnsupportedFormat`) without a ten-bit encoder surface or the colour tag.
**Why:** an HDR project must still be exportable on any device. **Alternative:** HDR10 (PQ) or Dolby Vision
profiles, or H.264 with 8-bit HLG; neither is widely playable.

## 2026-10-03 · FFmpeg fallback is deferred
**Chosen:** not started. **Why:** a static FFmpeg for arm64 under the NDK is a large build (codec selection,
GPL linking, ~10+ MB per ABI, long CI) and nothing in the editor needs it yet: every format the app opens goes
through MediaCodec. **Alternative:** vendor a prebuilt LGPL FFmpeg and use it only for unsupported containers
once a concrete format gap shows up.

## 2026-10-03 · Export: a late frame is waited for, never replaced by the previous one
**Chosen:** the exporter's `fetch` substitutes another frame only when the decoder reports that the stream never
produces the wanted one (`VideoDecoder::isUnavailable`: skipped, or past the last frame); otherwise it waits, and
logs `slow frame` with the decoder's state if that takes over 500 ms. The choice lives in `encode/pickSourceFrame`
(host-tested). **Why:** the old code returned the nearest earlier cached frame whenever any later frame was already
cached, so a frame that was merely late (or dropped by the image queue, then re-decoded after the 400 ms in-flight
timeout) showed the picture before it: the "4-7 frames one frame early" in the retime check (the report says one frame
early, not a constant 4-7 frame offset; the checker counts them as off by one). **Alternative:** keep substituting
and bound the lateness; rejected, an export is offline and has all the time it needs, correctness first.
**Not verified on the device** (adb offline): the diagnosis comes from reading the code and the new log lines are what
will confirm it.

## 2026-10-03 · Export: decoder stalls report their state and recover by themselves
**Chosen:** a stall message now carries the decoder snapshot (`describe()`: target, decode position, last output,
seek goal, flags, counters) and the cached range; after 3 s without progress `VideoDecoder::recover` clears the
in-flight bookkeeping and forces a fresh seek (repeated every 3 s), and only 15 s without progress fails the export.
Frames the stream skips are logged (`never produced`). **Why:** the "stalled at source frame 230 after a reversed
clip" could not be reproduced here; every hypothesis (stale in-flight entries, a codec positioned oddly after the
mirrored reverse window) is cured by a re-seek, and the next occurrence will say exactly which state it was in.
**Alternative:** rebuild the whole decoder on a stall; heavier, and hardware decoders are scarce.

## 2026-10-03 · Export: audio decode failures are retried, an unready clip is waited for or reported by name
**Chosen:** in offline mode a clip whose decoder fails is retried (1 s cooldown, up to 4 consecutive failures) and the
render waits for it instead of mixing silence; only a clip that keeps failing raises the `Decode` fault, and an
offline wait over 30 s raises `OfflineStall`; both name the clip. Previously `ClipSource::ready()` treated a failed
clip as ready, so a single hiccup (codec reclaimed, failed seek) put silence in the export and aborted it with a
generic error. **Why:** transient hardware codec failures happen under load and when another app takes a decoder.
**Alternative:** fail on the first fault (the old behaviour) or retry forever; neither tells a bad file from a busy
phone. Playback (non-offline) behaviour is unchanged.

## 2026-10-03 · Magnetic base track: insertion point, reorder rule, trim rules
**Chosen:** base = lowest video track, always contiguous from 0. Insert/import goes to the *nearest clip boundary*
(ties to the end), not mid-clip, so no fragments appear. Reorder places the dragged clip in the slot its centre is
over (past a neighbour's midpoint swaps) and overlays follow their footage by being cut at the points where the
footage under them moves by different amounts (a bijective remap, so overlays never collide). Trim keeps the clip's
start and ripples followers; frames cut from the base are cut from overlays like a deletion, lengthening shifts
overlays at/after the trim point. A base clip cannot be dragged onto another track; an overlay dragged onto the base is
inserted. Legacy base gaps are closed (like deletions) on the first magnetic edit. **Why:** matches the LumaFusion
primary-storyline behaviour the user described and makes it impossible to create a gap or orphan an overlay.
**Alternatives:** split the base clip at the playhead on insert; connected clips that always travel with one base clip
(Final Cut style); leaving legacy gaps untouched; letting overlays crossing an insertion point be split.
**Open:** overlays that cross a base insertion point stay put (not split), and overlays over a closed legacy gap are
deleted; confirm both feel right.

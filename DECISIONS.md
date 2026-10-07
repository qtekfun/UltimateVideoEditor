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

## 2026-10-03 · Lane layout: video stack anchored to the bottom, ruler on top
**Chosen:** lanes keep display order (first = topmost). The lane stack (overlays, then the base, then audio) rests on the
bottom of the timeline panel and grows upward, so with few lanes the free room is between the ruler and the first lane;
once the stack is taller than the panel it scrolls vertically, opening at the bottom so the base is visible. The room above
the stack is the 'add a lane' drop zone. **Why:** the user wants overlays high on the screen and the base low, like
LumaFusion. **Alternative:** pin the stack to the top (the old layout) or centre the base. **Open:** audio lanes sit below the
base, so the base is not literally the lowest lane on screen; say if audio should sit elsewhere.

## 2026-10-03 · Dropping a clip: the position decides, an indicator says what happens (no prompt)
**Chosen:** one function, `DropPlan.decide`, picks the action while dragging and the same command runs on release; the
timeline draws its hint natively (`DropHint`). Thresholds: **INSERT radius = 10 project frames** measured from the dragged
clip's **start edge** to a junction of the *base* (a cut between two clips, frame 0, or the end); past the base's end the clip
is appended. Otherwise over a clip body it is an **OVERWRITE** of the frames the clip covers (tinted range). On overlay, audio
and title lanes there is **no insert**: landing on clips is always overwrite, free space is a plain move (ghost only).
The 'add lane' zone (below the ruler above the stack, or the ruler itself) creates a new overlay lane (placeholder tint); a
finger outside the panel is a **cancel** (red wash, release restores). A base clip dragged within the base only reorders (its
indicator is an insertion marker at the cut it lands on); it can never leave the base. A gap between lanes keeps the last lane.
Overwriting onto the base keeps its length (a drop past the end is placed at the end) and clears overlays above the replaced
frames like a deleted range, without closing it. One undo step per drop. **Why:** the user asked for LumaFusion's
position-driven behaviour with a live indicator instead of a confirmation prompt. **Alternatives:** a bottom-sheet prompt;
a zoom-dependent radius in pixels (the frame radius is large when zoomed in and tiny when zoomed out); using the clip centre
or end edge. **Deferred:** inserting (shifting later clips) on overlay and audio lanes; 'remember my choice'.
**Open:** whether clearing overlays above an overwritten base range is wanted (the alternative is to leave them untouched).

## 2026-10-03 · Reordering lanes with toolbar buttons
**Chosen:** up/down buttons for the selected lane; overlay video lanes swap with each other (their order is the stacking
order), audio lanes with audio lanes, and the base never moves or gets passed. **Why:** cheap and unambiguous on a touch
screen; long-press dragging of lane headers can come later. **Alternative:** long-press a lane header and drag it.

## 2026-10-03 · CI: two jobs, host tests separate from the Android build
**Chosen:** `.github/workflows/ci.yml` runs on pushes to master and on pull requests with two parallel jobs. `native-host-tests`
(no SDK, no submodule) runs `scripts/run-native-tests.sh` and the CMake/ctest host tests; `android` checks out the whisper.cpp
submodule, installs platform 37 / NDK 29.0.14206865 / CMake 3.31.6 with `sdkmanager` (the runner image already provides the SDK and sdkmanager; `android-actions/setup-android@v3` failed on a removed `tools` package), then runs
`:app:testDebugUnitTest :app:assembleDebug` and uploads the debug APK. Superseded runs are cancelled. Actions are pinned to major tags.
**Why:** the host tests are seconds long and must not wait for the slow first arm64 build of ggml/whisper. **Alternatives:**
one job; caching the NDK build output (skipped until the build time proves it matters); a release/signing job (needs secrets,
not wanted yet). Instrumented tests are deliberately not in CI (no device, and they wipe app data).
Housekeeping in the same PR: `scripts/run-native-tests.sh` was not executable in git (CI would have failed), a committed
`.pyc` was removed and `__pycache__` ignored, and CLAUDE.md now calls the test phone an OPPO (CPH2841).

## 2026-10-03 · Animated captions: look per frame in Kotlin, exporter splits clips by look
**Chosen:** a caption keeps its words with timing (`TitleContent.words`, clip frames) and an `animation`; `CaptionAnimator`
(pure integer maths) gives the `TitleLook` for a frame, the preview keys its raster by that look and the exporter emits one
`VideoClipSpec` per run of equal look. The rasteriser hides/highlights words with spans (transparent hidden words keep the
block size, so nothing moves) and redraws the active word scaled around its centre. **Why:** no native or shader change, and
the preview and the export cannot disagree because both use the same function and the same rasteriser. **Alternatives:**
one clip per word state at generation (clutters the timeline and loses the phrase); a native text renderer with per-glyph
state (a new engine subsystem); evaluating looks in C++ like keyframes (a second implementation to keep in step).
**Costs:** a caption is re-rasterised for each new look (a typewriter phrase of 30 letters is 30 small bitmaps; the cache
holds 96); during playback each change of look re-anchors the native clock, as an animated clip already does.

## 2026-10-03 · Animated captions: the four new styles and their numbers
**Chosen:** Karaoke (highlight + 110 % swell on the spoken word, all words visible), Word pop (words appear as spoken, newest
highlighted and settling 135 -> 118 -> 106 -> 100 % in 2-frame steps), Typewriter (letters appear across each word's span),
Bounce (phrase enters with eased keyframes 50 % -> 115 % -> 94 % -> 100 %). The pop steps are counted in frames, not
milliseconds, so the pop is shorter at 60 fps than at 30 fps. **Why:** frame counts keep the maths integer and the picture
set small; the difference is a few tens of milliseconds. **Alternative:** time-based steps converted with the project rate.

## 2026-10-03 · Animated captions: entrance is keyframes, highlight uses the title's own colour fields
**Chosen:** bounce/scale-in are real keyframes on the clip (editable with the existing keyframe tools, cropped correctly by
split/trim), added when a caption is generated or restyled, so a restyle replaces any keyframes the user had on a caption.
`TitleContent.highlightArgb` (default yellow) is the one emphasis colour. **Why:** reuses the keyframe pipeline in preview
and export with no new code path. **Alternative:** keep the user's keyframes on restyle (then a hand-made animation fights the
style's entrance).

## 2026-10-03 · Restyle all captions: scope, reset and what survives
**Chosen:** "restyle" applies to every clip with the `caption-` id prefix on every title track (not only the selected one),
as one undo step; text, timing and timeline position survive, while size, colours, vertical position, animation and entrance
follow the style. Colour swatches override the chosen style's colours and reset when another style is picked; the sheet also
opens alone (no clip needed) when captions exist but no clip with audio is selected. **Why:** the goal is a quick look
change for a whole video; captions are found by id prefix because generated captions are the only titles with word timing.
**Alternatives:** restyle only the selected track; keep manual positions; tag captions with an explicit flag in the JSON.
Text edited by hand loses its word timing (words are re-spaced evenly across the clip) rather than keeping stale timing.

## 2026-10-04 · Photos and stickers are title-like still clips drawn through the title texture path
**Chosen:** a clip gets `still: StillKind?` (`PHOTO`/`STICKER`) on a video track; it has no source length (range = length, normalised
in `Clip.cropped`), and is rendered by rasterising the picture in Kotlin to canvas-sized RGBA and uploading it with the existing title
texture API, in the preview and in the exporter alike. **Why:** zero native changes, so preview and export stay identical and
effects, keyframes, blend modes and HDR handling work for free; every title/media special case in the domain became one `hasMedia`
check. **Alternatives:** a native still-image decoder path with a GL texture per asset (more code, native risk, nothing gained until
photos need more than canvas resolution); treating a photo as a one-frame video asset with a long retime (breaks trimming past the
source and the magnetic-base maths).
**Limits:** a photo is stored at canvas resolution (fit inside), so zooming a still beyond 100 % looks soft; raise the raster size
(or add a native high-res path) if that matters. All stills of an export are rasterised up front and held in RAM together (about
8 MB each at 1080p, 33 MB at 4K): very large photo montages at 4K can run out of memory; stream them if that shows up.

## 2026-10-04 · Sticker set: drawn shapes plus system emoji, ids are persistent
**Chosen:** 8 procedurally drawn shapes (heart, star, arrow, check, burst, speech bubble, ring, alert) and 8 emoji drawn with the
platform's emoji font; no bundled images, no network, no licences to track (the shapes are original code). Ids (`shape:*`,
`emoji:*`) are stored in project files and must never be renamed. **Why:** a license-clean, zero-asset set that still looks like
a sticker (dark outline so it reads on any footage). **Alternatives:** bundled PNG/SVG packs (licence tracking, APK size);
downloading packs (network, caching). Emoji glyphs differ by device/OS version, so an export made on another device may look
slightly different from the preview made on this one.

## 2026-10-04 · Adding a sticker or photo: where it goes, how long it lasts
**Chosen:** a sticker lasts 3 s and goes on the selected overlay lane, else the top overlay lane, else a new lane above the base (its
own undo step) and overwrites what it overlaps there; a photo lasts 5 s and is placed like imported video (inserted on the base with
a ripple, overwritten on an overlay). Both are selected afterwards (stickers also open the inspector). **Why:** stickers are overlays
by nature, and the base-ripple rule is already how imports behave. **Alternative:** ask where to put it, or a longer default for
photos on overlays.

## 2026-10-04 · Still clips on the timeline: no thumbnail tile yet
**Chosen:** stills are drawn as plain clip blocks (no waveform, no thumbnails, their snapshot asset key is -1). A single-tile
thumbnail needs a native upload path into the thumbnail atlas, which could not be verified on a device in this session (the OPPO
was not reachable), so it is deferred rather than shipped blind. **Alternative:** pre-seed the on-disk tile cache from Kotlin
(couples to the tile file format). Animated GIF/WebP are also deferred: `ImageDecoder` returns the first frame, which is what shows.

## 2026-10-04 · Missing media: probe on load, mark, keep editable, relink as a saved change
**Chosen:** the load-time probe (`MediaImporter.verify`) decides what is missing; a file readable now but whose permission cannot be
persisted (`file://`, the permission limit) is not missing. Missing clips stay editable and are tinted/hatched by the native canvas
(clip flag bit 2, no snapshot version bump); the preview, mixer and workers skip the file; export refuses before asking where to save and
names the clips by lane and time ("V2 at 0:10"). Relinking is a saved library change and not an undo step, because undoing it would
restore a file the user just said does not exist. **Alternatives:** treat any failed permission take as missing (would flag readable
`file://` test media); make relink undoable; let export continue with black holes.

## 2026-10-04 · Relink: asset key replaced, waveform file deleted, thumbnails left to self-invalidate
**Chosen:** relinking gives the asset a new native key (`KeyRegistry.rekey`) and deletes `waveforms/<id>.peaks`; thumbnail tiles are
already tied to the media size, so they rebuild by themselves. **Why:** the waveform file has no size check, so a same-length replacement
would keep drawing the old waveform. **Alternative:** add a size/hash header to the peaks file (native change, more risk).

## 2026-10-04 · Replacement checks: reject what cannot work, warn about the rest
**Chosen:** reject a duplicate file, picture/video mix-ups, a replacement without video for a video asset, and without audio for an audio-only
asset; warn (but accept) about no audio, a file shorter than the part in use, another frame rate or colour space. Frames stay as they are
(source ranges are in project frames), the asset's duration/fps are re-read from the new file. **Alternative:** refuse shorter files.

## 2026-10-04 · Persisted URI permissions: release the unused ones only near the limit, at startup
**Chosen:** at app start, when 80% of Android's 512 persisted permissions are held, release those no readable project refers to.
**Why:** Android silently drops the oldest beyond the limit, which would turn old projects into "missing media". Doing it at startup and
only near the limit keeps it cheap and avoids releasing a permission for an import that is still being saved. **Alternative:** release when
a project is deleted or a file relinked (more bookkeeping, same effect).

## 2026-10-04 · Project recovery: `.bak` = last good save, prefer the temp file, keep the damaged file
**Chosen:** each save of a parsable file copies it to `project.json.bak` first, so a crash can lose at most the newest save. Recover restores the
newest parsable of `.tmp` then `.bak`, and keeps the damaged file as `project.json.corrupt`; nothing is overwritten when no copy parses.
The hub lists such projects with Recover (only when a copy parses) and Delete. **Alternative:** a rotating history of several backups
(more disk, more UI).

## 2026-10-04 · Save failures: banner, three quiet retries, refuse to leave
**Chosen:** a failed autosave sets a persistent banner with Retry, is retried three times 5 s apart, and Back is refused with a dialog (Retry /
Leave without saving) while the project is not on disk. **Why:** closing used to emit Close even when the last save had failed, silently
dropping the edits; unbounded retries also hang tests and hammer a broken disk. **Alternative:** keep retrying for ever.

## 2026-10-04 · Interrupted sessions: a marker written with commit(), a hub offer
**Chosen:** the open project id is stored synchronously when the editor opens and cleared when the user leaves it; if it is still set at
the next start the hub offers to reopen that project (dismissable). **Alternative:** reopen it automatically (surprising after a crash
caused by that very project).

## 2026-10-04 · Not installed on the OPPO
The OPPO (CPH2841) was reachable, but the app was in the foreground with the screen on, so it was being used by hand. Installing would
have killed the process and could have lost its last edits; nothing was installed or tapped. The relink UI, the hatched clips, the
banners and the hub Recover/Delete rows are therefore verified by JVM tests and native host tests only.


## Base clips can be lifted onto an overlay

**Decision:** dragging a base clip onto an overlay lane (or above the lanes, which creates a new lane) lifts it off the base: the base closes the gap it leaves, no overlay is deleted or shifted (it is a move, not a delete), and the clip lands at the dropped frame on the overlay, overwriting what it covers there. Dragging within the base still reorders. Command `LiftFromBase` (one undo step), decided by `DropPlan` so the indicator matches.
**Why:** the user reported that after dropping an overlay clip onto the base it could not be dragged back up; the earlier "a base clip never leaves the base" rule was too strict and unlike LumaFusion.
**Alternative:** leave a gap on the base where the clip was (breaks the gap-free base invariant), or keep the overlays where they were (they would lose their footage alignment).

## 2026-10-04 · Beat detection: Kotlin, on the waveform peak cache, amplitude only
**Chosen:** beats come from a pure-Kotlin detector (`domain.beat.BeatDetector`) fed with the loudness envelope of the existing
waveform peak cache (`waveforms/<assetId>.peaks`, finest level, ~750 bins/s): onset curve = positive rise of log energy, tempo =
autocorrelation over 60-180 BPM with a prior near 120, beats on the best phase pulled to onsets. **Why:** no second audio decode, no new
native code or JNI, deterministic and unit-testable with synthetic click tracks, and it runs off the main thread in milliseconds.
**Limits:** amplitude only (no spectrum), so speech or ambience gives "no clear beat", and a fast pulse may be found at half tempo (the
beats still sit on real beats). It needs the waveform to exist first, which the timeline already requests for every clip on screen.
**Alternative:** spectral-flux onsets in C++ over decoded PCM (more robust on dense music, but a new native analysis path that could not
be checked on the device here), or a tempo library.

## 2026-10-04 · Markers: absolute frames, two kinds, beats replaced per clip range
**Chosen:** markers sit at absolute project frames and do not move when clips are edited; `MANUAL` (placed at the playhead, toggled on
the nearest one within 2 frames) and `BEAT` (detected). Re-analysing replaces only the beats inside the analysed clip's timeline range;
manual markers are never touched, and "Clear detected beats" removes all beats. **Why:** a marker is a point of the *edit* (a cut target),
not of a clip, and replacing per range lets several clips each keep their own beats. **Alternative:** anchor beat markers to their clip
so they travel with it (they would also need re-mapping through retime and trims on every edit).

## 2026-10-04 · "Cut to beat": selected base clip and everything after it, nearest marker, never past the media
**Chosen:** applies to the selected base-track clip and every later base clip, ending each on the nearest marker after its start (tie:
the earlier one). A clip with media can only grow as far as its source allows (titles and stills freely); when the nearest marker is out
of reach the latest reachable one is used, and a clip with none is left alone. It uses `MagneticBase.trim`, so the base ripples and
overlays follow, all as one undo step. **Why:** the editor has a single selection, and "from here to the end" is the usual way a montage is
tightened to a song. **Alternative:** multi-select and cut only those clips; or fixed beat-multiple lengths (every clip = N beats).

## 2026-10-04 · Snap to markers: on by default, one toggle
**Chosen:** moving, trimming and dropping snap to markers with the same 8-frame threshold as clip edges; a "Snap to markers" switch in the
markers menu turns it off. **Why:** the point of beat markers is to land cuts on them, and a dense beat grid can make dragging sticky, so
it must be switchable. **Alternative:** snap only to manual markers, or only while a modifier gesture is held.

## 2026-10-04 · Text templates are data over titles, keyframes and plain bar stickers
**Chosen:** a template is a list of layers (text or bar) with a resting pose and keys given in seconds/canvas fractions; applying it creates
ordinary title and sticker clips with ordinary keyframes in one undo step, so preview and export match with no new render code. Bars are two
new sticker ids (`shape:bar-dark`, `shape:bar-accent`) that the picker does not list. Text is always centre-aligned and moved by offsets; a lane
is reused only if it is free over the range, otherwise a new one is added (nothing is overwritten). **Why:** the title rasteriser and caption
styles are changing in parallel work, so a background field on `TitleContent` would collide; bars as stickers reuse the still path.
**Alternative:** a `background` on `TitleContent` (one clip instead of two, but touches the rasteriser) or per-word animated text.
**Not verified:** how the templates look on the device (positions are canvas fractions chosen by reasoning, not by eye).

## 2026-10-04 · Markers and templates in the editor UI: one flag menu and one sheet
**Chosen:** the toolbar gets two buttons: a flag opening a menu (marker at playhead, find beats, cut to beat, clear beats, snap switch) and a
text-template sheet with an optional text field. **Why:** the toolbar already scrolls sideways; five more icons would bury the rest.
**Alternative:** a dedicated "Beats" panel with a BPM readout and per-beat editing, once markers are draggable on the ruler.


**Update:** per the user ("if you take a clip off the base, it goes to the layer where I drop it"), lifting is a pure move: overlays are never deleted or shifted. Trade-off: overlays that sat over later base footage no longer line up with it after the base closes. Alternative: shift later overlays with the base (risks overlap with overlays that cross the lifted range).

## Editor performance: keep the playhead out of the chrome

**Decision:** `EditorScreen` no longer reads the whole `EditorState` at its root. The playhead (which changes every 16 ms while playing) is removed from the state the chrome (toolbar, banners, dialogs, panels) reads (`chromeOf`), so a tick recomposes only the timecode; the effects that need the playhead read the live state through `StateEffect` (a `snapshotFlow` keyed on exactly what each effect uses). While the inspector is open it gets the live state, because its keyframe diamond and pose depend on the playhead.
**Why:** every tick used to recompose the root and all ~25 toolbar buttons and re-evaluate every effect key. Written from reading the code; the before/after numbers are in the PR when the OPPO was reachable, otherwise the PR says they were not measured.
**Alternative:** pull the playhead out of `EditorState` into its own flow (cleaner, but touches the ViewModel, every playhead-dependent getter and ~100 tests).

## Insert on non-base lanes

**Decision:** on overlay/audio/title lanes a drop whose start edge is within 10 frames of a cut between two touching clips inserts there and shifts only that lane's later clips right; free space, gaps and the lane ends stay plain moves (so moving a clip near the start of a lane never inserts). Command `InsertOnLane`, one undo step, decided by `DropPlan` so the indicator matches.
**Why:** the user deferred it earlier and asked for it afterwards; limiting it to interior cuts avoids surprising inserts when moving clips around free space.
**Alternative:** also insert at lane ends and at the edges of gaps (more reachable, more accidental shifts).

## Speed change on the base carries the overlays

**Decision:** changing the speed of a base clip (`MagneticBase.setSpeed`) ripples the following base clips and moves the overlays like a trim of its end: a shorter clip removes the freed frames from every other track (clips inside them go, crossing ones are trimmed, later ones shift left), a longer one opens the same room after the clip's old end. Overlays inside the clip's own range stay where they are.
**Why:** the speed inspector already rippled the base, leaving overlays out of step with it.
**Alternative:** scale overlays inside the range with the clip (not exact: an overlay has its own speed).

## Photo thumbnails

**Decision:** a photo clip now carries its asset key in the snapshot and the editor asks the native thumbnail worker for photo assets too. `ThumbDecoder::open` falls back from "no video track" to `AImageDecoder` (decoded scaled down, EXIF applied by the decoder) and every time maps to the one tile (`rgbaToTile`, host-tested). A photo the decoder cannot read simply shows no tile, as before.
**Why:** the cheapest path with no new snapshot field or GL code: the existing tile store, atlas and drawing do the rest.
**Alternative:** rasterise the still in Kotlin (the preview already does) and upload the bitmap as a tile (an extra JNI call per photo).

## Deferred: animated GIF/WebP, dragging lane headers

**Decision:** neither is done. Animated GIF/WebP needs per-frame timing and decode caches in the still pipeline (it is not cheap); lane reorder keeps the up/down buttons because the native canvas draws no lane header to grab, and a new hit target and gesture could not be verified without the OPPO.
**Why:** both would be unverified native/gesture work.
**Alternative:** `AImageDecoder` animated frames into the title-texture path as a follow-up; a long-press on a lane's empty area as the reorder gesture.

## Per-clip source colour space (mixing SDR, HLG and PQ in one project)

**Decision:** `Clip.colorOverride` (SDR / HLG / PQ, null = Auto) says how a clip's source is read; the project colour space stays the working and export space. Each preview layer carries it as a fifth-to-eighth `params` float (`-1` auto, `0..2` a `SourceTransfer`), the native layer then picks `colorModeFor(override, outputSpace)` instead of the asset's detected mode (and it is part of the draw signature, so changing it redraws); the export spec already carried a per-clip `colorMode`, which now prefers the override. The inspector shows "Auto (detected space)" plus the three overrides and a one-line note of what conversion applies in this project. Detection now falls back to HDR10 static metadata (`KEY_HDR_STATIC_INFO`) when a file has no transfer, and an explicit SDR transfer always wins. JSON: `ClipDto.colorOverride` (an id, unknown values read as Auto). The New Project dialog says the project space is the working/export space and clips are converted individually.
**Why:** the conversion matrix (SDR/HLG/PQ x SDR/HLG project) already existed natively per asset; mixing clips only needed a per-clip choice and honest detection. A per-asset override would have blocked two clips of one file read differently, and made undo/split semantics awkward.
**Alternative:** an asset-level override in the media panel (simpler, but cannot treat two cuts of the same file differently). Old projects keep their stored asset colour space; re-probing legacy assets for colour was not added (the import path already re-probes flags on load, and Auto + the per-clip override covers a wrong guess).

## Export time estimate (elapsed, time left, throughput)

**Decision:** a pure `ExportEstimator` smooths the engine's progress (EMA of the rate over samples at least 0.5 s apart, alpha 0.3). Nothing is shown until 2 s and 3 samples, remaining time is clamped to 24 h, a progress stall of 8 s shows "Waiting for the encoder…" instead of a guess, and throughput is frames/s and x real time from the whole run. The dialog ticks the elapsed time once a second; durations are rounded (5 s steps above two minutes) so they do not flicker. The clock is injected so tests are exact.
**Why:** the export bar alone gives no idea of how long a 4K or multi-layer export will take; a raw `elapsed / progress` extrapolation jumps wildly at the start and on stalls.
**Alternative:** a pre-export estimate from project complexity (layers, effects, resolution). Not done: it needs calibration data from several devices and clips, and the live estimate is accurate after a couple of seconds. There is no export notification in the app yet, so there is nothing else to show it in.

## FFmpeg fallback: designed, not built; Vulkan: evaluated, not adopted

**Decision:** the FFmpeg software-decode fallback is documented (`docs/ffmpeg-fallback.md`) but not implemented. ffmpeg-kit is archived and the Media3 extension is audio-only; the prebuilt `org.bytedeco:ffmpeg` arm64 jars work but add ~20 MB (23 MB for the GPL variant; `libavcodec.so` alone is 27 MB unpacked) for a feature nothing needs yet, so the recommended path is a minimal static LGPL build made in GitHub Actions and downloaded by checksum, plugged in behind an `IVideoDecoder` seam that writes software frames into CPU-writable `AHardwareBuffer`s so the cache and render path stay unchanged. Vulkan is evaluated in `docs/vulkan-evaluation.md`: not adopted, because the GLES renderer costs ~5% of a 4K60 frame and a second backend doubles the parity surface.
**Why:** both would be native code that cannot be validated without a device and real clips (ProRes/MPEG-2 files for FFmpeg), and neither fixes a problem the author has; building them blind would add risk and maintenance for no visible gain.
**Alternative:** vendor the bytedeco shared libs today (fast to wire, big APK, no control over the codec set) or start the Vulkan port now; both remain open if the triggers in the documents occur.

## 3D LUTs as a per-clip effect

**Decision:** `.cube` 3D LUTs (any size 2..65, so 17/33/65) are an effect type `LUT` (code 13: library key, intensity). The parser (`CubeParser`) accepts `TITLE`, `LUT_3D_SIZE`, comments and the default 0..1 domain and rejects 1D LUTs, other domains, wrong entry counts and non-finite values with the line number. LUTs live in an app-wide library (`LutStore`, `filesDir/luts/<key>_<size>_<name>.cube`) keyed by a 24-bit hash of the file's bytes (exact as a float in the effect's value list, and importing the same file twice is a no-op). The preview uploads each LUT once per key (parsed off the main thread) and the exporter receives the used LUTs in its request; both draw through the same effect pass: a half-float `GL_TEXTURE_3D` (RGB16F is filterable in ES 3.x, RGB32F is not) sampled trilinearly with the texel-centre mapping `(rgb*(N-1)+0.5)/N`, mixed by intensity. A LUT that is missing (library entry deleted, project from another device) leaves its clip ungraded and the preview says so. The LUT runs on the clip's pixels in the project's working space after the source-to-project conversion (Rec.709 gamma in an SDR project, the HLG signal in an HLG project), and the picker says so.
**Why:** a creative LUT is expected on display-referred pixels and the effect chain already works there; a per-clip effect reuses ordering, undo, keyframable-parameter plumbing, JSON and preview/export parity. A global hash key avoids copying LUT data into every project.
**Alternative:** LUTs stored inside the project (portable but bloats `project.json` or needs a project folder format); an adjustment/grade track that applies one LUT to everything below (a natural follow-up using the same shader); colour-space-aware LUTs (e.g. Rec.2020 log to HLG) which need transform metadata the .cube format does not carry.

## Media tray and drag and drop (WP-U2)

**Decisions**
- **Drops are planned by `DropPlan.decideNew`**, a sibling of `decide` for clips that are not on the timeline yet, with the same zones (base: insert near a cut / overwrite over a clip / append at the end; other lanes: overwrite or free placement; above the top lane: new lane; far outside or a lane of the wrong kind: cancel). The indicator and the release use the same decision.
- **Tray drags show the indicator only, no ghost clip on the canvas.** The platform drag shadow (the thumbnail) is the ghost. Free space is drawn like an overwrite (a tinted range) because the canvas has no "plain move" indicator; the new-lane placeholder needs a lane in the shown timeline, so the preview timeline gets one empty provisional lane (`track-v-new`). Alternative: a native "place" indicator (C++ change), not worth it for this package.
- **Reordering the tray reorders `mediaLibrary` itself**, so no new JSON field and old files load unchanged. Alternative: a separate order list, which would need syncing with imports and deletions.
- **Stickers and templates are tap-to-add, not draggable**, to keep the first version small; dragging them would need clip creation for stickers in the drop path. Alternative: payloads with a sticker/template id handled by `decideNew` too.
- **Files from other apps**: hovering uses a 3 s stand-in clip (their length is unknown until probed) so the indicator can show where they land; on drop they are imported, then the first is placed where it was dropped and the rest follow it. Read access is requested with `requestDragAndDropPermissions` (it lasts for the activity) and a persistable grant is attempted; if the provider refuses, the files still work this session and may need relinking after a restart. No copy of the file is made (privacy and size).
- **Thumbnails** come from `MediaMetadataRetriever` (first frame) and `ImageDecoder`, in a 12 MB in-memory LRU; no disk cache and nothing leaves the device.
- **The bottom tray starts collapsed** (a thin tab strip) with half and full heights; sticker and template toolbar buttons now open its tabs instead of modal sheets.
- **Audio on a video lane cancels** instead of jumping to the nearest audio lane, so what the indicator shows is always what happens.

## Privacy (user rule: no AI, no third-party services, no network)

The user ruled out AI features and any dependence on third-party services ("la privacidad es algo importantisimo"). These
choices were made autonomously to implement that rule strictly; confirm or change them.

- **Remove on-device speech recognition entirely (whisper.cpp), not just make it opt-in.** Why: it is an AI feature, it needed a
  model downloaded from a third-party host (Hugging Face) and therefore the INTERNET permission, and it added ~2 minutes to
  every native build. Alternative: keep it as an opt-in feature with the model sideloaded from a local file (no network). The
  rule says no AI, so it was dropped; the submodule, native pipeline, JNI, model store, download code and notices are gone.
- **Replace it with manual and file-based captions.** Typed captions (text, start, length, stepped by frames/seconds) and
  `.srt` / `.vtt` import (`Subtitles`), both feeding the existing caption styles, including the animated ones, through evenly
  timed words. Alternative considered: also `.lrc` (word-timed lyrics) and `.ass`; deferred, the parser is isolated and easy
  to extend.
- **The manifest declares no permissions at all, and cleartext traffic is disabled.** `OfflineGuaranteeTest` fails the build if a
  network permission, a networking API (HTTP, sockets, WebView, download manager), an analytics / crash-reporting / ads
  dependency or a socket header in the engine appears. `allowBackup` was already false and stays so (test-enforced), so
  nothing is uploaded by Android auto-backup. Alternative: only a manifest check; the source scan is cheap and catches the
  case where someone adds a library that brings the permission in through manifest merging only at build time (the scan
  also covers dependencies by name).
- **`CaptionPlanner` / `Transcript*` types are kept** even though nothing produces word-timed transcripts now: the planner is pure,
  tested, and a word-timed source (lyrics files) could use it. Alternative: delete as dead code.
- **Typed captions share one caption track, imported files get one track each.** Why: one-at-a-time typing would otherwise create
  a track per caption; a file per track keeps languages apart and makes undo of an import one step. Alternative: always one track.
- **WP-V1 smart cutout (ML segmentation) removed; motion tracking kept (classical Lucas-Kanade).** Chroma/luma key and masks
  stay the keying tools.
- **WP-V2 auto reframe by subject detection removed; silence-based auto cut kept and a manual start/end reframe helper added.**
  Speech-based editing (delete words in a transcript) removed with the transcript.
- **WP-V3 text to speech, vocal isolation and speaker-aware captions removed; voice effects (classical DSP) kept.** The Android
  system `TextToSpeech` service is not used: some engines synthesize on a server, and the app cannot tell which. Alternative:
  offer system TTS only when the engine reports it works offline (`Voice.isNetworkConnectionRequired`); dropped for strictness.
- **WP-A noise suppression uses spectral gating / Wiener filtering, not RNNoise or any neural denoiser.**
- **WP-R crash reporting is local logs only; no reporting service.** The privacy note points to `docs/PRIVACY.md`.
- **WP-V5 content packs: "bring your own" music and sounds, no bundled commercial library, no downloads.**
- **`docs/PRIVACY.md` states the guarantees and how to verify them**; the README links to it and PRD gains a privacy section.

## New-project sheet: selectors, quick presets and "match first clip" (WP-U1)

**Decision:** the sheet has four dropdowns (aspect ratio, resolution as the short side, frame rate, colour space) plus a quick-start chip row; the pixel size is computed (short side x shape, both sides rounded to even) and written under the selector, with Custom size / Custom short side as typed fields validated to even values 128..8192. "Match first clip" reads a picked clip with a new `ClipPeeker` (no persistable permission, no import), copies size (as a Custom size), frame rate (a listed rate or a one-off) and colour space (PQ maps to the HLG project space); a photo only gives its size. The last selector choices are saved in a small SharedPreferences store; a format taken from a clip is not saved. The sheet is a bottom sheet below 600 dp and a dialog above.
**Why:** the old dialog showed ~25 chips at once; dropdowns show one value each and presets cover the common cases in one tap. Reading a clip without taking a permission avoids spending one of Android's 512 persisted grants on a clip that is not in the project.
**Alternative:** keep chips (cluttered), or make "match" import the clip into the media library (spends a permission and adds media the user did not ask for). Photo EXIF orientation is not applied when reading a photo's size (a rotated photo may report its sensor orientation); the user can edit the size afterwards.

## Hub cards: thumbnail, length, search and sort (WP-U1)

**Decision:** cards show a 320 px first frame of the earliest video/photo clip (via `MediaMetadataRetriever` or a bounded bitmap decode), cached as JPEG in the app cache keyed by a hash of source and time (older files of that project are removed), the project length (end of the last clip, from `project.json` in the summary) and a short format line. Search and sort (Recent / Name) appear only above six projects. Import moved to the top-bar overflow menu.
**Why:** a card should identify the project at a glance; the cache directory may be cleared by the system and is regenerated, so there is nothing to migrate or clean up, and nothing leaves the device.
**Alternative:** store a `thumb.jpg` inside each project folder (survives cache clearing but must be copied/cleaned by clone, delete and export); always show search (noise for short lists).

## Colour grade: one effect, curves sent as 33 baked samples (WP-C)

**Decision:** the grade is one effect type (`COLOR_GRADE`, code 14) with 21 values (lift/gamma/gain wheels with master, offset, contrast + pivot, saturation, vibrance, temperature, tint) plus four tone curves. The curves are baked on the CPU (monotone cubic through up to 8 points) into 33 samples per channel and travel on the existing effect wire as 132 extra doubles; the shader reads them from a `uniform vec4 uCurve[33]` and interpolates linearly. Order inside the pass: white balance, offset, contrast about the pivot, lift, gain (in stops), gamma, saturation and vibrance, then master curve followed by the channel curve. `render/grade_math.h` is the CPU reference, pinned by host tests; `domain/Grade.kt` does the baking.
**Why:** it reuses the whole effect pipeline (undo, JSON, preview/export parity, ordering) with no new texture upload path, and 33 samples keep the interpolation error under half an 8-bit step for ordinary curves. The spec asked for a 256-entry 1D LUT texture; a uniform array avoids a texture per grade and per clip.
**Alternative:** a 256x1 RGBA texture per grade (more precise for extreme curves, but needs upload and lifetime handling in the preview and export paths); spline control points as uniforms (64 floats, evaluated per pixel, slower). The standalone Temperature/Tint effects stay for old projects; the grade has its own with the same formulas.

## Colour wheels: +-0.5 per channel plus a master slider (WP-C)

**Decision:** a wheel is a unit disk (red at 0 degrees, green at 120, blue at 240, clockwise on screen); the puck sets the three channel values so they sum to zero and span +-0.5, and the master slider (-1..1) carries the rest. Lift = 0.5 x (master + channel), gain = 2^(master + channel) stops, gamma exponent = 2^-(master + channel). Double tap resets a wheel.
**Why:** it matches how colourists use wheels (hue and strength with the puck, level with the master) and keeps the sums neutral so a wheel never changes brightness by itself.
**Alternative:** independent R/G/B sliders (precise but not how the tool is used); a wheel range of +-1 (twitchy on a phone).

## Looks and copy/paste are app-wide and local (WP-C)

**Decision:** saved looks are JSON files in the app's private storage (`looks/<id>.json`), shared by all projects, validated when listed and ignored when corrupt. Copy and paste of a grade use an in-memory clipboard of the screen session. Applying a look or pasting replaces the clip's first colour grade (or adds one) as one undo step. Names that collide get a number.
**Why:** a look is a convenience that should follow the user across projects, and nothing leaves the device.
**Alternative:** store looks inside each project (portable but not reusable); persist the clipboard (surprising after a restart).

## Video scopes: GPU point accumulation on a second surface (WP-C)

**Decision:** while the scopes panel is open the preview blits the letterboxed picture from the window framebuffer, before the swap, into a 320 x 180 texture; each of its pixels becomes one point (vertex shader, `gl_VertexID`, additive blending) in a half-float accumulation texture; a display pass turns counts into the picture and graticule on a second EGL window surface of the same context (a `SurfaceView` stacked above the preview). Waveform (luma), RGB parade, vectorscope with skin-tone line and primary targets, and histogram with a luma line. At most 30 Hz, a late redraw is scheduled so a paused scope never goes stale, nothing is read back to the CPU, and everything stops when the panel closes. Scale labels are Compose text and depend on the project space (percent, and 203/1000 nit marks for HLG). `render/scope_math.h` is the CPU reference used by the host tests.
**Why:** no pixel readback, no extra decode, cost proportional to 57,600 points, and the scope shows exactly what the preview shows. Sharing the context avoids copying textures between threads.
**Alternative:** CPU histogram from a readback (stalls the GPU), a compute shader (more GLES 3.1 surface area for the same result), a separate engine with its own context (cannot see the preview frame).

## Colour qualifiers (HSL keys) deferred (WP-C)

**Decision:** the secondary HSL qualifiers of the spec are not part of this package; the primary grade, curves, looks and scopes are.
**Why:** they need a mask or key output channel in the effect chain, which is a larger change than the rest together.
**Alternative:** approximate with the chroma-key effect (not the same tool).

**Confirmed by the user (2026-10-04):** remove whisper and the automatic transcription.

## Resizable layout (WP-U3)

**Decisions:**
- `LayoutState` is pure data with a reducer and is saved as one `key=value` line per window class and orientation in a private preferences file; reading is forgiving and the result is clamped to the window. Why: unit-testable, no new dependency (no DataStore). Alternative: DataStore.
- Dividers read the layout while measuring (custom `Layout` containers), so a drag re-measures instead of recomposing the editor; drags are coalesced to one update per 40 ms and flushed at the end, and preferences are written once per gesture. Alternative: `weight` modifiers (recompose per step).
- The lane height is a scale (0.75, 1, 1.4) applied by the native timeline (`setLaneScale`), so waveforms, thumbnails and diamonds follow without Kotlin knowing about them. Alternative: scale in Compose (not possible, the lanes are drawn natively).
- Side docks need a window of at least 600 dp; below that docks fall back to bottom / over the timeline. The editor keeps at least 30 % of the width.
- The bottom tray's collapsed state is a thin bar of the layout (the tray's own snap heights stay inside it).
- Lane height has +/- and chips; the vertical pinch was added later (see "Vertical lane zoom and Fit" below).
- Added `-Puveditor.appIdSuffix=<name>` for debug builds so several people or agents can install side by side with separate data (it solved agents overwriting each other on the shared Pixel).
**Found while testing:** the ToolButton tooltip wrapper broke `Modifier.align` (fixed in master by #42 in the same way) and the bottom tray took the whole editor on phones (fixed by #47).

## WP-T: multilayer titles, fonts and presets

- **A title is one clip drawn into one bitmap.** Layers (text, shape, image) live inside `TitleContent.layers` and are rasterised together by `LayeredTitleDrawer`, instead of making each layer a separate clip. *Why:* preview and export, the key cache, keyframes, effects, blend modes and transitions already work on a title clip; one clip also moves, trims and deletes as a unit and a preset is one file. *Alternative:* a group of clips on several lanes (what the old templates did): flexible per-layer animation but fragile (lanes, collisions, ungrouping). *Consequence:* the whole title animates as one; per-layer animation is not possible (the old lower third staggered its bar and text; the new one slides as a unit).
- **Plain titles stay.** `layers` is empty for plain titles and captions, which keep their own drawer and word animation; a plain title converts to layers on request (`Edit as layers`), except captions with word timing. *Why:* old projects, captions and their tests keep working unchanged.
- **Layer order.** Stored in painter's order (index 0 at the bottom), listed top first in the editor. *Why:* matches how drawing works; LumaFusion 5.5.3 lists top first.
- **The bitmap is symmetric around the canvas centre and cropped.** Because the compositor centres title bitmaps and the native side is unchanged. *Alternative:* full-canvas bitmaps (simple, but up to 33 MB per 4K title; the cap is 0.6 of the canvas beyond each side, so at most 1.2x the canvas per side).
- **Photo layers store the asset id; the file is resolved at render time** (`ImageLayer.resolvedUri`, never stored). *Why:* relinking a missing photo then just works and changes the cache key. *Alternative:* store the URI (breaks on relink and on other devices).
- **Photos are not saved in presets.** *Why:* they are files of one project (and privacy: a shared preset must not carry a reference to the user's media). Stickers, text and shapes travel.
- **Fonts are imported, not downloaded, and not embedded.** Font id = SHA-256 prefix of the file, so a project or preset names a font the same way on every device; a missing font falls back to the system font with a banner. *Why:* privacy (no network), licences (we must not redistribute a font inside a shared file), simplicity. *Alternative:* embed fonts in projects (works anywhere, but redistributes the font); a bundled font set (licence-clean fonts could be added later).
- **No `.ttc` collections.** The picker gives one file; choosing a face inside a collection needs UI we do not have yet.
- **Animation is the clip's keyframes.** `MotionPreset` generates ease keyframes over the first and last 0.4 s (editable afterwards with the usual keyframe tools), and applying one replaces the clip's keyframes. *Alternative:* a stored `intro`/`outro` on the clip that is evaluated at render time (cleaner to edit, but a new concept everywhere).
- **Built-in templates migrated to the preset format** (one multilayer title each). `AddTextTemplate` now needs two ids (clip, spare lane) instead of one per layer plus two.
- **Layer handles on the preview are a ring and cross** at the layer centre (plus the outline of a shape) with the existing gestures redirected, not corner handles. *Why:* text and picture bounds need native measurement; the gestures cover move, scale and rotate.
- **Decoding photos for a title happens where the title is rasterised** (the main thread in the preview, with a small cache), not on a worker like photo clips. *Why:* a title is rasterised synchronously today; layers are few and decoded at their drawn size. *Alternative:* a worker with a placeholder (more code); revisit if large photos cause jank.

## Multi-selection and group edits (WP-S)

**Decision:** selection is `selectedClipId` (primary) plus `selectedClipIds` (the group, when more than one); a plain tap, an empty-space tap or Clear resets the group, long press toggles a clip in any mode, and select mode adds taps and a marquee on empty lane space. The marquee is native state and `clipsInRect` runs natively. Group operations are pure `Timeline -> Timeline` functions (`GroupOps`) behind one command each, so they are all-or-nothing and one undo step.
**Why:** it reuses the immutable-snapshot undo and the single-clip rules (magnetic base, ClipDeletion), keeps the primary clip for the inspector, and needs no new native selection model beyond a primary flag (snapshot version 6) and the marquee.
**Alternative:** a native selection set owned by the canvas (more state to keep in step), or moving group selection logic into the view layer.

**Decision:** moving base clips together is only allowed for a run that touches and contains only base clips; it reorders the run (`MagneticBase.reorderBlock`). Mixed base and overlay selections only support attribute operations, with a message.
**Why:** the base has no gaps, so a free offset move has no meaning there, and mixing would need a rule for what the overlays do.
**Alternative:** allow mixed moves by reordering the base and shifting the overlays by the same delta.

**Decision:** paste puts overlay clips back on their lane (or the first lane of their kind) and refuses to land on an existing clip; duplicate pastes right after the last selected clip. Nothing is overwritten.
**Why:** a group edit that silently replaces footage is destructive and hard to see; a message is cheap.
**Alternative:** overwrite on overlay lanes like a single drop, or put colliding copies on a new lane.

**Decision:** "head and tail" transitions are opacity keyframes (a fade in and a fade out of each picture clip), because the model has transitions only between two clips; audio fades come with the audio tools.
**Why:** it gives the same visible result as LumaFusion's head and tail dissolves without a new transition type.
**Alternative:** a one-sided transition type in the domain, rendered by the compositor and exporter.

**Decision:** a group drag shows no insert/overwrite indicator, only the live preview and the cancel tint off the lanes.
**Why:** group moves on overlays are free-form, and a base run reorders like a single clip, whose insertion marker would be ambiguous for a block.
**Alternative:** extend `DropPlan` with group decisions.

**Found on the Pixel 8:** a plain tap on a clip that was part of the group left the group alive, and the canvas did not redraw the outlines when only the group changed (the snapshot key lacked `selectedClipIds`). Both are fixed, with a regression test for the first.

## Audio tools (WP-A)

**Decisions:**
- One per-sample DSP path (biquad EQ, balance pan, bus compressor, sidechain ducker, -1 dBFS brickwall limiter) is used by the realtime engine and the offline export mixer, so they are identical by construction; a host test compares the two. Block size never changes the result. Alternative: separate offline code (drift risk).
- Pan is a balance law (mono is placed with equal power, stereo is attenuated on one side) so a centred stereo clip is untouched. The limiter is a sample-peak limiter at -1 dBFS, not a true-peak one (spec says dBTP); oversampling can be added later.
- Noise suppression is STFT spectral subtraction (1024 window, hop 256) with the profile from a user-marked quiet stretch, run in the decode worker so playback and export share it. No Wiener/neural option. Alternative: Wiener filter (more musical noise control, more tuning).
- Loudness is BS.1770 K-weighted, gated, measured by the native engine on IO and cached by source identity and range; normalise stores a gain (`normalizeDb`) in the clip so it is cheap at playback.
- Ducking is computed gain automation from the voice track envelope (roles Voice and Music on tracks), never baked into clips.
- Audio snapshot is version 4 with strict native validation; the new JSON fields are optional so old projects load unchanged and old readers ignore them.
- Slider drags are "audio sessions": live preview without touching the history, one undo step on release.
**Not verified:** how the noise suppression and EQ sound on real speech (only synthetic signals in tests); the Pixel was used for a smoke test only (app starts, playback with the new mixer, mixer sheet and meter appear). My first device checks looked at another agent's `.wpc` install because the focus check matched by package prefix; checks now match the exact activity.

## Stabiliser (WP-X)

- **Classical computer vision, written here, no OpenCV.** Shi-Tomasi corners, pyramidal Lucas-Kanade with a
  forward-backward check and a RANSAC similarity fit are about 600 lines of C++ with no dependencies, no models and no
  network (the project's privacy rule). OpenCV would add several MB per ABI and a dependency for three functions.
  Alternative: OpenCV's `calcOpticalFlowPyrLK` + `estimateAffinePartial2D`.
- **The cache holds the raw frame-to-frame motion, not the smoothed correction.** Strength and crop then change
  instantly (the table is rebuilt in milliseconds) and the cache key does not depend on them. The spec said "output
  per-frame correction stored in a cache file"; the correction is what the registry holds.
- **One 24-bit key per (asset, strength percent, crop); the table is looked up by the source frame being drawn.** The
  scene description and the JNI signatures did not change: the effect only carries the key and the native side
  resolves the per-frame values in the draw loop (preview and exporter), so retime, reverse, transitions and export
  parity come for free. Alternative: bake the correction into per-frame keyframes (thousands of keys, and it would fight
  the user's own keyframes) or pass a table per layer through the scene.
- **Stabilise is a clip property, not an entry of the effect list.** It does not use one of the 8 effect slots, cannot
  be reordered or added twice, and always runs first (it moves pixels; the colour effects, mask and blend come after).
  The wire allows 9 effects per layer for this (`kMaxWireEffectsPerLayer`).
- **Frames are numbered at the project frame rate, from the media's first frame**, the same as the preview decoder
  (it is opened with the project rate as override). The analysis stores presentation times and the table resamples the
  path to project frames, so a 60 fps source in a 30 fps project works.
- **Gaussian smoothing of the camera path's parameters with a mirrored *odd* extension at the ends.** Simple,
  deterministic and tunable with one number (sigma from 0.1 s at strength 0 to 2.5 s at 1; the default strength 0.3 is
  about 0.8 s). The odd extension keeps a steady drift steady up to the first and last frame. Alternative: L1-optimal
  paths (better at separating pans from shake, much more code).
- **A constant zoom per clip** (tight = what the worst frame needs, capped at 2x; medium = half; full = none) instead of
  an adaptive per-frame zoom, which makes the picture "breathe". Edge fill always repeats the border pixels (edge mode
  1); transparent edges (mode 0) are in the shader for a later "show the background" option.
- **The analysis covers the clip plus 1 s on each side and merges with what exists.** A trim outwards within the
  margin needs nothing; further out the clip is *Stale* and one tap re-analyses the union. The cache lives with the
  project (`projects/<id>/stab`), not in the system cache folder, so it is not lost when the system trims caches.
- **Progress by polling, not callbacks.** The native job exposes a packed state long that Kotlin polls every 150 ms;
  no JNI callbacks, no thread attachment, nothing to leak.
- **The preview redraws when the table registry changes** (a revision counter in the draw signature), so a finished
  analysis or a new strength shows without scrubbing.


## Generalised keyframes (WP-K)

**Decisions:**
- Pose stays as joint `Keyframe`s; `pose.*` parameters are a per-parameter view over them (`Clip.paramKeys`). `Clip.params` never holds `pose.*` ids, so old projects load unchanged and the new JSON field is optional. Alternative: split pose into per-component tracks (migration and native evaluator change).
- Keys are in clip frames (0 = first frame of the clip), so they travel with the clip; split/trim/overwrite crop them, speed changes stretch them. Multiselect copy/paste must treat `Clip.params` exactly like `keyframes`: tracks are relative to the clip start and are copied and pasted with the clip.
- Bezier uses CSS `cubic-bezier` handles (x in 0..1, y in -2..3, default 0.42/0.0) solved by bisection. The native pose evaluator only knows linear/ease/hold, so Bezier pose segments are baked to per-frame linear keys at export.
- Effect values are exported as a per-frame table (`fxFrames`, concatenated `layer_fx.h` blobs) evaluated in Kotlin, the same function the preview uses, instead of porting track evaluation to native. Alternative: native keyframe evaluation (second implementation, parity risk).
- Audio snapshot is version 5 (still parses 4): per-clip lanes for gain, pan and EQ gains. The mixer processes in absolute 32-sample chunks (gain ramps linearly per chunk; pan/EQ evaluated at the chunk middle; all five EQ band stages are kept so filter state is stable), so realtime and offline are identical and block size does not matter. Hold ramps over its last frame before the next key.
- Inspector shows the clip as it is at the playhead (`displayedClip`); a control change on an animated parameter writes a key at the playhead (keeping that key's shape), otherwise the static base value changes. Removing the last key writes its value back to the static field; removing an effect drops its tracks.
- Colour wheels have no diamond (three-component control); sliders for grade values do.
**Not verified:** on the Pixel 8 only install and reaching the editor were done (the device was heavily shared); adding a keyframe through the diamond, the lane drag, and an export compared against the preview were NOT exercised on device. Covered by JVM and native host tests only (interpolation vectors, cropping, migration round trips, preview/export parity at frame boundaries, audio block-size independence).

## Interchange and media library (WP-I)

**Decision:** a project bundle is a zip (`bundle.json` manifest, raw `project.json`, optional card thumbnail, optional `media/`), imported by unpacking into a scratch folder under the projects folder and moving it into place with one atomic rename.
**Why:** a bundle with media can be gigabytes and an import can fail halfway; the scratch-and-rename keeps the projects folder free of half projects, and the raw `project.json` keeps fields a newer build wrote.
**Alternative:** unpack straight into the final folder and clean up on failure (a crash would leave a broken project), or keep media outside the bundle always.

**Decision:** auto-relink looks only among the assets of the other local projects and needs name (ignoring case) and size to match; it never searches the device.
**Why:** a MediaStore search needs a new storage permission; privacy and "no new access" win, and name plus size avoids picking a different file with the same name.
**Alternative:** query MediaStore (more matches, a new permission) or match by name only (wrong files).

**Decision:** the EDL is one file per track (a zip when there are several) and transitions are written as cuts; FCPXML puts the base on the spine and the rest as connected clips with the offset computed as if the parent played at normal speed.
**Why:** CMX3600 has one video channel and importers expect one track per EDL; FCPXML's connected-clip model matches the base-plus-overlays model of the app, and the notes list every approximation.
**Alternative:** one merged EDL (breaks importers), or a gap-only spine with every clip connected (works everywhere but loses the primary storyline).

**Decision:** FCPXML positions are written as a percentage of the frame height (y flipped, rotation negated) and retimes as a two-point `timeMap`; both are listed as unchecked in the sequence note.
**Why:** the exact Final Cut Pro units could not be verified without the application; the note keeps the export honest.
**Alternative:** omit transforms and retimes from FCPXML.

**Decision:** tags and notes on library files, like the library order, are saved with the project but are not part of Undo; "Remove unused" keeps any file that the timeline, an undo or redo state or the clipboard still uses.
**Why:** they are library data, not timeline edits, and undoing a deletion must never meet a file that is gone.
**Alternative:** make library edits undoable (needs the history to cover the asset list), or remove strictly by current usage (an undo could bring back a clip with no file).

**Decision:** marker colours are stored and exported but not drawn on the native ruler.
**Why:** drawing them needs a timeline snapshot version bump that other work is also changing; the dialog and the exports carry them.
**Alternative:** bump the snapshot to draw coloured markers.

**Decision:** the bundle carries only the project card picture; importing keeps it in the project folder but the app does not use it yet.
**Why:** the format reserves `thumbnails/` so a viewer without the media can show something; per-asset pictures would grow the bundle for little use.
**Alternative:** include a picture per asset and use them as fallbacks for missing media.

**Decision:** the pickers that ask where to write a bundle, an FCPXML or a single EDL use the generic type `application/octet-stream`; only the zip of several EDLs uses `application/zip`.
**Why:** seen on the Pixel 8: with a specific type the system file picker appends its own extension to the suggested name (`.uvbundle.zip`, `.fcpxml.xml`), which other tools do not recognise.
**Alternative:** keep the specific types and strip the doubled extension afterwards (not possible through the picker).

**Not verified:** nothing of this has been imported into Final Cut Pro, DaVinci Resolve or another editor; the sheet and the exports through the system picker are covered by view model tests and golden files (see PLAN.md for what was seen on the Pixel).

## Proxy media (WP-P)

**Decision:** proxies are made with the existing offline export engine (a one-clip, no-audio, H.264 movie at the source's own frame rate and length, short side 720 or 1080) instead of a separate transcoder.
**Why:** it reuses the tested decode, GLES and encoder path and the colour conversion, and guarantees the same frame count so every timeline frame maps to the same frame of the proxy.
**Alternative:** a Kotlin MediaCodec decoder-to-encoder loop with its own GL scaling (more code, a second pipeline to maintain). Costs: proxy generation is as slow as an export of that clip, and the engine's fixed 1 s keyframe interval is used rather than all-intra.

**Decision:** the proxy of an HDR source is a tone-mapped SDR Rec.709 file, read as SDR in the preview.
**Why:** an 8-bit H.264 proxy is the cheap, universal case; HDR projects only need the picture to be plausible while editing, export uses the original.
**Alternative:** a 10-bit HEVC HLG proxy (not every device encodes it, and it is heavier to decode).

**Decision:** the side index and the proxy files live in `cacheDir/proxies`, and the per-project switch, the budget and the proxy size live in local preferences, never in `project.json`.
**Why:** bundles, EDL/FCPXML and old builds see no change; the system may clear the cache, and the index self-heals (`recoverAfterKill`, `validate`).
**Alternative:** `filesDir` (survives cache clears but is never reclaimed by the system) or an optional field per asset in the project file.

**Decision:** an interrupted job restarts from the beginning after the process dies (the part file is deleted); "resumable" means the queue survives, not the half-written file.
**Why:** an MP4 muxer cannot be reopened mid-stream; proxies are a cache.
**Alternative:** fragmented MP4 segments (a much larger change in the encoder).

**Decision:** the app never makes proxies on its own: heavy media or three preview stalls raise a dismissible suggestion (the stall signal is detected from the preview's error messages).
**Why:** proxies cost disk and time, and the user asked for consent.
**Alternative:** automatic proxies above a threshold.

**Decision:** the proxy badges, the banner and the sheet read the live proxy state through a `State` in a composition local, not through the editor screen.
**Why:** a proxy's progress changes many times a second; reading it in the screen root would recompose the whole editor.

**Not verified:** see the PR description (device checks were limited to what is listed there).

## Export and preview reliability (device pass 2)

**Decision:** the decoder keeps ONE frame in flight (released to the image reader but not yet acquired by the consumer). A frame is only given up as lost when the consumer drained the reader more than 400 ms after its release, or after a hard limit of 5 s.
**Why:** the buffer queue between the codec and the AImageReader keeps only the newest frame queued since the consumer last acquired one, so releasing a second frame silently discards the first. With four in flight, frames were lost and recovered 400 ms later by a backward seek and a re-decode from the key frame. Measured on a Pixel 8 (not the reference device): export of a long-GOP 1080p30 clip 0.35x -> 1.8x real time, two layers 0.06x -> 1.3x, 4K60 H.264 preview 429-513 of 600 frames with 118-227 stalls -> 606 of 600 with none.
**Alternative:** raise the image reader queue or use acquireLatestImage (the queue still drops), or decode ahead into our own ring (more memory, same copy cost). Do not raise the in-flight limit without re-measuring on a device.

**Decision:** every reader of a media file gets its own descriptor through /proc/self/fd/N (new open file description), falling back to dup().
**Why:** dup() shares the file offset; video, audio and thumbnail extractors on different threads disturbed each other (a clip lost its tail after 177 of 300 samples, then every later frame repeated the last good one). That was the likely root of the intermittent decoder stalls and undecodable-audio errors reported earlier. Frame-exact retime check on the Pixel: 133 mismatched frames and failing audio -> 0 mismatches, all segments correct.
**Alternative:** pread-only extraction or a single reader thread (larger refactor). Files whose /proc reopen is refused (some provider descriptors) fall back to dup() and keep the old risk.

**Decision:** a reversed audio clip remembers the sample its block must reach after a seek and keeps filling instead of seeking again; the export checks audio faults on every frame.
**Why:** if the codec had nothing ready right after the seek, each following call seeked to the same place again (the gap exceeded the continue limit), flushing the codec every time, so the clip never became ready (export failed with the audio of clip N not ready after 30 s). Each stalled frame also blocked for 30 s while faults were only polled every 30 frames.
**Alternative:** a larger continue gap (hides the problem for fast codecs only).

## Text on title and sticker blocks of the timeline

**Decision:** the timeline snapshot (wire version 7) carries an optional short label per clip: a title's first line or a sticker's name, reduced to capital ASCII letters, digits, spaces and '-' (accents dropped, at most 24 characters). The native canvas draws it with its existing 3x5 pixel font, extended with A-Z and '-' (now in `timeline_view/glyphs.h`, host-tested), at the visible left edge of the block, cut to the room left.
**Why:** title, caption and sticker blocks were plain coloured rectangles, so a timeline with several of them could not be read (QA report, device pass 1).
**Alternative:** render the label with Android's text engine into a texture (handles every script and emoji, but needs a bitmap upload per label and a font stack on the render thread) or draw only an icon per kind (no text). Titles in other scripts (CJK, Arabic, emoji) show the generic label TEXT; revisit if that matters.

**Not verified:** the drawing itself was not seen on a device (the Pixel's screen was locked); the parser, the glyph shapes and the label rules are covered by host and JVM tests.


## Motion tracking (WP-V1)

**Decision:** the tracker is the stabiliser's `BoxTracker` (pyramidal Lucas-Kanade on Shi-Tomasi corners inside the box, similarity fit per frame, no models), analysed in one decode pass at 320 px: frames before the seed are buffered (8-bit, at most 900) and tracked backward when the seed frame arrives, then the decoder goes on forward.
**Why:** a second tracker would duplicate tested code; one pass means one hardware decoder for a short time and no seeking backwards; 320 px is plenty for a box and keeps the buffer near 50 MB.
**Alternative:** two decode passes (seek back for the backward run: slower and a second decoder session), or storing float frames (about four times the memory).

**Decision:** a frame is *lost* when the box tracker's confidence is below 0.15; the box then holds its last believable position and the path keeps going, so the target can be found again.
**Why:** a blank or blurred stretch must not end the track, and the user sees exactly which frames were guesses (red on the preview, counted in the status).
**Alternative:** stop at the first lost frame (the rest of the clip would be untracked) or interpolate across the gap (invents motion).

**Decision:** a target is stored in the project (`Timeline.motionTracks`: clip, name, seed) while the analysed path lives only in a cache file named from the media and the seed.
**Why:** the project stays small and portable and a cleared cache costs one re-analysis; the seed alone reproduces the path.
**Alternative:** saving every path sample in `project.json` (large files, stale after a relink).

**Decision:** following writes ordinary position keyframes (reduced to within 1 px by Douglas-Peucker, merged with the clip's existing key frames) instead of a live link to the track.
**Why:** preview and export need no new code path, the result stays editable with the keyframe tools, and one undo step undoes it; a straight drift is two keys.
**Alternative:** a `TrackedPose` evaluated by `RenderPlan` (stays in sync if the track is re-analysed, but needs native and export changes and cannot be hand-tweaked).

**Decision:** only the position follows the path (no scale or rotation), the attached clip is centred on the target, and the tracked clip's stabiliser correction is ignored.
**Why:** scale and rotation from a small box are noisy; centring is what "put this label on that face" means and the offset can be moved afterwards with keyframes; the stabiliser warp is a few pixels at most.
**Alternative:** following scale/rotation as options, keeping the attached clip's offset, or composing the stabiliser table into the path.

**Decision:** picking is a tap (box of 7, 12 or 20 % of the frame height by chip) or a dragged box on the preview, at the playhead's frame, which must be on the clip.
**Why:** a tap is the common case and the chips cover sizes without a second gesture; the playhead frame is what the user is looking at.
**Alternative:** a draggable box with handles over the preview (more precise, more code to keep out of the gesture layer used for moving clips).

## Silence auto cut and manual reframe (WP-V2)

**Decision:** silence detection runs on the loudness envelope already cached for waveforms (no audio is decoded again) with a plain level threshold, minimum length and padding; the cuts are applied as one base-track edit that reuses the delete rules (ripple, overlays follow). Reframe is manual: the user marks the point of interest (sliders, one mark per moment) and the app writes cover-fit pose keyframes; there is no subject detection.
**Why:** the privacy rule excludes models; peak envelopes are cheap and deterministic, and routing the cut through the existing base delete keeps every edit rule in one place. A manual point is predictable and editable afterwards as normal keyframes.
**Alternative:** spectral voice-activity detection (better in noisy rooms, more code and tuning), letting the user drag a box on the preview to pick the point (nicer, but more gesture code next to the tracker picking), and supporting retimed clips by mapping spans through the retime table (deferred: speed-changed clips are refused with a message).

## Export speed on long-GOP clips: model instead of more code (third pass)

**Decision:** no further production change to the decoder: the slowdown was already removed by keeping one frame in flight (second pass). This pass moves the constant to `decode/pending_policy.h` (`kMaxInFlightFrames`, shared by the decoder and a new host simulation) and adds `decode_sim_tests.cpp`, a deterministic simulation of the decoder worker with a sequential export consumer. It reproduces the old behaviour (138 backward seeks, 1.9 fps with 4 in flight) and guards the fix (1 seek, within 15% of the pipelined ideal), and checks that scrub and long jumps still seek once.
**Why:** the Pixel was locked, so a decoder change could not be verified; the root cause was already identified and fixed, and a regression guard that fails if someone raises the in-flight limit is the cheapest protection.
**Alternative:** allow two frames in flight with a reader that does not drop (would help the decode-bound case, 85% of ideal) or pre-arm the decoder targets of all layers before waiting for the first one (helps cuts with a new decoder). Both need a device to be measured; listed as follow-ups.

## Filter pack (WP-V5)

**Decision:** the 20 built-in looks are functions (a handful of ordered colour operations) in code, baked into 17-point `.cube` files on first use and stored in the existing LUT library; the picker shows them above imported LUTs with a computed swatch.
**Why:** original work with a clear licence and no assets to ship or download; reusing the LUT library and effect keeps preview/export parity, intensity and keyframes for free. 17 points is enough for smooth looks and small to store.
**Alternative:** shipping pre-baked `.cube` files in the APK (bigger, hard to review), a dedicated shader effect per look (more native code, no reuse of the LUT path), or 33-point cubes (smoother steep curves, 8x the data).

## Transition pack (WP-V5)

**Decision:** the new looks are a per-frame modification (pose, mask, effects) evaluated by one pure function that the preview calls directly and the exporter bakes into its existing pose keys and per-frame effect lists; no new shader or native code. Light leak is a warm exposure bloom on top of the crossfade rather than an overlay layer.
**Why:** preview/export parity falls out of sharing the function (and is tested frame by frame); the compositor already has masks, blur, exposure and pose keys, and it re-anchors per frame for animated clips, so nothing else changes. A synthetic overlay layer would have needed new layer plumbing in the plan, the preview and the exporter.
**Alternative:** a native two-input transition shader (true wipes with arbitrary shapes, real light leak textures, better glitch with channel split) with the picture of both clips composited in one pass, at the cost of new GLES code in preview and export and a texture for the outgoing frame; or a procedural overlay layer for the light leak.

## Release preparation (WP-R)

**Versioning:** `gradle/version.properties` is the only place a version is written; `versionCode = major*10000 + minor*100 + patch` (0.1.0 -> 100). **Why:** no counter file to forget, a tag `v<versionName>` is checked against it by the release workflow, and codes stay monotonic for normal semver bumps. **Alternative:** a git-describe or CI-run-number code (needs full history and breaks local builds), or an explicit `versionCode` line (two numbers to keep in sync).

**Signing:** read from `keystore.properties` (ignored) or `UVEDITOR_*` variables, and only used when all four values are present. **Why:** a partial setup must not sign with the wrong key or fail oddly; CI stays secret-free and produces unsigned files. **Alternative:** a Gradle `signingConfig` that fails when values are missing (forces everyone, including CI, to have a key).

**R8 on by default for release, with explicit keep rules:** the whole `engine` package (native methods, exceptions thrown from C++), any class with native methods, the callback method names C++ looks up (`onProgress`, `onFinished`, `onError`, `onWaveformReady`, `onThumbnailError`), our `@Serializable` classes/serializers and enum names. The APK went from 30.1 MB (unminified, resources unshrunk) to 6.5 MB. **Why:** a store build should be small and the JNI names are the only reflection-like surface. **Alternative:** keep minification off (simplest, but 4.6x larger).

**Local-only crash report:** an uncaught-exception handler writes `files/crash/last-crash.txt` (64 KB cap, atomic write), after which the system handler runs as before. The report has exception types, messages cut to 200 characters with content URIs, absolute paths and media file names replaced, stack frames, app version, phone model and Android version. About can show, copy, share (system share sheet, user-initiated) or delete it. **Why:** gives users a way to report a crash without any crash-reporting service. **Alternative:** nothing stored (users would need `adb logcat`), or a third-party service (rejected by the privacy rule).

**About screen and legal assets:** the licence text, `THIRD_PARTY_NOTICES.md` and `docs/PRIVACY.md` are copied into the APK's assets at build time by a Gradle task registered through the AGP variant API, so the app shows exactly what the repository says. **Alternative:** hard-code the text in Kotlin (two copies to keep in sync).

**"Clear caches" does not touch proxies:** proxy copies have their own switch and clear action because a running job owns that folder; About shows their size and points to the proxy sheet. **Alternative:** delete everything under the cache folder (could break a job in flight).

**First-run tips:** three dismissible cards in a dialog over the hub, shown once (preferences flag), reopenable from About. **Alternative:** a coach-mark overlay on the real controls (nicer, but it needs a stable layout, which the resizable layout package keeps changing).

**Two latent defects found by building the release variant, fixed here:** `thumb_atlas.h` kept a field only read by a debug-guarded log, which `-Werror` rejects in an optimised build; `Subtitles.kt` contained a literal byte-order mark in the source (lint error), now written as the escape sequence `\uFEFF`.

## Multicam (WP-M)

**Decision:** a multicam clip is realised as ordinary clips on the tracks, and the group (`Timeline.multicams`) is only
the recipe: angles with offsets, the audio angle, the lane ids and the cuts. Switching angles rewrites the realised
clips in place; "Flatten" just forgets the group; export needs no special path because it only ever sees plain clips.
**Why:** preview, export, the magnetic base, group edits, effects and speed all already work on plain clips, so a new
clip type would have touched every renderer and operation; this keeps the change inside `domain/multicam/`.
**Alternative:** a dedicated clip type resolved inside `RenderPlan` (smaller project files and angle media kept
visible in the model, at the cost of new code in the planner, the mixer snapshot, export and every clip operation).

**Decision:** edits on single realised clips (split, trim, retime, deleting one) make the group forget itself
(`MulticamOps.settle` from `Timeline.pruned()`), while moves of the whole block make it follow; styling keeps it.
**Why:** a group that no longer matches its clips would lie to the cut buttons.
**Alternative:** refuse those edits, or rebuild the group from the clips.

**Decision:** sync correlates the loudness envelopes of the waveform cache (the same data beat detection uses) in
Kotlin, with an FFT, instead of a new native decoder at 8 kHz PCM.
**Why:** no audio is decoded twice, it is host-testable, and a 100 Hz envelope resolves offsets finer than one frame at
60 fps; speech, claps and music all give strong loudness shapes.
**Alternative:** native 8 kHz PCM cross-correlation (finer than 10 ms, needed only for sample-accurate audio alignment
between recorders, which the picture cuts do not require).

**Decision:** a doubtful sync (confidence below 0.15: correlation minus the best rival peak) is shown as "unsure" and
not applied; the offset stays 0 until the user nudges it.
**Why:** a wrong offset looks plausible and wastes the whole edit; silence or unrelated audio must not pretend to match.
**Alternative:** apply the best guess anyway and show the confidence.

**Decision:** the audio of a multicam is one clip from one angle on a free audio lane, and the picture clips are
silenced with -96 dB (the gain floor) rather than muting a track. Without a free audio lane the pictures keep their own
sound and a message says so.
**Why:** cutting to another camera must not make the sound jump; muting a whole video track would silence the user's
other clips on the base.
**Alternative:** a per-clip mute flag (not in the model today).

**Decision:** a multicam clip is created on the base only, at the nearest cut to the playhead, and the other angles are
shown in the viewer as badges (live / proxy / still) from `MulticamPlanner` instead of moving pictures.
**Why:** one decoder at full quality is all the preview pipeline guarantees; moving thumbnails of five more angles need a
low-rate decode path that was not built in this pass.
**Alternative:** periodic stills from the thumbnail decoder or proxy decoders for each angle (the planner already
decides which angles would use which).

**Decision:** smooth slow motion uses classical block-matching optical flow at reduced resolution (160 px wide flow in the
preview, 320 in the export) with sub-pixel refinement, a bidirectional warp and a blend fallback where the match is doubtful.
**Why:** no AI or network is allowed; it runs in the compositor pass, needs no extra storage and degrades into a plain blend
instead of tearing. Measured on a Pixel 8: PSNR 43.9 against 35.9 dB for frame repetition at 0.25x.
**Alternative:** dense variational flow (Horn-Schunck or TV-L1), more accurate on smooth motion but much costlier per frame.

**Decision:** the preview interpolates at a quality that drops to plain blending when the frame budget is missed; the export
always runs full quality. The fractional position travels as a signed mix (negative towards the previous frame, for reversed clips).
**Why:** playback must not stutter, and the final file must not depend on how fast the phone was while editing.
**Alternative:** a fixed preview quality.

**Decision:** speed up to 100x relies on the existing decoder seek policy (forward gaps over 120 frames seek instead of
decoding through) rather than a new skip mode.
**Why:** it already decodes only the frames that are needed at such speeds, and one policy is easier to keep correct.
**Alternative:** an explicit skip-decode mode per clip.

**Decision:** Denoise and Deflicker are ordinary effects (codes 16 and 17) that run first in the chain and read the previous
and next frame (from the cache in the preview, fetched in the export); Denoise mixes the spatially smoothed previous frame.
**Why:** this fits the keyframable `Clip.params` model, and smoothing the previous frame first beat a raw temporal average.
Known cost: chroma PSNR falls (39 to 32 dB on the noisy test clip) while luma rises 24.8 to 29.7 dB.
**Alternative:** separate repair pass with its own UI, or per-channel strengths.

## Project templates (WP-V5)

**Decision:** a template is the project structure with media replaced by placeholders (clips with the asset id `slot:<id>`); filling it reuses the base-track rules (trim and ripple when a file is short, delete when an optional slot is empty, `Reframe` for the centre crop, transition clamping with `maxTransitionFrames`). The file is the `project.json` structure plus a placeholder list, never media. The entry point is the hub menu rather than a third mode of the New project sheet.
**Why:** every edit rule stays in one place and is already tested; sharing structure only keeps `.uvtemplate` files tiny and private. A separate wizard needs the media importer and creates the project in one step, which does not fit the sheet's format selectors.
**Alternative:** a Template start mode inside the New project sheet (one flow, but entangled with the selectors and the match-first-clip probing), or placeholder clips with retained stand-in media references (they would break when the media is missing).

## Voice effects (WP-V3)

**Decision:** the effects run in the decode worker, per clip, after the noise suppressor, as an input-aligned streaming processor (`audio/voice_fx.h`), not in the realtime mixer.
**Why:** it reuses the proven pattern of the denoiser (alignment by priming, flush, source identity by hash), keeps the audio thread free of FFTs, makes realtime, offline and export identical by construction, and needs no look-ahead reads in the mixer to hide the 32 ms latency of the vocoder.
**Alternative:** processing in the mixer would allow live slider changes without re-decoding, but needs the clip buffer to be read ahead by the latency, per-block FFT work on the audio thread and chunk-exact hop scheduling there.

**Decision:** the sliders are not keyframable and a change restarts the clip's decode (the slider applies on release). (Superseded for keyframing, see "Voice effect keyframes" below; a changed key still restarts the decode.)
**Why:** a changed effect is a new source (like a changed noise profile); live keyframing would need parameter interpolation inside the vocoder.
**Alternative:** apply the effect in the mixer (see above) and reuse `Clip.params` lanes.

**Decision:** pitch shifting by moving spectral peaks rigidly with identity phase locking, formants by a cepstral envelope split (lifter 1.6 ms), instead of time-stretch plus resampling.
**Why:** it is streaming with a fixed hop and no resampler, keeps latency and CPU bounded (about 16x real time for the whole chain on the dev machine), and keeps the level of sinusoids (the simple bin-scatter variant lost up to 8 dB).
**Alternative:** WSOLA or phase-vocoder time stretch plus a resampler: better on extreme shifts, but variable output length per frame and more state.

**Decision:** Whisper uses the cepstral envelope with random phases (a noise vocoder), not the harmonic spectrum with random phases.
**Why:** the harmonic version kept a clearly periodic structure (autocorrelation 0.71 at the pitch lag); the envelope version has none.
**Alternative:** a separate noise generator filtered by an LPC envelope.

**Decision:** presets define 1 to 3 sliders and resolve to a flat set of native settings in Kotlin; the native side knows no presets. Echo and reverb tails are fed progressively after the media ends, up to the clip's end or 8 s.
**Why:** presets can change without an engine release or snapshot change; the tail must not be appended at once because the clip buffer is a 1.5 s window.
**Alternative:** presets in C++ (smaller JSON, more engine coupling); a hard cut at the end of the media.

**Decision:** no text to speech, no vocal isolation and no speaker-aware captions (privacy rule); Android's system TextToSpeech can use network voices.

## Audio callback crash (SIGSEGV in `adoptStateFrom`) and native-exit diagnostics

**Evidence:** Pixel 8 crash buffer, `com.ultimatevideo.uveditor.mt2`, 2026-10-04 11:29:05, process uptime 6019 s: `SIGSEGV, SEGV_MAPERR, fault addr 0x440`, thread `AAudio_4`, symbolised frames `PreparedSnapshot::adoptStateFrom(PreparedSnapshot const&) const+448` <- `AudioCore::renderBlock(float*, int)+108` <- `AudioCore::render` <- `AudioEngine::onAudioReady` <- Oboe. The previous AAudio stream (`s#3`) had been closed 31 s earlier (the idle stop after a pause), the new stream (`s#4`) was opened and the crash came in its first callback, 1 ms after `requestStart`.
**Root cause:** the audio thread's `AudioCore::current_` is a raw pointer to the snapshot it last rendered with; the first block of a new stream adopts DSP state from it (`np->adoptStateFrom(*current_)`). `setSnapshotLocked` (edits made while no stream runs) and `streamStopped` erased every snapshot but the newest from `alive_`, which freed the one `current_` still pointed at. The next stream's first callback then read freed memory (`o.source->...`).
**Decision:** one pruning rule, `pruneAliveLocked()`, used by both: always keep the snapshot the audio thread acknowledged (`ackGeneration_`) and the newest; with no stream running drop the ones in between, with a running stream keep everything newer than the acknowledgement (it may be about to adopt it). `adoptStateFrom` also skips a null `source`. `AudioCore::audioThreadSnapshotIsAlive()` states the invariant for tests.
**Why:** it keeps the zero-lock, zero-allocation audio thread and the existing hand-off protocol, and bounds memory (at most two snapshots survive an idle period).
**Alternative:** `shared_ptr` owned by the audio thread (atomic refcounts and a free on the audio thread), or resetting `current_` in `streamStopped` and re-arming `pending_` (loses the filter continuity across a restart and needs the stream joined first).
**Regression coverage:** host tests of the exact scenario, a churn test and a two-thread test that follows the real protocol (a callback thread only between open and close, an editor thread publishing snapshots), the invariant checked after every step. With the old pruning the new tests fail 500+ checks and the process dumps core under `MALLOC_PERTURB_`; with the fix they pass. `scripts/run-sanitizer-tests.sh` runs the audio core under ASan/UBSan and TSan; the laptop has no sanitizer runtimes, so CI runs it (`UV_REQUIRE_SANITIZERS=1`).

**Decision:** two more use-after-free windows found by the audit are closed. (1) `AudioPlaybackEngine.close()` could free the native engine while a loudness or noise measurement was decoding on `Dispatchers.IO`: measurements now hold the read side of a lock, `close()` flags itself, cancels the analysis and takes the write side before `nativeDestroy`. (2) `FileStabiliser.cancel()` and `FileMotionTracker.cancel()` read `runningHandle` under the lock but called `native.cancel(handle)` after releasing it, so the analysis could destroy the service in between: the call now happens under the lock the analysis also takes before it clears the handle and destroys it. Regression test for (2) with a strict fake that counts calls on destroyed handles.
**Why:** same failure class (native object freed while another thread still holds its handle), cheap to remove.
**Alternative:** reference-counted native handles.

**Decision:** native crashes and ANRs are now visible in "About > Last crash report". On start, `ProcessExitRecorder` reads `ActivityManager.getHistoricalProcessExitReasons` (API 30+) off the main thread; for a native crash, ANR, kill by signal or initialisation failure newer than the last one handled it writes a short summary (reason, time, importance, system note, and the symbols found in the tombstone's trace via a printable-string scan, scrubbed of paths, URIs and media names) above any earlier report. Local only, nothing is sent.
**Why:** the Java uncaught-exception handler never sees a SIGSEGV, so the About screen was empty after exactly the crash that matters.
**Alternative:** installing a native signal handler (async-signal-safe constraints and a second crash path), or parsing the tombstone protobuf properly (a scan is enough to name the frames).

## HSL qualifier as an effect with its own matte (leftovers)

**Decision:** secondary colour correction is a new effect type `QUALIFIER` (wire code 18, 14 values) that builds its own matte from the pixel's HSL (hue centre and half width on the wheel with wrap-around, saturation range, Rec.709 luma range, each with a softness; optional invert and a "show matte" grey view) and corrects hue, saturation and lightness only where the matte is open (`mix(in, corrected, matte)`). It reuses the grade's `grade` wire vector and `uG` uniform array (more than the 6 values an ordinary effect carries), so only the type range, one uniform upload and a shader branch were added; CPU reference in `render/qualifier_math.h` mirrors the GLSL and is pinned by host tests (hue wrap, softness monotonic, neutral correction identity, hue shift red to green, range clamp, wire parsing). It sits in the effect chain, so the clip's mask and blend modes still apply after it.
**Why:** the spec asked for a qualifier that limits a second grade; a self-contained effect needs no change to the mask pipeline and works with keyframes/params tracks and looks the same in preview and export.
**Alternative:** a mask source feeding the existing mask machinery and any effect (more general, but the pipeline has a geometric mask only, and every effect would need a matte input), or extending the colour grade with qualifier fields (one more tab in an already big effect).
**Not done / next:** a dedicated editor section and the eyedropper (tap the preview to key on a colour): `Qualifier.keyedOn(values, r, g, b)` already turns a picked colour into key values (tested), but sampling the pixel needs a frame sampler (MediaMetadataRetriever / ImageDecoder behind an interface, the tap mapped with `TrackMath.fromCanvas` like the motion tracking pick). Until then the qualifier is edited with the generic effect sliders. Not seen on a device: the shader is compiled and run only on a GPU.

## Lane headers and reordering lanes by dragging (leftovers)

**Decision:** the native timeline draws a 22dp header column over the left edge of every lane (name V3/V2/V1/A1/T1 computed in `lane_header.h` with the same rule as the editor's lane names; red M and yellow S on muted and soloed audio lanes). The header takes the touch before clips (hit kind `LaneHeader`); a tap selects the lane, a long press picks it up. The moves are read from the touch events because the platform gesture detector stops reporting scrolls after a long press. The native side only draws (tinted lane plus a bar at the landing edge); the target is decided in `LaneOps.laneDropTarget` (nearest lane of the same kind, never the base) and applied on release as one `EditCommand.MoveTrackTo`, so the buttons and the drag share the lane rules. Mute/solo flags travel in the high bits of each track's type word (type in the low byte), so there is no snapshot version bump and old snapshots read as no flags.
**Why:** reordering overlay lanes only by buttons was a LumaFusion gap; the header column also gives the lanes names and shows mute/solo state.
**Alternative:** shifting the whole timeline right by the header width (cleaner, but changes every x coordinate and hit test), or a Compose overlay for the headers (breaks the rule that the canvas is drawn natively, and recomposes while scrolling vertically).
**Trade-off:** the header covers the first 22dp of the lanes; the first frames of a clip scrolled to the very left can only be grabbed after scrolling the timeline a little. Mute/solo are shown, not toggled, in the header (the Mixer sheet toggles them). Not seen on a device: host and JVM tests and the NDK build only.

## Marker colours and note indicator on the native ruler (leftovers)

**Decision:** the colour code (0 none, 1..6 in `MarkerColor` order) and a has-note bit travel in the marker's last wire word, which snapshot version 5 reserved as zero, so there is no snapshot version bump and old snapshots read as unstyled. The renderer colours the ruler flag and the lane line (alpha kept) and draws a small light square under the flag for a note; beats are unchanged. Colour table and parsing live in `timeline_view/marker_style.h` (host-tested); `SnapshotMarker` validates the code range.
**Why:** colours and notes existed in the model, the EDL/FCPXML export and the dialog but were invisible on the ruler.
**Alternative:** a new snapshot version with a dedicated field (cleaner but forces a format bump for four bits), or drawing the note text on the ruler (needs a glyph atlas for arbitrary text).

## Launcher icon (leftovers)

**Decision:** an original adaptive icon: three timeline clips (two in periwinkle, one in sky blue) and an amber playhead with a downward triangular head, on a deep blue-violet vertical gradient; layers `ic_launcher_background`, `ic_launcher_foreground`, `ic_launcher_monochrome` under `mipmap-anydpi` (also used as the round icon); manifest points to `@mipmap/ic_launcher` and `ic_launcher_round`. All foreground points are within about 30 units of the canvas centre, inside the 33-unit safe-zone radius, checked by `IconGeometryTest` from the path data (paths use only absolute M/L/Q/Z for that reason).
**Why:** the placeholder vector was a play triangle; the release checklist required a designed icon, original and not resembling other editors.
**Alternative:** a play triangle on the timeline (closer to generic video apps), or a raster icon set (larger APK, needs a design tool). Not seen rendered on a device; only the geometry is verified.

## FFmpeg software-decoding fallback (optional, off by default)

- **Route:** MediaCodec first; FFmpeg only if MediaCodec fails to open the stream for a fixable reason *and* this build contains it. A pure function (`decode/decoder_selection.h`) decides, so it is host-tested. Alternative: always prefer software for formats MediaCodec "might" mishandle (slower and worse battery for the common case).
- **Off by default, enabled with `-Puveditor.ffmpeg=<dir>`:** default builds and CI never need FFmpeg (a stub is linked); the engine grows by 7.6 MB when it is on. Alternative: always on (+7.6 MB for everyone for a rare need).
- **Built in CI, never locally and never committed:** a pinned version and SHA-256, an LGPL-only configuration that the script verifies, the libraries published as an artifact. Alternative: a prebuilt Maven artifact (about 20 MB, full codec set, no headers).
- **Reading with `pread()` on a duplicated descriptor** through a custom AVIO context, no FFmpeg protocols: the app has no network and the demuxer never moves a shared file offset. Alternative: the `fd:` protocol (shares the offset between readers).
- **Frame indices by integer maths:** pts to frame is round-half-up with 128-bit intermediates, the seek target is the floor; the frame rate is snapped to broadcast rationals. Alternative: floats (drift over long clips).
- **A seek that lands late retries further back** (4, 16, 64... frames, at most 8 times), and a seek that ends before any picture does the same. Open-GOP MPEG-2 and some single-key-frame files need it. Alternative: trust the first key frame the demuxer finds (frames silently missing).
- **Estimated durations (MPEG-PS/TS, no index) get 100 ms of slack**, and the decoder raises its last frame when a picture arrives beyond it. Alternative: trust the estimate (hides the last frames).
- **RGBA8 frames** through the existing `AHardwareBuffer` path, so cache, colour shader, effects and exporter are untouched. Cost: 10-bit sources lose precision; alternative: a 10-bit RGB path (not done).
- **Audio fallback** only when the platform fails to open the audio, with a proper downmix through libswresample (the MediaCodec path takes the first two channels).
- **Software decode is flagged, not hidden:** the editor says the clip is decoded on the CPU and, above a 1080p30 pixel rate, advises a proxy and reduces the look-ahead.
- **AV1 left out:** needs dav1d/libaom built separately and the platform decodes AV1 since Android 12. (Superseded: dav1d was added, see "FFmpeg fallback: dav1d" below.)

## 2026-10-04 · Final cleanup

**Flaky test root cause and fix.** `AudioToolsViewModelTest > a measurement that fails…` failed now and then with
`UncaughtExceptionsBeforeTest`, which means an earlier test left an exception on a real thread. Reproduced under CPU load
(1 failure in 15 runs, none in 40 unloaded): the proxy tests (`ProxyManagerTest`, `ProxyViewModelTest`, `ProxyWorkerTest`) shut
their executor down with `shutdownNow()` without waiting, so the worker thread was still inside `ProxyWorker.runOne` when
JUnit deleted the temporary folder; `index.flush()` then threw `FileNotFoundException`, which the worker did not catch
(it caught `ProxyException`, `InterruptedException` and `RuntimeException`, but an `IOException` is none of them), so the
exception escaped the coroutine and was reported at the start of the next `runTest`.
**Chosen:** fix both ends. Production: `ProxyWorker` now treats an `IOException` like any failure of a job (the job is marked
FAILED, the queue goes on) and its post-job housekeeping (evict, flush) can no longer end the loop; before, one full disk or
vanished folder would have stopped every later proxy for the rest of the session. Tests: the three proxy tests wait for the
executor to terminate before the folder is deleted, and two new `ProxyWorkerTest` cases fail on the old code and pass on the new
one. **Alternative:** only waiting in the tests, which would have left the worker fragile. Stability after the fix: see the pull request.

**SPECS renumbering.** Section 5 had duplicate and out-of-order numbers from parallel pull requests (two 5.21, two 5.22,
two 5.25, a 5.6b, and Proxy media sitting after section 6). They are now 5.1 to 5.31 in order, with a table of contents. Commit
and pull-request texts written before this cleanup use the old numbers; the mapping (old to new, by title) is: 5.6b 3D LUT
effect to 5.7; 5.7 Titles and transitions to 5.8; 5.8 Undo/redo to 5.9; 5.9 Export to 5.10; 5.10 Captions to 5.11; 5.11 Keyframes,
canvas formats to 5.12; 5.12 Effects to 5.13; 5.13 Retiming to 5.14; 5.14 Lane layout to 5.15; 5.15 Animated captions to 5.16;
5.16 Still clips to 5.17; 5.17 Markers and beats to 5.18; 5.18 Colour grade to 5.20; 5.20 Parameter keyframes to 5.23; the second
5.21 Interchange to 5.24; 5.23 Auto cut to 5.25; the first 5.25 Multilayer titles to 5.26; 5.24 Filter pack to 5.27; the second
5.25 Transition pack to 5.28; 5.26 Multicam to 5.29; 5.27 Project templates to 5.30; the stray 5.22 Proxy media to 5.31.
5.19 (multiselection), 5.21 (stabiliser) and 5.22 (motion tracking) keep their numbers.

**PLAN convention.** A checked box now means "implemented and covered by the automated tests that pass in CI"; what has been seen on
a phone is tracked separately in the *Verification debt* table so the owner can walk through it on the reference phone.
**Alternative:** leave boxes unticked until seen on a device, which hid how much was actually built.

**Dead code.** Removed the unused `Stills` constant object. Kept on purpose: `CaptionPlanner` and the `Transcript` types (the `.srt`
and `.vtt` importer builds its captions with them), `previewTargetAt` (used by tests as a convenience over `previewLayersAt`), and the
null-object test doubles `NoLayoutStore` and `InMemoryProxyPrefs`. All helper scripts are referenced from the docs, tests or CI.

**Update (qualifier editor and eyedropper):** the qualifier now has its own editor (`ui/editor/QualifierControls.kt`): Pick colour, Show matte and Invert chips, a Hue group with a wheel strip marking the selected hues (wrapping around red) and sliders for centre, width and softness, From/To ranges for saturation and luma (`Qualifier.withBound` keeps the minimum below the maximum) with softness, and the correction group with a reset. The eyedropper arms on the effect (`QualifierIntent.Arm`), the preview shows the existing `TrackTargetLayer` for the tap, and the view model maps the tap to the clip's picture with `TrackMath.fromCanvas` (so it honours the clip's position, scale and rotation), reads the colour at the playhead's source time through a `FrameSampler` injected like the stabiliser (`engine/sample/FrameSampler.kt`: `MediaMetadataRetriever.getFrameAtTime` for video, `ImageDecoder` for photos, 5x5 patch mean so one noisy pixel does not set the key), and applies `Qualifier.keyedOn` as one `SetEffectValues` undo step. A tap beside the picture keeps the eyedropper armed; every other failure ends it with a message.
**Limits:** the colour is read from the picture as the platform shows it, before the clip's effects, stabiliser and colour override: exact for SDR clips in SDR projects, approximate for HLG sources (the matte is built on the working-space signal). A drag instead of a tap uses the middle of the gesture. Not seen on a device: the platform sampler and the Compose editor were compiled but never run; the logic, the patch maths and the key mapping are covered by JVM tests with a fake sampler.

## Animated GIF clips (leftovers)

**Decision:** an animated GIF is an image asset with `animationDelaysMs` (optional JSON, normalised delays; null for photos and old projects). A clip of it loops the animation from the clip's first frame; `AnimationTiming.frameIndexAt` maps a project frame to an animation frame with exact integer microsecond maths (the frame whose interval contains the time; edges belong to the later frame), delays of 10 ms or less count as 100 ms (browser rule). `StillRef` gained `frame`, so the preview's existing picture cache and upload path key each animation frame separately, and the export plan splits the clip into one spec per stretch of the same frame (like animated captions, merging the looped repeats into one rasterised picture), so preview and export pick the same frame at every project frame. GIF frames are decoded by our own pure-Kotlin decoder (`engine/still/Gif.kt`: colour tables, transparency, interlacing, disposal 0..3, LZW) because the platform cannot be asked for "frame N"; frame 0 still goes through `ImageDecoder` like any photo. Import reads the headers only (delays), capped at 48 MB. Default clip length is one pass of the animation.
**Why:** export needs an exact frame per project frame; `AnimatedImageDrawable` only plays against the wall clock. A decoder written for the JVM can also be tested on the host with a real LZW encoder (table growth, clear codes, interlace, disposal).
**Not done:** animated WebP frames (the file's delays are read, but decoding needs a VP8/VP8L decoder: it shows the first frame), thumbnails for later frames (first frame), frame blending. Export keeps every distinct animation frame as a canvas-sized picture in memory (existing limit for stills): a GIF with hundreds of frames on a 4K canvas can run out of memory. Not seen on a device: the Android parts (`AndroidStillRasterizer.decodeAnimatedFrame`, import probe) are compiled but never run; the decoder, timing, plan and scene logic are covered by host tests.
**Alternative:** `AnimatedImageDrawable` + `Choreographer`-free stepping (not deterministic), or FFmpeg (deferred), or converting GIFs to video at import (large files, lossy).

## Animated WebP and bounded picture memory

**WebP without a VP8 decoder:** the RIFF container is parsed in Kotlin (VP8X, ANIM, ANMF, ALPH/VP8/VP8L, with limits on canvas
size and frame count), each frame is wrapped as a standalone still WebP (a VP8X header sized to the frame, alpha flag from ALPH or the
VP8L alpha bit, even-padded chunks) and decoded by the platform's `ImageDecoder` (minSdk 33 reads lossy, lossless and alpha). Frames are
composited with the file's blend and dispose bits on a transparent canvas; the ANIM background colour is only a hint and is not painted.
Why: a VP8 decoder is a large, risky amount of code for something the platform already does. Alternative: bundle libwebp (a third-party
library; the project avoids them) or decode with FFmpeg only when the optional fallback is built.
**Loop count ignored for WebP too:** as for GIF an animation always loops, because a clip's length is the user's choice. (Superseded, see "Animated loop count" below.)
**Compositor not shared with GIF:** GIF composites palette indices with restore-previous; WebP composites decoded ARGB with alpha blending.
Both implement `AnimatedPicture` and share `CanvasSnapshots`, which is the part that was worth sharing.
**Seeking:** a canvas snapshot every N frames (N grows with the canvas so snapshots total at most 32 MB, at least 8 frames apart), so a
seek back replays fewer than N frames instead of everything from frame 0. Alternative: keep every frame (the old memory problem).
**Stills at native size:** the texture keeps the picture's size (reduced only if larger than its fit) and the upload carries a display
size, instead of one canvas-size bitmap per frame. A 480x270 GIF frame on a 4K canvas is 0.5 MB instead of 33 MB. The compositor draws
and runs effects at the display size, so what is drawn does not change; the GPU does the upscale with linear filtering (the old path
upscaled on the CPU with a bilinear filter too). Not pixel-compared on a device: the GL path cannot run on the host.
**One budget, 128 MB (`PictureBudget`):** the preview's key cache accounts real texture sizes after decoding (an initial guess is capped
at 4 MB); the exporter no longer uploads every still before the first frame: the engine asks Kotlin for a picture the first time a frame
draws it and a native LRU (`PictureResidency`) releases the oldest beyond the budget, never the pictures the current frame draws, so the
budget can be exceeded only by what one frame needs. Titles are still uploaded up front (few, small). A loop over an animation larger
than the budget re-decodes the frames it evicted (forward play costs one frame decode per frame). Alternative: lower the quality of
frames when over budget; rejected, it would change how the picture looks.
**Early check:** before an export starts, the first frame of each distinct still source is rasterised once, so a missing or unsupported
file fails with a clear message instead of halfway through.
**JSON:** no new fields; the stored clip and asset formats are unchanged.


## Bundles carry LUTs and fonts (self-contained project for another phone)

**What travels:** only the two kinds of app-wide resource a project refers to: imported 3D LUTs (key in the LUT effect) and imported
fonts (id in a text layer), under `resources/` with a manifest list (kind, key, name, size, SHA-256). Looks, title presets and templates
are copied into the project by value when applied and stickers/emoji are built in, so nothing else is referenced globally.
Alternative: also ship the whole libraries (privacy and size: it would hand over resources the project does not use).
**Defaults:** LUTs on (small, the user's own grading), fonts **off** with a licence note next to the switch, media off. Alternative:
fonts on by default (convenient, but font licences often forbid redistributing the file).
**No format bump:** `formatVersion` stays 1 and `resources` is an optional manifest key, because the old reader decodes with
`ignoreUnknownKeys` and `extract` skips entries it does not know, so older builds keep opening new bundles (they just do not install
the resources). A bump to 2 would make every older build refuse them. Alternative: bump and keep a legacy bundle for old readers.
**Names without bytes:** a resource the project uses that stays out is still listed (no `entry`), so the importer can say "font Private
Font" instead of an opaque id. Alternative: list nothing (the report could only show a hash).
**Re-keying by content:** LUT keys are a 24-bit CRC, so a clash with a different LUT is possible. `LutStore.install` compares the stored
text, takes the next free key and the importer rewrites the project's references; importing again finds the LUT it placed (probing the
same chain), so no duplicates. A re-keyed LUT no longer has `keyOf(content)` as its key, so the same file imported by hand later is still
found by content, not by key. Alternative: refuse the LUT (the project would render without its look). Font ids are 64-bit hashes: a clash
with different bytes is refused, never overwritten, and an id that does not match the bytes is refused before anything is stored.
**One bad resource never stops the import:** each is checked (checksum, parser, size) and reported; the project still opens and shows what
is missing. Resources are installed before the project folder is moved into place; if that move fails they stay in the libraries
(harmless: they are content-addressed and deduplicated). Alternative: roll them back (more code for an unlikely failure).
**Limits:** 32 MB per resource (the largest `.cube` is about 9 MB, the font parser refuses more than 25 MB), 256 resources, 512 MB in total.
**Dialog:** one entry in the hub card menu ("Export bundle for another phone…") replaces the two old entries; the editor library menu keeps
its two bundle entries, which open the same dialog with the media switch preset. Alternative: keep separate menu items per combination
(too many combinations). **Not covered:** parameter keyframes of a LUT effect's key (`fx.<id>.0`) are not rewritten when a LUT is re-keyed;
the LUT picker never keyframes it.

## Overlay inspector hides a bottom media tray (QA defect O1)
**Chosen:** while the inspector is open as an overlay (the phone default), a tray docked at the bottom is not drawn; it comes back when the
inspector closes, with its tab, filter and search kept (`bottomTrayShown`). **Why:** the expanded tray is measured before the preview/timeline
block, so on a phone it left the inspector, which covers the timeline pane, only three controls of height (seen on the Pixel 8). **Alternative:**
cap the tray height or give the timeline a minimum height (more layout rules; the tray and the inspector are rarely wanted together on a phone).
Side-docked trays and a side-docked inspector are unchanged.

## Colour wheels and curves drag only from the handle (QA defect O2)
**Chosen:** a drag moves a wheel puck only when it starts within 0.3 of the wheel radius of the puck, and moves a curve point only when it starts
within the existing grab radius of that point; a drag that starts anywhere else is not consumed, so the colour panel scrolls under the finger.
Taps still place a puck, add a curve point or (long press) remove one, and a double tap still centres a wheel. **Why:** the old drags consumed every
swipe that began on a wheel or plot, which are most of the panel's width, so scrolling needed a narrow margin. **Alternative:** a dead zone on the
panel edge or a long-press-to-drag (two gestures to learn, and long press already removes a curve point). Implemented in `detectGrabbedDrag`.

## Android 12 (minSdk 31) and the Huawei decoder ladder
**Chosen:** minSdk is 31 so the app installs on Android 12 devices such as the Huawei MatePad MRO-W09 (Maleoon 910, `hvgr` driver). Everything the app
needs from APIs above 31 was already optional or replaceable (`InputStream.readNBytes`, API 33, became `readAtMost`). Opening a video decoder
walks a ladder (`decode/decoder_ladder.h`): default decoder with a PRIVATE reader of 8, 6, 4, 3 images, a YUV_420_888 reader, then the platform
software decoder (`c2.android.avc.decoder` / `c2.android.hevc.decoder`). The first rung whose codec starts wins; each failed rung is logged with its
status and the winner shows in the `decode/s` log line. If every rung fails the error says the device could not start a decoder for that size.
**Why:** the Huawei hisi AVC decoder reserves extra output buffers tied to the reader's `maxImages` and `AMediaCodec_start` returned -10000 with 8
images, so the preview was black. Six images start the hardware decoder and play 4K60 with no stalls. Larger counts (24) are worse: the decoder's
minimum undequeued buffers grows with them. **Alternative:** always use the software decoder on Huawei (works, but 4K60 would not play in real time)
or a device allow-list (needs hardware to test each model; the ladder needs none).

## Quick markers, LumaFusion style
**Chosen:** one tap on the marker button drops a marker at the playhead (one `AddMarker`, nothing else runs) and shows a
state-based "Marker added · Edit" chip for 3 s; a marker within 2 frames of the playhead opens its popup instead of
adding a second one (it used to remove it, which made a double tap undo itself). The beat tools, previous / next marker
and marker snapping moved behind a long press of the same button. **Why:** the old flow was flag → dropdown → "Add or
remove a marker" (a menu animation and a second tap) and the button was a tooltip-wrapped `ToolButton`; the user found it
slow. **Alternative:** keep the menu and add a second button (more toolbar width on a phone). The marker button is a
`combinedClickable`, not a `ToolButton`, because the Material tooltip also reacts to a long press.
**Hint, not a snackbar:** the chip is state in `EditorState` (`markerHint`, cleared by a 3 s job), so it is drawn in the
frame of the tap and can never queue up behind older messages. **Alternative:** the existing message snackbar (queued, and
its Undo action was not wired to this step).
**Popup:** a compact card over the top of the timeline, under the ruler, not at the marker's x. **Why:** Kotlin does not
know the canvas viewport (scroll and zoom live in native code), so it cannot place a popup at the marker without a new
round trip; the card is under the ruler so the marker stays visible. **Alternative:** a native-computed anchor (a bigger
JNI surface and a moving popup while scrolling). **One undo step:** the draft shows live through `dragPreview` and
commits one `EditMarker` on close, on stepping to another marker, or on a tap elsewhere.
**Drag:** a drag that starts on a marker moves it (integer frames, snapping to clip edges, the playhead and other
markers; a taken frame keeps the last valid spot). Releasing is one `MoveMarker`. Long press does not start it: a plain
drag already does, and the long press belongs to nothing on the ruler. **Navigation:** previous / next arrows in the
popup and "Previous marker" / "Next marker" in the long-press menu; the edit-point buttons also include markers when
"Snap to markers" is on (it is on by default).
**Hit target and labels stay native:** `HitKind.Marker` and the 40 dp target are in `hit_test.cpp` / `layout.h` (the viewport is
native); the marker name is a snapshot label under `-2 - index` drawn with the existing ASCII font, so names lose accents
and symbols on the canvas only (the stored name is intact). No snapshot version bump. **Name:** at most 40 characters,
one line; the note keeps 200 and several lines. **Exports:** the EDL has no marker events; FCPXML markers use the name,
then the note, then "Marker" / "Beat". **Measured:** the add-marker step in a JVM test is about 0.2 ms median (see the PR).


## HDR export is offered only when the encoder really configures (found on the Huawei MatePad)
`HdrExportSupport` trusted `MediaCodecInfo` profile lists. The MatePad's `OMX.hisi.video.encoder.hevc` lists HEVC Main10 but
`configureCodec` fails (-38) at every size, so an HLG project showed "HDR (HLG, 10-bit HEVC)" and every export ended in "the
encoder rejected the settings". The probe now also creates the encoder and calls `configure(..., CONFIGURE_FLAG_ENCODE)` once
(no start), and the HDR option is hidden when that throws; the project then exports as SDR with HLG clips tone-mapped, as on any
device without Main10. The failure text also gained its missing full stop.

## Tray tile: the click sits inside the drag source (found on the Huawei MatePad)
A tap on a media tray tile added nothing; only a long-press drag did (adb `input tap`, a short swipe and a real touch all
failed, so it was not adb timing). `dragAndDropSource` consumes the release of a short press, and the `clickable` was
declared before it, so the drag source (the innermost modifier, which sees pointer events first) swallowed the click.
`.clickable` now comes after `.dragAndDropSource` in `AssetTile`. `AssetTileTapTest` (instrumented, Compose UI test) taps a
tile and checks `onAdd` fires; it fails with the old order (verified on the tablet) and passes with the new one. This adds
`ui-test-junit4` and `ui-test-manifest` as test-only dependencies.

## Dark only with optional pure black; one palette for Compose and the native canvases
**Chosen:** the light and dynamic-colour schemes are gone. `Palette.kt` holds every colour as plain ARGB ints (`Dark`, `Amoled`); the
Material scheme is built from it and the same tokens go to the native timeline (`Palette.nativeColours()`, 22 values, JNI `nativeSetPalette`).
The pure-black switch lives in About, is stored in local SharedPreferences and applies live through Compose state. **Why:** the light theme was
incoherent next to a video preview, and two palettes (Kotlin and C++) would drift. **Alternative:** keep dynamic colour in dark only (colours
vary per phone, contrast cannot be tested); a DataStore (a new dependency for one boolean). **Checked:** `PaletteTest`, WCAG 4.5:1 text and 3:1
graphics over every pair in both variants (I tuned the block colours: first audio, photo, sticker and multicam blocks were 2.9-3.4:1 under white text).
**Not done:** the splash background is fixed at the dark grey (it cannot follow a runtime choice).

## Timeline text from a Kotlin-made bitmap atlas
**Chosen:** the canvas draws text from bitmaps rasterised by Kotlin with the system typeface and uploaded to a 2048x1024 RGBA atlas
(shelf packer, reset-when-full with a generation counter, 512 KB of uploads per frame, text in 2 extra draw calls per frame). Whole labels are one
bitmap (so emoji sequences and shaping work); ruler, lane names and timecode are composed from single-glyph bitmaps. **Why:** the 3x5 font was
capital ASCII only and blocky at 2800 px; no third-party font or library is allowed. **Alternatives:** per-codepoint glyph atlas (breaks emoji
sequences and needs native layout), distance-field text (needs a font file or a generator), a native callback into Kotlin from the render thread
(blocks the frame). The queue file asked for an LRU label cache; reset-when-full is simpler and safe because Kotlin re-sends what the snapshot
still needs, and a project with more than 700 distinct labels keeps the old font for the rest. Snapshot version 8 carries UTF-8 labels and a
clip kind. **Tests:** host tests for hash parity, UTF-8 validation, packer, table reset, ruler plan and labels; JVM tests for the pump (once only,
re-send after reset, latest request wins, cap) and `ClipLabels`.

## Rounded blocks by cutting corners, not by a new shader
**Chosen:** content is drawn on plain rectangles and each corner is covered by a 3-triangle fan in the colour behind the block (4 dp). **Why:** a
signed-distance shader would double the vertex size of every quad (the waveform is the largest user) and a second batch would add a draw call
per clip; this adds 12 triangles per block and no draw calls. **Cost:** no anti-aliasing on the arc (error under 0.4 px at 2.6x). Corner pieces
that straddle the clip rectangle are skipped. **Alternatives:** SDF shader, MSAA.

## Timeline performance protocol: what was and was not measured
The renderer prints its own statistics under `setprop debug.uveditor.timeline_stats 1` (CPU ms to build and submit a frame without the swap,
p50/p95/p99/max, draw calls and vertices per frame); `scripts/perf-timeline.sh` drives a 110-clip project. A baseline APK (master plus only the
statistics) was built, but **the Pixel 8 stayed locked behind a secure lock screen for the whole session, so neither baseline nor "after"
numbers exist**. The 5 % jank / p99 limit is unverified; the PR stays open for that. Structural expectation: the per-frame work grows by two text
draws, 12 triangles per block and ruler labels from the atlas, and shrinks by the old per-pixel font rectangles (each ruler or label character was up to 15 quads).

### Timeline performance: measured (Pixel 8, 240 frames, 110 clips, two interleaved passes each)
Text was already one batch with a per-frame upload budget; profiling showed uploads are small (about 0.03 ms/frame) and the cost was in building
vertices: `std::vector::insert` of initializer lists per vertex, `sin`/`cos` per rounded corner per block per frame, and three atlas lookups per ruler
glyph. **Fix:** vertex batches are pre-sized and written through a pointer, corner arc cos/sin come from a table computed once, a glyph run does one lookup per glyph.

| cpu_ms (mean of 2 passes) | p50 | p95 | p99 | max | draws | verts |
|---|---|---|---|---|---|---|
| master baseline | 5.8 | 10.6 | 11.9 | 14.6 | 1 | ~5700 |
| PR #94 before fix | 6.0-6.4 | 11.7-12.3 | 13.2-17.2 | 15-22 | 4 | ~7400 |
| PR #94 after fix | 5.3 | 9.3 | 10.8 | 17.0 | 4 | ~7450 |

p95 and p99 are below baseline. Max is a single-frame outlier in both builds. The four draws are blocks, clip text, overlays plus ruler, ruler text (order matters).
The stats line also reports `uploads` and `upload_ms` (label bitmaps placed in the atlas and the time that took).

## 2026-10-04 · The editor is always full screen (no split screen, no pop-up window)
**Context:** the native timeline and preview SurfaceViews, the decoders and the audio clock are sized for one full-screen window; EMUI could not even be asked to enter split screen from adb, so it could not be verified, and a half-height editor leaves no room for the timeline.
**Chosen:** `android:resizeableActivity="false"` on the application. Phones and the Huawei tablet (Android 12) then refuse split screen and free-form windows and show the system's "app does not support split screen" message.
**Alternatives:** keep multi-window and test it (needs resize handling in the native surfaces and the layout controller); restrict only the editor activity (the app has one activity, so no difference).
**Open:** from Android 16, apps targeting API 36 on screens of 600 dp or wider may ignore this flag (the platform ignores manifest resizability limits there until the opt-out ends at API 37). The Pixel 8 is below that width; the tablet runs Android 12, so both honour it. If a newer large-screen device splits the window anyway, the layout must cope with it: that is not handled today.

## 2026-10-04 · Fullscreen preview by a double tap: same views, moved layout
**Context:** the user wants a double tap on the preview to fill the window and another to leave, without stopping playback. The preview and timeline are native `SurfaceView`s; removing or re-parenting one destroys its surface, and the editor already had bugs where a late `surfaceDestroyed` of an old view tore down a new one.
**Chosen:** keep every view composed in the same place. Fullscreen only changes measuring: the side columns get zero width, the preview takes the whole block, the handle/controls/timeline are placed below the window instead of removed, and the plain-Compose title row, banners and bottom tray are not composed. System bars are hidden in immersive mode with transient reveal by edge swipe. The tap is read in the initial pointer pass, so the edit gestures, track picker and eyedropper stay untouched; only the second tap of a double tap has its down consumed. A small overlay (play/pause, exit) appears on a single tap and after entering, for 2.5 s. State is a pure reducer saved with `rememberSaveable`. `PreviewGestureLayer` now ignores movement under the touch slop, otherwise a jittery double tap would commit an edit.
**Alternatives:** (1) a second preview in a dialog or a separate activity: a second surface, a second attach of the engine, and the HDR window flag and audio state would have to be handed over. (2) Resizing the timeline to zero height: a zero-size `SurfaceView` may be destroyed and recreated by the platform, and the native timeline would resize to nothing. (3) `detectTapGestures` on the preview: it consumes taps and waits for the double-tap timeout before every single tap, which would delay and swallow the picker's taps. (4) Reusing the Fit button: it is the timeline zoom.
**Costs:** the timeline keeps being drawn off screen while playing in fullscreen, as before; it is the same work as the normal editor, not extra. While a track picker or eyedropper is armed, the first tap of a double tap still picks (they are one-shot); the second only toggles.

## FFmpeg fallback: dav1d (AV1)

**Decision:** libdav1d 1.5.4 (BSD-2-Clause) is built by `scripts/build-ffmpeg-android.sh` before FFmpeg (meson and ninja, the same NDK toolchain, static, PIC, release, no tools/tests, NEON assembly on), pinned by version and SHA-256 like FFmpeg, and enabled with `--enable-libdav1d --enable-decoder=libdav1d` plus the `av1` parser. Its `libdav1d.a` is copied into the artifact beside FFmpeg's libraries with the licence texts, and `cmake/ffmpeg.cmake` links it after libavcodec when present, so artifacts from before this change still link (without AV1). The default build is untouched: the fallback stays off, there is still no FFmpeg or dav1d code without `-Puveditor.ffmpeg`.
**Why dav1d:** FFmpeg's native AV1 decoder only drives hardware accelerators, libaom is a much slower software decoder and a reference implementation, and dav1d is the fast CPU decoder with NEON assembly. BSD-2-Clause is compatible with the LGPL configuration, so `config.h` still says "LGPL version 2.1 or later" (the script keeps checking that and now also checks that the libdav1d decoder is really configured). The decoder is reached through the generic software reader (`avcodec_find_decoder`), so no engine code changed; MediaCodec still gets the first try.
**Why `PKG_CONFIG_LIBDIR`:** FFmpeg's configure needs pkg-config to find dav1d. Setting the library dir (not the path) makes it blind to a libdav1d of the build machine, so a CI runner with a host dav1d cannot leak a host-architecture library into the arm64 build.
**Not done:** an AV1 encoder (no export in AV1); dav1d's own threading (libavcodec's frame threads drive it, within the existing CPU budget); 10-bit RGB output (RGBA8 like all software frames).
**Verified here (on a 14-core machine, in a few minutes, so it was not too heavy to run):** the whole script ran to the end (dav1d 1.5.4 cross-compiled for arm64 with NDK r29, NEON symbols present; FFmpeg 8.1.3 configured and built with it, `libdav1d` in the decoder list, licence guard passes, artifact with `licenses/`), and `./gradlew :app:assembleDebug -Puveditor.ffmpeg=<that install>` links the engine against it (`libuveditor_engine.so` 11,36 MB, `libdav1d` strings inside). What was not run: the GitHub workflow itself (it needs `meson` and `ninja-build` from apt, added to the workflow), and the AV1 reader test against a libav with development files (not installed here; the clip generation and a decode with `ffmpeg -c:v libdav1d` were checked). The host-tests job runs on this pull request. **Not run on a device:** no AV1 file was decoded on a phone.

## Voice effect keyframes

**Decision:** the sliders of a voice preset are parameters `audio.voice.<sliderIndex>` of `Clip.params`, so keys, interpolation, split/trim/speed re-basing, copy/paste and undo are the existing parameter machinery. Another preset or none drops the voice tracks (`TimelineOps.setClipAudio`); `setClipAudioAt` turns a change of a keyed slider at the playhead into a key and leaves the fixed value.
**Decision:** the animation is resolved in Kotlin to one lane per engine setting (`voiceLanesOf`), not per slider, and the native side knows no presets. Presets map sliders to settings with affine maps, so evaluating the settings at the union of the animated sliders' key frames (plus one point per frame in an eased or Bezier segment) and interpolating linearly in the engine is exact. Settings that never leave their fixed value get no lane.
**Decision:** the processor stays in the decode worker and reads a `VoiceSchedule` in clip-local output samples, with the position passed to `reset(position)`; the schedule and its identity are part of the source key, so a key edit restarts decoding like a slider release does and realtime, offline and export remain one code path (tested equal within 1e-6 and independent of block size). Snapshot version 7 appends the lanes behind the voice blocks with a trailing byte size (variable-length data after a region whose size is "the rest of the buffer" needs it); 4 to 6 still parse.
**Decision:** which stages exist comes from the widest value of each setting over the keys (`envelope`), so a lane can switch a stage on from a neutral static value; per-stage values are read every 16 samples (post chain) or at the centre of each analysis frame (spectral stage), never per call.
**Decision:** echo delay is read fractionally and ramped across a block, so a moving delay glides in pitch (tape-like) and a fixed one stays a whole number of samples as before; reverb size animation moves the comb feedback (decay) and not the delay lengths, which are fixed at the middle of the keyed range, because changing comb lengths live clicks. Ring modulation uses a phase accumulator so a moving carrier has no phase jump.
**Why:** one lane format, no preset knowledge in C++, and the decoding pipeline (alignment by priming, tails, seek resets) stays untouched apart from the schedule.
**Alternative:** run the effect in the realtime mixer with `PreparedLane`s (rejected earlier for FFTs on the audio thread); per-slider lanes with the maps in C++ (couples the engine to presets).
**Not heard on a device:** the DSP and its parity are host-tested (including a gliding pitch with bounded steps, a gliding delay peak at the expected sample, held-lane = static output); the inspector diamonds were compiled, not seen on a screen. A key edit re-reads the clip, so scrubbing the keys is not live.

## Animated loop count

**Decision:** the loop count is read from the container at import (`WebpAnimationScan.plays`: ANIM chunk; `GifDelayScan.plays`: NETSCAPE2.0 / ANIMEXTS1.0 application extension, first sub-block with id 1) and stored as `MediaAssetDto.animationPlays` (total passes, optional JSON; absent or 0 = forever). `AnimationTiming.plays` makes `frameIndexAt` return the last frame once `plays * period` has elapsed, so a clip longer than that holds the last frame. `segments` and the export plan go through `frameIndexAt`, so preview and export agree with no further change: the held stretch is one long segment, merged into one rasterised picture.
**Counting:** WebP: loop count = total plays (0 = forever), per the container spec. GIF: no extension = plays once (browsers); extension count 0 = forever; count N = N + 1 plays (the de-facto meaning in browsers and giflib, N is repeats after the first pass). Capped at 65,536 passes.
**Compatibility:** projects saved before this have no field and keep looping forever, so no existing clip changes; only newly imported files honour their count. A GIF with no loop extension now plays once where it used to loop; that is what the file says. The default clip length stays one pass (not plays x period, which could be hours); the user stretches the clip and sees the hold.
**Alternative:** a user toggle to loop anyway; not added, the clip's length is already the user's control and an infinite file keeps looping.
**Not run on a device:** probe changes are in the Android import path (compiled, covered through the pure scanners); timing, hold, segments and JSON have JVM tests.

## 2026-10-04 · Timeline polish left out of PR #94: snap guide, drag shadow, thumbnail fade-in, byte-budgeted label LRU
**Chosen:** (a) a snap guide line (`snap_guide.h`) at the frame a moved edge sits on; Kotlin decides it (`domain/SnapGuides.kt`: an edge that moved since the drag began and lies exactly on a snap target: frame 0, playhead, markers when marker snapping is on, edges of clips not being moved) and sends it with (b) the keys of the dragged clips through a new `nativeSetDragOverlay`; dragged blocks are drawn in a second pass over the others with a four-layer soft shadow (`shadowLayer`) and a light rim. (c) Thumbnail tiles fade in over 160 ms (`fade_curve.h`, smoothstep) from the upload time kept per atlas slot; the picture being replaced stays underneath so the cell never dips to the block colour; thumbnail vertices gained an alpha float and are now written through the pre-sized buffer. (d) `LabelTable` is a byte-budgeted LRU (budget 3/4 of the atlas): evicted rectangles go to a free list (row neighbours merged, best fit, leftover split) and are reused; bitmaps used in the current or previous frame are never evicted; only when all are protected does the old reset (new generation) remain as last resort. Evicted hashes are reported to Kotlin (`nativeLabelTakeEvicted`) so `LabelPump` forgets and resends only those.
**Why a separate call and no snapshot bump:** the overlay changes on every drag step and has nothing to do with the timeline content; the snapshot format is unchanged (still version 8). The guide is a coincidence test on the result, so it also shows when the finger lands exactly on a target; an unmoved edge (the far end of a trim) never draws one. Cost when not dragging: one empty-vector check per frame, no extra pass, no shadow geometry.
**Not done:** a ghost at the clip's original position (the canvas only has the preview timeline), guide for marker and media-tray drags.
**Tests:** host tests for the LRU (budget, eviction order, protection, reuse, merging, last-resort reset), guide placement, shadow ramp, fade curve; JVM tests for `SnapGuides` and the pump's eviction resend.

### Measured on the Huawei MatePad (Android 12), 110-clip project, `perf-timeline.sh` 45 s per pass (3-4 windows of 240 frames), two interleaved passes
| cpu_ms (mean over windows) | p50 | p95 | p99 | max | draws | verts |
|---|---|---|---|---|---|---|
| master baseline (2 passes) | 1.84 | 2.53 | 3.73 | 4.5 | 4.0 | 6963 |
| this change (2 passes) | 1.89 | 2.63 | 3.42 | 4.2 | 4.0 | 6963 |
| delta | +2.7 % | +4.0 % | -8.4 % | | | |

p95 and p99 are within 5 % of baseline. **Unverified:** the test project's media are missing, so no thumbnails were drawn (fade-in path not measured or seen on a device); a drag could not be started through adb (a swipe scrolls, `draganddrop` did nothing), so the guide and the shadow were not seen on screen and their drag-time cost was not measured.

## 2026-10-04 · Vertical lane zoom and Fit
**Context:** lane heights were three presets only. Many lanes needed scrolling and there was no way to see the whole stack.
**Chosen:** a pinch whose fingers spread mostly vertically scales the lane height (0.5x to 3x of the default, anchored under the fingers); a horizontal pinch is unchanged. The axis is decided once per pinch, after a 12 dp slop, so a diagonal wobble cannot zoom both. The existing Fit button (it already fitted the time axis) now also fits the lanes and keeps following the panel and the lane count until the user zooms by hand. The scale is one Q12 integer in native code; the renderer only reads it through `Layout`. The initial view still uses the Small / Medium / Large preset (fit is an explicit action), because starting every project at 3x tall lanes would change the familiar default.
**Alternatives:** a second Fit button (rejected: one already exists); scale-follows-both-axes with one finger-distance factor (rejected: a vertical pinch would also change the time zoom); per-lane heights (not needed, bigger change to hit testing and snapshots); persisting the scale (the horizontal zoom is not persisted, so neither is this).
**Open:** the pinch itself could not be exercised on a device (adb cannot pinch); the debug harness extras `--ef vzoom`, `--ez fit`, `--ei lanes` call the same native code. Tiny pinch spans (fingers nearly level) give no vertical factor.

## 2026-10-06 · LumaFusion project import (`.lfpackage`, `.lfarchive`), footage in a user-chosen folder
**Context:** the user wants to bring LumaFusion for iOS projects over: cuts and tracks must come across and whatever cannot be replicated must be listed. Two real projects (LumaFusion 5.5.2) were the only reference: a simple one (4 cuts) and an iPhone review (95 clips, 5 lanes, 43 photos, 6 titles, one effect, split-screen layouts). An `.lfpackage` is a zip, stored, with the archive and all footage (3.2 GB and 7.9 GB here).
**Chosen:** detect by content (zip with `*.lfarchive` and no `bundle.json`; JSON with `tracks` + `attributes.appVersion`); read the package through `ZipFile` on the document's file descriptor (central directory, zip64, no memory load, streamed 1 MB copies with progress and cancel); convert with exact `BigInteger` CMTime math rounded half up on positions (not durations) so touching clips touch in frames; map only what the samples justify and list the rest with counts. Footage goes to a **folder the user picks** (SAF tree, persisted permission, About > Media folder) and is referenced by `content://` document URIs; names are never overwritten (" (2)"), failures and cancels delete what the import created, deleting a project never touches the folder. Position and scale are imported only for horizontal layouts (conventions inferred from the geometry of the samples); portrait clips' orientation +90 and rotation -90 cancel; a vertical offset is reported.
**Alternatives:** unpacking into app-private storage (rejected by the user: they want to see and manage the files, and 8 GB hidden in app data is hostile); reading the zip with `ZipInputStream` (rejected: no central directory, no size check before copying, stored entries with data descriptors fail); guessing speed, reverse, transitions, markers and keyframes from LumaFusion field names (rejected: no sample has them, a wrong mapping is worse than a reported omission); mapping vertical position with a guessed sign (rejected: the samples cannot tell up from down).
**Update (device run on a Pixel 8):** `ZipFile` on `/proc/self/fd/N` failed with EACCES for a file in Downloads (FUSE), so the package is read by an own `ZipReader` on positional reads of the open descriptor; and importing LumaFusion's rotation (pi for a clip whose file has a 180 degree tag) turned the picture upside down because the decoder applies the tag, so no rotation is imported.
**Open:** scale/position units, opacity/volume/pan scales, title placement and alignment values are inferred, not compared with a LumaFusion render. Titles lose fonts and shadows. The `/proc/self/fd` route needs a seekable document: a provider that serves a pipe fails with a clear message.

## 2026-10-06 · Exports survive leaving the app: keep the screen on and run them in a foreground service
**Context:** the export ran in the dialog's `viewModelScope` and was cancelled when the editor was left; once the app was in the
background Android could freeze or kill the process, and a screen timeout during a long export suspended it. Rotation was already safe
(`configChanges`, a retained view model).
**Chosen:** both (a) `FLAG_KEEP_SCREEN_ON` on the window while a job runs and the activity is visible, and (b) a foreground service,
`ExportService`, with a progress notification and a Cancel action. The job moved out of the view model into a process-wide
`ExportExecutor` (pure Kotlin, unit tested) that publishes a `StateFlow`; the dialog is just a view of it, so it reconnects after the
editor or the activity is recreated and Cancel works from both places. The service only keeps the process alive and mirrors the state
into a notification; it holds no export logic, so the work does not depend on the service starting (if Android refuses to start it, for
example when the app is no longer in the foreground, the export still runs and the failure is logged under `UVExport`).
**Permissions (four, all about running the task, none about data or network):** `FOREGROUND_SERVICE`,
`FOREGROUND_SERVICE_MEDIA_PROCESSING`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS` (runtime, asked when Export is pressed,
optional). The service type is `mediaProcessing` on Android 15+ (API 35); that type does not exist on Android 12 to 14, where a
foreground service of an unknown type is rejected for apps targeting 34+, so the manifest declares `mediaProcessing|dataSync` and
`startForeground` passes `dataSync` below API 35 (which is why its permission is needed). `dataSync` here is only the label for "long
task"; nothing is synchronised. `OfflineGuaranteeTest` now allows exactly these four and a new test proves INTERNET and the other
network permissions are still rejected, and that the manifest has one non-exported service and no receivers or providers.
**Alternatives:** (1) wake lock only: keeps the CPU on but does not stop Android freezing or killing a background process and does not
help when the user leaves the app. (2) WorkManager: made for deferrable, constraint-based work; an export is started by the user and
has to start now, show live progress and be cancelled, and it would still run as a foreground service underneath. (3) No service, keep
screen on only: leaving the app or locking by hand still stops it.
**Limits:** the system gives a media processing service 6 hours per 24; at the limit `onTimeout` stops the service (the export would
then run unprotected). A killed process (low memory, swipe-away from Recents while the service is stopped) ends the export with no
resume, and the partly written SAF file is not removed because nothing is running to remove it (same as a crash before this change);
the next start has no job so the dialog never claims one is running. Only one export at a time (a second is refused).
**Not verified on a device:** see the pull request (the device checks could not be run in this session).

## 2026-10-06 · The export notification and the project list lead back to the exporting project
**Context:** on a real device, tapping the export notification opened the app wherever it was, and nothing in the project list showed that
an export was running, so a second one could be started from another project (and failed with a generic message). The editor's export
dialog also could not be closed while running, so the project list could not be reached from it.
**Chosen:** the job state carries the project id. The notification opens `MainActivity` with the id (works cold and through `onNewIntent`);
the pure `exportDestination()` opens the editor with the dialog only when the project exists and the executor still holds an export of it,
otherwise the project list, so a stale notification (project deleted, process restarted) never shows an empty dialog. The project list
shows a bar from the same `StateFlow` (running, finished with Share, failed; dismissed by the user, never silently dropped; cancelled just
goes). Other projects' Export buttons are refused with the running project's name. The running dialog gets a Hide button (dismiss hides it
only), which is what makes "go back to the project list during an export" possible.
**Alternatives:** carrying only the project name (rejected: names are not unique ids); reopening the dialog for any notification tap
(rejected: after a restart nothing is exporting); disabling the toolbar button outright (rejected: a disabled button cannot say why).

## 2026-10-06 · Save frame as image: a one-frame mode of the exporter
**Context:** thumbnails and covers need a still of any frame, exactly as the export would draw it (layers, titles, transitions, retimed and
reversed clips, HLG tone mapping). Only the Freeze frame tool and the hub thumbnails existed, and neither draws the composite.
**Chosen:** a "still" mode of `ExportJob` (`ExportParams::still`): the same `Renderer`, clip list, decoders and `drawScene` as the export, drawn
once into an offscreen RGBA8 framebuffer and read back with `glReadPixels` into a direct buffer that Kotlin owns; no encoder, muxer or
audio. Kotlin plans the frame with `buildExportPlan()` (so the plan cannot drift from the export) and keeps only the clips that cover the
frame, so only their media is opened. Colour: the still is always rendered in the SDR output space, which is exactly the existing SDR
export path (HLG tone mapped), and the bitmap is tagged sRGB. Encoding is `Bitmap.compress`; the YouTube size limit is met by bisecting
the JPEG quality (monotonic size, about eight encodes). "Selected clip only" plans a timeline holding only that clip, so it reuses the
plan instead of adding a second code path. "Fill" renders on a surface that covers the output and reads back the centre rectangle,
instead of changing `drawScene` (a viewport larger than the framebuffer would break the blend-mode destination snapshot and the effect
chain). The save is refused while an export runs and the dialog is modal, so two engine jobs never compete for the hardware decoders.
`decode/video_decoder.cpp` and `.h` were not touched.
**Alternatives:** (1) read the preview surface: not the exporter's picture (preview size, preview decode policy, a play state), and the
preview is not available at an arbitrary size; (2) a new decoder and compositor path for stills: a second copy of the seek, retime and
substitution logic that the exporter already hardened; (3) `PixelCopy` from the preview `SurfaceView`: screen-sized, needs the surface
visible; (4) a quality knob for PNG: PNG has no quality, so a YouTube PNG over 2 MB is flagged and JPEG suggested; (5) even-rounding sizes:
images need none, so odd project sizes are kept (only the engine surface for a crop is grown by a pixel or two so the integer letterbox
cannot leave a black edge). The request building (titles, stills, LUTs, descriptors) is repeated in `NativeFrameRenderer` rather than
extracted from `ExportViewModel`, to keep this change away from the export code that other work touches; a later refactor can share it.
**Limits:** one frame costs about a second (the decoder has to start and decode up to the frame); output is capped at 4096 px per side to
bound memory (readback buffer plus bitmap); HDR output (HLG PNG/AVIF) is out of scope; the document is created before the picture is drawn,
so a failure deletes it (like the export does) rather than drawing first and asking later.
**Verified on a device:** the engine and encoder with `FrameDemoActivity` on the Pixel 8 (frame numbers, ffmpeg comparison, HLG, fill, JPEG
search, ICC/sRGB tags); the dialog, the document picker and the Share sheet were not driven (the phone's screen was off).

## 2026-10-06 · Faster export: proxies for layers shown no larger than their proxy (opt-in)

**Context.** A 4K export with several 4K layers at once is decode-bound: the hardware decoders are shared. A layer shown in a third of the canvas
needs no more than a 1280x720 picture.
**Decision.** An export option, off by default, decodes a layer from its READY proxy when the proxy has at least as many pixels as the layer covers
in the output (rule and exclusions in SPECS 5.10; pure code in `ExportProxyAssist`, JVM tests `ExportProxyAssistTest`, `ExportFasterExportTest`).
Missing proxies fall back to originals; nothing is generated during an export (a preparation phase was not built: it only pays when the saving
exceeds the proxy generation time, which depends on a long project, and proxies are made in the background anyway).
**Why off by default.** It changes pixels (an H.264 8-bit copy instead of the original), so the user opts in; the dialog says so.
**Measured (Pixel 8, synthetic 3 layers of 4K H.264 30 fps at a third of a 4K canvas, HEVC 35 Mbps 4K30 output, 300 frames; `ExportDemoActivity --es layout stack`):**
originals 30 fps (10.8 s); with proxies 37 fps (8.2 s). Parity of the two outputs, frame by frame: SDR source PSNR 43.6 dB (min 43.1), SSIM 0.997;
HLG 10-bit HEVC source exported to SDR PSNR 41.3 dB (min 40.9), SSIM 0.997 (the HLG vs SDR source outputs differ by only 31.9 dB, so tone mapping
of the proxy is consistent). The synthetic content is `testsrc2`, a worst case for compression.
**Not done.** Preparation phase with progress; a real-project run.

## 2026-10-06 · Export encoder runs at priority 1 and maximum operating rate

**Context.** Profiling a 4K export showed the render thread waiting about 20 ms per frame for decoded frames even when the decoders were trivially
light (one 1280x720 proxy layer on a 4K canvas still gave 32 fps), while the same layer at 720p output ran 65 fps. The wait follows the encoder's load.
**Decision.** `setVideoFormat` sets `KEY_PRIORITY` 1 (non real time) and `KEY_OPERATING_RATE` max, which an offline export may do. `setprop debug.uveditor.export_enc_flags 0`
turns it off for comparisons.
**Measured (Pixel 8, 4K HEVC 35 Mbps output, `ExportDemoActivity`):** one 4K H.264 30 fps layer, 600 frames: 17.9 s (33.5 fps) to 9.3 s (64 fps; decode bound);
one 720p layer upscaled to 4K, 360 frames: 11.5 s to 3.45 s (104 fps); a 4K canvas with a third-size proxy layer: 31.7 to 124 fps; three such layers 37 to 95 fps; three 4K H.264
layers (decode bound) 30 to 35 fps. The decoded frames are identical with and without (framemd5 equal for both A/B pairs; the files differ only in the container tail),
so quality and bitrate control are unchanged. Setting the same two keys on the decoders changed nothing (35.4 vs 35.3 fps with three 4K layers).
**Not measured.** Power and temperature over a full 40 minute project (status stayed 0 over the 10 s runs).

## 2026-10-06 · HLG export on the Pixel 8: three causes behind "this device cannot encode HDR at this size"
**Found on:** a 4K60 HLG project (iPhone footage) on the Pixel 8 (Tensor G3, `c2.exynos.hevc.encoder`), which can encode Main10 up to 7680x7680 and 960 fps.
**Causes (all measured on the device with `debug.HdrProbeDemoActivity`):** (1) the probe's trial `configure` omitted `KEY_I_FRAME_INTERVAL` and the bitrate mode, which the exporter always sets;
that encoder rejects such a format with an empty `IllegalArgumentException` at every size, so the HDR option was hidden (with the keys it configures at 1080p30, 2160p30 and 2160p60;
`findEncoderForFormat` and `isFormatSupported` were true all along). The probe now builds the exporter's format and logs why a configure is rejected (tag `UVExport`). (2) With HDR offered, the exporter
still failed "no ten-bit encoder surface": the Mali driver has no RGBA1010102 EGL config flagged `EGL_RECORDABLE_ANDROID`; the context now falls back to the unflagged one (the encoder surface accepts it).
(3) The first HDR file was tagged and converted full range (`color_range=pc`, luma 0..1023): `ADATASPACE_BT2020_HLG` is full range and the encoder follows the buffer data space, not `KEY_COLOR_RANGE`.
The exporter now sets `ADATASPACE_BT2020_ITU_HLG` (limited) on the encoder surface; luma then matches the source (18..960 against 29..958).
**Not changed:** the Huawei MatePad's hisi encoder was rejected by the old probe, which also lacked the key-frame interval (OMX `-38` is what a missing one gives); the exporter's own configure failed there
too, and the new probe uses the exporter's keys, so it is expected to keep rejecting it, but the tablet was not available to confirm.

## 2026-10-06 · Uncompressed audio in QuickTime files is read by the engine, not by MediaExtractor
**Context:** the full 4K export of an imported LumaFusion project had no sound on the base track. The base footage is an iPhone `.mov` whose
soundtrack is linear PCM (`lpcm`, 48 kHz stereo 16-bit; `ffprobe`: `pcm_s16le`). Android's `MediaExtractor` does not list such a track, so the
probe answered `hasAudio = false`, the editor wrote that into the library, and the exporter (like the preview) builds audio lanes only for assets
with audio: the clips were silent without any error. LumaFusion's own data was right (audio stream present, volume 1 mapped to 0 dB, volume 0 to -96 dB).
**Chosen:** `data/MovAudioScan.kt` finds a PCM sound track in the header boxes so the probe says `hasAudio = true` (and so a project opened later is
repaired by `verifyAssets`), and `audio/mov_pcm.*` is a `PcmDecoder` that reads the samples through the sample tables (`stsz/stsc/stco`) and
converts them to float. It sits between the platform decoder and the FFmpeg fallback in `AudioEngine::openAssetDecoder` and in the waveform extractor,
so preview, export and waveforms all get it, and it needs no FFmpeg build. The LumaFusion report now lists the clips that are silent only because
LumaFusion had volume 0. **Alternatives:** requiring the FFmpeg fallback (off by default, a 7.6 MB engine growth for a trivial format);
transcoding the audio at import (copies and rewrites the user's media).
**Not handled:** edit lists (the track here starts 745 samples = 15 ms late; ignored), non-interleaved or 24-in-32 aligned `lpcm`, more than two channels (the first two are used).

## 2026-10-06 · No debug switch for the decoder's gap marking
**Context:** PR #118 shipped `debug.uveditor.decode_gap` to A/B the fix. A diagnostic run left it at 0 on the reference phone; system properties under `debug.*` survive until reboot, so every later export of the installed app ran with the fix off, and the 41-minute full exports did not measure the fix at all.
**Chosen:** the switch is removed; gap marking is always on. A/B comparisons use two builds, not a property that can outlive the run.
**Rule:** a debug property must never switch off a correctness or speed fix in a build that other people (or later sessions) use; diagnostics that stay (`export_perf`, `timeline_stats`, `atlas_bytes`) only add logging or shrink caches in debug builds. Scripts that set a debug property must reset it in a trap on exit.

## 2026-10-07 · Media folder layout: the app makes its own `ultimateVE` subfolder
**Context:** the chosen "Media folder" received loose files (the footage of every imported LumaFusion package, e.g. 122 files directly in the user's Movies/LFImport), and two packages with the same file names produced " (2)" copies. LumaFusion instead creates its own folder in the one the user picks and sorts what it writes below it (`LibraryMedia`, `media`, `Project-Backups`, `ReversedMedia`, `UserMedia`).
**Chosen:** the root is literally `ultimateVE` inside the chosen folder. A chosen folder that is already called `ultimateVE`, or that already holds one (any case), is reused: no `ultimateVE/ultimateVE`. Every folder is created on first use (`MediaLayout`, `FolderPath.ensure`), so an empty one never appears; only what the app writes is listed here.
| Folder | Written? | Why |
|---|---|---|
| `ultimateVE/Media/<project>/` | yes | Footage unpacked from `.lfpackage` imports (the only copy of media the app makes). One subfolder per import, named after the project (cleaned, unique among its siblings: "Name (2)"), as LumaFusion keeps a folder per project. Two packages with the same file names cannot collide, and deleting one folder removes one project's footage. The " (2)" suffix for files still applies, per subfolder. |
| `ultimateVE/Project-Backups/` | yes, on first `.uvbundle` export | The save picker opens there (`EXTRA_INITIAL_URI`, a document of the chosen tree). The folder is created when the picker opens and removed again if the picker is cancelled and the folder is still empty. The user can still save elsewhere. No "Back up now" button was added. |
| `LibraryMedia` | no | LUTs, fonts, looks, title presets and templates are app-private (`filesDir`), not user-visible, and stay so. |
| `ReversedMedia` | no | Reverse playback is a retime mapping (`domain/Retime.kt`); no reversed copy is ever written. |
| `UserMedia` | no | The app records nothing (no voice-over). Saved frames go to Pictures/ultimateVE through MediaStore and exported movies go where the system picker is pointed: both stay as they are. |
**Migration:** none. Files from imports made before this change are directly in the chosen folder and projects reference them by `content://` URI; they are never moved, renamed or deleted. About shows how many such files sit there. New imports use the subfolder.
**Cleanup:** a cancel or failure deletes the files written, then the folders this import created, innermost first and only while empty (`MediaLayout.discardEmpty`); an `ultimateVE` or `Media` folder that existed before is never removed. A package without footage creates nothing. Deleting a project never deletes user files.
**Alternatives:** one flat `ultimateVE/Media` (name collisions between projects, no per-project cleanup); copying LumaFusion's five folders up front (empty folders that mean nothing here).
**Not verified:** how each third-party document provider treats `EXTRA_INITIAL_URI` (the picker may ignore it).

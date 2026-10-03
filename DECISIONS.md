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

# CapCut features, animated text, and automatic subtitles: a study for ultimateVE

Study only. Nothing under `app/` was changed, no dependency was added, no phone was used. Written 2026-10-07 on master `ff693de`
(release 0.3.10). Facts about CapCut and about third-party libraries come from public pages fetched on that date (listed per
section and in the source list at the end); anything I could not verify is marked **[unverified]**. Numbers I measured myself are
marked **[measured]**; numbers I derived are marked **[estimate]** with the assumption.

## 0. Summary (read this if nothing else)

| Question | Verdict |
|---|---|
| Can we have "text explodes" and similar animated text/GIF-style effects? | **Yes, and it is the best next feature.** It is classical graphics (per-glyph transforms, seeded particles), compatible with every privacy rule, and builds directly on the animated-captions work. Effort L for a first good version (glyph atlas + instanced pass + 12-20 original presets). |
| Can we bring back automatic subtitles OFFLINE? | **Technically yes** (whisper.cpp, MIT, base q5_1 = 57 MB; 12 min of audio in about 1 min on my laptop, about 2-6 min on a phone **[estimate]**). **But it breaks the owner's "no AI/ML" rule**, so it needs an explicit owner decision. If approved: do it **outside the main APK** (see section 4.c, architecture C) or as a user-imported model file, never as a bundled/downloaded model in the main app. |
| Can we use an ONLINE model? | **Technically yes, but it is the worst fit.** Audio leaves the phone, INTERNET permission, a third party (or the user's own server), and the guarantee "the app physically cannot reach the network" is lost. Only acceptable as a **separate optional add-on app (architecture C)** with a user-supplied endpoint, and I recommend against doing it at all until the offline path has been tried. |
| The owner's "switch to enable online options" | A runtime switch (architecture A) **cannot** keep the guarantee: INTERNET is an install-time permission. A separate add-on app (C) can; two flavours (B) can for the default build but split the user base. **Recommendation: C, with B as the fallback** (section 4.c.6). |
| Classical (no-ML) help for subtitles | Worth doing regardless: voice-activity-based "snap to speech" and typing-assisted captioning (M). It does not give you words, only when someone speaks. |

Top five, ranked (details in section 5): 1) animated text presets with a per-glyph engine (L); 2) classical "assisted captioning"
(speech-segment snapping, `.lrc`/word-timed import, paste-a-script-and-time-it) (M); 3) a small original sticker/animation pack
plus APNG import (S-M); 4) more transitions/effects packs and shake/glitch/glow effects (M); 5) decision on the speech add-on
(offline whisper add-on if the owner relaxes the rule for an optional, separate APK) (L, gated by the owner).

---

## 1. CapCut feature inventory (dated 2026-10-07)

### 1.0 How reliable is the "free vs Pro" column?

CapCut does not publish one authoritative, current table, and public sources disagree. Findings from the pages I fetched:

- CapCut's own page `capcut.com/resource/capcut-standard-vs-pro` lists Pro at "$19.99 per month" / "$179.99 per year" (team from about
  $24.99), and marks **auto caption generator, background remover, motion tracking, noise reduction, AI video/music, AI image generator
  as Pro-only** (free column "x"). It also shows a "Standard" tier.
- Third-party reviews dated May 2026 say the opposite for some items: **auto captions stay on the free plan but with a cap** (one test: 5
  generations per rolling 30 days; CapCut does not publish the number **[unverified]**), basic background removal is free, and
  unlimited captions are Pro. Another (Descript's blog) says captions "moved behind a paid tier" in a 2025 restructure and that SRT
  export is paid and desktop-only.
- eesel.ai (edited 2026-05-20) names three tiers: Free, **Standard about $9.99/month** (mainly watermark removal), Pro $19.99/month or
  $179.99/year (4K/HDR/60 fps export, full asset library, camera tracking, remove flicker, vocal isolation, cloud storage).
- Everything agrees on one point: **AI actions are metered by credits/quotas even on Pro**, features vary by region, platform (mobile,
  desktop, web) and app version, and the split changes often. Treat the column below as "what public sources said in 2026", and
  check the in-app labels before relying on any single row.

Legend: **F** = free (maybe quota-limited), **P** = Pro/paid, **?** = sources disagree or silent. **Cloud/AI** = needs an account and/or
server-side or ML processing (CapCut states most of its AI runs in the cloud; I did not find a per-feature statement, **[unverified]**).
"ultimateVE today" is from `docs/USER_GUIDE.md` and the code.

### 1.1 Text and captions

| CapCut feature | Free/Pro | Cloud/AI | ultimateVE today | Gap |
|---|---|---|---|---|
| Auto captions (speech to text) | ? (F with quota / P, see 1.0) | Yes (ML; cloud) | Typed + `.srt`/`.vtt` import; no recognition (by design) | Speech recognition; see section 4 |
| Caption styles / animated captions (karaoke, word pop...) | F + P templates | No (templates) | 8 styles (Classic, Bold, Pop, Impact, Karaoke, Word pop, Typewriter, Bounce) | Fewer styles; no glow/shake/glitch |
| Text animations in/out/loop | F basic, P extra | No | Title templates, keyframes (position/scale/rotation/opacity), caption animations | **No per-glyph/per-word/per-line animation, no particle text** |
| "Text explode/shatter/particle text" | Mostly template/overlay packs; tutorials do it manually with split letters + fly-out + flash overlay | No | None | **Section 3** |
| Text templates | F + P | No | Text templates (data over titles/keyframes) | Smaller library; no animated presets |
| Auto lyrics | ? | Yes (ML) | None | Needs ML; `.lrc` import is the classical route |
| Translate captions | P (reported) | Yes (ML/cloud) | None | Needs ML |
| Text to speech | F in select languages, more P | Yes | None (removed for privacy) | Out of scope |
| Speech-to-text editing (delete words to cut video) | P/? | Yes | None | Needs ML transcript |
| Fonts | F + P | No | System fonts + imported fonts (WP-T) | OK |

### 1.2 Stickers, animated stickers, GIF overlays

| Feature | Free/Pro | ultimateVE today | Gap |
|---|---|---|---|
| Static and animated stickers, emoji | F + P packs | Drawn shapes + system emoji; GIF and animated WebP import with loop count | Original animated pack; APNG; Lottie is a possible extra |
| GIF/overlay library (Giphy-style online search) | F | Import files only | Online library is out of scope (network + third-party licences) |
| Keyframe sticker animation | F | Yes | OK |

### 1.3 Effects, filters, transitions

| Feature | Free/Pro | ultimateVE today | Gap |
|---|---|---|---|
| Filters and video effects (glitch, blur, shake, glow, VHS, light leaks) | F limited / P extensive | Chainable shader effects (tint, blur, sharpen, vignette, gray, sepia, chroma key, LUT, HSL qualifier, denoise, deflicker); 3D LUTs | Glitch, shake, glow/bloom, RGB split, film grain, light-leak overlays are classical shaders and missing |
| Transitions | F limited / P extensive | Crossfade plus a transition pack (SPECS 5.28) | More; all classical |
| Blend modes, masks, chroma key | F | Yes | OK |

### 1.4 AI video features

| Feature | Free/Pro | Needs ML | ultimateVE today | Verdict |
|---|---|---|---|---|
| Background removal / smart cutout | ? (basic F, HQ P) | Yes | Chroma/luma key, masks | Out (ML) |
| Auto reframe (subject aware) | P (reported) | Yes | Manual start/end reframe helper | Out (ML); manual stays |
| AI upscale / enhance | P | Yes | None | Out (ML); a classical Lanczos/sharpen is not equivalent |
| Retouch / beauty | P/F | Yes (face) | None | Out (ML), and irrelevant to phone reviews |
| AI stylise / "AI model", relight, remove object | P, credits | Yes (generative) | None | Out |
| Camera tracking / motion tracking | P (reported) | Classical possible | Point/region tracker (classical Lucas-Kanade), stabiliser | Done |
| Remove flicker, denoise | P | Classical possible | Deflicker + Denoise effects | Done |
| Text-to-video, avatars, AI music, AI images | P, credits | Yes, cloud | None | Out |
| Smooth slow motion (optical flow) | F/P | Classical | Optical-flow slow motion (WP-V4) | Done |

### 1.5 Audio

| Feature | Free/Pro | ultimateVE today | Gap |
|---|---|---|---|
| Noise reduction | P (per CapCut page) | Spectral noise suppression (profile based) | Done classically |
| Voice changer / effects | F + P | Voice effects (pitch+formant, robot, whisper, radio...) | OK |
| Beat sync | F | Beat detection, markers, cut-to-beat | Done |
| Vocal isolation | P | None | Out (ML) |
| Audio effects, EQ, ducking | F | EQ, fades, pan, loudness, ducking | OK |
| Music / SFX library | F + P licensed | None (bring your own) | Licensing: only CC0/own packs |
| Voice clone, dubbing | P, credits | None | Out (ML, cloud) |

### 1.6 Editing tools

Keyframes (F), speed curves (F), freeze (F), reverse (F), chroma key (F), masks (F), stabilisation (F/P), multi-layer (F), templates (F + P),
auto cut (silence removal: F/P), "smart cut" / highlights (ML): ultimateVE has keyframes (generalised), speed curves/ramps, freeze, reverse,
chroma key, masks, stabiliser, multicam, project templates (`.uvtemplate`), silence cutting. Gaps: highlight detection and "video to
shorts" need ML (out); camera shake/zoom presets are classical (S).

### 1.7 Export, sharing, cloud

Free caps at 1080p export per the eesel/Descript/bigvu-type reviews (CapCut's own page claims 8K export for both, an inconsistency
**[unverified]**); watermark on some free templates; 4K/HDR/60 fps on Pro. Cloud storage ~1 TB on Pro, collaboration on Team. ultimateVE:
4K60, HLG HEVC export, no watermark, bundle/EDL/FCPXML interchange; cloud and team are out of scope.

### 1.8 Takeaway for the inventory

Of everything CapCut ships, three groups matter: (1) **creative-asset features that are pure graphics/DSP** (animated text, stickers,
effects, transitions): all compatible, biggest visible gap; (2) **speech-dependent** features (auto captions, translate, lyrics, speech
editing, dubbing): all need ML; (3) **generative/cloud AI**: permanently out. The owner's phone-review use case values group (1) and
the captions part of group (2).

---

## 2. Impact / fit matrix

Effort: S (days), M (1-2 weeks), L (3-5 weeks), XL (more than 5 weeks) of focused work. "ML?" = fundamentally needs a learned model.
Perf/battery and APK are for the feature on a flagship phone. Risk = privacy/legal against the hard rules. Fit = parity between
preview and export via `domain/RenderPlan.kt`, native timeline in SurfaceView, titles rasterised in Kotlin, integer frame time.

| # | Gap | Value for phone-review videos | Effort | ML? | Perf / battery | APK | Privacy / legal | Architecture fit |
|---|---|---|---|---|---|---|---|---|
| 1 | Per-glyph animated text ("explode", shatter, wave, glitch) | High (hooks, titles, captions) | L | No | GPU instanced quads, < 1 ms/frame **[estimate]**; negligible battery | + few 100 KB (presets as data) | None; presets must be original | Good if evaluated stateless (3.4); needs a new glyph-atlas + instanced pass |
| 2 | Original animated stickers/overlays pack + APNG | Medium | S-M | No | Same path as GIF/WebP | + 0.5-3 MB if bundled | Only own/CC0 assets; no CapCut/Giphy assets | Fits the still/sticker path |
| 3 | Lottie support | Low-Medium | M | No | CPU rasterise per frame, fine for small vectors | rlottie ~1 MB native **[unverified]** or lottie-android jar | Lottie files vary in licence; GPL-3.0 compatible renderers (MIT, Apache-2.0) | OK via Kotlin rasterise path, but a new dependency; skip for now |
| 4 | Glitch/shake/glow/RGB-split/grain/light-leak effects | Medium-High | M | No | One shader pass each | negligible | None | Fits effect chain; CPU reference maths in `effect_math.h` |
| 5 | Camera shake/zoom/punch-in presets (keyframe generators) | Medium | S | No | none | none | none | Pure `domain/`, trivial |
| 6 | Assisted captioning: speech-segment snapping (VAD) | High for non-ML captions | M | No | Offline analysis seconds per minute | none | None | Pure analysis like silence cut |
| 7 | `.lrc` and word-timed import; paste-script-and-time | Medium | S | No | none | none | None | Fits `Subtitles.kt` / `CaptionPlanner` (kept) |
| 8 | Automatic captions, offline ASR | Very High | L (M if add-on) | **Yes** | CPU, 1-4 min per 12 min video, heating; 0.2-0.5 GB RAM | +57 MB model (base q5_1) +~2-4 MB lib **[estimate]** | **Breaks owner rule "no AI/ML"**; model licences MIT/CC-BY; no network needed if sideloaded | Fits as `caption-` title clips with `words` timing |
| 9 | Automatic captions, online | Very High | M | Yes (remote) | network, no CPU | none | **Breaks "no network", "no third-party", leaks audio**, GDPR | Same output mapping; huge policy change |
| 10 | Translate captions | Medium | L | Yes | CPU model 100s of MB | big | Breaks AI rule | n/a |
| 11 | Background removal, auto reframe, upscale, relight | Medium | XL | Yes | heavy GPU/NPU | big | Breaks AI rule | n/a |
| 12 | Transition pack growth | Medium | M | No | shader | negligible | None | Fits `RenderPlan` transitions |
| 13 | Text-to-speech | Low | M | Yes (or system TTS that may use network) | n/a | n/a | Breaks rule | n/a |

Classical (compatible with the privacy rule): 1, 2, 3, 4, 5, 6, 7, 12. Fundamentally ML: 8, 9, 10, 11, 13 (and every generative feature).

---

## 3. Deep dive: "text explodes" and animated-text/sticker effects

### 3.1 How CapCut-style effects are built

CapCut's own help and tutorials (fetched 2026-10-07: Pippit "text explosion effects" templates, YouTube/TikTok tutorials) show two
routes: **a built-in/template route** where the effect is a prebuilt animation, and a **manual route** where the editor splits the text
into one layer per letter, applies "fly out" animations per letter, and adds a flash effect plus a freeze frame. So the core of the
look is not mysterious; it is a small set of building blocks:

1. **Unit of animation**: per glyph, per word, per line (or whole block). A delay (stagger) between units gives the "wave".
2. **Transform channels** per unit: translate, scale, rotate, opacity, plus colour/blur. Driven by an easing curve (ease-out-back,
   elastic, spring, bounce).
3. **Explode/shatter**: each unit (or each fragment of a glyph) gets a pseudo-random velocity vector away from the centre, spin and
   gravity, and fades out. Shatter cuts the glyph into polygons/tiles (Voronoi or a grid) and does the same per tile.
4. **Particles**: a short-lived emitter (sparks, smoke puffs, confetti) spawned at the glyph positions at the "burst" frame.
5. **Accents**: glow (blurred copy added), shake (decaying random offset), glitch (RGB channel split + horizontal slice jitter +
   scanlines), mask reveal (wipe, per-glyph clip), flash (white overlay for 2-4 frames).

### 3.2 What we have and what is missing

Today: `domain/captions/CaptionAnimator` (138 lines, integer maths) yields a `TitleLook` per frame (visibleWords, visibleChars,
activeWord, activePercent). `engine/title/TitleRaster` and `LayeredTitleRaster` rasterise the whole title as one bitmap per look,
which the compositor draws as a texture; the exporter splits a caption into one `VideoClipSpec` per run of equal look; a re-anchor
of the native clock happens per look change (DECISIONS "Animated captions: look per frame in Kotlin, exporter splits clips by look").
That design is perfect for step-like looks (a word pops every ~10 frames). It does **not** scale to an explosion where every glyph
has a different transform on every frame: that would mean one new bitmap per frame (60-120 per 2 s), the 96-entry cache thrashes, and
the exporter would emit a clip per frame. Keyframes only move the whole clip.

### 3.3 Classical implementation options

| Option | How | Pros | Cons |
|---|---|---|---|
| A. Rasterise every frame in Kotlin (extend the current approach) | `CaptionAnimator` computes per-glyph transforms; `TitleRaster` draws each glyph with a Canvas matrix | No native change; exact parity (same code) | Cost per frame: a few ms to 10+ ms on the CPU with blur/glow; bitmap upload every frame (4K title bitmaps are large); exporter loop becomes one clip per frame; re-anchor every tick. Fine for 1-2 s bursts at 1080p, poor for long or full-screen effects |
| B. Glyph atlas + instanced GPU pass (recommended) | Kotlin rasterises each unique glyph (or each word/line) once into an atlas texture (already how stickers/titles upload textures); a new native "glyph pass" draws N instanced quads, each with a transform/opacity/colour computed **in the shader** from `(age_in_frames, unit_index, seed, preset params)` | Stateless (any frame can be drawn without simulating the previous ones: scrub-safe and export = preview by construction, same shader); cheap; supports particles | New engine piece; GLSL float maths must be deterministic enough across preview/export (they are the same shader on the same GPU, so identical; a CPU reference for host tests as with `color_math.h`) |
| C. Offline-baked sprite sheet | Pre-render the explosion once into frames (PNG sequence) and play as an animated sticker | Simplest runtime; reuses GIF/WebP path | Not editable text (every text change re-bakes); memory for long clips |
| D. A particle library (Lottie, Rive) | rlottie/lottie-android | Rich authoring | Text is not dynamic; dependency; licence of assets |

Recommendation: **B**, with **A kept as the fallback for simple per-word looks** (what exists). Make the evaluator a **closed-form
function of `(frame age, index, seed)`**, not a stepped simulation: position `p(t) = p0 + v*t + 0.5*g*t^2` with `v`, spin and delay
derived from an integer hash (splitmix32) of `(clipSeed, glyphIndex, fragmentIndex)`. Determinism is then by construction:
the same seed gives the same explosion in preview, export and after a project reload, and the seed is stored in the clip JSON.

### 3.4 Cost per frame (estimates, **[estimate]**, not measured on a device)

- A 30-character headline exploding into 4x4 shards per glyph: 480 instanced quads. A mid-range mobile GPU draws tens of thousands of
  small alpha-blended quads per frame at 60 fps; this is under 0.5 ms of GPU time and almost no CPU (one uniform block per draw).
- Glow: one downsampled blur of the title texture (the compositor already has blur for effects). 0.3-1 ms.
- Particles (sparks/confetti): 500-2000 point sprites from closed-form positions, also under 0.5 ms.
- Atlas memory: 30 glyphs at 256 px = ~8 MB RGBA; at 512 px for 4K export ~32 MB. Rasterised once per text edit.
- CPU: the evaluator in the shader means no per-frame Kotlin work beyond a uniform update, and no re-anchor of the native clock per
  look (the big current cost of animated captions).

### 3.5 What new engine pieces are needed

1. **Glyph/word atlas** in Kotlin (`engine/title`): break a layout into units with their bounding boxes (reuse `LayerBounds`), draw
   each into an atlas page, upload as a texture; add unit rects and baseline offsets to the scene snapshot.
2. **Glyph pass** in `render/` (GLES 3.2, instanced draw, `shaders.h`): parameters = preset id, parameter vector, frame age, seed.
   Keep a CPU reference (`glyph_math.h`) tested against the shader in `cpp/tests` like `color_math.h`.
3. **Particle pass** (point sprites from the same closed-form hash), optional second phase.
4. **Mask reveal / wipe** per unit: a scissor/threshold in the same shader.
5. `domain/` : `TextFx(preset, params, seed)` on `TitleContent` (JSON, versioned), included by `RenderPlan` so preview, export and
   audio-independent timing all see the same data. Integer frame time everywhere; only the final shader maths is float.
6. The snapshot JNI gets a new optional block (bindings only, per CLAUDE.md).

### 3.6 A library of ORIGINAL presets

Never copy CapCut's template names, assets or trademark ("Explode", "Shatter" as generic English words are fine, but name ours our own).
Presets are data: `{ unit: glyph|word|line, stagger frames, ease, in/out/loop, channels, burst params, seed policy }`. Suggested starter
set (16 names, all original): Burst, Shard, Scatter, Drift apart, Pop-in, Cascade, Wave, Bounce drop, Spin-in, Typewriter pop,
Glitch, Shake hit, Glow pulse, Wipe reveal, Stamp, Confetti. Editor UI: a tab in the existing Titles tray with a preview loop of each
preset on the user's own text, then 3-5 sliders per preset (duration, intensity, stagger, seed "Re-roll" button, colour). Also usable on
captions: the caption `animation` field gains a per-word `TextFx` so Karaoke could "pop" or "burst" each spoken word.

### 3.7 Animated sticker formats

| Format | Licence of the decoder | Size | Notes |
|---|---|---|---|
| GIF, animated WebP (have) | own code | in | Already imported, loop count honoured |
| APNG | trivial own parser (PNG chunks acTL/fcTL/fdAT) | in | Missing; 8-bit alpha, better than GIF. S |
| Video with alpha (WebM VP9 alpha / HEVC alpha) | MediaCodec outputs no alpha on most devices **[unverified]** | n/a | Avoid |
| Lottie (JSON vector) | rlottie MIT (Samsung); lottie-android Apache-2.0; ThorVG MIT (all GPL-3.0 compatible) | rlottie ~1 MB .so **[unverified]**, lottie-android ~few hundred KB jar **[unverified]** | Dynamic and tiny files, but the Lottie asset ecosystem (LottieFiles) has mixed licences; it adds a runtime dependency to `THIRD_PARTY_NOTICES.md` and a new code path. Not needed for text explosions |
| Own preset animations (data) | none | tiny | Cheapest: a sticker animation as the same glyph/shape engine (3.3 B) |

Assets: only **own-created or CC0**; no scraping from CapCut, Giphy or Tenor. Users can import their own files (already possible).
A bundled pack of 20-30 own animated overlays (arrows, sparkles, confetti, rings, "like/subscribe"-style shapes without third-party
marks) at about 100 KB each as WebP is roughly 2-3 MB.

### 3.8 Implementation plan (sketch, no code)

1. **Phase 0 (S)**: write `SPECS 5.8x` and a DECISIONS entry; choose the stateless closed-form model; define `TextFx` JSON.
2. **Phase 1 (M)**: atlas + glyph pass with 3 presets (Pop-in, Cascade, Burst); CPU reference + host tests; preview only.
3. **Phase 2 (M)**: export parity (scene snapshot in `RenderPlan`), golden frame tests (render frame N in preview path and export
   path to a buffer and compare), seed determinism tests, reload test.
4. **Phase 3 (M)**: remaining presets, glow/shake/glitch accents, editor UI, restyle-all integration for captions.
5. **Phase 4 (optional)**: particle pass, APNG import, own sticker pack.

Test strategy: pure `domain/` unit tests (seed hash, preset parameter ranges, JSON round trip, split/trim of a clip with `TextFx`);
`cpp/tests` host tests of the closed-form maths vs shader-equivalent CPU reference at boundary frames (age 0, burst frame, last
frame, negative age); instrumented golden-frame comparison between preview and export on the tablet-style flow already used for
slow motion (`scripts/check-*-export.sh`). Cost estimate for phases 0-3: **L**.

---

## 4. Automatic subtitles and speech recognition

### 4.0 What the previous attempt did, and why it went

- `93e3119` (2026-10-03) "Add on-device automatic captions (whisper.cpp)": whisper.cpp **v1.9.4 as a pinned git submodule** built
  statically into `uveditor_engine` with `GGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16`, OpenMP/native/llamafile off, CPU only. A JNI
  transcriber with progress and cancel; audio decoded with the existing PCM decoder and converted to exact 16 kHz mono; word
  timestamps; `domain/captions` mapped words to timeline frames, grouped phrases, produced title clips on a **new title track** in
  one undo step. Models **Whisper base q5_1 (57 MB, default) and tiny q5_1 (31 MB)** were **downloaded on demand** from Hugging Face
  with SHA-256 pinning and atomic install, which required the **first INTERNET permission**. It was not verified on a device (adb was
  offline) and added about 2 minutes to every native build (DECISIONS "Privacy").
- `86631f4` (2026-10-04) "Privacy: remove on-device AI captions and all network access" removed all of it after the owner's rule
  ("la privacidad es algo importantisimo"). Reasons recorded: it is an AI feature, it needed a third-party model host and INTERNET, and
  the native build got slower. The kept alternative: sideloading a model from a local file was **considered and not chosen** because
  the rule says no AI.
- Mistakes not to repeat: (1) downloading a model from the app (network + third party); (2) putting the engine in the main build
  (build time, APK/native size, rule violation in one place); (3) a feature shipped unverified on device; (4) submodule friction for
  contributors.

### 4.a Offline on-device engines

**Measured on this laptop (host-side, no phone)**: Intel Core Ultra 7 265U, 14 threads available, run with `-t 4`, x86-64 AVX2, CPU only,
whisper.cpp **v1.9.5** (commit `d1be6fd`, 2026-10-06), Release build. Test audio: the public-domain JFK sample, plus espeak-ng synthetic
speech (English and Spanish, 20 s, and 5-min files; one with pink noise added). Synthetic speech is easier and cleaner than real voices
so **WER is optimistic and the sample is tiny (53-60 words)**; the numbers are for ranking models, not for promising quality.
Scripts and raw results: `docs/research/whisper-host-bench/`.

| Model (file size) | Real-time factor, 20 s clip | RTF, 5-min file | Peak RSS | WER en | WER en + noise | WER es |
|---|---|---|---|---|---|---|
| tiny q5_1 (31 MB) | 0.044-0.051 | n/a | n/a | 13.3 % | 11.7 % | **26.4 %** |
| base q5_1 (57 MB) | 0.081-0.094 | **0.089-0.090** | 206 MB | 10.0 % | 13.3 % | 11.3 % |
| small q5_1 (181 MB) | 0.259-0.294 | **0.284-0.299** | 476 MB | 8.3 % | 11.7 % | 5.7 % |
| small f16 (465 MB) | 0.289-0.323 | n/a | n/a | 11.7 % | 11.7 % | 7.5 % |

(RTF = processing time / audio length; lower is better. Some "errors" are formatting: "120Hz" vs "one hundred and twenty hertz", "2 days" vs
"two days". Errors seen: tiny turned "battery easily lasts" into "matter is easily left" and garbled most Spanish; base mis-heard
Spanish "analizar" as "realizar" and "brillante" as "guiante"; small made the fewest errors.)

**Extrapolation to phones [estimate, my assumption]**: a flagship ARM phone in sustained 4-thread CPU work is likely 1.5-4x slower
than this laptop's 4-thread run (higher memory latency, throttling; ARM NEON dotprod/i8mm helps quantised matmuls). Published phone
numbers I found are thin: a third-party write-up (MVP Factory, **[unverified, secondary source]**) reports whisper-tiny q8_0 about 118 ms
and base q8_0 about 275 ms per 1-second chunk on a modern Android phone (RTF 0.12 and 0.28); a whisper.cpp issue discussion for a Pixel
6a reports RTF about 0.6 CPU-only. I found **no authoritative published Pixel 8 (Tensor G3) or Snapdragon 8 Elite numbers** for
whisper.cpp; the whisper.cpp benchmark thread I fetched (issue #89) contains only desktop, Raspberry Pi 4 and iPhone 13 mini figures.
So for a 12-minute video (720 s):

| Model | Laptop, measured/scaled | Pixel 8 / SD 8 Elite class [estimate] | MatePad (older Kirin) [estimate] |
|---|---|---|---|
| tiny q5_1 | ~35 s | 1-2 min | 2-4 min |
| base q5_1 | ~65 s | **2-4 min** | 4-8 min |
| small q5_1 | ~3.6 min | 6-14 min | 15-30 min, thermal throttling likely |

Energy: the CPU runs flat out on 4 cores for that time; a few percent of battery for base on a flagship **[estimate]**, noticeable heat
for small. This is a one-time batch job per video, not continuous.

**Candidate engines**

| Engine | Licence (code / model) | Size | Languages | Word timestamps | Notes |
|---|---|---|---|---|---|
| whisper.cpp + Whisper | MIT / MIT (verified: openai/whisper README) | tiny 75 MiB, base 142 MiB, small 466 MiB f16; q5_1: 31/57/181 MB (**measured**); RAM ~273/388/852 MB (README table) | 99 | Yes, experimental `-ml 1` token timing (measured: sensible 70-300 ms resolution; accuracy against ground truth **not verified**). DTW alignment option exists **[unverified]** | The proven choice; previous attempt used it; NEON; has Silero VAD integration; Vulkan backend exists but unproven on Adreno/Mali for this use **[unverified]** |
| sherpa-onnx (k2-fsa) | Apache-2.0 / depends per model | runtime several MB + model | many, includes Whisper and Parakeet-TDT v3 (25 European languages, Spanish) | Yes, Whisper timestamps from 1.12.24 | Extra dependency (onnxruntime); more flexible and has Android APKs for VAD+ASR demos |
| NVIDIA Parakeet-TDT 0.6B v3 | model CC-BY-4.0 (attribution needed, GPL-compatible as data) | 600 M params; q8 GGUF noted (size not stated) **[unverified]** | 25 incl. Spanish | Yes (word, char) | Reported Spanish WER 3.45 % Fleurs; large for a phone (hundreds of MB), whisper.cpp tree has a parakeet converter (seen in `models/`) **[experimental]** |
| Moonshine (Useful Sensors) | streaming models MIT; older non-English models "Community" licence (non-commercial) | tiny 34 M, small 123 M params | English, Spanish (tiny 6.2 % / small 4.9 % WER), others | not stated **[unverified]** | Very small and fast, designed for streaming; Spanish streaming models MIT per the docs I fetched; young project |
| Vosk / Kaldi | Apache-2.0 | small models ~40-50 MB | 20+ | Yes | Noticeably less accurate than Whisper on noise and accents (several sources); no punctuation/casing |
| Silero VAD | MIT | ~2 MB | language independent | n/a (segments) | Not recognition; useful with any engine and for the classical path |
| Android `SpeechRecognizer` | platform API; the recogniser is provided by the device's recognition service (usually the Google app) | not ours | per installed pack | `EXTRA_REQUEST_WORD_TIMING` exists | See below: **reject** |

**Android's own on-device recogniser (verified against AOSP source and docs)**: `createOnDeviceSpeechRecognizer()` (API 31),
`isOnDeviceRecognitionAvailable()`, `checkRecognitionSupport()` and `triggerModelDownload()` exist; the class "requires RECORD_AUDIO";
`RecognizerIntent.EXTRA_AUDIO_SOURCE` (API 33) takes a `ParcelFileDescriptor` plus `EXTRA_AUDIO_SOURCE_ENCODING` and
`EXTRA_AUDIO_SOURCE_SAMPLING_RATE` (default 16000), so **file input is possible** if the recognition service supports it ("if the
recognizer does not support this feature, it opens the mic": support is up to the service). `EXTRA_REQUEST_WORD_TIMING`,
`EXTRA_ENABLE_FORMATTING`, `EXTRA_ENABLE_LANGUAGE_DETECTION` exist (per-service support **[unverified]**). Problems: (1) it needs the
**RECORD_AUDIO permission**, contradicting the "no microphone permission" promise and the permission allow-list; (2) the model and
service belong to a third party (Google app; language packs are downloaded by it, using the network, outside our control), which is a
"third-party service" in the owner's terms; (3) availability varies by OEM and is absent on Huawei devices without Google services
(the MatePad); (4) behaviour of file input, accuracy and long-file limits are not specified or testable by us. **Verdict: no.**

**Delivery of the model without the app using the network**

| Route | Cost | Verdict |
|---|---|---|
| Bundled in the main APK | Release APK is about 6.6 MB (0.1.0 size log in `docs/RELEASE.md`); base q5_1 adds 57 MB (about 9x), small 181 MB. Also fits Play's 200 MB base limit but turns a tiny app into a large one for a feature not everyone uses | Bad for the main app |
| User imports a model file via the system picker (SAF) | Zero APK growth; zero network in the app; user downloads the file once with a browser or a computer; app verifies SHA-256 of known models; stored in app-private storage | Good, clumsy first run, and needs the owner's rule changed for "no models" |
| Separate optional add-on APK holding engine + model (architecture C) | Main APK untouched; the add-on can embed the base model (or ship via Play Asset Delivery / on F-Droid as its own app) | **Best** if the rule is relaxed |
| Play Asset Delivery/on-demand pack | A Play-only mechanism (network by Play, not the app); not applicable to F-Droid/GitHub builds | Out |

**Technical build notes**: NDK r28+ aligns native libraries for **16 KB pages** by default (Google Play requires 16 KB support for
apps targeting Android 15+ from 2025-11-01; verified in the Android Developers blog and secondary sources); whisper.cpp builds with
that. ggml's ARM path wants `dotprod`/`fp16` (the old build used `armv8.2-a+dotprod+fp16`); `i8mm` helps on newer cores but is not
available on all (Kirin on the MatePad: **[unverified]**), so it needs a runtime check or a second variant. Contributors need the
submodule or FetchContent. Build time was about +2 min before. Long file: whisper processes in 30 s windows; a 12-min file is fine in
chunks; memory stays under 0.5 GB for small. Spanish quality is the real constraint: **tiny is unusable for Spanish (26 % WER on clean
synthetic speech), base is borderline (11 %), small is the minimum for good Spanish** (5.7 %), and it costs 181 MB and about 0.3 RTF.

**Punctuation and casing**: Whisper produces them; Vosk does not. **Phone noise**: Whisper degraded only slightly on pink noise in my
test (base 10.0 -> 13.3 %); real wind/handling noise is harder **[not tested]**. **Word timing for animated captions**: measured
word offsets from `-ml 1` looked plausible (tokens ~70-700 ms) but there is no ground truth in this test; expect errors of 100-300 ms
and merged words; the caption pipeline should treat them as approximate and allow nudging.

### 4.b Classical, non-ML alternatives

- **Voice activity detection (VAD)** with energy/spectral flux, zero-crossing and hysteresis (classical), or Silero VAD (a 2 MB neural
  model: *is ML*, so stay classical for the rule). Gives speech segments with start/end. Existing code already finds silences from the
  waveform (the quick-edit "Cut silences"); inverting that gives speech segments. Accuracy: good on clean speech, poor with music or
  continuous noise.
- **Forced alignment of a typed/pasted script** to audio without a neural model: classical HMM/DTW alignment needs an acoustic model,
  so honest limit: without a model you can only align **text length to speech segments** (distribute words across detected speech
  segments proportionally to syllable-ish length). That gives usable subtitle timing when the speaker follows a script (reviewers often
  do) but errors of up to a second inside long segments. Real forced alignment needs a model (ML).
- **Beat/loudness segmentation** is what the app already does for beats; for subtitles it only finds phrases separated by pauses.
- **Assisted manual captioning (proposal, M)**: (1) the user pastes or types the text of the video; (2) the app proposes splitting into
  caption chunks; (3) analysis finds speech segments; (4) chunks snap to segments (proportional word timing inside each); (5) the
  user nudges with the existing caption sheet (frame stepper); (6) the result uses the existing `CaptionPlanner`/`words` mapping so all
  animated styles work. Also: `.lrc` and word-timed `.srt` import (S). This keeps everything offline, classical and testable on the JVM.

### 4.c Online models/services

**What it implies**: the audio of the owner's video leaves the device and is processed by someone else; needs the INTERNET permission
(plus an HTTP client: OkHttp or `HttpURLConnection`, both currently banned by the test), an endpoint, an account/key (or none for a
self-hosted server), and trust in a processor under GDPR (voice is personal data; a video-review channel's voice is the owner's own
data but bystanders' voices could be in clips).

| Option | Cost (2026) | Notes |
|---|---|---|
| OpenAI transcription API | whisper-1 $0.006/min, gpt-4o-transcribe $0.006/min, gpt-4o-mini-transcribe $0.003/min (secondary sources); word timestamps with `timestamp_granularities=["word"]`; 25 MB upload limit per file | 12 min is about $0.04-0.07; needs a key, account, third party, chunking above 25 MB |
| Other clouds (Google STT, Azure, Deepgram, AssemblyAI) | similar per-minute pricing **[unverified]** | Same implications |
| Self-hosted whisper.cpp server / faster-whisper on the user's own PC or NAS, reachable on LAN | free | No third party, but still INTERNET (LAN needs the same permission on Android), the owner must run a server, and cleartext on LAN conflicts with `usesCleartextTraffic=false` unless HTTPS with a certificate is set up |

Reliability/latency: upload of 12 min of AAC is about 10-15 MB, fine on Wi-Fi; transcription typically faster than real time; but needs
connectivity, and service terms/pricing change. Quality: cloud large models beat on-device base/small, especially for Spanish with
noise. Honest comparison: online gives the best words, offline-small gives acceptable words with no leakage; the **owner's rule makes
online the wrong default**.

**F-Droid (verified on f-droid.org/docs/Anti-Features)**: "Non-Free Network Services" applies to apps that promote or depend on a
proprietary network service; "Tethered Network Services" applies to depending on a service that is impossible or hard to replace, and is
**not applied if users can point the app at an alternative self-hostable server**. So a user-supplied endpoint avoids both flags;
bundling a default commercial endpoint would trigger NonFreeNet. Any INTERNET permission is still visible to users and to the Play data
safety form (declare "data shared: audio").

### 4.c.1 The owner's "switch" idea: three architectures

**(A) Runtime switch in the same app** (Settings > Online features, default OFF, per-use consent)

- `android.permission.INTERNET` is a normal permission granted at install; **there is no runtime toggle** that an app can use to
  drop it. Some ROMs (GrapheneOS and a few OEM skins) let the user block an app's network **[unverified per device]**; stock
  Android does not guarantee it. So with A the manifest always contains INTERNET, and the strong claim "the app physically cannot reach
  the network" is gone, even with the switch off.
- What a switch *can* guarantee: no online code path reachable when off, no sockets, no DNS, no libraries initialised; it can be
  unit-tested by injecting a failing network layer and asserting nothing calls it. It cannot protect against a future bug, a
  compromised dependency or a malicious fork; it relies on trusting the code instead of the OS.
- Consequences: `OfflineGuaranteeTest` and `docs/PRIVACY.md` have to be rewritten (permission allow-list + banned APIs), the Play
  data-safety form and the F-Droid listing change, and privacy-minded users who check `aapt2 dump permissions` will see INTERNET.
  Effort: M (switch UI, consent, client, tests). **Not recommended.**

**(B) Two build flavours / two APKs** (`offline` default, `online` with another applicationId, e.g. `...uveditor.online`)

- The offline flavour keeps zero INTERNET and no network code (compiled only in `src/online`); the guarantee holds *for that APK*.
- Costs: a second CI job and release artifact set, versioning/signing for both, two Play listings (or GitHub-only for online), two
  update paths, separate app data (projects do not move unless the user exports a bundle and imports it); users with both installed
  see two icons. Maintenance: every feature must work in both; only flavour-specific code differs.
- Test changes: `OfflineGuaranteeTest` currently scans `src/main` and reads `src/main/AndroidManifest.xml`; it must (a) keep failing the
  build if the **offline** flavour's *merged* manifest or sources contain INTERNET/network APIs (scan the merged manifest, not only
  `src/main`, because flavour manifests merge), and (b) for `online`, allow-list exactly one permission (INTERNET) and exactly one client
  class/package, and ban analytics/ads everywhere. Effort: M-L (flavour split + CI + tests + docs).
- Weakness: it fragments users and the "online" build still exists as an official APK carrying INTERNET.

**(C) A separate OPTIONAL companion app ("ultimateVE Online Add-on")** that alone holds INTERNET and the networking code

- Main app: **zero network permission, zero network code, unchanged guarantees.** It shows the "Online transcription" option only
  when the add-on is installed (`PackageManager` check; needs a `<queries><package android:name="...addon"/></queries>` element because
  of package visibility on Android 11+; `<queries>` is not a permission and the permission allow-list stays untouched).
- Interface (my recommendation for minimal leakage and no provider in the main app): an **explicit-component bound service** in the
  add-on (`bindService` with `setComponent` or `setPackage`, never an implicit intent). The main app decodes the selected clip's
  audio to 16 kHz mono PCM (the old `Mono16kConverter` approach), creates a pipe with `ParcelFileDescriptor.createPipe()` (not a
  socket) and passes the read end through the AIDL/Messenger call; the add-on streams the PCM to its endpoint or to its local model, and
  returns an immutable list of `(text, startMs, endMs)` words. **Nothing is written to disk and no content URI or FileProvider is needed**
  (if a URI route were preferred, the main app would need a `FileProvider` with temporary read grants, and `OfflineGuaranteeTest`
  currently forbids any `<provider>`/`<receiver>`).
- Only our add-on may answer: declare a **signature-level permission** in the add-on (or main app), require it on the service
  (`android:permission`), and have the main app also **verify the add-on's signing certificate** with
  `PackageManager.getPackageInfo(..., GET_SIGNING_CERTIFICATES)` and a pinned SHA-256 before binding. The explicit-component rule is the
  main defence against audio reaching an arbitrary app; the certificate check closes the permission-squatting hole (a malicious app
  that installs first and declares the same permission name with a weaker level). The add-on checks `Binder.getCallingUid()` against
  our signature too.
- UX: an extra install (Play listing or GitHub/F-Droid APK), a clear consent dialog per use ("Audio from this clip will be sent to X"),
  nothing stored, a clear status in the add-on. The add-on has its own privacy statement and its own `docs/PRIVACY_ADDON.md`; the main
  app's `PRIVACY.md` gets one sentence saying an optional separate app exists and the main app never contacts the network itself.
- Licence/distribution: GPL-3.0 for both; separate repo or a separate Gradle module in this repo with its own `applicationId`; F-Droid
  would see two apps; the add-on gets the NonFreeNet/Tethered analysis (user-supplied endpoint avoids both flags).
- **The add-on can do OFFLINE ASR as well**: embed (or load from a user-imported file) the whisper model and run whisper.cpp inside the
  add-on, **with no INTERNET permission at all**. That keeps the main APK small (no 57-181 MB model, no 2-minute extra native build,
  no ML in the main code base), isolates the "AI" feature in a separate package the owner can choose to never install, and makes the
  privacy story simple: *main app has no network and no ML; the optional speech add-on has no network either (offline variant)*.
  Online and offline could be two builds of the same add-on or two add-ons; I recommend the offline add-on first.
- Costs: AIDL surface to version (v1 only needs `transcribe(pfd, sampleRate, lang) -> words` plus cancel/progress), process-death
  handling, two release pipelines, Android 15/16 background-service start restrictions (bind from the foreground editor: allowed),
  testing the pair on device. Effort: M for the protocol + main-app side, L for the add-on with whisper (reusing the old pipeline).
- Weakness: discoverability (users must install a second app) and the owner must keep two repos/artifacts healthy.

**Comparison**

| | A: runtime switch | B: two flavours | C: add-on app |
|---|---|---|---|
| Main app keeps "cannot reach the network" | **No** | Yes for the offline APK | **Yes** |
| `OfflineGuaranteeTest` change | Rewrite allow-list and banned-API scan | Split by flavour, scan merged manifests | **None for the main app** (if no provider is added) |
| `docs/PRIVACY.md` change | Rewrite | Add a flavour section | One sentence |
| Trust model | Trust the code | Trust the APK you install | Trust nothing about main; add-on is separate |
| F-Droid / Play impact | INTERNET on the only listing | Two listings | Main listing unchanged |
| Cost (effort) | M | M-L | M (protocol) + L (add-on) |
| User friction | Lowest | Choose the right APK | Install two apps |
| Data sharing | n/a | Separate app data | Main app keeps projects; add-on is stateless |
| Offline ASR fits | Bloats main APK | Offline flavour could include model, still large | **Model lives in the add-on** |

**Recommendation among A/B/C**: **C**. Reasoning: only C leaves the main app with the exact guarantees that `docs/PRIVACY.md` and
`OfflineGuaranteeTest` document today (nothing to relax), it contains the "AI" risk in a package that can be absent, and it also
solves the APK-size problem for the offline model. A is the weakest on the owner's own priority. B is a sound fallback if the
add-on proves too awkward (for example if Android's binding rules or Huawei's EMUI restrict cross-app binding **[unverified on the
MatePad]**), but doubles the release work and splits users. Exact rule changes: A: PRIVACY.md network section, test allow-list and the
two scans; B: both, but only for the `online` flavour; C: none for the main app, but a new DECISIONS entry that the main project may
**talk to** an add-on through an explicit-component interface, and a new `docs/PRIVACY_ADDON.md` plus the add-on's own tests (its
manifest must allow-list its own permissions: the offline add-on none, the online add-on exactly INTERNET).

### 4.d UX and architecture of results

- **Mapping**: ASR returns words with `(text, startMs, endMs)`. The existing (kept) pure `CaptionPlanner`/`Transcript*` types and the
  old `AddCaptions` command already did: milliseconds to project frames with integer maths, group words into phrases (line length,
  max duration, split on pauses and punctuation), create one `caption-` title clip per phrase with `TitleContent.words` (clip
  frames) so every animated style, restyle-all, and future `TextFx` presets work. Imported `.srt` captions use evenly timed words;
  ASR words give real word timing.
- **Placement**: a new title track on top (as in `93e3119`), single undo step.
- **Correction UI**: the timeline and the captions sheet already allow editing text (editing resets word timing to even spacing); a
  "re-time words" button would re-run alignment for only that caption. Add a transcript list view with tap-to-seek (S-M).
- **Languages**: choose the language (auto detect is less reliable on short clips); Spanish needs small or better. Translation is
  ML and out of scope (also for the add-on).
- **Speaker labels / diarisation**: ML; skip. **Profanity**: a user-editable word list that masks (`f***`) in the caption text,
  classical (S).
- **Privacy of transcripts**: transcripts end up in `project.json` and in project bundles (`.uvbundle`/template exports) as caption
  text. That is the user's own content but it makes shared bundles reveal speech; mention in the bundle export dialog. Nothing is
  uploaded by the offline path. For the online add-on: per-use consent, no caching of audio, no logs of text.

---

## 5. Recommendation

### 5.1 Ranked shortlist

| Rank | What | Effort | Risk | Privacy | Expected value |
|---|---|---|---|---|---|
| 1 | Per-glyph animated text engine + 16 original presets (section 3) | L | Medium (new GL pass, parity tests) | Compliant | Very high: the visible CapCut gap the owner asked about |
| 2 | Assisted captioning: pasted script + speech-segment snapping + `.lrc` | M | Low | Compliant | High: gets 80 % of the value of auto subtitles without ML |
| 3 | Accent effects: glitch, shake, glow, RGB split, grain; camera-shake/punch presets | M | Low | Compliant | Medium-high for review videos |
| 4 | Original animated sticker pack + APNG import | S-M | Low (asset licences: own/CC0 only) | Compliant | Medium |
| 5 | Offline speech add-on (architecture C, whisper.cpp small q5_1, user-imported or bundled model, no INTERNET) | L (M for protocol, L for add-on) | Medium (cross-app protocol, device testing, EMUI) | **Needs the owner to approve "AI in a separate optional add-on"** | Very high for captions |
| 6 | Online transcription | M | High | Breaks multiple rules even as an add-on | Not recommended |
| 7 | Lottie support | M | Medium (dependency, asset licences) | Compliant | Low |
| - | Background removal, upscale, relight, translate, TTS, voice clone, generative | XL | n/a | Out: ML/cloud | Not worth it for this app |

### 5.2 Verdict: automatic subtitles

- **Possible offline? Yes.** Engine: **whisper.cpp (MIT) with Whisper small q5_1 (181 MB) for Spanish and English**, base q5_1
  (57 MB) as a faster/lower-quality choice; tiny is not acceptable for Spanish.
- **Cost**: APK size 0 in the main app if it lives in the add-on (add-on APK roughly 3-5 MB engine **[estimate]** plus the model);
  time for a 12-minute video about 3.6 min on my laptop for small, 6-14 min on a flagship phone, 15-30 min on the MatePad
  **[estimate]**; RAM about 0.5 GB; battery: noticeable (CPU flat out), a one-off per video.
- **Quality expectation**: clean voice at normal speed: about 5-10 % word error rate with small (measured on synthetic speech, so
  optimistic; real phone-review audio with wind and music will be worse, perhaps 10-15 %); always needs a proof-read; word timing
  approximate (100-300 ms).
- **Rule changes the owner would need to approve**: (1) the "no AI/ML" rule must allow an ML model in an **optional separate
  package**; (2) the main app asks it through an explicit local interface and ships no model; (3) for architecture C the main app's
  `PRIVACY.md` and `OfflineGuaranteeTest` stay as they are; (4) the add-on has no INTERNET permission and its own notice
  (`THIRD_PARTY_NOTICES` entry for whisper.cpp, Whisper weights MIT, and the model SHA-256s).
- If the owner does **not** want any ML anywhere: do rank 2 (assisted captioning) and stop.

### 5.3 Verdict: online option

**Bad idea for this project, do not build it now.** It leaks the voice track of the owner's videos, requires INTERNET and a third
party (or a self-hosted server on a LAN that needs cleartext or certificates), costs money for cloud APIs, and adds little over
offline small for the owner's use. If the owner still wants it later, ship it only as a **separate add-on** (architecture C) with a
**user-supplied endpoint**, no bundled key, a per-use consent dialog, only the selected clip's audio, nothing stored, and keep the
default add-on offline.

### 5.4 Open questions for the owner

1. Is an ML model acceptable **inside a separate optional add-on app** (offline, no INTERNET)? If no, we stop at assisted captioning.
2. If yes: bundle the model in the add-on (APK 60-190 MB bigger) or have the user import a model file via the picker (smaller, clumsier)?
3. Spanish matters: is small (181 MB, 3x slower than base) acceptable, or is base's 11 % error rate with proof-reading enough?
4. Should speech recognition have **word-level** timing (needed for karaoke/word-pop) or is phrase-level enough?
5. Distribution of the add-on: same Play listing family, GitHub only, or F-Droid? (Affects signing and testing.)
6. Do you want the owner-facing priority to be text effects first (rank 1) and the speech work later?
7. Which phones must the speech add-on work on: the OPPO/Pixel class only, or also the Huawei MatePad (slower; Google services absent)?
8. Will you accept the cost of two repos/artifacts (architecture C), or would you rather take flavours (B)?
9. Original text-effect presets: any style preferences or channel branding to match?

---

## Sources

CapCut (fetched/searched 2026-10-07; conflicting, see 1.0):
- https://www.capcut.com/resource/capcut-standard-vs-pro
- https://www.eesel.ai/blog/capcut-pricing (edited 2026-05-20)
- https://fluxnote.io/guides/capcut-ai-features-free-vs-paid-2026
- https://www.descript.com/blog/article/capcut-captions-arent-free-anymore-heres-a-better-option
- https://www.capcut.com/resource/add-captions-with-ai and https://www.capcut.com/help/entrance-to-auto-lyrics (search hits only; not read in full)
- https://pippit.capcut.com/templates/text-explosion-effects and https://www.youtube.com/watch?v=REcJHLIGhpE (text explosion tutorials, search hits)
- https://bigvu.tv/blog/capcut-free-vs-pro-what-2026s-restructure-actually-gives-you/ (search hit)
- existing repo notes: `docs/capcut-comparison.md`, `docs/lumafusion-comparison.md`, `docs/USER_GUIDE.md`

Speech and libraries:
- Android SpeechRecognizer / RecognizerIntent source: https://android.googlesource.com/platform/frameworks/base.git/+/master/core/java/android/speech/SpeechRecognizer.java and `.../RecognizerIntent.java`; https://developer.android.com/reference/android/speech/SpeechRecognizer (page too large to fetch fully; API levels from search summary)
- https://github.com/ggml-org/whisper.cpp (MIT, backends, memory table, VAD, `-ml 1`); https://github.com/ggml-org/whisper.cpp/issues/89 (benchmarks: no Android rows); https://github.com/ggml-org/whisper.cpp/issues/1070 and discussion #3567 (Android speed reports, search hits only)
- https://github.com/openai/whisper (code and weights MIT); https://github.com/snakers4/silero-vad (MIT, ~2 MB)
- https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3 (CC-BY-4.0, 25 languages, word timestamps)
- https://moonshine-voice.readthedocs.io/en/stable/models/available-models/ (sizes, WER, licences)
- https://github.com/k2-fsa/sherpa-onnx (Apache-2.0; search summary), https://k2-fsa.github.io/sherpa/onnx/vad/apk-asr.html
- Vosk comparisons (secondary): https://www.sinologic.net/en/2026-05/vosk-vs-whisper-local-the-ultimate-2026-guide-to-self-hosted-speech-recognition-stt.html
- https://mvpfactory.io/blog/wiring-whisper-cpp-to-android-s-audiorecord-api-building-a-sub-100ms-on-device (secondary phone timings)
- OpenAI transcription pricing (secondary): https://diyai.io/ai-tools/speech-to-text/openai-whisper-api-pricing-2026/ ; https://costgoat.com/pricing/openai-transcription
- F-Droid anti-features: https://f-droid.org/docs/Anti-Features/
- 16 KB pages: https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html
- Lottie renderers: https://github.com/Samsung/rlottie (MIT), https://github.com/airbnb/lottie-android (Apache-2.0), https://github.com/thorvg/thorvg (MIT); licence summaries from search results only.

Own measurements: `docs/research/whisper-host-bench/` (`make-test-audio.sh`, `run-bench.sh`, `wer.py`, `results-2026-10-07.txt`).
The whisper.cpp clone, models and audio live outside the repo under `/home/qtekfun/uvdata/research`.

Not verified: any per-device phone benchmark; Giphy/Tenor licensing; rlottie binary size; Kirin i8mm support; EMUI cross-app binding
behaviour; CapCut cloud versus on-device processing per feature; which Android recognition services support `EXTRA_AUDIO_SOURCE`.

# CapCut: what it offers that ultimateVE does not (yet)

CapCut is the second reference: the creator-first editor for short-form video. This note lists its
publicly described features and marks what ultimateVE already has. Last reviewed 2026-10-04. See also
`docs/lumafusion-comparison.md` for the pro-editor reference.

## 1. What CapCut offers (public sources)

### Editing
- Trim, split, merge; overlay layers and a multi-track timeline; speed from 0.1x to 100x with **speed
  curves** and **smooth slow motion using optical flow**.
- Keyframe animation, chroma key, stabilisation, colour grading.
- Export up to 4K 60 fps with HDR on mobile (8K on desktop), adjustable resolution and frame rate,
  direct sharing to social networks.

### AI tools
- **Auto captions** (speech recognition), including speaker-aware captions and many languages.
- **Text to speech** with many voices and languages; voice effects; vocal isolation; voice cloning (Pro).
- **Background removal / smart cutout** and **motion tracking**; camera tracking.
- **Auto reframe** between aspect ratios.
- **Repair tools**: stabilise, reduce image noise, remove flicker, AI audio noise reduction; 4K upscaling (Pro).
- Generative features: text-to-video, video and music generation, avatars, AI images (credit based).
- Script and video generation, "video to shorts" extraction.

### Creative content
- A large library of **templates** (a predefined arrangement of effects, transitions, audio timing and
  media placeholders), filters updated weekly, transitions, effects, stickers, fonts, text templates.
- Free and premium music and sound-effect libraries.

### Platform and business
- iOS, iPadOS, macOS, Android, Web, Windows and HarmonyOS; about 323 million monthly users (July 2024).
- Free tier and Pro (USD 19.99 per month, 179.99 per year): premium assets, AI credits, cloud storage,
  team collaboration.

## 2. Where ultimateVE stands

| CapCut capability | ultimateVE today | Gap and how to close it |
|---|---|---|
| Multi-track timeline, overlays, split, trim | Yes (base + overlays, magnetic base) | Done |
| Speed 0.1x-100x, speed curves | 0.1x-8x, ramps, reverse, freeze | Raise the upper limit (decode skip rules), add a graphical curve editor (WP-V4) |
| Smooth slow motion (optical flow) | Frame repetition only | Frame interpolation (WP-V4) |
| Keyframes | Pose and opacity | Generalised keyframes (WP-K) |
| Chroma key, effects, masks | Yes | Smart cutout (WP-V1) |
| Stabilise | No | WP-X |
| Colour grading, LUTs | Basic adjustments; LUTs and per-clip colour in progress | WP-C |
| Auto captions + animated styles | Yes (whisper, 8 styles) | Speaker-aware captions, more languages (WP-V3) |
| Text to speech | No | WP-V3 (Android TTS and optional neural voices) |
| Voice effects, vocal isolation, audio denoise | No | WP-A (denoise), WP-V3 (isolation, effects) |
| Background removal / smart cutout | No | WP-V1 (on-device segmentation) |
| Motion tracking | No | WP-V1 (track a point or a region, attach text/stickers) |
| Auto reframe | Presets and canvas change, no subject following | WP-V2 |
| Auto cut (silence removal, highlights), video to shorts | No | WP-V2 |
| Noise reduction and flicker removal on video | No | WP-V4 |
| Templates with placeholders | Text templates only | Project templates (WP-V5) |
| Filters, transitions and effects packs | A basic set; crossfade only | Transition pack (WP-V5) |
| Music and sound-effect library | No | Out of scope for licensing; offer "bring your own" and a free-licence pack link (WP-V5) |
| Stickers, fonts | Built-in shapes/emoji; fonts planned | WP-T |
| Share to social, export presets | Presets and share sheet | Done |
| HDR export | HLG HEVC Main10 | Done |
| Generative AI (text-to-video, avatars, voice cloning) | No | Out of scope (needs cloud services) |
| Cloud storage, team collaboration | No | Out of scope; project bundle (WP-I) covers hand-off |
| Easy project creation and a media-first layout | Dense dialog, buttons, no media tray | WP-U1, WP-U2, WP-U3 |

## 3. Suggested priorities

1. **Make the app easier to use first** (WP-U1 to WP-U3): the creation flow, a media tray with drag and drop,
   and a layout the user can resize. CapCut wins on first-minute experience.
2. Then the creator features that need models on the device: **cutout and tracking**, **auto reframe and
   auto cut**, **text to speech**, **optical-flow slow motion**.
3. Keep the pro features of the LumaFusion roadmap (colour, audio, titles) in parallel.

Everything that needs a server (generative video, avatars, voice cloning, cloud sync) stays out of scope:
the project is offline-first and open source.

## Sources

- CapCut standard vs pro: https://www.capcut.com/resource/capcut-standard-vs-pro
- Wikipedia, CapCut: https://en.wikipedia.org/wiki/CapCut
- CapCut on Google Play: https://play.google.com/store/apps/details?id=com.lemon.lvoverseas&hl=en_US
- CapCut keyframe animation: https://www.capcut.com/tools/keyframe-animation
- CapCut AI features, free versus credits (review): https://marcandrews.com/capcut-ai-features-review-2026-which-ones-are-worth-it/
- CapCut Pro overview (review): https://www.zebracat.ai/post/i-tried-capcut

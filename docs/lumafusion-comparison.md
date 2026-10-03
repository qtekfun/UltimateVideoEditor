# LumaFusion: what it does and how it is built, compared with ultimateVE

Research notes from public sources (listed at the end). LumaFusion is the editor ultimateVE aims to
emulate. Facts below are what the vendor and press state; details of its internals that are not public
are marked as unknown. Last reviewed 2026-10-04.

## 1. What LumaFusion offers

### Timeline and organisation
- Up to 6 video/audio/graphic tracks plus 6 additional audio tracks (the number depends on the device).
- Magnetic, track-based timeline with multiselect, markers, tags and notes.
- Project frame rates from 18 to 240 fps, cinema to social aspect ratios, 4K, ProRes and HDR on iOS.
- Media from the device, USB-C drives, cloud storage, Frame.io (iOS) and an optional Storyblocks library.
- Projects can be moved between devices (JSON-based project archive, as the Android team describes it).
- Android 2.5 (January 2026): head and tail transitions (auto cross dissolves at both ends of selected
  clips), applying a transition to many clips at once, "find selected clip in library" and the reverse
  lookup, and support for Samsung's APV codec. The Android version is described as moving toward parity with iOS.

### Effects, colour and animation
- Layered effects: green screen, luma and chroma keys, blurs, colour correction, a video stabiliser.
- Colour scopes: waveform, vector scope, histogram; custom looks; LUTs (`.cube` and `.3dl`), several at once.
- Unlimited keyframes for effects; speed ramping and enhanced keyframing are a paid add-on.
- Effect and title presets that can be shared.
- Grids and guides (title-safe, action-safe, horizon).

### Titles
- Multilayer titles combining text, shapes and images; fonts, colours, borders, shadows; custom fonts
  and shareable presets.

### Audio
- Keyframed levels, panning and EQ; graphic and parametric EQ with voice isolation; auto-ducking;
  stereo and dual-mono; third-party audio plugins (iOS).

### Paid extras (iOS pricing as listed)
- Creator Pass (Storyblocks library and extras), Speed Ramping and Enhanced Keyframing, Multicam Studio
  (sync up to 6 cameras/audio sources), FCPXML export, PaintX (paint with motion tracking).
- iOS 5.3 introduced a plugin architecture for third-party editing and VFX plugins.
- Android and ChromeOS: one-time price of USD 29.99 (final release, November 2022).

## 2. How it is built

### iOS
- Built on AVFoundation originally.

### Android and ChromeOS (the closest to our case)
- The team rebuilt the compositing engine from scratch because the iOS one relied on AVFoundation.
  Four engineers worked on the main phase for about three years.
- An engine that **directly manages several MediaCodec instances with a central clock** for A/V
  synchronisation, and an **OpenGL ES renderer** that composites multiple video sources, still images and
  rendered titles into output frames.
- Video and audio effects were recreated by hand: custom **GLSL shaders** for video, rewritten audio filters,
  and decoders optimised per platform.
- A priority was **conforming video**: whatever resolution, frame rate, file format or colour space the user
  imports, the result must look right.
- UI in **Kotlin**; windowed layouts and panels adapted to many screen sizes. More than half of new Android
  downloads are on tablets and Chromebooks, with about 71 % more usage time on large screens.
- Tested over three months with 12,000+ beta users on premium and budget devices, using Firebase Crashlytics.
- Unknown from public sources: native versus JVM split, frame cache design, audio engine (Oboe or other),
  thumbnail and waveform pipelines, export pipeline specifics.

## 3. How ultimateVE compares

The architecture is very close to what LumaFusion for Android describes: MediaCodec decoders with an audio
master clock, a GLES compositor, GLSL effects, Kotlin UI. Our differences: a C++ native engine behind JNI,
zero-copy `AHardwareBuffer` frames with an LRU cache, a timeline drawn natively on a `SurfaceView`, and MVI.

| Area | LumaFusion | ultimateVE today |
|---|---|---|
| Track count | 6 video + 6 audio (device-dependent) | Unlimited in the model; decoder limit caps simultaneous video layers (max 4 decoded at once) |
| Magnetic timeline | Yes | Base track is magnetic; overlays follow it |
| Multiselect, tags, notes | Yes | Single selection; markers only |
| Keyframes | Unlimited; effects, audio levels, pan, EQ | Position, scale, rotation, opacity (linear/ease/hold); effect parameters and audio not keyframed |
| Speed ramps | Yes (paid) | Yes, plus reverse and freeze |
| Colour correction | Full tools, scopes, looks | Basic adjustments and chroma key; no scopes, no curves/wheels |
| LUTs | `.cube` / `.3dl`, several | In progress (`.cube`) |
| HDR | Yes | HLG pipeline and HEVC Main10 export; per-clip colour override in progress |
| Stabiliser | Yes | No |
| Titles | Multilayer: text + shapes + images, custom fonts | Single text with style, templates, stickers as separate clips; no custom fonts |
| Audio | EQ, voice isolation, ducking, pan, keyframes | Gain per clip, speed varispeed; no EQ, pan, ducking |
| Multicam | Paid add-on | No |
| Export | Presets, FCPXML (paid) | H.264/HEVC MP4, upload presets; no XML export |
| Plugins | Third-party (iOS) | No |
| Media sources | Device, USB-C, cloud, Frame.io, stock library | SAF picker (device, USB, cloud via providers) |
| Frame rates | 18-240 fps | Rational frame rates; presets 23.976-60 fps |
| Captions | Not a headline feature | On-device captions with animated styles |
| Beat sync | Not a headline feature | Beat markers, cut to beat |

## 4. Suggested priorities derived from the comparison

1. **Colour tools**: scopes (waveform, histogram), basic colour wheels/curves, and the LUT work in progress.
   This is the largest gap for a "pro" feel and matches the user's HDR/SDR mixing need.
2. **Audio tools**: pan, keyframed volume, a simple EQ, auto-ducking and noise/voice clean-up.
3. **Multiselect** and bulk operations (apply transition to many clips, group move, copy/paste attributes).
4. **Keyframes on effect parameters and audio levels.**
5. **Titles with several layers** (text + shape + image) and custom fonts.
6. **Stabiliser** (CPU/GPU optical flow or gyro-based if the footage carries metadata).
7. **Project interchange**: share a project with its media list; FCPXML or EDL export.
8. **Multicam sync** (audio waveform correlation) once audio tooling exists.
9. A **media library** view with tags, find-in-timeline and find-in-library, mirroring LumaFusion 2.5.

## Sources

- LumaFusion for iOS, App Store listing: https://apps.apple.com/us/app/lumafusion/id1062022008
- LumaTouch, LumaFusion for ChromeOS and Android: https://luma-touch.com/lumatouch-releases-lumafusion-for-chromeos-and-android/
- Chrome OS developers, how LumaFusion was built: https://chromeos.dev/en/stories/lumafusion
- CineD, LumaFusion for Android 2.5 (APV, head/tail transitions): https://www.cined.com/lumafusion-for-android-2-5-brings-head-and-tail-transitions-multi-clip-editing-and-apv-codec-support/
- CineD, LumaFusion 5.3 plugin architecture: https://www.cined.com/lumafusion-5-3-opens-doors-to-third-party-plugins-with-new-architecture-and-coremelt-partnership/
- 9to5Google, Android release and price: https://9to5google.com/2022/11/09/lumafusion-android/

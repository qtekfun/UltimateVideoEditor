# FFmpeg software-decode fallback

Status: **built, optional, off by default.** Verified in CI only (host tests against a real libav, the pinned static build,
and the engine linked against it); **not yet run on a device.** Written 2026-10.

## What it is for

`MediaCodec` is the only hardware decode path. It cannot open every file a creator may import. A phone has no decoder
for ProRes (iPhone ProRes clips), DNxHD/DNxHR, MPEG-2 and MPEG-4 part 2 (old camcorders, screen recorders), MJPEG, VC-1/WMV,
or for audio such as AC-3/E-AC-3, ALAC, Vorbis in unusual containers or WMA. With the fallback built in, such a file opens
anyway, decoded on the CPU, instead of failing with "this clip cannot be previewed".

H.264 and HEVC 4:2:0 stay on MediaCodec, VP9 and AV1 too (the platform decodes them in software since Android 12; a phone without
an AV1 decoder, or a stream its decoder refuses, falls back to the bundled libdav1d). The fallback is tried **only** when MediaCodec fails to open the stream for a reason software can fix (unsupported format, no decoder,
codec error, the container could not be parsed) **and** this build contains FFmpeg. Otherwise the typed MediaCodec error is
reported, with a hint when the cause is that FFmpeg is not included.

It is off by default because it is not needed for the formats the project's author uses, and it adds about 7.6 MB of native
code (about 1,1 MB more for AV1, see below). Default builds, CI and APK size are unchanged.

## How to enable it

1. Get the static libraries. Do **not** build them on a small laptop (a minimal build is still a long compile): run the manual
   workflow `FFmpeg fallback` on GitHub (`Actions` > `FFmpeg fallback` > `Run workflow`) and download the artifact
   `ffmpeg-android-arm64`. Or, on a machine with the NDK r29 and time: `scripts/build-ffmpeg-android.sh [output dir]`.
2. Build the app against it:

   ```
   ./gradlew :app:assembleDebug -Puveditor.ffmpeg=/path/to/ffmpeg-android   # the directory with include/ and lib/
   ```

   `cmake/ffmpeg.cmake` checks that `include/libavcodec/avcodec.h` and the five `lib*.a` exist (a clear error otherwise) and
   links them statically, with their symbols kept private to `libuveditor_engine.so`.
3. Open a file MediaCodec cannot decode. The editor shows "Software decoding: this video format is not supported by the
   phone's decoder, so it is decoded on the CPU and may play slower", and, when the stream is too heavy for real time, the
   existing proxy suggestion appears.

The app never downloads anything at run time. FFmpeg's source is fetched only by the build script, pinned by version and
SHA-256.

## What is built

`scripts/build-ffmpeg-android.sh` configures FFmpeg **8.1.3** (`ffmpeg-8.1.3.tar.xz`, SHA-256
`7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3`) for arm64-v8a, Android 33, NDK r29:
`--disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network --disable-avfilter
--disable-avdevice --enable-static --disable-shared --enable-pic`, only the libraries avcodec, avformat, avutil, swscale and
swresample, protocol `file` only, no hardware acceleration, no GPL or nonfree component. The script aborts if the resulting
`config.h` is not `LGPL version 2.1 or later`, or if the configured FFmpeg has no libdav1d decoder.

dav1d **1.5.4** (`dav1d-1.5.4.tar.xz` from downloads.videolan.org, SHA-256
`686616b7c69eb88d44459391ab25cac13b6647a3b288835c5784e71c1514a5c5`, BSD-2-Clause) is built first with meson and ninja, cross-compiled
with the same NDK toolchain (arm64 with its NEON assembly, static, PIC, release, 8 and 10/12-bit, no tools or tests), and FFmpeg's
`configure` finds it through `pkg-config` with `PKG_CONFIG_LIBDIR` pointing only at that install (never a libdav1d of the build
machine). `libdav1d.a` is copied next to FFmpeg's libraries and `cmake/ffmpeg.cmake` links it after libavcodec when it is there (an
older artifact without it still links, it just has no AV1).

- **Video decoders:** libdav1d (AV1), h264, hevc, mpeg4, mpeg2video, mjpeg, prores, dnxhd, theora, vp8, vp9, vc1, wmv1/2/3, msmpeg4 v1-v3,
  h263/h263p, flv, svq1, svq3, cinepak, msvideo1, rawvideo, ffv1, huffyuv, utvideo.
- **Audio decoders:** aac, aac_latm, mp2, mp3, opus, vorbis, flac, alac, ac3, eac3, truehd, dca, wmav1/2, wmapro, amrnb/wb, and
  the PCM variants (s16/s24/s32/f32/f64/u8/alaw/mulaw).
- **Demuxers:** mov, matroska, mpegts, mpegps, avi, asf, flv, ogg, mp3, wav, flac, aac, ac3, eac3, h264, hevc, m4v, mpegvideo,
  mxf, amr, rm, ivf, dts, yuv4mpegpipe. Parsers for the matching codecs (AV1 included).

The workflow `.github/workflows/ffmpeg.yml` builds it (about a minute and a half on a runner), reports the sizes, uploads the
libraries and headers as an artifact (nothing binary is committed), then compiles and links the engine against them.

**Measured sizes (CI, stripped static libraries, arm64):**

| Library | Size |
|---|---|
| libavcodec.a | 7,45 MB |
| libavformat.a | 1,30 MB |
| libswscale.a | 1,54 MB |
| libavutil.a | 1,11 MB |
| libswresample.a | 0,15 MB |
| libdav1d.a | 1,20 MB |
| **total** | **12,8 MB** (archives; only what the engine uses is linked) |

`libuveditor_engine.so` (debug, arm64, symbols stripped): **2,67 MB without FFmpeg; 10,26 MB with FFmpeg alone (+7,6 MB, measured
in CI before dav1d) and 11,36 MB with FFmpeg and dav1d (+8,7 MB, measured on a local build of this script).**

## How it plugs in

- `decode/video_decoder_api.h`: the abstract `IVideoDecoder` that the preview engine and the exporter use. `VideoDecoder`
  (MediaCodec) and `ffmpeg::FfmpegDecoder` implement it. `AssetInfo` gained `software` and `proxyAdvised`.
- `decode/open_decoder.cpp`: `openVideoDecoder()` tries MediaCodec, then software. The decision is the pure function in
  `decode/decoder_selection.h` (also the typed error messages and the CPU budget).
- `decode/ffmpeg/software_reader.*`: libavcodec/libavformat over a `pread()` file-descriptor reader (no FFmpeg protocol, so
  several readers can use duplicates of one descriptor). Frame indices are exact: pts to frame is integer arithmetic with 128-bit
  intermediates, round half up (`decode/ffmpeg/ts_math.h`). A seek goes to the previous key frame; when the demuxer lands
  after the wanted frame (open GOPs) it retries further back, geometrically, and also when a seek ends before any picture.
- `decode/ffmpeg/ffmpeg_decoder.*`: a worker thread with the same window (look-behind / look-ahead) and the same seek policy as
  the MediaCodec decoder (`decode/seek_policy.h`, planned by `decode/ffmpeg/software_policy.h`), converting each wanted picture
  with swscale into an RGBA8 `AHardwareBuffer` from a small pool and handing it to the render thread through `drainImages()`,
  so the frame cache, colour shader, effects and exporter are untouched.
- `audio/ffmpeg_pcm_decoder.cpp`: a `PcmDecoder` (interleaved stereo float at the stream rate, proper downmix with
  libswresample), used when the platform cannot decode the audio: the mixer, loudness and noise measurements, and the waveform.
- The colour transfer, the display-matrix rotation and the frame rate (average rate, snapped to exact broadcast rationals,
  or the override) come from the container and feed the same per-asset paths the MediaCodec decoder uses.
- CPU budget: libavcodec threads = half the cores, between 1 and 4; a stream above 1080p30 pixel rate is flagged as not real
  time, its look-ahead is reduced and a proxy is advised.

## Limits

- **Slower.** Software decode of 4K (or 1080p60) ProRes or HEVC 4:2:2 will not play in real time on a phone. Scrubbing is fine
  once frames are cached; export decodes off screen and is simply slower. The editor says so and suggests a proxy.
- **RGBA8 loses HDR precision.** Frames are converted to 8-bit RGBA. A 10-bit source (ProRes 422 HQ, HEVC Main10 4:2:2) loses
  precision, and HLG/PQ gradients may band. The MediaCodec path keeps 10 bits. A 10-bit RGB path (`AHARDWAREBUFFER_FORMAT_R10G10B10A2`
  or half-float through swscale) would fix it; not done.
- **AV1 is decoded by libdav1d**, only when MediaCodec cannot open the stream. FFmpeg's own AV1 decoder needs a hardware
  accelerator and libaom is slow and larger, so dav1d is the only software AV1 decoder in the build. 4K AV1 will not play in real
  time on the CPU of a phone (the same proxy advice as for other heavy codecs applies); 10-bit AV1 is converted to RGBA8 like
  every software frame. There is no AV1 encoder (and no export in AV1). dav1d's own threading is not used: libavcodec's frame
  threads (half the cores, at most 4) drive it.
- **Audio** that the platform decodes is never routed to the software path; only a failure to open it is.
- **Not yet on a device.** The `AHardwareBuffer` upload, the preview/export path and the notice are covered by compile, link and
  host tests only.

## Licence

FFmpeg is configured LGPL-2.1+ (no `--enable-gpl`, no `--enable-nonfree`) and linked statically into the GPL-3.0 app, which the
LGPL allows. FFmpeg is **not** covered by the project's own licence: see `THIRD_PARTY_NOTICES.md` for the version, the exact
configure options and where to get the corresponding source.

## Tests

- `app/src/test/cpp/ffmpeg_selection_tests.cpp` (always, no libav, in `scripts/run-native-tests.sh` and CMake): routing and error
  messages, CPU budget, exact timestamp maths, and the worker's seek/decode planning driven by a fake reader (sequential
  playback over a long GOP seeks once; jumps; recover).
- `app/src/test/cpp/ffmpeg_reader_tests.cpp` via `scripts/run-ffmpeg-host-tests.sh` (needs libav dev files and the ffmpeg CLI;
  CI job "FFmpeg host tests"): generated MPEG-2 (short and long GOP), MPEG-4, ProRes, H.264, 29.97 fps, frame-rate override, AAC
  and AC-3: exact indices, seeking to targets inside a GOP, end of stream, the tone's frequency and an exact audio seek.
- The reader tests also open an AV1 clip (libaom makes it, the distribution's libav decodes it with libdav1d), when the encoder
  exists on the machine.
- Still to do on a device: open a ProRes file from the phone, scrub, export, and compare with the same file transcoded to H.264.

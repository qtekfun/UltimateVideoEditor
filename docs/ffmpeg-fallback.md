# FFmpeg software-decode fallback — evaluation and design

Status: **designed, not built.** Written 2026-10; measured numbers are marked, everything else is an estimate.

## What it would be for

`MediaCodec` (via `decode/video_decoder.cpp`) is the only decode path. It fails to open, or fails to decode, files whose codec the
device has no decoder for. A fallback would only run when `VideoDecoder::open` fails with `UnsupportedFormat`. Typical cases
on a phone: ProRes (iPhone ProRes clips), DNxHD/DNxHR, MPEG-2 and MPEG-4 part 2 (old camcorder and screen-recorder files),
HEVC 4:2:2 or 4:4:4, MJPEG and VC-1/WMV. H.264, HEVC 4:2:0 (8 and 10 bit), VP9 and AV1 (software in the platform since
Android 12) already work through MediaCodec and stay on it.

It is not needed for the MVP and no format the author uses today requires it, which is why it is deferred.

## Getting FFmpeg for arm64 — options compared

| Option | Verdict |
|---|---|
| **ffmpeg-kit** (the usual Android wrapper) | The GitHub project is archived and its Maven artifacts are retired; do not depend on it. |
| **Media3 FFmpeg extension** | Audio decoders only and it must be built from FFmpeg sources by the app anyway. Not useful for video. |
| **`org.bytedeco:ffmpeg` prebuilt jars** (Maven Central, e.g. `8.1.2-1.5.14`) | A real prebuilt option. Measured (`…-android-arm64.jar`, the `-gpl` variant is 23 MB): 20.6 MB compressed, 52.6 MB unpacked; `libavcodec.so` alone is 27 MB, `libavformat.so` 11 MB, `libavfilter.so` 6.5 MB, `libswscale.so` 1.2 MB, `libswresample.so` 0.1 MB, `libavutil.so` 0.7 MB, plus ~5 MB of JavaCPP `libjni*.so` we would not use. They are full builds (every codec), shared libraries, and carry **no headers** (those come from the FFmpeg source tag). Easy to use, but adds roughly **+20 MB to the APK** (arm64 only) for a feature almost nobody needs. |
| **Build a minimal static FFmpeg ourselves** | Best result: with `--disable-everything` and only the wanted decoders/parsers/demuxers, `libavcodec`+`libavformat`+`libavutil`+`libswscale`+`libswresample` come to roughly **3–6 MB** stripped (estimate; a build must confirm it). Cost: a build recipe to maintain. |

### Recommended path (when this is built)

Build the minimal static FFmpeg **in GitHub Actions**, never on the laptop (a configure+make of the minimal set takes on the
order of 10–25 minutes per ABI with the NDK toolchain; the laptop is memory-constrained). The workflow publishes the
`libav*.a` + headers as a release/CI artifact; the app build downloads a pinned, checksummed archive into
`app/src/main/cpp/third_party/ffmpeg/` (git-ignored). Nothing large is committed.

Recipe sketch (NDK r29, `aarch64-linux-android33`, FFmpeg release branch pinned by tag):

```
./configure --target-os=android --arch=aarch64 --enable-cross-compile \
  --cc=$NDK/…/aarch64-linux-android33-clang --sysroot=$NDK/…/sysroot \
  --disable-everything --disable-programs --disable-doc --disable-network --disable-autodetect \
  --enable-static --disable-shared --enable-small --enable-pic \
  --enable-avcodec --enable-avformat --enable-avutil --enable-swscale --enable-swresample \
  --enable-decoder=prores,dnxhd,mpeg2video,mpeg4,mjpeg,vc1,wmv3,hevc,h264 \
  --enable-demuxer=mov,matroska,mpegts,mpegps,avi,asf \
  --enable-parser=h264,hevc,mpegvideo,mpeg4video,mjpeg,vc1 \
  --enable-protocol=file
```

`--disable-autodetect` avoids pulling system libs; no `--enable-gpl`/`--enable-nonfree` is needed for these decoders (they
are LGPL), so the library stays LGPL-2.1+ and the app, which is GPL-3.0, may link it statically. If a GPL-only component were
ever added, GPL-3.0 still allows it; record the exact configure line in `THIRD_PARTY_NOTICES.md` either way, ship the
corresponding source/patch references, and note that FFmpeg is not covered by the project's own licence.

## Where it plugs in

The pipeline already has the right seam: `VideoDecoder::drainImages(fn)` hands the render thread an `AHardwareBuffer` per
decoded frame, and everything after that (frame cache keyed by (asset, frame), look-ahead window, EGLImage import, colour
shader, effects, export) is codec-agnostic.

1. **Interface.** Extract the public surface of `VideoDecoder` (`info`, `setTarget`, `setWindow`, `drainImages`, `markResolved`,
   `isUnavailable`, `recover`, `describe`, `shutdown`) into an abstract `IVideoDecoder`; `VideoDecoder` (MediaCodec) and a new
   `FfmpegDecoder` implement it. `PreviewEngine` and the exporter hold `std::shared_ptr<IVideoDecoder>`.
2. **Selection.** `openDecoder(fd, …)` tries MediaCodec first; only if it returns `UnsupportedFormat` (or the codec fails to
   configure) and the build flag `UV_FFMPEG_FALLBACK` is on does it open `FfmpegDecoder`. A project file that needs it shows a
   "decoded in software" badge on the clip, because it will be slower.
3. **Frames.** `FfmpegDecoder` runs its own worker thread: `avformat` seek to the previous keyframe → `avcodec` decode →
   `swscale` to `RGBA8` (or to NV12 and let the existing path treat it) written straight into a CPU-writable
   `AHardwareBuffer` (`AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN | GPU_SAMPLED_IMAGE`) taken from a small pool. The buffer then flows
   through `drainImages` exactly like a MediaCodec image, so the cache and render path are untouched. The window/eviction logic
   needs the same `frame → pts` mapping the MediaCodec decoder uses (integer frames, rational fps).
4. **Colour.** The container's colour metadata is read from `AVStream.codecpar` (`color_trc`, `color_primaries`) and fed into
   the same per-asset transfer that the MediaCodec path reports (`AssetInfo.colorTransfer`), so the per-clip colour override works.
5. **Audio.** Out of scope for the first version: the waveform and audio engines use `AMediaExtractor`/`AMediaCodec`, so a
   file whose *audio* codec is unsupported would also need `avcodec` audio decode into the existing PCM paths.
6. **Errors.** Typed (`Status::UnsupportedFormat`, `DecodeFailed`) with codec name and stage, surfaced like the MediaCodec ones.
7. **Performance expectation.** Software decode of 4K ProRes/HEVC 4:2:2 will not play in real time on a phone; the cache and
   look-ahead make scrubbing usable at 1080p. The export path can decode off-screen and is simply slower. This is acceptable for
   a fallback and must be stated in the UI.

## Tests

- Host: a fake `IVideoDecoder` for the selection logic (MediaCodec unsupported → fallback, flag off → typed error).
- Host (CI only, because it needs the built library): decode a tiny generated ProRes and MPEG-2 clip and compare the frame
  hash; seek accuracy at GOP boundaries.
- Device: open a ProRes file from the phone, scrub, export, and compare with the same file transcoded to H.264.

## Effort and decision

About 1–2 weeks of focused work (CI build recipe, decoder interface refactor, `FfmpegDecoder`, tests, device verification),
plus APK size and licence housekeeping. It cannot be validated without a device and test clips, so it was not built blind.
**Decision: keep it deferred; build the CI recipe first when a real file needs it** (a ProRes/MPEG-2 clip failing to open is
the trigger), then the decoder.

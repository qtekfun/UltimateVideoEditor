# Third-party notices

ultimateVE is licensed under the GNU General Public License v3.0 (see `LICENSE`). It includes
the following third-party software and data. Each is used under a licence that is compatible with GPL-3.0
distribution.

## Oboe (Apache-2.0)

Low-latency audio output uses [Oboe](https://github.com/google/oboe) from Maven (`com.google.oboe:oboe`),
licensed under the Apache License 2.0.

## AndroidX, Jetpack Compose, Kotlin and kotlinx libraries (Apache-2.0)

The app is built with the Android Jetpack libraries, Kotlin, kotlinx.coroutines and kotlinx.serialization, all
licensed under the Apache License 2.0.

## Licence compatibility

MIT and Apache-2.0 components may be combined into a GPL-3.0 work (Apache-2.0 is compatible with GPL-3.0, not
with GPL-2.0-only). The combined work is distributed under GPL-3.0.

## FFmpeg 8.1.3 (LGPL-2.1-or-later), optional

When the app is built with `-Puveditor.ffmpeg=<dir>` (off by default: see `docs/ffmpeg-fallback.md`), it links a static, minimal
build of [FFmpeg](https://ffmpeg.org) 8.1.3 (libavcodec, libavformat, libavutil, libswscale, libswresample) into
`libuveditor_engine.so`, to decode media the platform cannot. Release builds that do not use that flag contain no FFmpeg code.

- **Licence:** GNU Lesser General Public License, version 2.1 or (at your option) any later version. FFmpeg is configured without
  `--enable-gpl` and without `--enable-nonfree`; `scripts/build-ffmpeg-android.sh` aborts if the configured library reports
  anything other than "LGPL version 2.1 or later". The LGPL permits static linking into this GPL-3.0 application. FFmpeg itself is
  **not** covered by the project's licence, and its copyright belongs to the FFmpeg developers.
- **Source:** the unmodified release tarball `https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz`
  (SHA-256 `7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3`). No patches are applied. The corresponding source
  and the build recipe are this repository's `scripts/build-ffmpeg-android.sh` plus that tarball.
- **Configure options** (arm64-v8a, Android 33, NDK r29): `--target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile
  --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network --disable-debug --disable-avdevice
  --disable-avfilter --enable-static --disable-shared --enable-pic --enable-avcodec --enable-avformat --enable-avutil
  --enable-swscale --enable-swresample --enable-protocol=file`, plus `--enable-decoder`, `--enable-demuxer` and `--enable-parser`
  with the lists in the script (video: h264, hevc, mpeg4, mpeg2video, mjpeg, prores, dnxhd, theora, vp8, vp9, vc1, wmv, h263, flv,
  svq, cinepak, ffv1 and a few others; audio: aac, mp3, opus, vorbis, flac, alac, ac3, eac3, truehd, dca, wma, amr and PCM).
- **Replacing the library:** because it is linked statically, a user who wants to use a modified FFmpeg rebuilds the app from this
  repository with `-Puveditor.ffmpeg` pointing at their own build (the LGPL relinking right).
- AV1 is not included. If libdav1d (BSD-2-Clause) is added later, its notice goes here too.


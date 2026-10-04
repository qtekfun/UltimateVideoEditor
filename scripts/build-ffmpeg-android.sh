#!/usr/bin/env bash
# Builds a minimal, static, LGPL FFmpeg for arm64-v8a with the Android NDK: the software-decoding fallback
# (docs/ffmpeg-fallback.md). Reproducible: the FFmpeg release and its SHA-256 are pinned below.
#
# Do not run this on a small laptop: configure + make of even this minimal set takes 10-25 minutes. CI does it
# (.github/workflows/ffmpeg.yml) and publishes the result as an artifact; to use a result locally:
#
#   ./gradlew :app:assembleDebug -Puveditor.ffmpeg=/path/to/ffmpeg-android   # the directory with include/ and lib/
#
# Usage: scripts/build-ffmpeg-android.sh [output directory (default: build/ffmpeg-android)]
# Environment:
#   ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_HOME   where the NDK is (r29, 29.0.14206865, expected)
#   FFMPEG_CACHE             directory for the downloaded source tarball (default: <output>/download)
#   FFMPEG_CONFIGURE_ONLY=1  stop after `configure` and the licence check (validates the option list, no compile)
#   JOBS                     make parallelism (default: all cores)
set -euo pipefail

FFMPEG_VERSION="8.1.3"
FFMPEG_SHA256="7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
ANDROID_API=33   # the app's minSdk
NDK_VERSION="29.0.14206865"

root="$(cd "$(dirname "$0")/.." && pwd)"
out="${1:-$root/build/ffmpeg-android}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
cache="${FFMPEG_CACHE:-$out/download}"
mkdir -p "$cache"

ndk="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/$NDK_VERSION}}"
toolchain="$ndk/toolchains/llvm/prebuilt/linux-x86_64"
[ -x "$toolchain/bin/aarch64-linux-android${ANDROID_API}-clang" ] || { echo "NDK toolchain not found under $ndk" >&2; exit 2; }

# ---- source: pinned version and checksum ---------------------------------------------------------------------
tarball="$cache/ffmpeg-${FFMPEG_VERSION}.tar.xz"
if [ ! -f "$tarball" ]; then
    echo "Downloading $FFMPEG_URL"
    curl -fsSL -o "$tarball.part" "$FFMPEG_URL"
    mv "$tarball.part" "$tarball"
fi
echo "${FFMPEG_SHA256}  ${tarball}" | sha256sum -c -

src="$out/src/ffmpeg-${FFMPEG_VERSION}"
if [ ! -d "$src" ]; then
    mkdir -p "$out/src"
    tar -xf "$tarball" -C "$out/src"
fi

# ---- what is included ----------------------------------------------------------------------------------------
# Formats the platform's MediaCodec/MediaExtractor path cannot open (it only takes H.264/HEVC in MP4/MKV-like
# containers), plus the audio codecs the platform lacks or handles badly. AV1 is left out on purpose: it needs
# libdav1d/libaom (BSD, built separately) and the platform decodes AV1 itself since Android 12. See
# docs/ffmpeg-fallback.md for the rationale and how to add dav1d.
video_decoders="h264,hevc,mpeg4,mpeg2video,mpegvideo,mjpeg,prores,dnxhd,theora,vp8,vp9,vc1,wmv1,wmv2,wmv3,msmpeg4v1,msmpeg4v2,msmpeg4v3,h263,h263p,flv,svq1,svq3,cinepak,msvideo1,rawvideo,ffv1,huffyuv,utvideo"
audio_decoders="aac,aac_latm,mp2,mp2float,mp3,mp3float,opus,vorbis,flac,alac,ac3,eac3,truehd,dca,wmav1,wmav2,wmapro,amrnb,amrwb,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_f32le,pcm_f32be,pcm_f64le,pcm_u8,pcm_alaw,pcm_mulaw"
demuxers="mov,matroska,mpegts,mpegps,avi,asf,flv,ogg,mp3,wav,flac,aac,ac3,eac3,h264,hevc,m4v,mpegvideo,mxf,amr,rm,ivf,dts,yuv4mpegpipe"
parsers="h264,hevc,mpegvideo,mpeg4video,mjpeg,vc1,vp8,vp9,aac,aac_latm,mpegaudio,opus,vorbis,flac,ac3,dnxhd,h263,dca"

build="$out/build"
install="$out/install"
rm -rf "$build" "$install"
mkdir -p "$build"
cd "$build"

echo "Configuring FFmpeg ${FFMPEG_VERSION} for arm64-v8a (android${ANDROID_API}, NDK ${NDK_VERSION})"
"$src/configure" \
    --prefix="$install" \
    --target-os=android --arch=aarch64 --cpu=armv8-a --enable-cross-compile \
    --cc="$toolchain/bin/aarch64-linux-android${ANDROID_API}-clang" \
    --cxx="$toolchain/bin/aarch64-linux-android${ANDROID_API}-clang++" \
    --ar="$toolchain/bin/llvm-ar" --ranlib="$toolchain/bin/llvm-ranlib" \
    --nm="$toolchain/bin/llvm-nm" --strip="$toolchain/bin/llvm-strip" \
    --disable-everything --disable-autodetect --disable-programs --disable-doc --disable-network \
    --disable-debug --disable-avdevice --disable-avfilter \
    --enable-static --disable-shared --enable-pic \
    --enable-avcodec --enable-avformat --enable-avutil --enable-swscale --enable-swresample \
    --enable-decoder="${video_decoders},${audio_decoders}" \
    --enable-demuxer="$demuxers" \
    --enable-parser="$parsers" \
    --enable-protocol=file \
    --extra-cflags="-O3 -fPIC"

# Guard: the library must stay LGPL (no GPL components, no nonfree) so it can be linked statically into the
# GPL-3.0 app without extra obligations beyond LGPL's and so the licence notice is accurate.
if ! grep -q 'FFMPEG_LICENSE "LGPL version 2.1 or later"' config.h; then
    echo "The configured FFmpeg is not LGPL-2.1+ (check config.h):" >&2
    grep FFMPEG_LICENSE config.h >&2 || true
    exit 3
fi
echo "Licence: $(grep FFMPEG_LICENSE config.h)"

if [ "${FFMPEG_CONFIGURE_ONLY:-0}" = "1" ]; then
    echo "FFMPEG_CONFIGURE_ONLY=1: stopping after configure."
    exit 0
fi

make -j"${JOBS:-$(nproc)}"
make install

"$toolchain/bin/llvm-strip" --strip-debug "$install"/lib/*.a

# ---- the result ----------------------------------------------------------------------------------------------
{
    echo "FFmpeg ${FFMPEG_VERSION} (${FFMPEG_URL})"
    echo "sha256 ${FFMPEG_SHA256}"
    echo "NDK ${NDK_VERSION}, android${ANDROID_API}, arm64-v8a, static, $(grep FFMPEG_LICENSE config.h | sed 's/#define FFMPEG_LICENSE //')"
    echo "configure: $(grep '^FFMPEG_CONFIGURATION' config.h 2>/dev/null || grep FFMPEG_CONFIGURATION config.h)"
} > "$install/BUILDINFO.txt"
echo
echo "Library sizes (stripped):"
ls -l "$install"/lib/*.a | awk '{printf "  %10d  %s\n", $5, $9}'
du -ch "$install"/lib/*.a | tail -1
echo "Result: $install  (use with -Puveditor.ffmpeg=$install)"

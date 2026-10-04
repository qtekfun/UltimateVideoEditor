#!/usr/bin/env bash
# Builds a minimal, static, LGPL FFmpeg for arm64-v8a with the Android NDK: the software-decoding fallback
# (docs/ffmpeg-fallback.md), with libdav1d (BSD-2-Clause) for AV1. Reproducible: the FFmpeg and dav1d releases and their
# SHA-256 are pinned below.
#
# Needs on the PATH: curl, tar, xz, make, pkg-config, meson (>= 0.49) and ninja (dav1d builds with them; CI installs them).
#
# Do not run this on a small laptop: configure + make of even this minimal set takes 10-25 minutes. CI does it
# (.github/workflows/ffmpeg.yml) and publishes the result as an artifact; to use a result locally:
#
#   ./gradlew :app:assembleDebug -Puveditor.ffmpeg=/path/to/ffmpeg-android   # the directory with include/ and lib/
#
# Usage: scripts/build-ffmpeg-android.sh [output directory (default: build/ffmpeg-android)]
# Environment:
#   ANDROID_NDK_HOME / ANDROID_NDK_ROOT / ANDROID_HOME   where the NDK is (r29, 29.0.14206865, expected)
#   FFMPEG_CACHE             directory for the downloaded source tarballs (default: <output>/download)
#   FFMPEG_CONFIGURE_ONLY=1  stop after `configure` and the licence check (validates the option list, no compile)
#   JOBS                     make parallelism (default: all cores)
set -euo pipefail

FFMPEG_VERSION="8.1.3"
FFMPEG_SHA256="7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3"
FFMPEG_URL="https://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.xz"
# dav1d: the AV1 decoder (BSD-2-Clause, so it keeps the combined library LGPL-compatible). Built first, as a static library,
# and found by FFmpeg's configure through pkg-config.
DAV1D_VERSION="1.5.4"
DAV1D_SHA256="686616b7c69eb88d44459391ab25cac13b6647a3b288835c5784e71c1514a5c5"
DAV1D_URL="https://downloads.videolan.org/pub/videolan/dav1d/${DAV1D_VERSION}/dav1d-${DAV1D_VERSION}.tar.xz"
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
for tool in meson ninja pkg-config; do
    command -v "$tool" > /dev/null || { echo "$tool not found on the PATH (needed to build dav1d)" >&2; exit 2; }
done

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

# ---- dav1d ---------------------------------------------------------------------------------------------------
dav1d_tarball="$cache/dav1d-${DAV1D_VERSION}.tar.xz"
if [ ! -f "$dav1d_tarball" ]; then
    echo "Downloading $DAV1D_URL"
    curl -fsSL -o "$dav1d_tarball.part" "$DAV1D_URL"
    mv "$dav1d_tarball.part" "$dav1d_tarball"
fi
echo "${DAV1D_SHA256}  ${dav1d_tarball}" | sha256sum -c -

dav1d_src="$out/src/dav1d-${DAV1D_VERSION}"
if [ ! -d "$dav1d_src" ]; then
    mkdir -p "$out/src"
    tar -xf "$dav1d_tarball" -C "$out/src"
fi

dav1d_build="$out/dav1d-build"
dav1d_install="$out/dav1d-install"
rm -rf "$dav1d_build" "$dav1d_install"
cat > "$out/dav1d-cross.ini" <<CROSS
[binaries]
c = '$toolchain/bin/aarch64-linux-android${ANDROID_API}-clang'
cpp = '$toolchain/bin/aarch64-linux-android${ANDROID_API}-clang++'
ar = '$toolchain/bin/llvm-ar'
strip = '$toolchain/bin/llvm-strip'

[properties]
needs_exe_wrapper = true

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
CROSS

echo "Building dav1d ${DAV1D_VERSION} for arm64-v8a (android${ANDROID_API})"
# Static, PIC, optimised, with its NEON assembly; no command line tool, no tests, no examples. Both bit depths stay on
# (8 and 10 bit AV1 are both common from phones).
meson setup "$dav1d_build" "$dav1d_src" --cross-file "$out/dav1d-cross.ini" \
    --prefix "$dav1d_install" --libdir lib --buildtype release --default-library static -Db_staticpic=true \
    -Denable_tools=false -Denable_tests=false -Denable_examples=false -Denable_docs=false
ninja -C "$dav1d_build" -j"${JOBS:-$(nproc)}"
ninja -C "$dav1d_build" install
[ -f "$dav1d_install/lib/libdav1d.a" ] && [ -f "$dav1d_install/lib/pkgconfig/dav1d.pc" ] || { echo "dav1d did not install a static library and its pkg-config file" >&2; exit 4; }

# ---- what is included ----------------------------------------------------------------------------------------
# Formats the platform's MediaCodec/MediaExtractor path cannot open (it only takes H.264/HEVC in MP4/MKV-like
# containers), plus the audio codecs the platform lacks or handles badly. AV1 comes from libdav1d (built above), the only
# decoder that is both fast enough on a phone CPU and permissively licensed; FFmpeg's own AV1 decoder needs a hardware
# accelerator. See docs/ffmpeg-fallback.md.
video_decoders="libdav1d,h264,hevc,mpeg4,mpeg2video,mpegvideo,mjpeg,prores,dnxhd,theora,vp8,vp9,vc1,wmv1,wmv2,wmv3,msmpeg4v1,msmpeg4v2,msmpeg4v3,h263,h263p,flv,svq1,svq3,cinepak,msvideo1,rawvideo,ffv1,huffyuv,utvideo"
audio_decoders="aac,aac_latm,mp2,mp2float,mp3,mp3float,opus,vorbis,flac,alac,ac3,eac3,truehd,dca,wmav1,wmav2,wmapro,amrnb,amrwb,pcm_s16le,pcm_s16be,pcm_s24le,pcm_s24be,pcm_s32le,pcm_f32le,pcm_f32be,pcm_f64le,pcm_u8,pcm_alaw,pcm_mulaw"
demuxers="mov,matroska,mpegts,mpegps,avi,asf,flv,ogg,mp3,wav,flac,aac,ac3,eac3,h264,hevc,m4v,mpegvideo,mxf,amr,rm,ivf,dts,yuv4mpegpipe"
parsers="h264,hevc,av1,mpegvideo,mpeg4video,mjpeg,vc1,vp8,vp9,aac,aac_latm,mpegaudio,opus,vorbis,flac,ac3,dnxhd,h263,dca"

build="$out/build"
install="$out/install"
rm -rf "$build" "$install"
mkdir -p "$build"
cd "$build"

echo "Configuring FFmpeg ${FFMPEG_VERSION} for arm64-v8a (android${ANDROID_API}, NDK ${NDK_VERSION})"
# PKG_CONFIG_LIBDIR (not _PATH) so that only the dav1d built above is found, never a libdav1d of the build machine.
PKG_CONFIG_LIBDIR="$dav1d_install/lib/pkgconfig" "$src/configure" \
    --pkg-config="$(command -v pkg-config)" --pkg-config-flags=--static \
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
    --enable-libdav1d \
    --enable-decoder="${video_decoders},${audio_decoders}" \
    --enable-demuxer="$demuxers" \
    --enable-parser="$parsers" \
    --enable-protocol=file \
    --extra-cflags="-O3 -fPIC"

# Guard: the AV1 decoder must really be dav1d (a silent fall-back to "no AV1" would only show on a device).
if ! grep -q '#define CONFIG_LIBDAV1D_DECODER 1' config_components.h; then
    echo "The configured FFmpeg has no libdav1d decoder (check the configure log: ffbuild/config.log)" >&2
    exit 3
fi

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

# dav1d travels with FFmpeg: the engine links libdav1d.a after libavcodec.a (cmake/ffmpeg.cmake), and its licence text
# goes with the artifact.
cp "$dav1d_install/lib/libdav1d.a" "$install/lib/"
mkdir -p "$install/licenses"
cp "$dav1d_src/COPYING" "$install/licenses/dav1d-COPYING.txt"
cp "$src/COPYING.LGPLv2.1" "$install/licenses/ffmpeg-COPYING.LGPLv2.1.txt"

"$toolchain/bin/llvm-strip" --strip-debug "$install"/lib/*.a

# ---- the result ----------------------------------------------------------------------------------------------
{
    echo "FFmpeg ${FFMPEG_VERSION} (${FFMPEG_URL})"
    echo "sha256 ${FFMPEG_SHA256}"
    echo "dav1d ${DAV1D_VERSION} (${DAV1D_URL}), sha256 ${DAV1D_SHA256}, BSD-2-Clause, static"
    echo "NDK ${NDK_VERSION}, android${ANDROID_API}, arm64-v8a, static, $(grep FFMPEG_LICENSE config.h | sed 's/#define FFMPEG_LICENSE //')"
    echo "configure: $(grep '^FFMPEG_CONFIGURATION' config.h 2>/dev/null || grep FFMPEG_CONFIGURATION config.h)"
} > "$install/BUILDINFO.txt"
echo
echo "Library sizes (stripped):"
ls -l "$install"/lib/*.a | awk '{printf "  %10d  %s\n", $5, $9}'
du -ch "$install"/lib/*.a | tail -1
echo "Result: $install  (use with -Puveditor.ffmpeg=$install)"

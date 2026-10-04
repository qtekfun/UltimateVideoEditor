#!/usr/bin/env bash
# Builds and runs the FFmpeg fallback's readers against the system libav (host build, no NDK, no device).
# Needs: g++, pkg-config, the libav development packages (libavformat/libavcodec/libswscale/libswresample/libavutil)
# and the ffmpeg CLI to generate the test clips. In CI: .github/workflows/ffmpeg.yml, job "FFmpeg host tests".
#
#   sudo apt-get install -y ffmpeg libavformat-dev libavcodec-dev libswscale-dev libswresample-dev libavutil-dev pkg-config
#   scripts/run-ffmpeg-host-tests.sh
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

command -v ffmpeg >/dev/null || { echo "ffmpeg CLI not found" >&2; exit 2; }
pkg-config --exists libavcodec libavformat libswscale libswresample libavutil || { echo "libav development files not found" >&2; exit 2; }

clips="$out/clips"
mkdir -p "$clips"

# Frame N is flat grey with luma (3N mod 200) + 20, so a decoded picture identifies its own frame number.
src() { # frames, rate
    echo "color=c=black:s=320x240:r=$2,geq=lum='mod(N*3,200)+20':cb=128:cr=128"
}
has_encoder() { ffmpeg -hide_banner -encoders 2>/dev/null | grep -E "^ [A-Z.]+ +$1 " > /dev/null; }  # no grep -q: it would SIGPIPE ffmpeg under pipefail
mk() { # file, frames, rate, extra encoder arguments...
    local file="$1" frames="$2" rate="$3"
    shift 3
    ffmpeg -v error -y -f lavfi -i "$(src "$frames" "$rate")" -frames:v "$frames" "$@" "$clips/$file" || rm -f "$clips/$file"
}

# Long and short GOPs, a codec the platform does not decode (MPEG-4 part 2), an intra-only professional codec.
mk mpeg2_gop12.mpg 100 25 -c:v mpeg2video -g 12 -bf 2 -sc_threshold 1000000000 -pix_fmt yuv420p -b:v 4M
mk mpeg2_gop100.mpg 100 25 -c:v mpeg2video -g 100 -bf 2 -sc_threshold 1000000000 -pix_fmt yuv420p -b:v 4M
mk mpeg4.avi 100 25 -c:v mpeg4 -g 25 -sc_threshold 1000000000 -pix_fmt yuv420p -b:v 4M
mk prores.mov 100 25 -c:v prores_ks -profile:v 3 -pix_fmt yuv422p10le
mk ntsc.mpg 90 30000/1001 -c:v mpeg2video -g 15 -sc_threshold 1000000000 -pix_fmt yuv420p -b:v 4M
# AV1: the fallback's decoder is libdav1d; the distribution's libav decodes it with the same library. libaom makes the clip.
if has_encoder libaom-av1; then mk av1.mkv 100 25 -c:v libaom-av1 -cpu-used 8 -row-mt 1 -g 50 -b:v 0 -crf 24 -pix_fmt yuv420p; fi
if has_encoder libx264; then mk h264.mp4 100 25 -c:v libx264 -g 50 -keyint_min 50 -sc_threshold 0 -bf 2 -pix_fmt yuv420p -crf 12; fi

# Audio: a 440 Hz sine, 2 s at 48 kHz.
tone() { ffmpeg -v error -y -f lavfi -i "sine=frequency=440:sample_rate=48000:duration=2,volume=3" "$@" || true; }
tone -c:a aac -b:a 128k "$clips/aac.m4a"
tone -c:a ac3 -b:a 192k "$clips/ac3.ac3"

ls -l "$clips" >&2
pkgs="$(pkg-config --cflags --libs libavformat libavcodec libswscale libswresample libavutil)"
# shellcheck disable=SC2086
g++ -std=c++20 -Wall -Wextra -O1 -I"$root/app/src/main/cpp" \
    "$root/app/src/test/cpp/ffmpeg_reader_tests.cpp" \
    "$root/app/src/main/cpp/decode/ffmpeg/software_reader.cpp" \
    "$root/app/src/main/cpp/audio/ffmpeg_pcm_decoder.cpp" \
    -o "$out/ffmpeg_reader_tests" $pkgs -pthread
"$out/ffmpeg_reader_tests" "$clips"

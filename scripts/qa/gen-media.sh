#!/usr/bin/env bash
# Generates the synthetic media of scripts/qa-smoke.sh with ffmpeg, 4 s each, small (640x360), into <dir>
# (default /home/qtekfun/uvdata/qa-media: big files stay out of /tmp, which is RAM). Nothing here is a real recording.
#
#   avc_aac_30.mp4     H.264 + AAC, 30 fps, 440 Hz tone
#   hevc_aac_30.mp4    HEVC (hvc1) + AAC, 30 fps
#   pcm_s16le.mov      H.264 + pcm_s16le ('sowt')  \ the iPhone-style uncompressed soundtrack Android's extractor does not list:
#   pcm_s16be.mov      H.264 + pcm_s16be ('twos')  / the base track exported without sound
#   pcm_s24le.mov      H.264 + pcm_s24le ('in24')
#   vfr30.mp4          30 fps, picture timestamps jittered by +-4 ms (variable frame rate), AAC
#   vfr27.mp4          27 fps, jittered, AAC
#   cfr60.mp4          60 fps, AAC
#   hlg10.mp4          HEVC Main10, BT.2020 / ARIB STD-B67 (HLG), limited range, tagged, AAC
#   rot90.mp4          the 30 fps clip with a display matrix of 90 degrees (the container rotation the decoder applies)
#   photo.jpg, photo.png   1600x1200 pictures
#   numbers.mp4        1280x720 30 fps, every frame carries its own number in binary squares (scripts/check-retime-export.py)
#
# Idempotent: files that exist are kept (QA_REGEN=1 rebuilds them).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
dir="${1:-/home/qtekfun/uvdata/qa-media}"
mkdir -p "$dir"
size=640x360
tone="sine=frequency=440:sample_rate=48000:duration=4"

make() { # name command...
    local name="$1"
    shift
    if [ -s "$dir/$name" ] && [ "${QA_REGEN:-0}" != 1 ]; then return 0; fi
    echo "generating $name"
    "$@" "$dir/$name.part.${name##*.}"
    mv "$dir/$name.part.${name##*.}" "$dir/$name"
}
ff() { ffmpeg -v error -y "$@"; }
pic() { echo "testsrc2=size=$size:rate=$1:duration=4"; }

make avc_aac_30.mp4 ff -f lavfi -i "$(pic 30)" -f lavfi -i "$tone" -c:v libx264 -pix_fmt yuv420p -g 30 -c:a aac -b:a 96k -shortest
make hevc_aac_30.mp4 ff -f lavfi -i "$(pic 30)" -f lavfi -i "$tone" -c:v libx265 -tag:v hvc1 -x265-params log-level=error -pix_fmt yuv420p -g 30 -c:a aac -b:a 96k -shortest
for codec in pcm_s16le pcm_s16be pcm_s24le; do
    make "$codec.mov" ff -f lavfi -i "$(pic 30)" -f lavfi -i "$tone" -c:v libx264 -pix_fmt yuv420p -g 30 -c:a "$codec" -shortest
done
for rate in 30 27; do
    make "vfr$rate.mp4" ff -f lavfi -i "$(pic "$rate")" -f lavfi -i "$tone" -vf "settb=1/90000,setpts='N/($rate*TB)+0.004*sin(N*2.3)/TB'" \
        -fps_mode passthrough -enc_time_base:v 1/90000 -video_track_timescale 90000 -c:v libx264 -bf 0 -pix_fmt yuv420p -g "$rate" -c:a aac -b:a 96k -shortest
done
make cfr60.mp4 ff -f lavfi -i "$(pic 60)" -f lavfi -i "$tone" -c:v libx264 -pix_fmt yuv420p -g 60 -c:a aac -b:a 96k -shortest
make hlg10.mp4 ff -f lavfi -i "$(pic 30),format=yuv420p10le" -f lavfi -i "$tone" -c:v libx265 -pix_fmt yuv420p10le -tag:v hvc1 \
    -x265-params "colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc:range=limited:log-level=error" \
    -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc -color_range tv -g 30 -c:a aac -b:a 96k -shortest
make rot90.mp4 ff -display_rotation 90 -i "$dir/avc_aac_30.mp4" -c copy
make photo.jpg ff -f lavfi -i "testsrc2=size=1600x1200:rate=1" -frames:v 1 -q:v 3
make photo.png ff -f lavfi -i "testsrc2=size=1600x1200:rate=1" -frames:v 1
if [ ! -s "$dir/numbers.mp4" ] || [ "${QA_REGEN:-0}" = 1 ]; then
    echo "generating numbers.mp4"
    python3 "$here/../check-retime-export.py" gen "$dir/numbers.mp4" > /dev/null
fi
ls -l "$dir"

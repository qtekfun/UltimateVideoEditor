#!/usr/bin/env bash
# Checks smooth slow motion in the real preview pipeline on a device: SlowMotionPreviewInstrumentedTest draws source frame 10
# of a synthetic 60 fps clip plain and with a 0.25 / 0.5 mix towards frame 11, and this script compares those pictures with
# the 240 fps ground truth of the same motion (truth frames 40, 41 and 42, 43 = frame 10 + 1/4 and + 1/2 frame).
#   scripts/check-slowmo-preview.sh <adb-serial>
# Same prerequisites as check-slowmo-export.sh (PKG, ffmpeg, the app and androidTest APKs installed, app closed).
set -euo pipefail
serial="$1"
pkg="${PKG:-com.qtekfun.ultimatevideoeditor}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dir="/sdcard/Android/data/$pkg/files"

if adb -s "$serial" shell dumpsys window | grep -m1 mCurrentFocus | grep -q "$pkg/"; then
    echo "the app is in the foreground on $serial; not running (set FORCE=1 to override)" >&2
    [ "${FORCE:-0}" = "1" ] || exit 3
fi

# Smooth diagonal waves (periods of 100-170 px) plus a finer ripple: textured everywhere like footage, without the
# aliasing of a noise-like pattern and without hard saturated edges (those only measure the chroma artefacts of the
# video compression, which differ from frame to frame).
ffmpeg -v error -y -f lavfi -i "nullsrc=size=2400x1400,format=rgb24,geq=r='128+75*sin(X/17+Y/29)+20*sin(X/6)':g='128+75*sin(X/23-Y/19+1)+20*sin(Y/7)':b='128+75*sin((X-Y)/27+2)+20*sin((X+Y)/9)'" -frames:v 1 "$work/big.png"
gen() { # <fps> <seconds> <out>
    ffmpeg -v error -y -framerate "$1" -loop 1 -i "$work/big.png" -t "$2" \
        -vf "crop=1280:720:x='round(t*300)':y='round(t*90)',scale=out_color_matrix=bt709:out_range=tv,format=yuv420p" \
        -c:v libx264 -preset fast -crf 12 -g 30 -colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv "$3"
}
gen 60 1.0 "$work/slowmo_src.mp4"
gen 240 1.0 "$work/truth240.mp4"
adb -s "$serial" push "$work/slowmo_src.mp4" "$dir/slowmo_src.mp4" > /dev/null
adb -s "$serial" push "$work/truth240.mp4" "$dir/slowmo_truth.mp4" > /dev/null
adb -s "$serial" logcat -c
adb -s "$serial" shell am instrument -w -e class com.qtekfun.ultimatevideoeditor.engine.preview.SlowMotionPreviewInstrumentedTest \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$work/run.txt" | tail -6
grep -q "^OK" "$work/run.txt" || { echo "the instrumented run failed" >&2; adb -s "$serial" logcat -d | grep -E "uveditor|slow motion" | tail -20; exit 1; }
# The reader surface has no real alpha channel, so the pictures are read as RGBX (rgb0).
adb -s "$serial" pull "$dir/preview_info.txt" "$work/preview_info.txt" > /dev/null
cat "$work/preview_info.txt"
for f in preview_mix0 preview_mix25 preview_mix50 preview_truth40 preview_truth41 preview_truth42; do
    adb -s "$serial" pull "$dir/$f.rgba" "$work/$f.rgba" > /dev/null
    ffmpeg -v error -y -f rawvideo -pix_fmt rgb0 -s 1280x720 -i "$work/$f.rgba" -frames:v 1 "$work/$f.png"
done
psnr() { # <a> <b>
    ffmpeg -hide_banner -i "$1" -i "$2" -lavfi psnr -f null - 2>&1 | grep -o "average:[0-9.inf]*" | cut -d: -f2
}
# The truth is the app's own rendering of the 240 fps clip, so both sides share the colour pipeline and only the
# in-between frame can differ.
echo "PSNR of the preview against the preview of the 240 fps truth (dB, higher is better):"
echo "  mix 0    vs truth frame +0.00: $(psnr "$work/preview_mix0.png" "$work/preview_truth40.png")   (the plain frame: should be very high)"
echo "  mix 0    vs truth frame +0.25: $(psnr "$work/preview_mix0.png" "$work/preview_truth41.png")   (what repeating the frame shows)"
echo "  mix 0.25 vs truth frame +0.25: $(psnr "$work/preview_mix25.png" "$work/preview_truth41.png")   (interpolated)"
echo "  mix 0    vs truth frame +0.50: $(psnr "$work/preview_mix0.png" "$work/preview_truth42.png")   (what repeating the frame shows)"
echo "  mix 0.5  vs truth frame +0.50: $(psnr "$work/preview_mix50.png" "$work/preview_truth42.png")   (interpolated)"

#!/usr/bin/env bash
# Checks "Save frame as image" on a device against the exporter: renders frames of a three-clip project (plain from mid-GOP,
# reversed, 2x) with FrameDemoActivity, exports the same project, and compares each saved frame with ffmpeg's frame of the export.
#   scripts/check-frame-export.sh <adb-serial> [frames]      (default 0,37,59,60,90,119,120,130,149)
# Needs a debug build installed with `-Puveditor.appIdSuffix=sf` (set PKG for another suffix), ffmpeg/ffprobe, python3, and the
# screen unlocked. Frame numbers are read back from the picture's binary squares (scripts/check-retime-export.py gen).
set -euo pipefail
serial="$1"
frames="${2:-0,37,59,60,90,119,120,130,149}"
here="$(cd "$(dirname "$0")" && pwd)"
pkg="${PKG:-com.qtekfun.ultimatevideoeditor.sf}"
dir="/sdcard/Android/data/$pkg/files"
work="$(mktemp -d "${TMPDIR:-$HOME/uvdata}/frame-check.XXXXXX")"
trap 'rm -rf "$work"' EXIT
adb_() { adb -s "$serial" "$@"; }
activity="$pkg/com.qtekfun.ultimatevideoeditor.debug.FrameDemoActivity"

python3 "$here/check-retime-export.py" gen "$work/src.mp4"
adb_ push "$work/src.mp4" "$dir/src.mp4" > /dev/null

run() { # mode extra...
    adb_ shell rm -f "$dir/frame_result.txt"
    adb_ shell am start -n "$activity" --es video "$dir/src.mp4" --es mode "$@" > /dev/null
    for _ in $(seq 1 90); do sleep 1; adb_ shell test -f "$dir/frame_result.txt" && break; done
    sleep 1
    adb_ shell cat "$dir/frame_result.txt"
}
run frames --es frames "$frames" --es format png | tee "$work/frames.txt"
run export | tee "$work/export.txt"
grep -q "export OK" "$work/export.txt"
adb_ pull "$dir/frame_export.mp4" "$work/export.mp4" > /dev/null
IFS=, read -ra list <<< "$frames"
fail=0
for n in "${list[@]}"; do
    adb_ pull "$dir/frame_$n.png" "$work/frame_$n.png" > /dev/null
    ffmpeg -v error -y -i "$work/export.mp4" -vf "select=eq(n\,$n)" -vsync 0 -frames:v 1 "$work/ex_$n.png"
    psnr="$(ffmpeg -v info -i "$work/frame_$n.png" -i "$work/ex_$n.png" -lavfi psnr -f null - 2>&1 | grep -o 'average:[0-9a-z.]*' | tail -1)"
    echo "frame $n vs export: $psnr"
    case "$psnr" in average:inf|average:[4-9][0-9]*) ;; *) fail=1 ;; esac
done
# A frame the project shows must not be black: the harness reports the frame number read back (-1 when it cannot).
if grep -q "number=0 " "$work/frames.txt" && ! grep -q "frame 0 -> .*number=100" "$work/frames.txt"; then
    echo "a frame came out empty" >&2
    fail=1
fi
exit $fail

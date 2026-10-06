#!/usr/bin/env bash
# Export throughput breakdown on a device, through the debug harness (ExportDemoActivity), no UI.
#   scripts/export-perf.sh <serial> [options]
#     --layout single|cuts|stack|split|layers   (default single)   --w 1920 --h 1080 --fps 30 --codec avc|hevc --bitrate 12
#     --frames N (single/stack)  --n N --len N (cuts)  --k N (stack)  --source FILE (default: generated 4K H.264, 60 s, GOP 60)
#     --sync   also glFinish after each draw (separates GPU time from the encoder wait)
#     --runs N (default 2)
# Needs ffmpeg on the host (only to generate the source), the app built with `-Puveditor.appIdSuffix=<x>` and installed; set PKG
# (default com.ultimatevideo.uveditor.xp). Every run takes /tmp/pixel-device.lock (or $LOCK) and refuses to run while the app is
# in the foreground. Prints the UVExportPerf windows (ms per frame spent in fetch/decode wait, blit, draw, swap, ...), the
# thermal status and the overall fps. Does not touch other packages.
set -euo pipefail
serial="$1"
shift
layout=single w=1920 h=1080 fps=30 codec=avc bitrate=12 frames=300 n=40 len=30 k=3 source="" sync=0 runs=2
while [ $# -gt 0 ]; do
    case "$1" in
        --layout) layout="$2"; shift 2;;
        --w) w="$2"; shift 2;;
        --h) h="$2"; shift 2;;
        --fps) fps="$2"; shift 2;;
        --codec) codec="$2"; shift 2;;
        --bitrate) bitrate="$2"; shift 2;;
        --frames) frames="$2"; shift 2;;
        --n) n="$2"; shift 2;;
        --len) len="$2"; shift 2;;
        --k) k="$2"; shift 2;;
        --source) source="$2"; shift 2;;
        --sync) sync=1; shift;;
        --runs) runs="$2"; shift 2;;
        *) echo "unknown option $1" >&2; exit 2;;
    esac
done
pkg="${PKG:-com.ultimatevideo.uveditor.xp}"
dir="/sdcard/Android/data/$pkg/files"
lock="${LOCK:-/tmp/pixel-device.lock}"
if [ -z "$source" ]; then
    source=/tmp/uv_perf_src_4k.mp4
    [ -f "$source" ] || ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=3840x2160:rate=30" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
        -t 60 -c:v libx264 -preset veryfast -b:v 40M -pix_fmt yuv420p -g 60 -keyint_min 60 -sc_threshold 0 -bf 2 -c:a aac -b:a 128k "$source"
fi

one() {
    local adb="adb -s $serial"
    if $adb shell dumpsys window | grep -m1 mCurrentFocus | grep -q "$pkg/"; then
        echo "$pkg is in the foreground on $serial; not running" >&2
        return 3
    fi
    $adb shell mkdir -p "$dir"
    $adb push -q "$source" "$dir/perf_in.mp4"
    $adb shell setprop debug.uveditor.export_perf "$([ "$sync" = 1 ] && echo 2 || echo 1)"
    $adb logcat -c
    echo "thermal before: $($adb shell dumpsys thermalservice | grep -m1 'Thermal Status' || true)"
    $adb shell am start -W -n "$pkg/com.ultimatevideo.uveditor.debug.ExportDemoActivity" \
        --es video "$dir/perf_in.mp4" --es out "$dir/perf_out.mp4" --es codec "$codec" --ei w "$w" --ei h "$h" --ei fps "$fps" \
        --ei frames "$frames" --ei n "$n" --ei len "$len" --ei k "$k" --ei bitrate "$bitrate" --es layout "$layout" > /dev/null
    for _ in $(seq 1 600); do
        $adb shell "test -f $dir/perf_out.mp4.result.txt" && break
        sleep 2
    done
    $adb shell cat "$dir/perf_out.mp4.result.txt" || true
    $adb shell rm -f "$dir/perf_out.mp4.result.txt"
    echo "thermal after: $($adb shell dumpsys thermalservice | grep -m1 'Thermal Status' || true)"
    $adb logcat -d | grep -E "UVExportPerf|export done|seek:" | awk '/seek:/{s++; next} {print} END{print "seeks logged: " s+0}'
    $adb shell setprop debug.uveditor.export_perf 0
}

for r in $(seq 1 "$runs"); do
    echo "== run $r: layout=$layout ${w}x${h}@$fps $codec sync=$sync"
    export -f one
    export serial dir pkg source sync layout w h fps codec bitrate frames n len k
    flock "$lock" bash -c one
done

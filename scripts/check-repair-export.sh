#!/usr/bin/env bash
# Measures noise reduction and flicker removal on a device: adds noise and a frame-to-frame brightness flicker to a clean
# synthetic clip, exports it twice (plain and with the Denoise and Deflicker effects, RepairExportInstrumentedTest) and
# compares both with the clean clip: PSNR (higher is better) and how much the average brightness still flickers.
#   scripts/check-repair-export.sh <adb-serial>
# Same prerequisites as check-slowmo-export.sh (PKG, ffmpeg, the app and androidTest APKs installed, app closed).
set -euo pipefail
serial="$1"
pkg="${PKG:-com.ultimatevideo.uveditor}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dir="/sdcard/Android/data/$pkg/files"

if adb -s "$serial" shell dumpsys window | grep -m1 mCurrentFocus | grep -q "$pkg/"; then
    echo "the app is in the foreground on $serial; not running (set FORCE=1 to override)" >&2
    [ "${FORCE:-0}" = "1" ] || exit 3
fi

tag="-colorspace bt709 -color_primaries bt709 -color_trc bt709 -color_range tv"
ffmpeg -v error -y -f lavfi -i "nullsrc=size=2400x1400,format=rgb24,geq=r='128+75*sin(X/17+Y/29)+20*sin(X/6)':g='128+75*sin(X/23-Y/19+1)+20*sin(Y/7)':b='128+75*sin((X-Y)/27+2)+20*sin((X+Y)/9)'" -frames:v 1 "$work/big.png"
# Clean: the texture drifting slowly (1.5 px per frame). Noisy: fresh noise on every frame plus a +-6 % brightness flicker.
ffmpeg -v error -y -framerate 60 -loop 1 -i "$work/big.png" -t 1.0 \
    -vf "crop=1280:720:x='round(t*90)':y='round(t*30)',scale=out_color_matrix=bt709:out_range=tv,format=yuv420p" \
    -c:v libx264 -preset fast -crf 8 -g 30 $tag "$work/clean.mp4"
ffmpeg -v error -y -i "$work/clean.mp4" \
    -vf "noise=alls=22:allf=t+u,eq=brightness='0.06*(mod(n\,2)*2-1)':eval=frame,format=yuv420p" \
    -c:v libx264 -preset fast -crf 8 -g 30 $tag "$work/repair_src.mp4"

adb -s "$serial" push "$work/repair_src.mp4" "$dir/repair_src.mp4" > /dev/null
adb -s "$serial" logcat -c
adb -s "$serial" shell am instrument -w -e class com.ultimatevideo.uveditor.engine.export.RepairExportInstrumentedTest \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$work/run.txt" | tail -5
grep -q "^OK" "$work/run.txt" || { echo "the instrumented run failed" >&2; adb -s "$serial" logcat -d | grep -E "uveditor|effect" | tail -20; exit 1; }
for f in repair_off_out.mp4 repair_on_out.mp4 repair_times.txt; do
    adb -s "$serial" pull "$dir/$f" "$work/$f" > /dev/null
done
echo "--- export times (ms)"
cat "$work/repair_times.txt"

stat() { # <label> <video> : PSNR against the clean clip and the std of the per-frame mean luma
    echo "--- $1"
    if [ "$2" != "$work/clean.mp4" ]; then
        ffmpeg -hide_banner -i "$2" -i "$work/clean.mp4" -lavfi psnr -f null - 2>&1 | grep -E "PSNR" | sed 's/^.*\] //'
    fi
    python3 - "$2" <<'EOF'
import statistics, subprocess, sys
out = subprocess.run(
    ["ffmpeg", "-hide_banner", "-v", "info", "-i", sys.argv[1], "-vf", "signalstats,metadata=print:key=lavfi.signalstats.YAVG", "-f", "null", "-"],
    capture_output=True, text=True).stderr
values = [float(line.split("=")[1]) for line in out.splitlines() if "YAVG" in line]
steps = [abs(b - a) for a, b in zip(values, values[1:])]
print(f"mean luma flicker: average frame-to-frame step {statistics.mean(steps):.3f} (of 255), {len(values)} frames")
EOF
}
stat "noisy source, no repair (as exported)" "$work/repair_off_out.mp4"
stat "noise reduction + flicker removal" "$work/repair_on_out.mp4"
stat "clean reference" "$work/clean.mp4"

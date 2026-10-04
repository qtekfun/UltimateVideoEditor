#!/usr/bin/env bash
# Measures smooth slow motion on a device: exports a 60 fps clip at 0.25x with frame repetition and with
# optical-flow interpolation (SlowMotionExportInstrumentedTest), then compares both with a 240 fps ground-truth render of
# the same synthetic motion and reports the PSNR/SSIM of each, the judder (how unevenly consecutive frames differ) and the
# export times.
#   scripts/check-slowmo-export.sh <adb-serial>
# Needs ffmpeg on the host and the app plus its androidTest APK installed (PKG = the application id, default
# com.ultimatevideo.uveditor; for a -Puveditor.appIdSuffix=sm build use PKG=com.ultimatevideo.uveditor.sm). Never run it while
# the app is open on the device: `am instrument` restarts the process. Uses /tmp/pixel-device.lock if it exists as a lock.
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

# A textured picture scrolled diagonally: 300 px/s horizontally and 90 px/s vertically, at 60 fps and at 240 fps.
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
adb -s "$serial" logcat -c
adb -s "$serial" shell am instrument -w -e class com.ultimatevideo.uveditor.engine.export.SlowMotionExportInstrumentedTest \
    "$pkg.test/androidx.test.runner.AndroidJUnitRunner" | tee "$work/run.txt" | tail -5
grep -q "^OK" "$work/run.txt" || { echo "the instrumented run failed" >&2; adb -s "$serial" logcat -d | grep -E "uveditor|slow motion" | tail -20; exit 1; }
for f in slowmo_off_out.mp4 slowmo_on_out.mp4 slowmo_times.txt; do
    adb -s "$serial" pull "$dir/$f" "$work/$f" > /dev/null
done

echo "--- export times (ms)"
cat "$work/slowmo_times.txt"

# Output frame k shows source time (5 + k/4) / 60 s = frame 20 + k of the 240 fps truth.
for name in slowmo_off slowmo_on; do
    echo "--- $name"
    # The truth keeps its frames but gets the output's 60 fps timestamps so the comparison pairs frame k with frame k.
    ffmpeg -v error -y -i "$work/truth240.mp4" -vf "select=gte(n\,20),setpts=N/(60*TB)" -r 60 -frames:v 160 -c:v libx264 -crf 10 -pix_fmt yuv420p "$work/truth_ref.mp4"
    ffmpeg -hide_banner -i "$work/${name}_out.mp4" -i "$work/truth_ref.mp4" -lavfi "[0:v][1:v]psnr;[0:v][1:v]ssim" -f null - 2>&1 | grep -E "PSNR|SSIM" | sed 's/^.*\] //'
    python3 - "$work/${name}_out.mp4" <<'EOF'
import statistics, subprocess, sys
out = subprocess.run(
    ["ffmpeg", "-hide_banner", "-v", "info", "-i", sys.argv[1], "-vf",
     "tblend=all_mode=difference,signalstats,metadata=print:key=lavfi.signalstats.YAVG", "-f", "null", "-"],
    capture_output=True, text=True).stderr
values = [float(line.split("=")[1]) for line in out.splitlines() if "YAVG" in line]
values = values[1:]  # the first difference has no predecessor
mean = statistics.mean(values)
print(f"judder (std/mean of frame-to-frame difference): {statistics.pstdev(values) / mean:.3f}  (mean difference {mean:.3f}, {len(values)} frames)")
EOF
done

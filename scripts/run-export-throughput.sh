#!/usr/bin/env bash
# Measures export throughput (frames/s and x real time) on a device with ExportThroughputInstrumentedTest.
#   scripts/run-export-throughput.sh <adb-serial> [scenario ...]
# Scenarios: longgop1 longgop2 shortgop1 shortgop2 (GOP of one key frame vs 30 frames; 1 or 2 layers).
# Needs the app and the androidTest APK installed (./gradlew :app:assembleDebug :app:assembleDebugAndroidTest, then
# adb install -r / adb install -r -t) and ffmpeg on the host. `am instrument` restarts the app process, so every
# scenario takes /tmp/pixel-device.lock (shared by the agents using the debug phone) and refuses to run while the
# app is in the foreground (FORCE=1 overrides).
set -euo pipefail
serial="$1"
shift
scenarios=("$@")
[ ${#scenarios[@]} -gt 0 ] || scenarios=(shortgop1 longgop1 longgop2)
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
# PKG is the application id of the installed build (default; with -PappIdSuffix=.x it is com.qtekfun.ultimatevideoeditor.x).
pkg="${PKG:-com.qtekfun.ultimatevideoeditor}"
dir="/sdcard/Android/data/$pkg/files"

gen() { # name gop
    [ -f "/tmp/uv_tp_$1.mp4" ] || ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1920x1080:rate=30" -t 15 \
        -c:v libx264 -preset veryfast -b:v 20M -pix_fmt yuv420p -g "$2" -keyint_min "$2" -sc_threshold 0 -bf 2 "/tmp/uv_tp_$1.mp4"
}
gen long 9999
gen short 30

run_one() {
    local scenario="$1" kind layers
    kind="${scenario%[0-9]}"
    layers="${scenario: -1}"
    if adb -s "$serial" shell dumpsys window | grep -m1 mCurrentFocus | grep -q "$pkg/"; then
        echo "the app is in the foreground on $serial; not running $scenario (FORCE=1 overrides)" >&2
        [ "${FORCE:-0}" = "1" ] || return 3
    fi
    adb -s "$serial" push "/tmp/uv_tp_${kind%gop}.mp4" "$dir/tp_src.mp4" > /dev/null
    adb -s "$serial" logcat -c
    adb -s "$serial" shell am instrument -w -e class com.qtekfun.ultimatevideoeditor.engine.export.ExportThroughputInstrumentedTest \
        -e name "$scenario" -e layers "$layers" $pkg.test/androidx.test.runner.AndroidJUnitRunner \
        | grep -E "^(OK|FAILURES|Tests run)" || true
    adb -s "$serial" shell cat "$dir/tp_${scenario}_stats.txt" || true
    adb -s "$serial" logcat -d | grep -E "decode/s:|UVPerf|slow frame|stalled" | tail -${TAIL:-6}
}

for s in "${scenarios[@]}"; do
    echo "== $s"
    flock /tmp/pixel-device.lock bash -c "$(declare -f run_one); serial='$serial'; dir='$dir'; pkg='$pkg'; run_one '$s'" || echo "$s skipped (exit $?)"
done

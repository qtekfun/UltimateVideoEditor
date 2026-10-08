#!/usr/bin/env bash
# Runs RetimeExportInstrumentedTest N times on a device and checks the exported MP4 with check-retime-export.py.
#   scripts/run-retime-export-test.sh <adb-serial> [runs]
# Needs the app and the androidTest APK installed (./gradlew :app:assembleDebug :app:assembleDebugAndroidTest, then
# adb install -r / adb install -r -t), ffmpeg on the host, and the device unlocked and not in use.
set -euo pipefail
serial="$1"
runs="${2:-1}"
here="$(cd "$(dirname "$0")" && pwd)"
# PKG: application id of the installed build (default; com.qtekfun.ultimatevideoeditor.x for a -PappIdSuffix=.x build).
pkg="${PKG:-com.qtekfun.ultimatevideoeditor}"
work="$(mktemp -d)"
# `am instrument` restarts the app's process: never do that while someone is using the app on the device.
if adb -s "$serial" shell dumpsys window | grep -m1 mCurrentFocus | grep -q "$pkg/"; then
    echo "the app is in the foreground on $serial; not running (set FORCE=1 to override)" >&2
    [ "${FORCE:-0}" = "1" ] || exit 3
fi
trap 'rm -rf "$work"' EXIT
dir="/sdcard/Android/data/$pkg/files"

python3 "$here/check-retime-export.py" gen "$work/retime_src.mp4"
adb -s "$serial" push "$work/retime_src.mp4" "$dir/retime_src.mp4" > /dev/null
failures=0
for i in $(seq 1 "$runs"); do
    adb -s "$serial" logcat -c
    if adb -s "$serial" shell am instrument -w -e class com.qtekfun.ultimatevideoeditor.engine.export.RetimeExportInstrumentedTest \
        $pkg.test/androidx.test.runner.AndroidJUnitRunner | tee "$work/run.txt" | grep -q "^OK"; then
        for f in retime_out.mp4 retime_expected.txt retime_segments.txt plain_out.mp4 plain_expected.txt plain_segments.txt; do
            adb -s "$serial" pull "$dir/$f" "$work/$f" > /dev/null
        done
        python3 "$here/check-retime-export.py" check "$work" retime || failures=$((failures + 1))
        python3 "$here/check-retime-export.py" check "$work" plain || failures=$((failures + 1))
        adb -s "$serial" logcat -d | grep -E "slow frame|not in the stream|never produced|offline render gave up|failed \(status" | head -10
    else
        failures=$((failures + 1))
        echo "run $i failed:"
        grep -E "Error|stalled|Exception" "$work/run.txt" | head -3
        adb -s "$serial" logcat -d | grep -E "uveditor|uv_audio|slow frame|not in the stream|never produced|decoder stalled|export " | tail -30
    fi
done
echo "$failures of $runs run(s) failed"
exit $((failures > 0))

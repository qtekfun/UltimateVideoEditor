#!/usr/bin/env bash
# Runs RetimeExportInstrumentedTest N times on a device and checks the exported MP4 with check-retime-export.py.
#   scripts/run-retime-export-test.sh <adb-serial> [runs]
# Needs the app and the androidTest APK installed (./gradlew :app:assembleDebug :app:assembleDebugAndroidTest, then
# adb install -r / adb install -r -t), ffmpeg on the host, and the device unlocked and not in use.
set -euo pipefail
serial="$1"
runs="${2:-1}"
here="$(cd "$(dirname "$0")" && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
dir=/sdcard/Android/data/com.ultimatevideo.uveditor/files

python3 "$here/check-retime-export.py" gen "$work/retime_src.mp4"
adb -s "$serial" push "$work/retime_src.mp4" "$dir/retime_src.mp4" > /dev/null
failures=0
for i in $(seq 1 "$runs"); do
    adb -s "$serial" logcat -c
    if adb -s "$serial" shell am instrument -w -e class com.ultimatevideo.uveditor.engine.export.RetimeExportInstrumentedTest \
        com.ultimatevideo.uveditor.test/androidx.test.runner.AndroidJUnitRunner | tee "$work/run.txt" | grep -q "^OK"; then
        for f in retime_out.mp4 retime_expected.txt retime_segments.txt; do adb -s "$serial" pull "$dir/$f" "$work/$f" > /dev/null; done
        python3 "$here/check-retime-export.py" check "$work" || failures=$((failures + 1))
    else
        failures=$((failures + 1))
        echo "run $i failed:"
        grep -E "Error|stalled|Exception" "$work/run.txt" | head -3
        adb -s "$serial" logcat -d | grep -E "seek:|decode/s|decoder error|export |seek failed|flush failed|AndroidPcm|UVAudio|AudioCore" | tail -30
    fi
done
echo "$failures of $runs run(s) failed"
exit $((failures > 0))

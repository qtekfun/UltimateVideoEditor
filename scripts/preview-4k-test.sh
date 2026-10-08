#!/usr/bin/env bash
# Plays a clip from the app's private files in the debug preview and prints the engine statistics (drawn, decoded, stalls).
#   PKG=com.qtekfun.ultimatevideoeditor[.suffix] scripts/preview-4k-test.sh <adb-serial> <file-in-app-files> [seconds]
# Put the clip there with: adb push clip.mp4 /data/local/tmp/ && adb shell run-as $PKG cp /data/local/tmp/clip.mp4 files/
set -euo pipefail
serial="$1"; file="$2"; secs="${3:-12}"
pkg="${PKG:-com.qtekfun.ultimatevideoeditor}"
adb -s "$serial" shell am force-stop "$pkg"
adb -s "$serial" logcat -c
adb -s "$serial" shell am start -n "$pkg/com.qtekfun.ultimatevideoeditor.debug.DebugPreviewActivity" \
    --es path "/data/data/$pkg/files/$file" --ez autoplay true >/dev/null
sleep "$secs"
adb -s "$serial" logcat -d -s UVPreviewDebug | grep stats | tail -n 1
adb -s "$serial" logcat -d | grep -E "uveditor: (seek:|slow|decoder)" | wc -l | sed 's/^/decoder events: /'
adb -s "$serial" shell am force-stop "$pkg"

#!/usr/bin/env bash
# Measures A/V clock drift on a connected device over a long timeline.
#
# What it measures: while playing, the audio device clock (master) and the native preview clock
# (CLOCK_MONOTONIC) are compared every UI tick; the editor logs, every 5 s, the mean and maximum
# |heard frame - frame the preview should be on| and how many times the preview had to be
# re-anchored (tag UVSync). It does not measure display or speaker latency.
#
# Usage: [PKG=com.ultimatevideo.uveditor[.suffix]] scripts/av-drift-test.sh <adb-serial> [minutes=5]
# Needs: adb, ffmpeg, python3, a debug build installed (run-as). The screen must be unlocked: the script seeds the
# project, opens it from the hub through uiautomator (dismissing the reopen offer) and presses play on its own.
set -euo pipefail

serial="${1:?usage: $0 <adb-serial> [minutes]}"
minutes="${2:-5}"
pkg="${PKG:-com.ultimatevideo.uveditor}"
. "$(dirname "$0")/device-ui.sh"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# 10 s, 1080p30: a white flash on the first two frames of every second with a 50 ms 1 kHz beep at
# the same instant, otherwise a frame counter on black.
ffmpeg -loglevel error -y \
  -f lavfi -i "color=c=black:s=1920x1080:r=30:d=10,drawtext=text='%{frame_num}':fontsize=120:fontcolor=white:x=40:y=40,drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='lt(mod(n\,30)\,2)'" \
  -f lavfi -i "sine=frequency=1000:sample_rate=48000:duration=10,volume='if(lt(mod(t,1),0.05),1,0)':eval=frame" \
  -c:v libx264 -preset veryfast -pix_fmt yuv420p -c:a aac -b:a 128k -shortest "$work/beep10.mp4"

adb_ push "$work/beep10.mp4" /data/local/tmp/beep10.mp4 >/dev/null
adb_ shell run-as "$pkg" mkdir -p files/projects/avdrift
adb_ shell "cat /data/local/tmp/beep10.mp4 | run-as $pkg sh -c 'cat > files/beep10.mp4'"

clips=$((minutes * 6))  # 10 s per clip
python3 - "$clips" "$pkg" > "$work/project.json" <<'PY'
import json, sys
n = int(sys.argv[1])
pkg = sys.argv[2]
clips = [{"id": f"c{i}", "assetId": "a1", "timelineStartFrame": i * 300, "sourceInFrame": 0,
          "sourceOutFrame": 300} for i in range(n)]
print(json.dumps({
    "version": 1, "id": "avdrift", "name": "AV drift test",
    "settings": {"width": 1920, "height": 1080, "fpsNum": 30, "fpsDen": 1, "colorSpace": "Rec709-SDR"},
    "mediaLibrary": [{"id": "a1", "uri": f"file:///data/data/{pkg}/files/beep10.mp4",
                      "durationFrames": 300, "nativeFpsNum": 30, "nativeFpsDen": 1,
                      "colorSpace": "Rec709-SDR", "hasVideo": True, "hasAudio": True}],
    "tracks": [{"id": "v1", "type": "video", "order": 0, "clips": clips}],
    "transitions": []}))
PY
adb_ push "$work/project.json" /data/local/tmp/avdrift.json >/dev/null
adb_ shell "cat /data/local/tmp/avdrift.json | run-as $pkg sh -c 'cat > files/projects/avdrift/project.json'"

adb_ shell setprop log.tag.UVSync DEBUG
ui_require_unlocked
ui_launch
ui_open_project "AV drift test"
adb_ logcat -c
echo "Project 'AV drift test' seeded (${minutes} min) and opened; pressing play and logging UVSync for $((minutes * 60 + 20)) s ..."
ui_tap_text "Play"
timeout "$((minutes * 60 + 20))" adb -s "$serial" logcat -s UVSync:D | tee "$work/uvsync.log" || true
ui_tap_text "Pause" || true

python3 - "$work/uvsync.log" <<'PY'
import re, sys
mx, reanch, windows, means = 0, 0, 0, []
for line in open(sys.argv[1]):
    m = re.search(r"meanDriftFrames=(\S+) maxAbsDriftFrames=(\d+) reanchors=(\d+)", line)
    if m:
        windows += 1
        means.append(float(m.group(1)))
        mx = max(mx, int(m.group(2)))
        reanch += int(m.group(3))
print(f"windows={windows} worst |drift|={mx} frames, overall mean={sum(means)/max(len(means),1):.3f} frames, re-anchors={reanch}")
PY

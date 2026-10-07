#!/usr/bin/env bash
# Rendering cost of the native timeline canvas on a device: installs a 110-clip test project into the app's private
# storage (debug builds only, `run-as`), opens it, scrolls the timeline for N seconds and prints the renderer's own
# statistics (CPU time to build and submit a frame, draw calls and vertices per frame; the property
# debug.uveditor.timeline_stats=1 turns them on, read once when the canvas starts).
#
#   [PKG=com.ultimatevideo.uveditor[.suffix]] scripts/perf-timeline.sh <adb-serial> [seconds=12]
#
# It never installs the app and never clears data; the screen must be unlocked. Run it inside the shared device lock.
set -euo pipefail
serial="${1:?usage: $0 <adb-serial> [seconds]}"
secs="${2:-12}"
pkg="${PKG:-com.ultimatevideo.uveditor}"
here="$(dirname "$0")"
. "$here/device-ui.sh"

ui_require_unlocked
trap 'adb_ shell setprop debug.uveditor.timeline_stats 0' EXIT  # a stale debug property outlives the run (DECISIONS.md, "No debug switch")
adb_ shell setprop debug.uveditor.timeline_stats 1
python3 "$here/make-perf-project.py" | adb_ shell "run-as $pkg sh -c 'mkdir -p files/projects/perf110 && cat > files/projects/perf110/project.json'"
ui_launch
ui_open_project "Timeline perf 110"
sleep 3
read -r width height < <(adb_ shell wm size | awk -F'[: x]+' '/Physical size/ {print $3, $4}')
adb_ logcat -c
end=$((SECONDS + secs))
y=$((height * 78 / 100))
i=0
while [ "$SECONDS" -lt "$end" ]; do
    # Scroll right and left in alternating drags, with a vertical drag now and then.
    if [ $((i % 2)) -eq 0 ]; then
        adb_ shell input swipe $((width * 85 / 100)) "$y" $((width * 20 / 100)) "$y" 140
    else
        adb_ shell input swipe $((width * 20 / 100)) "$y" $((width * 85 / 100)) "$y" 140
    fi
    if [ $((i % 5)) -eq 4 ]; then adb_ shell input swipe $((width / 2)) $((y + 80)) $((width / 2)) $((y - 160)) 200; fi
    i=$((i + 1))
done
sleep 1
echo "== renderer statistics (uv_timeline)"
adb_ logcat -d -s uv_timeline | grep "stats frames" | tail -12 | sed 's/^.*stats /stats /'
adb_ shell setprop debug.uveditor.timeline_stats 0

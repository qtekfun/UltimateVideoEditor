#!/usr/bin/env bash
# Frame-time check of the editor on a connected device: opens the first project in the hub, plays it for N
# seconds and prints the jank summary of the app window from `dumpsys gfxinfo`. Compare two APKs by running it
# once per install. Needs an unlocked screen; it only taps (open project, play, pause), never installs or clears data.
#
#   scripts/perf-editor.sh <adb-serial> [seconds=10]
#
# Taps are placed from the screen size (portrait phone layout: first hub card at 18.5% of the height, play
# button at 43.5%), so adjust PROJECT_Y / PLAY_Y if the layout changes.
set -euo pipefail
serial="${1:?usage: $0 <adb-serial> [seconds]}"
secs="${2:-10}"
pkg=com.ultimatevideo.uveditor
adb_() { adb -s "$serial" "$@"; }

focus="$(adb_ shell dumpsys window | grep -m1 mCurrentFocus || true)"
echo "focus: $focus"
read -r width height < <(adb_ shell wm size | awk -F'[: x]+' '/Physical size/ {print $3, $4}')
cx=$((width / 2))
project_y=${PROJECT_Y:-$((height * 185 / 1000))}
play_y=${PLAY_Y:-$((height * 435 / 1000))}

adb_ shell am force-stop "$pkg"
adb_ shell am start -n "$pkg/.MainActivity" >/dev/null
sleep 3
adb_ shell input tap "$cx" "$project_y"   # first project card
sleep 4
adb_ shell dumpsys gfxinfo "$pkg" reset >/dev/null
adb_ shell input tap "$cx" "$play_y"      # play
sleep "$secs"
adb_ shell input tap "$cx" "$play_y"      # pause
sleep 1
adb_ shell dumpsys gfxinfo "$pkg" | sed -n '/Total frames rendered/,/Number Slow issue draw commands/p'

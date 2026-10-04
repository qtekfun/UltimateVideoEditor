#!/usr/bin/env bash
# Frame-time check of the editor on a connected device: opens a project from the hub, plays it for N seconds and
# prints the jank summary of the app window from `dumpsys gfxinfo`. Compare two APKs by running it once per install.
# It only taps (open project, play, pause) and never installs or clears data; the screen must be unlocked.
#
#   [PKG=com.ultimatevideo.uveditor[.suffix]] scripts/perf-editor.sh <adb-serial> [seconds=10] [project name]
#
# The project is found by its name through uiautomator (the "app closed while ... was open" offer is dismissed
# first). Without a name the first card is tapped by position (18.5% of the screen height).
set -euo pipefail
serial="${1:?usage: $0 <adb-serial> [seconds] [project name]}"
secs="${2:-10}"
project="${3:-}"
pkg="${PKG:-com.ultimatevideo.uveditor}"
. "$(dirname "$0")/device-ui.sh"

ui_require_unlocked
ui_launch
if [ -n "$project" ]; then
    ui_open_project "$project"
else
    if ui_has_text "Dismiss"; then ui_tap_text "Dismiss"; sleep 1; fi
    read -r width height < <(adb_ shell wm size | awk -F'[: x]+' '/Physical size/ {print $3, $4}')
    adb_ shell input tap $((width / 2)) $((height * 185 / 1000))
    sleep 4
fi

adb_ shell dumpsys gfxinfo "$pkg" reset >/dev/null
ui_tap_text "Play"
sleep "$secs"
ui_tap_text "Pause" || true
sleep 1
adb_ shell dumpsys gfxinfo "$pkg" | sed -n '/Total frames rendered/,/Number Slow issue draw commands/p'

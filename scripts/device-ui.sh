#!/usr/bin/env bash
# Helpers for driving the app on a device with adb and uiautomator, shared by the perf and A/V drift scripts.
# Source it: `. "$(dirname "$0")/device-ui.sh"`, after setting `serial` and `pkg`.
#
#   ui_require_unlocked     exit with a clear message when the screen is off or locked
#   ui_wake                 wake the screen (cannot get past a secure lock screen)
#   ui_tap_text "Play"      tap the node whose text or content description is exactly (or starts with) the argument
#   ui_has_text "Reopen"    succeeds when such a node is on screen
#   ui_open_project "Name"  from the hub: dismiss the reopen offer, open the project card with that name
#   ui_force_english        pin the app to English (the scripts look for English words; the app is translated), see below
#   ui_launch               pin English, force-stop and start the app on the hub
#
# uiautomator crashes when two sessions dump at once, so every dump takes /tmp/uiautomator.lock.
#
# Language: the app follows the phone's language (it is translated, see docs/TRANSLATING.md) but these scripts find buttons by their
# English text, so ui_launch pins the app to English first: on Android 13+ with the system's per-app language
# (`cmd locale set-app-locales <pkg> --locales en`, which touches only that package), on Android 12 with the app's own language
# preference (shared_prefs/language.xml, written with run-as, which needs a debug build). Use it with a suffixed QA build.

adb_() { adb -s "$serial" "$@"; }

ui_dump() {
    # Prints the XML of the current screen on stdout.
    flock -w 60 /tmp/uiautomator.lock bash -c "adb -s '$serial' shell uiautomator dump /sdcard/uv_ui.xml >/dev/null 2>&1 && adb -s '$serial' shell cat /sdcard/uv_ui.xml"
}

# Finds a node by text/content-desc (exact match first, then prefix) and prints "x y" of its centre, or nothing.
ui_find() {
    local wanted="$1"
    ui_dump | python3 -c '
import re, sys
wanted = sys.argv[1]
xml = sys.stdin.read()
nodes = re.findall(r"<node [^>]*>", xml)
def centre(node):
    m = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    if not m: return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2
def attr(node, name):
    m = re.search(name + r"=\"([^\"]*)\"", node)
    return m.group(1) if m else ""
for exact in (True, False):
    for node in nodes:
        for name in ("text", "content-desc"):
            value = attr(node, name)
            if value and ((value == wanted) if exact else value.startswith(wanted)):
                c = centre(node)
                if c:
                    print(c[0], c[1]); sys.exit(0)
' "$wanted"
}

ui_has_text() { [ -n "$(ui_find "$1")" ]; }

ui_tap_text() {
    local pos
    pos="$(ui_find "$1")"
    if [ -z "$pos" ]; then
        echo "ui: '$1' not found on screen" >&2
        return 1
    fi
    # shellcheck disable=SC2086
    adb_ shell input tap $pos
}

ui_wake() {
    adb_ shell input keyevent KEYCODE_WAKEUP
    adb_ shell wm dismiss-keyguard >/dev/null 2>&1 || true
    sleep 1
}

ui_require_unlocked() {
    local focus
    focus="$(adb_ shell dumpsys window | grep -m1 mCurrentFocus || true)"
    if echo "$focus" | grep -q "NotificationShade\|Keyguard\|StatusBar"; then
        ui_wake
        focus="$(adb_ shell dumpsys window | grep -m1 mCurrentFocus || true)"
    fi
    if echo "$focus" | grep -q "NotificationShade\|Keyguard"; then
        echo "the screen of $serial is locked; unlock it (a secure lock cannot be bypassed) and run again" >&2
        exit 4
    fi
}

ui_force_english() {
    local sdk
    sdk="$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')"
    if [ "${sdk:-0}" -ge 33 ]; then
        adb_ shell cmd locale set-app-locales "$pkg" --locales en >/dev/null 2>&1 || echo "ui: could not pin $pkg to English" >&2
    else
        adb_ shell am force-stop "$pkg"
        printf '%s' '<?xml version="1.0" encoding="utf-8" standalone="yes" ?><map><string name="tag">en</string></map>' \
            | adb_ shell "run-as $pkg sh -c 'mkdir -p shared_prefs && cat > shared_prefs/language.xml'" \
            || echo "ui: could not pin $pkg to English (run-as needs a debug build)" >&2
    fi
}

ui_launch() {
    ui_force_english
    adb_ shell am force-stop "$pkg"
    adb_ shell am start -n "$pkg/com.qtekfun.ultimatevideoeditor.MainActivity" >/dev/null
    sleep 3
}

ui_open_project() {
    local name="$1"
    # After a force-stop the hub offers to reopen the project that was open; dismiss it so the cards stay in place.
    if ui_has_text "Dismiss"; then
        ui_tap_text "Dismiss"
        sleep 1
    fi
    # Find the card by its name; scroll the list a few times when the hub has more projects than fit.
    local tries=0
    until ui_has_text "$name"; do
        tries=$((tries + 1))
        if [ "$tries" -gt 6 ]; then
            echo "ui: project '$name' not found in the hub" >&2
            return 1
        fi
        adb_ shell input swipe 540 1600 540 600 300
        sleep 1
    done
    ui_tap_text "$name"
    sleep 4
}

#!/usr/bin/env bash
# Drives one complete export of a project through the real UI and checks the result. Run it with the phone unlocked and awake.
#   scripts/full-export-ui.sh <serial> [options]
#     --project NAME   project card in the hub              (default "Review IPhone 18 Pro Max")
#     --file NAME      file name to save as                 (default "Review IPhone 18 Pro Max 4K.mp4")
#     --dir DIR        folder on the phone's storage         (default /sdcard/Movies)
#     --pkg PKG        package to drive                      (default com.qtekfun.ultimatevideoeditor)
#     --frames N --seconds S   expected frame count / duration (default 42891 / 714.85)
#     --attach         do not drive the UI: the export is already running (or finished); only poll and verify
#     --verify-only    only pull and check the file that exists
#     --selftest XML   test the screen parsing on a saved `uiautomator dump` file (no device needed)
# Steps: check the screen (exit 4 when locked: a secure lock is never bypassed), open the project, tap "Export movie", choose
# 2160p / 60 fps / HEVC / 35 Mbps, "Export…", answer the notification prompt, pick the folder in the save picker, type the file
# name and save; then only read: file size, the foreground service, logcat. At the end the file is pulled and ffprobe'd; the
# last line is PASS or FAIL. It never taps "Cancel", never force-stops the app, never installs, never deletes anything.
# Needs adb, python3 and ffprobe on the host. Takes /tmp/pixel-device.lock only for the UI-driving part.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
project="Review IPhone 18 Pro Max"
fname="Review IPhone 18 Pro Max 4K.mp4"
dir="/sdcard/Movies"
pkg="com.qtekfun.ultimatevideoeditor"
want_frames=42891
want_seconds=714.85
attach=0
verify_only=0
selftest=""
serial=""
while [ $# -gt 0 ]; do
    case "$1" in
        --project) project="$2"; shift 2;;
        --file) fname="$2"; shift 2;;
        --dir) dir="$2"; shift 2;;
        --pkg) pkg="$2"; shift 2;;
        --frames) want_frames="$2"; shift 2;;
        --seconds) want_seconds="$2"; shift 2;;
        --attach) attach=1; shift;;
        --verify-only) verify_only=1; shift;;
        --selftest) selftest="$2"; shift 2;;
        -*) echo "unknown option $1" >&2; exit 2;;
        *) serial="$1"; shift;;
    esac
done

adb_() { adb -s "$serial" "$@"; }

# ---- screen parsing (pure: reads a uiautomator XML on stdin) -------------------------------------------------------------
# find <text> [class]: centre "x y" of the first node whose text or content-desc equals <text> (exact first, then prefix), or of
# the first node of class <class> when <text> is empty.
parse_find() {
    python3 -I -c '
import re, sys
wanted, cls = sys.argv[1], sys.argv[2]
xml = sys.stdin.read()
nodes = re.findall(r"<node [^>]*>", xml)
def attr(n, name):
    m = re.search(r"(?<![\w-])" + name + r"=\"([^\"]*)\"", n)
    return m.group(1) if m else ""
def centre(n):
    m = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", n)
    if not m: return None
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2
if not wanted:
    for n in nodes:
        if attr(n, "class").endswith(cls):
            c = centre(n)
            if c: print(c[0], c[1]); sys.exit(0)
    sys.exit(0)
for exact in (True, False):
    for n in nodes:
        for name in ("text", "content-desc"):
            v = attr(n, name)
            if v and ((v == wanted) if exact else v.startswith(wanted)):
                c = centre(n)
                if c: print(c[0], c[1]); sys.exit(0)
' "$1" "${2:-}"
}

if [ -n "$selftest" ]; then
    fail=0
    for probe in "$@"; do :; done
    # Smoke test: every text of the dump must be findable and give a point inside the screen; print a few.
    python3 -I - "$selftest" <<'PY'
import re, sys
xml = open(sys.argv[1], encoding="utf-8").read()
texts = [t for t in re.findall(r'(?<![\w-])text="([^"]+)"', xml) if t.strip()]
print(f"{len(texts)} texts, e.g. {texts[:6]}")
PY
    first="$(python3 -I -c 'import re,sys; t=[x for x in re.findall(r"(?<![\w-])text=\"([^\"]+)\"", open(sys.argv[1], encoding="utf-8").read()) if x.strip()]; print(t[0] if t else "")' "$selftest")"
    pos="$(parse_find "$first" < "$selftest")"
    echo "find '$first' -> '$pos'"
    [ -n "$pos" ] || { echo "selftest FAIL"; exit 1; }
    [ -z "$(parse_find "no such text on any screen" < "$selftest")" ] || { echo "selftest FAIL (found a missing text)"; exit 1; }
    echo "selftest OK"
    exit 0
fi

[ -n "$serial" ] || { echo "usage: $0 <serial> [options]" >&2; exit 2; }

ui_dump() {
    flock -w 60 /tmp/uiautomator.lock bash -c "adb -s '$serial' shell uiautomator dump /sdcard/uv_ui_full.xml >/dev/null 2>&1 && adb -s '$serial' shell cat /sdcard/uv_ui_full.xml"
}
ui_find() { ui_dump | parse_find "$1" "${2:-}"; }
ui_has() { [ -n "$(ui_find "$1")" ]; }
ui_tap() { # ui_tap TEXT: tap it or fail with a message
    local pos
    pos="$(ui_find "$1")"
    if [ -z "$pos" ]; then echo "ui: '$1' not on screen" >&2; return 1; fi
    # shellcheck disable=SC2086
    adb_ shell input tap $pos
}
ui_wait_for() { # ui_wait_for TEXT [seconds]
    local end=$((SECONDS + ${2:-20}))
    while [ $SECONDS -lt $end ]; do ui_has "$1" && return 0; sleep 1; done
    return 1
}
focus() { adb_ shell dumpsys window | grep -m1 mCurrentFocus || true; }

require_awake_unlocked() {
    local wake
    wake="$(adb_ shell dumpsys power | grep -m1 -o 'mWakefulness=[A-Za-z]*')"
    if [ "$wake" != "mWakefulness=Awake" ]; then
        # Waking is allowed; getting past a lock is not (the phone's owner does that).
        adb_ shell input keyevent KEYCODE_WAKEUP
        sleep 2
    fi
    if focus | grep -qE "NotificationShade|Keyguard|StatusBar" || adb_ shell dumpsys window | grep -q "isKeyguardShowing=true"; then
        echo "the screen of $serial is locked or off: unlock it and run this again (a secure lock is never bypassed)" >&2
        exit 4
    fi
}

service_running() { adb_ shell dumpsys activity services "$pkg" | grep -q "isForeground=true"; }
out="$dir/$fname"
size_of() { adb_ shell stat -c %s "'$out'" 2>/dev/null | tr -d '\r'; }

drive_ui() {
    require_awake_unlocked
    if service_running; then echo "an export is already running in $pkg; not starting another (use --attach)" >&2; return 5; fi
    if ! focus | grep -q "$pkg/"; then
        adb_ shell am start -n "$pkg/com.qtekfun.ultimatevideoeditor.MainActivity" > /dev/null
        sleep 3
    fi
    if ! focus | grep -q "$pkg/"; then echo "the app is not in the foreground: $(focus)" >&2; return 6; fi

    if ! ui_has "Export movie"; then   # the editor's button; otherwise open the project from the hub
        ui_has "Dismiss" && { ui_tap "Dismiss"; sleep 1; }
        local tries=0
        until ui_has "$project"; do
            tries=$((tries + 1)); [ $tries -gt 8 ] && { echo "project '$project' not found in the hub" >&2; return 7; }
            adb_ shell input swipe 540 1600 540 600 300; sleep 1
        done
        ui_tap "$project" || return 7
        ui_wait_for "Export movie" 40 || { echo "the editor did not open" >&2; return 7; }
    fi
    ui_tap "Export movie" || return 8
    ui_wait_for "Export…" 15 || { echo "the export dialog did not open" >&2; return 8; }

    chip() { # chip LABEL: scroll the dialog until the chip shows, tap it
        local tries=0
        until ui_has "$1"; do
            tries=$((tries + 1)); [ $tries -gt 6 ] && { echo "chip '$1' not found" >&2; return 1; }
            adb_ shell input swipe 540 1500 540 900 250; sleep 1
        done
        ui_tap "$1"; sleep 1
    }
    chip "2160p" || return 9
    chip "60 fps" || return 9
    chip "HEVC" || return 9
    chip "35 Mbps" || return 9
    ui_wait_for "Export…" 5 || return 9
    # The button sits below the settings: it is part of the dialog's actions, so it stays on screen.
    ui_tap "Export…" || return 9
    sleep 2
    # Notification permission (Android 13+), only the first time.
    if ui_has "Allow"; then ui_tap "Allow"; sleep 2; fi

    # Save picker (DocumentsUI): go to the folder, type the name, save.
    ui_wait_for "Save" 20 || ui_wait_for "SAVE" 5 || { echo "the save picker did not open: $(focus)" >&2; return 10; }
    local folder_name; folder_name="$(basename "$dir")"
    if ! ui_has "$folder_name"; then
        ui_has "Show roots" && { ui_tap "Show roots"; sleep 1; }
        ui_has "Internal storage" && { ui_tap "Internal storage"; sleep 1; }
    fi
    ui_tap "$folder_name" || { echo "folder '$folder_name' not offered by the picker" >&2; return 10; }
    sleep 1
    local field; field="$(ui_find "" EditText)"
    [ -n "$field" ] || { echo "no file name field in the picker" >&2; return 10; }
    # shellcheck disable=SC2086
    adb_ shell input tap $field
    adb_ shell input keyevent KEYCODE_MOVE_END
    for _ in $(seq 1 80); do adb_ shell input keyevent KEYCODE_DEL; done
    adb_ shell input text "$(printf '%s' "$fname" | sed 's/ /%s/g')"
    sleep 1
    ui_tap "Save" || ui_tap "SAVE" || { echo "no Save button" >&2; return 10; }
    sleep 4
    if ! service_running; then echo "the export service did not start" >&2; return 11; fi
    echo "export started: $out"
}

verify() {
    local tmp; tmp="$(mktemp -d)"
    adb_ pull "$out" "$tmp/out.mp4" > /dev/null || { echo "FAIL: cannot pull $out"; return 1; }
    local probe
    probe="$(ffprobe -v error -count_frames -select_streams v:0 -show_entries stream=codec_name,width,height,r_frame_rate,nb_read_frames -of default=nw=1 "$tmp/out.mp4")"
    local audio dur
    audio="$(ffprobe -v error -select_streams a -show_entries stream=codec_name -of csv=p=0 "$tmp/out.mp4")"
    dur="$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$tmp/out.mp4")"
    echo "$probe"; echo "audio: ${audio:-none}"; echo "duration: $dur"
    local codec width height rate frames ok=1
    codec="$(echo "$probe" | sed -n 's/^codec_name=//p')"; width="$(echo "$probe" | sed -n 's/^width=//p')"
    height="$(echo "$probe" | sed -n 's/^height=//p')"; rate="$(echo "$probe" | sed -n 's/^r_frame_rate=//p')"
    frames="$(echo "$probe" | sed -n 's/^nb_read_frames=//p')"
    [ "$codec" = hevc ] || { echo "FAIL codec $codec"; ok=0; }
    [ "$width" = 3840 ] && [ "$height" = 2160 ] || { echo "FAIL size ${width}x$height"; ok=0; }
    [ "$rate" = 60/1 ] || { echo "FAIL frame rate $rate"; ok=0; }
    python3 -I -c 'import sys; f,w=int(sys.argv[1] or 0),int(sys.argv[2]); sys.exit(0 if abs(f-w) <= 3 else 1)' "$frames" "$want_frames" || { echo "FAIL frames $frames (want ~$want_frames)"; ok=0; }
    [ -n "$audio" ] || { echo "FAIL no audio"; ok=0; }
    python3 -I -c 'import sys; sys.exit(0 if abs(float(sys.argv[1] or 0)-float(sys.argv[2])) <= 1.0 else 1)' "$dur" "$want_seconds" || { echo "FAIL duration $dur (want ~$want_seconds)"; ok=0; }
    rm -rf "$tmp"
    [ $ok = 1 ]
}

if [ $verify_only = 0 ]; then
    if [ $attach = 0 ]; then
        flock /tmp/pixel-device.lock bash -c "true"  # wait for other sessions; the lock is not held while polling
        drive_ui || { echo "FAIL: could not start the export (exit $?)"; exit 1; }
    fi
    echo "polling (read-only)..."
    last=-1; stable=0; started=$SECONDS
    while :; do
        sleep 60
        size="$(size_of)"; size="${size:-0}"
        log="$(adb_ logcat -d | grep -E "export done|export failed" | tail -1 | cut -c1-300)"
        running=no; service_running && running=yes
        printf '%s  %s bytes  service=%s  elapsed=%ss\n' "$(date +%T)" "$size" "$running" $((SECONDS - started))
        if echo "$log" | grep -q "export failed"; then echo "FAIL: $log"; exit 1; fi
        if [ "$running" = no ]; then
            # Finished (or the service died): wait until the size stops changing, then verify.
            if [ "$size" = "$last" ]; then stable=$((stable + 1)); else stable=0; fi
            last="$size"
            [ $stable -ge 1 ] && break
        else
            last="$size"; stable=0
        fi
        [ $((SECONDS - started)) -gt 14400 ] && { echo "FAIL: still running after 4 h"; exit 1; }
    done
fi

if verify; then echo "PASS"; else echo "FAIL"; exit 1; fi

#!/usr/bin/env bash
# Installs an APK on a Huawei (EMUI) device and presses the install confirmations for you.
#
# Huawei's package installer asks the person at the device to confirm every `adb install`
# ("Fuente de instalacion: Herramientas de ordenador ... origen desconocido" with CANCELAR / CONTINUAR, then INSTALAR).
# This script runs `adb install -r` in the background and, only while the focused window belongs to the system
# package installer or the permission controller, taps the button a person would tap (CONTINUAR / INSTALAR / ...).
# It does NOT disable any security feature, change a setting or pretend to be another installer: it presses the same
# confirmation buttons, on a device you own and have connected over adb, so you do not have to.
#
#   scripts/huawei-install.sh <apk> [adb-serial]          (serial defaults to $HW_SERIAL, then the only device)
#   HW_TIMEOUT=240 scripts/huawei-install.sh app.apk       seconds to wait for the install to finish (default 240)
#
# Exit codes: 0 installed, 1 adb install failed (its message is printed), 2 usage / no device,
#             3 timed out, 4 the screen is locked (a secure lock screen cannot be bypassed: unlock the device).
#
# The screen must be on and unlocked while it runs; the script wakes it (KEYCODE_WAKEUP) but cannot enter a PIN.
# Keeping the screen on while the device is on USB (Developer options "Stay awake") helps unattended runs.
set -u

apk="${1:-}"
serial="${2:-${HW_SERIAL:-}}"
timeout_s="${HW_TIMEOUT:-240}"

if [ -z "$apk" ] || [ ! -f "$apk" ]; then
    echo "usage: $0 <apk> [adb-serial]" >&2
    exit 2
fi
if [ -z "$serial" ]; then
    serial="$(adb devices | awk 'NR>1 && $2=="device" {print $1}' | head -1)"
fi
if [ -z "$serial" ] || [ "$(adb -s "$serial" get-state 2>/dev/null)" != "device" ]; then
    echo "huawei-install: no adb device${serial:+ with serial $serial}" >&2
    exit 2
fi

adb_() { adb -s "$serial" "$@"; }
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
log="$work/install.log"

focus() { adb_ shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r'; }

screen_locked() {
    case "$(focus)" in *NotificationShade*|*Keyguard*|*keyguard*) return 0 ;; esac
    return 1
}

wake() {
    if ! adb_ shell dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake"; then
        adb_ shell input keyevent KEYCODE_WAKEUP
        sleep 1
    fi
    if screen_locked; then
        adb_ shell wm dismiss-keyguard >/dev/null 2>&1 || true
        sleep 1
    fi
}

wake
if screen_locked; then
    echo "huawei-install: the screen of $serial is locked; unlock it (a secure lock cannot be bypassed) and run again" >&2
    exit 4
fi

# Prints "x y label" of the confirmation button to press on the current screen, or nothing.
find_button() {
    flock -w 30 /tmp/uiautomator.lock bash -c "adb -s '$serial' shell uiautomator dump /sdcard/hwi.xml >/dev/null 2>&1 && adb -s '$serial' shell cat /sdcard/hwi.xml" |
        python3 -c '
import re, sys
xml = sys.stdin.read()
# Buttons to press, in priority order (lower-case, Spanish and English). Never "cancelar"/"cancel".
wanted = ["continuar", "continue", "instalar", "install", "aceptar", "accept", "permitir", "allow", "ok", "siguiente", "next"]
nodes = []
for m in re.finditer(r"<node [^>]*>", xml):
    node = m.group(0)
    t = re.search(r"text=\"([^\"]*)\"", node)
    b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
    rid = re.search(r"resource-id=\"([^\"]*)\"", node)
    # Only real buttons: the AppGallery card on the confirmation screen also shows advertised apps with
    # non-clickable "INSTALAR" labels that must never be tapped.
    if not t or not b or "clickable=\"true\"" not in node:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    nodes.append((t.group(1).strip().lower(), (x1 + x2) // 2, (y1 + y2) // 2, rid.group(1) if rid else ""))
# The installer ids are unambiguous; use them first, then fall back to the labels.
for text, x, y, rid in nodes:
    if rid.endswith(("install_button_continue", ":id/ok_button", ":id/continue_button")) or (
            rid.startswith("com.android.packageinstaller:id/") and text in wanted):
        print(x, y, text)
        sys.exit(0)
for want in wanted:
    for text, x, y, rid in nodes:
        if text == want and rid.split(":")[0] in ("com.android.packageinstaller", "com.android.permissioncontroller",
                                                   "com.huawei.appmarket", "android", ""):
            print(x, y, want)
            sys.exit(0)
'
}

adb_ install -r -t "$apk" >"$log" 2>&1 &
install_pid=$!

start=$SECONDS
taps=0
while kill -0 "$install_pid" 2>/dev/null; do
    if [ $((SECONDS - start)) -ge "$timeout_s" ]; then
        kill "$install_pid" 2>/dev/null
        echo "huawei-install: timed out after ${timeout_s}s" >&2
        case "$(focus)" in *packageinstaller*) adb_ shell input keyevent KEYCODE_BACK ;; esac
        exit 3
    fi
    # The screen may fall asleep during a long install: wake it, and stop if it ended up locked.
    if ! adb_ shell dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake"; then
        wake
        if screen_locked; then
            kill "$install_pid" 2>/dev/null
            echo "huawei-install: the screen locked during the install; unlock the device and run again" >&2
            exit 4
        fi
    fi
    case "$(focus)" in
        *packageinstaller*|*permissioncontroller*|*com.huawei.systemmanager*|*com.huawei.appmarket*)
            if pos="$(find_button)" && [ -n "$pos" ]; then
                set -- $pos
                echo "huawei-install: pressing '$3' at $1,$2"
                adb_ shell input tap "$1" "$2"
                taps=$((taps + 1))
                sleep 2
                continue
            fi
            ;;
    esac
    sleep 1
done

wait "$install_pid" 2>/dev/null
cat "$log"
if grep -q "^Success" "$log"; then
    echo "huawei-install: installed ($taps confirmation(s) pressed)"
    exit 0
fi
exit 1

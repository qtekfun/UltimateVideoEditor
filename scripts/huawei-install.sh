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
#   HW_ATTEMPTS=2   runs again when the installer aborts the session itself (INSTALL_FAILED_ABORTED)
#   HW_DEBUG=<dir>  saves every UI dump and a focus trace there, to see what a stuck installer shows
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

# Dumps the UI (kept in $work/last-dump.xml) and prints "x y label signature" for the confirmation button to press
# on the current screen, or nothing. What may be pressed depends on the app that owns the screen (the package in the
# dump, which is more reliable than mCurrentFocus: right after a transition the focus can still name the previous window):
#   - system package installer / permission controller: CONTINUAR / INSTALAR / ACEPTAR ... buttons, never CANCELAR;
#   - Huawei AppGallery install-check page: only its own "install" button (hidden_card_install_button_continue).
#     That page also shows advertised apps with their own INSTALAR buttons and a "similar apps" button: never tapped.
# The signature identifies the screen, so the same screen is not tapped twice in a row (see below).
find_button() {
    flock -w 30 /tmp/uiautomator.lock bash -c "adb -s '$serial' shell uiautomator dump /sdcard/hwi.xml >/dev/null 2>&1 && adb -s '$serial' shell cat /sdcard/hwi.xml" >"$work/last-dump.xml"
    python3 - "$work/last-dump.xml" <<'PY'
import hashlib, re, sys
xml = open(sys.argv[1], errors="replace").read()
root = re.search(r'package="([^"]*)"', xml)
pkg = root.group(1) if root else ""
wanted = ["continuar", "continue", "instalar", "install", "aceptar", "accept", "permitir", "allow", "ok", "siguiente", "next"]
nodes = []
sig = hashlib.md5()
for m in re.finditer(r"<node [^>]*>", xml):
    node = m.group(0)
    t = re.search(r'text="([^"]*)"', node)
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
    rid = re.search(r'resource-id="([^"]*)"', node)
    p = re.search(r'package="([^"]*)"', node)
    sig.update((((rid.group(1) if rid else "") + "|" + (t.group(1) if t else "")).encode()))
    if not t or not b or 'clickable="true"' not in node or 'enabled="true"' not in node:
        continue
    x1, y1, x2, y2 = map(int, b.groups())
    nodes.append((t.group(1).strip().lower(), (x1 + x2) // 2, (y1 + y2) // 2, rid.group(1) if rid else "", p.group(1) if p else ""))
digest = sig.hexdigest()[:12]
if pkg == "com.huawei.appmarket":
    for text, x, y, rid, p in nodes:
        if rid.endswith("hidden_card_install_button_continue"):
            print(x, y, text, digest)
            sys.exit(0)
elif pkg in ("com.android.packageinstaller", "com.google.android.packageinstaller",
             "com.android.permissioncontroller", "com.google.android.permissioncontroller"):
    for want in wanted:
        for text, x, y, rid, p in nodes:
            if text == want and p == pkg:
                print(x, y, text, digest)
                sys.exit(0)
PY
}

# One line per visible label of the last dump, to see what a stuck installer is showing.
describe_screen() {
    python3 - "$work/last-dump.xml" <<'PY'
import re, sys
xml = open(sys.argv[1], errors="replace").read()
for m in re.finditer(r"<node [^>]*>", xml):
    n = m.group(0)
    t = re.search(r'text="([^"]*)"', n)
    c = 'clickable="true"' in n
    if t and t.group(1) and (c or len(t.group(1)) > 3):
        rid = re.search(r'resource-id="([^"]*)"', n)
        print("   %s%s  [%s]" % ("(button) " if c else "", t.group(1)[:80], rid.group(1) if rid else ""))
PY
}

# Runs one install attempt. Returns 0 installed, 1 adb failed, 3 timed out, 4 screen locked.
attempt() {
    : >"$log"
    adb_ install -r -t "$apk" >"$log" 2>&1 &
    local install_pid=$!
    local start=$SECONDS last_sig="" last_tap=0 idle_since=$SECONDS reported=0 cur prev_focus="" pos
    while kill -0 "$install_pid" 2>/dev/null; do
        if [ $((SECONDS - start)) -ge "$timeout_s" ]; then
            kill "$install_pid" 2>/dev/null
            echo "huawei-install: timed out after ${timeout_s}s; the last screen showed:" >&2
            describe_screen >&2
            case "$(focus)" in *packageinstaller*) adb_ shell input keyevent KEYCODE_BACK ;; esac
            return 3
        fi
        # The screen may fall asleep during a long install: wake it, and stop if it ended up locked.
        if ! adb_ shell dumpsys power 2>/dev/null | grep -q "mWakefulness=Awake"; then
            wake
            if screen_locked; then
                kill "$install_pid" 2>/dev/null
                echo "huawei-install: the screen locked during the install; unlock the device and run again" >&2
                return 4
            fi
        fi
        cur="$(focus)"
        if [ -n "${HW_DEBUG:-}" ] && [ "$cur" != "$prev_focus" ]; then
            echo "t=$((SECONDS - start)) focus: $cur" >>"$HW_DEBUG/trace.txt"
            prev_focus="$cur"
        fi
        case "$cur" in
            *packageinstaller*|*permissioncontroller*|*com.huawei.systemmanager*|*com.huawei.appmarket*)
                pos="$(find_button)"
                if [ -n "${HW_DEBUG:-}" ]; then
                    n=$((n + 1))
                    cp "$work/last-dump.xml" "$HW_DEBUG/dump-$n.xml" 2>/dev/null
                    echo "$n $cur -> ${pos:-none}" >>"$HW_DEBUG/trace.txt"
                fi
                if [ -n "$pos" ]; then
                    set -- $pos
                    # A page can take seconds to be replaced after a tap. Pressing the same screen again would land
                    # on whatever replaces it (the AppGallery page has advertised apps where CONTINUAR was), so wait
                    # for the screen to change and only retry a screen that stays the same for HW_RETAP seconds.
                    if [ "$4" = "$last_sig" ] && [ $((SECONDS - last_tap)) -lt "${HW_RETAP:-12}" ]; then
                        sleep 1
                        continue
                    fi
                    echo "huawei-install: pressing '$3' at $1,$2"
                    adb_ shell input tap "$1" "$2"
                    taps=$((taps + 1))
                    last_sig="$4"
                    last_tap=$SECONDS
                    idle_since=$SECONDS
                    reported=0
                    sleep 2
                    continue
                fi
                # An installer screen with nothing we may press: say what it shows once, so a stuck run is diagnosable.
                if [ $((SECONDS - idle_since)) -ge 20 ] && [ "$reported" = 0 ]; then
                    echo "huawei-install: waiting on an installer screen with no button to press; it shows:" >&2
                    describe_screen >&2
                    reported=1
                fi
                ;;
            *) idle_since=$SECONDS ;;
        esac
        sleep 1
    done
    wait "$install_pid" 2>/dev/null
    grep -q "^Success" "$log" && return 0
    return 1
}

taps=0
n=0
attempts="${HW_ATTEMPTS:-2}"
rc=1
for try in $(seq 1 "$attempts"); do
    attempt
    rc=$?
    if [ "$rc" = 0 ]; then
        cat "$log"
        echo "huawei-install: installed ($taps confirmation(s) pressed)"
        exit 0
    fi
    # The installer sometimes aborts the session by itself ("User rejected permissions") when a confirmation screen
    # is replaced under the tap; the device is fine and a second run goes through. Anything else is reported as is.
    if [ "$rc" = 1 ] && [ "$try" -lt "$attempts" ] && grep -q "INSTALL_FAILED_ABORTED" "$log"; then
        echo "huawei-install: the installer aborted the session ($(grep -m1 Failure "$log")); trying again" >&2
        sleep 3
        continue
    fi
    break
done
cat "$log"
exit "$rc"

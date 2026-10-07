#!/usr/bin/env bash
# Device regression runner: catches, before a person does, the defects that only show on a phone. Non-destructive, offline,
# uses synthetic media made by ffmpeg (scripts/qa/gen-media.sh) and a SUFFIXED test build, never the real app or its data.
#
#   scripts/qa-smoke.sh <adb-serial> [--quick] [--install] [--reinstall] [--apk FILE] [--fix-props] [--only ID,ID] [--no-ui]
#
#   --quick       the two minute subset (no mixed 16 s export, no rotation / saved-frame / export-dialog checks)
#   --install     install the APK first (adb install -r -t; on a Huawei scripts/huawei-install.sh). Needs --apk or the default build
#   --reinstall   uninstall the QA package first (its data goes with it; nothing else is touched), then install
#   --apk FILE    default app/build/outputs/apk/debug/app-debug.apk, built with
#                 `flock /tmp/gradle-build.lock ./gradlew :app:assembleDebug -Puveditor.appIdSuffix=qa`
#   --fix-props   reset stale debug.uveditor.* properties on the device to empty before the hygiene check (they are only reported otherwise)
#   --only IDS    run only these checks (comma separated, see the table)
#   --no-ui       skip the uiautomator flows (and the real-touch drag check)
#
# Environment: QA_PKG (default com.ultimatevideo.uveditor.qa; must carry a suffix: the unsuffixed app holds the user's project and
# is refused), QA_DATA (default /home/qtekfun/uvdata/qa-smoke; large files never go to /tmp, which is RAM), QA_LOCK (default
# /tmp/pixel-device.lock, /tmp/tablet-device.lock on a Huawei).
#
# Exit codes: 0 every check passed (SKIP is not a failure), 1 a check failed, 2 usage or a safety refusal, 3 the device is busy
# (another app in front that is not ours; the runner does not fight for the screen), 4 the screen is locked.
# What each check protects against, and how to read a failure: docs/QA.md.
set -uo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
qa="$root/scripts/qa"
serial="${1:-}"
[ -n "$serial" ] || { sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
shift
quick=0 install=0 reinstall=0 fixprops=0 ui=1 only="" apk="$root/app/build/outputs/apk/debug/app-debug.apk"
while [ $# -gt 0 ]; do
    case "$1" in
        --quick) quick=1 ;;
        --install) install=1 ;;
        --reinstall) reinstall=1; install=1 ;;
        --apk) apk="$2"; shift ;;
        --fix-props) fixprops=1 ;;
        --only) only="$2"; shift ;;
        --no-ui) ui=0 ;;
        *) echo "unknown option $1" >&2; exit 2 ;;
    esac
    shift
done

pkg="${QA_PKG:-com.ultimatevideo.uveditor.qa}"
data="${QA_DATA:-/home/qtekfun/uvdata/qa-smoke}"
media="$data/media"
mkdir -p "$data" "$media"
# The app's own external files directory, created by the app (a directory made by `adb shell` is not writable by the app).
dev="/sdcard/Android/data/$pkg/files"
log="$data/last-run.log"
: > "$log"
adb_() { adb -s "$serial" "$@"; }
say() { echo "$*" | tee -a "$log" >&2; }

# ---- safety ---------------------------------------------------------------------------------------------------------------------
case "$pkg" in
    com.ultimatevideo.uveditor) echo "refusing: $pkg is the real app (it holds the user's project). Use a suffixed build, e.g. -Puveditor.appIdSuffix=qa" >&2; exit 2 ;;
    com.ultimatevideo.uveditor.*) ;;
    *) echo "refusing: $pkg is not a suffixed ultimateVE package" >&2; exit 2 ;;
esac
for tool in adb ffmpeg ffprobe python3 flock; do command -v "$tool" > /dev/null || { echo "missing tool: $tool" >&2; exit 2; }; done
if [ "$(adb_ get-state 2>/dev/null)" != "device" ]; then
    echo "no adb device $serial (wireless adb drops: 'adb mdns services', then 'adb connect <host:port>')" >&2
    exit 2
fi
model="$(adb_ shell getprop ro.product.model | tr -d '\r')"
maker="$(adb_ shell getprop ro.product.manufacturer | tr -d '\r')"
case "$model $maker $serial" in
    *CPH2841*|*PGEM10*|*OPPO*|*oppo*|*Oppo*) echo "refusing: $model ($maker) is not a test device for this runner" >&2; exit 2 ;;
esac
lock="${QA_LOCK:-/tmp/pixel-device.lock}"
case "$maker" in HUAWEI|Huawei|huawei) lock="${QA_LOCK:-/tmp/tablet-device.lock}" ;; esac
exec 9> "$lock"
say "waiting for the device lock $lock ..."
flock -w 1800 9 || { echo "could not take $lock within 30 minutes" >&2; exit 3; }
trap 'cleanup' EXIT

sdk="$(adb_ shell getprop ro.build.version.sdk | tr -d '\r')"
focus() { adb_ shell dumpsys window 2>/dev/null | grep -m1 mCurrentFocus | tr -d '\r'; }
case "$(focus)" in
    *com.qtekfun.mapas*) echo "device busy: ultimatemaps (com.qtekfun.mapas) is in the foreground; not touching it. Try again later." >&2; exit 3 ;;
esac

# ---- results --------------------------------------------------------------------------------------------------------------------
declare -a R_ID R_REF R_TITLE R_STATUS R_DETAIL
record() { # id ref title status detail
    R_ID+=("$1"); R_REF+=("$2"); R_TITLE+=("$3"); R_STATUS+=("$4"); R_DETAIL+=("$5")
    printf '%-9s %-4s %-5s %s: %s\n' "$1" "$2" "$4" "$3" "$5" >> "$log"
    printf '  %-5s %s\n' "$4" "$1 $3" >&2
}
want() { [ -z "$only" ] && return 0; case ",$only," in *",$1,"*) return 0 ;; esac; return 1; }
full() { [ "$quick" = 0 ]; }

# Runs a scripts/qa/check-export.py assertion and records its verdict. $1 id, $2 defect ref, $3 title, rest: arguments.
assert() {
    local id="$1" ref="$2" title="$3" out
    shift 3
    out="$(python3 "$qa/check-export.py" "$@" 2>&1 | tail -1)"
    case "$out" in
        OK*) record "$id" "$ref" "$title" PASS "${out#OK }" ;;
        FAIL*) record "$id" "$ref" "$title" FAIL "${out#FAIL }" ;;
        *) record "$id" "$ref" "$title" FAIL "checker gave: $out" ;;
    esac
}

cleanup() {
    # Nothing here sets a debug property (stale ones are only reset on request, --fix-props). Leave the device as found.
    adb_ shell am force-stop "$pkg" > /dev/null 2>&1 || true
}

# ---- preparation ----------------------------------------------------------------------------------------------------------------
versionName="$(sed -n 's/^versionName=//p' "$root/gradle/version.properties" | tr -d '[:space:]')"
if [ "$reinstall" = 1 ] && adb_ shell pm list packages "$pkg" | grep -q "package:$pkg\$"; then
    say "uninstalling $pkg (only the QA package)"
    adb_ uninstall "$pkg" > /dev/null
fi
if [ "$install" = 1 ]; then
    [ -f "$apk" ] || { echo "no APK at $apk (build it first, see --apk)" >&2; exit 2; }
    say "installing $apk"
    case "$maker" in
        HUAWEI|Huawei|huawei) "$root/scripts/huawei-install.sh" "$apk" "$serial" > /dev/null || { echo "install failed" >&2; exit 2; } ;;
        *) adb_ install -r -t "$apk" > /dev/null || { echo "install failed" >&2; exit 2; } ;;
    esac
fi
if ! adb_ shell pm list packages "$pkg" | grep -q "package:$pkg\$"; then
    echo "$pkg is not installed on $serial: run with --install (and --apk)" >&2
    exit 2
fi
installed="$(adb_ shell dumpsys package "$pkg" | grep -m1 versionName= | sed 's/.*versionName=//' | tr -d '\r ')"

pid_of() { adb_ shell pidof "$pkg" 2>/dev/null | tr -d '\r' | awk '{print $1}'; }
device_now() { adb_ shell date '+%m-%d\ %H:%M:%S.000' | tr -d '\r'; }
# The log of our process since $1 (device time stamp from device_now), without clearing the shared log buffer.
log_since() { adb_ logcat -d -T "$1" ${2:+--pid="$2"} 2>/dev/null | tr -d '\r'; }

need_files() {
    say "media: $media"
    "$qa/gen-media.sh" "$media" > /dev/null || { echo "could not generate the media" >&2; exit 2; }
    python3 "$qa/make-projects.py" "$dev" "$data/projects" > /dev/null || exit 2
    if ! adb_ shell "test -d $dev" 2>/dev/null; then
        # Let the app create its directory (the probe harness asks for it) so that it owns it and can write results into it.
        start_activity HdrProbeDemoActivity
        wait_for_file "$dev/hdr-probe.txt" 40 || true
    fi
    for f in "$media"/*; do
        local name size
        name="$(basename "$f")"
        size="$(stat -c %s "$f")"
        if [ "$(adb_ shell stat -c %s "$dev/$name" 2>/dev/null | tr -d '\r')" != "$size" ]; then adb_ push "$f" "$dev/$name" > /dev/null; fi
    done
    for p in "$data"/projects/*.json; do
        local id
        id="$(basename "$p" .json)"
        adb_ push "$p" "$dev/$id.json" > /dev/null
        adb_ shell "run-as $pkg sh -c 'mkdir -p files/projects/$id && cat $dev/$id.json > files/projects/$id/project.json'" > /dev/null 2>&1 \
            || adb_ shell "cat $dev/$id.json | run-as $pkg sh -c 'mkdir -p files/projects/$id && cat > files/projects/$id/project.json'"
    done
}
say "device: $model, Android $(adb_ shell getprop ro.build.version.release | tr -d '\r') (API $sdk), package $pkg $installed, runner $(date +%H:%M:%S)"

# ---- helpers for the debug harnesses ---------------------------------------------------------------------------------------------
start_activity() { # class args...
    local cls="$1"
    shift
    adb_ shell am start -W -n "$pkg/com.ultimatevideo.uveditor.debug.$cls" "$@" > /dev/null
}
wait_for_file() { # device-path timeout-seconds ; prints nothing, returns 0 when it exists
    local path="$1" t="$2" i=0
    while [ "$i" -lt "$t" ]; do
        adb_ shell "test -f $path" 2>/dev/null && return 0
        sleep 1
        i=$((i + 1))
    done
    return 1
}

fgs_state=""
# Samples `dumpsys activity services` until $1 exists on the device; remembers the last foreground description of the export service.
wait_sampling_service() { # result-path timeout
    local path="$1" t="$2" start=$SECONDS d
    fgs_state="" fgs_died=0
    while [ $((SECONDS - start)) -lt "$t" ]; do
        adb_ shell "test -f $path" 2>/dev/null && return 0
        # A crash leaves no result file (and the process lingers behind the "app has stopped" dialog): do not wait out the timeout.
        if [ -z "$(pid_of)" ] || adb_ logcat -b crash -d -T "$ex_stamp" 2>/dev/null | grep -q "Process: $pkg,"; then
            if [ $((SECONDS - start)) -ge 2 ]; then fgs_died=1; return 1; fi
        fi
        d="$(adb_ shell dumpsys activity services "$pkg/com.ultimatevideo.uveditor.ui.export.ExportService" 2>/dev/null | tr -d '\r')"
        if echo "$d" | grep -q "isForeground=true"; then fgs_state="$d"; fi
        sleep 0.4
    done
    return 1
}

# Exports a seeded project through QaExportActivity (the dialog's executor and foreground service). $1 name, then activity extras.
# Sets: ex_result (the result line), ex_file (pulled MP4, empty on failure), ex_seconds, ex_log (engine log of the run).
export_project() {
    local name="$1" project="$2" out="$dev/out_$1.mp4"
    shift 2
    ex_file="" ex_result="" ex_seconds=0
    adb_ shell rm -f "$out" "$out.result.txt" > /dev/null
    local stamp
    stamp="$(device_now)"
    ex_stamp="$stamp"
    start_activity QaExportActivity --es project "$dev/$project.json" --es out "$out" "$@"
    ex_pid_start="$(pid_of)"
    if ! wait_sampling_service "$out.result.txt" "${EXPORT_TIMEOUT:-300}"; then
        if [ "$fgs_died" = 1 ]; then
            local crashlog
            crashlog="$(adb_ logcat -b crash -d -T "$stamp" 2>/dev/null | tr -d '\r' | sed 's/.*AndroidRuntime: //')"
            ex_result="the app crashed during the export: $( (echo "$crashlog" | grep -m1 'InvalidForegroundServiceType' || echo "$crashlog" | grep -m1 'Exception:') | cut -c1-170)"
            adb_ shell am force-stop "$pkg" > /dev/null  # dismisses the crash dialog; only the QA package
        else ex_result="no result within ${EXPORT_TIMEOUT:-300} s"; fi
    else
        sleep 1
        ex_result="$(adb_ shell cat "$out.result.txt" | tr -d '\r' | head -1)"
    fi
    ex_seconds="$(echo "$ex_result" | sed -n 's/.* in \([0-9.,]*\) s,.*/\1/p' | tr ',' '.')"
    ex_seconds="${ex_seconds:-0}"
    ex_pid_end="$(pid_of)"
    ex_log="$data/log_$name.txt"
    log_since "$stamp" "$ex_pid_start" > "$ex_log"
    if echo "$ex_result" | grep -q '^OK'; then
        adb_ pull "$out" "$data/out_$name.mp4" > /dev/null 2>&1 && ex_file="$data/out_$name.mp4"
    fi
    adb_ shell rm -f "$out" > /dev/null
}

# ---- checks ---------------------------------------------------------------------------------------------------------------------
# The safe values of debug.uveditor.* properties. The code reads only the three diagnostics (DebugPropertyGuardTest keeps that true);
# the others are switches of earlier builds that a build still installed on a phone may read: for those, 0 would have turned a fix off
# (a stale decode_gap=0 invalidated three hours of exports), so 1 or unset is safe and 0 is not. Any other name is unknown.
safe_value_pattern() {
    case "$1" in
        debug.uveditor.timeline_stats|debug.uveditor.export_perf|debug.uveditor.atlas_bytes) echo '^(|0)$' ;;
        debug.uveditor.decode_gap|debug.uveditor.export_enc_flags|debug.uveditor.export_cull) echo '^(|1)$' ;;
        debug.uveditor.dec_flags|debug.uveditor.decode_ahead) echo '^(|0)$' ;;
        *) echo '^$' ;;
    esac
}
check_hygiene() { # id
    local id="$1" bad=() line name value
    while IFS= read -r line; do
        name="$(echo "$line" | sed -n 's/^\[\(debug\.uveditor\.[^]]*\)\]: \[.*/\1/p')"
        value="$(echo "$line" | sed -n 's/^\[[^]]*\]: \[\(.*\)\]$/\1/p')"
        [ -n "$name" ] || continue
        echo "$value" | grep -Eq "$(safe_value_pattern "$name")" && continue
        if [ "$fixprops" = 1 ]; then
            # Back to the safe value of the property (empty is safe for all of them).
            adb_ shell setprop "$name" "" < /dev/null > /dev/null
            say "reset stale $name (was $value)"
            continue
        fi
        bad+=("$name=$value")
    done < <(adb_ shell getprop | tr -d '\r' | grep '^\[debug\.uveditor\.')
    if [ "${#bad[@]}" -eq 0 ]; then
        record "$id" D5 "no stale debug.uveditor.* property" PASS "all unset or at their safe value"
    else
        record "$id" D5 "no stale debug.uveditor.* property" FAIL "${bad[*]} (a stale switch hid a fix for hours; reset with --fix-props or reboot)"
    fi
}

check_version() {
    want ENV-1 || return 0
    if [ "$installed" = "$versionName" ]; then
        record ENV-1 D7 "installed build is the current version" PASS "versionName $installed"
    else
        record ENV-1 D7 "installed build is the current version" FAIL "device has $installed, gradle/version.properties says $versionName (stale APK: rebuild and --install)"
    fi
}

hdr_probe=""   # true / false / unknown
check_probe() {
    want PROBE || return 0
    adb_ shell rm -f "/sdcard/Android/data/$pkg/files/hdr-probe.txt" > /dev/null
    start_activity HdrProbeDemoActivity
    if ! wait_for_file "/sdcard/Android/data/$pkg/files/hdr-probe.txt" 40; then
        record PROBE D6 "HLG export probe answers" FAIL "HdrProbeDemoActivity wrote nothing"
        hdr_probe=unknown
        return 0
    fi
    adb_ pull "/sdcard/Android/data/$pkg/files/hdr-probe.txt" "$data/hdr-probe.txt" > /dev/null
    hdr_probe="$(sed -n 's/^MediaCodecHdrExportSupport 1920x1080@30 = //p' "$data/hdr-probe.txt" | head -1)"
    local trials
    trials="$(grep -c '^  1920x1080@30 probe+gop+vbr: .*configure=ok' "$data/hdr-probe.txt" || true)"
    # The probe must say yes when an encoder accepts the exporter's own format (variant "probe+gop+vbr" of the harness mirrors it).
    if [ "$trials" -gt 0 ] && [ "$hdr_probe" != "true" ]; then
        record PROBE D6 "HLG probe agrees with a trial encode" FAIL "an HEVC encoder configures the Main10 HLG format ($trials trial(s) ok) but supportsHlgExport says '$hdr_probe'"
    else
        record PROBE D6 "HLG probe agrees with a trial encode" PASS "supportsHlgExport(1080p30)=$hdr_probe, $trials trial configure(s) ok"
    fi
}

# Frame, PTS, audio and service checks over one export of a seeded project.
# $1 id prefix, $2 project, $3 expected frames, $4 fps, $5 clips, $6 seconds per clip, $7 audio spec "label:clipIndex ..."
check_project_export() {
    local pre="$1" project="$2" frames="$3" fps="$4" clips="$5" per="$6" audio="$7"
    want "$pre-FPS" || want "$pre-AUD" || want FGS || want "$pre-TAGS" || want "$pre-VERIFY" || return 0
    export_project "$pre" "$project"
    if [ -z "$ex_file" ]; then
        for c in FPS AUD TAGS VERIFY; do want "$pre-$c" && record "$pre-$c" D1 "export of $project" FAIL "export failed: $ex_result"; done
        want FGS && record FGS D4 "export foreground service" FAIL "export failed: $ex_result"
        return 0
    fi
    if want "$pre-FPS"; then
        assert "$pre-FPS" D5 "$project: $frames frames, steady PTS" av "$ex_file" --frames "$frames" --fps "$fps"
    fi
    if want "$pre-AUD"; then
        local item label idx
        for item in $audio; do
            label="${item%%:*}"
            idx="${item##*:}"
            assert "$pre-AUD-$label" D1 "sound in the export: $label" audio "$ex_file" --segment "$((idx * per)):$per"
        done
    fi
    if want "$pre-TAGS"; then assert "$pre-TAGS" D6 "$project: SDR tags bt709" tags "$ex_file" --kind sdr; fi
    if want "$pre-VERIFY"; then assert "$pre-VERIFY" D10 "$project: the app verified its own file" verification --result "$ex_result" --expect verified; fi
    if want "$pre-SEEK" || want "$pre-FPS"; then
        assert "$pre-SEEK" D5 "$project: no stall, bounded seeks" seeks "$ex_log" --seconds "$ex_seconds" --clips "$clips"
    fi
    if want FGS; then check_service; fi
}

service_checked=0
check_service() {
    [ "$service_checked" = 0 ] || return 0
    service_checked=1
    local crash types
    if [ -z "$ex_pid_start" ] || [ "$ex_pid_start" != "$ex_pid_end" ]; then
        record FGS D4 "export foreground service starts" FAIL "the app process died during the export (pid ${ex_pid_start:-none} -> ${ex_pid_end:-none})"
        return 0
    fi
    crash="$(grep -c "InvalidForegroundServiceTypeException\|FATAL EXCEPTION" "$ex_log" || true)"
    if [ "$crash" -gt 0 ]; then
        record FGS D4 "export foreground service starts" FAIL "$(grep -m1 'InvalidForegroundServiceTypeException\|FATAL EXCEPTION' "$ex_log" | cut -c1-120)"
        return 0
    fi
    if [ -z "$fgs_state" ]; then
        record FGS D4 "export foreground service starts" FAIL "no isForeground=true for ExportService while the export ran (dumpsys activity services)"
        return 0
    fi
    types="$(echo "$fgs_state" | grep -o 'foregroundServiceType=0x[0-9a-fA-F]*\|types=0x[0-9a-fA-F]*\|fgsType=0x[0-9a-fA-F]*' | head -1)"
    case "$types" in
        ''|*=0x0|*=0x00|*=0x00000000) record FGS D4 "export foreground service starts" FAIL "foreground with no service type (${types:-no type shown})" ;;
        *) record FGS D4 "export foreground service starts" PASS "isForeground=true, $types, process alive" ;;
    esac
}

check_hlg() {
    want EXP-HLG || return 0
    EXPORT_TIMEOUT=180 export_project hlg qahlg --es codec hevc --ez hdr true --ei w 1920 --ei h 1080
    if [ -n "$ex_file" ]; then
        if [ "$hdr_probe" = "false" ]; then
            record EXP-HLG D6 "HLG export offered iff it works" FAIL "the probe says no HDR but an HLG export succeeded (the probe differs from the exporter's format)"
        else
            assert EXP-HLG D6 "HLG export tags (hvc1 Main10, bt2020nc, HLG, tv)" tags "$ex_file" --kind hlg
            if want VERIFY-HLG; then assert VERIFY-HLG D10 "HLG export verified by the app (10-bit signatures)" verification --result "$ex_result" --expect verified; fi
        fi
    elif echo "$ex_result" | grep -qi "UNSUPPORTED"; then
        if [ "$hdr_probe" = "true" ]; then
            record EXP-HLG D6 "HLG export offered iff it works" FAIL "the probe says HDR is supported but the exporter refused: $ex_result"
        else
            record EXP-HLG D6 "HLG export offered iff it works" PASS "this device cannot encode HLG and the probe says so ($hdr_probe)"
        fi
    else
        record EXP-HLG D6 "HLG export" FAIL "$ex_result"
    fi
}

# Deliberately damaged exports: the app's verification must flag each of them. The harness (QaExportActivity --es damage <mode>) damages the
# finished file between the end of the export and the verification, so a check that does not look at the file, or is skipped, shows here.
check_verify_damage() {
    want VERIFY-DAMAGE || return 0
    local mode bad="" good="" out
    for mode in zero2mb zerotail garble; do
        EXPORT_TIMEOUT=240 export_project "dmg_$mode" qaquick --es damage "$mode"
        if ! echo "$ex_result" | grep -q '^OK'; then bad="$bad $mode(export: $(echo "$ex_result" | cut -c1-60))"; continue; fi
        out="$(python3 "$qa/check-export.py" verification --result "$ex_result" --expect warning 2>&1 | tail -1)"
        case "$out" in OK*) good="$good $mode" ;; *) bad="$bad $mode(${out#FAIL })" ;; esac
    done
    if [ -z "$bad" ]; then
        record VERIFY-DAMAGE D10 "damaged exports are flagged by the verification" PASS "flagged:$good"
    else
        record VERIFY-DAMAGE D10 "damaged exports are flagged by the verification" FAIL "$(echo "$bad" | cut -c1-200)"
    fi
}

check_rotation() {
    want EXP-ROT || return 0
    export_project rot qarot
    if [ -z "$ex_file" ]; then record EXP-ROT D9 "rotation applied once" FAIL "export failed: $ex_result"; return 0; fi
    assert EXP-ROT D9 "rotation applied exactly once" rotation "$ex_file" --source "$media/rot90.mp4" --index 30
}

check_frame() {
    want FRAME || return 0
    local f="/sdcard/Android/data/$pkg/files" frames="0,37,59,60,90,119" n bad="" worst="" count=0
    adb_ shell rm -f "$f/frame_result.txt" "$f/frame_export.mp4" > /dev/null
    adb_ shell "rm -f $f/frame_*.jpg" > /dev/null
    start_activity FrameDemoActivity --es video "$dev/numbers.mp4" --es mode frames --es frames "$frames" --es format jpg
    if ! wait_for_file "$f/frame_result.txt" 90; then record FRAME D2 "saved frame matches the export" FAIL "FrameDemoActivity wrote no result"; return 0; fi
    sleep 1
    adb_ shell cat "$f/frame_result.txt" | tr -d '\r' > "$data/frame_result.txt"
    adb_ shell rm -f "$f/frame_result.txt" > /dev/null
    if grep -q FAILED "$data/frame_result.txt"; then record FRAME D2 "saved frame matches the export" FAIL "$(grep -m1 FAILED "$data/frame_result.txt" | cut -c1-140)"; return 0; fi
    start_activity FrameDemoActivity --es video "$dev/numbers.mp4" --es mode export
    if ! wait_for_file "$f/frame_result.txt" 120; then record FRAME D2 "saved frame matches the export" FAIL "the reference export wrote no result"; return 0; fi
    sleep 1
    adb_ pull "$f/frame_export.mp4" "$data/frame_export.mp4" > /dev/null 2>&1
    adb_ shell rm -f "$f/frame_result.txt" "$f/frame_export.mp4" > /dev/null
    local out
    for n in ${frames//,/ }; do
        adb_ pull "$f/frame_$n.jpg" "$data/frame_$n.jpg" > /dev/null 2>&1 || { bad="$bad $n(missing file)"; continue; }
        out="$(python3 "$qa/check-export.py" frame "$data/frame_$n.jpg" --reference "$data/frame_export.mp4" --index "$n" --min-psnr 40 2>&1 | tail -1)"
        count=$((count + 1))
        case "$out" in OK*) worst="${worst:+$worst, }$n:$(echo "${out##*PSNR }" | sed 's/ dB against.*/dB/')" ;; *) bad="$bad $n(${out#FAIL })" ;; esac
    done
    adb_ shell "rm -f $f/frame_*.jpg" > /dev/null
    if [ -z "$bad" ]; then
        record FRAME D2 "saved frames are not flat and match the export" PASS "$count frames of a 3-clip project; PSNR $(echo "$worst" | cut -c1-70)"
    else
        record FRAME D2 "saved frames are not flat and match the export" FAIL "$(echo "$bad" | cut -c1-180)"
    fi
}

# ---- uiautomator flows ------------------------------------------------------------------------------------------------------------
. "$root/scripts/device-ui.sh" 2>/dev/null
ui_ready=0
ui_prepare() {
    [ "$ui" = 1 ] || return 1
    [ "$ui_ready" = 1 ] && return 0
    case "$(focus)" in *NotificationShade*|*Keyguard*|*keyguard*) ui_wake ;; esac
    case "$(focus)" in *NotificationShade*|*Keyguard*|*keyguard*) say "the screen is locked: unlock $serial for the UI checks"; return 1 ;; esac
    ui_ready=1
}
# Only ever tap while one of our windows has the focus: another app in front means someone is using the phone.
in_front() { case "$(focus)" in *"$pkg/"*) return 0 ;; *) return 1 ;; esac; }
# The text of everything on screen as "text|selected|checked" lines. A Compose chip is a checkable container with the text in a child,
# so a text takes the selected / checked state of the nearest container that has one.
ui_screen() { ui_dump | python3 -c '
import sys, xml.etree.ElementTree as ET
try:
    root = ET.fromstring(sys.stdin.read())
except ET.ParseError:
    sys.exit(0)
def walk(node, selected, checked):
    selected = selected or node.get("selected") == "true"
    checked = checked or node.get("checked") == "true"
    value = node.get("text") or node.get("content-desc") or ""
    if value:
        print("%s|%s|%s" % (value, str(selected).lower(), str(checked).lower()))
    for child in node:
        # A container resets the inherited state only when it carries its own checkable flag.
        own = child.get("checkable") == "true"
        walk(child, False if own else selected, False if own else checked)
walk(root, False, False)
'; }

check_footer() {
    want UI-VER || return 0
    ui_prepare || { record UI-VER D7 "hub footer shows Engine v<versionName>" SKIP "no UI (--no-ui or locked screen)"; return 0; }
    ui_launch
    in_front || { record UI-VER D7 "hub footer shows Engine v<versionName>" SKIP "focus is $(focus | cut -c1-80)"; return 0; }
    local screen footer
    for _ in 1 2 3; do
        screen="$(ui_screen)"
        footer="$(echo "$screen" | grep -m1 '^Engine v' | cut -d'|' -f1)"
        [ -n "$footer" ] && break
        # The footer is at the bottom of the hub: scroll a little when it is not on the first screen.
        adb_ shell input swipe 540 1700 540 700 300; sleep 1
    done
    if [ "$footer" = "Engine v$versionName" ]; then
        record UI-VER D7 "hub footer shows Engine v<versionName>" PASS "$footer"
    else
        record UI-VER D7 "hub footer shows Engine v<versionName>" FAIL "footer '${footer:-not found}', expected 'Engine v$versionName'"
    fi
}

check_thumbnails() {
    want UI-THUMB || return 0
    ui_prepare || { record UI-THUMB D3 "photos get thumbnails, no error" SKIP "no UI (--no-ui or locked screen)"; return 0; }
    local stamp screen
    stamp="$(device_now)"
    ui_launch
    in_front || { record UI-THUMB D3 "photos get thumbnails, no error" SKIP "focus is $(focus | cut -c1-80)"; return 0; }
    if ! ui_open_project "QA photos"; then record UI-THUMB D3 "photos get thumbnails, no error" FAIL "could not open the seeded project 'QA photos' in the hub"; return 0; fi
    sleep 8
    in_front || { record UI-THUMB D3 "photos get thumbnails, no error" SKIP "focus left the app"; return 0; }
    screen="$(ui_screen)"
    local errs
    errs="$(log_since "$stamp" "$(pid_of)" | grep -E ' [WE] uv_thumb' | head -3)"
    if [ -n "$errs" ] || echo "$screen" | grep -q "Could not generate thumbnails\|No filmstrip for"; then
        record UI-THUMB D3 "photos get thumbnails, no error" FAIL "$(echo "${errs:-$(echo "$screen" | grep -m1 'thumbnails\|filmstrip')}" | head -1 | cut -c1-150)"
    else
        record UI-THUMB D3 "photos get thumbnails, no error" PASS "8 photo clips of 2 pictures opened, no uv_thumb warning, no error message"
    fi
}

check_export_dialog() {
    want UI-EXPORT || return 0
    local project="$1" label="$2" screen want_text
    ui_prepare || { record UI-EXPORT D6 "export dialog defaults ($label)" SKIP "no UI (--no-ui or locked screen)"; return 0; }
    ui_launch
    in_front || { record UI-EXPORT D6 "export dialog defaults ($label)" SKIP "focus is $(focus | cut -c1-80)"; return 0; }
    ui_open_project "$project" || { record UI-EXPORT D6 "export dialog defaults ($label)" FAIL "could not open '$project'"; return 0; }
    in_front || { record UI-EXPORT D6 "export dialog defaults ($label)" SKIP "focus left the app"; return 0; }
    ui_tap_text "Export movie" || { record UI-EXPORT D6 "export dialog defaults ($label)" FAIL "no Export button in the editor"; return 0; }
    sleep 2
    screen="$(ui_screen)"
    adb_ shell input keyevent KEYCODE_BACK
    echo "$screen" | grep -q '^Resolution|' || { record UI-EXPORT D6 "export dialog defaults ($label)" FAIL "the export dialog did not open"; return 0; }
    case "$label" in
        hlg)
            if echo "$screen" | grep -q "cannot encode HDR"; then
                if [ "$hdr_probe" = "true" ]; then record UI-EXPORT D6 "export dialog offers HDR for an HLG project" FAIL "dialog says the device cannot encode HDR but the probe says it can"
                else record UI-EXPORT D6 "export dialog offers HDR for an HLG project" PASS "device cannot encode HLG; the dialog says so (probe $hdr_probe)"; fi
            elif echo "$screen" | grep -q '^HDR (HLG, 10-bit HEVC)'; then
                if echo "$screen" | grep '^HDR (HLG, 10-bit HEVC)|' | grep -q '|true'; then
                    record UI-EXPORT D6 "export dialog offers HDR for an HLG project" PASS "HDR (HLG, 10-bit HEVC) is offered and selected by default"
                else
                    record UI-EXPORT D6 "export dialog offers HDR for an HLG project" FAIL "HDR is offered but not selected by default: $(echo "$screen" | grep '^HDR (HLG')"
                fi
            else
                record UI-EXPORT D6 "export dialog offers HDR for an HLG project" FAIL "no Dynamic range section in the dialog"
            fi ;;
        *)
            want_text="720p"
            if echo "$screen" | grep "^$want_text|" | grep -q 'true'; then
                record UI-EXPORT D6 "export dialog defaults ($label)" PASS "$want_text (the project's size) selected by default"
            else
                record UI-EXPORT D6 "export dialog defaults ($label)" FAIL "$want_text not selected: $(echo "$screen" | grep -m3 'p|' | tr '\n' ' ')"
            fi ;;
    esac
}

check_save_frame_ui() {
    want UI-FRAME || return 0
    local what="one-tap save frame writes a real picture"
    ui_prepare || { record UI-FRAME D2 "$what" SKIP "no UI (--no-ui or locked screen)"; return 0; }
    ui_launch
    in_front || { record UI-FRAME D2 "$what" SKIP "focus is $(focus | cut -c1-80)"; return 0; }
    ui_open_project "QA quick" || { record UI-FRAME D2 "$what" FAIL "could not open the seeded project 'QA quick'"; return 0; }
    in_front || { record UI-FRAME D2 "$what" SKIP "focus left the app"; return 0; }
    local folder=/sdcard/Pictures/ultimateVE before after new
    before="$(adb_ shell ls "$folder" 2>/dev/null | tr -d '\r')"
    ui_tap_text "Save frame as image" || { record UI-FRAME D2 "$what" FAIL "no Save frame button in the editor"; return 0; }
    sleep 6
    after="$(adb_ shell ls "$folder" 2>/dev/null | tr -d '\r')"
    new="$(comm -13 <(echo "$before" | sort) <(echo "$after" | sort) | grep -i '^QA' | head -1)"
    if [ -z "$new" ]; then
        record UI-FRAME D2 "$what" FAIL "no new picture in $folder after the tap (screen: $(ui_screen | grep -i -m1 'frame\|flat\|nothing' | cut -c1-100))"
        return 0
    fi
    adb_ pull "$folder/$new" "$data/saved_frame.jpg" > /dev/null 2>&1
    # Only the file this run made is removed (the folder is shared with the real app's saves).
    adb_ shell rm -f "$folder/$new" > /dev/null
    assert UI-FRAME D2 "$what" frame "$data/saved_frame.jpg" --reference "$media/vfr27.mp4" --index 0 --min-psnr 18
}


# A real one-finger stream (scripts/qa/touch/Touch.java; `adb shell input` cannot hold before it moves). Arguments: x1 y1 x2 y2 holdMs moveMs.
touch_jar=""
touch() {
    if [ -z "$touch_jar" ]; then
        touch_jar="$("$qa/touch/build.sh" "$data/touch" 2> /dev/null | tail -1)"
        [ -s "$touch_jar" ] && adb_ push "$touch_jar" /data/local/tmp/uv-touch.jar > /dev/null
    fi
    adb_ shell "app_process -Djava.class.path=/data/local/tmp/uv-touch.jar /system/bin Touch $*"
}
# Resets the seeded "QA drag" project (the checks edit it) and opens it in the editor with the tray collapsed.
open_qadrag() {
    adb_ shell am force-stop "$pkg" > /dev/null
    adb_ push "$data/projects/qadrag.json" "$dev/qadrag.json" > /dev/null
    adb_ shell "cat $dev/qadrag.json | run-as $pkg sh -c 'mkdir -p files/projects/qadrag && cat > files/projects/qadrag/project.json'"
    ui_launch
    in_front && ui_open_project "QA drag" && in_front
}
# "c0:0 c1:60 ..." (clip id and start frame) from the project the editor saved.
drag_state() {
    adb_ shell "run-as $pkg cat files/projects/qadrag/project.json" 2> /dev/null | python3 -c '
import json, sys
p = json.load(sys.stdin)
print(" ".join("%s:%d" % (c["id"], c["timelineStartFrame"]) for t in p["tracks"] for c in t["clips"]))'
}
# Geometry of the editor on a phone with the tray collapsed, from the timeline canvas (the lower SurfaceView): the overlay
# lane's clip c3 (frames 60-90) is at 40 % of the width and 170 dp above the canvas bottom. Sets tl_l tl_t tl_r tl_b cx cy.
drag_geometry() {
    local b density
    b="$(ui_dump | grep -o 'class="android.view.SurfaceView"[^>]*bounds="[^"]*"' | grep -o 'bounds="[^"]*"' | tail -1 | tr -dc '0-9,[]' | tr '][' ',,' | tr -s ',' | sed 's/^,//; s/,$//')"
    [ -n "$b" ] || return 1
    IFS=, read -r tl_l tl_t tl_r tl_b <<< "$b"
    density="$(adb_ shell wm density | grep -o '[0-9]*$' | tail -1)"
    cx=$((tl_l + (tl_r - tl_l) * 40 / 100))
    cy=$((tl_b - 170 * density / 160))
}

check_drag_clip() {
    want UI-DRAG-CLIP || return 0
    local what="a clip moves with hold-and-drag, and a selected clip with a plain drag"
    ui_prepare || { record UI-DRAG-CLIP D8 "$what" SKIP "no UI (--no-ui or locked screen)"; return 0; }
    open_qadrag || { record UI-DRAG-CLIP D8 "$what" SKIP "could not open the seeded project 'QA drag' (focus $(focus | cut -c1-80))"; return 0; }
    drag_geometry || { record UI-DRAG-CLIP D8 "$what" SKIP "no timeline canvas found in the UI dump"; return 0; }
    local dx=$(((tl_r - tl_l) * 28 / 100)) before after_hold after_back c3_before c3_hold c3_back
    before="$(drag_state)"
    # Hold (past the long-press timeout) on the unselected clip, then drag it to the right without lifting.
    touch "$cx" "$cy" "$((cx + dx))" "$cy" 900 700 || true
    sleep 4
    after_hold="$(drag_state)"
    # It is selected now: a plain drag (no hold) brings it back. The finger lands near the middle of the clip (a touch within
    # about 24 dp of an end would trim it instead).
    touch "$((cx + dx + dx / 6))" "$cy" "$((cx + dx / 6))" "$cy" 0 600 || true
    sleep 4
    after_back="$(drag_state)"
    c3_before="$(echo "$before" | tr ' ' '\n' | grep '^c3:')"
    c3_hold="$(echo "$after_hold" | tr ' ' '\n' | grep '^c3:')"
    c3_back="$(echo "$after_back" | tr ' ' '\n' | grep '^c3:')"
    if [ -z "$c3_before" ]; then
        record UI-DRAG-CLIP D8 "$what" SKIP "the seeded project could not be read back"
    elif [ "$c3_hold" = "$c3_before" ]; then
        record UI-DRAG-CLIP D8 "$what" FAIL "hold then drag left the clip at $c3_before (it must move right)"
    elif [ "${c3_back#c3:}" -ge "${c3_hold#c3:}" ]; then
        record UI-DRAG-CLIP D8 "$what" FAIL "after the hold-drag ($c3_hold) a plain drag to the left gave $c3_back (it must move left)"
    else
        record UI-DRAG-CLIP D8 "$what" PASS "c3 starts at frame ${c3_before#c3:}, ${c3_hold#c3:} after hold and drag right, ${c3_back#c3:} after a plain drag left"
    fi
}

# ---- run ------------------------------------------------------------------------------------------------------------------------
adb_ shell input keyevent KEYCODE_WAKEUP > /dev/null 2>&1
need_files
want HYG-1 && check_hygiene HYG-1
check_version
check_probe

check_project_export Q qaquick 180 60 3 1 "PCM-s16le:1 AAC-VFR27:0"
if full; then
    # 8 clips of 2 s at 60 fps; clip order: vfr30 vfr27 cfr60 pcm16le pcm16be pcm24le aac hevc
    check_project_export M qamixed 960 60 8 2 "AAC-VFR30:0 AAC-VFR27:1 AAC-CFR60:2 PCM-s16le:3 PCM-s16be:4 PCM-s24le:5 AAC:6 AAC-HEVC:7"
    if want EXP-BUDGET && [ -n "${ex_file:-}" ]; then
        budget=120
        if awk "BEGIN { exit !($ex_seconds < $budget) }"; then
            record EXP-BUDGET D5 "mixed 30/27/60 fps export in time" PASS "16 s of 60 fps 720p exported in ${ex_seconds} s (budget ${budget} s)"
        else
            record EXP-BUDGET D5 "mixed 30/27/60 fps export in time" FAIL "took ${ex_seconds} s, budget ${budget} s (a seek per missing frame crawls)"
        fi
    fi
fi
check_hlg
if full; then check_rotation; check_frame; check_verify_damage; fi
check_footer
check_thumbnails
check_save_frame_ui
check_drag_clip
if full; then
    check_export_dialog "QA quick" sdr
    check_export_dialog "QA HLG" hlg
fi

# ---- report ---------------------------------------------------------------------------------------------------------------------
fails=0 passes=0 skips=0
{
    echo
    echo "ultimateVE QA smoke: $model, Android $(adb_ shell getprop ro.build.version.release | tr -d '\r'), $pkg $installed, $([ "$quick" = 1 ] && echo quick || echo full)"
    printf '%-16s %-6s %-5s %s\n' CHECK DEFECT RESULT DETAIL
    for i in "${!R_ID[@]}"; do
        printf '%-16s %-6s %-5s %s -- %s\n' "${R_ID[$i]}" "${R_REF[$i]}" "${R_STATUS[$i]}" "${R_TITLE[$i]}" "$(echo "${R_DETAIL[$i]}" | cut -c1-200)"
        case "${R_STATUS[$i]}" in PASS) passes=$((passes + 1)) ;; FAIL) fails=$((fails + 1)) ;; *) skips=$((skips + 1)) ;; esac
    done
    echo "RESULT: $passes passed, $fails failed, $skips skipped"
} | tee -a "$log" > "$data/last-result.txt"
cat "$data/last-result.txt"
[ "$fails" -eq 0 ]

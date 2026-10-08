#!/usr/bin/env bash
# Hidden-layer skipping (SPECS 5.10): parity and timing through the debug harness (ExportDemoActivity, layout cover).
#   scripts/check-hidden-layers.sh <serial> parity [case...]        exports each case with skipping on and off, compares framemd5
#   scripts/check-hidden-layers.sh <serial> time <source> [runs]    4K source, full cover, on/off interleaved, prints the result and skip count
#                                                                    (FRAMES=n, COLOR=1 for an HLG source)
# Needs ffmpeg on the host and a debug build with `-Puveditor.appIdSuffix=<x>` installed (PKG, default com.qtekfun.ultimatevideoeditor.oc);
# run-as is used because the harness cannot read a directory the shell made under Android/data. Each export holds
# /tmp/pixel-device.lock (or $LOCK) for its whole run, notes which window had focus afterwards, and sets no system property.
# Work files go to $WORK (default /home/qtekfun/uvdata/gp, not /tmp).
set -euo pipefail
serial="$1"
mode="$2"
shift 2
pkg="${PKG:-com.qtekfun.ultimatevideoeditor.oc}"
lock="${LOCK:-/tmp/pixel-device.lock}"
work="${WORK:-/home/qtekfun/uvdata/gp}"
adb="adb -s $serial"
ra="$adb shell run-as $pkg"

# put <local> <name>: copies a file into the app's private files directory.
put() {
    $adb push -q "$1" "/data/local/tmp/uv_$2"
    $ra mkdir -p files
    $ra cp "/data/local/tmp/uv_$2" "files/$2"
    $adb shell rm -f "/data/local/tmp/uv_$2"
}
get() { $adb exec-out run-as "$pkg" cat "files/$1" > "$2"; }

# run_export <out name> <am args...>: one export under the lock; waits for the result file.
run_export() {
    local name="$1"
    shift
    local dir="/data/data/$pkg/files"
    flock "$lock" bash -c "
        $ra rm -f 'files/$name' 'files/$name.result.txt'
        $adb logcat -c
        $adb shell am start -W -n '$pkg/com.qtekfun.ultimatevideoeditor.debug.ExportDemoActivity' --es video '$dir/in.mp4' --es out '$dir/$name' $* > /dev/null
        for _ in \$(seq 1 900); do $ra test -f 'files/$name.result.txt' && break; sleep 1; done
        $ra cat 'files/$name.result.txt'
        $adb logcat -d | grep -E 'export done|hidden layers are drawn|UVExportPerf (section|summary)' || true
        $adb shell dumpsys window | grep -m1 mCurrentFocus
    "
}

case "$mode" in
parity)
    cases=("$@")
    [ ${#cases[@]} -gt 0 ] || cases=(full resume small fade opacity short zoom aspect)
    src="$work/parity_src.mp4"
    [ -f "$src" ] || ffmpeg -loglevel error -y -f lavfi -i "testsrc2=size=1920x1080:rate=30" -f lavfi -i "sine=frequency=440:sample_rate=48000" \
        -t 20 -c:v libx264 -preset veryfast -b:v 20M -pix_fmt yuv420p -g 30 -keyint_min 30 -sc_threshold 0 -bf 2 -c:a aac -b:a 128k "$src"
    put "$src" in.mp4
    for c in "${cases[@]}"; do
        extra="--es layout cover --es case $c --ei w 1280 --ei h 720 --ei fps 30 --ei frames 240 --ez audio false"
        [ "$c" = aspect ] && extra="--es layout cover --es case full --ei w 720 --ei h 720 --ei cw 720 --ei ch 720 --ei fps 30 --ei frames 240 --ez audio false"
        echo "== case $c"
        run_export "cov_on.mp4" "$extra"
        run_export "cov_off.mp4" "$extra --ez keep_hidden true"
        get cov_on.mp4 "$work/cov_on_$c.mp4"
        get cov_off.mp4 "$work/cov_off_$c.mp4"
        ffmpeg -loglevel error -y -i "$work/cov_on_$c.mp4" -an -f framemd5 "$work/cov_on_$c.md5"
        ffmpeg -loglevel error -y -i "$work/cov_off_$c.mp4" -an -f framemd5 "$work/cov_off_$c.md5"
        if diff -q <(grep -v '^#' "$work/cov_on_$c.md5") <(grep -v '^#' "$work/cov_off_$c.md5") > /dev/null; then
            echo "PARITY $c: identical ($(grep -vc '^#' "$work/cov_on_$c.md5") frames)"
        else
            echo "PARITY $c: DIFFERENT"
            # The hardware encoder is not always deterministic (a run of identical code can differ too): say how far apart they are.
            ffmpeg -loglevel error -y -i "$work/cov_on_$c.mp4" -i "$work/cov_off_$c.mp4" -lavfi "psnr=stats_file=$work/cov_psnr_$c.txt" -f null -
            echo "  frames differing: $(grep -vc 'psnr_avg:inf' "$work/cov_psnr_$c.txt"), lowest PSNR: $(grep -v 'psnr_avg:inf' "$work/cov_psnr_$c.txt" | sed -n 's/.*psnr_avg:\([0-9.]*\) .*/\1/p' | sort -n | head -1) dB"
        fi
    done
    ;;
time)
    src="$1"
    runs="${2:-2}"
    put "$src" in.mp4
    for r in $(seq 1 "$runs"); do
        for variant in off on; do
            keep=""
            [ "$variant" = off ] && keep="--ez keep_hidden true"
            echo "== run $r skipping $variant"
            run_export "t_$variant.mp4" "--es layout cover --es case full --es codec hevc --ei w 3840 --ei h 2160 --ei fps 60 --ei frames ${FRAMES:-1200} --ei bitrate 35 --ez audio false ${COLOR:+--ei color $COLOR} ${EXTRA:-} $keep"
        done
    done
    ;;
*)
    echo "usage: $0 <serial> parity|time ..." >&2
    exit 2
    ;;
esac

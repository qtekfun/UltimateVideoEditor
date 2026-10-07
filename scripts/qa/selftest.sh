#!/usr/bin/env bash
# Proves that the assertions of scripts/qa-smoke.sh can fail: feeds scripts/qa/check-export.py good and deliberately broken files
# (made with ffmpeg) and expects PASS for the good ones and FAIL for each broken one. A runner whose checks cannot fail is worse than
# none. No device needed; skipped (exit 0) when ffmpeg is not installed. ~15 s.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
command -v ffmpeg > /dev/null && command -v ffprobe > /dev/null || { echo "selftest skipped: ffmpeg is not installed"; exit 0; }
work="$(mktemp -d "${TMPDIR:-/tmp}/uv-qa-selftest.XXXXXX")"
trap 'rm -rf "$work"' EXIT
check="$here/check-export.py"
fails=0
ff() { ffmpeg -v error -y "$@"; }

# expect <pass|fail> <name> <checker args...>
expect() {
    local want="$1" name="$2" out code
    shift 2
    out="$(python3 "$check" "$@" 2>&1 | tail -1)"
    case "$out" in OK*) code=pass ;; *) code=fail ;; esac
    if [ "$code" = "$want" ]; then
        echo "ok    $name ($out)"
    else
        echo "WRONG $name: wanted $want, got: $out"
        fails=$((fails + 1))
    fi
}

tone="sine=frequency=440:sample_rate=48000:duration=2"
pic="testsrc2=size=320x180:rate=30:duration=2"
ff -f lavfi -i "$pic" -f lavfi -i "$tone" -c:v libx264 -pix_fmt yuv420p -x264-params colorprim=bt709:transfer=bt709:colormatrix=bt709:range=tv -c:a aac -shortest "$work/good.mp4"
ff -f lavfi -i "$pic" -an -c:v libx264 -pix_fmt yuv420p "$work/noaudio.mp4"
ff -f lavfi -i "$pic" -f lavfi -i "$tone,volume=0" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$work/silent.mp4"
ff -f lavfi -i "testsrc2=size=320x180:rate=30:duration=1.5" -f lavfi -i "$tone" -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest "$work/short.mp4"
ff -f lavfi -i "$pic" -vf "setpts=PTS*0+0" -fps_mode passthrough -c:v libx264 -pix_fmt yuv420p "$work/flatpts.mp4" 2> /dev/null
ff -f lavfi -i "$pic" -c:v libx264 -pix_fmt yuv420p "$work/untagged.mp4"

expect pass "good file: frames, spacing and sound" av "$work/good.mp4" --frames 60 --fps 30 --audio yes --segments 0:2
expect fail "missing audio stream is caught" av "$work/noaudio.mp4" --frames 60 --fps 30 --audio yes --segments 0:2
expect fail "silent audio is caught" av "$work/silent.mp4" --frames 60 --fps 30 --audio yes --segments 0:2
expect fail "wrong frame count is caught" av "$work/short.mp4" --frames 60 --fps 30
expect fail "a different frame rate is caught" av "$work/good.mp4" --frames 60 --fps 25
expect pass "audio over a segment" audio "$work/good.mp4" --segment 0:2
expect fail "silent segment" audio "$work/silent.mp4" --segment 0:2
expect fail "no audio stream, audio check" audio "$work/noaudio.mp4" --segment 0:2

expect pass "SDR tags" tags "$work/good.mp4" --kind sdr
expect fail "untagged file is not SDR-tagged" tags "$work/untagged.mp4" --kind sdr
ff -f lavfi -i "$pic,format=yuv420p10le" -c:v libx265 -pix_fmt yuv420p10le -tag:v hvc1 -x265-params "colorprim=bt2020:transfer=arib-std-b67:colormatrix=bt2020nc:range=limited:log-level=error" \
    -color_primaries bt2020 -color_trc arib-std-b67 -colorspace bt2020nc -color_range tv "$work/hlg.mp4"
ff -f lavfi -i "$pic" -c:v libx265 -pix_fmt yuv420p -tag:v hvc1 -x265-params log-level=error -colorspace bt709 -color_trc bt709 -color_primaries bt709 -color_range tv "$work/hevc8.mp4"
expect pass "HLG tags, Main 10, hvc1" tags "$work/hlg.mp4" --kind hlg
expect fail "8-bit SDR HEVC is not HLG" tags "$work/hevc8.mp4" --kind hlg
expect fail "HLG file is not SDR" tags "$work/hlg.mp4" --kind sdr

# Rotation: a landscape file with a 90 degree container rotation, exported as portrait pictures.
ff -f lavfi -i "testsrc2=size=320x180:rate=30:duration=2" -c:v libx264 -pix_fmt yuv420p "$work/land.mp4"
ff -display_rotation 90 -i "$work/land.mp4" -c copy "$work/rot.mp4"
ff -i "$work/rot.mp4" -vf scale=360:640 -c:v libx264 -pix_fmt yuv420p "$work/rot_once.mp4"
# The unrotated landscape picture stretched to portrait: what an export that ignored the container rotation looks like.
# (Built from the original file, not with -noautorotate: that flag behaves differently across ffmpeg versions and made CI flaky.)
ff -i "$work/land.mp4" -vf scale=360:640 -c:v libx264 -pix_fmt yuv420p "$work/rot_never.mp4"
ff -i "$work/rot.mp4" -vf "scale=360:640,hflip,vflip" -c:v libx264 -pix_fmt yuv420p "$work/rot_twice.mp4"
expect pass "rotated once" rotation "$work/rot_once.mp4" --source "$work/rot.mp4" --index 30
expect fail "container rotation ignored" rotation "$work/rot_never.mp4" --source "$work/rot.mp4" --index 30
expect fail "turned the wrong way" rotation "$work/rot_twice.mp4" --source "$work/rot.mp4" --index 30

# Saved frame: a flat (black) picture, which is what the user got, and a real one.
ff -f lavfi -i "color=c=black:s=320x180" -frames:v 1 "$work/black.jpg"
ff -f lavfi -i "color=c=0x303030:s=320x180" -frames:v 1 "$work/grey.jpg"
ff -i "$work/good.mp4" -vf "select=eq(n\,10)" -frames:v 1 -q:v 2 "$work/real.jpg"
expect pass "a real saved frame matches the video" frame "$work/real.jpg" --reference "$work/good.mp4" --index 10
expect fail "a black picture is flat" frame "$work/black.jpg" --reference "$work/good.mp4" --index 10
expect fail "a grey picture is flat" flat "$work/grey.jpg"
expect fail "a picture of another frame does not match" frame "$work/real.jpg" --reference "$work/good.mp4" --index 50 --min-psnr 30
expect pass "a real picture is not flat" flat "$work/real.jpg"

# Engine log: seeks and stalls.
{ for i in $(seq 1 3); do echo "10-07 07:00:00.000 1 2 I uveditor: seek: missing=$i decodePos=0 target=$i primed=0 awaiting=0"; done; echo "export done: 180 frames in 1369 ms"; } > "$work/good.log"
{ for i in $(seq 1 300); do echo "10-07 07:00:00.000 1 2 I uveditor: seek: missing=$i decodePos=0 target=$i primed=1 awaiting=0"; done; } > "$work/thrash.log"
echo "10-07 07:00:00.000 1 2 E uveditor: decoder stalled: wanted 5; have 1,2" > "$work/stalled.log"
expect pass "a few seeks" seeks "$work/good.log" --seconds 6 --clips 3
expect fail "a seek per missing frame" seeks "$work/thrash.log" --seconds 6 --clips 3
expect fail "a decoder stall" seeks "$work/stalled.log" --seconds 6 --clips 3

# The app's post-export verification: it must say verified for a good file, and a damaged file must not get past it.
good_line='OK frames=180 audio=true verification=verified frames=180 probes=24 decoded=210 took_ms=900 headline="Checked: the video is complete (180 frames, 00:03)" in 4.1 s, 100 bytes'
expect pass "verified result is accepted" verification --result "$good_line" --expect verified
expect fail "a skipped verification is not verified" verification --result 'OK frames=180 verification=skipped' --expect verified
expect fail "a missing verification is caught" verification --result 'OK frames=180 audio=true' --expect verified
expect fail "could not verify is not verified" verification --result 'OK frames=180 verification=could_not_verify reason=no decoder' --expect verified
expect fail "a warning on a good export fails the run" verification --result 'OK frames=180 verification=warning tail_frames=20 checks=decode' --expect verified
expect pass "a damaged file that was flagged" verification --result 'OK frames=180 verification=warning tail_frames=45 checks=sample_data damage=zerotail' --expect warning
expect fail "a damaged file that passed is the worst outcome" verification --result 'OK frames=180 verification=verified frames=180 damage=zero2mb' --expect warning
expect fail "a damaged file with the check skipped" verification --result 'OK frames=180 verification=skipped damage=truncate' --expect warning
expect fail "a damaged file with no verification at all" verification --result 'OK frames=180 damage=truncate' --expect warning

if [ "$fails" -eq 0 ]; then echo "selftest passed: every assertion passes the good input and fails the broken one"; else echo "selftest FAILED: $fails wrong verdict(s)"; fi
[ "$fails" -eq 0 ]

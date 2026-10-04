#!/usr/bin/env bash
# Runs the audio core host tests under AddressSanitizer + UBSan and under ThreadSanitizer.
#
# They guard the lifetime rules of the real-time audio path (the audio thread must never touch a snapshot that was
# freed while no stream ran: a crash seen on a device). The sanitizer runtimes are not installed everywhere, so this
# script skips a sanitizer whose runtime cannot be linked, unless UV_REQUIRE_SANITIZERS=1 (CI sets it).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
src="$root/app/src/main/cpp"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

files=(
    "$src/tests/audio_host_tests.cpp"
    "$src/core/error.cpp"
    "$src/audio/analysis.cpp"
    "$src/audio/audio_core.cpp"
    "$src/audio/audio_mixer.cpp"
    "$src/audio/audio_snapshot.cpp"
    "$src/audio/clip_buffer.cpp"
    "$src/audio/dsp.cpp"
    "$src/audio/loudness.cpp"
    "$src/audio/resampler.cpp"
    "$src/audio/retime_source.cpp"
    "$src/audio/spectral_denoise.cpp"
    "$src/audio/voice_fx.cpp"
)

status=0
run() {
    local name="$1" flags="$2"
    if ! g++ -std=c++20 -O1 -g -fno-omit-frame-pointer -I"$src" $flags "${files[@]}" -o "$out/$name" -pthread 2>"$out/$name.log"; then
        if [[ "${UV_REQUIRE_SANITIZERS:-0}" == "1" ]]; then
            echo "FAILED to build the $name host tests:"
            cat "$out/$name.log"
            status=1
        else
            echo "skipping $name: the sanitizer runtime is not available here"
        fi
        return
    fi
    echo "running audio host tests under $name"
    if ! "$out/$name"; then
        echo "FAILED: $name"
        status=1
    fi
}

run asan "-fsanitize=address,undefined -fno-sanitize-recover=undefined"
run tsan "-fsanitize=thread"
exit "$status"

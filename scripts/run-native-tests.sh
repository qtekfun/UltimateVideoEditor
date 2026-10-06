#!/usr/bin/env bash
# Builds and runs the host-side native unit tests (no NDK needed).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
g++ -std=c++20 -Wall -Wextra -Werror -O1 -I"$root/app/src/main/cpp" \
    "$root/app/src/test/cpp/native_tests.cpp" -o "$out/native_tests" -pthread
"$out/native_tests"
# Deterministic simulation of the decoder worker and a sequential export consumer (long-GOP regression guard).
g++ -std=c++20 -Wall -Wextra -Werror -O1 -I"$root/app/src/main/cpp" \
    "$root/app/src/test/cpp/decode_sim_tests.cpp" -o "$out/decode_sim_tests"
"$out/decode_sim_tests"
# FFmpeg fallback: decoder selection, CPU budget, exact timestamp mapping and the worker's seek planning (no libav needed).
g++ -std=c++20 -Wall -Wextra -Werror -O1 -I"$root/app/src/main/cpp" \
    "$root/app/src/test/cpp/ffmpeg_selection_tests.cpp" -o "$out/ffmpeg_selection_tests"
"$out/ffmpeg_selection_tests"
# Uncompressed (iPhone 'lpcm') audio in QuickTime files: sample tables, sample formats, seeking.
g++ -std=c++20 -Wall -Wextra -Werror -O1 -I"$root/app/src/main/cpp" \
    "$root/app/src/main/cpp/tests/mov_pcm_host_tests.cpp" "$root/app/src/main/cpp/audio/mov_pcm.cpp" "$root/app/src/main/cpp/core/error.cpp" \
    -o "$out/mov_pcm_host_tests"
"$out/mov_pcm_host_tests"

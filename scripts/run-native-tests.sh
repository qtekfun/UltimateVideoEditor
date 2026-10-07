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
# Smart export: HEVC NAL edits, GOP trimming, the planner's disqualifiers, the MP4 writer and reader.
g++ -std=c++20 -Wall -Wextra -Werror -O1 -I"$root/app/src/main/cpp" \
    "$root/app/src/main/cpp/tests/smart_export_host_tests.cpp" "$root/app/src/main/cpp/encode/mp4_writer.cpp" \
    "$root/app/src/main/cpp/encode/mp4_source.cpp" "$root/app/src/main/cpp/encode/smart_source.cpp" -o "$out/smart_export_host_tests"
"$out/smart_export_host_tests"
# The engine version is the one in gradle/version.properties (the hub footer once showed a hard-coded 0.1.0).
version="$(sed -n 's/^versionName=//p' "$root/gradle/version.properties" | tr -d '[:space:]')"
for define in "-DUV_ENGINE_VERSION=\"$version\"" "-DUV_NO_ENGINE_VERSION"; do
    g++ -std=c++20 -Wall -Wextra -Werror -O1 -I"$root/app/src/main/cpp" -DEXPECTED_VERSION="\"$version\"" "$define" \
        "$root/app/src/test/cpp/engine_version_tests.cpp" "$root/app/src/main/cpp/core/engine_version.cpp" -o "$out/engine_version_tests"
    "$out/engine_version_tests"
done

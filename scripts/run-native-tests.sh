#!/usr/bin/env bash
# Builds and runs the host-side native unit tests (no NDK needed).
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
g++ -std=c++20 -Wall -Wextra -Werror -O1 -I"$root/app/src/main/cpp" \
    "$root/app/src/test/cpp/native_tests.cpp" -o "$out/native_tests" -pthread
"$out/native_tests"
